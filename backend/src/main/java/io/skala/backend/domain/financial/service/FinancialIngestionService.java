package io.skala.backend.domain.financial.service;

import io.skala.backend.domain.financial.dto.IngestionDiagnostic;
import io.skala.backend.domain.financial.dto.IngestionResult;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData.Line;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData.Value;
import io.skala.backend.domain.financial.entity.AccountItem;
import io.skala.backend.domain.financial.entity.FinancialLine;
import io.skala.backend.domain.financial.entity.FinancialValue;
import io.skala.backend.domain.financial.entity.ReportItem;
import io.skala.backend.domain.financial.repository.AccountItemRepository;
import io.skala.backend.domain.financial.repository.FinancialLineRepository;
import io.skala.backend.domain.financial.repository.FinancialValueRepository;
import io.skala.backend.domain.financial.repository.ReportItemRepository;
import io.skala.backend.domain.financial.value.AccountSourceType;
import io.skala.backend.domain.financial.value.ReportItemKind;
import io.skala.backend.domain.financial.value.Scenario;
import io.skala.backend.domain.submission.entity.Submission;
import io.skala.backend.domain.user.entity.Organization;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 파서 결과(submissionData)를 financial_line·financial_value에 적재하고 account_item·report_item에 연결한다.
 *
 * <p>파서 계약상 호출자(백엔드)가 확인해야 하는 것: 원본 해시, 조직 일치, 요청 연도 밖 기간, 보고 항목 미연결,
 * 중복 상세 ID, 미확정 금액. 요청 연도보다 이른 연도 열(양식의 전년 실적 등)은 참고값으로 저장하고 오류로 보지 않는다.
 * 검토(변경 감지·Gap)는 요청 연도 금액만 쓴다. 이 검사에서 걸린 항목은 {@link IngestionDiagnostic}으로 돌려주고, 저장할 수 있는
 * 행·금액만 저장한다. 빈 금액(MISSING)은 저장하지 않으며 0으로 바꾸지 않는다.
 */
@Service
@RequiredArgsConstructor
public class FinancialIngestionService {

    static final String SUPPORTED_ADAPTER = "financial-submission/1.0";
    /** financial_value.year 하한(DB 검사 조건) */
    private static final int MIN_YEAR = 2000;

    private final ReportItemRepository reportItemRepository;
    private final AccountItemRepository accountItemRepository;
    private final FinancialLineRepository financialLineRepository;
    private final FinancialValueRepository financialValueRepository;

