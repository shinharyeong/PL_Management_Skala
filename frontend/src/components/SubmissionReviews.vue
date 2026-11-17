<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useSession } from '../stores/session'
import { dateTime, errorText, query } from '../lib/display'
import { formatMoney } from '../lib/decimal-display'

const props = defineProps({ submission: { type: Object, default: null }, requestId: { type: String, default: '' } })
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
const message = ref('')
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

/** 현재 필터로 소명 목록을 조회하며 다음 페이지는 적용한 필터를 유지한다.
 * Args: more 다음 페이지 여부. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: GET·목록 갱신·이전 목록 조회 취소; 서버의 필수 소명을 임의 제외하지 않는다.
 */
async function load(more = false) {
  if (more && (loading.value || !cursor.value)) return
  listController.abort(); listController = new AbortController()
  const current = ++generation
  if (!more) { appliedFilters = { ...filters }; items.value = []; cursor.value = null }
  loading.value = true; error.value = ''
  try {
    const page = await session.request(`/api/v1/review-items${query({ ...appliedFilters, submissionId: props.submission?.id, requestId: props.requestId || null, limit: 50, cursor: more ? cursor.value : null })}`, { signal: listController.signal })
    if (current !== generation) return
    items.value = more ? [...items.value, ...page.items] : page.items; cursor.value = page.nextCursor
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
    evidenceReason.value = item.changeDetected ? 'CHANGE' : 'GAP'
    evidenceBasis.value = item.comparisons?.[0]?.basis || 'PLAN'
    detailLoading.value = false
    await nextTick()
    if (current !== detailGeneration) return
    detailPanel.value?.focus({ preventScroll: true })
    detailPanel.value?.scrollIntoView({ behavior: 'auto', block: 'start' })
  } catch (cause) { if (current === detailGeneration && cause.name !== 'AbortError') detailError.value = errorText(cause) }
  finally { if (current === detailGeneration) detailLoading.value = false }
}

/** 소유 조직의 초안 답변 또는 제출 후 새 보완 회차만 편집할 수 있다.
 * Args: 없음. Returns: 답변 가능 여부. Raises: 없음. Side Effects: 없음.
 */
const canAnswer = computed(function allowsAnswer() {
  if (session.actor?.role !== 'ORG_MANAGER' || !detail.value || detail.value.explanationState === 'ACCEPTED') return false
  return selectedSubmission.value?.status === 'DRAFT' || (selectedSubmission.value?.status === 'PENDING' && detail.value.explanationState === 'UNANSWERED')
})

/** 기획자는 제출된 현재 답변의 수용 또는 보완 요청만 할 수 있다.
 * Args: 없음. Returns: 검토 가능 여부. Raises: 없음. Side Effects: 없음.
 */
const canDecide = computed(function allowsDecision() {
  return session.actor?.role === 'PLANNER' && selectedSubmission.value?.status === 'PENDING' && detail.value?.explanationState === 'IN_REVIEW'
})

/** 명시한 답변 저장·수용·보완 요청을 현재 회차와 토큰으로 전송한다.
 * Args: action ANSWER/ACCEPT/REQUEST_CHANGES. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: PUT/POST·응답 갱신·changed 이벤트; 통신 재시도는 같은 본문·키를 유지한다.
 */
