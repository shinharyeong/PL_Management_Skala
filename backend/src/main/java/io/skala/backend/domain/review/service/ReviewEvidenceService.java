package io.skala.backend.domain.review.service;

import io.skala.backend.domain.financial.entity.AccountItem;
import io.skala.backend.domain.financial.entity.FinancialValue;
import io.skala.backend.domain.financial.repository.FinancialValueRepository;
import io.skala.backend.domain.request.entity.SubmissionRequest;
import io.skala.backend.domain.request.repository.SubmissionRequestRepository;
import io.skala.backend.domain.review.dto.EvidenceResponse;
import io.skala.backend.domain.review.dto.VersionCompareResponse;
import io.skala.backend.domain.review.entity.ReviewExplanation;
import io.skala.backend.domain.review.entity.ReviewItem;
import io.skala.backend.domain.review.repository.ReviewExplanationRepository;
import io.skala.backend.domain.review.service.ChangeDetector.Key;
import io.skala.backend.domain.submission.entity.Submission;
import io.skala.backend.domain.submission.repository.SubmissionRepository;
import io.skala.backend.domain.submission.service.BaselineResolver;
import io.skala.backend.domain.submission.service.SubmissionLabels;
import io.skala.backend.domain.submission.value.SubmissionStatus;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.security.AuthenticatedUser;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** GAP-09 원천 셀, GAP-10 셀 단위 버전 비교 */
@Service
@RequiredArgsConstructor
public class ReviewEvidenceService {

    private static final Pattern CELL = Pattern.compile("^([A-Z]+)(\\d+)$");

    private final ReviewItemQueryService reviewItemQueryService;
    private final FinancialValueRepository financialValueRepository;
    private final ReviewExplanationRepository reviewExplanationRepository;
    private final SubmissionRepository submissionRepository;
    private final SubmissionRequestRepository submissionRequestRepository;
    private final BaselineResolver baselineResolver;

    @Transactional(readOnly = true)
    public EvidenceResponse evidence(Long reviewItemId, AuthenticatedUser user) {
        ReviewItem item = reviewItemQueryService.load(reviewItemId, user);
        Key key = ReviewItemQueryService.key(item);
        List<EvidenceResponse.Item> items = cellsOf(item.getSubmission(), key).stream()
                .sorted(CELL_ORDER)
                .map(v -> {
                    AccountItem account = v.getLine().getAccountItem();
                    return new EvidenceResponse.Item(v.getLine().getSourceSheet(), v.getSourceCell(),
                            account == null ? null : new EvidenceResponse.Account(account.getSourceCode(),
                                    account.getSourceName()),
                            v.getLine().getDescription(), Amounts.money(v.getAmount()));
                })
                .toList();
        return new EvidenceResponse(items);
    }

    @Transactional(readOnly = true)
    public VersionCompareResponse versions(Long reviewItemId, AuthenticatedUser user) {
        user.requireRole(UserRole.PLANNER);
        ReviewItem item = reviewItemQueryService.load(reviewItemId, user);
        Submission owner = item.getSubmission();
        SubmissionRequest request = submissionRequestRepository.findById(owner.getRequestId()).orElseThrow();

        List<Submission> approved = new ArrayList<>(submissionRepository
                .findAllByRequestIdAndOrganizationIdAndStatusOrderByApprovedSeqAsc(owner.getRequestId(),
                        owner.getOrganizationId(), SubmissionStatus.APPROVED));
        if (approved.isEmpty()) {
            // 이 요청에 승인본이 아직 없으면 직전 기준(전월 승인본·확정 계획)을 현재 기준으로 보여 준다.
            Submission fallback = baselineResolver.resolveApproved(owner, request).submission();
            if (fallback != null) {
                approved.add(fallback);
            }
        }
        List<Submission> columns = new ArrayList<>(approved);
        boolean ownerIsNew = owner.getStatus() != SubmissionStatus.APPROVED;
        if (ownerIsNew) {
            columns.add(owner);
        }
        List<VersionCompareResponse.Column> header = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            Submission s = columns.get(i);
            boolean isNew = ownerIsNew && i == columns.size() - 1;
            boolean isCurrent = !isNew && i == approved.size() - 1;
            SubmissionRequest r = submissionRequestRepository.findById(s.getRequestId()).orElseThrow();
            String label = s.getApprovedSeq() != null ? SubmissionLabels.approved(s, r) : SubmissionLabels.of(s, r);
            String next = isNew ? "ver%d.%02d".formatted(request.getMonth() == null ? 0 : request.getMonth(),
                    submissionRepository.findMaxApprovedSeq(owner.getRequestId(), owner.getOrganizationId()) + 1)
                    : null;
            header.add(new VersionCompareResponse.Column(String.valueOf(s.getId()), label,
                    isNew ? "NEW" : isCurrent ? "CURRENT" : "CONFIRMED",
                    s.getStatus() == SubmissionStatus.APPROVED ? s.getReviewedAt() : null, next));
        }

