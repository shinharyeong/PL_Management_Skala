package io.skala.backend.report;

import static io.skala.backend.report.ReportAuthoringJson.*;
import static io.skala.backend.submission.ApiData.object;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;

/** 인증된 보고뷰에 한정한 SELECT 문을 메모리에서 평가한다. 업무 DB SQL을 실행하지 않는다. */
public final class ReportQueryEngine {
    private static final Set<String> DIMENSIONS = Set.of("year","month","scenario","organization_id","organization_name","report_item_id","report_item_name","metric_code");
    private static final Pattern QUERY = Pattern.compile("(?is)^SELECT\\s+(.+?)\\s+FROM\\s+report_view(?:\\s+WHERE\\s+(.+?))?(?:\\s+GROUP\\s+BY\\s+(.+?))?(?:\\s+ORDER\\s+BY\\s+(.+?))?(?:\\s+LIMIT\\s+(\\d+))?\\s*$");
    private static final Pattern AGGREGATE = Pattern.compile("(?i)^(SUM|AVG|MIN|MAX)\\s*\\(\\s*amount\\s*\\)\\s+(?:AS\\s+)?([a-z][a-z0-9_]*)$");
    /** 정적 평가기 생성을 막는다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    private ReportQueryEngine() {}

    /** 지원 SQL을 분석하고 인증된 행에만 적용한다.
     * Args: sql 단일 report_view SELECT, facts 서버가 확인한 보고값.
     * Returns: columns와 rows. Raises: 지원하지 않는 SQL 또는 잘못된 금액에 400.
     * Side Effects: 메모리만 사용하고 임의 테이블·함수·파일·네트워크에 접근하지 않는다.
     */
    public static Map<String,Object> execute(String sql, List<Map<String,Object>> facts) {
        if (sql == null || sql.length() > 4000 || sql.contains(";") || sql.contains("--") || sql.contains("/*") || sql.contains("\\")) throw invalid("하나의 report_view SELECT만 사용할 수 있습니다.");
        Matcher query = QUERY.matcher(sql.strip());
        if (!query.matches()) throw invalid("report_view SELECT 형식과 지원 절을 확인해 주세요.");
        var selections = new ArrayList<Selection>(); var keys = new HashSet<String>(); boolean aggregated = false;
        for (String token : query.group(1).split(",")) {
            String expression = token.strip().toLowerCase(Locale.ROOT); Matcher aggregate = AGGREGATE.matcher(expression);
            Selection selected;
            if (aggregate.matches()) { selected = new Selection(aggregate.group(2), aggregate.group(1).toUpperCase(Locale.ROOT)); aggregated = true; }
            else if (DIMENSIONS.contains(expression) || "amount".equals(expression)) selected = new Selection(expression, null);
            else throw invalid("조회 열은 보고뷰 차원과 amount 집계만 사용할 수 있습니다.");
            if (!keys.add(selected.key())) throw invalid("조회 열 이름을 중복할 수 없습니다.");
            selections.add(selected);
        }
        if (selections.isEmpty() || selections.size() > 12) throw invalid("조회 열 개수를 확인해 주세요.");
        List<String> groups = query.group(3) == null ? List.of() : Arrays.stream(query.group(3).toLowerCase(Locale.ROOT).split(",")).map(String::strip).toList();
        if (groups.stream().anyMatch(group -> !DIMENSIONS.contains(group)) || new HashSet<>(groups).size() != groups.size()) throw invalid("GROUP BY에는 중복 없는 보고뷰 차원만 사용할 수 있습니다.");
        if (!aggregated && !groups.isEmpty()) throw invalid("GROUP BY에는 금액 집계를 함께 선택해 주세요.");
        if (aggregated && selections.stream().anyMatch(selection -> selection.operation() == null && !groups.contains(selection.key()))) throw invalid("집계하지 않은 조회 열은 GROUP BY에 포함해 주세요.");
        var predicates = predicates(query.group(2)); var filtered = facts.stream().filter(row -> matches(row, predicates)).toList();
        var rows = new ArrayList<Map<String,Object>>();
        if (aggregated) {
            var buckets = new LinkedHashMap<List<Object>,List<Map<String,Object>>>();
            for (var row : filtered) buckets.computeIfAbsent(groups.stream().map(row::get).toList(), ignored -> new ArrayList<>()).add(row);
            for (var bucket : buckets.values()) {
                var output = new LinkedHashMap<String,Object>();
                for (Selection selection : selections) output.put(selection.key(), selection.operation() == null ? bucket.getFirst().get(selection.key()) : aggregate(bucket, selection.operation()));
                rows.add(output);
            }
        } else for (var row : filtered) {
            var output = new LinkedHashMap<String,Object>(); for (Selection selection : selections) output.put(selection.key(), row.get(selection.key())); rows.add(output);
        }
        if (query.group(4) != null) {
            String[] order = query.group(4).strip().toLowerCase(Locale.ROOT).split("\\s+");
            if (order.length > 2 || !keys.contains(order[0]) || (order.length == 2 && !Set.of("asc","desc").contains(order[1]))) throw invalid("ORDER BY는 조회한 열 하나와 ASC/DESC를 사용해 주세요.");
            Comparator<Map<String,Object>> comparator = (left,right) -> compare(left.get(order[0]), right.get(order[0]));
            if (order.length == 2 && "desc".equals(order[1])) comparator = comparator.reversed(); rows.sort(comparator);
        }
        int limit = 1000;
        if (query.group(5) != null) try { limit = Integer.parseInt(query.group(5)); } catch (NumberFormatException error) { throw invalid("LIMIT은 1~1000입니다."); }
        if (limit < 1 || limit > 1000) throw invalid("LIMIT은 1~1000입니다.");
        String valueColumn=selections.stream().filter(selection -> selection.operation()!=null || "amount".equals(selection.key())).map(Selection::key).findFirst().orElse(null);
        return object("columns", selections.stream().map(selection -> object("key", selection.key(), "label", selection.key())).toList(), "rows", rows.subList(0, Math.min(limit, rows.size())), "truncated", rows.size() > limit,"valueColumn",valueColumn);
    }

