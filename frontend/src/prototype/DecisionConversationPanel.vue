<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import PButton from 'primevue/button'
import PSelect from 'primevue/select'
import PTextarea from 'primevue/textarea'
import PTag from 'primevue/tag'
import PMessage from 'primevue/message'
import PPanel from 'primevue/panel'
import PDialog from 'primevue/dialog'
import ReportDataTable from '../components/ReportDataTable.vue'
import DecisionReportToolResults from './DecisionReportToolResults.vue'
import { reportToolResults } from './decision-report-tools.js'
import { analysisBrief, answerActions, answerCitations, askAssistant, assistantCoverageLabel, assistantExecutionSummary, assistantIncompleteReason, assistantPayload, assistantStatusLabel, canStartGoalPreview, conversationKey, goalPreviewQuestion, observationExplanations, observationForExecution, observationTables, reviewDraftSource, successfulObservations } from './decision-conversation-api.js'
import { workDateText } from './decision-workflow-api.js'
const props = defineProps({ api: { type: Function, required: true }, analysis: Object, masterId: String, baselines: Array, disabled: Boolean })
const emit = defineEmits(['select-master', 'navigate', 'request', 'execution'])
const question = ref(''), busy = ref(false), error = ref(''), turns = ref([]), source = ref(null), sourceVisible = ref(false)
const conversationRoot = ref(null)
const resultOrder = ref('SIGNED_DESC')
const resultOrderOptions = [{ label: '계획 초과액 큰 순', value: 'SIGNED_DESC' }, { label: '계획 대비 차이 절댓값 큰 순', value: 'ABS_DESC' }]
const sessions = new Map()
let generation = 0, pendingController = null, activeKey = ''
/** 각 문답의 실제 질문·조회 근거에 연결된 설명만 계산한다.
 * Args: 없음. Returns: 실행 ID별 분석 Map. Raises: 없음. Side Effects: 없음.
 */
function briefByTurn() { const result = new Map(); for (const turn of turns.value) if (turn.response) result.set(turn.id, analysisBrief(turn.response, turn.question)); return result }
const briefs = computed(briefByTurn)
/** 같은 문답의 성공한 보고서·소명 도구 결과를 검증해 표시한다.
 * Args: turn 현재 문답. Returns: 검증한 자료. Raises: 없음. Side Effects: 새 조회·파일 생성 없음.
 */
function reportResults(turn) { return reportToolResults(successfulObservations(turn.response), turn.response?.context) }
/** 사실·해석·가설 유형을 의미에 맞는 태그 색으로 표시한다.
 * Args: kind 서버 설명 유형. Returns: PrimeVue 태그 색. Raises: 없음. Side Effects: 없음.
 */
function findingSeverity(kind) { return kind === 'FACT' ? 'secondary' : kind === 'INTERPRETATION' ? 'info' : 'warn' }
/** 현재 대화가 고정한 마스터 ID를 반환한다.
 * Args: 없음. Returns: 기준 ID. Raises: 없음. Side Effects: 없음.
 */
function currentMaster() { return props.analysis?.snapshot.masterId || props.masterId || '' }
const master = computed(currentMaster)
/** 기준과 분석 버전으로 이력을 분리할 키를 만든다.
 * Args: 없음. Returns: 대화 키. Raises: 없음. Side Effects: 없음.
 */
function scopeKey() { return conversationKey(master.value, props.analysis) }
/** 선택한 기준을 사용자가 알아볼 수 있게 표시한다.
 * Args: 없음. Returns: 기준 이름. Raises: 없음. Side Effects: 없음.
 */
function basisLabel() {
  if (props.analysis) return `${props.analysis.snapshot.masterLabel} · 분석 v${props.analysis.revision}`
  for (const item of props.baselines || []) if (item.masterId === master.value) return item.label
  return '확정 마스터 선택 필요'
}
const basis = computed(basisLabel)
/** 기준 전환 시 이전 요청을 중단하고 해당 기준의 로컬 이력을 복원한다.
 * Args: key 다음 기준 키. Returns: 없음. Raises: 없음.
 * Side Effects: 요청 중단·이력 전환. 다른 기준의 늦은 응답을 차단한다.
 */
