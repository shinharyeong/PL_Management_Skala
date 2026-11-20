<script setup>
import { computed } from 'vue'
import { formatMoney, chartGeometry } from '../lib/decimal-display'
import { reportColumns, reportStatus, reportPieGeometry } from '../lib/report-authoring'

const props = defineProps({ block: { type: Object, required: true }, result: { type: Object, default: null } })

/** 서버 열 메타데이터만으로 결과표를 구성한다.
 * Args: 없음. Returns: 열 목록. Raises: 없음. Side Effects: 없음.
 */
const columns = computed(function resultColumns() { return reportColumns(props.result?.columns) })

/** 차트에 사용할 십진 금액 열과 행 라벨을 선택한다.
 * Args: 없음. Returns: 값·라벨·좌표. Raises: 없음. Side Effects: 없음; 누락을 실제 0으로 만들지 않는다.
 */
const bars = computed(function resultBars() {
  const items = []
  for (const row of props.result?.rows || []) {
    const value = row[props.result?.valueColumn] ?? row.amount ?? row.currentAmount ?? row.value ?? row.gapAmount
    if (value === null || value === undefined || !/^-?\d+(\.\d+)?$/.test(String(value))) continue
    const name = row.label ?? row.name ?? row.reportItemName ?? row.report_item_name ?? row.organizationName ?? row.organization_name ?? ''
    const period = row.year && row.month ? `${row.year}.${String(row.month).padStart(2, '0')}` : ''
    items.push({ label: `${period} ${name}`.trim() || String(items.length + 1), value: String(value) })
  }
  const geometry = chartGeometry(items.map(function amount(item) { return item.value }))
  return items.map(function position(item, index) { return { ...item, zero: geometry.zero, top: Math.min(geometry.zero, geometry.positions[index]), height: Math.abs(geometry.zero - geometry.positions[index]) } })
})

/** 양수 자료에만 원형 비중을 표시한다. Args: 없음. Returns: 구간 또는 null. Raises: 없음. Side Effects: 없음. */
const pie = computed(function resultPie() { return reportPieGeometry(bars.value.map(function value(item) { return item.value })) })
const palette = ['#466ba0', '#65a79b', '#bf895c', '#8c78ae', '#c06b7e', '#78904c', '#528baf', '#9b9384']

/** 컬럼의 금액 문자열을 정밀하게 표시하고 나머지는 원문으로 보여 준다.
 * Args: row 서버 행, key 열 키. Returns: 표시 문자열. Raises: 없음. Side Effects: 없음.
 */
function cell(row, key) {
  const value = row[key]
  if (value === null || value === undefined) return '—'
  if ((key === props.result?.valueColumn || /amount|total|revenue|cost|profit|delta|^value$/i.test(key)) && /^-?\d+(\.\d+)?$/.test(String(value))) return formatMoney(value)
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}
</script>

