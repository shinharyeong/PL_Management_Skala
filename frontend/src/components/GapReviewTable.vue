<script setup>
import { computed, ref } from 'vue'
import { formatMoney } from '../lib/decimal-display'

const props = defineProps({ rows: { type: Array, required: true }, catalog: { type: Array, default: () => [] }, unit: { type: String, default: 'THOUSAND' }, sort: { type: String, default: 'amount-desc' }, selected: { type: Array, default: () => [] }, comparisonType: { type: String, default: 'ACTUAL_PLAN' } })
const emit = defineEmits(['toggle', 'detail', 'source'])
const collapsed = ref([])
const states = { NEEDS_REVIEW: '1단계 · 검토 필요', SELECTED: '2단계 · 검토 완료', IN_PROGRESS: '3단계 · 소명 진행중', RE_REVIEW: '4단계 · 재검토', ACCEPTED: '5단계 · 승인 완료', EXCLUDED: '제외됨', OPTIONAL: '소명 대상 아님' }
const nodes = computed(buildTree)
const tableRows = computed(flattenTree)
const actionable = computed(() => props.rows.filter(row => row.actionable))
const allSelected = computed(() => actionable.value.length > 0 && actionable.value.every(row => props.selected.includes(row.selectionId || row.review.id)))

/** 실제 부모 관계를 유지하고 부모가 없는 매출·비용 항목은 원천 종류별 표시 그룹으로 묶는다.
 * Args: 없음. Returns: 계층 루트. Raises: 없음. Side Effects: 메모리만 사용; 표시 그룹은 ID·집계값을 생성하거나 DB에 저장하지 않는다.
 */
function buildTree() {
  const metadata = new Map(props.catalog.map(item => [item.id, item])), values = new Map(props.rows.map(row => [row.reportItem.id, row])), nodes = new Map()
  for (const row of props.rows) {
    let id = row.reportItem.id; const seen = new Set()
    while (id && !seen.has(id)) {
      seen.add(id)
      const meta = metadata.get(id) || row.reportItem
      if (!nodes.has(id)) nodes.set(id, { id, name: meta.name, code: meta.code, kind: meta.kind, parentId: meta.parentId, value: values.get(id), children: [] })
      id = meta.parentId
    }
  }
  const roots = []
  for (const node of nodes.values()) { const parent = nodes.get(node.parentId); if (parent && parent.id !== node.id) parent.children.push(node); else roots.push(node) }
  const groups = [{ id: 'kind:REVENUE', name: '매출', kind: 'REVENUE', children: [] }, { id: 'kind:COST', name: '영업비용', kind: 'COST', children: [] }]
  const result = []
  for (const node of roots) { const group = groups.find(item => item.kind === node.kind); if (group) group.children.push(node); else result.push(node) }
  return [...groups.filter(group => group.children.length), ...result]
}

/** 목록의 금액·비율을 부동소수점 손실 없이 절댓값 정렬용 정수로 변환한다.
 * Args: value 십진 문자열/null. Returns: 정수 또는 null. Raises: 없음. Side Effects: 없음.
 */
