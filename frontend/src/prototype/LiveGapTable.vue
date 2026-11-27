<script setup>
import copy from './copy-text.js'
import {hierarchyTableRows,expandedGapItems} from './hierarchy-table-rows.js'
import { calculatorStage, calculatorStages, canSelectForReview } from './calculator-stages.js'
import { computed, ref, onMounted, onBeforeUnmount, useId } from 'vue'
import { gapColumnMinimums, resizeGapColumn, fitGapColumns } from './resizable-columns.js'
import { compare } from './exact-money.js'
import { formatMoney } from '../lib/decimal-display.js'
import { statuses, statusTones } from './domain.js'
import './monthly-live.css'
import {calculatorComparisons} from './calculator-values.js'

const props = defineProps({ rows: Array, selected: Array, unit: String, type: String, editable: Boolean, groupedStatuses: Boolean, openableIds: { type: Array, default: () => [] } })
const emit = defineEmits(['select', 'all', 'detail'])
const collapsedMajors = ref(new Set())
function toggleMajor(name){const next=new Set(collapsedMajors.value);next.has(name)?next.delete(name):next.add(name);collapsedMajors.value=next}
const columnWidths = defineModel('columnWidths', { default: null })
const table = ref(null), container = ref(null), resizing = ref(false)
let observer
let drag = null
const columns = computed(() => [copy.gapTableText.text001, '계획', '이전 추정', '실적', '추정', copy.gapTableText.text004])
const amountFields = ['planAmount','previousEstimate','actualAmount','estimateAmount']
const tooltip=ref(null),tooltipId=useId()
function showComparison(event,row){
  if(row.level!==3)return
  const rect=event.currentTarget.getBoundingClientRect(),width=Math.min(360,window.innerWidth-24)
  const x=event.clientX ?? rect.left+24,y=event.clientY ?? rect.bottom
  tooltip.value={row,comparisons:calculatorComparisons(row),style:{width:width+'px',left:Math.max(12,Math.min(x+14,window.innerWidth-width-12))+'px',top:Math.max(12,Math.min(y+14,window.innerHeight-240))+'px'}}
}
function hideComparison(){tooltip.value=null}
const tableStyle = computed(() => columnWidths.value ? { width: columnWidths.value.reduce((a,b)=>a+b,0)+'px', minWidth:'0' } : {})
const columnStyle = index => columnWidths.value ? { width: (columnWidths.value[index] - (index === 0 ? 30 : 0))+'px' } : {}
function measuredWidths() {
  const widths=columnWidths.value || Array.from(table.value.querySelectorAll('thead th'), th=>th.getBoundingClientRect().width)
  return fitGapColumns(widths,container.value.clientWidth)
}
onMounted(()=>{
  observer=new ResizeObserver(()=>{if(drag)finishResize();columnWidths.value=measuredWidths()})
  observer.observe(container.value)
})
function startResize(event, index) {
  if (event.button !== 0 || drag) return
  drag = { index, pointerId:event.pointerId, startX:event.clientX, widths:[...measuredWidths()], target:event.currentTarget }
  event.currentTarget.setPointerCapture(event.pointerId)
  resizing.value = true
}
function moveResize(event) {
  if (drag?.pointerId !== event.pointerId) return
  columnWidths.value = resizeGapColumn(drag.widths, drag.index, event.clientX-drag.startX)
}
function finishResize(event) {
  if (!drag || (event && event.pointerId !== drag.pointerId)) return
  if (event?.type === 'pointerup') moveResize(event)
  const {target,pointerId} = drag
  drag = null
  resizing.value = false
  if (target.hasPointerCapture(pointerId)) target.releasePointerCapture(pointerId)
}
function resizeBy(index, delta) { columnWidths.value = resizeGapColumn(measuredWidths(), index, delta) }
onBeforeUnmount(()=>{observer?.disconnect();finishResize()})
const money = value => formatMoney(value, props.unit)
const amount = value => value == null ? '' : money(value)
const signed = value => value == null ? '비교값 없음' : `${compare(value,'0')>0?'+':''}${money(value)}`
const signedRate = value => value == null ? '산정 불가' : `${compare(value,'0')>0?'+':''}${value}%`
const tone = status => props.groupedStatuses ? calculatorStages.find(stage => stage.label === calculatorStage(status))?.tone || 'excluded' : statusTones[statuses.indexOf(status)] || 'excluded'
const selectable = row => props.editable && canSelectForReview(row)
const selectableRows = computed(() => groupedRows.value.filter(row=>row.id).filter(selectable))
const allSelected = computed(() => selectableRows.value.length > 0 && selectableRows.value.every(row => props.selected.includes(row.id)))
// Each gutter spans its descendants and closing row. Every data row keeps the
// same five financial columns, regardless of its depth in the hierarchy.
const groupedRows = computed(() => hierarchyTableRows(props.rows,collapsedMajors.value))

</script>

