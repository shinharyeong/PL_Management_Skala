<script setup>
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useSession } from '../stores/session'
import { dateTime, errorText, query } from '../lib/display'
import { formatMoney } from '../lib/decimal-display'
import SubmissionReviews from './SubmissionReviews.vue'

const route = useRoute()
const session = useSession()
const requests = ref([])
const selectedId = ref(typeof route.query.requestId === 'string' ? route.query.requestId : '')
const selected = ref(null)
const publication = ref(null)
const thresholds = ref(null)
const loading = ref(false)
const detailLoading = ref(false)
const saving = ref(false)
const thresholdBusy = ref('')
const error = ref('')
const thresholdError = ref('')
const message = ref('')
const thresholdMessage = ref('')
const preview = ref(null)
const create = reactive({ year: Number(route.query.year) || new Date().getFullYear(), month: Number(route.query.month) || 1, dueAt: '', message: '' })
const criterion = reactive({ scenario: 'ACTUAL', percent: '', amount: '' })
const controller = new AbortController()
let detailController = new AbortController()
let generation = 0
let listGeneration = 0
const keys = new Map()

/** 월간 요청 전체를 읽고 경로 또는 지정 연월에 해당하는 요청을 선택한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: GET·목록 갱신·상세 조회.
 */
async function loadRequests() {
  const current = ++listGeneration
  loading.value = true; error.value = ''
  try {
    const all = []; let cursor = null
    do {
      const page = await session.request(`/api/v1/submission-requests${query({ type: 'MONTHLY_ACTUAL', limit: 100, cursor })}`, { signal: controller.signal })
      if (current !== listGeneration) return
      all.push(...page.items); cursor = page.nextCursor
    } while (cursor && !controller.signal.aborted)
    if (controller.signal.aborted || current !== listGeneration) return
    requests.value = all
    if (!selectedId.value) {
      for (const item of all) if (item.year === Number(create.year) && item.month === Number(create.month)) selectedId.value = String(item.id)
      selectedId.value ||= all[0] ? String(all[0].id) : ''
    }
    if (selectedId.value) await loadDetail()
  } catch (cause) { if (current === listGeneration && cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { if (current === listGeneration && !controller.signal.aborted) loading.value = false }
}

/** 선택 월의 조직 현황과 발행 상태를 새로 읽는다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: GET·이전 상세 취소·구 응답 차단.
 */
async function loadDetail() {
  detailController.abort(); detailController = new AbortController()
  const current = ++generation
  selected.value = null; publication.value = null; detailLoading.value = true; error.value = ''
  try {
    const detail = await session.request(`/api/v1/submission-requests/${selectedId.value}`, { signal: detailController.signal })
    if (current !== generation) return
    if (detail.type !== 'MONTHLY_ACTUAL') throw new Error('월간 실적·추정 요청을 선택해 주세요.')
    const periods = await session.request(`/api/v1/master-periods?year=${detail.year}`, { signal: detailController.signal })
    if (current !== generation) return
    selected.value = detail
    for (const item of periods.items) if (item.month === detail.month) publication.value = item
  } catch (cause) { if (current === generation && cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { if (current === generation) detailLoading.value = false }
}

/** 확정 연간 계획에 근거한 월간 전체 조직 요청을 생성한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: 요청 POST·성공 후 현황 조회; 같은 본문은 같은 키로 재시도.
 */
async function createRequest() {
  if (saving.value) return
  const body = JSON.stringify({ type: 'MONTHLY_ACTUAL', year: Number(create.year), month: Number(create.month), dueAt: create.dueAt ? new Date(create.dueAt).toISOString() : null, message: create.message || null })
  if (!keys.has(body)) keys.set(body, crypto.randomUUID())
  saving.value = true; error.value = ''; message.value = ''
  try {
    const result = await session.request('/api/v1/submission-requests', { method: 'POST', body, headers: { 'Idempotency-Key': keys.get(body) }, signal: controller.signal })
    if (controller.signal.aborted) return
    keys.delete(body); selectedId.value = String(result.id); message.value = `${result.year}년 ${result.month}월 요청을 생성했습니다.`
    await loadRequests()
  } catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { if (!controller.signal.aborted) saving.value = false }
}

/** 현재 ACTUAL·ESTIMATE 기준을 읽고 선택 시나리오 입력에 반영한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(기준 영역 표시). Side Effects: GET·기준 상태 갱신.
 */
async function loadThresholds() {
  thresholdError.value = ''
  try {
    const result = await session.request('/api/v1/gap-thresholds/current', { signal: controller.signal })
    if (!controller.signal.aborted) { thresholds.value = result; changeScenario() }
  } catch (cause) { if (cause.name !== 'AbortError') thresholdError.value = errorText(cause) }
}

/** 선택 시나리오의 저장된 기준을 정밀도 손실 없이 입력용 십진 문자열로 표시한다.
 * Args: 없음. Returns: 없음. Raises: 없음.
 * Side Effects: 기준 입력·미리보기 초기화. 명시된 값의 끝 소수 0·천 단위 구분자만 제거하며 미설정·빈 값은 빈칸으로 유지한다.
 */
function changeScenario() {
  const current = thresholds.value?.[criterion.scenario === 'ACTUAL' ? 'actual' : 'estimate']
  criterion.percent = current?.percent != null && current.percent !== '' ? formatMoney(current.percent).replaceAll(',', '') : ''
  criterion.amount = current?.amount != null && current.amount !== '' ? formatMoney(current.amount).replaceAll(',', '') : ''
  preview.value = null
}

/** 명시 입력한 기준을 영향 미리보기 또는 새 유효 기준으로 요청한다.
 * Args: apply 저장 여부. Returns: Promise<void>. Raises: 없음(영역 표시).
 * Side Effects: preview POST 또는 기준 생성 POST·최신 기준 조회; 저장과 미리보기를 구분한다.
 */
async function thresholdCommand(apply = false) {
  if (thresholdBusy.value || !thresholds.value || (!apply && !selected.value)) return
  if (!/^\d+(\.\d+)?$/.test(criterion.percent.trim()) || !/^\d+(\.\d+)?$/.test(criterion.amount.trim())) { thresholdError.value = '차이율과 최소 금액을 0 이상의 숫자로 직접 입력해 주세요.'; return }
  const payload = { scenario: criterion.scenario, percent: criterion.percent.trim(), amount: criterion.amount.trim() }
  if (apply) payload.expectedStateToken = thresholds.value.stateToken
  else payload.requestId = selected.value.id
  const body = JSON.stringify(payload); const path = `/api/v1/gap-thresholds${apply ? '' : '/preview'}`
  if (!keys.has(body)) keys.set(body, crypto.randomUUID())
  thresholdBusy.value = apply ? 'apply' : 'preview'; thresholdError.value = ''; thresholdMessage.value = ''
  try {
    const result = await session.request(path, { method: 'POST', body, headers: apply ? { 'Idempotency-Key': keys.get(body) } : {}, signal: controller.signal })
    if (controller.signal.aborted) return
    keys.delete(body)
    if (apply) { thresholdMessage.value = '새 기준을 적용했습니다. 작성 중인 초안은 재검증하고, 이미 제출한 소명의 고정 기준은 유지됩니다.'; await loadThresholds() }
    else preview.value = { ...result, percent: payload.percent, amount: payload.amount }
  } catch (cause) { if (cause.name !== 'AbortError') thresholdError.value = errorText(cause) }
  finally { if (!controller.signal.aborted) thresholdBusy.value = '' }
}

/** 조직의 최신 제출본 또는 요청별 이력으로 이동하는 경로를 만든다.
 * Args: target 대상 조직 DTO. Returns: 라우트 객체. Raises: 없음. Side Effects: 없음.
 */
function targetLink(target) { return { path: target.latestSubmittedSubmissionId ? `/submissions/${target.latestSubmittedSubmissionId}` : '/history', query: { from: 'gap', requestId: selected.value.id, organizationId: target.organization.id } } }

/** 실제 제출 상태를 한국어로 표시한다.
 * Args: value 최신 상태. Returns: 표시 이름. Raises: 없음. Side Effects: 없음.
 */
function statusLabel(value) { return ({ PENDING: '검토 대기', APPROVED: '승인 완료', REJECTED: '반려', NOT_SUBMITTED: '미제출' })[value] || '미제출' }

/** 요청과 기준을 독립적으로 초기 조회한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(각 영역 표시). Side Effects: 두 조회 시작.
 */
async function initialize() { await Promise.allSettled([loadRequests(), loadThresholds()]) }

/** 화면 이탈 시 조회·생성 응답을 중단한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소·세대 무효화.
 */
function dispose() { generation++; listGeneration++; controller.abort(); detailController.abort() }
onMounted(initialize)
onBeforeUnmount(dispose)
</script>

<template>
  <div class="gap-page">
    <header class="page-heading"><div><h1>월간 시트 관리</h1><p class="muted">확정 계획 → 월간 요청 → 검증·소명 → 승인 → 실적 마스터</p></div><RouterLink to="/planning/annual-plan" class="button">연간 계획 확정 확인</RouterLink></header>
    <section class="panel"><div class="section-heading"><h2>월간 실적·추정 요청</h2><button class="button" :disabled="loading || detailLoading" @click="loadRequests">새로고침</button></div><p class="muted">해당 연간 계획이 확정되어야 요청을 생성할 수 있습니다. 확정 계획의 전체 대상 조직이 제출 대상입니다.</p><details class="create-month"><summary>새 월간 요청 생성</summary><form @submit.prevent="createRequest"><div class="filters"><label>연도<input v-model="create.year" type="number" min="2000" max="9999" required :disabled="saving" /></label><label>마감 월<select v-model="create.month" :disabled="saving"><option v-for="value in 12" :key="value" :value="value">{{ value }}월</option></select></label><label>회신 기한<input v-model="create.dueAt" type="datetime-local" :disabled="saving" /></label></div><label>요청 메시지<textarea v-model="create.message" maxlength="5000" :disabled="saving" rows="3"></textarea></label><button class="button primary" :disabled="saving">{{ saving ? '생성 중…' : '전 조직 월간 요청 생성' }}</button></form></details><p v-if="message" class="notice" role="status">{{ message }}</p><div v-if="error" class="alert error" role="alert">{{ error }}</div><label v-if="requests.length">검토할 요청<select v-model="selectedId" :disabled="loading" @change="loadDetail"><option v-for="item in requests" :key="item.id" :value="String(item.id)">{{ item.year }}년 {{ item.month }}월 실적·추정</option></select></label><p v-if="loading || detailLoading" class="empty compact" role="status">월간 요청 현황을 불러오는 중…</p><p v-else-if="!selected && !error" class="empty compact">아직 월간 요청이 없습니다.</p>
      <template v-if="selected && !detailLoading"><div class="metrics"><div><span>최종 제출</span><strong>{{ selected.submittedCount }}/{{ selected.targetCount }}</strong></div><div><span>조직 승인</span><strong>{{ selected.approvedCount }}/{{ selected.targetCount }}</strong></div><div><span>실적 마스터</span><strong>{{ publication?.publicationState === 'PUBLISHED' ? '발행 완료' : '첫 발행 대기' }}</strong></div></div><p class="muted">회신 기한 {{ dateTime(selected.dueAt) }} · 기존 마스터가 있으면 재제출 검토 중에도 그 승인본을 유지합니다.</p><RouterLink v-if="publication?.latestMasterId" :to="`/planning/masters/${publication.latestMasterId}`" class="button master-link">{{ publication.latestVersionLabel }} 확인 →</RouterLink><div class="table-scroll"><table><thead><tr><th>사업조직</th><th>코드</th><th>최신 제출 상태</th><th>검토</th></tr></thead><tbody><tr v-for="target in selected.targets" :key="target.organization.id"><th scope="row">{{ target.organization.name }}</th><td>{{ target.organization.code }}</td><td>{{ statusLabel(target.latestStatus) }}<small v-if="target.draftCount">초안 {{ target.draftCount }}건</small></td><td><RouterLink :to="targetLink(target)" class="text-button">{{ target.latestStatus === 'PENDING' ? '소명·승인 검토' : '제출 내역' }} →</RouterLink></td></tr></tbody></table></div></template>
    </section>
    <section class="panel gap-criteria" aria-labelledby="criteria-title"><div class="section-heading"><h2 id="criteria-title">손익 Gap 기준</h2><button class="text-button" :disabled="!!thresholdBusy" @click="loadThresholds">현재 기준 조회</button></div><p class="muted">조직·보고 항목·월의 손익을 비교합니다. 기준이 0이 아닌 경우 절대 차이율과 절대 금액을 모두 엄격하게 초과해야 합니다. 실제 기준이 0이고 현재 손익이 0이 아니면 초과입니다.</p><div class="threshold-saved"><p>ACTUAL {{ thresholds ? thresholds.actual ? `${formatMoney(thresholds.actual.percent)}% · ${formatMoney(thresholds.actual.amount)}원` : '미설정' : '조회 필요' }}</p><p>ESTIMATE {{ thresholds ? thresholds.estimate ? `${formatMoney(thresholds.estimate.percent)}% · ${formatMoney(thresholds.estimate.amount)}원` : '미설정' : '조회 필요' }}</p></div><div v-if="thresholdError" class="alert error" role="alert">{{ thresholdError }}</div><p v-if="thresholdMessage" class="notice" role="status">{{ thresholdMessage }}</p><form class="filters" @submit.prevent="thresholdCommand(true)"><label>대상<select v-model="criterion.scenario" :disabled="!!thresholdBusy" @change="changeScenario"><option value="ACTUAL">실적 ACTUAL</option><option value="ESTIMATE">추정 ESTIMATE</option></select></label><label>절대 차이율 · %<input v-model="criterion.percent" inputmode="decimal" required :disabled="!!thresholdBusy" /></label><label>최소 절대 금액 · 원<input v-model="criterion.amount" inputmode="decimal" required :disabled="!!thresholdBusy" /></label><button type="button" class="button" :disabled="!!thresholdBusy || !selected" @click="thresholdCommand(false)">영향 미리보기</button><button class="button primary" :disabled="!!thresholdBusy || !thresholds">{{ thresholdBusy === 'apply' ? '적용 중…' : '새 기준 적용' }}</button></form><p v-if="preview" class="notice">미리보기 기준 {{ formatMoney(preview.percent) }}% · {{ formatMoney(preview.amount) }}원: 평가 {{ preview.evaluatedItemCount }}항목, 초과 {{ preview.exceededItemCount }}항목 / {{ preview.exceededOrganizationCount }}조직, 계산 제외 {{ preview.excludedItemCount }}항목. 아직 저장하지 않았습니다.</p></section>
    <ol class="gap-pipeline" aria-label="월간 처리 순서"><li v-for="(stage, index) in ['연간 계획 확정', '월간 요청·업로드', '업무 검증·소명', '소명 수용·조직 승인', '실적 마스터 발행']" :key="stage"><img :src="`/assets/gap-step-${index + 1}.svg`" alt="" /><span>{{ index + 1 }}. {{ stage }}</span></li></ol>
    <SubmissionReviews v-if="selected" :key="selected.id" :request-id="String(selected.id)" @changed="loadDetail" />
  </div>
</template>

<style scoped>
.create-month { margin:20px 0; }.create-month summary { cursor:pointer; color:var(--gold-ink); font-size:13px; }.create-month form { margin-top:18px; }.threshold-saved { display:flex; flex-wrap:wrap; gap:16px; margin:18px 0; padding:14px; background:#faf9f6; border-radius:8px; font-size:12px; }.gap-criteria .filters { align-items:flex-end; }.master-link { margin:18px 0; }.gap-pipeline { display:grid; grid-template-columns:repeat(5,minmax(0,1fr)); gap:12px; list-style:none; margin:24px 0; padding:0; }.gap-pipeline li { display:flex; align-items:center; gap:8px; color:var(--muted); font-size:12px; }.gap-pipeline img { width:24px; height:24px; }.gap-page td small { display:block; color:var(--muted); margin-top:5px; }@media(max-width:900px) { .gap-pipeline { grid-template-columns:repeat(2,minmax(0,1fr)); } }
</style>
