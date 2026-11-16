<script setup>
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useSession } from '../stores/session'
import { amount, errorText, query } from '../lib/display'

const props = defineProps({ submission: { type: Object, required: true } })
const emit = defineEmits(['open-cell'])
const session = useSession()
const filters = reactive({ month: '', scenario: '' })
const pages = reactive({ values: { items: [], cursor: null, error: '' }, reports: { items: [], cursor: null, error: '' } })
const summary = ref(null)
const loading = ref(false)
const busy = ref('')
let appliedFilters = {}
let controller = new AbortController()
let generation = 0

/** 검증 준비 상태의 오류를 사용자 안내로 구분한다.
 * Args: cause 안전한 API 오류. Returns: 표시 문자열. Raises: 없음. Side Effects: 없음.
 */
function resultMessage(cause) {
  if (cause.code === 'VALIDATION_REQUIRED') return '업무 검증을 실행하면 적재 내역과 보고 집계를 확인할 수 있습니다.'
  if (cause.code === 'VALIDATION_STALE') return '검증 기준이 변경되었습니다. 업무 검증을 다시 실행하면 현재 적재 결과를 확인할 수 있습니다.'
  if (cause.code === 'PARSING_NOT_READY') return '현재 파싱이 끝난 뒤 조회할 수 있습니다.'
  return errorText(cause)
}

/** 선택한 자료의 한 페이지를 읽고 같은 조회 세대에만 반영한다.
 * Args: kind values/reports, more 다음 페이지 여부, current 조회 세대.
 * Returns: Promise<void>. Raises: 없음(영역별 오류 표시).
 * Side Effects: 읽기 HTTP·목록/요약 갱신; 금액을 합산하거나 원본을 변경하지 않는다.
 */
async function readPage(kind, more, current) {
  const page = pages[kind]
  const endpoint = kind === 'values' ? 'financial-values' : 'report-values'
  try {
    const result = await session.request(`/api/v1/submissions/${props.submission.id}/${endpoint}${query({
      ...appliedFilters, year: props.submission.year, limit: 50, cursor: more ? page.cursor : null,
    })}`, { signal: controller.signal })
    if (current !== generation || controller.signal.aborted) return
    page.items = more ? [...page.items, ...result.items] : result.items
    page.cursor = result.nextCursor
    if (kind === 'values') summary.value = result.summary
  } catch (cause) {
    if (current === generation && cause.name !== 'AbortError') page.error = resultMessage(cause)
  }
}

/** 월·구분 필터를 적용하고 적재 내역과 보고값을 독립 조회한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(영역별 표시).
 * Side Effects: 이전 HTTP 취소·두 목록 초기화·GET. 페이지별 실패를 다른 결과에 전파하지 않는다.
 */
async function load() {
  controller.abort(); controller = new AbortController()
  const current = ++generation
  appliedFilters = { ...filters }
  loading.value = true; summary.value = null
  for (const page of Object.values(pages)) { page.items = []; page.cursor = null; page.error = '' }
  await Promise.allSettled([readPage('values', false, current), readPage('reports', false, current)])
  if (current === generation) loading.value = false
}

/** 적용한 필터와 서버 cursor로 해당 표의 다음 페이지를 조회한다.
 * Args: kind values/reports. Returns: Promise<void>. Raises: 없음(영역별 표시).
 * Side Effects: GET·목록 추가; 조회 중 중복 클릭을 막는다.
 */
async function more(kind) {
  if (loading.value || busy.value || !pages[kind].cursor) return
  const current = generation
  busy.value = kind; pages[kind].error = ''
  await readPage(kind, true, current)
  if (current === generation) busy.value = ''
}

/** API의 시나리오를 표시 이름으로 옮긴다.
 * Args: value PLAN/ACTUAL/ESTIMATE. Returns: 표시 문자열. Raises: 없음. Side Effects: 없음.
 */
function scenarioName(value) { return ({ PLAN: '계획', ACTUAL: '실적', ESTIMATE: '추정' })[value] || '—' }

/** 보고값의 누락·미확정을 숫자 0과 구분한다.
 * Args: value AVAILABLE/MISSING/UNRESOLVED. Returns: 표시 문자열. Raises: 없음. Side Effects: 없음.
 */
function valueStatus(value) { return ({ AVAILABLE: '확인', MISSING: '누락', UNRESOLVED: '미확정' })[value] || '미확인' }

/** 선택한 원본 위치를 상위 셀 조회 패널에 전달한다.
 * Args: source API가 제공한 sheet/cell. Returns: 없음. Raises: 없음. Side Effects: open-cell 이벤트.
 */
function openCell(source) { if (source?.sheet && source?.cell) emit('open-cell', source) }

/** 이탈한 화면의 응답이 다음 제출본에 반영되지 않게 취소한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 조회 세대 무효화·HTTP 취소.
 */
function dispose() { generation++; controller.abort() }
onMounted(load)
onBeforeUnmount(dispose)
</script>