    /**
     * @param submission   적재 대상 DRAFT 제출본
     * @param organization 제출 조직(파서가 확인한 소유 조직과 대조)
     * @param requestYear  요청 연도. 이후 연도 금액이나 요청 연도 금액 열이 하나도 없는 파일은 오류로 본다
     * @param data         파서 submissionData
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public IngestionResult ingest(Submission submission, Organization organization, int requestYear,
                                  ParserSubmissionData data) {
        List<IngestionDiagnostic> diagnostics = new ArrayList<>();
        checkEnvelope(submission, organization, data, diagnostics);
        copyParserErrors(data, diagnostics);

        // 재파싱이면 이전 적재분을 지우고 새로 넣는다.
        financialValueRepository.deleteAllBySubmissionId(submission.getId());
        financialLineRepository.deleteAllBySubmissionId(submission.getId());

        Map<String, ReportItem> reportItems = resolveReportItems(data.reportItems());
        Map<String, AccountItem> accounts = new HashMap<>();
        Set<String> seenDetails = new HashSet<>();
        Set<String> seenRows = new HashSet<>();
        List<FinancialValue> values = new ArrayList<>();
        int lineCount = 0;
        boolean hasRequestYear = false;

        for (Line line : data.lines()) {
            String rowRef = line.source() == null ? null : line.source().sheet() + "!" + line.source().row();
            if (line.source() == null || line.source().sheet() == null || line.source().row() == null
                    || line.source().row() < 1 || !seenRows.add(rowRef)) {
                diagnostics.add(new IngestionDiagnostic(rowRef, accountName(line), "LINE_SOURCE_INVALID",
                        "행 위치를 확인할 수 없습니다. 양식을 바꾸지 않았는지 확인해 주세요."));
                continue;
            }
            ReportItem reportItem = line.reportItemKey() == null ? null
                    : reportItems.get(ReportItemCodes.shorten(line.reportItemKey()));
            if (reportItem == null) {
                // 파서가 REPORT_MAPPING_UNRESOLVED 진단을 이미 내므로 중복 진단은 만들지 않는다.
                continue;
            }
            if (line.detailId() != null
                    && !seenDetails.add(line.detailId() + "\u0000" + Objects.toString(line.detailRevision(), ""))) {
                diagnostics.add(new IngestionDiagnostic(rowRef, accountName(line), "DUPLICATE_DETAIL_ID",
                        "같은 상세 ID가 여러 행에 있습니다. 중복 행을 정리해 주세요."));
                continue;
            }

            FinancialLine saved = financialLineRepository.save(FinancialLine.builder()
                    .submission(submission)
                    .accountItem(resolveAccount(line, accounts))
                    .reportItem(reportItem)
                    .detailId(truncate(line.detailId(), 500))
                    .detailRevision(truncate(line.detailRevision(), 80))
                    .teamCode(truncate(line.teamCode(), 160))
                    .taskCode(truncate(line.taskCode(), 160))
                    .managementCategory(truncate(line.managementCategory(), 500))
                    .actualBasis(truncate(line.actualBasis(), 500))
                    .costType(truncate(line.costType(), 500))
                    .description(line.description())
                    // 이름·담당자는 파서가 준 그대로 저장한다. 본부 코드는 파서가 주지 않으므로 이름으로 추정하지 않는다(#58).
                    .headquartersName(truncate(line.headquartersName(), 500))
                    .teamName(truncate(line.teamName(), 500))
                    .taskName(truncate(line.taskName(), 500))
                    .personInCharge(truncate(line.personInCharge(), 500))
                    .sourceSheet(truncate(line.source().sheet(), 255))
                    .sourceRow(line.source().row())
                    .build());
            lineCount++;
            hasRequestYear |= collectValues(saved, line, requestYear, values, diagnostics);
        }
        if (lineCount > 0 && !hasRequestYear) {
            // 이전 연도 열만 있으면 다른 연도 양식이다.
            diagnostics.add(new IngestionDiagnostic(null, null, "PERIOD_OUT_OF_RANGE",
                    "요청 연도(" + requestYear + "년) 금액 열이 없습니다. 요청한 연도의 양식인지 확인해 주세요."));
        }
        financialValueRepository.saveAll(values);
        return new IngestionResult(lineCount, values.size(), diagnostics);
    }

    private void checkEnvelope(Submission submission, Organization organization, ParserSubmissionData data,
                               List<IngestionDiagnostic> diagnostics) {
        if (!SUPPORTED_ADAPTER.equals(data.adapterVersion())) {
            diagnostics.add(new IngestionDiagnostic(null, null, "UNSUPPORTED_PARSER_RESULT",
                    "파일을 해석할 수 없습니다. 다시 업로드해 주세요."));
        }
        if (data.sourceSha256() == null || !data.sourceSha256().equalsIgnoreCase(submission.getSourceSha256())) {
            diagnostics.add(new IngestionDiagnostic(null, null, "SOURCE_HASH_MISMATCH",
                    "업로드한 파일과 해석 결과가 다릅니다. 파싱을 다시 시도해 주세요."));
        }
        ParserSubmissionData.Owner owner = data.owner();
        boolean ownerMatches = owner != null && "RESOLVED".equals(owner.status())
                && (Objects.equals(owner.code(), organization.getCode())
                || Objects.equals(owner.name(), organization.getName()));
        if (!ownerMatches) {
            diagnostics.add(new IngestionDiagnostic(null, null, "ORGANIZATION_MISMATCH",
                    "파일의 조직이 로그인한 조직과 다릅니다. 내 조직 양식으로 다시 업로드해 주세요."));
        }
    }

    private void copyParserErrors(ParserSubmissionData data, List<IngestionDiagnostic> diagnostics) {
        for (ParserSubmissionData.Diagnostic d : data.diagnostics()) {
            if (!"ERROR".equals(d.severity())) {
                continue;
            }
            String cell = d.source() == null ? null : d.source().sheet() + "!" + d.source().cell();
            diagnostics.add(new IngestionDiagnostic(cell, null, d.code(), d.message()));
        }
    }

    /** 보고 말단과 그 상위 경로를 report_item에 맞춘다. 없는 항목만 새로 만든다. */
    private Map<String, ReportItem> resolveReportItems(List<ParserSubmissionData.ReportItem> parsed) {
        Map<String, Node> wanted = new LinkedHashMap<>();
        for (ParserSubmissionData.ReportItem item : parsed) {
            ReportItemKind kind = kindOf(item.kind());
            if (kind == null || item.key() == null || item.path().isEmpty() || !"RESOLVED".equals(item.status())) {
                continue;
            }
            List<String> path = item.path();
            for (int depth = 1; depth < path.size(); depth++) {
                List<String> prefix = path.subList(0, depth);
                String code = ReportItemCodes.of(kind.name(), prefix);
                String parentCode = depth == 1 ? null : ReportItemCodes.of(kind.name(), path.subList(0, depth - 1));
                wanted.putIfAbsent(code, new Node(code, prefix.get(depth - 1), parentCode, kind, false));
            }
            String parentCode = path.size() == 1 ? null
                    : ReportItemCodes.of(kind.name(), path.subList(0, path.size() - 1));
            String leafCode = ReportItemCodes.shorten(item.key());
            String name = item.name() != null ? item.name() : path.get(path.size() - 1);
            wanted.merge(leafCode, new Node(leafCode, name, parentCode, kind, true),
                    (old, leaf) -> new Node(old.code, old.name, old.parentCode, old.kind, true));
        }

        Map<String, ReportItem> existing = new HashMap<>();
        reportItemRepository.findAllByCodeIn(wanted.keySet()).forEach(r -> existing.put(r.getCode(), r));
        int order = reportItemRepository.findMaxDisplayOrder();
        // LinkedHashMap 순서상 상위 항목이 항상 먼저 나온다.
        for (Node node : wanted.values()) {
            ReportItem found = existing.get(node.code);
            if (found == null) {
                ReportItem parent = node.parentCode == null ? null : existing.get(node.parentCode);
                found = reportItemRepository.save(ReportItem.create(node.code, truncate(node.name, 500), parent,
                        node.kind, node.leaf, ++order));
                existing.put(node.code, found);
            } else if (node.leaf && !Boolean.TRUE.equals(found.getIsLeaf())) {
                found.markLeaf();
            }
        }
        return existing;
    }