        Key key = ReviewItemQueryService.key(item);
        Map<String, BigDecimal[]> values = new TreeMap<>(Comparator.comparing(ReviewEvidenceService::cellSortKey));
        Map<String, FinancialValue> latest = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            for (FinancialValue v : cellsOf(columns.get(i), key)) {
                String cell = v.getLine().getSourceSheet() + "!" + v.getSourceCell();
                BigDecimal[] slot = values.computeIfAbsent(cell, c -> new BigDecimal[columns.size()]);
                slot[i] = slot[i] == null ? v.getAmount() : slot[i].add(v.getAmount());
                latest.put(cell, v);
            }
        }
        String explanation = reviewExplanationRepository.findByReviewItemIdAndRound(item.getId(), 1)
                .map(ReviewExplanation::getAnswer).orElse(null);
        List<VersionCompareResponse.Row> rows = new ArrayList<>();
        values.forEach((cell, slot) -> {
            BigDecimal last = slot.length == 0 ? null : slot[slot.length - 1];
            BigDecimal previous = slot.length < 2 ? null : slot[slot.length - 2];
            FinancialValue source = latest.get(cell);
            AccountItem account = source.getLine().getAccountItem();
            List<String> amounts = new ArrayList<>();
            for (BigDecimal v : slot) {
                amounts.add(Amounts.money(v));
            }
            rows.add(new VersionCompareResponse.Row(cell,
                    account != null ? account.getSourceName() : item.getReportItem().getName(), amounts,
                    Amounts.money(Amounts.diff(last, previous)), source.getLine().getDescription(),
                    ownerIsNew || columns.contains(owner) ? explanation : null,
                    slot.length >= 2 && !ChangeDetector.sameAmount(last, previous)));
        });
        return new VersionCompareResponse(header, rows);
    }

    private List<FinancialValue> cellsOf(Submission submission, Key key) {
        return financialValueRepository.findRequestYearValuesBySubmissionId(submission.getId()).stream()
                .filter(v -> v.getLine().getReportItem().getId().equals(key.reportItemId())
                        && v.getYear() == key.year() && v.getMonth() == key.month() && v.getScenario() == key.scenario())
                .toList();
    }

    /** 시트 → 행 → 열 순서 */
    static final Comparator<FinancialValue> CELL_ORDER = Comparator.comparing(
            (FinancialValue v) -> cellSortKey(v.getLine().getSourceSheet() + "!" + v.getSourceCell()));

    static String cellSortKey(String sheetCell) {
        int bang = sheetCell.lastIndexOf('!');
        String sheet = bang < 0 ? "" : sheetCell.substring(0, bang);
        String cell = bang < 0 ? sheetCell : sheetCell.substring(bang + 1);
        Matcher m = CELL.matcher(cell);
        if (!m.matches()) {
            return sheet + "\u0000" + cell;
        }
        return sheet + "\u0000" + "%010d".formatted(Long.parseLong(m.group(2))) + "%5s".formatted(m.group(1));
    }
}
