<script setup>
import { computed } from 'vue'
import PChart from 'primevue/chart'
import PPanel from 'primevue/panel'
import PDataTable from 'primevue/datatable'
import PColumn from 'primevue/column'
import { reportChartModel } from '../lib/report-display.js'

const props = defineProps({ block: { type: Object, required: true } })
const model = computed(() => reportChartModel(props.block))
const palette = ['#2563eb', '#0d9488', '#8b5cf6', '#d97706', '#e11d48', '#64748b']
const data = computed(() => ({ labels: model.value.labels, datasets: [{ label: props.block.title || props.block.metric || '금액', data: model.value.values, backgroundColor: model.value.rows.map((_,i) => palette[i % palette.length]), borderColor: '#2563eb', borderWidth: 2 }] }))
const options = computed(() => ({ responsive: true, maintainAspectRatio: false, animation: false,
  plugins: { legend: { display: model.value.type === 'doughnut' }, tooltip: { callbacks: { label: exactTooltip } } },
  ...(model.value.type === 'doughnut' ? {} : { scales: { y: { beginAtZero: true } } }),
}))

/** 차트 근사 좌표 대신 서버가 반환한 금액을 표시한다.
 * Args: context Chart.js tooltip 항목.
 * Returns: 원본 표시 문자열. Raises: 없음. Side Effects: 없음.
 */
function exactTooltip(context) { return String(model.value.rows[context.dataIndex]?.text ?? '데이터 없음') }
</script>

<template>
  <div class="report-chart-view">
    <PChart :type="model.type" :data="data" :options="options" class="report-chart-canvas" :canvasProps="{ role: 'img', 'aria-label': block.title || block.metric || '보고서 차트' }" />
    <PPanel header="차트 데이터" toggleable collapsed>
      <PDataTable :value="model.rows" data-key="id" size="small"><PColumn field="name" header="항목" /><PColumn field="text" header="금액" /><template #empty>조회된 데이터가 없습니다.</template></PDataTable>
    </PPanel>
  </div>
</template>

<style scoped>
.report-chart-view{display:grid;gap:1rem;margin:1rem 0}.report-chart-canvas{height:300px;min-width:0}
</style>
