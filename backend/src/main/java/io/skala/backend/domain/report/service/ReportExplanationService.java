package io.skala.backend.domain.report.service;

import io.skala.backend.domain.report.dto.ClassificationResponse;
import io.skala.backend.domain.report.dto.ExplanationCategoryResponse;
import io.skala.backend.domain.report.dto.ReportExplanationResponse;
import io.skala.backend.domain.report.dto.ReportExplanationResponse.Card;
import io.skala.backend.domain.report.dto.ReportExplanationResponse.OrganizationRef;
import io.skala.backend.domain.report.dto.ReportExplanationResponse.Reviewing;
import io.skala.backend.domain.report.exception.ReportErrorCode;
import io.skala.backend.domain.financial.value.Scenario;
import io.skala.backend.domain.review.service.Amounts;
import io.skala.backend.domain.review.service.GapRules;
import io.skala.backend.domain.review.value.GapBasis;
import io.skala.backend.domain.review.value.GapComparisonType;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.AuthenticatedUser;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPT-12~14 Gap 설명 블록의 소명 사유 유형 분류(FR-RPT-007). AI를 쓰지 않는다.
 *
 * <ul>
 *   <li>블록 = 기준 월(year, month) × 비교 종류. 그 달 version(확정 master, 없으면 조직별 최신 승인본 — 보고서용 뷰
 *       {@code report.report_source}, 홈과 같은 규칙)의 제출본에서 임계값을 넘은 검토 항목. 실적 비교는 기준 월 항목,
 *       추정 비교는 기준 월+1 항목(PRD 상대 날짜: "8월 추정 → 기준 월+1 추정")</li>
 *   <li>금액은 검토 항목의 Gap 금액(gap_comparison.gap_amount)을 그대로 쓴다</li>
 *   <li>수락된 소명만 카드가 되고, 수락 전 소명은 '사유 확인 중'(reviewing)으로 따로 준다</li>
 *   <li>분류하지 않은 카드는 '기타'(catch_all) 합계에 들어가고, 상위 유형 합계는 하위 유형 합이다</li>
 *   <li>후보: 지난달 같은 조직·항목 분류(LAST_MONTH)가 먼저, 없으면 답변에 유형 단어가 하나만 맞을 때(RULE)</li>
 * </ul>
 * 분류는 소명 행이 아니라 explanation_classification에 저장한다(소명에는 유형을 쓰지 않음).
 */
