<script setup>
import { computed, nextTick, ref, onMounted, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { useSession } from '../stores/session'
import { query, requestTitle, submissionStatus, dateTime, amount, errorText, saveBlob } from '../lib/display'
import SubmissionData from './SubmissionData.vue'

const session = useSession()
const route = useRoute()
const submission = ref(null)
const loading = ref(false)
const busy = ref('')
const error = ref('')
const actionMessage = ref('')
const decisionAction = ref('')
const rejectNote = ref('')
const diagnostics = ref([])
const diagnosticCursor = ref(null)
const records = ref([])
const recordCursor = ref(null)
const summary = ref(null)
const resultError = ref('')
const resultsLoading = ref(false)
const sourceTarget = ref(null)
const sourceCell = ref(null)
const sourceError = ref('')
const sourceLoading = ref(false)
const sourcePanel = ref(null)
const lifetime = new AbortController()
let controller = new AbortController()
let cellController = new AbortController()
let generation = 0
let cellGeneration = 0
let timer
const commandKeys = new Map()
const base = `/api/v1/submissions/${route.params.id}`

/** 현재 파싱 처리 상태를 판정한다.
 * Args: 없음. Returns: 대기/실행 여부. Raises: 없음. Side Effects: 없음.
 */
const parsing = computed(function isParsing() { return ['QUEUED', 'RUNNING'].includes(submission.value?.parsing.status) })

/** 본인 초안의 파싱 성공 후에만 업무 검증을 제공한다.
 * Args: 없음. Returns: 검증 권한·상태 충족 여부. Raises: 없음. Side Effects: 없음.
 */
const canValidate = computed(function allowsValidation() { return session.actor?.role === 'ORG_MANAGER' && submission.value?.status === 'DRAFT' && submission.value?.parsing.status === 'SUCCEEDED' })

/** 최종 제출 가능 여부는 서버의 준비 상태와 담당자 권한으로 결정한다.
 * Args: 없음. Returns: 최종 제출 가능 여부. Raises: 없음. Side Effects: 없음.
 */
const canSubmit = computed(function allowsSubmission() { return session.actor?.role === 'ORG_MANAGER' && submission.value?.status === 'DRAFT' && submission.value?.readiness.canSubmit === true })

/** 기획 담당자에게 검토 대기 중인 연간 제출본의 판단만 제공한다.
 * Args: 없음. Returns: 현재 승인·반려 화면 제공 여부. Raises: 없음. Side Effects: 없음.
 */
const canReview = computed(function allowsReview() { return session.actor?.role === 'PLANNER' && submission.value?.status === 'PENDING' && submission.value?.type === 'ANNUAL_PLAN' })

/** 허용한 화면 종류와 식별자만 사용해 진입 문맥으로 돌아간다.
 * Args: 없음. Returns: RouterLink 대상·표시 이름. Raises: 없음. Side Effects: 없음; 임의 URL을 사용하지 않는다.
 */
const returnLink = computed(function submissionReturn() {
  const requestId = typeof route.query.requestId === 'string' ? route.query.requestId : submission.value?.requestId
  const organizationId = typeof route.query.organizationId === 'string' ? route.query.organizationId : submission.value?.organizationId
  if (session.actor?.role === 'PLANNER' && route.query.from === 'requests') return { to: { path: '/planning/requests', query: { requestId } }, label: '요청별 조직 현황' }
  if (session.actor?.role === 'PLANNER' && route.query.from === 'organizations') return { to: { path: '/planning/organizations', query: { mode: 'actual', organizationId, year: submission.value?.year || route.query.year, month: submission.value?.month || 0 } }, label: '조직별 현황' }
  return { to: { path: '/history', query: { requestId: route.query.requestId, organizationId: route.query.organizationId, from: route.query.from } }, label: '제출 이력 관리' }
})

/** 서버 상태에 맞춰 파싱·검증·제출 완료 안내를 구분한다.
 * Args: 없음. Returns: 사용자 안내 문자열. Raises: 없음. Side Effects: 없음.
 */
const stateMessage = computed(function describeState() {
  const item = submission.value
  if (!item) return ''
  if (item.status === 'PENDING') return session.actor?.role === 'PLANNER' ? '검토 대기 중인 제출본입니다. 적재 내역과 진단·원본을 확인한 뒤 승인하거나 반려해 주세요.' : '최종 제출이 완료되었습니다. 경영기획팀의 검토를 기다리고 있습니다.'
  if (item.status === 'APPROVED') return '승인된 제출본입니다. 제출 당시의 적재 내역과 원본을 확인할 수 있습니다. 연간 계획 전체 확정은 별도 절차입니다.'
  if (item.status === 'REJECTED') return '반려된 제출본입니다. 반려 사유를 확인하고 전체 파일을 다시 제출해 주세요.'
  if (item.parsing.status === 'FAILED') return '파일을 처리하지 못했습니다. 진단을 확인하고 파일을 다시 선택해 주세요.'
  if (item.parsing.status === 'NEEDS_REVIEW') return '확인이 필요한 항목이 있습니다. 아래 진단의 원본 셀을 확인해 주세요.'
  if (item.readiness.canSubmit) return session.actor?.role === 'PLANNER' ? '업무 검증을 통과한 초안입니다. 조직 담당자의 최종 제출을 기다리고 있습니다.' : '업무 검증을 통과했습니다. 적재 내역을 확인한 뒤 최종 제출해 주세요.'
  if (item.readiness.validationState === 'STALE') return '검증 기준이 변경되었습니다. 업무 검증을 다시 실행해 주세요.'
  if (item.readiness.validationState === 'CURRENT') return '업무 검증을 실행했습니다. 최종 제출을 막는 항목과 진단을 확인해 주세요.'
  return session.actor?.role === 'PLANNER' ? '파싱이 완료된 초안입니다. 조직 담당자의 업무 검증과 최종 제출 전입니다.' : '파싱이 완료되었습니다. 업무 검증을 실행해 적재 내역과 제출 가능 여부를 확인하세요.'
})

/** 원인별 서버 안내를 우선 사용하고 기존 코드만 표시 문구로 보완한다.
 * Args: blocker 서버 준비 상태 항목. Returns: 안내 문자열. Raises: 없음. Side Effects: 없음.
 */
function blockerMessage(blocker) {
  return blocker.message || ({ VALIDATION_REQUIRED: '업무 검증이 필요합니다.', VALIDATION_STALE: '업무 검증을 다시 실행해 주세요.', PARSING_NOT_READY: '파싱이 끝나지 않았습니다.', PARSING_REQUIRES_ATTENTION: '파싱 진단의 확인이 필요합니다.' })[blocker.code] || '최종 제출 전에 이 항목의 확인이 필요합니다.'
}

/** 진단과 파싱 후보를 독립 조회하고 현재 세대의 결과만 반영한다.
 * Args: current 조회 세대. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: 읽기 HTTP·결과 갱신; 두 then 콜백은 동일 세대의 응답만 반영한다.
 */
async function loadResults(current = generation) {
  resultsLoading.value = true; resultError.value = ''
  diagnostics.value = []; diagnosticCursor.value = null; records.value = []; recordCursor.value = null; summary.value = null
  const results = await Promise.allSettled([
    session.request(`${base}/diagnostics?limit=50`, { signal: controller.signal }).then(function setDiagnostics(page) {
      if (current !== generation || lifetime.signal.aborted) return
      diagnostics.value = page.items; diagnosticCursor.value = page.nextCursor
    }),
    session.request(`${base}/parsed-records?limit=50`, { signal: controller.signal }).then(function setRecords(page) {
      if (current !== generation || lifetime.signal.aborted) return
      summary.value = page.summary; records.value = page.items; recordCursor.value = page.nextCursor
    }),
  ])
  if (current !== generation || lifetime.signal.aborted) return
  for (const result of results) if (result.status === 'rejected' && result.reason.name !== 'AbortError') resultError.value += `${errorText(result.reason)} `
  resultsLoading.value = false
}

/** 최신 제출 상태를 읽고 파싱 중에만 순차 폴링한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: 이전 조회 취소·GET·타이머/결과 갱신. 구시도 파싱 후보를 현재 결과로 남기지 않는다.
 */
async function load() {
  clearTimeout(timer); controller.abort(); controller = new AbortController()
  const current = ++generation
  loading.value = true; error.value = ''; resultsLoading.value = false
  try {
    const item = await session.request(base, { signal: controller.signal })
    if (current !== generation || lifetime.signal.aborted) return
    if (submission.value?.parsing.attemptNo !== item.parsing.attemptNo || ['QUEUED', 'RUNNING'].includes(item.parsing.status)) closeCell()
    submission.value = item
    if (item.status !== 'PENDING') { decisionAction.value = ''; rejectNote.value = '' }
    if (parsing.value) {
      diagnostics.value = []; records.value = []; summary.value = null
      diagnosticCursor.value = null; recordCursor.value = null
      timer = setTimeout(load, 1500)
    } else await loadResults(current)
  } catch (cause) { if (current === generation && cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { if (current === generation) loading.value = false }
}

/** 진단 또는 파싱 후보의 다음 페이지를 현재 조회에 추가한다.
 * Args: type diagnostics/records. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: GET·목록 추가; 불투명 cursor를 수정하지 않는다.
 */
async function more(type) {
  if (busy.value || loading.value) return
  const current = generation
  busy.value = type; resultError.value = ''
  try {
    const endpoint = type === 'records' ? 'parsed-records' : 'diagnostics'
    const cursor = type === 'records' ? recordCursor.value : diagnosticCursor.value
    const page = await session.request(`${base}/${endpoint}${query({ limit: 50, cursor })}`, { signal: controller.signal })
    if (current !== generation || lifetime.signal.aborted) return
    if (type === 'records') { records.value.push(...page.items); recordCursor.value = page.nextCursor }
    else { diagnostics.value.push(...page.items); diagnosticCursor.value = page.nextCursor }
  } catch (cause) { if (current === generation && cause.name !== 'AbortError') resultError.value = errorText(cause) }
  finally { if (current === generation) busy.value = '' }
}

/** 현재 상태 토큰에 연결한 검증·최종 제출·재파싱 명령을 수행한다.
 * Args: action validation/submit/parsing-retries. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: 명령 POST·최신 상태 조회. 같은 본문의 통신 재시도는 멱등키를 재사용한다.
 */
async function command(action) {
  if (busy.value || loading.value || session.actor?.role !== 'ORG_MANAGER') return
  if (action === 'validation' && !canValidate.value) return
  if (action === 'submit' && !canSubmit.value) return
  if (action === 'parsing-retries' && !submission.value.parsing.retryAllowed) return
  const payload = { expectedStateToken: submission.value.stateToken }
  if (action === 'parsing-retries') payload.expectedAttemptNo = submission.value.parsing.attemptNo
  const fingerprint = `${action}:${JSON.stringify(payload)}`
  if (!commandKeys.has(fingerprint)) commandKeys.set(fingerprint, crypto.randomUUID())
  busy.value = action; error.value = ''; actionMessage.value = ''
  clearTimeout(timer); closeCell()
  try {
    const result = await session.request(`${base}/${action}`, { method: 'POST', headers: { 'Idempotency-Key': commandKeys.get(fingerprint) }, body: JSON.stringify(payload), signal: lifetime.signal })
    if (lifetime.signal.aborted) return
    submission.value = action === 'validation' ? result.submission : result
    commandKeys.delete(fingerprint)
    actionMessage.value = action === 'submit' ? '최종 제출이 완료되었습니다.' : action === 'validation' ? '업무 검증 결과를 갱신했습니다.' : '같은 파일의 재파싱을 시작했습니다.'
    await load()
  } catch (cause) {
    if (cause.name !== 'AbortError' && !lifetime.signal.aborted) {
      if (['RESOURCE_CHANGED', 'REVIEW_CONTEXT_CHANGED', 'VALIDATION_STALE'].includes(cause.code)) await load()
      error.value = errorText(cause)
    }
  } finally { if (!lifetime.signal.aborted) busy.value = '' }
}

/** 승인 또는 반려 확인 폼을 열거나 취소한다.
 * Args: action APPROVE/REJECT 또는 취소를 뜻하는 빈 문자열. Returns: 없음.
 * Raises: 없음. Side Effects: 화면의 판단 선택·안내 변경; 서버 상태는 바꾸지 않는다.
 */
function chooseDecision(action) {
  if (!canReview.value || busy.value || loading.value || !['', 'APPROVE', 'REJECT'].includes(action)) return
  decisionAction.value = action
  error.value = ''; actionMessage.value = ''
}

/** 검토 대기 제출본에 명시한 판단을 저장하고 서버의 검토 결과를 표시한다.
 * Args: 없음(선택한 action과 반려 사유 사용). Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: 토큰·멱등키를 포함한 POST와 최신 GET; 통신 실패 시 같은 본문·키를 보존한다.
 */
async function decide() {
  if (!canReview.value || busy.value || loading.value || !['APPROVE', 'REJECT'].includes(decisionAction.value)) return
  const action = decisionAction.value
  const note = rejectNote.value.trim()
  if (action === 'REJECT' && (!note || note.length > 2000)) {
    error.value = '반려 사유를 앞뒤 공백을 제외한 1~2,000자로 입력해 주세요.'
    return
  }
  const payload = { expectedStateToken: submission.value.stateToken, action }
  if (action === 'REJECT') payload.note = note
  const fingerprint = `decisions:${JSON.stringify(payload)}`
  if (!commandKeys.has(fingerprint)) commandKeys.set(fingerprint, crypto.randomUUID())
  busy.value = 'decisions'; error.value = ''; actionMessage.value = ''
  try {
    const result = await session.request(`${base}/decisions`, { method: 'POST', headers: { 'Idempotency-Key': commandKeys.get(fingerprint) }, body: JSON.stringify(payload), signal: lifetime.signal })
    if (lifetime.signal.aborted) return
    submission.value = result.submission
    commandKeys.delete(fingerprint)
    decisionAction.value = ''; rejectNote.value = ''
    actionMessage.value = action === 'APPROVE' ? '제출본을 승인했습니다.' : '제출본을 반려했습니다. 조직 담당자가 반려 사유를 확인할 수 있습니다.'
    await load()
  } catch (cause) {
    if (cause.name !== 'AbortError' && !lifetime.signal.aborted) {
      if (['RESOURCE_CHANGED', 'INVALID_SUBMISSION_STATE', 'REVIEW_CONTEXT_CHANGED'].includes(cause.code)) await load()
      error.value = errorText(cause)
    }
  } finally { if (!lifetime.signal.aborted) busy.value = '' }
}

/** 지정한 원본 위치를 조회하고 최신 선택만 패널에 표시한다.
 * Args: source 서버가 반환한 sheet/cell. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: 이전 셀 조회 취소·GET·표시 패널 초점 이동.
 */
async function openCell(source) {
  if (!source?.sheet || !source?.cell) return
  cellController.abort(); cellController = new AbortController()
  const current = ++cellGeneration
  sourceTarget.value = { sheet: source.sheet, cell: source.cell }; sourceCell.value = null; sourceError.value = ''; sourceLoading.value = true
  await nextTick()
  if (current !== cellGeneration || lifetime.signal.aborted) return
  sourcePanel.value?.focus()
  try {
    const value = await session.request(`${base}/source-cells${query(sourceTarget.value)}`, { signal: cellController.signal })
    if (current === cellGeneration && !lifetime.signal.aborted) sourceCell.value = value
  } catch (cause) { if (current === cellGeneration && cause.name !== 'AbortError') sourceError.value = errorText(cause) }
  finally { if (current === cellGeneration) sourceLoading.value = false }
}

/** 원본 셀 조회를 취소하고 패널을 닫는다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소·선택 초기화.
 */
function closeCell() { cellGeneration++; cellController.abort(); sourceTarget.value = null; sourceCell.value = null; sourceError.value = ''; sourceLoading.value = false }

/** 권한 확인을 거친 원본 XLSX를 내려받는다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: GET·사용자 다운로드.
 */
async function download() {
  busy.value = 'download'; error.value = ''
  try { saveBlob(await session.request(`${base}/file`, { blob: true, signal: lifetime.signal }), submission.value.originalFilename) }
  catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { if (!lifetime.signal.aborted) busy.value = '' }
}

/** 화면 이탈 시 명령·폴링·원본 셀 조회를 모두 무효화한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 타이머 해제·HTTP 취소·세대 무효화.
 */
function dispose() { generation++; clearTimeout(timer); lifetime.abort(); controller.abort(); closeCell() }
onMounted(load)
onBeforeUnmount(dispose)
</script>

<template>
  <RouterLink :to="returnLink.to" class="back-link">← {{ returnLink.label }}</RouterLink>
  <header class="page-heading"><div><p class="overline">업로드한 전체 파일</p><h1>{{ requestTitle(submission) }}</h1></div><button class="button" :disabled="loading || !!busy" @click="load()">새로고침</button></header>
  <div v-if="error" class="alert error" role="alert">{{ error }}</div><p v-if="actionMessage" class="notice" role="status">{{ actionMessage }}</p>
  <p v-if="!submission && loading" class="panel empty" role="status">제출본을 불러오고 있습니다…</p>
  <template v-if="submission">
    <section class="panel">
      <div class="section-heading"><h2>파일 처리 상태</h2><span :class="['badge', { green: submission.status === 'PENDING' || submission.status === 'APPROVED', red: submission.status === 'REJECTED' || submission.parsing.status === 'FAILED' }]">{{ submissionStatus(submission) }}</span></div>
      <div class="progress-steps"><div class="done"><b>1</b><span>파일 업로드</span></div><span class="step-line"></span><div :class="{ current: parsing, done: ['SUCCEEDED', 'NEEDS_REVIEW'].includes(submission.parsing.status), failed: submission.parsing.status === 'FAILED' }"><b>2</b><span>엑셀 파싱</span></div><span class="step-line"></span><div :class="{ current: !parsing && submission.status === 'DRAFT', done: submission.status !== 'DRAFT' }"><b>3</b><span>검증 및 최종 제출</span></div></div>
      <div v-if="parsing" class="notice" role="status"><span class="spinner"></span>엑셀을 읽고 있습니다. 처리 결과를 자동으로 확인합니다.</div><p v-else class="notice">{{ stateMessage }}</p>
      <dl class="file-meta"><div><dt>파일명</dt><dd>{{ submission.originalFilename }}</dd></div><div><dt>업로드</dt><dd>{{ submission.uploadedBy?.name }} · {{ dateTime(submission.uploadedAt) }}</dd></div><div><dt>저장본</dt><dd>{{ submission.versionLabel || '초안 · 제출 회차 없음' }}</dd></div><div><dt>파싱 시도</dt><dd>{{ submission.parsing.attemptNo }}회</dd></div><div v-if="submission.submittedAt"><dt>제출</dt><dd>{{ submission.submittedBy?.name }} · {{ dateTime(submission.submittedAt) }}</dd></div><div v-if="submission.reviewedAt"><dt>검토</dt><dd>{{ submission.reviewedBy?.name }} · {{ dateTime(submission.reviewedAt) }}</dd></div></dl>
      <div v-if="submission.rejectReason" class="alert warning reject-reason"><strong>반려 사유</strong><p>{{ submission.rejectReason }}</p></div>
      <p v-if="submission.status === 'REJECTED' && session.actor.role === 'ORG_MANAGER'" class="notice">반려 사유를 반영한 전체 파일을 새 초안으로 올려 주세요. 금액이 변경된 항목에는 소명이 필요하며, 소명 작성 기능이 연결되기 전까지 금액 변경본의 최종 제출은 제한됩니다.</p>
      <div v-if="submission.status === 'DRAFT' && !parsing && submission.readiness.blockers?.length" class="readiness"><h3>최종 제출 전 확인</h3><div v-for="(blocker, index) in submission.readiness.blockers" :key="index" class="diagnostic-row"><span class="badge amber">확인</span><div><strong>{{ blockerMessage(blocker) }}</strong><small>{{ blocker.code }}</small><button v-if="blocker.source?.cell" class="text-button" @click="openCell(blocker.source)">{{ blocker.source.sheet }} {{ blocker.source.cell }} 원본 확인</button></div></div></div>
      <div class="panel-footer"><button class="button" :disabled="!!busy || loading" @click="download">{{ busy === 'download' ? '다운로드 중…' : '원본 다운로드' }}</button><div v-if="session.actor.role === 'ORG_MANAGER'" class="button-row"><button v-if="submission.parsing.retryAllowed" class="button" :disabled="!!busy || loading" @click="command('parsing-retries')">같은 파일 다시 파싱</button><RouterLink v-if="['DRAFT', 'REJECTED'].includes(submission.status)" :to="`/requests/${submission.requestId}/upload`" class="button">{{ submission.status === 'REJECTED' ? '전체 파일 다시 제출' : '다른 파일 선택' }}</RouterLink><button v-if="submission.status === 'DRAFT'" class="button" :disabled="!!busy || loading || !canValidate" @click="command('validation')">{{ busy === 'validation' ? '검증 중…' : submission.readiness.validationState === 'CURRENT' ? '업무 검증 다시 실행' : '업무 검증' }}</button><button v-if="submission.status === 'DRAFT'" class="button primary" :disabled="!!busy || loading || !canSubmit" @click="command('submit')">{{ busy === 'submit' ? '제출 중…' : '최종 제출' }}</button></div></div>
    </section>
    <section v-if="canReview" class="panel decision-panel" aria-labelledby="decision-title">
      <div class="section-heading"><h2 id="decision-title">제출본 검토</h2><span class="badge amber">검토 대기</span></div>
      <p class="muted section-description">아래 적재 내역과 진단·원본을 확인해 주세요. 승인해도 연간 계획 전체가 확정되지는 않습니다.</p>
      <div v-if="!decisionAction" class="button-row decision-actions"><button class="button" :disabled="!!busy || loading" @click="chooseDecision('REJECT')">반려</button><button class="button primary" :disabled="!!busy || loading" @click="chooseDecision('APPROVE')">승인</button></div>
      <form v-else class="decision-form" @submit.prevent="decide">
        <template v-if="decisionAction === 'REJECT'"><label for="reject-note">반려 사유 <span class="muted">필수 · 최대 2,000자</span></label><textarea id="reject-note" v-model="rejectNote" :disabled="!!busy || loading" maxlength="2000" required aria-describedby="reject-note-help" placeholder="조직 담당자가 수정해야 할 내용과 근거를 적어 주세요."></textarea><p id="reject-note-help" class="muted">사유는 조직 담당자에게 표시되며, 기존 제출본과 적재 금액은 이력에 보존됩니다.</p></template>
        <p v-else class="notice">{{ submission.versionLabel }} 제출본을 승인하시겠습니까? 검토자와 검토 시각이 기록됩니다.</p>
        <div class="button-row decision-actions"><button type="button" class="button" :disabled="!!busy || loading" @click="chooseDecision('')">취소</button><button type="submit" class="button primary" :disabled="!!busy || loading || (decisionAction === 'REJECT' && !rejectNote.trim())">{{ busy === 'decisions' ? '처리 중…' : decisionAction === 'APPROVE' ? '승인 확정' : '반려 확정' }}</button></div>
      </form>
    </section>
    <p v-else-if="session.actor.role === 'PLANNER' && submission.status === 'PENDING' && submission.type !== 'ANNUAL_PLAN'" class="notice">월간 제출본의 승인·반려는 아직 연결되지 않았습니다.</p>
    <section v-if="sourceTarget" ref="sourcePanel" class="panel source-panel" tabindex="-1" aria-labelledby="source-cell-title"><div class="section-heading"><h2 id="source-cell-title">원본 셀 · {{ sourceTarget.sheet }} {{ sourceTarget.cell }}</h2><button class="text-button" @click="closeCell">닫기</button></div><p v-if="sourceLoading" class="empty compact" role="status">원본 셀을 불러오는 중…</p><p v-if="sourceError" class="alert error" role="alert">{{ sourceError }}</p><dl v-if="sourceCell" class="source-grid"><div><dt>셀 상태</dt><dd>{{ sourceCell.presence === 'EMPTY' ? '빈 셀' : '값 있음' }} · {{ sourceCell.dataType }}</dd></div><div><dt>원본 값</dt><dd>{{ sourceCell.rawValue ?? '—' }}</dd></div><div><dt>수식</dt><dd v-if="sourceCell.formula">{{ sourceCell.formula }}</dd><dd v-else-if="sourceCell.sharedFormulaBase">공유 수식 · 기준 셀 {{ sourceCell.sharedFormulaBase.sheet }} {{ sourceCell.sharedFormulaBase.cell }}<small class="shared-formula">기준 셀 수식: {{ sourceCell.sharedFormulaBase.formula || '기준 수식 원문 미확인' }}</small><small v-if="sourceCell.sharedFormulaBase.range" class="shared-formula">공유 범위: {{ sourceCell.sharedFormulaBase.range }}</small></dd><dd v-else>{{ sourceCell.cacheStatus === 'NOT_FORMULA' ? '없음' : '수식 원문 미확인' }}</dd></div><div><dt>저장된 계산값</dt><dd>{{ sourceCell.cachedValue ?? '—' }} · {{ sourceCell.cacheStatus }}</dd></div><div><dt>정규화 값</dt><dd>{{ amount(sourceCell.normalizedAmount) }}<small v-if="sourceCell.normalizedAmount !== null && sourceCell.normalizedAmount !== undefined"> · {{ sourceCell.sourceUnit ? '원' : '단위 미확인' }}</small></dd></div><div><dt>단위 · 배율</dt><dd>{{ sourceCell.sourceUnit ?? '—' }} · {{ sourceCell.multiplier ?? '—' }}</dd></div><div><dt>정규화 상태</dt><dd>{{ sourceCell.normalizationStatus }}</dd></div></dl></section>
    <template v-if="!parsing">
      <SubmissionData :key="`${submission.id}:${submission.parsing.attemptNo}:${submission.stateToken}`" :submission="submission" @open-cell="openCell" />
      <div v-if="resultError" class="alert error" role="alert">{{ resultError }} <button class="text-button" :disabled="resultsLoading || loading || !!busy" @click="load()">결과 다시 조회</button></div>
      <section class="panel"><div class="section-heading"><h2>파싱 · 업무 검증 진단</h2><span class="muted">{{ diagnostics.length }}건{{ diagnosticCursor ? ' 이상' : '' }}</span></div><p v-if="resultsLoading && !diagnostics.length" class="empty" role="status">진단을 불러오는 중…</p><p v-else-if="!diagnostics.length" class="empty compact">{{ resultError ? '진단 조회 상태를 확인해 주세요.' : '표시할 진단이 없습니다.' }}</p><div v-for="(diagnostic, index) in diagnostics" :key="index" class="diagnostic-row"><span :class="['badge', diagnostic.severity === 'ERROR' ? 'red' : 'amber']">{{ diagnostic.severity === 'ERROR' ? '오류' : '확인' }}</span><div><strong>{{ diagnostic.message }}</strong><small>{{ diagnostic.code }} · {{ diagnostic.stage }}</small><small v-if="diagnostic.code === 'CONTROL_CACHE_PRECISION'">저장 캐시 {{ amount(diagnostic.cachedAmount) }}원 · 상세 합계 {{ amount(diagnostic.calculatedAmount) }}원 · 차이 {{ amount(diagnostic.difference) }}원</small><button v-if="diagnostic.source?.cell" class="text-button" @click="openCell(diagnostic.source)">{{ diagnostic.source.sheet }} {{ diagnostic.source.cell }} 원본 확인</button><small v-else-if="diagnostic.source">{{ diagnostic.source.sheet }}</small></div></div><button v-if="diagnosticCursor" class="button load-more" :disabled="!!busy || loading" @click="more('diagnostics')">진단 더 보기</button></section>
      <section class="panel"><div class="section-heading"><div><h2>파싱 원본 후보</h2><p class="muted section-description">엑셀에서 추출한 셀 미리보기입니다. 위 적재 내역에 선택된 행과 구분해 확인하세요.</p></div></div><div v-if="summary" class="metrics"><div><span>읽은 시트</span><strong>{{ summary.sheetCount }}</strong></div><div><span>추출 셀</span><strong>{{ summary.recordCount }}</strong></div><div><span>정규화 완료</span><strong>{{ summary.mappedCount }}</strong></div><div><span>확인 필요</span><strong>{{ summary.unresolvedCount }}</strong></div></div><p v-if="summary?.unusedTemplateRowCount > 0" class="notice" role="note">미사용 양식 {{ summary.unusedTemplateRowCount }}행 · 입력 없는 양식 행은 업무 검증에서 제외하고 원본을 보존했습니다.</p><p v-if="resultsLoading && !records.length" class="empty" role="status">추출 금액을 불러오는 중…</p><p v-else-if="!records.length" class="empty">{{ resultError ? '파싱 결과 조회 상태를 확인해 주세요.' : '표시할 추출 셀이 없습니다.' }}</p><div v-else class="table-scroll"><table><caption class="visually-hidden">파싱 후보의 항목, 기간, 금액 후보와 원본 위치</caption><thead><tr><th>항목</th><th>대상 기간</th><th>구분</th><th class="number">금액 후보</th><th>원본 위치</th><th>근거</th></tr></thead><tbody><tr v-for="(record, index) in records" :key="index"><td><strong>{{ record.itemName || '항목 미확인' }}</strong><small v-if="record.accountCode">{{ record.accountCode }}</small></td><td>{{ record.year || '—' }}<template v-if="record.month">.{{ String(record.month).padStart(2, '0') }}</template></td><td>{{ ({ PLAN: '계획', ACTUAL: '실적', ESTIMATE: '추정' })[record.scenario] || '미확인' }}</td><td class="number amount">{{ amount(record.amount) }}<small v-if="record.amount !== null && record.amount !== undefined">{{ record.amountUnit === 'KRW' ? '원' : '단위 미확인' }}</small></td><td><button class="text-button" @click="openCell(record)">{{ record.sheet }}<small>{{ record.cell }}</small></button></td><td><details><summary>보기</summary><dl class="cell-evidence"><dt>원본 값</dt><dd>{{ record.rawValue ?? '—' }}</dd><dt>수식</dt><dd>{{ record.formula || '원문 없음 · 원본 셀에서 확인' }}</dd><dt>정규화 상태</dt><dd>{{ record.normalizationStatus || '—' }}</dd></dl></details></td></tr></tbody></table></div><button v-if="recordCursor" class="button load-more" :disabled="!!busy || loading" @click="more('records')">{{ busy === 'records' ? '불러오는 중…' : '추출 셀 더 보기' }}</button></section>
    </template>
  </template>
</template>

<style scoped>
.decision-actions { justify-content:flex-end; margin-top:18px; }.decision-form { margin-top:20px; }.decision-form label span { margin-left:6px; font-weight:400; }.decision-form>p.muted { margin-top:8px; font-size:12px; line-height:1.7; }.reject-reason p { margin:4px 0 0; white-space:pre-wrap; }
.shared-formula { display:block; margin-top:8px; line-height:1.7; }.readiness { margin-top:20px; }.source-panel { outline-offset:4px; }.source-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:18px; }.source-grid dt { color:var(--muted); font-size:12px; }.source-grid dd { margin:5px 0 0; font-size:13px; overflow-wrap:anywhere; white-space:pre-wrap; }.source-grid>div:nth-child(3) { grid-column:1/-1; }@media(max-width:700px) { .source-grid { grid-template-columns:1fr; } }
</style>
