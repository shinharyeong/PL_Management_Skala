<script setup>
import { computed, ref } from 'vue'
import { isTextual, monthCell, periodText } from '../lib/report-studio.js'
import { kindLabel } from '../lib/structure-review.js'

/** 보고서 만들기 3단계(데이터): 공통 기준 월과 블록별 기간·단위. 서버 호출은 하지 않고 이벤트로 알린다.
 * Props: definition 자료 틀, base {year, month}, periods 서버가 준 월별 상태, metrics 등록된 지표, busy.
 * Emits: year(숫자), month(숫자), period(block, rule), offset(block, 개월), edited, addMetric(id). 단위는 block.unit 을 직접 바꾼다. */
const props = defineProps({
  definition: { type: Object, required: true },
  base: { type: Object, required: true },
  periods: { type: Object, default: null },
  metrics: { type: Array, default: () => [] },
  busy: { type: Boolean, default: false },
})
const emit = defineEmits(['year', 'month', 'period', 'offset', 'edited', 'addMetric'])
const open = ref(null)
const UNITS = ['원', '천원', '만원', '백만원', '억원', '조원']
const allUnit = ref('')
const STATE_LABEL = { def: '확정 자료', prov: '잠정 자료', none: '데이터 없음' }
const picked = computed(() => cells.value.find(c => c.month === props.base.month))
function applyAll(unit) { if (!unit) return; for (const block of dataBlocks.value) block.unit = unit; emit('edited') }
const years = computed(() => [props.base.year - 1, props.base.year, props.base.year + 1])
const cells = computed(() => Array.from({ length: 12 }, (_, i) => {
  const month = i + 1
  return { month, ...monthCell((props.periods?.months || []).find(item => item.month === month)) }
}))
const dataBlocks = computed(() => props.definition.blocks.filter(block => !isTextual(block)))
const compareText = block => (block.spec?.compare || []).join(', ') || '—'
const pick = cell => { if (cell.cls !== 'none' && !props.busy) emit('month', cell.month) }
const note = computed(() => {
  const state = props.periods?.months?.find(item => item.month === props.base.month)?.state
  return state === 'PROVISIONAL' ? '잠정 자료는 승인된 조직만 포함할 수 있습니다.' : state === 'CONFIRMED' ? '확정 마스터를 기준으로 조회합니다.' : '선택한 월의 데이터가 없습니다.'
})
</script>

<template>
  <div class="rs-dsel">
    <section class="rs-panel">
      <b style="font-size:16px">공통 기준 월</b>
      <div class="rs-picked" :class="picked?.cls"><span class="y">{{ base.year }}년</span><span class="m">{{ base.month }}월</span><span class="st">{{ STATE_LABEL[picked?.cls] || '' }}</span></div>
      <div class="rs-yrs"><button v-for="y in years" :key="y" type="button" :class="{ on: y === base.year }" :disabled="busy" @click="y !== base.year && emit('year', y)">{{ y }}년</button></div>
      <div class="rs-mgrid">
        <button v-for="c in cells" :key="c.month" type="button" class="rs-mbtn" :class="[c.cls, { on: c.month === base.month }]" :disabled="busy || c.cls === 'none'" :aria-pressed="c.month === base.month" @click="pick(c)">{{ c.month }}월<small>{{ c.label }}</small></button>
      </div>
      <div class="rs-hint">{{ note }}</div>
    </section>

    <section class="rs-panel">
      <div class="rs-row" style="justify-content:space-between"><b style="font-size:16px">블록별 기간 · 단위</b>
        <label class="rs-row rs-cap" for="rs-all-unit">모든 블록 금액 단위
          <select id="rs-all-unit" v-model="allUnit" class="rs-input" style="width:auto;height:34px" :disabled="busy" @change="applyAll(allUnit)"><option value="">선택</option><option v-for="u in UNITS" :key="u" :value="u">{{ u }}</option></select></label></div>
      <div class="rs-cap">행을 누르면 기간과 단위를 바꿀 수 있습니다. 비워 두면 공통 기준 월을 따릅니다.</div>
      <div class="rs-tbl">
        <div class="rs-tr th"><span>양식 블록</span><span>기간</span><span>단위</span><span>비교 기준</span></div>
        <template v-for="block in dataBlocks" :key="block.id">
          <button type="button" class="rs-tr" :class="{ sel: open === block.id }" @click="open = open === block.id ? null : block.id">
            <span>{{ block.title || `${kindLabel(block.kind)} · ${block.id}` }}</span><span>{{ periodText(block) }}</span><span>{{ block.unit || '억원' }}</span><span>{{ compareText(block) }}</span>
          </button>
          <div v-if="open === block.id" class="rs-pctl">
            <span>기준 월과의 관계</span>
            <button type="button" :class="{ on: (block.period?.rule || 'base') === 'base' }" :disabled="busy" @click="emit('period', block, 'base')">기준 월</button>
            <button type="button" :class="{ on: block.period?.rule === 'base_offset' }" :disabled="busy" @click="emit('period', block, 'base_offset')">기준 월에서 이동</button>
            <template v-if="block.period?.rule === 'base_offset'">
              <label :for="`rs-off-${block.id}`">이동(개월)</label>
              <input :id="`rs-off-${block.id}`" type="number" min="-24" max="24" step="1" :value="block.period.offset" :disabled="busy" @change="emit('offset', block, $event.target.value === '' ? 0 : Number($event.target.value))" />
            </template>
            <label :for="`rs-unit-${block.id}`">금액 단위</label>
            <select :id="`rs-unit-${block.id}`" v-model="block.unit" :disabled="busy" @change="emit('edited')"><option v-for="u in UNITS" :key="u" :value="u">{{ u }}</option></select>
          </div>
        </template>
        <div v-if="!dataBlocks.length" class="rs-tr" style="grid-template-columns:1fr"><span class="rs-cap">숫자를 채울 블록이 없습니다. 이전 단계에서 표·차트·숫자 카드를 확인해 주세요.</span></div>
      </div>
      <div v-if="metrics.length" class="rs-row"><span class="rs-cap">등록된 지표 추가</span>
        <button v-for="metric in metrics" :key="metric.id" type="button" class="rs-btn ghost" :disabled="busy" @click="emit('addMetric', metric.id)">+ {{ metric.name }}</button></div>
    </section>
  </div>
</template>
