<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useSession } from '../stores/session'
import { dateTime, errorText, query } from '../lib/display'
import { formatMoney } from '../lib/decimal-display'
import ReviewAttachments from './ReviewAttachments.vue'
import RequestedComparison from './RequestedComparison.vue'

const props = defineProps({ submission: { type: Object, default: null }, requestId: { type: String, default: '' }, initialReviewId: { type: String, default: '' }, detailOnly: { type: Boolean, default: false } })
const emit = defineEmits(['changed'])
const session = useSession()
const filters = reactive({ reason: '', explanationState: '', scenario: '' })
const items = ref([])
const cursor = ref(null)
const loading = ref(false)
const error = ref('')
const detail = ref(null)
const detailPanel = ref(null)
const selectedSubmission = ref(null)
const detailLoading = ref(false)
const detailError = ref('')
const answer = ref('')
const note = ref('')
const busy = ref(false)
const attachmentBusy = ref(false)
const message = ref('')
const includeOptional = ref(false)
const evidence = ref(null)
const evidenceReason = ref('CHANGE')
const evidenceBasis = ref('PLAN')
const evidenceLoading = ref(false)
const evidenceError = ref('')
const source = ref(null)
const sourceLoading = ref(false)
let listController = new AbortController()
let detailController = new AbortController()
const lifetime = new AbortController()
let generation = 0
let detailGeneration = 0
let evidenceGeneration = 0
let sourceGeneration = 0
let appliedFilters = {}
const commandKeys = new Map()

/** 조직 담당자에게 Figma v3의 항목 선택·소명 편집 배치를 적용한다.
 * Args: 없음. Returns: 조직 역할 여부. Raises: 없음. Side Effects: 없음.
 */
const organizationLayout = computed(function usesOrganizationLayout() { return session.actor?.role === 'ORG_MANAGER' })

/** 서버 보고항목 종류와 월·시나리오로 소명 목록을 계층화한다.
 * Args: 없음. Returns: 종류별 기간·항목 배열. Raises: 없음. Side Effects: 없음; 근거 셀을 임의 항목으로 합치지 않는다.
 */
const reviewGroups = computed(function groupReviewItems() {
  const groups = new Map()
  for (const item of items.value) {
    if (organizationLayout.value && !includeOptional.value && !item.required && item.workflowState !== 'EXCLUDED') continue
    const kind = item.reportItem?.kind || 'OTHER'
    if (!groups.has(kind)) groups.set(kind, { kind, name: kindLabel(kind), periods: new Map(), count: 0 })
    const group = groups.get(kind)
    const period = `${item.month}:${item.scenario}`
    if (!group.periods.has(period)) group.periods.set(period, { key: period, title: `${item.month}월 ${scenarioLabel(item.scenario)}`, items: [] })
    group.periods.get(period).items.push(item)
    group.count++
  }
  const result = []
  for (const group of groups.values()) result.push({ ...group, periods: [...group.periods.values()] })
  return result
})

/** 선택한 비교 기준의 실제 금액을 표시한다.
 * Args: 없음. Returns: 비교 DTO 또는 null. Raises: 없음. Side Effects: 없음.
 */
const activeComparison = computed(function selectedComparison() {
  for (const comparison of detail.value?.comparisons || []) if (comparison.basis === evidenceBasis.value) return comparison
  return detail.value?.comparisons?.[0] || null
})

/** 현재 제출본의 원본 위치만 근거 목록에서 추린다.
 * Args: 없음. Returns: 원본 근거 배열. Raises: 없음. Side Effects: 없음.
 */
const currentSources = computed(function currentSourceCells() {
  const result = []
  for (const item of evidence.value?.items || []) if (item.side === 'AFTER' && item.source?.cell) result.push(item)
  return result
})

/** 종류 코드를 사용자에게 익숙한 매출·비용·손익으로 표시한다.
 * Args: kind 보고항목 종류. Returns: 표시명. Raises: 없음. Side Effects: 없음.
 */
function kindLabel(kind) { return ({ REVENUE: '매출', COST: '비용', DERIVED: '영업이익', OP: '영업이익', OTHER: '기타 항목' })[kind] || kind }

/** 시나리오 코드를 화면 문구로 표시한다.
 * Args: scenario PLAN/ACTUAL/ESTIMATE. Returns: 표시명. Raises: 없음. Side Effects: 없음.
 */
function scenarioLabel(scenario) { return ({ PLAN: '계획', ACTUAL: '실적', ESTIMATE: '추정' })[scenario] || scenario }

/** 현재 필터로 소명 목록을 조회하며 다음 페이지는 적용한 필터를 유지한다.
 * Args: more 다음 페이지 여부. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: GET·목록 갱신·이전 목록 조회 취소; 서버의 필수 소명을 임의 제외하지 않는다.
 */
