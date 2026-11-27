<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useSession } from '../stores/session'
import { dateTime, errorText, query, submissionStatus } from '../lib/display'
import { formatMoney } from '../lib/decimal-display'
import { difference, moneyUnits } from '../lib/dashboard-values'

const props = defineProps({
  submissionId: { type: String, required: true },
  reportItemId: { type: String, default: '' },
  reportItemKind: { type: String, default: '' },
  year: { type: [Number, String], default: '' },
  month: { type: [Number, String], default: '' },
  scenario: { type: String, default: '' },
  unit: { type: String, default: 'THOUSAND' },
})
const session = useSession()
const versions = ref([])
const current = ref(null)
const selection = reactive(['', '', ''])
const scenarios = reactive(['PLAN', 'ACTUAL', 'ACTUAL'])
const columns = ref([])
const loading = ref(false)
const comparing = ref(false)
const error = ref('')
const onlyChanges = ref(false)
const visibleCount = ref(50)
const sourceDialog = ref(null)
const sourceView = ref(null)
const sourceCache = new Map()
let controller = new AbortController()
let sourceController = new AbortController()
let generation = 0
let sourceGeneration = 0

const unitName = computed(displayUnitName)
const comparisonRows = computed(alignRows)
const filteredRows = computed(filterRows)
const visibleRows = computed(visibleRowsPage)
const unalignedCount = computed(countUnaligned)
const contextKey = computed(comparisonContextKey)

/** 화면의 표시 단위를 한국어로 반환한다.
 * Args: 없음(props.unit 참조). Returns: 단위명. Raises: 없음. Side Effects: 없음.
 */
function displayUnitName() { return ({ KRW: '원', THOUSAND: '천원', MILLION: '백만원', HUNDRED_MILLION: '억원' })[props.unit] || '원' }

/** 비교 대상 변경을 감지할 안정적인 키를 만든다.
 * Args: 없음(props 참조). Returns: 문자열. Raises: 없음. Side Effects: 없음.
 */
function comparisonContextKey() { return JSON.stringify([props.submissionId, props.reportItemId, props.reportItemKind, props.year, props.month, props.scenario]) }

/** 제출 유형에 맞는 기본 비교 시나리오를 선택한다.
 * Args: submission 제출 요약. Returns: PLAN/ACTUAL/ESTIMATE. Raises: 없음. Side Effects: 없음.
 */
function defaultScenario(submission) { return submission?.type === 'ANNUAL_PLAN' ? 'PLAN' : ['ACTUAL', 'ESTIMATE'].includes(props.scenario) ? props.scenario : 'ACTUAL' }

/** 시나리오를 표시 이름으로 변환한다.
 * Args: value 시나리오. Returns: 이름. Raises: 없음. Side Effects: 없음.
 */
function scenarioName(value) { return ({ PLAN: '계획', ACTUAL: '실적', ESTIMATE: '추정' })[value] || '—' }

/** 제출 요약을 모호하지 않은 버전 선택 이름으로 표시한다.
 * Args: item 제출 요약. Returns: 버전·상태·업로드 시각. Raises: 없음. Side Effects: 없음.
 */