function decimalSort(value) { const match = /^(-?)(\d+)(?:\.(\d+))?$/.exec(String(value ?? '')); return match ? BigInt(match[2] + (match[3] || '').padEnd(8, '0').slice(0, 8)) : null }
/** 같은 계층의 항목 순서를 비교한다. Args: left/right 노드. Returns: 정렬값. Raises: 없음. Side Effects: 없음. */
function compareNodes(left, right) {
  const field = props.sort.startsWith('rate') ? 'gapRate' : 'gapAmount', a = decimalSort(left.value?.[field]), b = decimalSort(right.value?.[field])
  if (a === null && b !== null) return 1
  if (b === null && a !== null) return -1
  if (a !== b) return (a < b ? -1 : 1) * (props.sort.endsWith('desc') ? -1 : 1)
  return left.name.localeCompare(right.name, 'ko')
}
/** 접힌 가지를 제외한 행을 깊이와 함께 펼친다. Args: 없음. Returns: 표시 행들. Raises: 없음. Side Effects: 없음. */
function flattenTree() {
  const rows = [], visited = new Set()
  /** 재귀 가지를 안전하게 추가한다. Args: list 현재 노드들, depth 깊이. Returns: 없음. Raises: 없음. Side Effects: 지역 결과 배열 갱신. */
  function append(list, depth) { for (const node of [...list].sort(compareNodes)) { if (visited.has(node.id)) continue; visited.add(node.id); rows.push({ ...node, depth }); if (!collapsed.value.includes(node.id)) append(node.children, depth + 1) } }
  append(nodes.value, 0); return rows
}
/** 해당 가지의 열림을 전환한다. Args: id 보고항목ID. Returns: 없음. Raises: 없음. Side Effects: 접기 상태 갱신. */
function toggleBranch(id) { collapsed.value = collapsed.value.includes(id) ? collapsed.value.filter(value => value !== id) : [...collapsed.value, id] }
/** 현재 보이는 수정 가능한 검토를 일괄 선택한다. Args: checked 선택여부. Returns: 없음. Raises: 없음. Side Effects: 선택 이벤트.
 */
function selectAll(checked) { for (const row of actionable.value) emit('toggle', row.selectionId || row.review.id, checked) }
/** 금액 부호에 맞는 표현 클래스를 반환한다. Args: value 십진수. Returns: css 클래스. Raises: 없음. Side Effects: 없음. */
function signClass(value) { return value == null ? '' : String(value).startsWith('-') ? 'negative' : decimalSort(value) > 0n ? 'positive' : '' }
</script>

<template>
  <div v-if="!rows.length" class="empty"><strong>해당 조건의 항목이 없습니다.</strong><p>조직·조회 월·비교 대상 또는 상태 탭을 변경해 확인해 주세요.</p></div>
  <div v-else class="gap-table-scroll"><table class="gap-tree"><thead><tr><th><label class="all-check"><input type="checkbox" :checked="allSelected" :disabled="!actionable.length" aria-label="조회 항목 전체 선택" @change="selectAll($event.target.checked)" />항목 · 대 / 중 / 소 / 상세</label></th><th class="number">{{ comparisonType === 'ESTIMATE_PREVIOUS' ? '이전 추정' : '계획값' }}</th><th class="number">{{ comparisonType === 'ACTUAL_PLAN' ? '실적' : '현재 추정' }}</th><th class="number">차액</th><th class="number">차이율</th><th>진행 단계</th></tr></thead><tbody><tr v-for="node in tableRows" :key="node.id" :class="['tree-row', 'depth-' + Math.min(node.depth, 3), { branch: node.children.length, checked: selected.includes(node.value?.selectionId || node.value?.review?.id), excluded: node.value?.workflowState === 'EXCLUDED' }]"><th scope="row" :style="{ paddingLeft: (14 + node.depth * 18) + 'px' }"><span v-for="level in Math.min(node.depth, 5)" :key="level" class="branch-guide" :style="{ left: (level * 18 - 7) + 'px' }"></span><button v-if="node.children.length" class="branch-toggle" :aria-label="node.name + (collapsed.includes(node.id) ? ' 펼치기' : ' 접기')" :aria-expanded="!collapsed.includes(node.id)" @click="toggleBranch(node.id)">{{ collapsed.includes(node.id) ? '+' : '−' }}</button><input v-if="node.value?.selectionId || node.value?.review" type="checkbox" :checked="selected.includes(node.value.selectionId || node.value.review.id)" :disabled="!node.value.actionable" :aria-label="node.name + ' 선택'" @change="emit('toggle', node.value.selectionId || node.value.review.id, $event.target.checked)" /><button v-if="node.value?.review" class="item-detail" @click="emit('detail', node.value.review.id)">{{ node.name }}</button><button v-else-if="node.value" class="item-detail" @click="emit('source', node.value.reportItem)">{{ node.name }}</button><span v-else>{{ node.name }}</span></th><td class="number">{{ formatMoney(node.value?.baselineAmount, unit) }}</td><td class="number">{{ formatMoney(node.value?.currentAmount, unit) }}</td><td :class="['number', signClass(node.value?.gapAmount)]">{{ formatMoney(node.value?.gapAmount, unit) }}</td><td :class="['number', signClass(node.value?.gapRate)]">{{ node.value?.gapRate == null ? '—' : formatMoney(node.value.gapRate) + '%' }}</td><td class="workflow-cell"><button v-if="node.value?.review" :class="['stage-label', node.value.workflowState]" @click="emit('detail', node.value.review.id)">{{ states[node.value.workflowState] || '검토 필요' }}</button><span v-else-if="node.value" class="stage-label OPTIONAL">{{ node.value.valueStatus === 'MISSING_BASELINE' ? '비교 기준 없음' : node.value.valueStatus === 'MISSING_CURRENT' ? '현재 값 없음' : '소명 대상 아님' }}</span></td></tr></tbody></table></div>