function changeScope(key) {
  generation++; pendingController?.abort(); pendingController = null
  for (const turn of turns.value) if (turn.pending) { turn.pending = false; turn.error = '분석 기준이 변경되어 조회를 중단했습니다.' }
  if (activeKey) sessions.set(activeKey, turns.value)
  activeKey = key; turns.value = sessions.get(key) || []; busy.value = false; error.value = ''; sourceVisible.value = false; source.value = null
  sessions.delete(key); sessions.set(key, turns.value)
  while (sessions.size > 4) sessions.delete(sessions.keys().next().value)
  publishExecution(turns.value.at(-1))
}
/** 현재 문답의 실제 조회 상태를 같은 화면의 보고서에 전달한다.
 * Args: turn 실행 정보가 있는 문답 또는 undefined. Returns: 없음. Raises: 없음.
 * Side Effects: execution 이벤트만 발생하며 원문·결과를 저장하거나 새로 조회하지 않는다.
 * @param {object|undefined} turn
 * @returns {void}
 */
function publishExecution(turn) {
  if (!turn?.execution) { emit('execution', { phase: 'IDLE' }); return }
  emit('execution', { ...turn.execution, executionId: turn.id, phase: turn.pending ? 'PENDING' : turn.response ? 'RESULT' : 'ERROR', response: turn.response, error: turn.error })
}
/** 질문을 보내고 실제 도구 실행 결과를 해당 문답에 연결한다.
 * Args: 없음. Returns: 요청 시작 여부 Promise<boolean>. Raises: 없음; 오류를 화면에 표시한다.
 * Side Effects: 조회 Agent 요청·문답 메모리 변경. 업무를 저장하지 않는다.
 */
async function submitQuestion() { return sendQuestion(question.value) }
/** 일반 질문 또는 확인한 목표 비교를 같은 대화 실행으로 보낸다.
 * Args: message 전송할 질문, options 질문 출처와 확인한 목표 입력. Returns: 요청 시작 여부 Promise<boolean>.
 * Raises: 없음; 오류를 화면에 표시한다. Side Effects: 읽기 요청·문답 메모리 변경, 업무 저장 없음.
 * @param {string} message
 * @param {{source:string,goalInput?:object}} options
 * @returns {Promise<boolean>}
 */
async function sendQuestion(message, options = { source: 'CHAT' }) {
  if (busy.value || props.disabled) return false
  let payload
  try { payload = assistantPayload(master.value, props.analysis, message, turns.value) }
  catch (cause) { error.value = cause.message; return false }
  const execution = { payload, source: options.source === 'FORM' ? 'FORM' : 'CHAT', goalInput: options.goalInput ? { ...options.goalInput } : null }
  const current = ++generation, turn = reactive({ id: crypto.randomUUID(), question: payload.message, response: null, pending: true, error: '', execution })
  const requestController = new AbortController(); pendingController = requestController
  turns.value.push(turn); if (turns.value.length > 8) turns.value.shift()
  busy.value = true; error.value = ''; question.value = ''
  publishExecution(turn)
  try { const response = await askAssistant(props.api, payload, requestController.signal); if (current === generation) turn.response = response }
  catch (cause) { if (current === generation) turn.error = cause.name === 'AbortError' ? '질문 조회를 중단했습니다.' : cause.message }
  finally { if (current === generation) { turn.pending = false; busy.value = false; pendingController = null; sessions.set(activeKey, turns.value); publishExecution(turn) } }
  return true
}
/** 목표 폼에서 요청한 비교를 질문 초안을 보존하며 대화로 실행한다.
 * Args: input 확정 마스터·연도·목표 억원. Returns: 요청 시작 여부 Promise<boolean>.
 * Raises: 없음; 입력·대화 상태 오류 표시. Side Effects: 읽기 요청·보고서 실행 상태 전달. 초안 충돌 때만 대화로 이동하며 목표·분석 저장 없음.
 */
async function requestGoalPreview(input) {
  let message
  try { message = goalPreviewQuestion(input) }
  catch (cause) { error.value = cause.message; await revealConversation(false); return false }
  if (!canStartGoalPreview(question.value, busy.value, props.disabled, input.masterId, master.value)) {
    error.value = question.value.trim() ? '작성 중인 질문을 보존했습니다. 먼저 질문을 보내거나 입력을 비운 뒤 목표 비교를 다시 눌러 주세요.' : busy.value ? '현재 질문을 조회 중입니다. 완료 후 목표 비교를 다시 눌러 주세요.' : '목표 폼과 대화의 기준을 확인하고 편집 중인 내용을 먼저 정리해 주세요.'
    await revealConversation(false); return false
  }
  return sendQuestion(message, { source: 'FORM', goalInput: input })
}
/** 출처 또는 기존 초안을 확인할 대화 위치로 이동한다.
 * Args: latest 가장 최근 문답으로 이동할지 여부. Returns: Promise<void>.
 * Raises: 없음. Side Effects: 렌더 후 스크롤·기존 질문 초점 이동.
 */
