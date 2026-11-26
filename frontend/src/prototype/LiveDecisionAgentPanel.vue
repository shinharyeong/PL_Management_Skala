<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import PButton from 'primevue/button'
import PInputText from 'primevue/inputtext'
import PSelect from 'primevue/select'
import PTextarea from 'primevue/textarea'
import PCheckbox from 'primevue/checkbox'
import PMessage from 'primevue/message'
import PTag from 'primevue/tag'
import PDialog from 'primevue/dialog'
import PTabs from 'primevue/tabs'
import PTabList from 'primevue/tablist'
import PTab from 'primevue/tab'
import PTabPanels from 'primevue/tabpanels'
import PTabPanel from 'primevue/tabpanel'
import PDataTable from 'primevue/datatable'
import PColumn from 'primevue/column'
import DecisionGoalAssistant from './DecisionGoalAssistant.vue'
import DecisionEvidenceWorkbench from './DecisionEvidenceWorkbench.vue'
import DecisionWorkflowPanel from './DecisionWorkflowPanel.vue'
import DecisionFinalReport from './DecisionFinalReport.vue'
import DecisionConversationPanel from './DecisionConversationPanel.vue'
import DecisionPreviewReport from './DecisionPreviewReport.vue'
import { buildPreviewResult } from './decision-preview-result.js'
import { usePrototypeStore } from './live-store.js'
import { formatMoney } from '../lib/decimal-display.js'
import { workDateText } from './decision-workflow-api.js'
import { seoulPeriod } from './domain.js'
import { analysisPayload, candidateDraft, candidatePayload, decisionRequest, emptyCandidate, exclusionText, isReadOnlyPreviewError, startAnalysisAction, wonToEok } from './decision-agent-api.js'

const store = usePrototypeStore()
const router = useRouter()
let removeRouteGuard
const controller = new AbortController()
const busy = ref(false), operation = ref(''), error = ref(''), notice = ref('')
const workflowBusy = ref(false), workflowItems = ref([]), workflowReady = ref(false), requestSource = ref(null)
const year = ref(seoulPeriod().year), baselines = ref([]), analyses = ref([]), selectedId = ref(null)
const baselinesLoaded = ref(false)
const readOnly = ref(false), conversationPanel = ref(null), previewing = ref(false)
const previewExecution = ref(null), previewReportElement = ref(null)
const analysis = ref(null), candidates = ref([]), savedDraft = ref('[]'), activeTab = ref('candidates')
const discardVisible = ref(false), pendingAction = ref(null)
const form = ref({ title: '', prompt: '', masterId: null, targetEok: '', protectedCategories: ['HEADCOUNT', 'RND'] })
const effectTypes = [{ label: '직접 증익', value: 'NET_PROFIT' }, { label: '추가 매출', value: 'REVENUE' }]
const categories = [{ label: '운영 개선', value: 'OPERATIONS' }, { label: '구매·외주', value: 'PROCUREMENT' }, { label: '매출 확대', value: 'REVENUE' }, { label: '인력 관련', value: 'HEADCOUNT' }, { label: 'R&D', value: 'RND' }, { label: '미분류', value: 'UNKNOWN' }]
const years = [year.value - 2, year.value - 1, year.value, year.value + 1]

/** 현재 목표 폼과 선택 분석의 보고서 기준을 만든다.
 * Args: 없음. Returns: 마스터·연도·목표·분석 revision 범위. Raises: 없음. Side Effects: 없음.
 */
function previewScope() { return { masterId: form.value.masterId, year: year.value, targetProfitEok: form.value.targetEok, analysisId: analysis.value?.id || null, revision: analysis.value?.revision ?? null } }
/** 현재 기준에 맞는 실제 대화 실행만 단일 보고서로 투영한다.
 * Args: 없음. Returns: 검증한 보고서 모델. Raises: 없음. Side Effects: 저장·계산 없음.
 */
function previewReport() { return buildPreviewResult(previewExecution.value, previewScope()) }
const previewResult = computed(previewReport)
/** 현재 선택 기준의 이름을 초기 보고서에도 표시한다.
 * Args: 없음. Returns: 선택 기준 이름 또는 빈 문자열. Raises: 없음. Side Effects: 없음.
 */
function previewBaselineLabel() { for (const baseline of baselines.value) if (baseline.masterId === form.value.masterId) return baseline.label; return '' }
const previewLabel = computed(previewBaselineLabel)
/** 대화 실행 상태를 보고서에 연결하고 폼 실행 때 보고서로 이동한다.
 * Args: execution 자식의 실제 실행 이벤트. Returns: Promise<void>. Raises: 없음.
 * Side Effects: 메모리 실행 교체·렌더 후 보고서 스크롤. 업무 저장 없음.
 */
