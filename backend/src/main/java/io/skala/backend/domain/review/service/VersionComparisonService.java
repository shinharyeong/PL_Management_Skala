package io.skala.backend.domain.review.service;

import io.skala.backend.domain.financial.entity.ReportItem;
import io.skala.backend.domain.financial.repository.FinancialValueRepository;
import io.skala.backend.domain.request.entity.SubmissionRequest;
import io.skala.backend.domain.request.repository.SubmissionRequestRepository;
import io.skala.backend.domain.review.dto.ReviewTreeResponse.Node;
import io.skala.backend.domain.review.dto.VersionComparisonResponse;
import io.skala.backend.domain.review.service.ChangeDetector.Aggregate;
import io.skala.backend.domain.review.service.ChangeDetector.Key;
import io.skala.backend.domain.submission.entity.Submission;
import io.skala.backend.domain.submission.repository.SubmissionRepository;
import io.skala.backend.domain.submission.service.BaselineResolver;
import io.skala.backend.domain.submission.service.SubmissionLabels;
import io.skala.backend.domain.submission.service.SubmissionQueryService;
import io.skala.backend.domain.submission.support.SubmissionAccess;
import io.skala.backend.domain.submission.value.SubmissionStatus;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.AuthenticatedUser;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GAP-01 두 버전 비교(보고 항목 × 연·월 × 구분), GAP-02 같은 결과 엑셀.
 * 기준 대상 기본값: 같은 요청·조직의 최신 승인본(비교 대상 제외), 없으면 직전 기준(전월 승인본·확정 계획).
 * 기준 대상은 같은 조직의 제출본이어야 한다.
 */
@Service
@RequiredArgsConstructor
public class VersionComparisonService {

    private final SubmissionAccess submissionAccess;
    private final SubmissionRepository submissionRepository;
    private final SubmissionRequestRepository submissionRequestRepository;
    private final SubmissionQueryService submissionQueryService;
    private final BaselineResolver baselineResolver;
    private final FinancialValueRepository financialValueRepository;

    record Comparison(Submission base, Submission target, List<ReviewTreeService.Leaf> leaves) {
    }

    @Transactional(readOnly = true)
    public VersionComparisonResponse compare(Long submissionId, Long baseSubmissionId, AuthenticatedUser user) {
        Comparison c = load(submissionId, baseSubmissionId, user);
        return new VersionComparisonResponse(submissionQueryService.toResponse(c.base()),
                submissionQueryService.toResponse(c.target()),
                ReviewTreeService.build(c.leaves(), ReviewTreeService.comparator("DIFF", "DESC")));
    }