async function load(more = false) {
  if (props.detailOnly && props.initialReviewId) {
    if (!detail.value) await select(props.initialReviewId)
    return
  }
  if (more && (loading.value || !cursor.value)) return
  listController.abort(); listController = new AbortController()
  const current = ++generation
  if (!more) { appliedFilters = { ...filters }; items.value = []; cursor.value = null }
  loading.value = true; error.value = ''
  try {
    const page = await session.request(`/api/v1/review-items${query({ ...appliedFilters, submissionId: props.submission?.id, requestId: props.requestId || null, limit: 50, cursor: more ? cursor.value : null })}`, { signal: listController.signal })
    if (current !== generation) return
    items.value = more ? [...items.value, ...page.items] : page.items; cursor.value = page.nextCursor
    if (organizationLayout.value && props.submission) {
      while (cursor.value) {
        const next = await session.request(`/api/v1/review-items${query({ ...appliedFilters, submissionId: props.submission.id, limit: 50, cursor: cursor.value })}`, { signal: listController.signal })
        if (current !== generation) return
        items.value.push(...next.items); cursor.value = next.nextCursor
      }
    }
    if (organizationLayout.value && !more && !detail.value) {
      for (const item of items.value) if (item.required || item.workflowState === 'EXCLUDED') { await select(item.id); break }
    }
    if (!organizationLayout.value && !more && props.initialReviewId && !detail.value) await select(props.initialReviewId)
  } catch (cause) { if (current === generation && cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { if (current === generation) loading.value = false }
}

/** 검토 건과 제출 상태를 읽고 최신 선택의 회차·답변만 편집기에 넣는다.
 * Args: id 검토 건 ID. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: 이전 상세 취소·GET·선택/근거 패널 초기화·최신 상세 렌더 후 패널로 스크롤과 키보드 포커스 이동. 서버 십진값은 보존하고 비율·기준의 끝 소수 0은 템플릿 표시에서만 제거한다.
 */
async function select(id) {
  if (busy.value) return
  detailController.abort(); detailController = new AbortController()
  const current = ++detailGeneration
  evidenceGeneration++; sourceGeneration++
  detail.value = null; evidence.value = null; source.value = null; selectedSubmission.value = null
  evidenceLoading.value = false; sourceLoading.value = false
  detailLoading.value = true; detailError.value = ''; evidenceError.value = ''; message.value = ''; answer.value = ''; note.value = ''
  try {
    const item = await session.request(`/api/v1/review-items/${id}`, { signal: detailController.signal })
    if (current !== detailGeneration) return
    const submission = props.submission || await session.request(`/api/v1/submissions/${item.submissionId}`, { signal: detailController.signal })
    if (current !== detailGeneration) return
    detail.value = item; selectedSubmission.value = submission
    const latest = item.explanations?.at(-1)
    answer.value = latest?.answer || ''
    evidenceReason.value = item.comparisons?.length ? 'GAP' : item.changeDetected ? 'CHANGE' : ''
    evidenceBasis.value = item.comparisons?.[0]?.basis || 'PLAN'
    detailLoading.value = false
    await nextTick()
    if (current !== detailGeneration) return
    detailPanel.value?.focus({ preventScroll: true })
    if (!organizationLayout.value) detailPanel.value?.scrollIntoView({ behavior: 'auto', block: 'start' })
    if (organizationLayout.value && evidenceReason.value) await loadEvidence(false)
  } catch (cause) { if (current === detailGeneration && cause.name !== 'AbortError') detailError.value = errorText(cause) }
  finally { if (current === detailGeneration) detailLoading.value = false }
}

/** 소유 조직의 초안 답변 또는 제출 후 새 보완 회차만 편집할 수 있다.
 * Args: 없음. Returns: 답변 가능 여부. Raises: 없음. Side Effects: 없음.
 */
const canAnswer = computed(function allowsAnswer() {
  if (session.actor?.role !== 'ORG_MANAGER' || !detail.value || detail.value.explanationState === 'ACCEPTED' || detail.value.workflowState === 'EXCLUDED') return false
  const status = props.submission?.status || selectedSubmission.value?.status
  return status === 'DRAFT' || (status === 'PENDING' && detail.value.explanationState === 'UNANSWERED' && !!detail.value.explanations?.at(-1)?.requestedBy)
})

/** 기획자는 제출된 현재 답변의 수용 또는 보완 요청만 할 수 있다.
 * Args: 없음. Returns: 검토 가능 여부. Raises: 없음. Side Effects: 없음.
 */
const canDecide = computed(function allowsDecision() {
  return session.actor?.role === 'PLANNER' && (props.submission?.status || selectedSubmission.value?.status) === 'PENDING' && detail.value?.explanationState === 'IN_REVIEW' && detail.value?.workflowState !== 'EXCLUDED'
})

/** 명시한 답변 저장·수용·보완 요청을 현재 회차와 토큰으로 전송한다.
 * Args: action ANSWER/ACCEPT/REQUEST_CHANGES. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: PUT/POST·응답 갱신·changed 이벤트; 통신 재시도는 같은 본문·키를 유지한다.
 */
async function save(action) {
  if (busy.value || attachmentBusy.value || !detail.value || (action === 'ANSWER' ? !canAnswer.value : !canDecide.value)) return
  const content = answer.value.trim(); const reviewNote = note.value.trim()
  if (action === 'ANSWER' && (!content || content.length > 4000)) { detailError.value = '소명 답변은 공백을 제외해 1~4,000자로 입력해 주세요.'; return }
  if (action === 'REQUEST_CHANGES' && !reviewNote) { detailError.value = '보완이 필요한 내용을 입력해 주세요.'; return }
  const payload = { expectedStateToken: detail.value.stateToken, round: detail.value.currentRound }
  if (action === 'ANSWER') payload.answer = content
  else { payload.action = action; if (reviewNote) payload.note = reviewNote }
  const path = `/api/v1/review-items/${detail.value.id}/${action === 'ANSWER' ? 'answer' : 'decisions'}`
  const body = JSON.stringify(payload); const fingerprint = `${path}:${body}`
  if (!commandKeys.has(fingerprint)) commandKeys.set(fingerprint, crypto.randomUUID())
  busy.value = true; detailError.value = ''; message.value = ''
  try {
    const result = await session.request(path, { method: action === 'ANSWER' ? 'PUT' : 'POST', body, headers: { 'Idempotency-Key': commandKeys.get(fingerprint) }, signal: lifetime.signal })
    if (lifetime.signal.aborted) return
    detail.value = result; answer.value = result.explanations?.at(-1)?.answer || ''; note.value = ''
    commandKeys.delete(fingerprint)
    message.value = action === 'ANSWER' ? '소명 답변을 저장했습니다.' : action === 'ACCEPT' ? '소명을 수용했습니다.' : '새 소명 회차로 보완을 요청했습니다.'
    await load(false); emit('changed')
  } catch (cause) {
    if (cause.name !== 'AbortError' && !lifetime.signal.aborted) detailError.value = errorText(cause)
  } finally { if (!lifetime.signal.aborted) busy.value = false }
}

/** 선택한 변경/Gap 비교의 원천을 앞뒤 다중집합으로 조회한다.
 * Args: more 다음 페이지 여부. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: GET·근거 갱신; 서로 다른 상세를 임의의 일대일 변경으로 연결하지 않는다.
 */
async function loadEvidence(more = false) {
  if (!detail.value || (more && (evidenceLoading.value || !evidence.value?.nextCursor))) return
  const current = ++evidenceGeneration; const selected = detailGeneration
  const previous = more ? evidence.value : null
  sourceGeneration++; sourceLoading.value = false
  if (!more) evidence.value = null
  evidenceLoading.value = true; evidenceError.value = ''; source.value = null
  try {
    const reason = previous?.reason || evidenceReason.value
    const basis = reason === 'GAP' ? previous?.basis || evidenceBasis.value : null
    const result = await session.request(`/api/v1/review-items/${detail.value.id}/evidence${query({ reason, basis, limit: 50, cursor: previous?.nextCursor })}`, { signal: detailController.signal })
    if (current !== evidenceGeneration || selected !== detailGeneration) return
    evidence.value = { ...result, items: previous ? [...previous.items, ...result.items] : result.items }
  } catch (cause) { if (current === evidenceGeneration && selected === detailGeneration && cause.name !== 'AbortError') evidenceError.value = errorText(cause) }
  finally { if (current === evidenceGeneration && selected === detailGeneration) evidenceLoading.value = false }
}

/** 기준본 또는 현재본의 실제 원본 셀을 해당 제출 ID로 조회한다.
 * Args: position 서버가 반환한 submissionId/sheet/cell. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: GET·원본 표시; 기준 셀을 현재 제출본의 셀로 바꾸지 않는다.
 */
async function openSource(position) {
  const current = ++sourceGeneration; const selected = detailGeneration
  source.value = null; sourceLoading.value = true; evidenceError.value = ''
  try {
    const result = await session.request(`/api/v1/submissions/${position.submissionId}/source-cells${query({ sheet: position.sheet, cell: position.cell })}`, { signal: detailController.signal })
    if (current === sourceGeneration && selected === detailGeneration) source.value = { ...result, position }
  } catch (cause) { if (current === sourceGeneration && selected === detailGeneration && cause.name !== 'AbortError') evidenceError.value = errorText(cause) }
  finally { if (current === sourceGeneration && selected === detailGeneration) sourceLoading.value = false }
}

/** 서버 소명 상태를 표시 이름으로 바꾼다.
 * Args: value 상태. Returns: 표시 이름. Raises: 없음. Side Effects: 없음.
 */
function stateLabel(value) { return ({ UNANSWERED: '미작성', ANSWERED: '작성 완료', IN_REVIEW: '검토 대기', ACCEPTED: '수락 완료' })[value] || value }

/** 첨부 저장이 반환한 현재 토큰을 소명 답변에 연결한다.
 * Args: review 서버가 반환한 갱신 검토 DTO. Returns: 없음. Raises: 없음.
 * Side Effects: 현재 상세·상위 제출 상태만 갱신하며 작성 중인 답변 문자열은 보존한다.
 */
function attachmentChanged(review) { if (detail.value?.id === review.id) { detail.value = review; emit('changed') } }

/** 조회·명령과 이전 선택의 응답을 중단한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소·세대 무효화.
 */
function dispose() { generation++; detailGeneration++; evidenceGeneration++; sourceGeneration++; listController.abort(); detailController.abort(); lifetime.abort() }
onMounted(load)
onBeforeUnmount(dispose)
</script>

<template>
  <section v-if="organizationLayout" class="panel organization-reviews" aria-labelledby="organization-reviews-title">
    <div class="section-heading"><h2 id="organization-reviews-title">{{ submission?.status === 'DRAFT' ? '변경 확인·소명' : submission?.status === 'PENDING' && submission?.readiness?.unansweredCount > 0 ? '소명 보완' : '변경 항목·소명 이력' }}</h2><span class="muted">필수 소명 {{ submission?.readiness?.requiredCount ?? '—' }}건 · 미작성 {{ submission?.readiness?.unansweredCount ?? '—' }}건</span></div>
    <p v-if="submission?.status === 'DRAFT'" class="muted">검증 당시 소명 정책에 따라 ‘소명 필수’로 표시된 항목의 사유를 작성해 주세요. 제외된 항목은 필수 소명에 포함되지 않습니다.</p>
    <label v-if="items.length" class="optional-toggle"><input v-model="includeOptional" type="checkbox" />필수 대상이 아닌 변경 항목도 보기</label>
    <div v-if="error" class="alert error" role="alert">{{ error }} <button class="text-button" @click="load(false)">다시 조회</button></div>
    <p v-if="loading && !items.length" class="empty" role="status">소명 항목을 불러오는 중…</p>
    <div v-else-if="!reviewGroups.length && !error" class="empty compact"><strong>작성할 소명이 없습니다</strong><p>{{ submission?.type === 'ANNUAL_PLAN' ? '필수 소명이 없는 계획은 파일 검증 후 제출할 수 있습니다.' : '현재 검증 결과에 소명 대상이 없습니다. 제출 내용을 확인해 주세요.' }}</p></div>
    <div v-if="reviewGroups.length" class="review-workspace">
      <aside class="review-tree" aria-label="소명 대상"><h3>소명 대상</h3>
        <details v-for="group in reviewGroups" :key="group.kind" class="kind-group" open><summary><span>{{ group.name }}</span><small>{{ group.count }}개 항목</small></summary>
          <div class="kind-children"><details v-for="period in group.periods" :key="period.key" class="period-group" open><summary><span>{{ period.title }}</span><small>{{ period.items.length }}개 항목</small></summary>
            <div class="period-children"><button v-for="item in period.items" :key="item.id" :class="['review-choice', { selected: detail?.id === item.id }]" :disabled="busy" :aria-pressed="detail?.id === item.id" @click="select(item.id)"><span><strong>{{ item.reportItem?.name || item.reportItemName }}</strong><small :class="['badge', { green: ['ANSWERED', 'ACCEPTED'].includes(item.explanationState) }]">{{ item.workflowState === 'EXCLUDED' ? '제외' : stateLabel(item.explanationState) }}</small></span><small>{{ item.required ? '소명 필수' : '참고 항목' }}</small><span class="choice-amount">{{ item.amountPresent ? formatMoney(item.currentAmount, 'MILLION') : '항목 제거' }}<small v-if="item.amountPresent"> 백만원</small></span></button></div>
          </details></div>
        </details>
        <button v-if="cursor" class="button" :disabled="loading || busy" @click="load(true)">항목 더 보기</button>
      </aside>
      <div class="review-editor-column">
        <p v-if="detailLoading" class="empty" role="status">비교 내용을 불러오는 중…</p>
        <div v-if="detailError" class="alert error" role="alert">{{ detailError }}</div><p v-if="message" class="notice" role="status">{{ message }}</p>
        <section v-if="detail" ref="detailPanel" class="org-review-editor" tabindex="-1" :aria-labelledby="`org-review-${detail.id}`">
          <div class="section-heading"><div><p class="muted">{{ kindLabel(detail.reportItem?.kind) }} › {{ detail.month }}월 {{ scenarioLabel(detail.scenario) }}</p><h3 :id="`org-review-${detail.id}`">{{ detail.reportItem?.name || detail.reportItemName }}</h3></div><span class="unit">금액 단위 백만원</span></div>
          <div v-if="detail.comparisons?.length > 1" class="comparison-tabs" aria-label="비교 기준"><button v-for="comparison in detail.comparisons" :key="comparison.basis" :class="{ active: evidenceBasis === comparison.basis }" :disabled="evidenceLoading" @click="evidenceBasis = comparison.basis; evidenceReason = 'GAP'; loadEvidence(false)">{{ comparison.basis === 'PLAN' ? '연간 계획 대비' : '이전 제출 대비' }}</button></div>
          <div class="version-comparison"><div><span>원본 셀</span><button v-for="item in currentSources.slice(0, 2)" :key="item.valueId" class="text-button source-link" @click="openSource(item.source)">{{ item.source.sheet }}!{{ item.source.cell }}</button><strong v-if="!currentSources.length">{{ evidenceLoading ? '조회 중…' : detail.amountPresent ? '원본 근거 보기' : '이번 제출에서 제거' }}</strong><small v-if="currentSources.length > 2">외 {{ currentSources.length - 2 }}개 · 아래 근거에서 확인</small><small>{{ detail.month }}월 {{ scenarioLabel(detail.scenario) }}</small></div><div><span>{{ activeComparison?.basis === 'PLAN' ? '연간 계획' : '이전 제출값' }}</span><strong>{{ formatMoney(activeComparison?.baselineAmount ?? evidence?.beforeTotal, 'MILLION') }}</strong><small>{{ activeComparison?.baselineScenario ? scenarioLabel(activeComparison.baselineScenario) : '' }}</small></div><div class="current-value"><span>이번 제출값</span><strong>{{ detail.amountPresent ? formatMoney(detail.currentAmount, 'MILLION') : '항목 제거' }}</strong><small>{{ submission?.versionLabel || '제출 전 초안' }}</small></div><div><span>기준 대비 차이</span><strong>{{ formatMoney(activeComparison?.gapAmount ?? evidence?.netDelta, 'MILLION') }}</strong><small>{{ activeComparison?.gapRate === null ? '기준값 0 · 비율 없음' : activeComparison?.gapRate === undefined ? '' : `${formatMoney(activeComparison.gapRate)}%` }}</small></div></div>
          <p v-if="detail.workflowState === 'EXCLUDED'" class="notice">경영기획팀에서 제외한 항목입니다. 저장된 금액과 이전 소명은 그대로 보존됩니다.</p>
          <RequestedComparison :comparison="detail.explanations?.at(-1)?.requestComparison" /><div v-if="detail.explanations?.at(-1)?.requestNote" class="notice warning"><strong>경영기획팀 요청</strong><p class="preserve-lines">{{ detail.explanations.at(-1).requestNote }}</p></div>
          <details v-if="detail.explanations?.length && detail.explanations.some(round => round.answer)" class="previous-answers" :open="submission?.status !== 'DRAFT'"><summary>지난 소명 사유 · {{ detail.currentRound }}회차</summary><ol class="explanation-rounds"><li v-for="round in detail.explanations" :key="round.id"><strong>{{ round.round }}회차 · {{ ({ PENDING: '검토 전', ACCEPTED: '수락', REJECTED: '보완 요청' })[round.verdict] || round.verdict }}</strong><p>{{ round.answer || '답변 대기 중입니다.' }}</p><small v-if="round.answeredAt">{{ dateTime(round.answeredAt) }}</small><p v-if="round.reviewNote">경영기획팀 의견: {{ round.reviewNote }}</p><RequestedComparison :comparison="round.requestComparison" /><small v-if="round.inheritedFrom">이전 수락 소명 · {{ round.inheritedFrom.versionLabel }}</small></li></ol></details>
          <ReviewAttachments :key="`attachments:${detail.id}:${detail.currentRound}`" :review="detail" :editable="canAnswer && !busy" @changed="attachmentChanged" @busy="attachmentBusy = $event" />
          <form v-if="canAnswer" class="org-answer" @submit.prevent="save('ANSWER')"><p class="muted">차이가 난 원인과 향후 대응, 금액을 변경한 근거를 적어 주세요.</p><label :for="`org-answer-${detail.id}`">{{ submission?.status === 'PENDING' ? '보완 소명' : '이번 소명' }}<textarea :id="`org-answer-${detail.id}`" v-model="answer" :disabled="busy" maxlength="4000" required rows="4" placeholder="변경 원인과 향후 대응을 입력하세요."></textarea></label><div class="answer-footer"><small class="muted">{{ answer.length }}/4,000자</small><button class="button primary" :disabled="busy || attachmentBusy || !answer.trim()">{{ busy ? '저장 중…' : submission?.status === 'PENDING' ? '보완 소명 제출' : '소명 저장' }}</button></div></form>
          <details class="source-details"><summary>원본 근거·버전별 상세</summary><div v-if="evidenceError" class="alert error" role="alert">{{ evidenceError }}</div><div class="button-row"><button v-if="detail.changeDetected" class="text-button" :disabled="evidenceLoading" @click="evidenceReason = 'CHANGE'; loadEvidence(false)">이전 파일과 변경 비교</button><button v-if="detail.comparisons?.length" class="text-button" :disabled="evidenceLoading" @click="evidenceReason = 'GAP'; loadEvidence(false)">현재 Gap 기준 비교</button></div><p v-if="evidenceLoading" class="muted" role="status">원본 근거를 불러오는 중…</p><div v-if="evidence" class="table-scroll"><table><caption class="visually-hidden">비교 기준과 이번 제출의 원본 근거</caption><thead><tr><th>버전</th><th>원본 위치</th><th class="number">금액 (백만원)</th></tr></thead><tbody><tr v-for="(item, index) in evidence.items" :key="index"><td>{{ item.side === 'BEFORE' ? '비교 기준' : '이번 제출' }}</td><td><button v-if="item.source?.cell" class="text-button" @click="openSource(item.source)">{{ item.source.sheet }}!{{ item.source.cell }}</button></td><td class="number">{{ formatMoney(item.amount, 'MILLION') }}</td></tr></tbody></table><button v-if="evidence.nextCursor" class="button" :disabled="evidenceLoading" @click="loadEvidence(true)">근거 더 보기</button></div></details>
          <p v-if="sourceLoading" class="muted" role="status">원본 셀을 확인하는 중…</p><dl v-if="source" class="source-evidence"><div><dt>원본 위치</dt><dd>제출 {{ source.position.submissionId }} · {{ source.position.sheet }}!{{ source.position.cell }}</dd></div><div><dt>원본 값</dt><dd>{{ source.rawValue ?? '—' }}</dd></div><div><dt>수식</dt><dd>{{ source.formula || source.sharedFormulaBase?.formula || '없음' }}</dd></div><div><dt>원 단위 금액</dt><dd>{{ formatMoney(source.normalizedAmount) }}</dd></div></dl>
        </section>
      </div>
    </div>
    <slot name="footer"></slot>
  </section>
  <section v-else class="panel reviews-panel" :aria-labelledby="detailOnly ? undefined : 'reviews-title'">
    <template v-if="!detailOnly">
    <div class="section-heading"><div><h2 id="reviews-title">변경 항목·Gap 소명</h2><p class="muted">비교 기준과 원본 근거를 확인합니다. 필수 소명을 수락하거나 검토 대상에서 제외한 뒤 제출본을 승인할 수 있습니다.</p></div><button class="button" :disabled="loading || busy" @click="load(false)">새로고침</button></div>
    <form class="filters" @submit.prevent="load(false)"><label>사유<select v-model="filters.reason"><option value="">전체</option><option value="CHANGE">금액·원천 변경</option><option value="GAP">손익 Gap</option><option value="INHERITED">이전 소명 이어받기</option></select></label><label>상태<select v-model="filters.explanationState"><option value="">전체</option><option value="UNANSWERED">답변 필요</option><option value="ANSWERED">답변 저장</option><option value="IN_REVIEW">검토 대기</option><option value="ACCEPTED">수용 완료</option></select></label><label>구분<select v-model="filters.scenario"><option value="">전체</option><option value="PLAN">계획</option><option value="ACTUAL">실적</option><option value="ESTIMATE">추정</option></select></label><button class="button" :disabled="loading || busy">조회</button></form>
    <div v-if="error" class="alert error" role="alert">{{ error }}</div><p v-if="loading && !items.length" class="empty" role="status">소명을 불러오는 중…</p><p v-else-if="!items.length && !error" class="empty compact">선택한 조건의 소명 항목이 없습니다.</p>
    <div v-if="items.length" class="table-scroll"><table><caption class="visually-hidden">필수 소명과 진행 상태</caption><thead><tr><th>항목</th><th>월 · 구분</th><th>사유</th><th>상태</th><th class="number">현재 금액 (원)</th><th>확인</th></tr></thead><tbody><tr v-for="item in items" :key="item.id"><th scope="row">{{ item.reportItem?.name || item.reportItemName }}<small>{{ item.required ? '필수' : '참고' }} · 조직 {{ item.organizationId }}</small></th><td>{{ item.year }}.{{ String(item.month).padStart(2, '0') }} · {{ item.scenario }}</td><td>{{ item.changeDetected ? '변경 ' : '' }}{{ item.gapExceeded ? '손익 Gap' : '' }}{{ !item.changeDetected && !item.gapExceeded ? '이어받기' : '' }}</td><td>{{ stateLabel(item.explanationState) }}</td><td class="number">{{ item.amountPresent ? formatMoney(item.currentAmount) : '항목 제거' }}</td><td><button class="text-button" :disabled="busy" @click="select(item.id)">소명 · 근거</button></td></tr></tbody></table></div>
    <button v-if="cursor" class="button load-more" :disabled="loading || busy" @click="load(true)">소명 더 보기</button>
    </template>
    <p v-if="detailLoading" class="empty" role="status">소명 상세를 불러오는 중…</p><div v-if="detailError" class="alert error" role="alert">{{ detailError }}</div><p v-if="message" class="notice" role="status">{{ message }}</p>
    <section v-if="detail" ref="detailPanel" class="review-detail" tabindex="-1" :aria-labelledby="`review-detail-title-${detail.id}`"><div class="section-heading"><h3 :id="`review-detail-title-${detail.id}`">{{ detail.reportItem?.name || detail.reportItemName }} · {{ detail.month }}월 {{ detail.scenario }}</h3><div class="button-row"><span class="badge">{{ stateLabel(detail.explanationState) }} · {{ detail.currentRound }}회차</span><button class="text-button" :disabled="busy || detailLoading" @click="select(detail.id)">상세 새로고침</button></div></div>
      <div v-if="detail.comparisons?.length" class="table-scroll"><table><caption class="visually-hidden">확정 계획 및 이전 제출 대비 손익 Gap</caption><thead><tr><th>비교 기준</th><th class="number">기준 손익</th><th class="number">현재 손익</th><th class="number">차액</th><th>차이율</th><th>초과</th></tr></thead><tbody><tr v-for="comparison in detail.comparisons" :key="comparison.basis"><th scope="row">{{ comparison.basis === 'PLAN' ? '확정 계획' : '이전 제출' }} · {{ comparison.baselineScenario }}</th><td class="number">{{ formatMoney(comparison.baselineAmount) }}</td><td class="number">{{ formatMoney(comparison.currentAmount) }}</td><td class="number">{{ formatMoney(comparison.gapAmount) }}</td><td>{{ comparison.gapRate === null ? '기준 0 · 비율 없음' : `${formatMoney(comparison.gapRate)}%` }}</td><td>{{ comparison.exceeded ? '초과' : '미초과' }}<small>{{ formatMoney(comparison.threshold?.percent) }}% · {{ formatMoney(comparison.threshold?.amount) }}원</small></td></tr></tbody></table></div>
      <ol class="explanation-rounds"><li v-for="round in detail.explanations" :key="round.id"><strong>{{ round.round }}회차 · {{ ({ PENDING: '검토 전', ACCEPTED: '수용', REJECTED: '보완 요청' })[round.verdict] || round.verdict }}</strong><RequestedComparison :comparison="round.requestComparison" /><p v-if="round.requestNote">보완 요청: {{ round.requestNote }}</p><p>{{ round.answer || '아직 답변이 없습니다.' }}</p><small v-if="round.answeredAt">{{ round.answeredBy?.name }} · {{ dateTime(round.answeredAt) }}</small><p v-if="round.reviewNote">검토 의견: {{ round.reviewNote }}</p><small v-if="round.reviewedAt">{{ round.reviewedBy?.name }} · {{ dateTime(round.reviewedAt) }}</small><small v-if="round.inheritedFrom">이전 수용 소명 · {{ round.inheritedFrom.versionLabel }}</small></li></ol>
      <ReviewAttachments :key="`attachments:${detail.id}:${detail.currentRound}`" :review="detail" :editable="canAnswer && !busy" @changed="attachmentChanged" @busy="attachmentBusy = $event" />
      <form v-if="canAnswer" @submit.prevent="save('ANSWER')"><label>소명 사유 · 필수<textarea v-model="answer" :disabled="busy" maxlength="4000" required rows="4" placeholder="금액이 변경된 사유와 비교 차이의 근거를 작성해 주세요."></textarea></label><button class="button primary" :disabled="busy || !answer.trim()">{{ busy ? '저장 중…' : '소명 답변 저장' }}</button></form>
      <div v-if="canDecide" class="review-actions"><label>검토 의견 · 보완 요청 시 필수<textarea v-model="note" :disabled="busy" maxlength="2000" rows="3"></textarea></label><div class="button-row"><button class="button" :disabled="busy || !note.trim()" @click="save('REQUEST_CHANGES')">보완 요청</button><button class="button primary" :disabled="busy" @click="save('ACCEPT')">소명 수용</button></div></div>
      <div class="evidence-controls"><h3>변경 전후 · 원본 근거</h3><label>근거<select v-model="evidenceReason" :disabled="evidenceLoading"><option v-if="detail.changeDetected" value="CHANGE">원천 변경</option><option v-if="detail.comparisons?.length" value="GAP">손익 Gap</option></select></label><label v-if="evidenceReason === 'GAP'">비교<select v-model="evidenceBasis" :disabled="evidenceLoading"><option v-for="comparison in detail.comparisons" :key="comparison.basis" :value="comparison.basis">{{ comparison.basis === 'PLAN' ? '확정 계획' : '이전 제출' }}</option></select></label><button class="button" :disabled="evidenceLoading || (!detail.changeDetected && !detail.comparisons?.length)" @click="loadEvidence(false)">근거 조회</button></div>
      <div v-if="evidenceError" class="alert error" role="alert">{{ evidenceError }}</div><p v-if="evidenceLoading" class="muted" role="status">원천 근거를 불러오는 중…</p>
      <template v-if="evidence"><p class="muted">원천 합계: 이전 {{ formatMoney(evidence.beforeTotal) }}원 → 현재 {{ formatMoney(evidence.afterTotal) }}원 · 차액 {{ formatMoney(evidence.netDelta) }}원. {{ evidence.comparisonMode === 'MULTISET' ? '기준본과 현재본의 원천 내역을 각각 표시합니다.' : '' }}</p><div class="table-scroll"><table><caption class="visually-hidden">변경 전후 원천 행과 셀</caption><thead><tr><th>구분</th><th>원천 의미</th><th class="number">금액</th><th>원본</th></tr></thead><tbody><tr v-for="(item, index) in evidence.items" :key="index"><td>{{ item.side === 'BEFORE' ? '이전' : '현재' }}</td><td><span v-for="(value, key) in item.semanticAttributes" :key="key" class="semantic-value">{{ key }}: {{ value ?? '—' }}</span></td><td class="number">{{ formatMoney(item.amount) }}</td><td><button v-if="item.source?.cell" class="text-button" @click="openSource(item.source)">{{ item.source.sheet }} {{ item.source.cell }}</button></td></tr></tbody></table></div><button v-if="evidence.nextCursor" class="button load-more" :disabled="evidenceLoading" @click="loadEvidence(true)">근거 더 보기</button></template>
      <p v-if="sourceLoading" class="muted" role="status">원본 셀을 확인하는 중…</p><dl v-if="source" class="source-evidence"><div><dt>원본 위치</dt><dd>제출 {{ source.position.submissionId }} · {{ source.position.sheet }} {{ source.position.cell }}</dd></div><div><dt>원본 값</dt><dd>{{ source.rawValue ?? '—' }}</dd></div><div><dt>수식</dt><dd>{{ source.formula || source.sharedFormulaBase?.formula || '없음' }}</dd></div><div><dt>원 단위 금액</dt><dd>{{ formatMoney(source.normalizedAmount) }}</dd></div></dl>
    </section>
  </section>
</template>

<style scoped>
.organization-reviews { gap:14px; }
.optional-toggle { display:flex; align-items:center; gap:7px; font-weight:400; font-size:11px; color:var(--muted); }.optional-toggle input { width:14px; height:14px; min-height:14px; margin:0; accent-color:var(--gold); }
.review-workspace { display:grid; grid-template-columns:296px minmax(0,1fr); align-items:start; gap:18px; min-height:420px; }
.review-tree { display:flex; flex-direction:column; gap:12px; min-width:0; }
.review-tree h3 { font-size:14px; }
.kind-group>summary,.period-group>summary { display:flex; align-items:center; justify-content:space-between; padding:7px 9px; border-radius:6px; font-size:12px; font-weight:700; white-space:normal; }
.kind-group>summary { background:#323231; color:white; }.kind-group>summary small { color:#e3e3e2; }
.kind-group>summary span::before,.period-group>summary span::before { content:'−'; margin-right:7px; }.kind-group:not([open])>summary span::before,.period-group:not([open])>summary span::before { content:'+'; }
.kind-children { padding:6px 8px; border-left:2px solid #323231; border-bottom:2px solid #323231; }
.period-group>summary { background:#d8eef2; color:#214f5c; }.period-group>summary small,.kind-group>summary small { font-size:11px; font-weight:400; }
.period-children { display:flex; flex-direction:column; gap:5px; padding:6px 7px; border-left:2px solid #73b7c5; border-bottom:2px solid #73b7c5; }
.review-choice { display:flex; flex-direction:column; align-items:stretch; gap:6px; width:100%; background:#f7f7f7; border:1px solid var(--line); border-radius:6px; padding:9px 10px; text-align:left; color:var(--ink); }
.review-choice.selected { background:#f2ebdf; border-color:var(--gold); }.review-choice>span:first-child { display:flex; align-items:start; justify-content:space-between; gap:6px; }.review-choice strong { font-size:12px; line-height:1.5; }.review-choice small { font-size:11px; color:var(--muted); }.review-choice .badge { font-size:9px; padding:1px 6px; }.choice-amount { font-size:11px; }
.review-editor-column { min-width:0; }.org-review-editor { display:flex; flex-direction:column; gap:14px; border:1px solid var(--line); border-radius:10px; padding:18px; outline-offset:4px; }.org-review-editor h3 { font-size:16px; margin-top:3px; }.org-review-editor .muted,.unit { font-size:11px; }.unit { color:var(--muted); white-space:nowrap; }
.version-comparison { display:grid; grid-template-columns:1.1fr 1fr 1.1fr 1fr; border:1px solid var(--line); border-radius:8px; overflow:hidden; }.version-comparison>div { display:flex; flex-direction:column; gap:5px; padding:10px; border-right:1px solid var(--line); min-width:0; }.version-comparison>div:last-child { border-right:0; }.version-comparison span { color:var(--muted); font-size:11px; }.version-comparison strong { font-size:15px; overflow-wrap:anywhere; font-variant-numeric:tabular-nums; }.version-comparison small { font-size:10px; color:var(--muted); }.version-comparison .current-value { background:#f6f0e6; }.source-link { text-align:left; font-size:10px; overflow-wrap:anywhere; }
.comparison-tabs { display:flex; gap:5px; }.comparison-tabs button { padding:6px 9px; border:1px solid var(--line); border-radius:6px; background:white; font-size:11px; }.comparison-tabs .active { color:var(--gold-ink); background:#f5eee3; border-color:var(--gold); }
.org-answer { display:flex; flex-direction:column; gap:8px; }.org-answer textarea { min-height:94px; }.answer-footer { display:flex; justify-content:space-between; align-items:center; gap:12px; }.answer-footer small { font-size:10px; }.previous-answers,.source-details { font-size:12px; }.previous-answers .explanation-rounds { margin:10px 0 0; }.organization-reviews .explanation-rounds li { font-size:12px; padding:10px; }.source-details>.button-row { margin:12px 0; }.source-details summary { color:var(--muted); font-size:11px; }
@media(max-width:1150px) { .review-workspace { grid-template-columns:250px minmax(0,1fr); }.version-comparison { grid-template-columns:1fr 1fr; }.version-comparison>div { border-bottom:1px solid var(--line); }.version-comparison>div:nth-child(n+3) { border-bottom:0; } }
@media(max-width:850px) { .review-workspace { grid-template-columns:1fr; }.review-tree { max-height:300px; overflow-y:auto; }.org-review-editor { padding:14px; }.organization-reviews>.section-heading { align-items:flex-start; flex-direction:column; } }
.review-detail { scroll-margin-top:24px; margin-top:24px; padding-top:24px; border-top:1px solid var(--line); }.review-detail form,.review-actions { margin:20px 0; }.explanation-rounds { padding:0; margin:20px 0; list-style:none; display:grid; gap:12px; }.explanation-rounds li { padding:15px; border:1px solid var(--line); border-radius:8px; background:#faf9f6; font-size:13px; }.explanation-rounds p { white-space:pre-wrap; margin-top:8px; line-height:1.7; }.explanation-rounds small { display:block; margin-top:6px; color:var(--muted); }.evidence-controls { display:flex; align-items:flex-end; flex-wrap:wrap; gap:12px; margin-top:24px; }.evidence-controls h3 { width:100%; }.evidence-controls label { min-width:140px; margin:0; }.evidence-controls select { margin-bottom:0; }.semantic-value { display:block; font-size:11px; white-space:normal; }.source-evidence { margin-top:20px; padding:16px; background:#faf9f6; border-radius:8px; font-size:12px; }.source-evidence>div { margin-bottom:12px; }.source-evidence dt { color:var(--muted); }.source-evidence dd { margin:6px 0 0; overflow-wrap:anywhere; white-space:pre-wrap; }.reviews-panel th small { display:block; font-weight:400; color:var(--muted); margin-top:5px; }
</style>
