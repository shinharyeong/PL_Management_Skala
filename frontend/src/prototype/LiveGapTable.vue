<script setup>
import copy from './copy-text.js'
import {hierarchyTableRows,expandedGapItems} from './hierarchy-table-rows.js'
import { calculatorStage, calculatorStages, canSelectForReview } from './calculator-stages.js'
import { computed, ref, onMounted, onBeforeUnmount } from 'vue'
import { gapColumnMinimums, resizeGapColumn, fitGapColumns } from './resizable-columns.js'
import { sum, subtract, percent, compare } from './exact-money.js'
import { formatMoney } from '../lib/decimal-display.js'
import { statuses, statusTones } from './domain.js'
import './monthly-live.css'

const props = defineProps({ rows: Array, selected: Array, unit: String, type: String, editable: Boolean, groupedStatuses: Boolean })
const emit = defineEmits(['select', 'all'])
const collapsedMajors = ref(new Set())
function toggleMajor(name){const next=new Set(collapsedMajors.value);next.has(name)?next.delete(name):next.add(name);collapsedMajors.value=next}
const columnWidths = defineModel('columnWidths', { default: null })
const table = ref(null), container = ref(null), resizing = ref(false)
let observer
let drag = null
const columns = computed(() => [
  copy.gapTableText.text001,
  props.type === '추정 vs 추정' ? '직전 추정' : '계획값',
  props.type === '실적 vs 계획' ? '실적' : '추정',
  copy.gapTableText.text002, copy.gapTableText.text003, copy.gapTableText.text004,
])
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
const tone = status => props.groupedStatuses ? calculatorStages.find(stage => stage.label === calculatorStage(status))?.tone || 'excluded' : statusTones[statuses.indexOf(status)] || 'excluded'
const selectable = row => props.editable && canSelectForReview(row)
const selectableRows = computed(() => expandedGapItems(props.rows,collapsedMajors.value).filter(selectable))
const allSelected = computed(() => selectableRows.value.length > 0 && selectableRows.value.every(row => props.selected.includes(row.id)))
const rate = row => {
  const value = percent(row.base, row.current)
  return value == null ? '산정 불가' : `${compare(value, '0') > 0 ? '+' : ''}${value}%`
}
const delta = row => {
  const value = subtract(row.current, row.base)
  return `${compare(value, '0') > 0 ? '+' : ''}${money(value)}`
}
// Each gutter spans its descendants and closing row. Every data row keeps the
// same five financial columns, regardless of its depth in the hierarchy.
const groupedRows = computed(() => hierarchyTableRows(props.rows,collapsedMajors.value))

</script>

<template>
  <div ref="container" class="table-scroll hierarchy-table live-gap-table" :class="{'is-resizing':resizing}" tabindex="0" role="region" aria-label="Gap 계산 결과">
    <table ref="table" :style="tableStyle">
      <colgroup><col v-for="level in 3" :key="level" class="live-hierarchy-gutter" /><col v-for="(_,index) in columns" :key="index" :class="index===0?'live-hierarchy-name':index<4?'live-hierarchy-amount':index===4?'live-hierarchy-rate':'live-hierarchy-status'" :style="columnStyle(index)" /></colgroup>
      <thead><tr><th v-for="(label,index) in columns" :key="index" :colspan="index===0?4:1" :class="{number:index>0&&index<5}" :title="label">
        <label v-if="index===0" class="live-hierarchy-heading"><input type="checkbox" aria-label="표시 항목 모두 선택" :disabled="!selectableRows.length" :checked="allSelected" @change="emit('all', $event.target.checked, selectableRows.map(row=>row.id))" /><span data-copy-key="gapTableText.text001">{{ label }}</span></label>
        <span v-else :data-copy-key="index>=3?'gapTableText.text00'+(index-1):undefined">{{ label }}</span>
        <span v-if="index<columns.length-1" class="column-resizer" role="separator" aria-orientation="vertical" tabindex="0" :aria-label="label+' 열 너비 조절'" :aria-valuemin="gapColumnMinimums[index]" :aria-valuemax="Math.max(10000,columnWidths?.[index] || 0)" :aria-valuetext="columnWidths ? Math.round(columnWidths[index])+'픽셀' : '자동 너비'" :aria-valuenow="columnWidths ? Math.round(columnWidths[index]) : undefined" title="드래그하여 열 너비 조절 · ← → 키로 조절" @pointerdown.stop.prevent="startResize($event,index)" @pointermove="moveResize" @pointerup="finishResize" @pointercancel="finishResize" @lostpointercapture="finishResize" @keydown.left.stop.prevent="resizeBy(index,$event.shiftKey?-1:-10)" @keydown.right.stop.prevent="resizeBy(index,$event.shiftKey?1:10)" @click.stop.prevent />
      </th></tr></thead>
      <tbody>
        <tr v-for="row in groupedRows" :key="row.key" :class="row.close ? 'hierarchy-close' : [['major-row','middle-row','minor-row','detail-row'][row.level], {checked:row.id && selected.includes(row.id)}]">
          <template v-if="row.close"><td :colspan="8 - row.level" :class="['major-rail','middle-rail','minor-rail'][row.level]" aria-hidden="true"></td></template>
          <template v-else>
            <td v-for="rail in row.rails" :key="rail.level" :rowspan="rail.span" class="hierarchy-rail" :class="['major-rail','middle-rail','minor-rail'][rail.level]" aria-hidden="true"></td>
            <th v-if="row.level < 3" :colspan="4 - row.level" scope="row"><button v-if="row.level===0" type="button" class="major-collapse-button" :aria-expanded="!row.collapsed" :aria-label="row.name+(row.collapsed?' 하위 항목 펼치기':' 하위 항목 접기')" @click="toggleMajor(row.name)"><span class="major-collapse-icon" aria-hidden="true">{{row.collapsed?'▸':'▾'}}</span>대 · {{row.name}}</button><template v-else>{{ ['대','중','소'][row.level] }} · {{ row.name }}</template></th>
            <td v-else class="hierarchy-detail"><label class="live-hierarchy-item" :class="{'is-selectable':selectable(row)}"><input type="checkbox" :aria-label="row.name + ' 선택'" :checked="selected.includes(row.id)" :disabled="!selectable(row)" @change="emit('select',row.id)" /><span>{{ row.name }}</span></label></td>
            <td class="number" :title="money(row.base)">{{ money(row.base) }}</td><td class="number" :title="money(row.current)">{{ money(row.current) }}</td><td class="number" :title="delta(row)" :class="compare(row.current,row.base) < 0 ? 'negative' : 'positive'">{{ delta(row) }}</td><td class="number" :title="rate(row)">{{ rate(row) }}</td><td><span v-if="row.status" class="badge" :class="tone(row.status)" :title="groupedStatuses ? row.status : undefined">{{ groupedStatuses ? calculatorStage(row.status) : row.status }}</span></td>
          </template>
        </tr>
      </tbody>
    </table>
  </div>
</template>