    private AccountItem resolveAccount(Line line, Map<String, AccountItem> cache) {
        if (line.account() == null || line.account().code() == null || line.account().name() == null) {
            return null;
        }
        AccountSourceType type = "REVENUE".equals(line.domain()) ? AccountSourceType.REVENUE_CLASS
                : "COST".equals(line.domain()) ? AccountSourceType.EXPENSE_BUDGET : null;
        if (type == null) {
            return null;
        }
        String code = truncate(line.account().code(), 160);
        String name = truncate(line.account().name(), 500);
        return cache.computeIfAbsent(type + "\u0000" + code + "\u0000" + name, k ->
                accountItemRepository.findBySourceTypeAndSourceCodeAndSourceName(type, code, name)
                        .orElseGet(() -> accountItemRepository.save(AccountItem.create(type, code, name))));
    }

    /** @return 요청 연도 금액 열(빈 칸 포함)이 하나라도 있으면 true */
    private boolean collectValues(FinancialLine saved, Line line, int requestYear, List<FinancialValue> values,
                                  List<IngestionDiagnostic> diagnostics) {
        boolean hasRequestYear = false;
        Set<String> slots = new HashSet<>();
        for (Value value : line.values()) {
            String cell = value.sourceCell() == null ? null : line.source().sheet() + "!" + value.sourceCell();
            Scenario scenario = scenarioOf(value.scenario());
            if (scenario == null || value.year() == null || value.month() == null
                    || value.month() < 1 || value.month() > 12) {
                diagnostics.add(new IngestionDiagnostic(cell, accountName(line), "PERIOD_UNRESOLVED",
                        "금액 열의 연도·월·구분(계획/실적/추정)을 확인할 수 없습니다. 머리글을 확인해 주세요."));
                continue;
            }
            if (value.year() > requestYear || value.year() < MIN_YEAR) {
                diagnostics.add(new IngestionDiagnostic(cell, accountName(line), "PERIOD_OUT_OF_RANGE",
                        "요청 연도 이후의 금액 열이 있습니다. 요청한 연도의 양식인지 확인해 주세요."));
                continue;
            }
            hasRequestYear |= value.year() == requestYear;
            switch (Objects.toString(value.valueStatus(), "")) {
                case "AVAILABLE" -> {
                    BigDecimal amount = parseAmount(value.amount());
                    if (amount == null || value.sourceCell() == null || value.sourceRecordId() == null) {
                        diagnostics.add(new IngestionDiagnostic(cell, accountName(line), "VALUE_UNRESOLVED",
                                "금액을 숫자로 읽을 수 없습니다. 셀 값을 확인해 주세요."));
                    } else if (!slots.add(value.year() + "-" + value.month() + "-" + scenario)) {
                        diagnostics.add(new IngestionDiagnostic(cell, accountName(line), "DUPLICATE_PERIOD",
                                "같은 월·구분 금액 열이 두 번 있습니다. 머리글을 확인해 주세요."));
                    } else {
                        values.add(FinancialValue.create(saved, value.year(), value.month(), scenario, amount,
                                truncate(value.sourceRecordId(), 500), truncate(value.sourceCell(), 30)));
                    }
                }
                case "MISSING" -> {
                    // 빈 칸은 값이 없는 것으로 둔다(0으로 채우지 않음).
                }
                default -> diagnostics.add(new IngestionDiagnostic(cell, accountName(line), "VALUE_UNRESOLVED",
                        "금액을 숫자로 읽을 수 없습니다. 셀 값을 확인해 주세요."));
            }
        }
        return hasRequestYear;
    }

    static BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            BigDecimal amount = new BigDecimal(raw.trim());
            // numeric(30,6): 정수부 24자리, 소수부 6자리까지
            return amount.scale() > 6 || amount.precision() - amount.scale() > 24 ? null : amount;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static ReportItemKind kindOf(String kind) {
        return "REVENUE".equals(kind) ? ReportItemKind.REVENUE : "COST".equals(kind) ? ReportItemKind.COST : null;
    }

    private static Scenario scenarioOf(String scenario) {
        if (scenario == null) {
            return null;
        }
        return switch (scenario) {
            case "PLAN" -> Scenario.PLAN;
            case "ACTUAL" -> Scenario.ACTUAL;
            case "ESTIMATE" -> Scenario.ESTIMATE;
            default -> null;
        };
    }

    private static String accountName(Line line) {
        return line.account() == null ? null : line.account().name();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private record Node(String code, String name, String parentCode, ReportItemKind kind, boolean leaf) {
    }
}