async function acceptPreviewExecution(execution) {
  previewExecution.value = execution
  if (readOnly.value && execution?.source === 'FORM' && execution.phase === 'PENDING') {
    await nextTick()
    previewReportElement.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
}
/** 보고서의 검증 관찰 원문을 기존 대화 출처 창에서 연다.
 * Args: id 현재 실행의 관찰 ID. Returns: 원문 열기 결과. Raises: 없음.
 * Side Effects: 기존 출처 모달만 표시하며 새 API를 호출하지 않는다.
 */
function openPreviewSource(id) { return conversationPanel.value?.openObservation(id, previewExecution.value?.executionId) }

/** 편집 상태가 마지막 서버 응답과 다른지 확인한다.
 * Args: 없음. Returns: 변경 여부. Raises: 없음. Side Effects: 없음.
 */
function hasChanges() { return JSON.stringify(candidates.value) !== savedDraft.value }
const dirty = computed(hasChanges)

/** 미저장 편집과 섞이지 않은 계산 결과만 노출한다.
 * Args: 없음. Returns: 최신 결과 또는 null. Raises: 없음. Side Effects: 없음.
 */
function currentResult() { return dirty.value ? null : analysis.value?.result || null }
const result = computed(currentResult)

/** 선택한 확정 마스터에서 사용 가능한 잔여 월을 만든다.
 * Args: 없음. Returns: 월 선택지. Raises: 없음. Side Effects: 없음.
 */
function remainingMonths() {
  const months = []
  for (let month = (analysis.value?.snapshot.baseMonth ?? 12) + 1; month <= 12; month++) months.push({ label: `${analysis.value.snapshot.year}년 ${month}월`, value: month })
  return months
}
const monthOptions = computed(remainingMonths)

/** 서버 계산 결과를 후보 과제·근거와 연결한다.
 * Args: 없음. Returns: 표에 표시할 효과 목록. Raises: 없음. Side Effects: 없음.
 */
function effectRows() {
  const rows = []
  for (const effect of result.value?.effects || []) {
    const candidate = analysis.value.candidates.find(findCandidate)
    /** 계산 결과와 동일한 후보 ID를 찾는다.
     * Args: item 저장된 후보. Returns: 일치 여부. Raises: 없음. Side Effects: 없음.
     */
    function findCandidate(item) { return item.id === effect.candidateId }
    rows.push({ ...candidate, ...effect, organizationName: organizationName(candidate?.organizationId) })
  }
  return rows
}
const effects = computed(effectRows)

/** 실제 저장·실행 상태를 여섯 단계 진행 표시로 만든다.
 * Args: 없음. Returns: 단계 배열. Raises: 없음. Side Effects: 없음.
 */
function progressSteps() {
  let issued = 0, accepted = 0, decided = 0
  for (const item of workflowItems.value) {
    if (!['DRAFT', 'CANCELLED'].includes(item.state)) issued++
    if (['ACCEPTED', 'APPROVED', 'DECLINED', 'IN_PROGRESS', 'COMPLETED'].includes(item.state)) accepted++
    if (item.decision) decided++
  }
  return [
    { label: '목표와 기준 고정', done: !!analysis.value, detail: analysis.value ? `${analysis.value.snapshot.masterLabel} 고정` : '확정 마스터 선택 필요' },
    { label: '조직 검토 요청', done: issued > 0, detail: `${issued}건 전달 · ${workflowItems.value.length - issued}건 초안 또는 취소` },
    { label: '근거 검토·후보 편입', done: accepted > 0 || !!analysis.value?.candidates.length && !dirty.value, detail: dirty.value ? '저장하지 않은 후보 변경 있음' : `${analysis.value?.candidates.length || 0}개 후보 · ${accepted}건 회신 채택` },
    { label: '효과·제약 검증', done: !!result.value, detail: operation.value === 'run' ? '백엔드 계산 및 설명 생성 중' : result.value ? '서버 계산 완료' : '시나리오 실행 필요' },
    { label: '추진 결정·후속 추적', done: decided > 0, detail: `${decided}건 결정 기록 · 과제별 진행 확인` },
    { label: '회의용 의사결정서', done: !!result.value, detail: result.value ? '현재 근거와 업무 기록 반영' : '계산 후 생성' },
  ]
}
const steps = computed(progressSteps)

/** 분석 목록에 기준 정보를 포함한 선택지를 만든다.
 * Args: 없음. Returns: 분석 선택지. Raises: 없음. Side Effects: 없음.
 */
function analysisOptions() {
  const options = []
  for (const item of analyses.value) options.push({ ...item, label: `${item.title} · v${item.revision}` })
  return options
}
const savedOptions = computed(analysisOptions)

/** 고정한 보호 제약을 사람이 읽을 수 있는 이름으로 표시한다.
 * Args: 없음. Returns: 제약 문장. Raises: 없음. Side Effects: 없음.
 */
function constraintText() {
  const labels = []
  for (const category of analysis.value?.protectedCategories || []) labels.push(category === 'HEADCOUNT' ? '인력 관련 효과 제외' : category === 'RND' ? 'R&D 효과 제외' : category)
  return labels.join(' · ') || '별도 보호 제약 없음'
}
const constraints = computed(constraintText)

/** 금액을 원본 정밀도를 보존한 억원 표시로 바꾼다.
 * Args: value 원 단위 문자열. Returns: 억원 숫자 문자열. Raises: 없음. Side Effects: 없음.
 * @param {string|null|undefined} value
 * @returns {string}
 */
function money(value) { return formatMoney(value, 'HUNDRED_MILLION') }

/** 고정한 조직 목록에서 조직명을 찾는다.
 * Args: id 조직 ID. Returns: 조직명 또는 미확인 안내. Raises: 없음. Side Effects: 없음.
 * @param {string} id
 * @returns {string}
 */
function organizationName(id) {
  for (const organization of analysis.value?.snapshot.organizations || []) if (organization.id === id) return organization.name
  return '조직 확인 필요'
}

/** API 요청에 화면 수명에 연결된 중단 신호를 붙인다.
 * Args: path 공개 API 주소, options 전송 옵션. Returns: 응답 Promise.
 * Raises: HTTP·중단 오류. Side Effects: 인증된 HTTP 요청.
 * @param {string} path
 * @param {object} options
 * @returns {Promise<object>}
 */
async function api(path, options = {}) {
  const signal = options.signal ? AbortSignal.any([controller.signal, options.signal]) : controller.signal
  return store.api(path, { ...options, signal })
}

/** 작업을 직렬 실행하고 오류를 사용자가 확인할 수 있도록 남긴다.
 * Args: name 작업 표시명, work 비동기 작업. Returns: 성공 여부.
 * Raises: 없음; 오류를 화면에 표시한다. Side Effects: 요청과 화면 상태 변경.
 * @param {string} name
 * @param {Function} work
 * @returns {Promise<boolean>}
 */
async function run(name, work) {
  if (busy.value || workflowBusy.value) return false
  busy.value = true; operation.value = name; error.value = ''; notice.value = ''
  try { await work(); return !controller.signal.aborted }
  catch (cause) { if (!controller.signal.aborted) error.value = cause.message || '요청을 완료하지 못했습니다.'; return false }
  finally { busy.value = false; operation.value = '' }
}

/** 서버 분석 응답을 편집 상태에 반영하고 변경 기준을 다시 고정한다.
 * Args: value 서버 분석. Returns: 없음. Raises: 응답 금액 형식 오류.
 * Side Effects: 현재 분석·후보·목록 선택 변경.
 * @param {object} value
 * @returns {void}
 */
function acceptAnalysis(value) {
  if (analysis.value?.id !== value.id) { workflowItems.value = []; workflowReady.value = false; requestSource.value = null }
  analysis.value = value
  candidates.value = value.candidates.map(candidateDraft)
  savedDraft.value = JSON.stringify(candidates.value)
  selectedId.value = value.id
  const existing = analyses.value.findIndex(matchesAnalysis)
  /** 목록에서 같은 분석을 찾는다.
   * Args: item 목록 항목. Returns: 일치 여부. Raises: 없음. Side Effects: 없음.
   */
  function matchesAnalysis(item) { return item.id === value.id }
  const item = { id: value.id, title: value.title, revision: value.revision, createdAt: value.createdAt }
  if (existing >= 0) analyses.value.splice(existing, 1, item)
  else analyses.value.unshift(item)
}

/** 현재 연도의 확정 마스터 목록을 새로 읽는다.
 * Args: 없음. Returns: Promise<void>. Raises: HTTP 오류.
 * Side Effects: 마스터 목록·선택 초기화와 서버 조회.
 */
async function readBaselines() {
  baselines.value = []; baselinesLoaded.value = false; form.value.masterId = null
  const response = await decisionRequest(api, 'baselines', { year: year.value })
  if (!controller.signal.aborted) { baselines.value = response.items || []; baselinesLoaded.value = true }
}

/** 연도 변경 또는 재시도로 마스터 목록을 조회한다.
 * Args: 없음. Returns: Promise<boolean>. Raises: 없음. Side Effects: 조회 상태 변경.
 */
async function refreshBaselines() { return run('baselines', readBaselines) }

/** 저장 분석과 확정 마스터를 독립적으로 조회하고 부분 오류를 알린다.
 * Args: 없음. Returns: Promise<void>. Raises: HTTP 오류.
 * Side Effects: 서버 조회 및 목록 변경.
 */
async function readInitialData() {
  const responses = await Promise.allSettled([readBaselines(), decisionRequest(api, 'analyses')])
  if (controller.signal.aborted) return
  if (responses[1].status === 'fulfilled') { analyses.value = responses[1].value.items || []; readOnly.value = false }
  else if (isReadOnlyPreviewError(responses[1].reason)) { readOnly.value = true; analyses.value = [] }
  const failures = []
  for (const [index, response] of responses.entries()) if (response.status === 'rejected' && !(index === 1 && isReadOnlyPreviewError(response.reason))) failures.push(response.reason.message)
  if (failures.length) throw new Error(failures.join(' / '))
}

/** 첫 진입 또는 재시도에서 목록을 조회한다.
 * Args: 없음. Returns: Promise<boolean>. Raises: 없음. Side Effects: 서버 조회.
 */
async function initialize() { return run('initialize', readInitialData) }

/** 미저장 후보를 버리기 전에 확인하고 요청한 전환을 실행한다.
 * Args: action 생성·불러오기 함수. Returns: 없음. Raises: 없음.
 * Side Effects: 확인 대화상자 또는 요청 실행.
 * @param {Function} action
 * @returns {void}
 */
function switchAnalysis(action) {
  if (dirty.value) { pendingAction.value = action; discardVisible.value = true }
  else action()
}

/** 확인한 미저장 변경을 버리고 대기한 전환을 실행한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 편집 복구·분석 전환.
 */
function confirmDiscard() {
  discardChanges(); discardVisible.value = false
  const action = pendingAction.value; pendingAction.value = null
  action?.()
}

/** 마지막 서버 저장 상태로 후보 편집을 복원한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 편집 내용·오류 변경.
 */
function discardChanges() { candidates.value = JSON.parse(savedDraft.value); error.value = ''; notice.value = '마지막 저장 상태로 되돌렸습니다.' }

/** 목표와 기준으로 새 분석을 생성한다.
 * Args: 없음. Returns: Promise<void>. Raises: 입력·HTTP 오류.
 * Side Effects: 분석 저장과 화면 전환.
 */
async function createWork() {
  if (readOnly.value) throw new Error('현재는 조회 전용입니다. 저장 없이 목표 비교를 사용해 주세요.')
  const payload = analysisPayload(form.value)
  const response = await decisionRequest(api, 'create', payload)
  if (controller.signal.aborted) return
  acceptAnalysis(response); activeTab.value = 'discovery'; notice.value = '확정 마스터와 목표를 고정했습니다. 근거를 조사해 검토 가설을 찾거나 개선 후보를 직접 입력해 주세요.'
}

/** 생성 작업을 중복 실행 없이 시작한다.
 * Args: 없음. Returns: Promise<boolean>. Raises: 없음. Side Effects: 생성 요청.
 */
async function createAnalysis() { return run('create', createWork) }

/** 새 분석 생성 전 미저장 편집 여부를 확인한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 확인 또는 생성 요청.
 */
function requestCreate() { return startAnalysisAction(readOnly.value, previewCurrentGoal, requestSavedAnalysis) }

/** 정상 환경의 기존 분석 생성 전환을 유지한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 미저장 확인 또는 기존 저장 요청.
 */
function requestSavedAnalysis() { switchAnalysis(createAnalysis) }

/** 조회 전용 목표 폼을 기존 대화의 저장 없는 비교로 연결한다.
 * Args: 없음. Returns: Promise<boolean>. Raises: 없음; 대화 화면에 입력 오류를 표시한다.
 * Side Effects: 대화 공개 메서드 호출만 수행하며 분석 생성 API는 호출하지 않는다.
 */
async function previewCurrentGoal() {
  if (previewing.value) return false
  error.value = ''; notice.value = ''
  previewing.value = true
  try {
    await nextTick()
    if (!conversationPanel.value) { error.value = '대화 화면을 준비 중입니다. 잠시 후 다시 눌러 주세요.'; return false }
    return await conversationPanel.value.requestGoalPreview({ masterId: form.value.masterId, year: year.value, targetProfitEok: form.value.targetEok })
  }
  finally { previewing.value = false }
}

/** 선택한 저장 분석의 최신 버전을 읽는다.
 * Args: 없음. Returns: Promise<void>. Raises: HTTP 오류.
 * Side Effects: 저장 분석 조회 및 편집 화면 전환.
 */
async function loadWork() {
  const response = await decisionRequest(api, 'load', { id: selectedId.value })
  if (controller.signal.aborted) return
  acceptAnalysis(response); activeTab.value = response.result ? 'decision' : 'discovery'
}

/** 선택 분석을 직렬로 불러온다.
 * Args: 없음. Returns: Promise<boolean>. Raises: 없음. Side Effects: 조회 요청.
 */
async function loadAnalysis() { return run('load', loadWork) }

/** 저장 분석 전환 전 미저장 편집 여부를 확인한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 확인 또는 조회 요청.
 */
function requestLoad() { switchAnalysis(loadAnalysis) }

/** 실제 조직·효과를 직접 입력할 빈 후보를 추가한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 편집 후보 추가.
 */
function addCandidate() { candidates.value.push(emptyCandidate(crypto.randomUUID())); activeTab.value = 'candidates' }

/** 선택한 후보를 편집 목록에서 제거한다. 저장 전까지 서버에는 반영하지 않는다.
 * Args: index 후보 순번. Returns: 없음. Raises: 없음. Side Effects: 편집 후보 제거.
 * @param {number} index
 * @returns {void}
 */
function removeCandidate(index) { candidates.value.splice(index, 1) }

/** 후보 전체를 최신 revision 조건으로 저장한다.
 * Args: 없음. Returns: Promise<void>. Raises: 검증·버전 충돌·HTTP 오류.
 * Side Effects: 후보 저장과 이전 계산 결과 무효화.
 */
async function saveWork() {
  const payload = []
  for (const candidate of candidates.value) payload.push(candidatePayload(candidate, analysis.value.snapshot))
  const response = await decisionRequest(api, 'save', { id: analysis.value.id, expectedRevision: analysis.value.revision, candidates: payload })
  if (!controller.signal.aborted) { acceptAnalysis(response); notice.value = '후보를 저장했습니다. 변경된 후보로 시나리오를 다시 계산해 주세요.' }
}

/** 편집 후보 저장을 직렬 실행한다.
 * Args: 없음. Returns: Promise<boolean>. Raises: 없음. Side Effects: 후보 저장 요청.
 */
async function saveCandidates() { return run('save', saveWork) }

/** 미저장 후보를 먼저 저장한 뒤 그 응답 revision으로 계산을 요청한다.
 * Args: 없음. Returns: Promise<void>. Raises: 검증·버전 충돌·HTTP 오류.
 * Side Effects: 필요한 후보 저장·시나리오 계산·결과 표시.
 */
async function calculateWork() {
  if (dirty.value) await saveWork()
  if (controller.signal.aborted) return
  const response = await decisionRequest(api, 'run', { id: analysis.value.id, expectedRevision: analysis.value.revision })
  if (!controller.signal.aborted) { acceptAnalysis(response); activeTab.value = 'decision'; notice.value = '시나리오 계산을 완료했습니다. 조건부 효과와 남은 Gap을 검토해 주세요.' }
}

/** 저장 후 시나리오 계산을 직렬 실행한다.
 * Args: 없음. Returns: Promise<boolean>. Raises: 없음. Side Effects: 저장·계산 요청.
 */
async function calculate() { return run('run', calculateWork) }

/** 미저장 후보가 있는 화면 이탈을 차단하고 복구 방법을 알린다.
 * Args: to 이동할 라우트. Returns: 이동 허용 여부. Raises: 없음. Side Effects: 오류 안내.
 * @param {object} to
 * @returns {boolean}
 */
function guardNavigation(to) {
  if (to.name === 'decision-agent') return true
  if (!dirty.value && !busy.value && !workflowBusy.value) return true
  error.value = busy.value ? '진행 중인 작업이 끝난 뒤 이동해 주세요.' : '저장하지 않은 후보가 있습니다. 후보를 저장하거나 변경 되돌리기를 선택한 뒤 이동해 주세요.'
  return false
}

/** 브라우저 닫기·새로고침 시 미저장 후보를 경고한다.
 * Args: event 브라우저 이탈 이벤트. Returns: 없음. Raises: 없음. Side Effects: 이탈 확인 요청.
 * @param {BeforeUnloadEvent} event
 * @returns {void}
 */
function warnBeforeUnload(event) { if (dirty.value || busy.value || workflowBusy.value) { event.preventDefault(); event.returnValue = '' } }

/** 화면을 초기화하고 미저장 이탈 감시를 시작한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 이벤트 등록과 목록 조회.
 */
function mountPanel() { removeRouteGuard = router.beforeEach(guardNavigation); window.addEventListener('beforeunload', warnBeforeUnload); initialize() }

/** 떠난 화면으로 응답이 반영되지 않도록 요청과 감시를 정리한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 요청 취소와 이벤트 해제.
 */
function disposePanel() { controller.abort(); removeRouteGuard?.(); window.removeEventListener('beforeunload', warnBeforeUnload) }

/** 사용자가 확인한 자연어 제안을 목표 입력에 반영한다.
 * Args: proposal 목표 제안. Returns: Promise<void>. Raises: 금액 형식 오류.
 * Side Effects: 목표 폼·연도·마스터 선택 변경. 분석은 자동 생성하지 않는다.
 */
async function applyGoalProposal(proposal) {
  if (proposal.targetProfitWon != null) form.value.targetEok = wonToEok(proposal.targetProfitWon)
  form.value.protectedCategories = [...proposal.protectedCategories]
  if (proposal.targetYear && proposal.targetYear !== year.value) {
    year.value = proposal.targetYear
    if (!years.includes(proposal.targetYear)) years.push(proposal.targetYear)
    await refreshBaselines()
  }
  notice.value = readOnly.value ? '목표 제안을 입력에 반영했습니다. 저장 없이 목표 비교를 누르면 오른쪽 보고서에 정리됩니다.' : '확인한 목표 제안을 입력에 반영했습니다. 기준 마스터와 금액을 확인하고 분석을 시작해 주세요.'
}
/** 선택한 가설을 조직 요청 작성 탭으로 넘긴다.
 * Args: source 근거 기반 가설. Returns: 없음. Raises: 없음.
 * Side Effects: 요청 원천과 활성 탭 변경; 발송은 하지 않는다.
 */
function prepareWorkRequest(source) { requestSource.value = { ...source }; activeTab.value = 'workflow' }
/** 분석 생성 전 대화용 마스터 선택을 목표 입력과 공유한다.
 * Args: id 확정 마스터 ID. Returns: 없음. Raises: 없음. Side Effects: 선택 기준 변경.
 */
function selectConversationMaster(id) { if (!analysis.value) form.value.masterId = id }
/** 대화 응답의 허용된 탐색 동작으로 기존 업무 화면을 연다.
 * Args: tab 허용된 업무 탭. Returns: 없음. Raises: 없음. Side Effects: 탭·안내 변경.
 */
function openConversationTab(tab) {
  if (!analysis.value || !['discovery', 'scenarios', 'workflow'].includes(tab)) return
  if (tab === 'scenarios' && !result.value) { activeTab.value = 'candidates'; notice.value = '저장한 후보를 확인하고 시나리오를 실행하면 계산 결과를 비교할 수 있습니다.'; return }
  activeTab.value = tab
}
/** 조직 업무 작업 중에는 분석의 중복 변경과 이탈을 막는다.
 * Args: value 진행 여부. Returns: 없음. Raises: 없음. Side Effects: 진행 상태 변경.
 */
function setWorkflowBusy(value) { workflowBusy.value = value }
/** 요청·결정·후속 추적 목록을 최종 보고서와 공유한다.
 * Args: value 최신 업무 목록. Returns: 없음. Raises: 없음. Side Effects: 보고서 업무 기준 변경.
 */
function setWorkflowItems(value) { workflowItems.value = value }
/** 업무 목록 조회가 완료된 경우에만 최신 기록의 인쇄를 허용한다.
 * Args: value 목록 조회 성공 여부. Returns: 없음. Raises: 없음. Side Effects: 보고서 준비 상태 변경.
 */
function setWorkflowReady(value) { workflowReady.value = value }
/** 회신 검토와 결정 이후 최신 분석 후보·계산 유효성을 다시 읽는다.
 * Args: 없음. Returns: Promise<void>. Raises: HTTP 오류.
 * Side Effects: 분석 재조회와 이전 편집 상태 초기화.
 */
async function refreshCurrentAnalysis() {
  analysis.value = { ...analysis.value, result: null, narrative: null }
  const response = await decisionRequest(api, 'load', { id: analysis.value.id })
  if (!controller.signal.aborted) acceptAnalysis(response)
}
onMounted(mountPanel)
onBeforeUnmount(disposePanel)
</script>

<template>
  <div class="decision-agent" :class="{ 'readonly-agent': readOnly }">
    <header class="agent-heading">
      <div><p class="eyebrow">P&amp;L INTELLIGENCE</p><h1>목표를 실행 가능한 판단으로</h1><p class="subtle">확정 전망과 개선 근거를 연결해, 이번 회의에서 결정할 내용을 정리합니다.</p></div>
      <PTag :value="readOnly ? '조회 전용 · 목표 비교' : '독립 운영 · 검토와 실행'" severity="secondary" />
    </header>
    <PMessage v-if="error" severity="error" :closable="false">{{ error }}<PButton v-if="!analysis" label="다시 조회" size="small" text :disabled="busy || workflowBusy" @click="initialize" /></PMessage>
    <PMessage v-if="notice" severity="success" :closable="false">{{ notice }}</PMessage>
    <p v-if="readOnly" class="readonly-mode-note"><i class="pi pi-lock" aria-hidden="true" /> 조회 전용 · 목표 비교는 저장되지 않습니다.</p>
    <div v-if="!readOnly" class="analysis-library"><div><span class="field-caption">저장한 분석</span><PSelect v-model="selectedId" :options="savedOptions" option-label="label" option-value="id" placeholder="기존 목표와 근거 이어서 검토" :disabled="busy || workflowBusy" aria-label="저장한 분석 선택" /></div><PButton label="불러오기" icon="pi pi-folder-open" outlined :disabled="busy || workflowBusy || !selectedId" @click="requestLoad" /></div>
    <DecisionConversationPanel ref="conversationPanel" class="agent-conversation" :api="api" :analysis="analysis" :master-id="form.masterId" :baselines="baselines" :disabled="dirty || busy || workflowBusy" @select-master="selectConversationMaster" @navigate="openConversationTab" @request="prepareWorkRequest" @execution="acceptPreviewExecution" />
    <div class="agent-workspace">
      <aside class="goal-rail">
        <form class="goal-card" @submit.prevent="requestCreate">
          <div class="section-heading"><span class="section-number">01</span><h2>목표와 분석 기준</h2></div>
          <div v-if="!readOnly" class="field"><label for="agent-title">분석 이름</label><PInputText id="agent-title" v-model="form.title" maxlength="200" placeholder="회의에서 검토할 목표" :disabled="busy || workflowBusy" required /></div>
          <div class="field"><label for="agent-prompt">경영 요청</label><PTextarea id="agent-prompt" v-model="form.prompt" rows="3" maxlength="4000" placeholder="달성할 목표와 검토할 방향을 입력해 주세요." :disabled="busy || workflowBusy" :required="!readOnly" /><small>{{ readOnly ? '요청에서 목표를 읽고, 확인한 금액으로 비교합니다.' : '계산에 적용할 보호 제약은 아래에서 직접 선택합니다.' }}</small></div>
          <DecisionGoalAssistant :api="api" :prompt="form.prompt" :year="year" :disabled="busy || workflowBusy" @apply="applyGoalProposal" />
          <div class="field"><label for="agent-year">목표 연도</label><PSelect input-id="agent-year" v-model="year" :options="years" :disabled="busy || workflowBusy" @change="refreshBaselines" /></div>
          <div class="field"><label for="agent-baseline">확정 마스터</label><PSelect input-id="agent-baseline" v-model="form.masterId" :options="baselines" option-label="label" option-value="masterId" placeholder="기준 버전을 선택해 주세요" :disabled="busy || workflowBusy || !baselines.length" /><small>누적 실적 + 잔여 추정으로 구성한 연간 전망을 고정합니다.</small></div>
          <PMessage v-if="!busy && baselinesLoaded && !baselines.length" severity="warn" :closable="false">이 연도의 확정 마스터가 없습니다. 월간 제출을 검토·확정한 뒤 다시 조회해 주세요.</PMessage>
          <div class="field"><label for="agent-target">연간 영업이익 목표 <span class="unit-label">억 원</span></label><PInputText id="agent-target" v-model="form.targetEok" inputmode="decimal" maxlength="40" placeholder="목표 금액 입력" :disabled="busy || workflowBusy" required /><small>선택한 연도 12월까지의 목표 · 쉼표 없이 입력</small></div>
          <fieldset v-if="!readOnly" class="constraints"><legend>보호할 비용</legend><div><PCheckbox input-id="protect-headcount" v-model="form.protectedCategories" value="HEADCOUNT" :disabled="readOnly || busy || workflowBusy" /><label for="protect-headcount">인력 관련 효과 제외</label></div><div><PCheckbox input-id="protect-rnd" v-model="form.protectedCategories" value="RND" :disabled="readOnly || busy || workflowBusy" /><label for="protect-rnd">R&amp;D 효과 제외</label></div><small>{{ readOnly ? '보호 비용 선택은 개선 과제 검증 단계에서 적용합니다. 이번 목표 비교에는 개선 효과나 보호 제약을 적용하지 않습니다.' : '보호 제약이 있으면 미분류 과제도 계산에서 제외합니다.' }}</small></fieldset>
          <PButton type="submit" :label="readOnly ? '저장 없이 목표 비교' : analysis ? '이 조건으로 새 분석 만들기' : '기준 고정하고 시작'" icon="pi pi-arrow-right" :loading="previewing || operation === 'create'" :disabled="previewing || busy || workflowBusy || !form.masterId" />
          <p v-if="readOnly" class="readonly-form-note">선택한 확정 마스터를 기준으로 결과를 오른쪽 보고서에 정리합니다. 분석·조직 요청·승인은 저장하지 않으며, 이번 비교에는 보호 제약과 개선 과제 효과를 적용하지 않습니다.</p>
        </form>
        <section v-if="!readOnly" class="progress-card"><h2>분석 진행</h2><ol><li v-for="(step, index) in steps" :key="step.label" :class="{ completed: step.done }"><span class="step-marker"><i v-if="step.done" class="pi pi-check" aria-hidden="true" /><template v-else>{{ index + 1 }}</template></span><div><strong>{{ step.label }}</strong><small>{{ step.detail }}</small></div></li></ol><p>조직 회신은 근거 검토 후 후보에 편입합니다. 추진 결정과 후속 전망 변화는 기록으로 구분합니다.</p></section>
      </aside>

      <section ref="previewReportElement" class="analysis-area" :class="{ 'preview-report-area': readOnly }" aria-live="polite" :aria-busy="busy || previewing">
        <DecisionPreviewReport v-if="readOnly" :result="previewResult" :baseline-label="previewLabel" :year="year" @source="openPreviewSource" />
        <div v-else-if="!analysis" class="agent-empty"><span class="empty-symbol"><i class="pi pi-chart-line" aria-hidden="true" /></span><p class="eyebrow">A CLEAR BASIS FOR YOUR NEXT DECISION</p><h2>목표까지 남은 금액부터,<br>다음에 확인할 근거까지.</h2><p>확정 마스터를 선택하고 목표를 입력하면<br>조직별 개선 후보를 검토할 공간이 열립니다.</p><div class="empty-stages"><span>목표 Gap</span><i class="pi pi-arrow-right" aria-hidden="true" /><span>후보 검증</span><i class="pi pi-arrow-right" aria-hidden="true" /><span>의사결정서</span></div><small>가상 효과를 채우지 않습니다. 실제 조직의 개선 가능액과 근거를 입력해 주세요.</small></div>
        <template v-else>
          <header class="analysis-header"><div><p class="eyebrow">ACTIVE ANALYSIS · v{{ analysis.revision }}</p><h2>{{ analysis.title }}</h2><p>{{ analysis.snapshot.masterLabel }} · {{ analysis.snapshot.year }}년 {{ analysis.snapshot.baseMonth }}월 기준 · {{ constraints }}</p></div><PTag :value="dirty ? '미저장 변경' : result ? '계산 완료 · 조건부' : '후보 검토 중'" :severity="dirty ? 'warn' : result ? 'success' : 'secondary'" /></header>
          <div class="baseline-strip"><div><span>고정한 연간 전망</span><strong>{{ money(analysis.snapshot.baselineProfitWon) }}<small>억</small></strong></div><div><span>경영 목표</span><strong>{{ money(analysis.targetProfitWon) }}<small>억</small></strong></div><div><span>후보 검토 기간</span><strong class="period-text">{{ analysis.snapshot.baseMonth < 12 ? `${analysis.snapshot.baseMonth + 1}–12월` : '잔여 월 없음' }}</strong></div></div>
          <PMessage v-if="dirty" severity="warn" :closable="false">후보가 변경되어 이전 결과를 숨겼습니다. 저장 후 다시 계산하면 최신 의사결정서를 확인할 수 있습니다.</PMessage>
          <PMessage v-if="analysis.snapshot.baseMonth === 12" severity="info" :closable="false">이 기준은 12월까지 반영되어 있습니다. 남은 기간의 개선 후보를 검토하려면 이전 월의 확정 마스터로 새 분석을 만들어 주세요.</PMessage>
          <div class="analysis-actions"><span>{{ candidates.length }}개 후보 <span v-if="dirty">· 저장 필요</span></span><div><PButton v-if="dirty" label="변경 되돌리기" text severity="secondary" :disabled="busy || workflowBusy" @click="discardChanges" /><PButton label="후보 저장" outlined :disabled="busy || workflowBusy || !dirty" :loading="operation === 'save'" @click="saveCandidates" /><PButton :label="dirty ? '저장하고 시나리오 실행' : '시나리오 실행'" icon="pi pi-sparkles" :loading="operation === 'run'" :disabled="busy || workflowBusy" @click="calculate" /></div></div>
          <PTabs v-model:value="activeTab">
            <PTabList><PTab value="discovery">근거 조사·제안</PTab><PTab value="workflow">조직 검토·실행</PTab><PTab value="candidates">개선 후보</PTab><PTab value="scenarios" :disabled="!result">시나리오 비교</PTab><PTab value="decision" :disabled="!result">의사결정서</PTab><PTab value="evidence">근거와 기준</PTab></PTabList>
            <PTabPanels>
              <PTabPanel value="discovery"><DecisionEvidenceWorkbench :analysis="analysis" :api="api" :disabled="dirty || busy || workflowBusy" @request="prepareWorkRequest" /></PTabPanel>
              <PTabPanel value="workflow"><DecisionWorkflowPanel :analysis="analysis" :api="api" :refresh-analysis="refreshCurrentAnalysis" :request-source="requestSource" :disabled="dirty || busy" @busy="setWorkflowBusy" @items="setWorkflowItems" @loaded="setWorkflowReady" /></PTabPanel>
              <PTabPanel value="candidates">
                <div class="tab-heading"><div><h3>숫자를 뒷받침할 개선 후보</h3><p>미래에 발생할 효과를 검토합니다. 근거 검토 표시가 실현 성과를 뜻하지는 않습니다.</p></div><PButton label="후보 추가" icon="pi pi-plus" outlined :disabled="busy || workflowBusy || !monthOptions.length || candidates.length >= 100" @click="addCandidate" /></div>
                <div v-if="!candidates.length" class="inline-empty"><i class="pi pi-list-check" aria-hidden="true" /><strong>아직 입력한 후보가 없습니다.</strong><p>조직과 과제, 효과 금액, 반영 시점, 근거를 추가해 주세요.<br>후보 없이도 현재 전망과 목표 Gap을 확인할 수 있습니다.</p></div>
                <article v-for="(candidate, index) in candidates" :key="candidate.id" class="candidate-card">
                  <div class="candidate-heading"><h4><span>{{ String(index + 1).padStart(2, '0') }}</span>{{ candidate.title || '새 개선 후보' }}</h4><PButton label="제거" icon="pi pi-trash" text severity="danger" :disabled="busy || workflowBusy" @click="removeCandidate(index)" /></div>
                  <div class="candidate-grid">
                    <div class="field"><label :for="`org-${candidate.id}`">담당 조직</label><PSelect :input-id="`org-${candidate.id}`" v-model="candidate.organizationId" :options="analysis.snapshot.organizations" option-label="name" option-value="id" placeholder="기준 마스터의 조직" :disabled="busy || workflowBusy" /></div>
                    <div class="field"><label :for="`title-${candidate.id}`">과제명</label><PInputText :id="`title-${candidate.id}`" v-model="candidate.title" maxlength="200" placeholder="검토할 개선 과제" :disabled="busy || workflowBusy" /></div>
                    <div class="field"><label :for="`type-${candidate.id}`">효과 유형</label><PSelect :input-id="`type-${candidate.id}`" v-model="candidate.type" :options="effectTypes" option-label="label" option-value="value" :disabled="busy || workflowBusy" /></div>
                    <div class="field"><label :for="`amount-${candidate.id}`">{{ candidate.type === 'REVENUE' ? '추가 매출' : '비용 차감 전 직접 증익' }} <span class="unit-label">억 원</span></label><PInputText :id="`amount-${candidate.id}`" v-model="candidate.amountEok" inputmode="decimal" maxlength="40" placeholder="효과 금액" :disabled="busy || workflowBusy" /></div>
                    <div v-if="candidate.type === 'REVENUE'" class="field"><label :for="`margin-${candidate.id}`">공헌이익률 <span class="unit-label">%</span></label><PInputText :id="`margin-${candidate.id}`" v-model="candidate.contributionMarginPercent" inputmode="decimal" maxlength="10" placeholder="0~100" :disabled="busy || workflowBusy" /></div>
                    <div class="field"><label :for="`cost-${candidate.id}`">실행 부대비용 <span class="unit-label">억 원</span></label><PInputText :id="`cost-${candidate.id}`" v-model="candidate.implementationCostEok" inputmode="decimal" maxlength="40" :disabled="busy || workflowBusy" /></div>
                    <div class="field"><label :for="`embedded-${candidate.id}`">기존 전망 반영분 <span class="unit-label">억 원</span></label><PInputText :id="`embedded-${candidate.id}`" v-model="candidate.embeddedEffectEok" inputmode="decimal" maxlength="40" :disabled="busy || workflowBusy" /></div>
                    <div class="field"><label :for="`month-${candidate.id}`">효과 반영 월</label><PSelect :input-id="`month-${candidate.id}`" v-model="candidate.recognitionMonth" :options="monthOptions" option-label="label" option-value="value" placeholder="잔여 기간 중 선택" :disabled="busy || workflowBusy" /></div>
                    <div class="field"><label :for="`category-${candidate.id}`">효과 분류</label><PSelect :input-id="`category-${candidate.id}`" v-model="candidate.category" :options="categories" option-label="label" option-value="value" :disabled="busy || workflowBusy" /></div>
                    <div class="field"><label :for="`duplicate-${candidate.id}`">중복 효과 묶음 <span class="optional">선택</span></label><PInputText :id="`duplicate-${candidate.id}`" v-model="candidate.duplicateGroup" maxlength="100" placeholder="동일 효과면 같은 식별자" :disabled="busy || workflowBusy" /></div>
                  </div>
                  <p class="calculation-note">{{ candidate.type === 'REVENUE' ? '추가 매출 × 공헌이익률' : '비용 차감 전 직접 증익' }} − 실행 부대비용 − 기존 전망 반영분 = 추가 이익 효과. 비용·반영분 0은 해당 없음으로 가정하므로 확인해 주세요.</p>
                  <div class="evidence-fields"><div class="field"><label :for="`note-${candidate.id}`">검토 내용과 가정</label><PTextarea :id="`note-${candidate.id}`" v-model="candidate.evidenceNote" rows="2" maxlength="4000" placeholder="효과 산출 근거, 확인한 조건, 남은 불확실성" :disabled="busy || workflowBusy" /></div><div class="field"><label :for="`ref-${candidate.id}`">근거 위치</label><PInputText :id="`ref-${candidate.id}`" v-model="candidate.evidenceRef" maxlength="2000" placeholder="계약·견적·소명 ID 또는 문서 위치" :disabled="busy || workflowBusy" /></div></div>
                  <div class="review-checkbox"><PCheckbox :input-id="`reviewed-${candidate.id}`" v-model="candidate.reviewed" binary :disabled="busy || workflowBusy" /><label :for="`reviewed-${candidate.id}`">담당자가 근거를 검토함 <span>추진 승인·실현 확정과 구분</span></label></div>
                </article>
                <p class="subtle rules-note">보호 비용, 미분류 과제, 중복 효과, 기간 밖 효과, 0 이하의 추가 이익은 서버 계산에서 제외됩니다. 같은 중복 묶음에 속한 후보는 모두 제외됩니다.</p>
              </PTabPanel>

              <PTabPanel value="scenarios">
                <template v-if="result"><div class="tab-heading"><div><h3>근거 수준별 시나리오</h3><p>근거 검토 후보만 반영한 결과와 가정을 포함한 전체 후보의 결과를 비교합니다.</p></div><span class="unit-label">단위: 억 원</span></div>
                  <div class="scenario-grid"><article><PTag value="기준 전망" severity="secondary" /><strong>{{ money(result.baselineProfitWon) }}</strong><p>확정 마스터의 연간 전망</p><span>목표까지 {{ money(result.initialGapWon) }}억</span></article><article><PTag value="근거 검토 후보" severity="info" /><strong>{{ money(result.reviewedOutlookWon) }}</strong><p>검토한 후보 효과 +{{ money(result.reviewedEffectWon) }}억</p><span>남은 Gap {{ money(result.reviewedGapWon) }}억</span></article><article class="conditional-scenario"><PTag value="가정 포함 전체 후보" severity="warn" /><strong>{{ money(result.conditionalOutlookWon) }}</strong><p>전체 적격 후보 효과 +{{ money(result.conditionalEffectWon) }}억</p><span>남은 Gap {{ money(result.conditionalGapWon) }}억</span></article></div>
                  <PMessage severity="info" :closable="false">전체 후보 시나리오에 근거 검토 후보가 포함됩니다. 두 효과를 더하지 않습니다. 모든 개선 전망은 조건부이며 미래 실현을 보장하지 않습니다.</PMessage>
                  <PDataTable :value="effects" data-key="candidateId" size="small" class="effects-table"><PColumn field="organizationName" header="담당 조직" /><PColumn field="title" header="과제" /><PColumn header="추가 이익"><template #body="{ data }">{{ money(data.netEffectWon) }}억</template></PColumn><PColumn header="반영 판단"><template #body="{ data }"><PTag :value="!data.included ? '제외' : data.reviewed ? '근거 검토' : '가정 포함'" :severity="!data.included ? 'danger' : data.reviewed ? 'info' : 'warn'" /><p v-if="data.exclusionReasons.length" class="table-note">{{ exclusionText(data.exclusionReasons) }}</p></template></PColumn></PDataTable>
                </template>
              </PTabPanel>

              <PTabPanel value="decision"><DecisionFinalReport v-if="result" :analysis="analysis" :work-items="workflowItems" :work-items-ready="workflowReady" :disabled="busy || workflowBusy" /></PTabPanel>

              <PTabPanel value="evidence"><div class="tab-heading"><div><h3>같은 기준으로 다시 확인할 수 있도록</h3><p>분석을 생성할 때 고정한 원본과 현재 후보의 근거를 확인합니다.</p></div></div><dl class="source-details"><div><dt>기준 마스터</dt><dd>{{ analysis.snapshot.masterLabel }} · {{ analysis.snapshot.masterId }}</dd></div><div><dt>기준일</dt><dd>{{ workDateText(analysis.snapshot.asOf) }}</dd></div><div><dt>원본 제출 ID</dt><dd>{{ analysis.snapshot.sourceSubmissionIds.join(', ') || '원본 제출 정보 없음' }}</dd></div><div><dt>분석 기준</dt><dd>{{ analysis.snapshot.year }}년 연간 목표 · {{ analysis.snapshot.baseMonth }}월까지의 확정 기준 · 잔여 월 후보</dd></div><div><dt>보호 제약</dt><dd>{{ constraints }}</dd></div><div><dt>경영 요청 원문</dt><dd>{{ analysis.prompt }}</dd></div></dl><h4 class="evidence-title">후보별 근거 위치</h4><div v-if="!candidates.length" class="inline-empty">아직 등록한 후보 근거가 없습니다.</div><article v-for="candidate in candidates" :key="candidate.id" class="evidence-item"><div><strong>{{ candidate.title || '제목 미입력' }}</strong><PTag :value="candidate.reviewed ? '담당자 근거 검토' : '가정 단계'" :severity="candidate.reviewed ? 'info' : 'warn'" /></div><p>{{ candidate.evidenceNote || '검토 내용 미입력' }}</p><span>{{ candidate.evidenceRef || '근거 위치 미입력' }}</span></article><PMessage severity="info" :closable="false">문서 위치와 검토 여부는 담당자가 입력한 기록입니다. 문서 내용을 자동 검증하지 않으며, 조직 요청·회신과 실제 검토 결과는 조직 검토·실행 탭에서 확인합니다.</PMessage></PTabPanel>
            </PTabPanels>
          </PTabs>
        </template>
      </section>
    </div>
    <PDialog v-model:visible="discardVisible" modal header="저장하지 않은 후보가 있습니다" :style="{ width: '28rem' }"><p>변경을 버리고 다른 분석으로 이동할까요? 저장된 후보에는 영향이 없습니다.</p><template #footer><PButton label="편집 계속" text @click="discardVisible = false" /><PButton label="변경 버리고 계속" severity="warn" @click="confirmDiscard" /></template></PDialog>

  </div>
</template>

<style scoped>
.decision-agent{--agent-ink:#193047;--agent-muted:#64768c;--agent-blue:#28619c;--agent-line:#dbe3eb;--agent-soft:#f3f7fa;color:var(--agent-ink);font-size:14px;max-width:1700px;margin:auto}.readonly-agent{display:flex;flex-direction:column}.readonly-agent>.agent-heading{order:0;margin-bottom:12px}.readonly-agent>.p-message,.readonly-agent>.readonly-mode-note{order:1}.readonly-agent>.agent-workspace{order:2}.readonly-agent>.agent-conversation{order:3;margin-top:28px}.readonly-mode-note{display:flex;gap:7px;align-items:center;color:var(--agent-muted);font-size:11px;margin:0 0 20px!important}.analysis-area.preview-report-area{padding:0;border:0;background:transparent;scroll-margin-top:24px}.readonly-agent .goal-card{border-color:#dbe3eb;box-shadow:none}.decision-agent h1,.decision-agent h2,.decision-agent h3,.decision-agent h4,.decision-agent p{margin:0}.agent-heading{display:flex;justify-content:space-between;gap:24px;align-items:flex-start;margin:8px 0 28px}.eyebrow{font-size:11px;font-weight:750;letter-spacing:.18em;color:var(--agent-blue);margin-bottom:10px!important}.agent-heading h1{font-size:28px;letter-spacing:-.04em;line-height:1.35;margin-bottom:8px}.subtle,.agent-heading p,.analysis-header p{color:var(--agent-muted);line-height:1.65}.decision-agent :deep(.p-message){margin:12px 0}.analysis-library{display:flex;align-items:end;gap:10px;padding:14px 18px;background:white;border:1px solid var(--agent-line);border-radius:8px;margin-bottom:20px}.analysis-library>div{flex:1;max-width:550px}.field-caption{display:block;font-size:12px;font-weight:650;margin-bottom:6px}.analysis-library :deep(.p-select){width:100%}.agent-workspace{display:grid;grid-template-columns:300px minmax(0,1fr);gap:22px;align-items:start}.goal-rail{display:grid;gap:18px}.goal-card,.progress-card{padding:20px;background:white;border:1px solid var(--agent-line);border-radius:8px}.section-heading{display:flex;gap:9px;align-items:baseline;margin-bottom:18px}.section-number{font-size:12px;font-weight:750;letter-spacing:.08em;color:var(--agent-blue)}.section-heading h2,.progress-card h2{font-size:16px}.field{display:flex;flex-direction:column;gap:7px;margin-bottom:16px;min-width:0}.field label{font-size:12px;font-weight:650;line-height:1.4}.field small,.constraints small{color:var(--agent-muted);font-size:11px;line-height:1.55}.field :deep(.p-inputtext),.field :deep(.p-textarea),.field :deep(.p-select){width:100%;font-size:13px}.unit-label,.optional{font-size:11px;font-weight:400;color:var(--agent-muted);white-space:nowrap}.unit-label{margin-left:3px}.constraints{border:0;margin:0 0 20px;padding:0}.constraints legend{font-size:12px;font-weight:650;margin-bottom:10px}.constraints>div{display:flex;align-items:center;gap:8px;margin-bottom:9px}.constraints label{font-size:12px}.constraints small{display:block;margin-top:9px}.goal-card>.p-button{width:100%}.readonly-form-note{font-size:11px;line-height:1.7;color:var(--agent-muted);margin-top:12px!important}.progress-card ol{list-style:none;padding:0;margin:20px 0 12px;display:grid;gap:17px}.progress-card li{display:flex;gap:11px;align-items:start}.step-marker{display:flex;align-items:center;justify-content:center;width:24px;height:24px;border:1px solid #d3dce5;border-radius:50%;font-size:10px;color:var(--agent-muted);flex-shrink:0}.completed .step-marker{background:#e9f3ef;border-color:#e9f3ef;color:#197259}.step-marker i{font-size:11px}.progress-card li strong{font-size:12px;display:block;padding-top:3px}.progress-card li small{display:block;color:var(--agent-muted);font-size:11px;line-height:1.5;margin-top:4px}.progress-card>p{padding-top:12px;border-top:1px solid var(--agent-line);font-size:11px;color:var(--agent-muted);line-height:1.7}.analysis-area{min-width:0;border:1px solid var(--agent-line);border-radius:8px;background:white;padding:24px}.agent-empty{min-height:650px;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;padding:30px}.empty-symbol{width:58px;height:58px;display:grid;place-items:center;background:var(--agent-soft);border-radius:16px;margin-bottom:28px;color:var(--agent-blue)}.empty-symbol i{font-size:26px}.agent-empty h2{font-size:27px;letter-spacing:-.04em;line-height:1.5}.agent-empty>p:not(.eyebrow){color:var(--agent-muted);line-height:1.8;margin-top:17px}.agent-empty small{font-size:11px;color:var(--agent-muted);line-height:1.7}.empty-stages{display:flex;align-items:center;gap:15px;margin:32px 0;font-size:12px;font-weight:650}.empty-stages i{font-size:10px;color:#93a6b9}.analysis-header{display:flex;justify-content:space-between;gap:15px;align-items:start}.analysis-header h2{font-size:23px;line-height:1.4;letter-spacing:-.04em;margin-bottom:8px;overflow-wrap:anywhere}.analysis-header p:not(.eyebrow){font-size:11px}.baseline-strip{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));padding:20px 0;border-top:1px solid var(--agent-line);border-bottom:1px solid var(--agent-line);margin:22px 0 8px}.baseline-strip>div{padding:0 16px;border-left:1px solid var(--agent-line)}.baseline-strip>div:first-child{padding-left:0;border:0}.baseline-strip span,.sheet-kpis span{display:block;color:var(--agent-muted);font-size:11px;margin-bottom:8px}.baseline-strip strong{font-size:25px;letter-spacing:-.04em;overflow-wrap:anywhere}.baseline-strip small,.sheet-kpis small{font-size:12px;color:var(--agent-muted);margin-left:2px}.baseline-strip .period-text{font-size:19px}.analysis-actions{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:12px 0 19px}.analysis-actions>span{font-size:11px;color:var(--agent-muted)}.analysis-actions>div{display:flex;gap:8px;flex-wrap:wrap}.decision-agent :deep(.p-button){font-size:12px}.decision-agent :deep(.p-tab){font-size:12px;padding:14px 16px}.decision-agent :deep(.p-tabpanels){padding:22px 0 0}.tab-heading{display:flex;justify-content:space-between;gap:15px;align-items:start;margin-bottom:20px}.tab-heading h3{font-size:16px;margin-bottom:8px}.tab-heading p{font-size:12px;line-height:1.65;color:var(--agent-muted)}.inline-empty{padding:40px 20px;text-align:center;background:#f8fafc;border:1px dashed var(--agent-line);color:var(--agent-muted);border-radius:6px;font-size:12px}.inline-empty>i{font-size:26px;display:block;margin-bottom:15px}.inline-empty strong{display:block;color:var(--agent-ink);margin-bottom:8px}.inline-empty p{line-height:1.8}.candidate-card{border:1px solid var(--agent-line);border-radius:6px;padding:18px;margin-bottom:16px}.candidate-heading{display:flex;justify-content:space-between;gap:10px;align-items:center;margin-bottom:18px}.candidate-heading h4{font-size:14px;overflow-wrap:anywhere}.candidate-heading h4 span{color:var(--agent-blue);font-size:12px;margin-right:9px}.candidate-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 16px}.calculation-note{font-size:11px;line-height:1.7;color:var(--agent-muted);background:var(--agent-soft);padding:11px 13px;margin:0 0 16px!important}.evidence-fields .field:last-child{margin-bottom:12px}.review-checkbox{display:flex;align-items:center;gap:9px;font-size:12px}.review-checkbox span{font-size:11px;color:var(--agent-muted);display:block;margin-top:3px}.rules-note{font-size:11px;margin-top:16px!important}.scenario-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin:20px 0}.scenario-grid article{padding:18px;border:1px solid var(--agent-line);border-radius:6px;min-width:0}.scenario-grid strong{display:block;font-size:26px;letter-spacing:-.04em;margin:18px 0 10px;overflow-wrap:anywhere}.scenario-grid p{font-size:11px;color:var(--agent-muted);line-height:1.7;margin-bottom:8px}.scenario-grid article>span:last-child{font-size:12px;font-weight:650}.conditional-scenario{background:#f4f8fc;border-color:#93b4d4!important}.decision-agent :deep(.p-datatable-table){font-size:12px;table-layout:auto;min-width:480px}.decision-agent :deep(.p-datatable-thead>tr>th){background:var(--agent-soft);font-size:11px;color:#63788f;border-color:var(--agent-line)}.decision-agent :deep(.p-datatable-tbody>tr>td){border-color:var(--agent-line);vertical-align:top;line-height:1.65;overflow-wrap:anywhere}.table-note{display:block;color:var(--agent-muted);font-size:11px;line-height:1.6;margin-top:4px!important}.effects-table{margin-top:22px}.source-details{margin:0;padding:0}.source-details>div{display:grid;grid-template-columns:120px minmax(0,1fr);padding:15px 0;border-bottom:1px solid var(--agent-line);gap:15px}.source-details dt{font-size:12px;color:var(--agent-muted)}.source-details dd{margin:0;font-size:12px;line-height:1.7;overflow-wrap:anywhere;white-space:pre-line}.evidence-title{margin:25px 0 16px!important;font-size:14px}.evidence-item{padding:16px;border:1px solid var(--agent-line);margin-bottom:12px;border-radius:6px}.evidence-item>div{display:flex;justify-content:space-between;align-items:center;gap:10px}.evidence-item strong{font-size:13px}.evidence-item p{font-size:12px;line-height:1.7;margin-top:12px;white-space:pre-line}.evidence-item>span{font-size:11px;color:var(--agent-muted);display:block;margin-top:8px;overflow-wrap:anywhere}.decision-agent :deep(.p-tag){font-size:10px;white-space:nowrap}.decision-agent :deep(.p-select-label){font-size:12px}.decision-agent :deep(.p-message-text){font-size:12px;line-height:1.7}
@media(min-width:1450px){.agent-workspace{grid-template-columns:320px minmax(0,1fr)}.analysis-area{padding:30px}.candidate-grid{grid-template-columns:repeat(3,minmax(0,1fr))}}
@media(max-width:1100px){.agent-workspace{grid-template-columns:260px minmax(0,1fr);gap:14px}.analysis-area{padding:18px}.goal-card,.progress-card{padding:16px}.sheet-conclusion{grid-template-columns:1fr}.gap-emphasis{border-left:0;border-top:1px solid var(--agent-line);padding:15px 0 0}.sheet-kpis{grid-template-columns:repeat(2,minmax(0,1fr))}.scenario-grid{grid-template-columns:1fr}.scenario-grid article{display:grid;grid-template-columns:1fr 1fr;gap:6px 14px}.scenario-grid strong{margin:0;text-align:right}.analysis-actions{align-items:start;flex-direction:column}.decision-agent :deep(.p-tab){padding:12px 10px}}
@media(max-width:760px){.agent-workspace{grid-template-columns:1fr}.agent-heading h1{font-size:23px}.agent-heading{gap:12px}.goal-rail{grid-template-columns:1fr}.progress-card{display:none}.analysis-library{padding:12px;flex-wrap:wrap}.analysis-library>div{min-width:180px}.analysis-area{padding:16px}.candidate-grid{grid-template-columns:1fr}.candidate-card{padding:14px}.sheet-heading h3{font-size:21px}.sheet-conclusion h4{font-size:18px}.baseline-strip strong{font-size:20px}.baseline-strip .period-text{font-size:15px}.baseline-strip>div{padding:0 10px}.tab-heading{flex-direction:column}.agent-empty{min-height:370px;padding:20px 8px}.agent-empty h2{font-size:23px}.analysis-header{flex-direction:column}.source-details>div{grid-template-columns:1fr;gap:6px}.analysis-actions>div{width:100%}.analysis-actions :deep(.p-button){flex-grow:1}.decision-agent :deep(.p-tablist-tab-list){flex-wrap:wrap}.evidence-item>div{align-items:start;flex-direction:column}}
</style>