    /** 엑셀: 경로·항목·월·구분·기준값·비교값·차액·차이율(%) 한 행씩. 금액은 원 단위. */
    @Transactional(readOnly = true)
    public String writeExcel(Long submissionId, Long baseSubmissionId, AuthenticatedUser user, OutputStream out)
            throws IOException {
        Comparison c = load(submissionId, baseSubmissionId, user);
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Gap");
            String[] header = {"보고 항목 경로", "상세", "기준값(" + label(c.base()) + ")", "비교값(" + label(c.target()) + ")",
                    "차액", "차이율(%)"};
            Row head = sheet.createRow(0);
            for (int i = 0; i < header.length; i++) {
                head.createCell(i).setCellValue(header[i]);
            }
            int r = 1;
            List<ReviewTreeService.Leaf> sorted = new ArrayList<>(c.leaves());
            sorted.sort(Comparator.comparing((ReviewTreeService.Leaf l) -> path(l.reportItem()))
                    .thenComparing(ReviewTreeService.Leaf::name));
            for (ReviewTreeService.Leaf leaf : sorted) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(path(leaf.reportItem()));
                row.createCell(1).setCellValue(leaf.name());
                amount(row.createCell(2), leaf.plan());
                amount(row.createCell(3), leaf.actual());
                amount(row.createCell(4), Amounts.diff(leaf.actual(), leaf.plan()));
                String rate = Amounts.rate(leaf.actual(), leaf.plan());
                row.createCell(5).setCellValue(rate == null ? "산정 불가" : rate);
            }
            workbook.write(out);
        }
        return "Gap_" + label(c.base()) + "_vs_" + label(c.target()) + ".xlsx";
    }

    private Comparison load(Long submissionId, Long baseSubmissionId, AuthenticatedUser user) {
        user.requireRole(UserRole.PLANNER);
        Submission target = submissionAccess.loadForRead(submissionId, user);
        Submission base = baseSubmissionId != null ? submissionAccess.loadForRead(baseSubmissionId, user)
                : defaultBase(target);
        if (base == null) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "COMPARISON_SOURCE_UNAVAILABLE",
                    "비교할 기준 버전이 없습니다.");
        }
        if (!Objects.equals(base.getOrganizationId(), target.getOrganizationId()) || base.getId().equals(target.getId())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_COMPARISON_SELECTION",
                    "같은 조직의 다른 버전을 골라 주세요.");
        }
        Map<Key, Aggregate> baseValues = ChangeDetector.aggregate(financialValueRepository.findAllWithLineBySubmissionId(base.getId()));
        Map<Key, Aggregate> targetValues = ChangeDetector.aggregate(financialValueRepository.findAllWithLineBySubmissionId(target.getId()));
        if (baseValues.isEmpty() || targetValues.isEmpty()) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "COMPARISON_SOURCE_UNAVAILABLE",
                    "비교할 데이터가 없습니다.");
        }
        Set<Key> keys = new LinkedHashSet<>(targetValues.keySet());
        keys.addAll(baseValues.keySet());
        List<ReviewTreeService.Leaf> leaves = new ArrayList<>();
        for (Key key : keys) {
            Aggregate b = baseValues.get(key);
            Aggregate t = targetValues.get(key);
            ReportItem reportItem = t != null ? t.reportItem() : b.reportItem();
            BigDecimal plan = b == null ? null : b.total();
            BigDecimal actual = t == null ? null : t.total();
            leaves.add(new ReviewTreeService.Leaf(reportItem, ReviewTreeService.leafName(key.month(), key.scenario()),
                    plan, actual, null, null, false, null, null));
        }
        return new Comparison(base, target, leaves);
    }

    private Submission defaultBase(Submission target) {
        Submission latestApproved = submissionRepository
                .findAllByRequestIdAndOrganizationIdAndStatusOrderByApprovedSeqAsc(target.getRequestId(),
                        target.getOrganizationId(), SubmissionStatus.APPROVED).stream()
                .filter(s -> !s.getId().equals(target.getId()))
                .reduce((a, b) -> b).orElse(null);
        if (latestApproved != null) {
            return latestApproved;
        }
        SubmissionRequest request = submissionRequestRepository.findById(target.getRequestId()).orElseThrow();
        return baselineResolver.resolveApproved(target, request).submission();
    }

    private String label(Submission s) {
        SubmissionRequest r = submissionRequestRepository.findById(s.getRequestId()).orElseThrow();
        String label = s.getApprovedSeq() != null ? SubmissionLabels.approved(s, r)
                : SubmissionLabels.of(s, r);
        return label == null ? "초안" + s.getId() : label;
    }

    private static String path(ReportItem item) {
        List<String> names = new ArrayList<>();
        for (ReportItem r = item; r != null; r = r.getParent()) {
            names.add(0, r.getName());
        }
        return String.join(" › ", names);
    }

    /** 원 단위 금액. 엑셀 숫자(유효 숫자 15자리)로 정확히 담을 수 없으면 문자열로 쓴다. 값이 없으면 빈 칸. */
    private static void amount(Cell cell, BigDecimal value) {
        if (value == null) {
            return;
        }
        if (value.stripTrailingZeros().precision() <= 15) {
            cell.setCellValue(value.doubleValue());
        } else {
            cell.setCellValue(Amounts.money(value));
        }
    }
}