@Service
@RequiredArgsConstructor
public class ReportExplanationService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional(readOnly = true)
    public ExplanationCategoryResponse categories(AuthenticatedUser user, Integer tableVersion) {
        user.requireRole(UserRole.PLANNER);
        Categories table = loadCategories(tableVersion);
        return new ExplanationCategoryResponse(table.version, table.list.stream()
                .map(c -> new ExplanationCategoryResponse.Item(String.valueOf(c.id), c.code, c.name, c.parentCode,
                        c.catchAll, c.assignable))
                .toList());
    }

    @Transactional(readOnly = true)
    public ReportExplanationResponse explanations(AuthenticatedUser user, int year, int month,
                                                  GapComparisonType comparison) {
        user.requireRole(UserRole.PLANNER);
        checkPeriod(year, month);
        if (comparison == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "comparison이 필요합니다.");
        }
        Categories table = loadCategories(null);
        Block block = loadBlock(year, month, comparison, table);
        Map<String, Long> lastMonth = lastMonthCategories(year, month, comparison, table);
        List<Card> cards = new ArrayList<>();
        for (Row row : block.accepted) {
            Long suggested = lastMonth.get(row.organizationId + ":" + row.reportItemId);
            String source = suggested == null ? null : "LAST_MONTH";
            if (suggested == null) {
                suggested = table.ruleSuggestion(row.answer);
                source = suggested == null ? null : "RULE";
            }
            cards.add(new Card(String.valueOf(row.explanationId), row.organization(), row.item,
                    Amounts.money(row.gapAmount), row.answer, idOrNull(table.current(row.categoryId)),
                    idOrNull(suggested), source));
        }
        List<Reviewing> reviewing = block.reviewing.stream()
                .map(r -> new Reviewing(r.organization(), r.item, Amounts.money(r.gapAmount)))
                .toList();
        Totals totals = block.totals(table);
        return new ReportExplanationResponse(Amounts.money(totals.gapTotal), cards, reviewing, totals.byCategory,
                Amounts.money(totals.classified), totals.matches());
    }

    @Transactional
    public ClassificationResponse classify(AuthenticatedUser user, long explanationId, String categoryId,
                                           GapComparisonType comparison) {
        user.requireRole(UserRole.PLANNER);
        Map<String, Object> explanation = jdbc.queryForList("""
                SELECT re.verdict, ri.id AS review_item_id, ri.year, ri.month, ri.scenario
                  FROM review_explanation re JOIN review_item ri ON ri.id = re.review_item_id
                 WHERE re.id = ?""", explanationId).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "소명을 찾을 수 없습니다."));
        if (!"ACCEPTED".equals(explanation.get("verdict"))) {
            throw ReportErrorCode.EXPLANATION_NOT_ACCEPTED.exception();
        }
        Categories table = loadCategories(null);
        Long category = null;
        if (categoryId != null) {
            category = parseId(categoryId);
            Category found = table.byId.get(category);
            if (found == null) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "사유 유형을 찾을 수 없습니다.");
            }
            if (!found.assignable) {
                throw ReportErrorCode.CATEGORY_NOT_ASSIGNABLE.exception();
            }
            jdbc.update("""
                    INSERT INTO explanation_classification(review_explanation_id, category_id, classified_by, classified_at)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (review_explanation_id) DO UPDATE
                       SET category_id = EXCLUDED.category_id, classified_by = EXCLUDED.classified_by,
                           classified_at = EXCLUDED.classified_at""",
                    explanationId, category, user.id(), OffsetDateTime.now(clock));
        } else {
            jdbc.update("DELETE FROM explanation_classification WHERE review_explanation_id = ?", explanationId);
        }

        // 이 소명이 속한 블록(기준 월 × 비교 종류)의 합계를 다시 계산해 돌려준다
        Scenario scenario = Scenario.valueOf((String) explanation.get("scenario"));
        GapComparisonType type = comparison != null ? comparison
                : defaultComparison(((Number) explanation.get("review_item_id")).longValue(), scenario);
        int itemYear = ((Number) explanation.get("year")).intValue();
        int itemMonth = ((Number) explanation.get("month")).intValue();
        int[] base = baseMonthOf(itemYear, itemMonth, type);
        Totals totals = loadBlock(base[0], base[1], type, table).totals(table);
        return new ClassificationResponse(String.valueOf(explanationId), idOrNull(category), totals.byCategory,
                Amounts.money(totals.classified), Amounts.money(totals.gapTotal), totals.matches());
    }

    // ---------------------------------------------------------------- 블록

    private Block loadBlock(int year, int month, GapComparisonType comparison, Categories table) {
        int[] item = itemMonthOf(year, month, comparison);
        Block block = new Block();
        if (item == null) {
            return block;
        }
        jdbc.query("""
                SELECT o.id AS organization_id, o.code, o.name AS organization_name, i.id AS report_item_id,
                       i.name AS item_name, gc.gap_amount, acc.id AS explanation_id, acc.answer, ec.category_id
                  FROM report.report_source s
                  JOIN review_item ri ON ri.submission_id = s.submission_id AND ri.active
                                     AND ri.year = ? AND ri.month = ? AND ri.scenario = ?
                  JOIN gap_comparison gc ON gc.review_item_id = ri.id AND gc.basis = ? AND gc.exceeded
                  JOIN report_item i ON i.id = ri.report_item_id
                  JOIN organization o ON o.id = s.organization_id
                  LEFT JOIN LATERAL (SELECT re.id, re.answer FROM review_explanation re
                                      WHERE re.review_item_id = ri.id AND re.verdict = 'ACCEPTED'
                                      ORDER BY re.round DESC LIMIT 1) acc ON true
                  LEFT JOIN explanation_classification ec ON ec.review_explanation_id = acc.id
                 WHERE s.kind IN ('master', 'provisional') AND s.year = ? AND s.month = ?
                 ORDER BY i.display_order, i.code, o.code, ri.id""", rs -> {
            Row row = new Row(rs.getLong("organization_id"), rs.getString("code"), rs.getString("organization_name"),
                    rs.getLong("report_item_id"), rs.getString("item_name"), rs.getBigDecimal("gap_amount"),
                    (Long) rs.getObject("explanation_id"), rs.getString("answer"), (Long) rs.getObject("category_id"));
            (row.explanationId == null ? block.reviewing : block.accepted).add(row);
        }, item[0], item[1], GapRules.itemScenarioOf(comparison).name(), GapRules.basisOf(comparison).name(),
                year, month);
        return block;
    }

    /** 지난달 같은 블록에서 조직·항목별 마지막 분류. 지금 분류표에서 넣을 수 있는 유형만 */
    private Map<String, Long> lastMonthCategories(int year, int month, GapComparisonType comparison, Categories table) {
        int[] previous = month == 1 ? new int[] {year - 1, 12} : new int[] {year, month - 1};
        int[] item = itemMonthOf(previous[0], previous[1], comparison);
        Map<String, Long> result = new HashMap<>();
        if (item == null) {
            return result;
        }
        jdbc.query("""
                SELECT DISTINCT ON (s.organization_id, ri.report_item_id) s.organization_id, ri.report_item_id, ec.category_id
                  FROM report.report_source s
                  JOIN review_item ri ON ri.submission_id = s.submission_id
                                     AND ri.year = ? AND ri.month = ? AND ri.scenario = ?
                  JOIN review_explanation re ON re.review_item_id = ri.id
                  JOIN explanation_classification ec ON ec.review_explanation_id = re.id
                 WHERE s.kind IN ('master', 'provisional') AND s.year = ? AND s.month = ?
                 ORDER BY s.organization_id, ri.report_item_id, ec.classified_at DESC, ec.id DESC""", rs -> {
            Category category = table.byId.get(rs.getLong("category_id"));
            if (category != null && category.assignable) {
                result.put(rs.getLong("organization_id") + ":" + rs.getLong("report_item_id"), category.id);
            }
        }, item[0], item[1], GapRules.itemScenarioOf(comparison).name(), previous[0], previous[1]);
        return result;
    }

    /** 목적: 보고서와 Agent가 같은 소명 대상 기간을 사용하도록 기존 규칙을 제공한다.
     * Args: year 기준 연도, month 기준 월, comparison 비교 종류.
     * Returns: 실적은 기준 월, 추정은 다음 월의 연·월. 12월 기준 추정은 null.
     * Raises: NullPointerException 비교 종류가 없는 경우.
     * Side Effects: 없음. */
    public static int[] itemMonthOf(int year, int month, GapComparisonType comparison) {
        if (GapRules.itemScenarioOf(comparison) == Scenario.ACTUAL) {
            return new int[] {year, month};
        }
        return month == 12 ? null : new int[] {year, month + 1};
    }

    static int[] baseMonthOf(int itemYear, int itemMonth, GapComparisonType comparison) {
        if (GapRules.itemScenarioOf(comparison) == Scenario.ACTUAL) {
            return new int[] {itemYear, itemMonth};
        }
        return itemMonth == 1 ? new int[] {itemYear - 1, 12} : new int[] {itemYear, itemMonth - 1};
    }

    /** 비교 종류를 안 주면 그 항목이 넘은 비교 중 첫 번째(ACTUAL_VS_PLAN → ESTIMATE_VS_PREVIOUS → ESTIMATE_VS_PLAN) */
    private GapComparisonType defaultComparison(long reviewItemId, Scenario scenario) {
        List<GapComparisonType> exceeded = jdbc.queryForList(
                        "SELECT basis FROM gap_comparison WHERE review_item_id = ? AND exceeded", String.class, reviewItemId)
                .stream().map(basis -> GapRules.typeOf(scenario, GapBasis.valueOf(basis))).filter(Objects::nonNull).toList();
        return Arrays.stream(GapComparisonType.values()).filter(exceeded::contains).findFirst()
                .orElse(scenario == Scenario.ACTUAL ? GapComparisonType.ACTUAL_VS_PLAN : GapComparisonType.ESTIMATE_VS_PREVIOUS);
    }

    // ---------------------------------------------------------------- 분류표

    private Categories loadCategories(Integer tableVersion) {
        Integer version = tableVersion != null ? tableVersion
                : jdbc.queryForObject("SELECT max(table_version) FROM explanation_category", Integer.class);
        if (version == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "사유 유형 분류표가 없습니다.");
        }
        List<Category> list = jdbc.query("""
                SELECT c.id, c.code, c.name, c.parent_code, c.catch_all, c.suggest_keywords,
                       NOT EXISTS (SELECT 1 FROM explanation_category k
                                    WHERE k.table_version = c.table_version AND k.parent_code = c.code) AS assignable
                  FROM explanation_category c WHERE c.table_version = ? ORDER BY c.display_order, c.code""",
                (rs, n) -> new Category(rs.getLong("id"), rs.getInt("code"), rs.getString("name"),
                        (Integer) rs.getObject("parent_code"), rs.getBoolean("catch_all"),
                        rs.getString("suggest_keywords"), rs.getBoolean("assignable")), version);
        if (list.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "사유 유형 분류표가 없습니다.");
        }
        return new Categories(version, list);
    }

    private static Long parseId(String id) {
        try {
            return Long.valueOf(id);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "categoryId 형식이 올바르지 않습니다.");
        }
    }

    private static void checkPeriod(int year, int month) {
        if (year < 2000 || year > 9999 || month < 1 || month > 12) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "year는 2000~9999, month는 1~12입니다.");
        }
    }

    private static String idOrNull(Long id) {
        return id == null ? null : String.valueOf(id);
    }

    private record Category(long id, int code, String name, Integer parentCode, boolean catchAll, String keywords,
                            boolean assignable) {
    }

    private static final class Categories {
        final int version;
        final List<Category> list;
        final Map<Long, Category> byId = new LinkedHashMap<>();

        Categories(int version, List<Category> list) {
            this.version = version;
            this.list = list;
            list.forEach(c -> byId.put(c.id, c));
        }

        /** 지금 분류표의 넣을 수 있는 유형이면 그 id, 아니면 null(다른 버전 분류는 미분류로 본다) */
        Long current(Long categoryId) {
            Category c = categoryId == null ? null : byId.get(categoryId);
            return c != null && c.assignable ? c.id : null;
        }

        Category catchAll() {
            return list.stream().filter(Category::catchAll).findFirst().orElse(null);
        }

        /** 답변에 유형 단어가 맞는 넣을 수 있는 유형이 정확히 하나면 그 유형 */
        Long ruleSuggestion(String answer) {
            if (answer == null || answer.isBlank()) {
                return null;
            }
            List<Category> matched = list.stream()
                    .filter(c -> c.assignable && !c.catchAll && c.keywords != null)
                    .filter(c -> Arrays.stream(c.keywords.split(",")).map(String::strip)
                            .anyMatch(word -> !word.isEmpty() && answer.contains(word)))
                    .toList();
            return matched.size() == 1 ? matched.get(0).id : null;
        }
    }

    private record Row(long organizationId, String organizationCode, String organizationName, long reportItemId,
                       String item, BigDecimal gapAmount, Long explanationId, String answer, Long categoryId) {

        OrganizationRef organization() {
            return new OrganizationRef(String.valueOf(organizationId), organizationCode, organizationName);
        }
    }

    private record Totals(Map<String, String> byCategory, BigDecimal classified, BigDecimal gapTotal) {

        boolean matches() {
            return classified.compareTo(gapTotal) == 0;
        }
    }

    private static final class Block {
        final List<Row> accepted = new ArrayList<>();
        final List<Row> reviewing = new ArrayList<>();

        /**
         * 유형별 합계. 미분류 카드는 '기타', 상위 유형은 하위 합. 분류 합계는 넣을 수 있는 유형의 합(상위 유형 중복 없음).
         * Gap 합계는 수락 전 소명까지 포함하므로, 수락 전 소명이 남아 있으면 둘이 다르다.
         */
        Totals totals(Categories table) {
            Map<Long, BigDecimal> sums = new LinkedHashMap<>();
            table.list.forEach(c -> sums.put(c.id, BigDecimal.ZERO));
            Category fallback = table.catchAll();
            BigDecimal classified = BigDecimal.ZERO;
            for (Row row : accepted) {
                Long category = table.current(row.categoryId);
                if (category == null && fallback != null) {
                    category = fallback.id;
                }
                if (category != null) {
                    sums.merge(category, row.gapAmount, BigDecimal::add);
                    classified = classified.add(row.gapAmount);
                }
            }
            for (Category parent : table.list) {
                if (!parent.assignable) {
                    BigDecimal sum = table.list.stream().filter(c -> Objects.equals(c.parentCode, parent.code))
                            .map(c -> sums.get(c.id)).reduce(BigDecimal.ZERO, BigDecimal::add);
                    sums.put(parent.id, sum);
                }
            }
            BigDecimal gapTotal = classified;
            for (Row row : reviewing) {
                gapTotal = gapTotal.add(row.gapAmount);
            }
            Map<String, String> byCategory = new LinkedHashMap<>();
            sums.forEach((id, sum) -> byCategory.put(String.valueOf(id), Amounts.money(sum)));
            return new Totals(byCategory, classified, gapTotal);
        }
    }
}