    /** 같은 SQL WHERE에 속하는 원천행을 대사 계산에도 사용한다. Args: sql 검증대상, facts 보고뷰. Returns: 필터된 원천행. Raises: SQL 오류. Side Effects: 없음. */
    public static List<Map<String,Object>> selectedFacts(String sql,List<Map<String,Object>> facts) {
        execute(sql,List.of()); Matcher query=QUERY.matcher(sql.strip()); query.matches(); var conditions=predicates(query.group(2));
        return facts.stream().filter(row -> matches(row,conditions)).toList();
    }
    /** SQL 금액 집계 종류를 확인한다. Args: sql 검증된 SELECT. Returns: SUM/AVG/MIN/MAX 또는 MIXED. Raises: 없음. Side Effects: 없음. */
    public static String aggregation(String sql) {
        var operations=new HashSet<String>(); Matcher matcher=Pattern.compile("(?i)(SUM|AVG|MIN|MAX)\\s*\\(\\s*amount\\s*\\)").matcher(sql);
        while (matcher.find()) operations.add(matcher.group(1).toUpperCase(Locale.ROOT)); return operations.isEmpty() ? "SUM" : operations.size()==1 ? operations.iterator().next() : "MIXED";
    }
    /** WHERE의 제한된 동등조건을 범위 선택에도 제공한다. Args: sql 단일 SELECT. Returns: 필드별 문자열. Raises: SQL·중복조건 오류. Side Effects: 없음. */
    public static Map<String,String> filters(String sql) {
        execute(sql,List.of()); Matcher query=QUERY.matcher(sql.strip()); query.matches(); var result=new LinkedHashMap<String,String>();
        for (Condition condition:predicates(query.group(2))) if (result.putIfAbsent(condition.key(),condition.value())!=null) throw invalid("같은 WHERE 필드를 중복할 수 없습니다."); return result;
    }

    /** WHERE를 허용된 동등 비교 목록으로 분석한다. Args: where 선택 절. Returns: 조건 목록. Raises: 400 미지원 조건. Side Effects: 없음. */
    private static List<Condition> predicates(String where) {
        if (where == null) return List.of(); var conditions = new ArrayList<Condition>();
        for (String part : where.split("(?i)\\s+AND\\s+")) {
            Matcher match = Pattern.compile("(?is)^([a-z_]+)\\s*=\\s*(?:'([^']*)'|(\\d+))$").matcher(part.strip());
            if (!match.matches() || !DIMENSIONS.contains(match.group(1).toLowerCase(Locale.ROOT))) throw invalid("WHERE에는 보고뷰 차원의 = 비교와 AND만 사용할 수 있습니다.");
            conditions.add(new Condition(match.group(1).toLowerCase(Locale.ROOT), match.group(2) == null ? match.group(3) : match.group(2)));
        }
        return conditions;
    }
    /** 행이 허용 조건을 만족하는지 검사한다. Args: row 보고행, conditions 조건. Returns: 일치 여부. Raises: 없음. Side Effects: 없음. */
    private static boolean matches(Map<String,Object> row, List<Condition> conditions) { return conditions.stream().allMatch(condition -> Objects.equals(String.valueOf(row.get(condition.key())), condition.value())); }
    /** 정확한 금액을 집계한다. Args: rows 보고행, operation 집계명. Returns: 십진 문자열. Raises: 잘못된 금액. Side Effects: 없음. */
    private static String aggregate(List<Map<String,Object>> rows, String operation) {
        var values = rows.stream().map(row -> new BigDecimal(row.get("amount").toString())).toList();
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return switch (operation) { case "SUM" -> sum.toPlainString(); case "AVG" -> sum.divide(BigDecimal.valueOf(values.size()), 6, java.math.RoundingMode.HALF_UP).toPlainString(); case "MIN" -> Collections.min(values).toPlainString(); case "MAX" -> Collections.max(values).toPlainString(); default -> throw invalid("지원하지 않는 집계입니다."); };
    }
    /** 정렬값을 숫자 또는 문자열로 비교한다. Args: left/right 정렬값. Returns: 비교 결과. Raises: 없음. Side Effects: 없음. */
    private static int compare(Object left, Object right) {
        if (left == null || right == null) return left == right ? 0 : left == null ? -1 : 1;
        try { return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())); } catch (NumberFormatException error) { return left.toString().compareTo(right.toString()); }
    }
    private record Selection(String key, String operation) {}
    private record Condition(String key, String value) {}
}