async function revealConversation(latest) {
  await nextTick()
  const target = latest ? conversationRoot.value?.querySelector('.conversation-turn:last-child') : conversationRoot.value
  target?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  if (!latest) conversationRoot.value?.querySelector('#decision-question')?.focus({ preventScroll: true })
}
/** 진행 중인 읽기 요청을 중단한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 요청 중단 신호 전달.
 */
function cancelQuestion() { pendingController?.abort() }
/** 분석을 만들기 전 대화용 마스터 선택을 부모와 공유한다.
 * Args: id 마스터 ID. Returns: 없음. Raises: 없음. Side Effects: 선택 이벤트 발생.
 */
function selectMaster(id) { emit('select-master', id) }
/** 검증된 관찰의 읽기 쉬운 출처 상세를 연다.
 * Args: observation 실제 도구 관찰. Returns: 없음. Raises: 없음. Side Effects: 출처 창 표시.
 */
function openSource(observation) { source.value = observation; sourceVisible.value = true }
/** 보고서가 참조한 정확한 실행의 실제 관찰만 기존 원문 창에서 연다.
 * Args: id 관찰 ID, executionId 화면 문답 ID. Returns: 관찰을 열었는지 여부. Raises: 없음.
 * Side Effects: 현재 기준·동일 문답의 원문 창 표시. 실행마다 재사용되는 관찰 ID만으로 원문을 찾지 않는다.
 * @param {string} id
 * @param {string} executionId
 * @returns {boolean}
 */
function openObservation(id, executionId) {
  const observation = observationForExecution(turns.value, executionId, id)
  if (!observation) return false
  openSource(observation)
  return true
}
/** 서버가 허용한 탐색 동작을 기존 업무 탭으로 넘긴다.
 * Args: action 검증한 nextAction. Returns: 없음. Raises: 없음. Side Effects: 탐색 이벤트.
 */
function navigate(action) { if (props.analysis) emit('navigate', action.tab) }
/** 현재 분석과 연결되는 검증 요청 초안만 모은다.
 * Args: response 답변 응답. Returns: 요청 원천 배열. Raises: 없음. Side Effects: 없음.
 */
function draftRequests(response) { const drafts = []; for (const observation of response.observations || []) { const draft = reviewDraftSource(observation, props.analysis); if (draft) drafts.push(draft) } return drafts }
/** 사용자가 선택한 요청 초안을 기존 확인·저장 화면으로 전달한다.
 * Args: draft 검증 원천. Returns: 없음. Raises: 없음. Side Effects: 요청 작성 이벤트; 자동 저장 없음.
 */
function prepareDraft(draft) { if (!props.disabled) emit('request', draft) }
/** 현재 결과에 정렬 가능한 조직별 영업이익 표가 있는지 확인한다.
 * Args: response 현재 응답. Returns: 조직별 결과 유무. Raises: 없음. Side Effects: 없음.
 */
function hasOrganizationResults(response) { for (const observation of successfulObservations(response)) if (['organization_pnl', 'profit_drivers'].includes(observation.tool)) return true; return false }
/** 실제 실행 상태를 표시하고 구체 실패 원인은 서버 안내와 구분한다.
 * Args: response Agent 응답. Returns: 상태 문구. Raises: 없음. Side Effects: 없음.
 */
function statusLabel(response) { return assistantStatusLabel(response) }
/** 완료 여부와 모델 가용성에 맞춰 상태 태그 색상을 정한다.
 * Args: response 응답. Returns: 색상. Raises: 없음. Side Effects: 없음.
 */
function statusSeverity(response) { return response.mode === 'MODEL' && response.status === 'COMPLETED' ? 'success' : response.status === 'FAILED' ? 'danger' : 'warn' }
/** 실제 호출 상태만 사용자에게 표시한다.
 * Args: status 서버 호출 상태. Returns: 표시 문구. Raises: 없음. Side Effects: 없음.
 */
function toolStatus(status) { return { SUCCESS: '조회 완료', ERROR: '조회 실패', REUSED: '기존 조회 재사용' }[status] || '상태 확인 필요' }
/** 큰 원문은 명시한 범위까지만 접힌 상세에 표시한다.
 * Args: observation 관찰. Returns: JSON 텍스트. Raises: 없음. Side Effects: 없음.
 */
