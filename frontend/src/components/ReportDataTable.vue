<script setup>
import { computed } from 'vue'
import PDataTable from 'primevue/datatable'
import PColumn from 'primevue/column'
import PColumnGroup from 'primevue/columngroup'
import PRow from 'primevue/row'
import { reportCell } from '../prototype/report-api.js'
import { reportBodyCell, reportTableModel } from '../lib/report-display.js'

const props = defineProps({ data: { type: Object, required: true } })
const model = computed(() => reportTableModel(props.data))

/** 현재 본문 셀에 병합 속성을 전달한다.
 * Args: column 원본 열 좌표.
 * Returns: PrimeVue Column pass-through 설정. Raises: 없음. Side Effects: 없음.
 * 본문 세로 병합은 rowSpan DOM 속성으로 전달해 내부 rowspan=null과 분리한다.
 */
function columnPassThrough(column) {
  return { bodyCell: context => reportBodyCell(context.parent.props.rowData, column) }
}
</script>

<template>
  <PDataTable v-if="model.width" :value="model.rows" data-key="id" show-gridlines size="small" class="report-data-table" :pt="{table: {'aria-label':'보고서 데이터'}}">
    <PColumnGroup v-if="model.headers.length" key="report-header-group" type="header">
      <PRow v-for="(row,index) in model.headers" :key="index"><PColumn v-for="cell in row" :key="cell.key" :header="cell.text" :colspan="cell.colspan" :rowspan="cell.rowspan" /></PRow>
    </PColumnGroup>
    <PColumn v-for="(_,index) in model.width" :key="index" :field="`cells.${index}`" :pt="columnPassThrough(index)">
      <template #body="{ data: row }">{{ reportCell(row.cells[index]) }}</template>
    </PColumn>
    <template #empty>조회된 데이터가 없습니다.</template>
  </PDataTable>
</template>

<style scoped>
.report-data-table{margin:1rem 0;max-width:100%}.report-data-table :deep(td),.report-data-table :deep(th){white-space:pre-wrap;min-width:5rem;font-variant-numeric:tabular-nums}
@media print{.report-data-table :deep(.p-datatable-table-container){overflow:visible}}
</style>