<template>
  <section class="panel">
    <div class="section-heading"><div><h2>적재 내역 · 보고 집계</h2><p class="muted section-description">{{ submission.status === 'DRAFT' ? '검증 초안의 적재 결과입니다. 최종 제출 전이며 승인된 집계가 아닙니다.' : '이 제출본에 고정된 적재 결과입니다. 공식 마스터 집계와 구분해 확인하세요.' }}</p></div><span class="muted">단위: 원</span></div>
    <form class="filters" @submit.prevent="load()"><label>대상 월<select v-model="filters.month"><option value="">전체 월</option><option v-for="month in 12" :key="month" :value="month">{{ month }}월</option></select></label><label>구분<select v-model="filters.scenario"><option value="">전체 구분</option><option value="PLAN">계획</option><option value="ACTUAL">실적</option><option value="ESTIMATE">추정</option></select></label><button class="button" :disabled="loading || !!busy">조회</button></form>
    <div v-if="summary" class="metrics"><div><span>적재 상세행</span><strong>{{ summary.lineCount }}</strong></div><div><span>월별 금액</span><strong>{{ summary.valueCount }}</strong></div><div><span>업무 검증</span><strong>{{ summary.validationState === 'CURRENT' ? '실행 완료' : '재검증 필요' }}</strong></div><div><span>완전성</span><strong>{{ summary.complete ? '충족' : '확인 필요' }}</strong></div></div>
    <p v-if="summary && !summary.complete" class="notice warning">확인된 행만 적재되어 있습니다. 아래 진단을 확인해 주세요. 불완전한 보고값은 금액으로 표시하지 않습니다.</p>
    <h3 class="data-title">적재 금액</h3>
    <p v-if="pages.values.error" class="notice warning" role="status">{{ pages.values.error }}</p>
    <p v-if="loading" class="empty compact" role="status">적재 내역을 불러오는 중…</p>
    <div v-else-if="pages.values.items.length" class="table-scroll"><table><caption class="visually-hidden">검증으로 선택한 상세행의 월별 금액과 출처</caption><thead><tr><th>보고 항목 · 계정</th><th>팀 · 과제</th><th>대상 기간</th><th>구분</th><th class="number">금액 (원)</th><th>원본 위치</th></tr></thead><tbody><tr v-for="item in pages.values.items" :key="item.id"><td><strong>{{ item.reportItem.name }}</strong><small>{{ item.accountName || '계정명 없음' }} · {{ item.accountCode || '—' }}</small><small v-if="item.description">{{ item.description }}</small><small v-if="item.detailId">상세 ID {{ item.detailId }}</small></td><td>{{ item.teamCode || '—' }}<small>{{ item.taskCode || '—' }}</small></td><td>{{ item.year }}.{{ String(item.month).padStart(2, '0') }}</td><td>{{ scenarioName(item.scenario) }}</td><td class="number amount">{{ amount(item.amount) }}</td><td><button class="text-button source-link" :disabled="!item.source?.cell" @click="openCell(item.source)">{{ item.source?.sheet || '—' }}<small>{{ item.source?.cell || '—' }}</small></button></td></tr></tbody></table></div>
    <p v-else-if="!pages.values.error" class="empty compact">선택한 조건의 적재 금액이 없습니다.</p>
    <button v-if="pages.values.cursor" class="button load-more" :disabled="loading || !!busy" @click="more('values')">적재 금액 더 보기</button>
    <h3 class="data-title">보고 항목별 집계</h3><p class="muted">서버에서 상세를 집계한 값입니다. 합계·상세·영업이익을 다시 합산하지 않습니다.</p>
    <p v-if="pages.reports.error" class="notice warning" role="status">{{ pages.reports.error }}</p>
    <p v-if="loading" class="empty compact" role="status">보고 집계를 불러오는 중…</p>
    <div v-else-if="pages.reports.items.length" class="table-scroll report-table"><table><caption class="visually-hidden">보고 항목과 월별 집계의 확인 상태</caption><thead><tr><th>보고 항목</th><th>대상 기간</th><th>구분</th><th>확인 상태</th><th class="number">금액 (원)</th></tr></thead><tbody><tr v-for="item in pages.reports.items" :key="`${item.reportItemId}:${item.year}:${item.month}:${item.scenario}`"><td><strong>{{ item.reportItemName }}</strong></td><td>{{ item.year }}.{{ String(item.month).padStart(2, '0') }}</td><td>{{ scenarioName(item.scenario) }}</td><td><span :class="['badge', item.valueStatus === 'AVAILABLE' ? 'green' : 'amber']">{{ valueStatus(item.valueStatus) }}</span></td><td class="number amount">{{ item.valueStatus === 'AVAILABLE' ? amount(item.amount) : '—' }}</td></tr></tbody></table></div>
    <p v-else-if="!pages.reports.error" class="empty compact">선택한 조건의 보고 집계가 없습니다.</p>
    <button v-if="pages.reports.cursor" class="button load-more" :disabled="loading || !!busy" @click="more('reports')">보고 집계 더 보기</button>
  </section>
</template>

<style scoped>
.data-title { margin:24px 0 12px; }.report-table { margin-top:12px; }.source-link { text-align:left; }.source-link small { display:block; }.source-link:disabled { color:var(--muted); }
</style>
