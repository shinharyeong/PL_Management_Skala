<script setup>
import { computed, inject, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useSession } from '../stores/session'
import { dateTime, errorText, query, requestTitle, saveBlob, submissionStatus } from '../lib/display'
import { formatMoney } from '../lib/decimal-display'
import { issueLocation, issueNextAction } from '../lib/submission-guidance'
import SubmissionReviews from './SubmissionReviews.vue'
import SubmissionRejectionItems from './SubmissionRejectionItems.vue'
import SubmissionExplanationPolicy from './SubmissionExplanationPolicy.vue'
import SubmissionData from './SubmissionData.vue'

const session = useSession()
const navigationContext = inject('organizationSubmissionContext', null)
const route = useRoute()
const base = `/api/v1/submissions/${route.params.id}`
const submission = ref(null)
const baseline = ref(null)
const step = ref('validation')
const loading = ref(false)
const busy = ref('')
const error = ref('')
const message = ref('')
const diagnostics = ref([])
const diagnosticCursor = ref(null)
const reviewSummary = ref([])
const summaryLoading = ref(false)
const summaryError = ref('')
const evidenceOpen = ref(false)
const source = ref(null)
const sourceLoading = ref(false)
const sourceError = ref('')
const sourceDialog = ref(null)
const lifetime = new AbortController()
let controller = new AbortController()
let sourceController = new AbortController()
let generation = 0
let sourceGeneration = 0
let timer
let initialized = false
let validateAfterUpload = route.query.validate === '1'
const commandKeys = new Map()

/** 이미 조회한 제출 상태를 공통 사이드바에 전달한다.
 * Args: item 현재 제출 DTO. Returns: 없음. Raises: 없음. Side Effects: 메모리 탐색 문맥만 갱신하며 GET·URL 변경을 추가하지 않는다.
 */
function publishNavigationContext(item) {
  if (navigationContext) navigationContext.value = { id: String(item.id), actorId: session.actor?.id, status: item.status, unansweredCount: item.readiness?.unansweredCount || 0 }
}

/** 파싱 대기·실행 동안 상태를 자동 조회한다.
 * Args: 없음. Returns: 처리 중 여부. Raises: 없음. Side Effects: 없음.
 */
const parsing = computed(function isParsing() { return ['QUEUED', 'RUNNING'].includes(submission.value?.parsing.status) })

/** 파일의 검증 완료와 소명 작성 단계의 진입 가능 여부를 서버 결과로 판정한다.
 * Args: 없음. Returns: 소명 단계 진입 가능 여부. Raises: 없음. Side Effects: 없음.
 */
const canExplain = computed(function allowsExplanationStep() {
  const item = submission.value
  if (!item || item.readiness?.validationState !== 'CURRENT' || item.parsing.status !== 'SUCCEEDED') return false
  for (const issue of item.readiness.blockers || []) if (!['REQUIRED_EXPLANATIONS_MISSING', 'EXPLANATIONS_NOT_ACCEPTED'].includes(issue.code)) return false
  return true
})

/** 같은 위치의 검증 진단과 제출 차단 안내를 중복 없이 묶는다.
 * Args: 없음. Returns: 오류·안내 목록. Raises: 없음. Side Effects: 없음; 소명 미작성은 소명 단계에서 안내한다.
 */
const validationIssues = computed(function combinedValidationIssues() {
  const result = []; const seen = new Set()
  for (const issue of [...(submission.value?.readiness?.blockers || []), ...diagnostics.value]) {
    if (['REQUIRED_EXPLANATIONS_MISSING', 'EXPLANATIONS_NOT_ACCEPTED'].includes(issue.code)) continue
    const key = `${issue.code}:${issue.source?.sheet || ''}:${issue.source?.cell || ''}:${issue.message || ''}`
    if (!seen.has(key)) { seen.add(key); result.push(issue) }
  }
  return result
})

/** 원본 재업로드를 동일 요청의 새 초안에 연결한다.
 * Args: 없음. Returns: 허용된 RouterLink 경로. Raises: 없음. Side Effects: 없음.
 */