function rawDetails(observation) { const text = JSON.stringify(observation, null, 2); return text.length > 24000 ? `${text.slice(0, 24000)}\n… 표시 한도 초과로 상세 일부 생략` : text }
/** 해제된 화면으로 답변이 돌아오지 않도록 요청을 중단한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 요청 중단·응답 세대 무효화.
 */
function dispose() { generation++; pendingController?.abort() }
watch(scopeKey, changeScope, { immediate: true, flush: 'sync' })
onBeforeUnmount(dispose)
defineExpose({ requestGoalPreview, openObservation })
</script>
<template><section ref="conversationRoot" class="decision-conversation" aria-label="대화형 손익 분석"><header><div><p class="conversation-eyebrow">ASK P&amp;L AGENT</p><h2>궁금한 점에서 분석을 시작하세요</h2><p>질문에 필요한 조회와 보고서 준비를 선택하고, 결과를 확인해 다음 근거를 이어 찾습니다.</p></div><PTag value="확정 기준 · 조회와 보고서" severity="info" /></header><div class="conversation-basis"><PSelect v-if="!analysis" :model-value="masterId" :options="baselines || []" option-label="label" option-value="masterId" placeholder="대화 기준 확정 마스터 선택" aria-label="대화 기준 마스터" @update:model-value="selectMaster" /><span v-else>{{ basis }}</span><small>{{ analysis ? '저장된 후보·시나리오와 연결한 질문' : '목표 분석을 만들기 전에도 조회할 수 있습니다.' }}</small></div><PMessage v-if="!master" severity="info" :closable="false">먼저 확정 마스터를 선택해 주세요. 없는 기간은 목표·분석 기준에서 연도를 변경해 확인할 수 있습니다.</PMessage><PMessage v-if="error" severity="error" :closable="false">{{ error }}</PMessage><div class="conversation-turns" aria-live="polite"><article v-for="turn in turns" :key="turn.id" class="conversation-turn"><div class="user-question"><strong>질문</strong><p>{{ turn.question }}</p></div><div class="agent-answer"><p v-if="turn.pending" class="pending-answer" role="status"><i class="pi pi-spin pi-spinner" aria-hidden="true" />조회와 답변을 기다리고 있습니다. 완료되면 실제 호출 내역을 표시합니다.</p><PMessage v-if="turn.error" severity="error" :closable="false">{{ turn.error }}</PMessage><template v-if="turn.response"><div class="answer-status"><PTag :value="statusLabel(turn.response)" :severity="statusSeverity(turn.response)" /><span>{{ turn.response.context?.masterLabel || basis }}<template v-if="turn.response.context?.revision"> · 분석 v{{ turn.response.context.revision }}</template></span></div><p class="results-caption">{{ assistantExecutionSummary(turn.response) }}</p><p v-if="turn.response.answer && !briefs.get(turn.id)" class="answer-text">{{ turn.response.answer }}</p><PMessage v-if="briefs.get(turn.id) && assistantIncompleteReason(turn.response) && assistantIncompleteReason(turn.response) !== briefs.get(turn.id).summary" severity="warn" :closable="false">{{ assistantIncompleteReason(turn.response) }}</PMessage>
<section v-if="briefs.get(turn.id)" class="analysis-brief" aria-label="질문에 대한 근거 분석">
  <p class="answer-text">{{ briefs.get(turn.id).summary }}</p>
  <div class="analysis-coverage"><strong>{{ assistantCoverageLabel(turn.response, briefs.get(turn.id)) }}</strong><span>사실과 해석·가설을 구분했습니다.</span></div>
  <ol class="analysis-findings"><li v-for="finding in briefs.get(turn.id).findings" :key="finding.id">
    <PTag :value="finding.kindLabel" :severity="findingSeverity(finding.kind)" /><p>{{ finding.text }}</p>
    <p v-if="finding.uncertainty" class="analysis-uncertainty"><strong>판단의 한계</strong> {{ finding.uncertainty }}</p>
    <details class="finding-citations"><summary>연결된 근거 {{ finding.citations.length }}건</summary><PButton v-for="(citation, index) in finding.citations" :key="`${finding.id}-${index}`" text severity="secondary" class="citation-button" @click="openObservation(citation.observationId, turn.id)"><span class="citation-number">{{ index + 1 }}</span><span>{{ citation.label }} · {{ citation.field }}<strong>{{ citation.displayQuote }}</strong></span><i class="pi pi-external-link" aria-hidden="true" /></PButton></details>
  </li></ol>
  <div class="analysis-requirements"><h3>답변한 항목과 남은 확인</h3><ol><li v-for="requirement in briefs.get(turn.id).requirements" :key="requirement.id"><div><strong>{{ requirement.question }}</strong><PTag :value="requirement.statusLabel" :severity="requirement.status === 'ANSWERED' ? 'secondary' : 'warn'" /></div><template v-if="requirement.status !== 'ANSWERED'"><p>{{ requirement.missingInformation }}</p><p class="analysis-followup"><i class="pi pi-question-circle" aria-hidden="true" />{{ requirement.followUpQuestion }}</p></template></li></ol></div>