async function save(action) {
  if (busy.value || !detail.value || (action === 'ANSWER' ? !canAnswer.value : !canDecide.value)) return
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
function stateLabel(value) { return ({ UNANSWERED: '답변 필요', ANSWERED: '답변 저장', IN_REVIEW: '검토 대기', ACCEPTED: '수용 완료' })[value] || value }

/** 조회·명령과 이전 선택의 응답을 중단한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소·세대 무효화.
 */
function dispose() { generation++; detailGeneration++; evidenceGeneration++; sourceGeneration++; listController.abort(); detailController.abort(); lifetime.abort() }
onMounted(load)
onBeforeUnmount(dispose)
</script>

<template>
  <section class="panel reviews-panel" aria-labelledby="reviews-title">
    <div class="section-heading"><div><h2 id="reviews-title">수정 · 손익 Gap 소명</h2><p class="muted">금액 변경과 손익 Gap은 별도로 확인합니다. 필수 소명을 작성해 제출하고, 기획팀의 수용 후 제출본을 승인할 수 있습니다.</p></div><button class="button" :disabled="loading || busy" @click="load(false)">새로고침</button></div>
    <form class="filters" @submit.prevent="load(false)"><label>사유<select v-model="filters.reason"><option value="">전체</option><option value="CHANGE">금액·원천 변경</option><option value="GAP">손익 Gap</option><option value="INHERITED">이전 소명 이어받기</option></select></label><label>상태<select v-model="filters.explanationState"><option value="">전체</option><option value="UNANSWERED">답변 필요</option><option value="ANSWERED">답변 저장</option><option value="IN_REVIEW">검토 대기</option><option value="ACCEPTED">수용 완료</option></select></label><label>구분<select v-model="filters.scenario"><option value="">전체</option><option value="PLAN">계획</option><option value="ACTUAL">실적</option><option value="ESTIMATE">추정</option></select></label><button class="button" :disabled="loading || busy">조회</button></form>
    <div v-if="error" class="alert error" role="alert">{{ error }}</div><p v-if="loading && !items.length" class="empty" role="status">소명을 불러오는 중…</p><p v-else-if="!items.length && !error" class="empty compact">선택한 조건의 소명 항목이 없습니다.</p>
    <div v-if="items.length" class="table-scroll"><table><caption class="visually-hidden">필수 소명과 진행 상태</caption><thead><tr><th>항목</th><th>월 · 구분</th><th>사유</th><th>상태</th><th class="number">현재 금액 (원)</th><th>확인</th></tr></thead><tbody><tr v-for="item in items" :key="item.id"><th scope="row">{{ item.reportItem?.name || item.reportItemName }}<small>{{ item.required ? '필수' : '참고' }} · 조직 {{ item.organizationId }}</small></th><td>{{ item.year }}.{{ String(item.month).padStart(2, '0') }} · {{ item.scenario }}</td><td>{{ item.changeDetected ? '변경 ' : '' }}{{ item.gapExceeded ? '손익 Gap' : '' }}{{ !item.changeDetected && !item.gapExceeded ? '이어받기' : '' }}</td><td>{{ stateLabel(item.explanationState) }}</td><td class="number">{{ item.amountPresent ? formatMoney(item.currentAmount) : '항목 제거' }}</td><td><button class="text-button" :disabled="busy" @click="select(item.id)">소명 · 근거</button></td></tr></tbody></table></div>
    <button v-if="cursor" class="button load-more" :disabled="loading || busy" @click="load(true)">소명 더 보기</button>
    <p v-if="detailLoading" class="empty" role="status">소명 상세를 불러오는 중…</p><div v-if="detailError" class="alert error" role="alert">{{ detailError }}</div><p v-if="message" class="notice" role="status">{{ message }}</p>
    <section v-if="detail" ref="detailPanel" class="review-detail" tabindex="-1" :aria-labelledby="`review-detail-title-${detail.id}`"><div class="section-heading"><h3 :id="`review-detail-title-${detail.id}`">{{ detail.reportItem?.name || detail.reportItemName }} · {{ detail.month }}월 {{ detail.scenario }}</h3><div class="button-row"><span class="badge">{{ stateLabel(detail.explanationState) }} · {{ detail.currentRound }}회차</span><button class="text-button" :disabled="busy || detailLoading" @click="select(detail.id)">상세 새로고침</button></div></div>
      <div v-if="detail.comparisons?.length" class="table-scroll"><table><caption class="visually-hidden">확정 계획 및 이전 제출 대비 손익 Gap</caption><thead><tr><th>비교 기준</th><th class="number">기준 손익</th><th class="number">현재 손익</th><th class="number">차액</th><th>차이율</th><th>초과</th></tr></thead><tbody><tr v-for="comparison in detail.comparisons" :key="comparison.basis"><th scope="row">{{ comparison.basis === 'PLAN' ? '확정 계획' : '이전 제출' }} · {{ comparison.baselineScenario }}</th><td class="number">{{ formatMoney(comparison.baselineAmount) }}</td><td class="number">{{ formatMoney(comparison.currentAmount) }}</td><td class="number">{{ formatMoney(comparison.gapAmount) }}</td><td>{{ comparison.gapRate === null ? '기준 0 · 비율 없음' : `${formatMoney(comparison.gapRate)}%` }}</td><td>{{ comparison.exceeded ? '초과' : '미초과' }}<small>{{ formatMoney(comparison.threshold?.percent) }}% · {{ formatMoney(comparison.threshold?.amount) }}원</small></td></tr></tbody></table></div>
      <ol class="explanation-rounds"><li v-for="round in detail.explanations" :key="round.id"><strong>{{ round.round }}회차 · {{ ({ PENDING: '검토 전', ACCEPTED: '수용', REJECTED: '보완 요청' })[round.verdict] || round.verdict }}</strong><p v-if="round.requestNote">보완 요청: {{ round.requestNote }}</p><p>{{ round.answer || '아직 답변이 없습니다.' }}</p><small v-if="round.answeredAt">{{ round.answeredBy?.name }} · {{ dateTime(round.answeredAt) }}</small><p v-if="round.reviewNote">검토 의견: {{ round.reviewNote }}</p><small v-if="round.reviewedAt">{{ round.reviewedBy?.name }} · {{ dateTime(round.reviewedAt) }}</small><small v-if="round.inheritedFrom">이전 수용 소명 · {{ round.inheritedFrom.versionLabel }}</small></li></ol>
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
.review-detail { scroll-margin-top:24px; margin-top:24px; padding-top:24px; border-top:1px solid var(--line); }.review-detail form,.review-actions { margin:20px 0; }.explanation-rounds { padding:0; margin:20px 0; list-style:none; display:grid; gap:12px; }.explanation-rounds li { padding:15px; border:1px solid var(--line); border-radius:8px; background:#faf9f6; font-size:13px; }.explanation-rounds p { white-space:pre-wrap; margin-top:8px; line-height:1.7; }.explanation-rounds small { display:block; margin-top:6px; color:var(--muted); }.evidence-controls { display:flex; align-items:flex-end; flex-wrap:wrap; gap:12px; margin-top:24px; }.evidence-controls h3 { width:100%; }.evidence-controls label { min-width:140px; margin:0; }.evidence-controls select { margin-bottom:0; }.semantic-value { display:block; font-size:11px; white-space:normal; }.source-evidence { margin-top:20px; padding:16px; background:#faf9f6; border-radius:8px; font-size:12px; }.source-evidence>div { margin-bottom:12px; }.source-evidence dt { color:var(--muted); }.source-evidence dd { margin:6px 0 0; overflow-wrap:anywhere; white-space:pre-wrap; }.reviews-panel th small { display:block; font-weight:400; color:var(--muted); margin-top:5px; }
</style>