<template>
  <div ref="container" class="table-scroll hierarchy-table live-gap-table" :class="{'is-resizing':resizing}" tabindex="0" role="region" aria-label="Gap 계산 결과" @scroll="hideComparison" @keydown.esc="hideComparison">
    <table ref="table" :style="tableStyle">
      <colgroup><col v-for="level in 3" :key="level" class="live-hierarchy-gutter" /><col v-for="(_,index) in columns" :key="index" :class="index===0?'live-hierarchy-name':index<5?'live-hierarchy-amount':'live-hierarchy-status'" :style="columnStyle(index)" /></colgroup>
      <thead><tr><th v-for="(label,index) in columns" :key="index" :colspan="index===0?4:1" :class="{number:index>0&&index<5}" :title="label">
        <label v-if="index===0" class="live-hierarchy-heading"><input type="checkbox" aria-label="표시 항목 모두 선택" :disabled="!selectableRows.length" :checked="allSelected" @change="emit('all', $event.target.checked, selectableRows.map(row=>row.id))" /><span data-copy-key="gapTableText.text001">{{ label }}</span></label>
        <span v-else :data-copy-key="index===5?'gapTableText.text004':undefined">{{ label }}</span>
        <span v-if="index<columns.length-1" class="column-resizer" role="separator" aria-orientation="vertical" tabindex="0" :aria-label="label+' 열 너비 조절'" :aria-valuemin="gapColumnMinimums[index]" :aria-valuemax="Math.max(10000,columnWidths?.[index] || 0)" :aria-valuetext="columnWidths ? Math.round(columnWidths[index])+'픽셀' : '자동 너비'" :aria-valuenow="columnWidths ? Math.round(columnWidths[index]) : undefined" title="드래그하여 열 너비 조절 · ← → 키로 조절" @pointerdown.stop.prevent="startResize($event,index)" @pointermove="moveResize" @pointerup="finishResize" @pointercancel="finishResize" @lostpointercapture="finishResize" @keydown.left.stop.prevent="resizeBy(index,$event.shiftKey?-1:-10)" @keydown.right.stop.prevent="resizeBy(index,$event.shiftKey?1:10)" @click.stop.prevent />
      </th></tr></thead>
      <tbody>
        <tr v-for="row in groupedRows" :key="row.key" :class="row.close ? 'hierarchy-close' : [['major-row','middle-row','minor-row','detail-row'][row.level], {checked:row.id && selected.includes(row.id)}]" :tabindex="row.level===3&&!row.close?0:undefined" :aria-describedby="tooltip?.row.key===row.key?tooltipId:undefined" @mouseenter="showComparison($event,row)" @mouseleave="hideComparison" @focusin="showComparison($event,row)" @focusout="hideComparison">
          <template v-if="row.close"><td :colspan="8 - row.level" :class="['major-rail','middle-rail','minor-rail'][row.level]" aria-hidden="true"></td></template>
          <template v-else>
            <td v-for="rail in row.rails" :key="rail.level" :rowspan="rail.span" class="hierarchy-rail" :class="['major-rail','middle-rail','minor-rail'][rail.level]" aria-hidden="true"></td>
            <th v-if="row.level < 3" :colspan="4 - row.level" scope="row"><button v-if="row.level===0" type="button" class="major-collapse-button" :aria-expanded="!row.collapsed" :aria-label="row.name+(row.collapsed?' 하위 항목 펼치기':' 하위 항목 접기')" @click="toggleMajor(row.name)"><span class="major-collapse-icon" aria-hidden="true">{{row.collapsed?'▸':'▾'}}</span>대 · {{row.name}}</button><button v-else-if="row.level===2" type="button" class="major-collapse-button item-collapse-button" :aria-expanded="!row.collapsed" :aria-label="row.name+(row.collapsed?' 월별 항목 펼치기':' 월별 항목 접기')" @click="toggleMajor(row.key)"><span class="major-collapse-icon" aria-hidden="true">{{row.collapsed?'▸':'▾'}}</span>소 · {{row.name}}</button><template v-else>{{ ['대','중','소'][row.level] }} · {{ row.name }}</template></th>
            <td v-else class="hierarchy-detail"><label class="live-hierarchy-item" :class="{'is-selectable':selectable(row)}"><input type="checkbox" :aria-label="row.name + ' 선택'" :checked="selected.includes(row.id)" :disabled="!selectable(row)" :title="row.status === '재검토 필요' ? '조직이 회신한 항목입니다. 항목 이름을 눌러 회신을 확인하고 수락하거나 보완을 요청하세요.' : undefined" @change="emit('select',row.id)" /><button v-if="openableIds.includes(row.id)" type="button" class="cell-link" title="항목을 열어 비교·소명 회신을 확인합니다" @click.prevent.stop="emit('detail',row)">{{ row.name }}</button><span v-else>{{ row.name }}</span></label></td>
            <td v-for="field in amountFields" :key="field" class="number" :class="{'is-missing':row.level===3 && row[field]==null}" :aria-label="row.level===3 && row[field]==null?'값 없음':undefined">{{ row.level===3 ? amount(row[field]) : '' }}</td><td><span v-if="row.status" class="badge" :class="tone(row.status)" :title="groupedStatuses ? (row.status==='재검토 필요'?'회신 도착':row.status==='검토 완료'?'검토 전':undefined) : undefined">{{ groupedStatuses ? (row.status==='재검토 필요'?'회신 도착':['검토 필요','검토 완료'].includes(row.status)?'검토 전':calculatorStage(row.status)) : row.status }}</span></td>
          </template>
        </tr>
      </tbody>
    </table>
  </div>
  <Teleport to="body"><div v-if="tooltip" :id="tooltipId" class="gap-comparison-tooltip" role="tooltip" :style="tooltip.style">
    <strong class="gap-tooltip-title">{{tooltip.row.name}}</strong>
    <div v-for="comparison in tooltip.comparisons" :key="comparison.label" class="gap-tooltip-comparison">
      <b>{{comparison.label}}</b><dl><div><dt>차액 <small>({{unit==='HUNDRED_MILLION'?'억원':'천원'}})</small></dt><dd :class="comparison.diff==null?'':compare(comparison.diff,'0')<0?'negative':'positive'">{{signed(comparison.diff)}}</dd></div><div><dt>차이율</dt><dd>{{comparison.base==null||comparison.current==null?'비교값 없음':signedRate(comparison.rate)}}</dd></div></dl>
    </div>
  </div></Teleport>
</template>