const reuploadLink = computed(function reuploadTarget() { return { path: `/requests/${submission.value?.requestId}/upload`, query: { fromSubmission: submission.value?.id } } })

/** 현재 제출 상태에 맞는 제목을 선택한다.
 * Args: 없음. Returns: 화면 제목. Raises: 없음. Side Effects: 없음.
 */
const title = computed(function workflowTitle() {
  if (submission.value?.status === 'PENDING' && submission.value?.readiness?.unansweredCount > 0) return '소명 보완'
  return submission.value?.status !== 'DRAFT' ? '제출본 상세' : requestTitle(submission.value)
})

/** 현재 파일 상태와 저장된 진단을 읽고 파싱 중에만 폴링한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 오류).
 * Side Effects: GET·이전 요청 취소·메모리 상태 갱신. 업로드 화면에서 검증 시작을 누른 신규 초안만 자동 업무 검증한다.
 */
async function load() {
  clearTimeout(timer); controller.abort(); controller = new AbortController()
  const current = ++generation
  loading.value = true; error.value = ''
  try {
    const item = await session.request(base, { signal: controller.signal })
    if (current !== generation || lifetime.signal.aborted) return
    submission.value = item
    publishNavigationContext(item)
    if (!initialized) {
      step.value = item.status === 'DRAFT' ? canExplain.value ? 'reasons' : 'validation' : item.status === 'PENDING' && item.readiness?.unansweredCount > 0 ? 'reasons' : 'detail'
      initialized = true
    }
    if (item.status !== 'DRAFT' && step.value === 'confirm') step.value = 'detail'
    if (item.status === 'DRAFT' && !canExplain.value && !parsing.value) step.value = 'validation'
    if (parsing.value) { diagnostics.value = []; diagnosticCursor.value = null; timer = setTimeout(load, 1500) }
    else {
      const page = await session.request(`${base}/diagnostics?limit=50`, { signal: controller.signal })
      if (current !== generation || lifetime.signal.aborted) return
      diagnostics.value = page.items; diagnosticCursor.value = page.nextCursor
      const baselineId = item.comparisonContext?.previousSubmissionId
      if (baselineId && baseline.value?.id !== baselineId) baseline.value = await session.request(`/api/v1/submissions/${baselineId}`, { signal: controller.signal })
      if (!baselineId) baseline.value = null
    }
  } catch (cause) { if (current === generation && cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { if (current === generation) loading.value = false }
  if (validateAfterUpload && submission.value?.status === 'DRAFT' && submission.value?.parsing.status === 'SUCCEEDED' && !lifetime.signal.aborted && !error.value) {
    validateAfterUpload = false
    if (submission.value.readiness?.validationState !== 'CURRENT') await command('validation')
  }
}

/** 원본 진단의 다음 페이지를 정확한 cursor로 이어 읽는다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 오류). Side Effects: GET·진단 목록 갱신.
 */
async function moreDiagnostics() {
  if (!diagnosticCursor.value || loading.value || busy.value) return
  busy.value = 'diagnostics'; error.value = ''
  try {
    const page = await session.request(`${base}/diagnostics${query({ limit: 50, cursor: diagnosticCursor.value })}`, { signal: controller.signal })
    if (!lifetime.signal.aborted) { diagnostics.value.push(...page.items); diagnosticCursor.value = page.nextCursor }
  } catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { busy.value = '' }
}

/** 검증·재파싱·최종 제출을 현재 상태 토큰과 멱등키로 실행한다.
 * Args: action validation/parsing-retries/submit. Returns: Promise<void>. Raises: 없음(화면 오류).
 * Side Effects: 사용자가 선택한 POST·최신 GET. 최종 제출은 확인 단계에서만 가능하고 서버 준비 상태를 우회하지 않는다.
 */
async function command(action) {
  if (!submission.value || busy.value || loading.value) return
  if (action === 'validation' && (submission.value.status !== 'DRAFT' || submission.value.parsing.status !== 'SUCCEEDED')) return
  if (action === 'submit' && (step.value !== 'confirm' || submission.value.readiness.canSubmit !== true)) return
  if (action === 'parsing-retries' && !submission.value.parsing.retryAllowed) return
  const payload = { expectedStateToken: submission.value.stateToken }
  if (action === 'parsing-retries') payload.expectedAttemptNo = submission.value.parsing.attemptNo
  const fingerprint = `${action}:${JSON.stringify(payload)}`
  if (!commandKeys.has(fingerprint)) commandKeys.set(fingerprint, crypto.randomUUID())
  busy.value = action; error.value = ''; message.value = ''; clearTimeout(timer)
  try {
    const result = await session.request(`${base}/${action}`, { method: 'POST', body: JSON.stringify(payload), headers: { 'Idempotency-Key': commandKeys.get(fingerprint) }, signal: lifetime.signal })
    if (lifetime.signal.aborted) return
    submission.value = action === 'validation' ? result.submission : result
    publishNavigationContext(submission.value)
    commandKeys.delete(fingerprint)
    if (action === 'submit') { step.value = 'detail'; message.value = '제출이 완료되었습니다. 경영기획팀에서 검토 중입니다.' }
    else if (action === 'validation') step.value = canExplain.value ? 'reasons' : 'validation'
    else { validateAfterUpload = true; step.value = 'validation' }
    await load()
  } catch (cause) {
    if (cause.name !== 'AbortError' && !lifetime.signal.aborted) {
      if (['RESOURCE_CHANGED', 'REVIEW_CONTEXT_CHANGED', 'VALIDATION_STALE'].includes(cause.code)) await load()
      error.value = errorText(cause)
    }
  } finally { if (!lifetime.signal.aborted) busy.value = '' }
}

/** 제출 확인 단계에서 저장된 모든 소명을 페이지 끝까지 조회한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 오류).
 * Side Effects: GET·현재 단계 갱신; 제출 명령·금액 변경을 수행하지 않는다.
 */
async function showConfirmation() {
  if (!submission.value?.readiness.canSubmit || busy.value) return
  step.value = 'confirm'; summaryLoading.value = true; summaryError.value = ''; reviewSummary.value = []
  try {
    let cursor = null
    do {
      const page = await session.request(`/api/v1/review-items${query({ submissionId: submission.value.id, limit: 50, cursor })}`, { signal: lifetime.signal })
      if (lifetime.signal.aborted) return
      reviewSummary.value.push(...page.items); cursor = page.nextCursor
    } while (cursor)
  } catch (cause) { if (cause.name !== 'AbortError') summaryError.value = errorText(cause) }
  finally { summaryLoading.value = false }
}

/** 검증 안내나 적재 내역의 실제 원본 셀을 읽기 전용으로 표시한다.
 * Args: position sheet/cell을 가진 서버 근거. Returns: Promise<void>. Raises: 없음(화면 오류).
 * Side Effects: 이전 셀 조회 취소·GET·다이얼로그 표시; 원본 값을 변경하지 않는다.
 */
async function openSource(position) {
  if (!position?.sheet || !position?.cell) return
  sourceController.abort(); sourceController = new AbortController()
  const current = ++sourceGeneration
  source.value = { position }; sourceLoading.value = true; sourceError.value = ''
  await nextTick()
  if (current !== sourceGeneration || lifetime.signal.aborted) return
  sourceDialog.value?.showModal()
  try {
    const result = await session.request(`${base}/source-cells${query({ sheet: position.sheet, cell: position.cell })}`, { signal: sourceController.signal })
    if (current === sourceGeneration && !lifetime.signal.aborted) source.value = { ...result, position }
  } catch (cause) { if (current === sourceGeneration && cause.name !== 'AbortError') sourceError.value = errorText(cause) }
  finally { if (current === sourceGeneration) sourceLoading.value = false }
}

/** 선택한 원본 근거를 닫고 진행 중인 조회를 중단한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소·화면 초기화.
 */
function closeSource() { sourceGeneration++; sourceController.abort(); sourceDialog.value?.close(); source.value = null; sourceLoading.value = false }

/** 제출에 저장된 원본 파일을 다운로드한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 오류). Side Effects: GET·사용자 다운로드.
 */
async function download() {
  if (busy.value) return
  busy.value = 'download'; error.value = ''
  try { saveBlob(await session.request(`${base}/file`, { blob: true, signal: lifetime.signal }), submission.value.originalFilename) }
  catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { busy.value = '' }
}

/** 시나리오 코드를 조직 화면의 구분으로 표시한다.
 * Args: value 시나리오 코드. Returns: 표시명. Raises: 없음. Side Effects: 없음.
 */
function scenarioLabel(value) { return ({ PLAN: '계획', ACTUAL: '실적', ESTIMATE: '추정' })[value] || value }

/** 적재 상세에서 원본 검증 화면으로 이동한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 선택 단계 변경.
 */
function openGuidance() { step.value = 'validation'; evidenceOpen.value = false }

/** 화면 이탈 시 모든 조회·명령·폴링을 취소한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: AbortController 중단·타이머 해제.
 */
function dispose() { generation++; clearTimeout(timer); lifetime.abort(); controller.abort(); closeSource() }
onMounted(load)
onBeforeUnmount(dispose)
</script>

<template>
  <header class="page-heading"><div><p class="overline">{{ session.actor.organization?.name }} · {{ session.actor.organization?.code }}</p><h1>{{ title }}</h1><p v-if="submission && submission.status !== 'DRAFT'" class="muted submission-subtitle">{{ requestTitle(submission) }}</p></div><span v-if="submission" class="badge">{{ submission.year }}년<template v-if="submission.month"> {{ submission.month }}월</template></span></header>
  <div v-if="error" class="alert error" role="alert">{{ error }} <button class="text-button" :disabled="loading || !!busy" @click="load">다시 확인</button></div><p v-if="message" class="alert success" role="status">{{ message }}</p>
  <p v-if="!submission && loading" class="panel empty" role="status">제출본을 불러오는 중…</p>
  <template v-if="submission">
    <SubmissionExplanationPolicy :submission="submission" />
    <section v-if="step === 'validation'" class="panel validation-panel">
      <div class="section-heading"><h2>검증 결과</h2><span :class="['badge', { red: !parsing && !canExplain, green: canExplain }]">{{ parsing ? '파일 확인 중' : canExplain ? '검증 완료' : submission.parsing.status === 'FAILED' ? '처리 실패' : '확인 필요' }}</span></div>
      <div class="file-summary"><strong>{{ submission.originalFilename }}</strong><span class="muted">{{ parsing ? '엑셀을 읽고 있습니다.' : `${validationIssues.length}건의 안내${diagnosticCursor ? ' 이상' : ''}` }}</span></div>
      <p v-if="parsing" class="notice" role="status"><span class="spinner"></span>파일의 구조와 금액을 확인하고 있습니다. 결과가 준비되면 자동으로 표시됩니다.</p>
      <div v-else-if="validationIssues.length" class="table-scroll"><table class="validation-table"><caption class="visually-hidden">원본 위치별 검증 결과와 수정 안내</caption><thead><tr><th>원본 위치</th><th>검증 항목</th><th>수정 안내</th></tr></thead><tbody><tr v-for="(issue, index) in validationIssues" :key="index"><td><button v-if="issue.source?.cell" class="text-button" @click="openSource(issue.source)">{{ issueLocation(issue) }}</button><span v-else>{{ issueLocation(issue) }}</span></td><td><span :class="['badge', issue.severity === 'ERROR' ? 'red' : 'amber']">{{ issue.severity === 'ERROR' ? '확인 필요' : '안내' }}</span><p>{{ issue.message }}</p><details><summary>진단 코드</summary><small>{{ issue.code }}</small></details></td><td>{{ issueNextAction(issue, submission.parsing.status) }}</td></tr></tbody></table></div>
      <p v-else-if="!parsing" class="notice">{{ canExplain ? '파일 검증을 완료했습니다. 변경 항목과 필요한 소명을 확인해 주세요.' : '엑셀 읽기를 완료했습니다. 검증을 실행해 제출할 금액과 비교 기준을 확인해 주세요.' }}</p>
      <button v-if="diagnosticCursor" class="text-button" :disabled="!!busy" @click="moreDiagnostics">검증 안내 더 보기</button>
      <div class="workflow-footer"><button class="text-button" :disabled="!!busy" @click="download">원본 다운로드</button><div class="button-row"><RouterLink :to="reuploadLink" class="button">파일 다시 선택</RouterLink><button v-if="submission.parsing.retryAllowed" class="button" :disabled="!!busy || loading" @click="command('parsing-retries')">검증 다시 시도</button><button v-if="submission.status === 'DRAFT' && submission.parsing.status === 'SUCCEEDED' && !canExplain" class="button primary" :disabled="!!busy || loading" @click="command('validation')">{{ busy === 'validation' ? '검증 중…' : '검증하고 변경 확인' }}</button><button v-if="canExplain" class="button primary" @click="step = 'reasons'">변경 확인·소명</button></div></div>
    </section>

    <template v-if="step === 'reasons'">
      <SubmissionRejectionItems v-if="baseline?.status === 'REJECTED'" :submission="baseline" /><div v-if="baseline" class="basis-strip"><strong>기준 버전</strong><span>{{ baseline.versionLabel }}</span><span class="badge">{{ baseline.status === 'REJECTED' ? '반려된 제출본' : baseline.month === submission.month ? '같은 달 이전 제출본' : '전월 승인 제출본' }}</span></div>
      <SubmissionReviews :submission="submission" @changed="load"><template #footer><div class="workflow-footer"><RouterLink v-if="submission.status === 'DRAFT'" :to="reuploadLink" class="button">파일 다시 선택</RouterLink><RouterLink v-else to="/history" class="button">제출 이력 관리</RouterLink><button v-if="submission.status === 'DRAFT'" class="button primary" :disabled="!submission.readiness.canSubmit || loading || !!busy" @click="showConfirmation">제출 내용 확인</button><button v-else class="text-button" @click="step = 'detail'">제출본 정보 보기</button></div></template></SubmissionReviews>
    </template>

    <section v-if="step === 'confirm'" class="panel confirmation-panel">
      <h2>제출 내용 확인</h2>
      <div class="confirmation-summary"><h3>{{ requestTitle(submission) }}</h3><dl><div><dt>원본 파일</dt><dd>{{ submission.originalFilename }}</dd></div><div><dt>제출 유형</dt><dd>{{ submission.type === 'ANNUAL_PLAN' ? '연간 계획' : '월간 실적·추정' }}</dd></div><div><dt>비교 기준 버전</dt><dd>{{ baseline?.versionLabel || (submission.type === 'ANNUAL_PLAN' ? '최초 계획 · 비교 기준 없음' : '확정 연간 계획') }}</dd></div></dl></div>
      <h3>소명 작성 내역</h3><p v-if="summaryLoading" class="muted" role="status">저장된 소명을 확인하는 중…</p><div v-if="summaryError" class="alert error" role="alert">{{ summaryError }} <button class="text-button" @click="showConfirmation">다시 확인</button></div>
      <p v-if="!summaryLoading && !summaryError && !reviewSummary.length" class="notice">소명 대상이 없습니다. 검증한 전체 파일을 제출합니다.</p>
      <div v-if="reviewSummary.length" class="table-scroll"><table><caption class="visually-hidden">제출 전 소명과 금액 확인</caption><thead><tr><th>항목</th><th>대상 월</th><th class="number">제출 금액 (백만원)</th><th>소명</th></tr></thead><tbody><tr v-for="item in reviewSummary" :key="item.id"><td>{{ item.reportItem?.name }}</td><td>{{ item.month }}월 {{ scenarioLabel(item.scenario) }}</td><td class="number">{{ item.amountPresent ? formatMoney(item.currentAmount, 'MILLION') : '항목 제거' }}</td><td><span v-if="item.workflowState === 'EXCLUDED'" class="badge">제외</span><details v-else-if="item.explanations?.at(-1)?.answer"><summary>{{ item.explanationState === 'ACCEPTED' ? '수락된 소명' : '작성 완료' }}</summary><p class="preserve-lines">{{ item.explanations.at(-1).answer }}</p></details><span v-else>{{ item.required ? '미작성' : '필수 대상 아님' }}</span></td></tr></tbody></table></div>
      <div class="workflow-footer end"><button class="button" :disabled="!!busy" @click="step = 'reasons'">변경 확인·소명</button><button class="button primary" :disabled="!!busy || summaryLoading || !!summaryError || !submission.readiness.canSubmit" @click="command('submit')">{{ busy === 'submit' ? '제출 중…' : '제출하기' }}</button></div>
    </section>

    <div v-if="step === 'detail'" class="submission-detail-grid">
      <section class="panel"><div class="saved-submission"><h2>{{ submission.versionLabel || '제출 전 초안' }} <span :class="['badge', { green: submission.status === 'APPROVED', red: submission.status === 'REJECTED' }]">{{ submission.status === 'REJECTED' ? '재제출 요청' : submissionStatus(submission) }}</span></h2><p>{{ dateTime(submission.submittedAt || submission.uploadedAt) }} · {{ submission.originalFilename }}</p><p>비교 기준 버전 {{ baseline?.versionLabel || (submission.type === 'ANNUAL_PLAN' ? '없음' : '확정 연간 계획') }}</p></div>
        <div v-if="submission.rejectReason" class="notice warning"><strong>반려 사유</strong><p class="preserve-lines">{{ submission.rejectReason }}</p></div>
        <SubmissionRejectionItems :submission="submission" /><p v-if="submission.status === 'REJECTED'" class="muted">반려 사유를 반영한 전체 엑셀 파일을 다시 올려 주세요. 이전 제출본과 소명 이력은 보존됩니다.</p>
        <p v-else-if="submission.status === 'PENDING'" class="notice">{{ submission.readiness.unansweredCount > 0 ? `보완할 소명이 ${submission.readiness.unansweredCount}건 있습니다. 기존 버전에 소명을 추가합니다.` : '제출을 완료했습니다. 경영기획팀 검토 결과를 기다려 주세요.' }}</p>
        <div class="button-row"><button class="button" :disabled="!!busy" @click="download">원본 다운로드</button><RouterLink v-if="submission.status === 'REJECTED'" :to="reuploadLink" class="button primary">수정 엑셀 업로드</RouterLink><button class="button" @click="step = 'reasons'">{{ submission.status === 'PENDING' && submission.readiness.unansweredCount > 0 ? '소명 보완하기' : '변경 항목·소명 보기' }}</button></div>
        <details class="details-disclosure" @toggle="evidenceOpen = $event.target.open"><summary>제출 금액·원본 근거 확인</summary><SubmissionData v-if="evidenceOpen" :submission="submission" @open-cell="openSource" @open-guidance="openGuidance" /></details>
      </section>
      <aside class="panel processing-history"><h2>처리 이력</h2><div><strong>엑셀 업로드</strong><small>{{ dateTime(submission.uploadedAt) }}</small></div><div v-if="submission.submittedAt"><strong>엑셀 제출</strong><small>{{ dateTime(submission.submittedAt) }}</small></div><div v-if="submission.reviewedAt"><strong>{{ submission.status === 'REJECTED' ? '재제출 요청' : '제출 승인' }}</strong><small>{{ submission.reviewedBy?.name }} · {{ dateTime(submission.reviewedAt) }}</small></div><RouterLink to="/history" class="text-button">제출 이력 관리 →</RouterLink></aside>
    </div>
  </template>
  <dialog v-if="source" ref="sourceDialog" class="source-dialog" aria-labelledby="org-source-title" @cancel.prevent="closeSource"><div class="section-heading"><h2 id="org-source-title">원본 셀 확인</h2><button class="button" @click="closeSource">닫기</button></div><p class="muted">{{ source.position.sheet }}!{{ source.position.cell }}</p><p v-if="sourceLoading" role="status">원본을 불러오는 중…</p><p v-if="sourceError" class="alert error" role="alert">{{ sourceError }}</p><dl v-if="!sourceLoading && !sourceError" class="source-fields"><div><dt>원본 값</dt><dd>{{ source.rawValue ?? '—' }}</dd></div><div><dt>수식</dt><dd>{{ source.formula || source.sharedFormulaBase?.formula || '없음' }}</dd></div><div><dt>저장된 계산값</dt><dd>{{ source.cachedValue ?? '—' }}</dd></div><div><dt>원 단위 금액</dt><dd>{{ formatMoney(source.normalizedAmount) }}</dd></div></dl></dialog>
</template>

<style scoped>
.submission-subtitle { margin-top:5px; font-size:12px; }.validation-panel,.confirmation-panel { gap:14px; }
.file-summary { display:flex; align-items:center; gap:12px; flex-wrap:wrap; border:1px solid var(--line); border-radius:8px; padding:9px 12px; font-size:12px; }.validation-table td { width:33.333%; line-height:1.75; }.validation-table p { margin:5px 0; }.validation-table details { margin-top:6px; font-size:10px; }.validation-table summary { font-size:10px; color:var(--muted); }
.workflow-footer { display:flex; align-items:center; justify-content:space-between; gap:12px; margin-top:2px; padding-top:10px; }.workflow-footer.end { justify-content:flex-end; }.basis-strip { display:flex; align-items:center; flex-wrap:wrap; gap:9px; margin:0 0 14px; font-size:12px; }
.confirmation-summary { border:1px solid var(--line); border-radius:10px; padding:14px 18px; }.confirmation-summary h3 { margin-bottom:7px; }.confirmation-summary dl { display:grid; gap:4px; margin:0; font-size:12px; }.confirmation-summary dl>div { display:flex; gap:14px; }.confirmation-summary dt { width:110px; flex-shrink:0; color:var(--muted); }.confirmation-summary dd { margin:0; overflow-wrap:anywhere; }.confirmation-panel td p { max-width:320px; margin-top:8px; font-size:12px; }
.submission-detail-grid { display:grid; grid-template-columns:minmax(0,750fr) minmax(230px,354fr); gap:16px; align-items:start; }.saved-submission { background:#f5f4f0; border:1px solid var(--line); border-radius:10px; padding:14px; }.saved-submission h2 { display:flex; align-items:center; flex-wrap:wrap; gap:10px; }.saved-submission p { font-size:12px; color:var(--muted); margin-top:5px; }.processing-history div { display:flex; flex-direction:column; gap:5px; font-size:12px; }.processing-history small { color:var(--muted); }.processing-history .text-button { margin-top:8px; }.details-disclosure { margin-top:12px; font-size:12px; }.details-disclosure>summary { margin-bottom:14px; }.details-disclosure :deep(.panel) { padding:12px; }
.source-dialog { width:min(650px,calc(100vw - 40px)); max-height:80vh; overflow:auto; border:1px solid var(--line); border-radius:12px; padding:24px; color:var(--ink); box-shadow:0 18px 80px #14171e25; }.source-dialog::backdrop { background:#14171e66; }.source-dialog>p { margin-top:16px; }.source-fields { display:grid; gap:16px; margin:20px 0 0; font-size:12px; }.source-fields dt { color:var(--muted); }.source-fields dd { margin:5px 0 0; overflow-wrap:anywhere; white-space:pre-wrap; }
@media(max-width:1000px) { .submission-detail-grid { grid-template-columns:1fr; }.processing-history { display:flex; flex-direction:row; flex-wrap:wrap; gap:24px; }.processing-history h2 { width:100%; } }
@media(max-width:700px) { .workflow-footer { flex-wrap:wrap; }.workflow-footer .button-row { justify-content:flex-end; }.validation-table { min-width:650px; }.confirmation-summary dl>div { flex-direction:column; gap:2px; }.confirmation-summary dt { width:auto; } }
</style>
