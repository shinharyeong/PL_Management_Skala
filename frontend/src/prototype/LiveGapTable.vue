<script setup>
import { computed, ref, watch } from 'vue'
import PButton from 'primevue/button'
import PCheckbox from 'primevue/checkbox'
import PColumn from 'primevue/column'
import PTag from 'primevue/tag'
import PTreeTable from 'primevue/treetable'
import { subtract, percent, compare } from './exact-money.js'
import { formatMoney } from '../lib/decimal-display.js'
import { buildGapTree, canSelectGapItem } from './monthly-view-model.js'
import './monthly-live.css'

const props = defineProps({ rows: Array, selected: Array, unit: String, type: String, editable: Boolean })
const emit = defineEmits(['select', 'detail', 'all'])
const expandedKeys = ref({})

/** 금액 문자열을 선택한 표시 단위로 변환한다.
 * Args: value 원 단위 금액. Returns: 표시 문자열. Raises: 없음. Side Effects: 없음.
 */
function money(value) { return formatMoney(value, props.unit) }

/** 현재 제출본에서 검토 대상으로 선택할 수 있는 항목인지 판정한다.
 * Args: row 검토 항목. Returns: 선택 가능 여부. Raises: 없음. Side Effects: 없음.
 */
function selectable(row) { return canSelectGapItem(row, props.editable) }

const selectableRows = computed(() => props.rows.filter(selectable))
const allSelected = computed(() => selectableRows.value.length > 0 && selectableRows.value.every(row => props.selected.includes(row.id)))
const partiallySelected = computed(() => !allSelected.value && selectableRows.value.some(row => props.selected.includes(row.id)))

/** 검토 상태를 PrimeVue Tag의 의미 색상으로 변환한다.
 * Args: status 업무 상태. Returns: Tag severity. Raises: 없음. Side Effects: 없음.
 */
function severity(status) {
  return { '검토 필요': 'warn', '검토 완료': 'info', '소명 진행중': 'info', '재검토 필요': 'danger', '소명 승인 완료': 'success', '제외됨': 'secondary' }[status] || 'secondary'
}

/** 합계 또는 상세 항목의 차이율을 정밀 연산으로 표시한다.
 * Args: row 기준값과 현재값. Returns: 차이율 문자열. Raises: 없음. Side Effects: 없음.
 */
function rate(row) {
  const value = percent(row.base, row.current)
  return value == null ? '산정 불가' : `${compare(value, '0') > 0 ? '+' : ''}${value}%`
}

/** 합계 또는 상세 항목의 차액을 정밀 연산으로 표시한다.
 * Args: row 기준값과 현재값. Returns: 부호를 포함한 표시 금액. Raises: 없음. Side Effects: 없음.
 */
function delta(row) {
  const value = subtract(row.current, row.base)
  return `${compare(value, '0') > 0 ? '+' : ''}${money(value)}`
}

const groupedNodes = computed(() => buildGapTree(props.rows))

/** 새 계산 결과의 분류를 처음에는 모두 펼쳐 상세 항목을 바로 볼 수 있게 한다.
 * Args: nodes 계산 결과 트리. Returns: 없음. Raises: 없음. Side Effects: 펼침 상태를 갱신한다.
 */
function expandGroups(nodes) {
  const keys = {}, pending = [...nodes]
  while (pending.length) {
    const node = pending.pop()
    if (node.children?.length) { keys[node.key] = true; pending.push(...node.children) }
  }
  expandedKeys.value = keys
}
watch(groupedNodes, expandGroups, { immediate: true })
</script>

<template>
  <PTreeTable v-model:expandedKeys="expandedKeys" :value="groupedNodes" class="live-gap-table" scrollable tableStyle="min-width: 1150px" :pt="{ table: { 'aria-label': 'Gap 계산 결과' } }">
    <PColumn expander style="min-width: 360px">
      <template #header><div class="live-hierarchy-item"><PCheckbox binary :modelValue="allSelected" :indeterminate="partiallySelected" :disabled="!selectableRows.length" aria-label="표시 항목 모두 선택" @update:modelValue="emit('all', $event)" /><span>항목</span></div></template>
      <template #body="{ node }">
        <span v-if="node.data.level < 3" :class="['live-group-label', `gap-level-${node.data.level}`]">{{ ['대', '중', '소'][node.data.level] }} · {{ node.data.name }}</span>
        <div v-else class="live-hierarchy-item gap-level-3" :class="{ 'gap-selected': selected.includes(node.data.id) }"><PCheckbox binary :modelValue="selected.includes(node.data.id)" :disabled="!selectable(node.data)" :aria-label="node.data.name + ' 선택'" @update:modelValue="emit('select', node.data.id)" /><PButton variant="text" class="cell-link" :label="node.data.name" @click="emit('detail', node.data)" /></div>
      </template>
    </PColumn>
    <PColumn :header="type === '추정 vs 추정' ? '직전 추정' : '계획값'" bodyClass="number" style="min-width: 170px"><template #body="{ node }">{{ money(node.data.base) }}</template></PColumn>
    <PColumn :header="type === '실적 vs 계획' ? '실적' : '추정'" bodyClass="number" style="min-width: 170px"><template #body="{ node }">{{ money(node.data.current) }}</template></PColumn>
    <PColumn header="차액" bodyClass="number" style="min-width: 170px"><template #body="{ node }"><span :class="compare(node.data.current, node.data.base) < 0 ? 'negative' : 'positive'">{{ delta(node.data) }}</span></template></PColumn>
    <PColumn header="차이율" bodyClass="number" style="min-width: 130px"><template #body="{ node }">{{ rate(node.data) }}</template></PColumn>
    <PColumn header="진행 단계" style="min-width: 150px"><template #body="{ node }"><PTag v-if="node.data.status" :value="node.data.status" :severity="severity(node.data.status)" /></template></PColumn>
  </PTreeTable>
</template>