function versionName(item) { return `${item.versionLabel || `미제출 초안 #${item.id}`} · ${submissionStatus(item)} · ${dateTime(item.uploadedAt)}` }

/** 선택한 제출 ID의 실제 요약을 반환한다.
 * Args: id 제출 ID. Returns: 제출 요약/undefined. Raises: 없음. Side Effects: 없음.
 */
function findVersion(id) { for (const item of versions.value) if (item.id === id) return item; return undefined }

/** 다른 열에서 이미 선택한 제출본의 중복 선택을 막는다.
 * Args: id 후보 ID, index 열 번호. Returns: 비활성 여부. Raises: 없음. Side Effects: 없음.
 */
function usedElsewhere(id, index) { for (let position = 0; position < selection.length; position++) if (position !== index && selection[position] === id) return true; return false }

/** 비교본이 바뀌면 해당 유형에서 가능한 시나리오로 초기화한다.
 * Args: index 열 번호. Returns: 없음. Raises: 없음. Side Effects: 로컬 선택 상태만 갱신.
 */
function selectVersion(index) { scenarios[index] = defaultScenario(findVersion(selection[index])) }

/** 같은 조직·연도의 모든 제출 요약과 서버가 지정한 직전 비교본을 읽는다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 오류). Side Effects: GET·이전 조회 취소·화면 초기화만 수행.
 */
async function initialize() {
  controller.abort(); sourceController.abort()
  controller = new AbortController()
  const run = ++generation
  closeSource()
  loading.value = true; comparing.value = false; error.value = ''; columns.value = []; versions.value = []; current.value = null
  selection.splice(0, 3, '', '', ''); sourceCache.clear()
  try {
    const detail = await session.request(`/api/v1/submissions/${props.submissionId}`, { signal: controller.signal })
    if (run !== generation) return
    if (props.year && String(props.year) !== String(detail.year)) throw new Error('선택한 제출본과 비교 연도가 다릅니다.')
    current.value = detail
    const found = []; const foundIds = new Set(); const seen = new Set(); let cursor = null
    do {
      const page = await session.request(`/api/v1/submissions${query({ organizationId: detail.organizationId, year: detail.year, limit: 100, cursor })}`, { signal: controller.signal })
      if (run !== generation) return
      for (const item of page.items) if (!foundIds.has(item.id)) { found.push(item); foundIds.add(item.id) }
      cursor = page.nextCursor
      if (cursor && seen.has(cursor)) throw new Error('제출 목록의 다음 페이지를 확인할 수 없습니다. 다시 조회해 주세요.')
      if (cursor) seen.add(cursor)
    } while (cursor)
    if (!foundIds.has(detail.id)) found.unshift(detail)
    versions.value = found
    selection[1] = detail.id
    const baselineId = detail.comparisonContext?.previousSubmissionId || detail.comparisonContext?.planSubmissionId
    if (baselineId && baselineId !== detail.id && findVersion(baselineId)) selection[0] = baselineId
    for (let index = 0; index < 3; index++) selectVersion(index)
    await compare()
  } catch (cause) { if (run === generation && cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { if (run === generation) loading.value = false }
}

/** 선택본 하나의 필터 범위 전체를 읽어 중복 키를 페이지 경계까지 확인한다.
 * Args: column 선택 열, run 조회 세대. Returns: Promise<void>. Raises: 없음(열별 오류).
 * Side Effects: GET·열 상태 갱신만 수행. 실패한 부분 목록은 비교에 사용하지 않는다.
 */
async function readColumn(column, run) {
  const values = []; const seen = new Set(); let cursor = null
  try {
    do {
      const page = await session.request(`/api/v1/submissions/${column.submission.id}/financial-values${query({
        year: current.value.year, month: props.month, scenario: column.scenario,
        reportItemId: props.reportItemKind === 'DERIVED' ? null : props.reportItemId, limit: 100, cursor,
      })}`, { signal: controller.signal })
      if (run !== generation) return
      values.push(...page.items)
      column.summary = page.summary
      cursor = page.nextCursor
      if (cursor && seen.has(cursor)) throw new Error('적재 내역의 다음 페이지를 확인할 수 없습니다. 다시 조회해 주세요.')
      if (cursor) seen.add(cursor)
    } while (cursor)
    if (run === generation) { column.values = values; column.ready = true }
  } catch (cause) {
    if (run === generation && cause.name !== 'AbortError') { column.error = errorText(cause); column.values = []; column.ready = false }
  }
}

/** 선택한 최대 세 제출본을 읽기 전용으로 나란히 조회한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(열별/화면 오류).
 * Side Effects: GET·이전 조회 취소·선택 결과 반영. 검증·제출·승인·원본 수정은 하지 않는다.
 */
async function compare() {
  if (!current.value) return
  controller.abort(); controller = new AbortController()
  const run = ++generation
  comparing.value = true; loading.value = false; error.value = ''; visibleCount.value = 50; closeSource()
  const selected = []; const selectedIds = new Set()
  for (let index = 0; index < 3; index++) {
    const submission = findVersion(selection[index])
    if (!submission) { selected.push(null); continue }
    if (selectedIds.has(submission.id)) { error.value = '동일한 제출본을 여러 열에 선택할 수 없습니다.'; comparing.value = false; return }
    selectedIds.add(submission.id)
    selected.push({ submission, scenario: scenarios[index], values: [], ready: false, error: '', summary: null })
  }
  columns.value = selected
  const requests = []
  for (const column of columns.value) if (column) requests.push(readColumn(column, run))
  await Promise.allSettled(requests)
  if (run === generation) comparing.value = false
}

/** 좌표·행 번호를 제외하고 명시적인 상세 ID와 업무 차원만으로 키를 구성한다.
 * Args: item 적재 상세. Returns: 직렬화된 키/null. Raises: 없음. Side Effects: 없음.
 * 상세 ID 또는 보고 항목 식별자가 없으면 동일 항목으로 추정하지 않는다. 시나리오는 열의 비교 기준이다.
 */
function businessKey(item) {
  if (!item.detailId || !item.reportItem?.id) return null
  return JSON.stringify([item.detailId, item.reportItem.id, item.reportItem.kind, item.accountCode ?? null, item.accountName ?? null, item.teamCode ?? null, item.taskCode ?? null, item.year, item.month])
}

/** 세 열의 업무 키를 연결하되 중복 키의 모든 원천 행을 독립 표시한다.
 * Args: 없음(columns 참조). Returns: 행 배열. Raises: 없음. Side Effects: 없음; 합산·대표 행 임의 선택 없음.
 */
function alignRows() {
  const groups = new Map(); const isolated = []
  for (let columnIndex = 0; columnIndex < columns.value.length; columnIndex++) {
    const column = columns.value[columnIndex]
    if (!column?.ready) continue
    for (const item of column.values) {
      const key = businessKey(item)
      if (!key) { isolated.push(independentRow(item, columnIndex, '상세 ID 누락 · 버전 간 연결 안 함')); continue }
      if (!groups.has(key)) groups.set(key, [[], [], []])
      groups.get(key)[columnIndex].push(item)
    }
  }
  const rows = []
  for (const [key, entries] of groups) {
    let ambiguous = false
    for (const items of entries) if (items.length > 1) ambiguous = true
    if (ambiguous) {
      for (let index = 0; index < entries.length; index++) for (const item of entries[index]) rows.push(independentRow(item, index, '동일 업무 키 중복 · 각각 표시'))
    } else {
      const cells = []
      for (const items of entries) cells.push(items[0] || null)
      rows.push({ key, entries: cells, item: cells.find(Boolean), issue: '' })
    }
  }
  rows.push(...isolated)
  return rows
}

/** 식별이 불확실한 원천 행을 해당 제출 열에만 배치한다.
 * Args: item 원천 행, index 열 번호, issue 사유. Returns: 독립 비교 행. Raises: 없음. Side Effects: 없음.
 */
function independentRow(item, index, issue) { const entries = [null, null, null]; entries[index] = item; return { key: `isolated:${index}:${item.id}`, entries, item, issue } }

/** 완전한 두 조회 결과 사이에서 금액·누락·설명 변경을 판별한다.
 * Args: row 비교 행. Returns: 변동 여부. Raises: 없음. Side Effects: 없음.
 */
function changedRow(row) {
  if (row.issue) return true
  const available = []
  for (let index = 0; index < columns.value.length; index++) if (columns.value[index]?.ready && columns.value[index].summary?.complete !== false) available.push(index)
  if (available.length < 2) return false
  const first = row.entries[available[0]]
  for (const index of available.slice(1)) {
    const item = row.entries[index]
    if (Boolean(item) !== Boolean(first)) return true
    if (item && (difference(item.amount, first.amount) !== '0' || item.description !== first.description)) return true
  }
  return false
}

/** 변경 행 필터를 적용한다.
 * Args: 없음. Returns: 비교 행 배열. Raises: 없음. Side Effects: 없음.
 */
function filterRows() { return onlyChanges.value ? comparisonRows.value.filter(changedRow) : comparisonRows.value }

/** 한 번에 렌더링할 행 범위를 반환한다.
 * Args: 없음. Returns: 현재 페이지까지의 행. Raises: 없음. Side Effects: 없음.
 */
function visibleRowsPage() { return filteredRows.value.slice(0, visibleCount.value) }

/** 자동 연결하지 않은 원천 행 수를 센다.
 * Args: 없음. Returns: 행 수. Raises: 없음. Side Effects: 없음.
 */
function countUnaligned() { let count = 0; for (const row of comparisonRows.value) if (row.issue) count++; return count }

/** 읽어 둔 다음 행을 화면에 추가한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 로컬 표시 개수만 증가.
 */
function showMore() { visibleCount.value += 50 }

/** 각 열을 바로 앞의 선택된 열과 비교하되 불확실한 누락을 차액으로 계산하지 않는다.
 * Args: row 비교 행, index 열 번호. Returns: {text,tone,changed}. Raises: 없음. Side Effects: 없음.
 */
function deltaView(row, index) {
  if (row.issue) return { text: '연결 보류', tone: '', changed: false }
  const item = row.entries[index]
  let previous = index - 1
  while (previous >= 0 && !columns.value[previous]) previous--
  if (previous < 0) return { text: '비교 기준', tone: '', changed: false }
  const column = columns.value[previous]; const before = row.entries[previous]
  if (!column.ready || column.summary?.complete === false || !columns.value[index]?.ready || columns.value[index].summary?.complete === false) return { text: '조회·검증 확인 필요', tone: '', changed: false }
  if (!before || !item) return { text: !before && item ? '이전 비교본에 없음' : '이 비교본에 없음', tone: '', changed: true }
  const delta = difference(item.amount, before.amount)
  if (delta === null) return { text: '차액 확인 불가', tone: '', changed: false }
  if (moneyUnits(delta) === 0n) return { text: '금액 변동 없음', tone: '', changed: item.description !== before.description }
  const positive = moneyUnits(delta) > 0n
  return { text: `${positive ? '+' : ''}${formatMoney(delta, props.unit)} ${unitName.value}`, tone: positive ? 'increase' : 'decrease', changed: true }
}

/** 원천 행이 없는 이유를 실패·부분 적재와 구분해 표시한다.
 * Args: column 조회 열. Returns: 안내 문자열. Raises: 없음. Side Effects: 없음.
 */
function absentText(column) { return !column ? '비교본 미선택' : !column.ready ? '조회 결과 없음' : column.summary?.complete === false ? '부분 적재 · 존재 여부 미확정' : '해당 업무 키의 적재값 없음' }

/** 선택한 행의 각 제출본 원문 셀을 자신의 제출 ID로만 읽는다.
 * Args: row 비교 행. Returns: Promise<void>. Raises: 없음(열별 오류).
 * Side Effects: GET·원문 대화상자 표시. 원본 수식 실행·캐시 보정 없음.
 */
async function openSource(row) {
  sourceController.abort(); sourceController = new AbortController()
  const run = ++sourceGeneration
  const slots = []
  for (let index = 0; index < 3; index++) slots.push({ column: columns.value[index], item: row.entries[index], loading: Boolean(row.entries[index]?.source?.cell), value: null, error: '' })
  sourceView.value = { row, slots }
  await nextTick()
  if (run !== sourceGeneration) return
  if (sourceDialog.value && !sourceDialog.value.open) sourceDialog.value.showModal()
  const requests = []
  for (const slot of sourceView.value.slots) if (slot.loading) requests.push(readSource(slot, run))
  await Promise.allSettled(requests)
}

/** 한 버전의 셀 근거를 불변 메모리 캐시에 보관한다.
 * Args: slot 원문 열, run 원문 조회 세대. Returns: Promise<void>. Raises: 없음(열별 오류).
 * Side Effects: GET·원문 표시 상태 갱신. 모든 캐시는 컴포넌트 이탈 시 폐기한다.
 */
async function readSource(slot, run) {
  const id = slot.column.submission.id; const source = slot.item.source
  const key = JSON.stringify([id, source.sheet, source.cell])
  try {
    const value = sourceCache.get(key) || await session.request(`/api/v1/submissions/${id}/source-cells${query({ sheet: source.sheet, cell: source.cell })}`, { signal: sourceController.signal })
    if (run !== sourceGeneration) return
    sourceCache.set(key, value); slot.value = value
  } catch (cause) { if (run === sourceGeneration && cause.name !== 'AbortError') slot.error = errorText(cause) }
  finally { if (run === sourceGeneration) slot.loading = false }
}

/** 원본 대화상자를 닫고 진행 중 조회를 취소한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 원문 HTTP 취소·대화상자 닫기.
 */
function closeSource() { sourceGeneration++; sourceController.abort(); sourceDialog.value?.close(); sourceView.value = null }

/** 공유 수식의 기준 원문과 해당 셀의 실제 수식을 구분한다.
 * Args: value 원문 셀. Returns: 수식 설명. Raises: 없음. Side Effects: 없음; 수식을 재작성하지 않는다.
 */
function formulaText(value) {
  if (value.formula) return value.formula
  if (value.sharedFormulaBase) return `공유 수식 기준 ${value.sharedFormulaBase.sheet}!${value.sharedFormulaBase.cell}: ${value.sharedFormulaBase.formula}`
  return value.cacheStatus === 'NOT_FORMULA' ? '수식 셀 아님' : '보존된 수식 없음'
}

/** 컴포넌트 이탈 시 모든 요청과 메모리 근거를 폐기한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소·메모리 캐시 해제.
 */
function dispose() { generation++; controller.abort(); closeSource(); sourceCache.clear() }

watch(contextKey, initialize, { immediate: true })
onBeforeUnmount(dispose)
</script>

<template>
  <section class="version-comparison" aria-label="원천 셀 버전 비교">
    <div class="comparison-heading"><div><h3>원천 셀 · 버전 비교</h3><p>같은 조직·연도의 제출본을 최대 3개까지 비교합니다.</p></div><span class="unit-caption">금액·차액 단위: {{ unitName }}</span></div>
    <p v-if="reportItemKind === 'DERIVED'" class="comparison-note">영업이익을 구성하는 매출·비용 원천을 표시합니다. 원천 행의 차액은 개별 근거이며 영업이익 Gap 판정과 구분됩니다.</p>
    <p v-if="error" class="alert error" role="alert">{{ error }} <button class="text-button" type="button" @click="initialize">다시 조회</button></p>
    <p v-if="loading" class="empty compact" role="status">동일 조직·연도의 비교 가능한 제출본을 읽고 있습니다…</p>
    <template v-if="current">
      <form class="version-selectors" @submit.prevent="compare">
        <label v-for="(_, index) in selection" :key="index"><span>{{ ['이전 비교본', '현재 제출본', '추가 비교본'][index] }}</span><select v-model="selection[index]" :disabled="loading || comparing || index === 1" @change="selectVersion(index)"><option value="">선택 안 함</option><option v-for="item in versions" :key="item.id" :value="item.id" :disabled="usedElsewhere(item.id, index)">{{ versionName(item) }}</option></select><select v-model="scenarios[index]" :disabled="!selection[index] || loading || comparing" :aria-label="`${index + 1}번째 비교본 구분`"><option v-if="findVersion(selection[index])?.type === 'ANNUAL_PLAN'" value="PLAN">계획</option><template v-else><option value="ACTUAL">실적</option><option value="ESTIMATE">추정</option></template></select></label>
        <button class="button" type="submit" :disabled="loading || comparing">{{ comparing ? '비교 중…' : '선택본 비교' }}</button>
      </form>
      <div class="comparison-options"><label><input v-model="onlyChanges" type="checkbox" /> 변동·연결 확인 항목만</label><span>{{ current.year }}년 {{ month ? `${month}월` : '전체 월' }} · {{ filteredRows.length }}행</span></div>
      <p class="comparison-note">상세 ID·보고 항목·계정·팀·과제·연월이 일치하는 행만 연결합니다. 원본 셀 주소는 조회 위치입니다. 각 열의 차액은 바로 왼쪽에 선택한 비교본을 기준으로 합니다.</p>
      <p v-if="unalignedCount" class="notice warning">{{ unalignedCount }}개 원천 행은 상세 ID 누락 또는 동일 업무 키 중복으로 별도 표시합니다. 합산하거나 다른 버전 행에 연결하지 않았습니다.</p>
      <p v-if="comparing" class="empty compact" role="status">각 비교본의 전체 원천 행을 확인하고 있습니다…</p>
      <div class="comparison-table-scroll"><table class="comparison-table"><caption class="visually-hidden">동일 업무 키의 최대 3개 제출 버전 값과 원본 내용 비교</caption><thead><tr><th scope="col">보고 항목 · 원천 업무 키</th><th v-for="(column, index) in columns" :key="index" scope="col" :class="{ 'current-column': index === 1, 'extra-column': index === 2 }"><template v-if="column"><strong>{{ column.submission.versionLabel || `미제출 초안 #${column.submission.id}` }}</strong><span :class="['version-status', { approved: column.submission.status === 'APPROVED' }]">{{ index === 1 ? '현재 · ' : '' }}{{ submissionStatus(column.submission) }}</span><small>{{ scenarioName(column.scenario) }} · {{ dateTime(column.submission.submittedAt || column.submission.uploadedAt) }}</small><small class="filename">{{ column.submission.originalFilename }}</small><p v-if="column.error" class="column-error" role="alert">{{ column.error }}</p><p v-else-if="column.summary?.complete === false" class="column-error">부분 적재 · 누락 여부와 차액 미확정</p><small v-else-if="column.ready">원천 {{ column.values.length }}행</small></template><span v-else>비교본 미선택</span></th></tr></thead><tbody v-if="!comparing"><tr v-for="row in visibleRows" :key="row.key"><th scope="row"><strong>{{ row.item.reportItem.name }}</strong><small>{{ row.item.year }}.{{ String(row.item.month).padStart(2, '0') }} · {{ row.item.accountName || '계정명 없음' }} ({{ row.item.accountCode || '코드 없음' }})</small><small>상세 ID {{ row.item.detailId || '없음' }}</small><small>팀 {{ row.item.teamCode || '—' }} · 과제 {{ row.item.taskCode || '—' }}</small><span v-if="row.issue" class="row-issue">{{ row.issue }}</span><button class="text-button" type="button" @click="openSource(row)">원본 나란히 보기 →</button></th><td v-for="(column, index) in columns" :key="index" :class="{ 'changed-cell': deltaView(row, index).changed }"><template v-if="row.entries[index]"><strong class="value-amount" :title="`${formatMoney(row.entries[index].amount)}원`">{{ formatMoney(row.entries[index].amount, unit) }}</strong><span :class="['value-delta', deltaView(row, index).tone]">{{ deltaView(row, index).text }}</span><p v-if="row.entries[index].description" class="original-description">{{ row.entries[index].description }}</p><span v-else class="muted">원본 상세 설명 없음</span><button v-if="row.entries[index].source?.cell" class="text-button cell-link" type="button" @click="openSource(row)">{{ row.entries[index].source.sheet }}!{{ row.entries[index].source.cell }}</button></template><span v-else class="absent-value">{{ row.issue && column?.ready ? '다른 원천 행과 연결하지 않음' : absentText(column) }}</span></td></tr></tbody></table></div>
      <p v-if="!comparing && !visibleRows.length" class="empty compact">{{ onlyChanges ? '현재 조회한 범위에서 비교 가능한 변동 항목이 없습니다.' : '선택한 기간·시나리오의 적재 원천 행이 없습니다. 각 비교본의 조회 상태를 확인해 주세요.' }}</p>
      <button v-if="visibleCount < filteredRows.length" class="button load-more" type="button" @click="showMore">원천 행 더 보기 ({{ visibleRows.length }}/{{ filteredRows.length }})</button>
    </template>
    <dialog ref="sourceDialog" class="version-source-dialog" aria-label="버전별 원본 셀" @cancel.prevent="closeSource">
      <template v-if="sourceView"><div class="comparison-heading"><div><h3>버전별 원본 셀</h3><p>{{ sourceView.row.item.reportItem.name }} · 상세 ID {{ sourceView.row.item.detailId || '없음' }}</p></div><button class="button" type="button" @click="closeSource">닫기</button></div><p v-if="sourceView.row.issue" class="notice warning">{{ sourceView.row.issue }}. 이 행의 다른 버전 근거를 추정하지 않습니다.</p><div class="source-columns"><section v-for="(slot, index) in sourceView.slots" :key="index"><h4>{{ slot.column?.submission.versionLabel || (slot.column ? `미제출 초안 #${slot.column.submission.id}` : '비교본 미선택') }}</h4><p v-if="slot.item" class="source-position">{{ slot.item.source?.sheet }}!{{ slot.item.source?.cell }} · {{ scenarioName(slot.column.scenario) }}</p><p v-if="slot.loading" role="status">원본을 읽고 있습니다…</p><p v-else-if="slot.error" class="column-error" role="alert">{{ slot.error }}</p><dl v-else-if="slot.value"><dt>원본 값</dt><dd>{{ slot.value.rawValue ?? '—' }}</dd><dt>원본 단위 · 환산 배율</dt><dd>{{ slot.value.sourceUnit || '미확정' }} · {{ slot.value.multiplier ?? '미확정' }}</dd><dt>적재 금액 (원)</dt><dd>{{ formatMoney(slot.item.amount) }}</dd><dt>원본 환산 금액 (원)</dt><dd>{{ slot.value.normalizationStatus === 'CONFIRMED' && slot.value.sourceUnit ? formatMoney(slot.value.normalizedAmount) : '미확정' }}</dd><dt>수식 근거</dt><dd class="formula-value">{{ formulaText(slot.value) }}</dd><dt>파일에 저장된 계산값</dt><dd>{{ slot.value.cachedValue ?? '—' }}</dd><dt>캐시 상태</dt><dd>{{ ({ NOT_FORMULA: '수식 셀 아님', STORED_UNVERIFIED: '파일 저장값 · 재계산하지 않음', MISSING: '계산값 없음', ERROR: '오류' })[slot.value.cacheStatus] || '미확인' }}</dd></dl><p v-else class="muted">{{ slot.item ? '조회할 원본 셀 위치가 없습니다.' : '연결된 원천 행이 없습니다.' }}</p></section></div></template>
    </dialog>
  </section>
</template>

<style scoped>
.version-comparison { margin-top:24px; padding-top:22px; border-top:1px solid #e6e3dc; color:#1a1a19; }.comparison-heading { display:flex; justify-content:space-between; align-items:flex-start; gap:16px; margin-bottom:16px; }.comparison-heading h3 { font-size:16px; margin:0; }.comparison-heading p,.comparison-note { color:#5f636b; font-size:12px; line-height:1.7; margin:6px 0 14px; }.unit-caption { color:#5f636b; font-size:11px; white-space:nowrap; }.version-selectors { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)) auto; gap:10px; align-items:end; padding:14px; border:1px solid #e6e3dc; border-radius:10px; background:#faf9f6; }.version-selectors label { min-width:0; display:grid; gap:6px; font-size:11px; }.version-selectors select { width:100%; min-width:0; font-size:11px; }.version-selectors button { align-self:center; }.comparison-options { display:flex; justify-content:space-between; gap:12px; align-items:center; margin:14px 0 8px; font-size:11px; color:#5f636b; }.comparison-options label { display:flex; align-items:center; gap:7px; }.comparison-options input { width:14px; height:14px; }.comparison-table-scroll { overflow:auto; border:1px solid #e6e3dc; border-radius:10px; }.comparison-table { width:100%; min-width:750px; table-layout:fixed; border-collapse:collapse; font-size:11px; }.comparison-table th,.comparison-table td { padding:12px; border-right:1px solid #e6e3dc; border-bottom:1px solid #e6e3dc; text-align:left; vertical-align:top; overflow-wrap:anywhere; white-space:normal; }.comparison-table tr>:last-child { border-right:0; }.comparison-table tbody tr:last-child>* { border-bottom:0; }.comparison-table th:first-child { width:24%; }.comparison-table thead th { background:#faf9f6; font-weight:normal; }.comparison-table thead .current-column { background:#f4ede2; }.comparison-table thead .extra-column { background:#fdf3f1; }.comparison-table strong { font-size:13px; }.comparison-table small { display:block; margin-top:5px; color:#5f636b; font-size:10px; font-weight:normal; line-height:1.6; }.comparison-table tbody th { font-weight:normal; background:#fff; }.version-status { display:inline-block; font-size:10px; margin:6px 0 1px; border-radius:10px; padding:3px 7px; background:#ede8df; color:#746145; }.version-status.approved { background:#e8f3ec; color:#3f8a5a; }.comparison-table .filename { overflow-wrap:anywhere; }.value-amount { display:block; font-variant-numeric:tabular-nums; }.value-delta { display:block; font-size:10px; color:#858a84; margin-top:6px; }.value-delta.increase { color:#b4524a; }.value-delta.decrease { color:#4866af; }.changed-cell { background:#fcf8f1; }.original-description { white-space:pre-wrap; line-height:1.6; margin:10px 0; color:#5f636b; }.cell-link { display:block; text-align:left; overflow-wrap:anywhere; margin-top:10px; font-size:10px; }.comparison-table th .text-button { display:block; margin-top:10px; font-size:11px; }.row-issue,.column-error { display:block; color:#a05629; font-size:11px; line-height:1.6; margin:8px 0; }.absent-value { color:#8c9098; font-size:11px; }.version-source-dialog { width:min(1080px,calc(100vw - 40px)); max-height:85vh; overflow:auto; padding:24px; border:1px solid #e6e3dc; border-radius:12px; color:#1a1a19; }.version-source-dialog::backdrop { background:#19191980; }.source-columns { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); border:1px solid #e6e3dc; border-radius:10px; overflow:hidden; }.source-columns section { min-width:0; padding:16px; border-right:1px solid #e6e3dc; }.source-columns section:nth-child(2) { background:#faf7f1; }.source-columns section:last-child { border-right:0; }.source-columns h4 { margin:0; font-size:14px; }.source-position { font-size:11px; color:#5f636b; overflow-wrap:anywhere; }.source-columns dt { margin-top:16px; font-size:11px; color:#5f636b; }.source-columns dd { margin:5px 0 0; font-size:12px; overflow-wrap:anywhere; white-space:pre-wrap; }.formula-value { font-family:ui-monospace,monospace; }.source-columns .muted { font-size:12px; line-height:1.6; }
@media(max-width:760px) { .version-selectors { grid-template-columns:1fr; }.comparison-heading { flex-wrap:wrap; }.source-columns { grid-template-columns:1fr; }.source-columns section { border-right:0; border-bottom:1px solid #e6e3dc; } }
</style>
