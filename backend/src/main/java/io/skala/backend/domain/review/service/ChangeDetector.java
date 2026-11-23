package io.skala.backend.domain.review.service;

import io.skala.backend.domain.financial.entity.FinancialValue;
import io.skala.backend.domain.financial.entity.ReportItem;
import io.skala.backend.domain.financial.value.Scenario;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 보고 항목 × 연·월 × 구분 단위로 금액을 모아 기준 버전과 비교한다. DB에 의존하지 않는 순수 계산이다.
 *
 * <p>같은 키의 원천 셀이 하나도 없으면 "값 없음"(null)으로 본다. 0과 null은 다르게 취급한다.
 */
public final class ChangeDetector {

    private ChangeDetector() {
    }

    public record Key(Long reportItemId, int year, int month, Scenario scenario) {
    }

    public record SourceCell(String sheet, String cell, BigDecimal amount) {
    }

    /** 한 키에 모인 금액. total은 셀 금액 합계이며 셀이 없으면 이 객체 자체가 없다. */
    public record Aggregate(ReportItem reportItem, BigDecimal total, List<SourceCell> cells) {

        public String fingerprint() {
            StringBuilder sb = new StringBuilder();
            cells.stream()
                    .sorted(Comparator.comparing(SourceCell::sheet).thenComparing(SourceCell::cell))
                    .forEach(c -> sb.append(c.sheet()).append('!').append(c.cell()).append('=')
                            .append(plain(c.amount())).append('\n'));
            return sha256(sb.toString());
        }
    }

    /**
     * 비교 결과 한 줄.
     *
     * @param current  현재 제출본 금액. 값이 없으면 null
     * @param baseline 기준 버전 금액. 기준이 없거나 값이 없으면 null
     * @param changed  기준 버전이 있고 금액(또는 값 유무)이 다르면 true
     */
    public record Detection(Key key, ReportItem reportItem, BigDecimal current, BigDecimal baseline, boolean changed,
                            String sourceFingerprint) {
    }

    public static Map<Key, Aggregate> aggregate(List<FinancialValue> values) {
        Map<Key, ReportItem> items = new LinkedHashMap<>();
        Map<Key, BigDecimal> totals = new LinkedHashMap<>();
        Map<Key, List<SourceCell>> cells = new LinkedHashMap<>();
        for (FinancialValue v : values) {
            ReportItem reportItem = v.getLine().getReportItem();
            Key key = new Key(reportItem.getId(), v.getYear(), v.getMonth(), v.getScenario());
            items.putIfAbsent(key, reportItem);
            totals.merge(key, v.getAmount(), BigDecimal::add);
            cells.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new SourceCell(v.getLine().getSourceSheet(), v.getSourceCell(), v.getAmount()));
        }
        Map<Key, Aggregate> result = new LinkedHashMap<>();
        items.forEach((key, item) -> result.put(key, new Aggregate(item, totals.get(key), List.copyOf(cells.get(key)))));
        return result;
    }

    /**
     * @param current  현재 제출본 집계
     * @param baseline 기준 버전 집계. 비교 기준이 없으면(연간 계획 첫 제출) null
     */
    public static List<Detection> detect(Map<Key, Aggregate> current, Map<Key, Aggregate> baseline) {
        Set<Key> keys = new LinkedHashSet<>(current.keySet());
        if (baseline != null) {
            keys.addAll(baseline.keySet());
        }
        List<Detection> result = new ArrayList<>(keys.size());
        for (Key key : keys) {
            Aggregate now = current.get(key);
            Aggregate before = baseline == null ? null : baseline.get(key);
            BigDecimal currentAmount = now == null ? null : now.total();
            BigDecimal baselineAmount = before == null ? null : before.total();
            boolean changed = baseline != null && !sameAmount(currentAmount, baselineAmount);
            ReportItem reportItem = now != null ? now.reportItem() : before.reportItem();
            String fingerprint = now == null ? sha256("") : now.fingerprint();
            result.add(new Detection(key, reportItem, currentAmount, baselineAmount, changed, fingerprint));
        }
        return result;
    }

    static boolean sameAmount(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return a.compareTo(b) == 0;
    }

    /** 비교 맥락(항목·기간·기준 버전·기준 금액) 지문. 같은 맥락이면 지난 판단을 다시 쓸 수 있다. */
    public static String contextFingerprint(String reportItemCode, Key key, Long baselineSubmissionId,
                                            BigDecimal baselineAmount) {
        return sha256(String.join("\n", reportItemCode, String.valueOf(key.year()), String.valueOf(key.month()),
                key.scenario().name(), Objects.toString(baselineSubmissionId, "-"),
                baselineAmount == null ? "-" : plain(baselineAmount)));
    }

    static String plain(BigDecimal amount) {
        return amount.signum() == 0 ? "0" : amount.stripTrailingZeros().toPlainString();
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