</section>
<DecisionReportToolResults :result="reportResults(turn)" :api="api" :scope-key="scopeKey() + ':' + turn.id" @source="openObservation($event, turn.id)" />
<details v-if="successfulObservations(turn.response).length" :open="!briefs.get(turn.id)" class="retrieved-results" aria-label="조회된 결과"><summary class="retrieved-toggle">실제 조회 자료 {{ successfulObservations(turn.response).length }}건 보기</summary>
  <div class="retrieved-heading"><h3>조회된 결과</h3><label v-if="hasOrganizationResults(turn.response)">조직 표 정렬<PSelect v-model="resultOrder" :options="resultOrderOptions" option-label="label" option-value="value" aria-label="조직 표 정렬" /></label></div>
  <PMessage v-if="turn.response.status !== 'COMPLETED' || turn.response.mode === 'UNAVAILABLE'" severity="warn" :closable="false">답변은 완료되지 않았지만 조회된 자료는 아래에서 확인할 수 있습니다. 이번 실행에서 조회에 성공한 결과만 표시합니다.</PMessage>
  <p v-else class="results-caption">이번 실행에서 조회한 원문 자료입니다. 숫자와 비교 기준을 함께 확인하세요.</p>
  <article v-for="observation in successfulObservations(turn.response)" :key="observation.id" class="retrieved-card">
    <header><h4>{{ observation.label }}</h4><PButton label="원문·기준 확인" icon="pi pi-external-link" text size="small" @click="openSource(observation)" /></header>
    <p class="results-basis">{{ observation.basis.year }}년 {{ observation.basis.baseMonth }}월 · 조회 {{ workDateText(observation.basis.capturedAt) }}</p>
    <p v-if="observation.basis.comparisonBasis" class="results-basis">비교 기준: {{ observation.basis.comparisonBasis }}</p>
    <PMessage v-if="observation.tool === 'goal_preview'" severity="info" :closable="false">DB에 저장하지 않은 목표 검토입니다. 현재 연간 전망과 비교한 값이며, 실제 실적 달성이나 개선 과제 승인을 뜻하지 않습니다.</PMessage>
    <div v-if="observationExplanations(observation).length" class="explanation-results"><article v-for="explanation in observationExplanations(observation)" :key="explanation.id"><h5>{{ explanation.label }}</h5><p>{{ explanation.text }}</p><small v-if="explanation.refId">근거 {{ explanation.refId }}<template v-if="explanation.reviewedAt"> · 검토 {{ workDateText(explanation.reviewedAt) }}</template></small></article><small>조회된 소명 {{ observation.data.items.length }}건 중 최대 20건을 표시합니다. 소명 승인은 과거 설명의 검토 기록입니다.</small></div>
    <section v-for="(table,index) in observationTables(observation, resultOrder)" :key="`result-${index}`" class="result-table"><h5>{{ table.label }}</h5><p v-if="table.orderNote" class="table-order-note">{{ table.orderNote }}</p><ReportDataTable :data="{ table: table.table }" /><small v-if="table.total > 20">조회된 {{ table.total }}건 중 20건 표시 · 나머지 원문은 원문·기준 확인에서 조회</small></section>
    <p v-if="!observationTables(observation, resultOrder).length && !observationExplanations(observation).length" class="results-caption">표시할 자료가 없습니다. 원문·기준 확인에서 조회 내용을 볼 수 있습니다.</p>
    <ul v-if="observation.warnings?.length" class="result-warnings"><li v-for="warning in observation.warnings" :key="warning">{{ warning }}</li></ul>
  </article>
  <small class="results-limit">표는 관찰마다 최대 4개·각 20행·8열을 표시합니다. 생략된 필드는 원문·기준 확인에 있습니다.</small>