<template>
  <section :class="['block-output', { provisional: result?.status === 'PROVISIONAL' }]" :style="{ fontFamily: block.style?.fontFamily || 'sans-serif', fontSize: `${block.style?.fontSize || 14}px` }">
    <header><h3>{{ block.title }}</h3><span class="state-tag">{{ reportStatus(result?.status) }}</span></header>
    <p v-if="result?.status === 'PROVISIONAL'" class="provisional-label">잠정 · 승인되지 않은 자료가 포함될 수 있습니다.</p>
    <p v-if="block.text" class="report-text">{{ block.text }}</p>
    <p v-if="!result" class="muted">미리보기를 실행하면 조회 결과와 출처를 확인할 수 있습니다.</p>
    <p v-else-if="result.status === 'NO_DATA'" class="muted">선택한 기간과 범위에 조회 가능한 데이터가 없습니다.</p>
    <ul v-if="result?.warnings?.length" class="block-warnings"><li v-for="(warning, index) in result.warnings" :key="index">{{ typeof warning === 'string' ? warning : warning.message }}</li></ul>
    <div v-if="block.type === 'CHART' && block.chartKind === 'PIE' && pie" class="pie-chart"><svg viewBox="0 0 42 42" role="img" :aria-label="`${block.title} 구성비, 상세값은 아래 표`"><circle v-for="(segment, index) in pie" :key="index" cx="21" cy="21" r="15.915494" fill="none" :stroke="palette[index % palette.length]" stroke-width="10" pathLength="100" :stroke-dasharray="`${segment.percent} ${100 - segment.percent}`" :stroke-dashoffset="segment.offset" transform="rotate(-90 21 21)"><title>{{ bars[index].label }}: {{ formatMoney(bars[index].value) }}원</title></circle></svg><ul><li v-for="(item, index) in bars" :key="index"><i :style="{ background: palette[index % palette.length] }"></i>{{ item.label }} · {{ formatMoney(item.value) }}원</li></ul></div>
    <p v-else-if="block.type === 'CHART' && block.chartKind === 'PIE'" class="muted">원형 차트는 음수가 없고 합계가 0보다 클 때 표시됩니다. 아래 표에서 실제 값을 확인하세요.</p>
    <div v-else-if="block.type === 'CHART' && bars.length" class="bar-chart" role="img" :aria-label="`${block.title} 금액 차트, 상세값은 아래 표`">
      <div v-for="(item, index) in bars" :key="index" class="bar-column"><div class="bar-area"><span class="zero-line" :style="{ top: `${item.zero}%` }"></span><span class="bar" :class="{ negative: item.value.startsWith('-') }" :style="{ top: `${item.top}%`, height: `${Math.max(item.height, 0.5)}%` }" :title="formatMoney(item.value)"></span></div><span>{{ item.label }}</span></div>
    </div>
    <div v-if="result?.rows?.length && columns.length" class="table-scroll"><table><caption>조회 금액 단위: 원</caption><thead><tr><th v-for="column in columns" :key="column.key">{{ column.label }}</th></tr></thead><tbody><tr v-for="(row, index) in result.rows" :key="index"><td v-for="column in columns" :key="column.key">{{ cell(row, column.key) }}</td></tr></tbody></table></div>
    <template v-if="['GAP', 'LIST'].includes(block.type) && result">
      <div class="reconciliation"><span>Gap 합계 <strong>{{ formatMoney(result.gapTotal) }}원</strong></span><span>분류 합계 <strong>{{ formatMoney(result.mappedTotal) }}원</strong></span><span>미분류(기타) <strong>{{ formatMoney(result.unclassifiedTotal) }}원</strong></span></div>
      <ul v-if="result.reasonTotals?.length"><li v-for="item in result.reasonTotals" :key="item.reasonTypeId || item.id">{{ item.name }}{{ item.aggregate ? ' (하위 합계)' : '' }}: {{ formatMoney(item.amount) }}원</li></ul>
    </template>
    <details v-if="result?.sources?.length"><summary>집계 출처 {{ result.sources.length }}건</summary><ul><li v-for="(source, index) in result.sources" :key="index"><RouterLink v-if="source.submissionId" :to="`/submissions/${source.submissionId}`">{{ source.organizationName || '원천 제출본' }} · {{ source.versionLabel || source.submissionId }}</RouterLink><span v-else>{{ source.name || source.masterId || source }}</span><span v-if="source.sheet"> · {{ source.sheet }}!{{ source.cell }}</span></li></ul></details>
  </section>
</template>

<style scoped>
.pie-chart{display:flex;align-items:center;gap:20px;margin:18px 0}.pie-chart svg{width:180px;flex-shrink:0}.pie-chart ul{list-style:none;padding:0;font-size:12px;line-height:1.8}.pie-chart i{display:inline-block;width:9px;height:9px;margin-right:7px}
.block-output{border:1px solid var(--line);border-radius:10px;padding:20px;background:#fff;position:relative}.block-output header{display:flex;justify-content:space-between;gap:16px;align-items:center;margin-bottom:12px}.block-output h3{font-size:1.1em}.state-tag{font-size:12px;color:var(--muted);white-space:nowrap}.provisional{border-color:#d4a354;background:#fffcf4}.provisional-label{color:#8b6018;font-size:12px;margin-bottom:12px}.report-text{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.8;margin:12px 0}.block-output caption{text-align:left;font-size:12px;color:var(--muted);padding:8px 0}.block-output table{font-size:inherit}.block-output td{font-variant-numeric:tabular-nums;white-space:nowrap}.block-warnings{color:#8b6018;padding-left:20px}.block-output details{font-size:12px;margin-top:18px}.block-output details ul{padding-left:20px;line-height:1.9}.bar-chart{display:flex;gap:12px;overflow:auto;margin:20px 0}.bar-column{flex:1;min-width:45px;text-align:center;font-size:11px}.bar-area{height:170px;position:relative;margin-bottom:10px}.bar{position:absolute;left:20%;width:60%;background:#466ba0;border-radius:3px}.bar.negative{background:#bf6254}.zero-line{position:absolute;left:0;right:0;border-top:1px solid #aaa}.reconciliation{display:flex;flex-wrap:wrap;gap:16px;margin-top:16px;font-size:13px}.reconciliation span{display:grid;gap:6px}
</style>