</template>

<style scoped>
.gap-table-scroll{overflow:auto;max-height:600px;border:1px solid #e7e4de;border-radius:9px}.gap-tree{min-width:760px;table-layout:fixed;font-size:11px}.gap-tree thead{position:sticky;top:0;z-index:2}.gap-tree thead th{font-size:10px;background:#faf9f6;padding:12px 10px}.gap-tree th:first-child{width:34%}.gap-tree th:last-child{width:18%}.gap-tree tbody th{position:relative;text-align:left;font-size:11px;line-height:1.6;font-weight:500;white-space:normal;overflow-wrap:anywhere}.tree-row th,.tree-row td{height:44px;padding:10px;border-bottom:5px solid white;vertical-align:middle;font-variant-numeric:tabular-nums}.tree-row.depth-0 th,.tree-row.depth-0 td{background:#123057;color:#f5f8fc;font-weight:700}.tree-row.depth-1 th,.tree-row.depth-1 td{background:#d8eff2;color:#224c60;font-weight:600}.tree-row.depth-2.branch th,.tree-row.depth-2.branch td{background:#edf3e4;color:#4a6542}.tree-row.depth-2:not(.branch) th,.tree-row.depth-2:not(.branch) td,.tree-row.depth-3 th,.tree-row.depth-3 td{background:#f5f7f9}.tree-row.checked th,.tree-row.checked td{background:#e9eef4}.tree-row.excluded th,.tree-row.excluded td{opacity:.65}.branch-guide{position:absolute;top:0;bottom:0;width:4px;background:#71b9c7}.branch-guide:nth-child(n+2){background:#a9c28b}.branch-toggle{background:transparent;color:inherit;width:18px;margin-right:5px;font-weight:700}.item-detail{display:inline;background:transparent;color:inherit;text-align:left;font-size:inherit;line-height:inherit;padding:0}.item-detail:hover{text-decoration:underline}.tree-row input,.all-check input{display:inline-block;width:14px;min-height:14px;margin:0 8px 0 0;vertical-align:middle;accent-color:#29384a}.all-check{font-size:10px;color:inherit;display:flex;align-items:center;justify-content:center;font-weight:400}.tree-row td.negative{color:#2b63b3}.tree-row td.positive{color:#bc4e43}.tree-row.depth-0 td.negative{color:#b4cff7}.tree-row.depth-0 td.positive{color:#f1c2ad}.stage-label{display:inline-block;background:transparent;color:#9a6b1a;font-size:10px;white-space:nowrap;padding:0}.stage-label.SELECTED{color:#2e6bb3}.stage-label.IN_PROGRESS{color:#8655b3}.stage-label.RE_REVIEW{color:#be5c36}.stage-label.ACCEPTED{color:#24805f}.stage-label.EXCLUDED,.stage-label.OPTIONAL{color:#808a98}.depth-0 .stage-label{color:#b4c7df}.workflow-cell{text-align:center}.gap-tree .number{font-size:11px;white-space:nowrap}.gap-tree tr:last-child td,.gap-tree tr:last-child th{border-bottom:0}
</style>