</details><div v-if="!briefs.get(turn.id) && answerCitations(turn.response).length" class="answer-citations"><strong>답변의 근거</strong><PButton v-for="(citation, index) in answerCitations(turn.response)" :key="`${citation.observationId}-${citation.path}-${index}`" text severity="secondary" class="citation-button" @click="openSource(citation.observation)"><span class="citation-number">{{ index + 1 }}</span><span>{{ citation.label }} · {{ citation.field }}<strong>{{ citation.displayQuote }}</strong></span><i class="pi pi-external-link" aria-hidden="true" /></PButton></div><PPanel :header="`사용한 조회 ${turn.response.toolCalls?.length || 0}건`" toggleable collapsed class="tool-trace"><ol><li v-for="(call, index) in turn.response.toolCalls || []" :key="call.id"><span>{{ index + 1 }}</span><strong>{{ call.label }}</strong><PTag :value="toolStatus(call.status)" :severity="call.status === 'ERROR' ? 'danger' : 'secondary'" /></li></ol><p v-if="!turn.response.toolCalls?.length">실행된 조회 도구가 없습니다.</p></PPanel><div v-if="turn.response.observations?.length" class="observation-links"><span>조회 출처</span><PButton v-for="observation in turn.response.observations" :key="observation.id" :label="observation.label" icon="pi pi-file" text size="small" @click="openSource(observation)" /></div><div v-if="draftRequests(turn.response).length" class="answer-actions"><div v-for="draft in draftRequests(turn.response)" :key="draft.kind + draft.id"><strong>{{ draft.title }}</strong><PButton label="조직 검토 요청 초안 확인" icon="pi pi-file-edit" outlined size="small" :disabled="disabled" @click="prepareDraft(draft)" /></div><small>조회한 저장 근거로 만든 초안입니다. 내용과 기한을 확인한 뒤 직접 저장·전달합니다.</small></div><div v-if="answerActions(turn.response.nextActions).length" class="answer-actions"><PButton v-for="action in answerActions(turn.response.nextActions)" :key="action.key" :label="action.label" outlined size="small" :disabled="!analysis || disabled" @click="navigate(action)" /><small v-if="!analysis">목표 분석을 생성하거나 불러오면 다음 업무로 연결할 수 있습니다.</small></div><PMessage v-for="limitation in turn.response.limitations || []" :key="limitation" severity="info" :closable="false">{{ limitation }}</PMessage></template></div></article></div><form class="conversation-composer" @submit.prevent="submitQuestion"><label for="decision-question">손익 데이터에 질문하기</label><PTextarea id="decision-question" v-model="question" rows="2" maxlength="2000" placeholder="어느 조직의 계획 대비 이익 차이가 큰지 보고, 승인된 소명도 함께 확인해 줘." :disabled="busy || disabled" /><div><small>대화는 이 화면에서 최근 4개 기준·기준별 8문답만 보관하고 오래된 내용은 제거됩니다. 후속 질문에는 최근 4회 질문·실행 상태·분석 요약을 문맥으로 전달하며, 수치는 현재 조회에서 다시 확인합니다.</small><PButton v-if="busy" label="조회 중단" text severity="secondary" @click="cancelQuestion" /><PButton type="submit" label="질문 보내기" icon="pi pi-arrow-up" :loading="busy" :disabled="busy || disabled || !master || !question.trim()" /></div></form><PDialog v-model:visible="sourceVisible" modal :header="source?.label || '조회 출처'" :style="{ width: '55rem', maxWidth: '96vw' }"><template v-if="source"><div class="source-basis"><PTag :value="source.status === 'SUCCESS' ? '실제 도구 조회' : '조회 실패'" :severity="source.status === 'SUCCESS' ? 'info' : 'danger'" /><p>마스터 {{ source.basis?.masterId }} · {{ source.basis?.year }}년 {{ source.basis?.baseMonth }}월 · 조회 {{ workDateText(source.basis?.capturedAt) }}</p><p v-if="source.basis?.comparisonBasis">비교 기준: {{ source.basis.comparisonBasis }}</p></div><p class="source-limit">요약 표는 최대 4개, 표마다 20행·8열을 표시합니다. 생략된 필드와 원문은 조회 상세 구조에서 확인할 수 있습니다.</p><div v-if="observationExplanations(source).length" class="explanation-results"><article v-for="explanation in observationExplanations(source)" :key="explanation.id"><h5>{{ explanation.label }}</h5><p>{{ explanation.text }}</p><small>{{ explanation.refId }}</small></article></div><section v-for="(table,index) in observationTables(source, resultOrder)" :key="index" class="observation-table"><h3>{{ table.label }}</h3><p v-if="table.orderNote" class="table-order-note">{{ table.orderNote }}</p><ReportDataTable :data="{ table: table.table }" /><small v-if="table.total > 20">전체 {{ table.total }}건 중 20건 표시 · 전체 구조는 조회 상세에서 확인</small></section><PMessage v-for="warning in source.warnings || []" :key="warning" severity="warn" :closable="false">{{ warning }}</PMessage><PPanel header="조회 상세 구조" toggleable collapsed><pre class="observation-json">{{ rawDetails(source) }}</pre></PPanel></template></PDialog></section></template>
<style scoped>.decision-conversation{border:1px solid #cbddeb;background:white;border-radius:10px;padding:24px;margin:0 0 24px;color:#193047}.decision-conversation>header{display:flex;justify-content:space-between;align-items:start;gap:18px}.conversation-eyebrow{font-size:10px;font-weight:750;letter-spacing:.17em;color:#28619c;margin:0 0 9px}.decision-conversation h2{font-size:21px;letter-spacing:-.035em;margin:0 0 9px}.decision-conversation header>div>p:last-child{font-size:12px;color:#64768c;line-height:1.7;margin:0}.conversation-basis{display:flex;gap:14px;align-items:center;flex-wrap:wrap;margin:18px 0;padding-bottom:18px;border-bottom:1px solid #dbe3eb}.conversation-basis>.p-select{min-width:270px;max-width:100%}.conversation-basis>span{font-size:12px;font-weight:650}.conversation-basis>small{font-size:11px;color:#64768c}.conversation-turn{margin:0 0 22px}.user-question{display:flex;gap:15px;padding:15px 18px;background:#f1f6fb;border-radius:6px}.user-question>strong{font-size:11px;color:#28619c;white-space:nowrap;padding-top:2px}.user-question>p,.answer-text{margin:0;font-size:14px;line-height:1.85;white-space:pre-line;overflow-wrap:anywhere}.agent-answer{padding:17px 8px 0}.answer-status{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-bottom:12px}.answer-status>span:last-child{font-size:11px;color:#64768c}.answer-citations{display:grid;gap:6px;margin:16px 0}.answer-citations>strong{font-size:11px;color:#64768c}.citation-button{display:flex!important;justify-content:start!important;gap:10px;text-align:left!important;border:1px solid #e1e8ef!important}.citation-number{display:grid;place-items:center;width:22px;height:22px;border-radius:4px;background:#edf3f9;color:#28619c;font-size:10px;flex-shrink:0}.citation-button>span:nth-child(2){font-size:11px;color:#64768c;min-width:0;flex:1}.citation-button strong{display:block;margin-top:4px;font-size:12px;color:#193047;overflow-wrap:anywhere}.citation-button>i{font-size:10px}.retrieved-results{margin:20px 0}.retrieved-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.retrieved-heading h3{font-size:16px;margin:0}.retrieved-heading label{display:flex;align-items:center;gap:8px;font-size:11px;color:#64768c}.retrieved-heading .p-select{max-width:100%;font-size:11px}.retrieved-card{border:1px solid #dbe3eb;border-radius:8px;padding:17px;margin-top:14px;min-width:0}.retrieved-card>header{display:flex;align-items:center;justify-content:space-between;gap:10px}.retrieved-card h4{font-size:14px;margin:0}.results-caption,.results-basis,.table-order-note{font-size:11px;line-height:1.7;color:#64768c;margin:8px 0}.result-table{min-width:0;margin-top:15px}.result-table h5,.explanation-results h5{font-size:12px;margin:0 0 8px}.result-table :deep(.p-datatable-table-container){max-width:100%;overflow:auto}.result-table :deep(td),.result-table :deep(th){font-size:11px;line-height:1.6}.results-limit,.result-table>small,.explanation-results small{display:block;font-size:10px;line-height:1.7;color:#64768c}.explanation-results{display:grid;gap:14px;margin-top:15px}.explanation-results article{background:#f6f9fc;padding:14px;border-left:3px solid #8eaccb;border-radius:4px}.explanation-results p{white-space:pre-wrap;overflow-wrap:anywhere;font-size:13px;line-height:1.9;margin:0 0 9px}.result-warnings{padding-left:17px;margin:12px 0 0;font-size:10px;color:#64768c;line-height:1.8}.tool-trace{margin:16px 0}.tool-trace ol{list-style:none;margin:0;padding:0;display:grid;gap:12px}.tool-trace li{display:flex;align-items:center;gap:10px;font-size:12px}.tool-trace li>span{color:#64768c;font-size:10px}.tool-trace li>strong{flex:1;font-weight:500}.tool-trace p{font-size:12px;color:#64768c}.observation-links{display:flex;align-items:center;gap:7px;flex-wrap:wrap}.observation-links>span{font-size:11px;color:#64768c;margin-right:7px}.answer-actions{display:flex;gap:9px;align-items:center;flex-wrap:wrap;margin:16px 0}.answer-actions small{font-size:11px;color:#64768c}.conversation-composer{margin-top:18px;border-top:1px solid #dbe3eb;padding-top:18px}.conversation-composer>label{display:block;font-size:12px;font-weight:650;margin-bottom:9px}.conversation-composer>.p-textarea{width:100%;font-size:14px;line-height:1.7}.conversation-composer>div{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:10px}.conversation-composer small{font-size:10px;color:#64768c;line-height:1.6;flex:1}.pending-answer{font-size:12px;color:#64768c;line-height:1.7;display:flex;gap:10px}.source-basis{font-size:12px;color:#64768c;line-height:1.7;margin-bottom:20px}.source-limit{font-size:11px;color:#64768c;line-height:1.7}.observation-table h3{font-size:14px;margin:22px 0 8px}.observation-table small{font-size:11px;color:#64768c}.observation-json{white-space:pre-wrap;word-break:break-word;font-size:11px;line-height:1.6;max-height:400px;overflow:auto}.decision-conversation :deep(.p-button){font-size:12px}.decision-conversation :deep(.p-tag){font-size:10px}.decision-conversation :deep(.p-message){margin:12px 0}.decision-conversation :deep(.p-message-text){font-size:12px;line-height:1.8}.decision-conversation :deep(.p-panel-header){font-size:12px}@media(max-width:650px){.decision-conversation{padding:17px}.decision-conversation>header{flex-direction:column}.conversation-basis>.p-select{width:100%;min-width:0}.conversation-composer>div{flex-wrap:wrap}.conversation-composer small{flex-basis:100%}.user-question{padding:12px;gap:10px}.retrieved-card{padding:12px}.retrieved-card>header{align-items:start;flex-wrap:wrap}.retrieved-heading label{width:100%;flex-wrap:wrap}}
.analysis-coverage{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;margin-top:18px;padding-bottom:10px;border-bottom:1px solid #dbe3eb;font-size:11px}.analysis-coverage>span{color:#64768c;font-size:10px}.analysis-findings,.analysis-requirements ol{list-style:none;margin:0;padding:0}.analysis-findings>li{padding:16px 0;border-bottom:1px solid #e5ebf1}.analysis-findings>li>p{font-size:13px;line-height:1.85;white-space:pre-line;overflow-wrap:anywhere;margin:9px 0 0}.analysis-findings>li>p.analysis-uncertainty{font-size:11px;color:#64768c}.analysis-uncertainty>strong{font-weight:600}.finding-citations{font-size:11px;color:#28619c;margin-top:10px}.finding-citations summary,.retrieved-toggle{cursor:pointer;line-height:1.8}.finding-citations .citation-button{width:100%;margin-top:7px}.analysis-requirements{margin-top:20px}.analysis-requirements h3{font-size:13px;margin:0 0 6px}.analysis-requirements li{padding:12px 0;border-bottom:1px solid #e5ebf1}.analysis-requirements li>div{display:flex;align-items:flex-start;justify-content:space-between;gap:12px}.analysis-requirements li strong{font-size:12px;line-height:1.8;overflow-wrap:anywhere;font-weight:600}.analysis-requirements p{font-size:11px;color:#64768c;line-height:1.8;white-space:pre-line;margin:7px 0 0;overflow-wrap:anywhere}.analysis-requirements p.analysis-followup{display:flex;align-items:flex-start;gap:8px;color:#28619c}.analysis-followup .pi{font-size:11px;margin-top:4px;flex-shrink:0}.retrieved-toggle{font-size:12px;color:#28619c;margin-bottom:12px;font-weight:600}.analysis-brief :deep(.p-tag){flex-shrink:0}.finding-citations .citation-button strong{white-space:pre-wrap}
</style>
