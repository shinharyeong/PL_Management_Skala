package io.skala.backend.report;

import static io.skala.backend.report.ReportAuthoringJson.JSON;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 저장된 단일 보고자료를 스크립트 없는 독립 HTML로 내보낸다. */
public final class ReportMaterialExport {
    private static final String[] COLORS={"#2563eb","#0d9488","#ea580c","#9333ea","#dc2626","#64748b"};
    /** 정적 작성기 생성을 막는다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    private ReportMaterialExport() {}
    /** 불변 버전과 출처·표·차트·분류를 HTML 바이트로 구성한다.
     * Args: material 권한 검사된 저장 자료.
     * Returns: UTF-8 HTML. Raises: 내부 JSON 형식 오류.
     * Side Effects: 메모리만 사용하며 파일·브라우저·네트워크를 열지 않는다.
     */
    public static byte[] write(Map<String,Object> material) {
        JsonNode document=JSON.valueToTree(material); var output=new StringBuilder("<!doctype html><html lang=\"ko\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>");
        output.append(escape(document.path("name").asText())).append("</title><style>body{font:14px system-ui,sans-serif;max-width:1200px;margin:36px auto;color:#172033;padding:0 24px}section{border-top:1px solid #d7dce4;padding:20px 0;break-inside:avoid}table{width:100%;border-collapse:collapse;margin:12px 0}th,td{border:1px solid #d7dce4;padding:7px;text-align:left}th{background:#f1f5f9}.meta{color:#64748b;font-size:12px}.warning{color:#9a3412}.text{white-space:pre-wrap}svg{max-width:100%;height:auto}@media print{body{margin:0;max-width:none}}</style><h1>")
                .append(escape(document.path("name").asText())).append("</h1><p class=\"meta\">자료 ").append(escape(document.path("id").asText())).append(" · 버전 ").append(document.path("revision").asInt()).append(" · 기준 ").append(document.path("baseYear").asInt()).append("-").append(document.path("baseMonth").asInt()).append(" · 저장 ").append(escape(document.path("createdAt").asText())).append("</p>");
        var results=new HashMap<String,JsonNode>(); for (JsonNode result:document.path("results")) results.put(result.path("blockId").asText(),result);
        for (JsonNode block:document.path("blocks")) {
            JsonNode result=results.get(block.path("id").asText()); JsonNode style=block.path("style");
            output.append("<section style=\"width:").append(Math.max(25,Math.min(100,style.path("width").asInt(100)))).append("%;font-family:").append(escape(style.path("fontFamily").asText("system-ui"))).append(";font-size:").append(style.path("fontSize").asInt(14)).append("px;color:").append(escape(style.path("color").asText("#172033"))).append(";background:").append(escape(style.path("backgroundColor").asText("#ffffff"))).append("\"><h2>").append(escape(block.path("title").asText())).append("</h2>");
            output.append("<p class=\"text\">").append(escape(block.path("text").asText(""))).append("</p>");
            if (result==null) output.append("<p class=\"warning\">계산 결과가 저장되지 않은 구성입니다.</p>");
            else {
                output.append("<p class=\"meta\">").append(escape(result.path("status").asText())).append(" · ").append(escape(result.path("scenario").asText())).append(" · ").append(escape(result.path("periods").toString())).append(" · 원 단위</p>");
                if ("CHART".equals(block.path("type").asText())) chart(output,result,block.path("chartKind").asText("BAR"));
                table(output,result.path("columns"),result.path("rows"));
                if ("GAP".equals(block.path("type").asText()) || "LIST".equals(block.path("type").asText())) {
                    output.append("<p>Gap: ").append(escape(result.path("gapTotal").asText("자료 없음"))).append(" / 분류 합계: ").append(escape(result.path("mappedTotal").asText())).append(" / 미분류: ").append(escape(result.path("unclassifiedTotal").asText("자료 없음"))).append("</p><ul>");
                    for (JsonNode reason:result.path("reasonTotals")) output.append("<li>").append(escape(reason.path("name").asText())).append(": ").append(escape(reason.path("amount").asText())).append(reason.path("aggregate").asBoolean() ? " (하위 합계)" : "").append("</li>"); output.append("</ul>");
                    for (JsonNode explanation:result.path("explanations")) output.append("<p class=\"text\">소명 #").append(escape(explanation.path("explanationId").asText())).append(" · ").append(escape(explanation.path("answer").asText())).append("</p>");
                }
                for (JsonNode warning:result.path("warnings")) output.append("<p class=\"warning\">").append(escape(warning.asText())).append("</p>");
                output.append("<details><summary>고정 출처와 SQL</summary><pre>").append(escape(result.path("sources").toPrettyString())).append("</pre><pre>").append(escape(result.path("sql").asText())).append("</pre></details>");
            }
            output.append("</section>");
        }
        return output.append("</html>").toString().getBytes(StandardCharsets.UTF_8);
    }
    /** 결과 표를 HTML로 추가한다. Args: output 버퍼, columns 열, rows 행. Returns: 없음. Raises: 없음. Side Effects: 버퍼만 변경. */
    private static void table(StringBuilder output,JsonNode columns,JsonNode rows) {
        if (rows.isEmpty()) { output.append("<p>표시할 확정 범위의 값이 없습니다.</p>"); return; }
        output.append("<table><thead><tr>"); for (JsonNode column:columns) output.append("<th>").append(escape(column.path("label").asText())).append("</th>"); output.append("</tr></thead><tbody>");
        for (JsonNode row:rows) { output.append("<tr>"); for (JsonNode column:columns) output.append("<td>").append(escape(row.path(column.path("key").asText()).asText("—"))).append("</td>"); output.append("</tr>"); } output.append("</tbody></table>");
    }
    /** 표시 좌표만 부동소수점으로 변환해 BAR/PIE SVG를 만든다. Args: output 버퍼, result 정확한 금액, kind 차트종류. Returns: 없음. Raises: 없음. Side Effects: 버퍼만 변경하며 원금액은 변환하지 않는다. */
    private static void chart(StringBuilder output,JsonNode result,String kind) {
        var values=new ArrayList<BigDecimal>(); var labels=new ArrayList<String>();
        for (JsonNode row:result.path("rows")) {
            if (values.size()==30) break;
            try { values.add(new BigDecimal(row.path(result.path("valueColumn").asText("amount")).asText())); labels.add(row.path("organization_name").asText(row.path("month").asText("항목"))); } catch (NumberFormatException error) { return; }
        }
        if (values.isEmpty()) return;
        if ("PIE".equals(kind)) {
            BigDecimal sum=values.stream().reduce(BigDecimal.ZERO,BigDecimal::add);
            if (sum.signum()<=0 || values.stream().anyMatch(value -> value.signum()<0)) { output.append("<p class=\"warning\">음수 또는 합계 0인 값은 원형 차트로 표현하지 않습니다.</p>"); return; }
            output.append("<svg viewBox=\"0 0 600 300\" role=\"img\" aria-label=\"원형 차트\">"); double angle=-Math.PI/2;
            for (int index=0;index<values.size();index++) { double fraction=values.get(index).divide(sum,12,java.math.RoundingMode.HALF_UP).doubleValue(),next=angle+fraction*2*Math.PI; if (fraction>=0.999999) output.append("<circle cx=\"150\" cy=\"150\" r=\"120\" fill=\"").append(COLORS[index%COLORS.length]).append("\"/>"); else output.append("<path d=\"M150,150 L").append(150+120*Math.cos(angle)).append(',').append(150+120*Math.sin(angle)).append(" A120,120 0 ").append(fraction>0.5 ? 1 : 0).append(" 1 ").append(150+120*Math.cos(next)).append(',').append(150+120*Math.sin(next)).append(" Z\" fill=\"").append(COLORS[index%COLORS.length]).append("\"/>"); angle=next; }
            for (int index=0;index<Math.min(12,labels.size());index++) output.append("<text x=\"300\" y=\"").append(20+index*23).append("\" fill=\"").append(COLORS[index%COLORS.length]).append("\">").append(escape(labels.get(index))).append("</text>"); output.append("</svg>");
        } else {
            BigDecimal max=values.stream().map(BigDecimal::abs).max(BigDecimal::compareTo).orElse(BigDecimal.ONE); if (max.signum()==0) max=BigDecimal.ONE;
            output.append("<svg viewBox=\"0 0 900 ").append(values.size()*32+20).append("\" role=\"img\" aria-label=\"막대 차트\">");
            for (int index=0;index<values.size();index++) { double width=values.get(index).abs().divide(max,12,java.math.RoundingMode.HALF_UP).doubleValue()*300; output.append("<text x=\"0\" y=\"").append(index*32+22).append("\">").append(escape(labels.get(index))).append("</text><rect x=\"").append(values.get(index).signum()<0 ? 450-width : 450).append("\" y=\"").append(index*32+7).append("\" width=\"").append(width).append("\" height=\"20\" fill=\"").append(values.get(index).signum()<0 ? "#dc2626" : "#2563eb").append("\"/><text x=\"760\" y=\"").append(index*32+22).append("\">").append(values.get(index).toPlainString()).append("</text>"); } output.append("</svg>");
        }
    }
    /** 텍스트를 HTML 문맥에서 이스케이프한다. Args: value 표시문자열. Returns: 안전한 텍스트. Raises: 없음. Side Effects: 없음. */
    private static String escape(String value) { return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"); }
}
