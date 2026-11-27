<script setup>
import { onBeforeUnmount, onMounted, reactive, ref, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSession } from '../stores/session'
import { dateTime, errorText, query, saveBlob } from '../lib/display'
import { formatMoney } from '../lib/decimal-display'
import { gapFilterSnapshot, matchesGapFilter } from '../lib/gap-result-filter'
import SubmissionReviews from './SubmissionReviews.vue'
import RequestEditor from './RequestEditor.vue'
import GapReviewTable from './GapReviewTable.vue'
import SubmissionVersionComparison from './SubmissionVersionComparison.vue'
import { draftStatus } from '../lib/request-workflow'
import MessageTemplateSelect from './MessageTemplateSelect.vue'
import SubmissionRejectionItems from './SubmissionRejectionItems.vue'
import ReviewPolicySettings from './ReviewPolicySettings.vue'
import SubmissionExplanationPolicy from './SubmissionExplanationPolicy.vue'

const route = useRoute()
const router = useRouter()
const session = useSession()
const requests = ref([])
const organizations = ref([])
const selectedId = ref(typeof route.query.requestId === 'string' ? route.query.requestId : '')
const selected = ref(null)
const publication = ref(null)
const thresholds = ref(null)
const loading = ref(false)
const detailLoading = ref(false)
const saving = ref(false)
const error = ref('')
const thresholdError = ref('')
const message = ref('')
const thresholdMessage = ref('')
const preview = ref(null)
const create = reactive({ year: Number(route.query.year) || new Date().getFullYear(), month: Number(route.query.month) || new Date().getMonth() + 1, dueAt: '', message: '', organizationIds: [] })
const criterion = reactive({ scenario: 'ACTUAL', percent: '', amount: '', percentEnabled: true, amountEnabled: true })
const appliedCriterion = ref(null)
const controller = new AbortController()
let detailController = new AbortController()
let generation = 0
let listGeneration = 0
const keys = new Map()
const expandedReplyId = ref('')
const replySubmission = ref(null)
const replyLoading = ref(false)
const replyDownloading = ref(false)
const replyError = ref('')
let replyController = new AbortController()
let replyGeneration = 0

/** 열린 조직 회신을 해제하고 늦은 응답을 차단한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 회신 GET 취소·상태 초기화.
 */
function closeReply() { replyGeneration++; replyController.abort(); replyController = new AbortController(); expandedReplyId.value = ''; replySubmission.value = null; replyLoading.value = false; replyDownloading.value = false; replyError.value = '' }

/** 조직행에서 실제 최종 제출본과 소명 회신을 펼친다.
 * Args: item 요청 대상 조직 DTO. Returns: Promise<void>. Raises: 없음(회신 영역 표시).
 * Side Effects: 이전 회신 조회 취소·제출 GET. 초안과 최종 회신을 구분한다.
 */
async function expandReply(item) {
  const close = expandedReplyId.value === String(item.organization.id)
  closeReply()
  if (close) return
  expandedReplyId.value = String(item.organization.id)
  if (!item.latestSubmittedSubmissionId) return
  await readReply(item.latestSubmittedSubmissionId)
}

/** 현재 펼친 회신의 최신 제출 토큰과 준비 상태를 읽는다.
 * Args: submissionId 제출 ID. Returns: Promise<void>. Raises: 없음(회신 영역 표시).
 * Side Effects: GET·회신 상태 갱신. 다른 조직을 펼친 뒤 도착한 응답은 버린다.
 */
async function readReply(submissionId) {
  const current = ++replyGeneration
  replyLoading.value = true; replyError.value = ''
  try { const result = await session.request(`/api/v1/submissions/${submissionId}`, { signal: replyController.signal }); if (current === replyGeneration) replySubmission.value = result }
  catch (cause) { if (current === replyGeneration && cause.name !== 'AbortError') replyError.value = errorText(cause) }
  finally { if (current === replyGeneration) replyLoading.value = false }
}

/** 소명 검토 이후 펼친 제출의 토큰과 현재 Gap 목록을 함께 새로 읽는다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(각 영역 표시).
 * Side Effects: GET·회신/Gap/알림 갱신. 제출 승인에는 최신 토큰을 사용한다.
 */
async function refreshReply() {
  const id = replySubmission.value?.id
  if (!id) return
  await Promise.allSettled([readReply(id), String(currentId.value) === String(id) ? loadComparison() : Promise.resolve(), session.refreshUnread()])
}

/** 펼친 최종 회신의 원본을 사용자의 클릭으로 내려받는다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(회신 영역 표시).
 * Side Effects: 원본 GET·브라우저 다운로드. 서버 파일은 수정하지 않는다.
 */
async function downloadReply() {
  if (!replySubmission.value || replyDownloading.value) return
  const current = replyGeneration; const item = replySubmission.value
  replyDownloading.value = true; replyError.value = ''
  try { const file = await session.request(`/api/v1/submissions/${item.id}/file`, { blob: true, signal: replyController.signal }); if (current === replyGeneration) saveBlob(file, item.originalFilename) }
  catch (cause) { if (current === replyGeneration && cause.name !== 'AbortError') replyError.value = errorText(cause) }
  finally { if (current === replyGeneration) replyDownloading.value = false }
}

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
  closeReply()
  detailController.abort(); detailController = new AbortController()
  const current = ++generation
  selected.value = null; publication.value = null; preview.value = null; detailLoading.value = true; error.value = ''
  try {
    const detail = await session.request(`/api/v1/submission-requests/${selectedId.value}`, { signal: detailController.signal })
    if (current !== generation) return
    if (detail.type !== 'MONTHLY_ACTUAL') throw new Error('월간 실적·추정 요청을 선택해 주세요.')
    const periods = await session.request(`/api/v1/master-periods?year=${detail.year}`, { signal: detailController.signal })
    if (current !== generation) return
    selected.value = detail
    organizationId.value = detail.targets.some(target => target.selected !== false && target.organization.id === organizationId.value) ? organizationId.value : detail.targets.find(target => target.selected !== false)?.organization.id || ''
    comparisonMonth.value = detail.month
    await loadWorkspace()
    if (current !== generation) return
    for (const item of periods.items) if (item.month === detail.month) publication.value = item
  } catch (cause) { if (current === generation && cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { if (current === generation) detailLoading.value = false }
}

/** 확정 연간 계획에 근거한 월간 전체 조직 요청을 생성한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: 요청 POST·성공 후 현황 조회; 같은 본문은 같은 키로 재시도.
 */
async function createRequest() {
  if (saving.value) return
  if (!create.organizationIds.length) { error.value = '제출을 요청할 조직을 한 곳 이상 선택해 주세요.'; return }
  const body = JSON.stringify({ type: 'MONTHLY_ACTUAL', year: Number(create.year), month: Number(create.month), dueAt: create.dueAt ? new Date(create.dueAt).toISOString() : null, message: create.message || null, organizationIds: create.organizationIds })
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
  const comparison = { ACTUAL_PLAN: 'ACTUAL_VS_PLAN', ESTIMATE_PREVIOUS: 'ESTIMATE_VS_PREVIOUS', ESTIMATE_PLAN: 'ESTIMATE_VS_PLAN' }[comparisonType.value]
  const current = thresholds.value?.items?.find(item => item.comparison === comparison) || thresholds.value?.[criterion.scenario === 'ACTUAL' ? 'actual' : 'estimate']
  if (!current) return
  criterion.percent = current?.percent != null && current.percent !== '' ? formatMoney(current.percent).replaceAll(',', '') : ''
  criterion.percentEnabled = current?.percentEnabled !== false
  criterion.amountEnabled = current?.amountEnabled !== false
  criterion.amount = current?.amount != null && current.amount !== '' ? formatMoney(current.amount).replaceAll(',', '') : ''
  preview.value = null
}

/** 입력 중인 조건을 미리 보거나 현재 표에 적용한다. 서버 기준·소명 상태는 변경하지 않는다. */
function thresholdCommand(apply = false) {
  thresholdError.value = ''; thresholdMessage.value = ''
  try {
    const snapshot = gapFilterSnapshot(criterion)
    if (apply) {
      appliedCriterion.value = snapshot; selectedReviews.value = []; preview.value = null
      if (!criteriaRows.value.some(row => row.workflowState === activeTab.value)) activeTab.value = 'ALL'
      thresholdMessage.value = '표시 조건을 적용했습니다. 소명 요청 대상은 항목을 선택해 정합니다.'
    } else preview.value = { evaluatedItemCount: displayRows.value.length, matchedItemCount: displayRows.value.filter(row => matchesGapFilter(row, snapshot)).length }
  } catch (cause) { thresholdError.value = errorText(cause) }
}

/** 조직의 최신 제출본 또는 요청별 이력으로 이동하는 경로를 만든다.
 * Args: target 대상 조직 DTO. Returns: 라우트 객체. Raises: 없음. Side Effects: 없음.
 */
function targetLink(target) { return { path: target.latestSubmittedSubmissionId ? `/submissions/${target.latestSubmittedSubmissionId}` : '/history', query: { from: 'gap', requestId: selected.value.id, organizationId: target.organization.id } } }

/** 현재 업로드 초안의 파싱 진단을 완료 제출본과 별도로 연결한다.
 * Args: target 조직 대상 DTO. Returns: 초안 상세 라우트. Raises: 없음. Side Effects: 없음.
 */
function draftLink(target) { return { path: `/submissions/${target.latestDraftSubmissionId}`, query: { from: 'gap', requestId: selected.value.id, organizationId: target.organization.id } } }

/** 실제 제출 상태를 한국어로 표시한다.
 * Args: value 최신 상태. Returns: 표시 이름. Raises: 없음. Side Effects: 없음.
 */
function statusLabel(value) { return ({ DRAFT: '미확정 초안', PENDING: '검토 대기', APPROVED: '승인 완료', REJECTED: '반려', NOT_SUBMITTED: '미제출' })[value] || '미제출' }

/** 요청과 기준을 독립적으로 초기 조회한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(각 영역 표시). Side Effects: 두 조회 시작.
 */
async function initialize() { await Promise.allSettled([loadRequests(), loadThresholds(), loadCatalog(), loadOrganizations()]) }

/** 로그인 방식과 관계없이 실제 활성 조직을 조회한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: GET·요청 대상 목록 갱신.
 */
async function loadOrganizations() {
  try {
    const items = []; let cursor = null
    do { const page = await session.request(`/api/v1/organizations${query({ active: true, limit: 100, cursor })}`, { signal: controller.signal }); items.push(...page.items); cursor = page.nextCursor } while (cursor && !controller.signal.aborted)
    if (!controller.signal.aborted) { organizations.value = items; create.organizationIds = items.map(item => item.id) }
  } catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
}

/** 화면 이탈 시 조회·생성 응답을 중단한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소·세대 무효화.
 */
function dispose() { generation++; listGeneration++; replyGeneration++; controller.abort(); detailController.abort(); workspaceController.abort(); replyController.abort() }
onMounted(initialize)
onBeforeUnmount(dispose)

const organizationId = ref(typeof route.query.organizationId === 'string' ? route.query.organizationId : '')
const comparisonMonth = ref(1)
const comparisonType = ref('ACTUAL_PLAN')
const currentId = ref('')
const baselineId = ref('')
const versions = ref([])
const currentSubmission = ref(null)
const reviewRows = ref([])
const comparison = ref(null)
const catalog = ref([])
const workspaceLoading = ref(false)
const workspaceError = ref('')
const unit = ref('THOUSAND')
const activeTab = ref(route.query.workspace ? 'SELECTED' : route.query.reportItemId ? 'ALL' : 'NEEDS_REVIEW')
const search = ref('')
const focusedReportItemId = ref(typeof route.query.reportItemId === 'string' ? route.query.reportItemId : '')
const sort = ref('amount-desc')
const selectedReviews = ref([])
const reviewDialog = ref(null)
const actionDialog = ref(null)
const requestDialog = ref(null)
const activeReview = ref('')
const activeSourceItem = ref(null)
const action = ref('')
const actionNote = ref('')
const actionDue = ref('')
const actionBusy = ref(false)
const actionError = ref('')
const detailOpen = ref(false)
let workspaceController = new AbortController()
let workspaceGeneration = 0
let planSources = []
const tabs = [
  { key: 'NEEDS_REVIEW', label: '검토 필요' }, { key: 'SELECTED', label: '검토 완료' },
  { key: 'IN_PROGRESS', label: '소명 진행중' }, { key: 'RE_REVIEW', label: '재검토 필요' },
  { key: 'ACCEPTED', label: '소명 승인 완료' }, { key: 'EXCLUDED', label: '제외됨' }, { key: 'ALL', label: '전체 항목' },
]
const target = computed(() => selected.value?.targets.find(item => item.organization.id === organizationId.value))
/** 현재 경로의 선정 항목 작업공간을 찾는다. Args: 없음. Returns: 작업공간 또는 null. Raises: 없음. Side Effects: 없음. */
const workspace = computed(function currentReviewWorkspace() { return session.reviewWorkspaces.find(item => item.key === route.query.workspace) || null })
const visibleTargets = computed(() => selected.value?.targets.filter(item => item.selected !== false) || [])
const selectedRows = computed(() => reviewRows.value.filter(item => selectedReviews.value.includes(item.id)))
/** 선택한 비교 행을 기존 검토 ID 유무와 관계없이 찾는다. Args: 없음. Returns: 비교 행 목록. Raises: 없음. Side Effects: 없음. */
const selectedComparisonRows = computed(function comparisonSelection() { return displayRows.value.filter(item => selectedReviews.value.includes(item.selectionId || item.review?.id)) })
/** 고정 소명 기준과 다른 비교 선택이 포함됐는지 판정한다. Args: 없음. Returns: 명시 비교 요청 여부. Raises: 없음. Side Effects: 없음. */
const customComparisonSelected = computed(function selectedCustomComparison() { return selectedComparisonRows.value.some(item => item.customComparison) })
const nextSelectionAction = computed(() => selectedRows.value.every(item => ['NEEDS_REVIEW', 'SELECTED'].includes(item.workflowState)) && selectedRows.value.some(item => item.workflowState === 'NEEDS_REVIEW') ? 'SELECT' : 'REQUEST_EXPLANATION')
const canAct = computed(() => currentSubmission.value?.status === 'PENDING' && String(currentSubmission.value?.requestId) === String(selected.value?.id))
const displayRows = computed(() => {
  const joined = new Map(reviewRows.value.filter(item => item.month === Number(comparisonMonth.value) && item.scenario === scenario()).map(item => [item.reportItem.id, item]))
  const values = comparison.value?.items || reviewRows.value.filter(item => item.month === Number(comparisonMonth.value) && item.scenario === scenario()).map(item => {
    const comp = item.comparisons?.find(value => value.basis === (comparisonType.value === 'ESTIMATE_PREVIOUS' ? 'PREVIOUS' : 'PLAN'))
    return { ...comp, reportItem: item.reportItem, month: item.month, scenario: item.scenario, currentAmount: item.currentAmount }
  })
  return values.map(value => {
    const review = joined.get(value.reportItem.id)
    const fixed = review?.comparisons?.some(item => String(item.baselineSubmissionId) === baselineId.value && item.baselineScenario === baselineScenario())
    const customComparison = !!baselineId.value && (!review || !fixed)
    const baseline = versions.value.find(item => item.id === baselineId.value)
    return { ...value, review, selectionId: customComparison ? `compare:${value.reportItem.id}` : review?.id, customComparison, workflowState: review?.workflowState || 'OPTIONAL', actionable: canAct.value && (customComparison ? baseline?.status !== 'DRAFT' && value.valueStatus === 'AVAILABLE' : !!review) }
  })
})
const criteriaRows = computed(() => displayRows.value.filter(row => (!appliedCriterion.value || matchesGapFilter(row, appliedCriterion.value)) && (!workspace.value || workspace.value.reviewItemIds.includes(row.review?.id)) && (!focusedReportItemId.value || row.reportItem.id === focusedReportItemId.value)))
const tabCounts = computed(() => Object.fromEntries(tabs.map(tab => [tab.key, criteriaRows.value.filter(row => tab.key === 'ALL' || row.workflowState === tab.key).length])))
const filteredRows = computed(() => criteriaRows.value.filter(row => (activeTab.value === 'ALL' || row.workflowState === activeTab.value) && (!search.value || row.reportItem.name.includes(search.value) || row.reportItem.code.includes(search.value))))
const appliedCriteriaLabel = computed(() => {
  const current = appliedCriterion.value
  if (!current) return '표시 조건 미적용 · 기준 적용을 눌러 조회 결과를 거르세요.'
  const parts = []
  if (current.percentEnabled) parts.push(`차이율 절댓값 ${formatMoney(current.percent)}% 이상`)
  if (current.amountEnabled) parts.push(`차액 절댓값 ${formatMoney(current.amount)}원 이상`)
  return `적용된 표시 조건: ${parts.length ? parts.join(' · ') : '필터 끔 · 금액이 바뀐 전체 항목'}`
})
/** 탭·검색·조회 조건으로 감춰진 항목은 일괄 처리에 포함하지 않는다. */
watch(filteredRows, rows => {
  const visible = new Set(rows.map(row => row.selectionId || row.review?.id).filter(Boolean))
  selectedReviews.value = selectedReviews.value.filter(id => visible.has(id))
}, { flush: 'sync' })
watch(criterion, () => { preview.value = null })
const targetName = computed(() => target.value?.organization.name || '사업조직')

/** 비교 필터의 현재 시나리오를 반환한다. Args: 없음. Returns: ACTUAL/ESTIMATE. Raises: 없음. Side Effects: 없음. */
function scenario() { return comparisonType.value === 'ACTUAL_PLAN' ? 'ACTUAL' : 'ESTIMATE' }
/** 비교 필터의 기준 시나리오를 반환한다. Args: 없음. Returns: PLAN/ESTIMATE. Raises: 없음. Side Effects: 없음. */
function baselineScenario() { return comparisonType.value === 'ESTIMATE_PREVIOUS' ? 'ESTIMATE' : 'PLAN' }
/** API 페이지를 빠짐없이 읽는다. Args: path API, params 조회, signal 취소. Returns: 전체 items. Raises: API오류. Side Effects: GET. */
async function allPages(path, params = {}, signal = controller.signal) {
  const values = []; let cursor = null
  do { const page = await session.request(path + query({ ...params, limit: 100, cursor }), { signal }); values.push(...page.items); cursor = page.nextCursor } while (cursor)
  return values
}
/** 답안지의 실제 계층을 읽는다. Args: 없음. Returns: Promise<void>. Raises: 없음(화면표시). Side Effects: GET. */
async function loadCatalog() {
  try { catalog.value = await allPages('/api/v1/report-items', { active: 'true' }) }
  catch (cause) { if (cause.name !== 'AbortError') workspaceError.value = errorText(cause) }
}
/** 선택 조직의 제출버전·고정 계획 출처를 읽는다. Args: 없음. Returns: Promise<void>. Raises: 없음(화면표시). Side Effects: 조회·선택 초기화. */
async function loadWorkspace() {
  workspaceController.abort(); workspaceController = new AbortController(); const generation = ++workspaceGeneration
  versions.value = []; currentSubmission.value = null; reviewRows.value = []; comparison.value = null; selectedReviews.value = []; workspaceError.value = ''
  if (!selected.value || !organizationId.value) return
  workspaceLoading.value = true
  try {
    const [history, plan] = await Promise.all([
      allPages('/api/v1/submissions', { organizationId: organizationId.value, year: selected.value.year }, workspaceController.signal),
      session.request('/api/v1/annual-plans/' + selected.value.year, { signal: workspaceController.signal }),
    ])
    if (generation !== workspaceGeneration) return
    versions.value = history; planSources = plan.sources || []
    currentId.value = workspace.value?.submissionId || target.value?.latestSubmittedSubmissionId || target.value?.latestDraftSubmissionId || ''
    if (workspace.value) { comparisonMonth.value = workspace.value.month; comparisonType.value = workspace.value.comparisonType }
    chooseBaseline()
    await loadComparison()
    if (generation + 1 !== workspaceGeneration) return
    if (typeof route.query.reviewItemId === 'string') {
      const item = await session.request('/api/v1/review-items/' + route.query.reviewItemId, { signal: workspaceController.signal })
      if (generation + 1 !== workspaceGeneration) return
      if (item.organizationId !== organizationId.value) { organizationId.value = item.organizationId; await loadWorkspace(); return }
      if (currentId.value !== item.submissionId || comparisonMonth.value !== item.month || scenario() !== item.scenario) {
        currentId.value = item.submissionId; comparisonMonth.value = item.month; comparisonType.value = item.scenario === 'ESTIMATE' ? 'ESTIMATE_PLAN' : 'ACTUAL_PLAN'; chooseBaseline()
        const expected = workspaceGeneration + 1; await loadComparison(); if (expected !== workspaceGeneration) return
      }
      openReview(item.id)
    }
  } catch (cause) { if (generation === workspaceGeneration && cause.name !== 'AbortError') workspaceError.value = errorText(cause) }
  finally { if (generation === workspaceGeneration) workspaceLoading.value = false }
}
/** 확정 계획 또는 전월 승인본을 기본 비교로 선택한다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 기준 선택. */
function chooseBaseline() {
  if (comparisonType.value !== 'ESTIMATE_PREVIOUS') baselineId.value = String(planSources.find(item => item.organizationId === organizationId.value)?.confirmedPlanSubmissionId || '')
  else baselineId.value = versions.value.find(item => item.type === 'MONTHLY_ACTUAL' && item.month === selected.value.month - 1 && item.status === 'APPROVED')?.id || ''
}
/** 현재·비교 버전의 실제 값과 소명 상태를 읽는다. Args: 없음. Returns: Promise<void>. Raises: 없음(화면표시). Side Effects: GET·표 갱신. */
async function loadComparison() {
  const generation = ++workspaceGeneration; selectedReviews.value = []; comparison.value = null; reviewRows.value = []; workspaceError.value = ''
  if (!currentId.value) { currentSubmission.value = null; workspaceLoading.value = false; return }
  workspaceLoading.value = true
  try {
    const [submission, reviews] = await Promise.all([
      session.request('/api/v1/submissions/' + currentId.value, { signal: workspaceController.signal }),
      allPages('/api/v1/review-items', { submissionId: currentId.value }, workspaceController.signal),
    ])
    if (generation !== workspaceGeneration) return
    currentSubmission.value = submission; reviewRows.value = reviews
    if (baselineId.value) {
      const result = await session.request('/api/v1/submissions/' + currentId.value + '/comparison' + query({ baselineSubmissionId: baselineId.value, scenario: scenario(), baselineScenario: baselineScenario(), month: comparisonMonth.value }), { signal: workspaceController.signal })
      if (generation !== workspaceGeneration) return
      comparison.value = result
    }
    if (generation !== workspaceGeneration) return
    if (!displayRows.value.some(row => row.workflowState === activeTab.value)) activeTab.value = 'ALL'
    if (workspace.value) selectedReviews.value = filteredRows.value.filter(row => row.workflowState === 'SELECTED' && workspace.value.reviewItemIds.includes(row.review?.id)).map(row => row.review.id)
  } catch (cause) { if (generation === workspaceGeneration && cause.name !== 'AbortError') workspaceError.value = errorText(cause) }
  finally { if (generation === workspaceGeneration) workspaceLoading.value = false }
}
/** 비교 유형에 맞춰 기준과 월을 바꾼다. Args: 없음. Returns: Promise<void>. Raises: 없음. Side Effects: 선택·GET. */
async function changeComparisonType() {
  criterion.scenario = scenario(); changeScenario()
  comparisonMonth.value = scenario() === 'ACTUAL' ? selected.value?.month || 1 : Math.min(12, (selected.value?.month || 1) + 1)
  chooseBaseline(); await loadComparison()
}
/** 항목 체크 상태를 변경한다. Args: id 검토ID, checked 선택여부. Returns: 없음. Raises: 없음. Side Effects: 선택 목록 갱신. */
function toggleReview(id, checked) { selectedReviews.value = checked ? [...new Set([...selectedReviews.value, id])] : selectedReviews.value.filter(value => value !== id) }
/** 선택한 검토의 실제 소명·근거창을 연다. Args: id 검토ID. Returns: 없음. Raises: 없음. Side Effects: 모달 표시. */
function openReview(id) { activeReview.value = id; activeSourceItem.value = reviewRows.value.find(item => item.id === id)?.reportItem || null; detailOpen.value = true; reviewDialog.value?.showModal() }
/** 소명 대상이 아닌 보고 항목도 실제 원천 버전 비교를 연다.
 * Args: item 보고 항목 DTO. Returns: 없음. Raises: 없음. Side Effects: 읽기 전용 모달 표시.
 */
function openSource(item) { activeReview.value = ''; activeSourceItem.value = item; detailOpen.value = true; reviewDialog.value?.showModal() }
/** 소명 상세창을 닫는다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 모달·마운트 해제. */
function closeReview() { reviewDialog.value?.close(); detailOpen.value = false }
/** 제외·일괄 요청 입력창을 연다. Args: next action. Returns: 없음. Raises: 없음. Side Effects: 입력 초기화·모달. */
function beginAction(next) { action.value = next; actionNote.value = ''; actionDue.value = ''; actionError.value = ''; actionDialog.value?.showModal() }
/** 명시한 항목의 검토 동작을 원자적으로 요청한다. Args: 없음. Returns: Promise<void>. Raises: 없음(화면표시). Side Effects: POST·표 재조회. */
async function submitAction() {
  if (actionBusy.value || (!selectedRows.value.length && !selectedComparisonRows.value.length)) return
  if (selectedReviews.value.length > 100) { actionError.value = '한 번에 최대 100개 항목을 선택할 수 있습니다.'; return }
  actionBusy.value = true; actionError.value = ''
  const custom = customComparisonSelected.value
  const payload = custom ? { expectedStateToken: currentSubmission.value.stateToken, baselineSubmissionId: baselineId.value, baselineScenario: baselineScenario(), scenario: scenario(), month: Number(comparisonMonth.value), reportItemIds: selectedComparisonRows.value.map(item => item.reportItem.id), note: actionNote.value, dueAt: actionDue.value ? new Date(actionDue.value).toISOString() : null } : { action: action.value, items: selectedRows.value.map(row => ({ reviewItemId: row.id, expectedStateToken: row.stateToken })), note: actionNote.value || null, dueAt: actionDue.value ? new Date(actionDue.value).toISOString() : null }
  const body = JSON.stringify(payload)
  const path = custom ? `/api/v1/submissions/${currentId.value}/comparison/explanation-requests` : '/api/v1/review-items/actions'
  if (!keys.has(body)) keys.set(body, crypto.randomUUID())
  try {
    await session.request(path, { method: 'POST', body, headers: { 'Idempotency-Key': keys.get(body) }, signal: controller.signal })
    keys.delete(body); actionDialog.value?.close(); message.value = action.value === 'EXCLUDE' ? '선택 항목을 제외했습니다.' : action.value === 'SELECT' ? '검토 대상을 선정했습니다.' : '선택 항목의 소명을 요청했습니다.'
    const workspaceKey = `${selected.value.id}:${organizationId.value}`
    if (action.value === 'SELECT') {
      session.openReviewWorkspace({ requestId: selected.value.id, organizationId: organizationId.value, organizationName: targetName.value, submissionId: currentId.value, reviewItemIds: selectedRows.value.map(item => item.id), month: Number(comparisonMonth.value), comparisonType: comparisonType.value })
      await router.push({ path: '/planning/gap', query: { requestId: selected.value.id, organizationId: organizationId.value, workspace: workspaceKey } }); return
    }
    session.finishReviewWorkspace(workspaceKey, selectedRows.value.map(item => item.id))
    if (route.query.workspace && !session.reviewWorkspaces.some(item => item.key === route.query.workspace)) { await router.push({ path: '/planning/gap', query: { requestId: selected.value.id, organizationId: organizationId.value } }); return }
    activeTab.value = ({ EXCLUDE: 'EXCLUDED', SELECT: 'SELECTED', REQUEST_EXPLANATION: 'IN_PROGRESS' })[action.value]; await loadComparison(); await session.refreshUnread()
  } catch (cause) { if (cause.name !== 'AbortError') actionError.value = errorText(cause) }
  finally { actionBusy.value = false }
}
/** 검토 완료 후 본문 상태와 배지를 갱신한다. Args: 없음. Returns: Promise<void>. Raises: 없음. Side Effects: GET. */
async function reviewed() { await loadComparison(); await session.refreshUnread() }
/** 제출 버전을 구분해 표시한다. Args: item 제출DTO. Returns: 라벨. Raises: 없음. Side Effects: 없음. */
function versionLabel(item) { return (item.versionLabel || '신규 업로드 · 미확정') + ' · ' + ({ PLAN: '계획', ANNUAL_PLAN: '연간 계획', MONTHLY_ACTUAL: '실적·추정' }[item.type] || '') + ' · ' + statusLabel(item.status) }
/** 요청 관리 모달을 연다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 모달 표시. */
function openRequest() { requestDialog.value?.showModal() }

/** 작업공간의 현재 선정 항목 전체에 소명을 요청할 입력창을 연다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 체크 선택·모달 변경; 요청은 확인 후 전송.
 */
function requestWorkspace() { selectedReviews.value = filteredRows.value.filter(item => workspace.value?.reviewItemIds.includes(item.review?.id) && item.workflowState === 'SELECTED').map(item => item.review.id); if (selectedReviews.value.length) beginAction('REQUEST_EXPLANATION') }

/** 현재 선택한 두 버전의 실제 비교표를 내려받는다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: GET·사용자가 요청한 파일 다운로드.
 */
async function downloadComparison() {
  if (!currentId.value || !baselineId.value) return
  try { const blob = await session.request('/api/v1/submissions/' + currentId.value + '/comparison/file' + query({ baselineSubmissionId: baselineId.value, scenario: scenario(), baselineScenario: baselineScenario(), month: comparisonMonth.value }), { blob: true, signal: controller.signal }); saveBlob(blob, targetName.value + '_비교_' + comparisonMonth.value + '월.xlsx') }
  catch (cause) { if (cause.name !== 'AbortError') workspaceError.value = errorText(cause) }
}

</script>



<template>
  <div class="gap-page">
    <header class="page-heading"><h1>월간 시트 관리</h1><div class="button-row"><button class="button" :disabled="!comparison || workspaceLoading" @click="downloadComparison">엑셀 다운로드</button><button class="button" @click="openRequest">요청·제출 현황</button><RouterLink v-if="publication?.latestMasterId" :to="'/planning/masters/' + publication.latestMasterId" class="button">마스터 확인</RouterLink></div></header>
    <section v-if="workspace" class="panel workspace-heading"><div class="section-heading"><div><h2>{{ workspace.organizationName }} · 선택 항목 검토</h2><p class="muted">선정 {{ workspace.reviewItemIds.length }}항목입니다. 조회 월·비교 필터로 항목을 확인한 뒤 소명을 요청하세요. 요청한 항목은 이 작업공간에서 제거됩니다.</p></div><RouterLink :to="{ path: '/planning/gap', query: { requestId: selectedId, organizationId } }" class="text-button">전체 월간 시트</RouterLink></div><button class="button primary" :disabled="workspaceLoading || !canAct || !filteredRows.some(item => item.workflowState === 'SELECTED')" @click="requestWorkspace">현재 표시된 선정 항목 소명 요청</button></section><div class="sheet-selectors"><label>제출 요청<select v-model="selectedId" :disabled="loading || !!workspace" @change="loadDetail"><option v-if="!requests.length" value="">등록된 요청 없음</option><option v-for="item in requests" :key="item.id" :value="String(item.id)">{{ item.year }}년 {{ item.month }}월 실적·추정</option></select></label><label>기준 대상<select v-model="currentId" :disabled="workspaceLoading || !versions.length || !!workspace" @change="loadComparison"><option value="">조직 제출본 선택</option><option v-for="item in versions.filter(item => item.type === 'MONTHLY_ACTUAL')" :key="item.id" :value="item.id">{{ versionLabel(item) }}</option></select></label><label>비교 대상<select v-model="baselineId" :disabled="workspaceLoading || !versions.length || !!workspace" @change="loadComparison"><option value="">선택하세요</option><option v-for="item in versions" :key="item.id" :value="item.id">{{ versionLabel(item) }}</option></select></label></div>
    <div v-if="error" class="alert error" role="alert">{{ error }}</div><p v-if="message" class="alert success" role="status">{{ message }}</p>
    <section class="panel gap-criteria" aria-labelledby="criteria-title">
      <form class="criteria-controls" @submit.prevent="thresholdCommand(true)">
        <div class="criteria-title"><h2 id="criteria-title">Gap 기준</h2><small>중복 선택 가능</small></div>
        <label class="criterion-toggle"><input v-model="criterion.percentEnabled" type="checkbox" :disabled="workspaceLoading" />차이율</label><label class="criterion-input"><span class="visually-hidden">차이율</span><input v-model="criterion.percent" inputmode="decimal" :disabled="workspaceLoading || !criterion.percentEnabled" required /><span>%</span></label>
        <label class="criterion-toggle"><input v-model="criterion.amountEnabled" type="checkbox" :disabled="workspaceLoading" />최소 금액</label><label class="criterion-input"><span class="visually-hidden">최소 금액 원</span><input v-model="criterion.amount" inputmode="decimal" :disabled="workspaceLoading || !criterion.amountEnabled" required /><span>원</span></label>
        <label class="comparison-filter"><span class="visually-hidden">비교 필터</span><select v-model="comparisonType" :disabled="workspaceLoading" @change="changeComparisonType"><option value="ACTUAL_PLAN">실적 vs 계획</option><option value="ESTIMATE_PREVIOUS">추정 vs 추정</option><option value="ESTIMATE_PLAN">추정 vs 계획</option></select></label>
        <button class="button dark" :disabled="workspaceLoading">기준 적용</button>
      </form>
      <div class="criteria-help"><span>켜진 조건을 모두 충족한 항목을 표시합니다. 둘 다 끄면 금액이 바뀐 전체 항목을 표시합니다.</span><button class="text-button" :disabled="workspaceLoading || !selected" @click="thresholdCommand(false)">결과 미리보기</button></div>
      <p v-if="thresholdError" class="alert error" role="alert">{{ thresholdError }}</p><p v-if="thresholdMessage" class="notice" role="status">{{ thresholdMessage }}</p><p v-if="preview" class="notice">현재 비교 {{ preview.evaluatedItemCount }}항목 중 {{ preview.matchedItemCount }}항목이 입력한 조건을 충족합니다. 기준 적용을 누르면 표에 반영됩니다.</p>
    </section>
    <ReviewPolicySettings :request-id="selected?.id || ''" @changed="loadComparison" />
    <ol class="review-pipeline" aria-label="검토 진행 단계"><li v-for="(stage, index) in tabs.slice(0,5)" :key="stage.key" :class="'stage-' + (index + 1)"><button @click="activeTab = stage.key"><b>{{ index + 1 }}</b><div><strong>{{ stage.label }}</strong><small>{{ ['차이 항목 확인', '검토 결과 확정', '조직 소명 수집', '보완 내용 재검토', '소명 및 확정'][index] }}</small></div></button></li></ol>
    <section class="panel review-board">
      <div class="review-tabs" role="tablist" aria-label="검토 상태"><button v-for="tab in tabs" :key="tab.key" role="tab" :aria-selected="activeTab === tab.key" :class="{ active: activeTab === tab.key }" @click="activeTab = tab.key">{{ tab.label }} <span>{{ tabCounts[tab.key] }}</span></button></div>
      <div class="review-filters"><label>조직<select v-model="organizationId" :disabled="workspaceLoading || !!workspace" @change="loadWorkspace"><option v-if="!visibleTargets.length" value="">요청 대상 없음</option><option v-for="item in visibleTargets" :key="item.organization.id" :value="item.organization.id">{{ item.organization.name }}</option></select></label><label>단위<select v-model="unit"><option value="THOUSAND">천원</option><option value="HUNDRED_MILLION">억원</option><option value="KRW">원</option></select></label><label>조회 월<select v-model="comparisonMonth" :disabled="workspaceLoading" @change="loadComparison"><option v-for="month in 12" :key="month" :value="month">{{ month }}월</option></select></label><label class="search"><span class="visually-hidden">항목 검색</span><input v-model="search" type="search" placeholder="항목 검색" /></label><label>정렬<select v-model="sort"><option value="amount-desc">차액 큰 순</option><option value="amount-asc">차액 작은 순</option><option value="rate-desc">차이율 큰 순</option><option value="rate-asc">차이율 작은 순</option></select></label></div>
      <p v-if="focusedReportItemId" class="notice">홈에서 선택한 항목만 표시합니다.<span v-if="!route.query.organizationId"> 전사 합계의 조직별 값을 확인할 조직을 선택해 주세요.</span> <button class="text-button" @click="focusedReportItemId = ''">전체 항목 보기</button></p>
      <p v-if="workspaceError" class="alert error" role="alert">{{ workspaceError }} <button class="text-button" @click="loadComparison">다시 조회</button></p>
      <p v-if="loading || detailLoading || workspaceLoading" class="empty" role="status">제출 버전과 비교 항목을 불러오는 중…</p>
      <template v-else>
        <p class="applied-criteria" role="status">{{ appliedCriteriaLabel }}</p>
        <GapReviewTable :rows="filteredRows" :catalog="catalog" :unit="unit" :sort="sort" :selected="selectedReviews" :comparison-type="comparisonType" @toggle="toggleReview" @detail="openReview" @source="openSource" />
        <p class="review-count">{{ targetName }} · {{ filteredRows.length }}항목<span v-if="currentSubmission"> · {{ currentSubmission.versionLabel || '신규 업로드 · 미확정' }} · {{ statusLabel(currentSubmission.status) }}</span></p>
        <SubmissionExplanationPolicy v-if="currentSubmission" :submission="currentSubmission" /><div v-if="currentSubmission" class="review-context"><p>다른 버전을 선택한 비교에서도 항목을 체크해 소명을 요청할 수 있습니다. 이번 요청의 비교 근거를 보존하며 제출 당시 자동 검증 기준은 유지됩니다.</p><RouterLink :to="{ path: '/submissions/' + currentSubmission.id, query: { from: 'gap', requestId: selectedId, organizationId } }" class="text-button">{{ currentSubmission.status === 'PENDING' ? '제출 승인·반려 검토 →' : '제출 내역 확인 →' }}</RouterLink></div>
      </template>
    </section>
    <footer class="selection-bar"><strong>{{ selectedReviews.length }}건 선택</strong><div class="button-row"><button class="button clear-selection" :disabled="!selectedReviews.length" @click="selectedReviews = []">선택 해제</button><button class="button exclude-selection" :disabled="!selectedReviews.length || !canAct || customComparisonSelected" @click="beginAction('EXCLUDE')">제외</button><button class="button primary" :disabled="!selectedReviews.length || !canAct" @click="beginAction(customComparisonSelected ? 'REQUEST_EXPLANATION' : nextSelectionAction)">{{ customComparisonSelected ? '선택 비교 항목 소명 요청 →' : nextSelectionAction === 'REQUEST_EXPLANATION' ? '일괄 소명 요청 →' : '선택 항목 검토하기 →' }}</button></div></footer>
    <dialog ref="reviewDialog" class="request-dialog gap-detail-dialog" aria-label="Gap 상세 내역" @close="detailOpen = false"><div class="section-heading"><h2>Gap 상세 내역 · {{ targetName }}</h2><button class="dialog-close" aria-label="닫기" @click="closeReview">×</button></div><SubmissionReviews v-if="detailOpen && currentSubmission && activeReview" :key="activeReview" :submission="currentSubmission" :initial-review-id="activeReview" :detail-only="true" @changed="reviewed" /><SubmissionVersionComparison v-if="detailOpen && currentSubmission && activeSourceItem" :key="currentSubmission.id + '-' + activeSourceItem.id" :submission-id="currentSubmission.id" :report-item-id="activeSourceItem.id" :report-item-kind="activeSourceItem.kind" :year="selected.year" :month="Number(comparisonMonth)" :scenario="scenario()" :unit="unit" /></dialog>
    <dialog ref="actionDialog" class="request-dialog" aria-labelledby="action-title"><form @submit.prevent="submitAction"><div class="section-heading"><h2 id="action-title">{{ action === 'EXCLUDE' ? '선택 항목 제외' : action === 'SELECT' ? '검토 대상 선정' : '소명 요청 보내기' }}</h2><button type="button" class="dialog-close" aria-label="닫기" :disabled="actionBusy" @click="actionDialog.close()">×</button></div><p class="muted">{{ targetName }} · 선택 {{ customComparisonSelected ? selectedComparisonRows.length : selectedRows.length }}항목</p><p v-if="customComparisonSelected" class="notice">지금 선택한 두 버전의 비교값을 이번 소명 요청의 근거로 전달합니다.</p><ul class="action-items"><li v-for="item in (customComparisonSelected ? selectedComparisonRows : selectedRows)" :key="item.id || item.reportItem.id">{{ item.month }}월 · {{ item.reportItem.name }}</li></ul><p v-if="actionError" class="alert error" role="alert">{{ actionError }}</p><label v-if="action === 'REQUEST_EXPLANATION'">회신 기한<input v-model="actionDue" type="datetime-local" required :disabled="actionBusy" /></label><MessageTemplateSelect v-if="action === 'REQUEST_EXPLANATION'" type="EXPLANATION" :disabled="actionBusy" @select="actionNote = $event" /><label>{{ action === 'EXCLUDE' ? '제외 사유' : action === 'SELECT' ? '검토 메모 (선택)' : '요청 메시지' }}<textarea v-model="actionNote" :required="action !== 'SELECT'" maxlength="4000" :disabled="actionBusy" rows="3" /></label><div class="button-row"><button type="button" class="button" :disabled="actionBusy" @click="actionDialog.close()">취소</button><button class="button primary" :disabled="actionBusy">{{ actionBusy ? '처리 중…' : action === 'REQUEST_EXPLANATION' ? '요청 보내기' : '확인' }}</button></div></form></dialog>
    <dialog ref="requestDialog" class="request-dialog monthly-request-dialog" aria-labelledby="request-title" @close="closeReply"><div class="section-heading"><h2 id="request-title">월간 요청·제출 현황</h2><button class="dialog-close" aria-label="닫기" @click="requestDialog.close()">×</button></div><p class="muted">확정된 연간 계획에 맞춰 필요한 조직에 제출을 요청합니다.</p>
      <p v-if="error" class="error" role="alert">{{ error }}</p><details class="create-month"><summary>새 월간 요청 보내기</summary><form @submit.prevent="createRequest"><p class="notice">월간 요청 전에 해당 연도의 계획과 Contingency를 확정해야 합니다. <RouterLink :to="{ path: '/planning/annual-plan', query: { year: create.year } }">연간 계획 확정 확인 →</RouterLink></p><div class="form-columns"><label>연도<input v-model="create.year" type="number" min="2000" max="9999" required :disabled="saving" /></label><label>마감 월<select v-model="create.month" :disabled="saving"><option v-for="value in 12" :key="value" :value="value">{{ value }}월</option></select></label></div><label>회신 기한<input v-model="create.dueAt" type="datetime-local" :disabled="saving" /></label><MessageTemplateSelect type="MONTHLY_ACTUAL" :disabled="saving" @select="create.message = $event" /><label>요청 메시지<textarea v-model="create.message" maxlength="5000" :disabled="saving" rows="3" /></label><p class="muted">전체 조직이 기본 선택됩니다. 이번에 요청할 조직만 남겨 주세요.</p><div class="organization-checks"><label v-for="item in organizations" :key="item.id"><input v-model="create.organizationIds" type="checkbox" :value="item.id" />{{ item.name }}</label></div><button class="button primary" :disabled="saving || !create.organizationIds.length">{{ saving ? '생성 중…' : create.organizationIds.length + '개 조직에 제출 요청 보내기' }}</button></form></details>
      <template v-if="selected"><div class="request-summary"><strong>{{ selected.year }}년 {{ selected.month }}월 실적·추정</strong><span>제출 {{ selected.submittedCount }} / {{ selected.selectedTargetCount ?? selected.targetCount }} · 승인 {{ selected.approvedCount }}</span></div><RequestEditor :key="selected.id + ':' + selected.stateToken" :request="selected" @updated="loadDetail" @refresh="loadDetail" /><div class="table-scroll"><table><thead><tr><th>사업조직</th><th>상태</th><th>회신·검토</th></tr></thead><tbody><template v-for="item in visibleTargets" :key="item.organization.id"><tr :class="{ 'reply-selected': expandedReplyId === String(item.organization.id) }"><th><button class="reply-toggle" :aria-expanded="expandedReplyId === String(item.organization.id)" @click="expandReply(item)">{{ expandedReplyId === String(item.organization.id) ? '▾' : '▸' }} {{ item.organization.name }}</button><small>{{ item.organization.code }}</small></th><td>{{ statusLabel(item.latestStatus) }}<small v-if="item.latestDraftSubmissionId">새 업로드 · {{ draftStatus(item.latestDraftParsingStatus) }}</small></td><td><button class="text-button" @click="expandReply(item)">{{ expandedReplyId === String(item.organization.id) ? '회신 접기' : '회신 펼치기' }}</button><RouterLink :to="targetLink(item)" class="text-button draft-link" @click="requestDialog.close()">{{ item.latestStatus === 'PENDING' ? '승인·반려 검토' : '제출 내역' }} →</RouterLink><RouterLink v-if="item.latestDraftSubmissionId" :to="draftLink(item)" class="text-button draft-link" @click="requestDialog.close()">업로드 검증 확인 →</RouterLink></td></tr><tr v-if="expandedReplyId === String(item.organization.id)" class="reply-detail"><td colspan="3"><p v-if="replyLoading" role="status">회신을 불러오는 중…</p><p v-if="replyError" class="alert error" role="alert">{{ replyError }}</p><template v-if="replySubmission"><div class="reply-heading"><div><strong>{{ item.organization.name }} · {{ replySubmission.versionLabel }}</strong><p>{{ replySubmission.submittedBy?.name || replySubmission.uploadedBy?.name }} · {{ dateTime(replySubmission.submittedAt || replySubmission.uploadedAt) }} 제출</p></div><button class="button" :disabled="replyDownloading" @click="downloadReply">⤓ {{ replySubmission.originalFilename }}</button></div><p v-if="replySubmission.rejectReason" class="notice">반려 사유: {{ replySubmission.rejectReason }}</p><SubmissionRejectionItems v-if="replySubmission.rejectionItems?.length" :submission="replySubmission" /><details class="reply-explanations"><summary>변경 항목 · 소명 회신 확인</summary><SubmissionReviews :key="replySubmission.id" :submission="replySubmission" @changed="refreshReply" /></details><RouterLink :to="{ path: '/planning/gap', query: { requestId: selected.id, organizationId: item.organization.id } }" class="button primary" @click="requestDialog.close()">이 조직의 Gap 검토하기 →</RouterLink></template><p v-else-if="!replyLoading && !replyError" class="notice">아직 최종 제출된 회신이 없습니다. 업로드 중인 자료는 초안 검증 화면에서 확인할 수 있습니다.</p></td></tr></template><tr v-if="!visibleTargets.length"><td colspan="3" class="empty">요청 대상 조직이 없습니다.</td></tr></tbody></table></div></template>
    </dialog>
  </div>
</template>

<style scoped>
.applied-criteria{font-size:12px;color:var(--muted);padding:12px 0;margin:0;line-height:1.6}
.reply-toggle{background:transparent;padding:0;text-align:left;font:inherit;color:inherit}.reply-selected{background:#faf7f1}.reply-detail>td{padding:20px;background:#fcfbf9}.reply-heading{display:flex;align-items:center;justify-content:space-between;gap:15px}.reply-heading p{font-size:11px;color:var(--muted);margin-top:6px}.reply-explanations{margin:20px 0}.reply-explanations summary{font-size:13px}@media(max-width:760px){.reply-heading{align-items:flex-start;flex-direction:column}}

.sheet-selectors{display:grid;grid-template-columns:1fr 1fr 1fr;gap:12px;margin:18px 0}.sheet-selectors label{font-size:11px;color:#929691}.sheet-selectors select{font-size:12px}.criteria-controls{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.criteria-title{min-width:96px;flex:1}.criteria-title h2{font-size:14px}.criteria-title small{display:block;color:#999996;font-size:10px;margin-top:10px}.criterion-toggle{display:flex;align-items:center;gap:8px;border:1px solid #c59a60;background:#f6f0e4;border-radius:6px;padding:10px 12px;font-size:12px;white-space:nowrap;color:#775326;min-height:40px}.criterion-toggle input{accent-color:#a9875b;width:16px;min-height:16px;margin:0}.criterion-input{position:relative;width:114px}.criterion-input:nth-of-type(4){width:145px}.criterion-input input{margin:0;padding-right:28px;min-height:40px}.criterion-input>span:last-child{position:absolute;right:10px;top:11px;font-size:11px;color:#888884;font-weight:400}.comparison-filter select{margin:0;min-height:40px}.criteria-controls .button{min-height:40px}.criteria-help{display:flex;justify-content:space-between;color:#888884;font-size:11px;margin-left:110px;gap:10px}.criteria-help .text-button{font-size:11px;white-space:nowrap}.review-pipeline{list-style:none;display:grid;grid-template-columns:repeat(5,1fr);gap:18px;margin:26px 24px;padding:0}.review-pipeline li{border:1px solid #f0c86e;border-radius:7px;background:#fff7e6;position:relative}.review-pipeline li:not(:last-child)::after{content:'›';position:absolute;right:-12px;top:22px;color:#9f9f9d}.review-pipeline button{background:transparent;padding:15px 12px;display:flex;align-items:flex-start;gap:10px;text-align:left;width:100%;color:#865b13}.review-pipeline b{display:flex;justify-content:center;align-items:center;width:24px;height:24px;border-radius:50%;background:currentColor;font-size:11px;position:relative;color:#865b13;flex-shrink:0}.review-pipeline b{background:#865b13;color:white}.review-pipeline .stage-2 b{background:#6d6d6a;color:white}.review-pipeline .stage-3 b{background:#7e4eb3;color:white}.review-pipeline .stage-4 b{background:#b9573a;color:white}.review-pipeline .stage-5 b{background:#50504e;color:white}.review-pipeline strong{font-size:12px}.review-pipeline small{display:block;font-size:10px;color:#858581;margin-top:7px}.review-pipeline .stage-2{background:#ededec;border-color:#dbdbda}.stage-2 button,.stage-2 b{color:#6d6d6a}.review-pipeline .stage-3{background:#f6efff;border-color:#dab8fa}.stage-3 button,.stage-3 b{color:#7e4eb3}.review-pipeline .stage-4{background:#fff1eb;border-color:#f3c3ac}.stage-4 button,.stage-4 b{color:#b9573a}.review-pipeline .stage-5{background:#f2f2f1;border-color:#9bcfbd}.stage-5 button,.stage-5 b{color:#50504e}.review-board{padding:22px 24px;min-height:480px}.review-tabs{display:flex;gap:20px;overflow-x:auto;border-bottom:1px solid #f1efea}.review-tabs button{white-space:nowrap;background:transparent;padding:0 0 12px;color:#858a83;font-size:12px;border-bottom:2px solid transparent}.review-tabs span{font-size:11px;color:#9f9f9d;margin-left:8px}.review-tabs .active{font-weight:700;color:#1a1a19;border-color:#1a1a19}.review-tabs .active span{color:#a9875b}.review-filters{display:flex;align-items:center;gap:10px;margin:4px 0}.review-filters label{display:flex;align-items:center;gap:8px;font-size:10px;font-weight:400;color:#aaaaa8;white-space:nowrap}.review-filters select,.review-filters input{margin:0;font-size:11px;min-height:34px}.review-filters .search{margin-left:12px;flex:1;max-width:260px}.review-filters label:last-child{margin-left:auto}.review-count{font-size:11px;text-align:center;color:#848480;margin-top:8px}.review-context{display:flex;justify-content:space-between;align-items:center;border-top:1px solid var(--line);padding-top:14px;gap:18px}.review-context p{font-size:11px;color:#888884;line-height:1.7;max-width:70%}.review-context a{white-space:nowrap;font-size:11px}.selection-bar{display:flex;align-items:center;justify-content:space-between;gap:20px;background:#191919;border-radius:12px;padding:14px 24px;position:sticky;bottom:16px;z-index:3;box-shadow:0 5px 20px #19191915;color:white}.selection-bar>strong{font-size:13px}.selection-bar .button{font-size:11px;min-height:36px}.selection-bar .clear-selection{background:#333331;border-color:#333331;color:#eee}.selection-bar .exclude-selection{background:#c6c6c5;border-color:#c6c6c5;color:#191919}.selection-bar .primary{min-width:200px}.selection-bar .button:disabled{opacity:.45}.gap-detail-dialog{width:min(1120px,calc(100vw - 40px));padding:24px}.gap-detail-dialog>.section-heading{margin-bottom:20px}.action-items{max-height:180px;overflow:auto;font-size:12px;color:var(--muted);line-height:1.9;margin:0;padding-left:20px}.monthly-request-dialog{width:min(1000px,calc(100vw - 40px))}.monthly-request-dialog>.section-heading{margin-bottom:14px}.create-month{margin:20px 0}.create-month form{display:flex;flex-direction:column;gap:16px;margin-top:18px}.request-summary{display:flex;justify-content:space-between;margin:20px 0;font-size:13px}.request-summary span{color:var(--muted)}.organization-checks{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}.organization-checks label{display:flex;gap:7px;align-items:center;font-size:11px}.organization-checks input{width:14px;min-height:14px;margin:0}.draft-link{display:block;margin-top:8px}td small{display:block;color:var(--muted);font-size:10px;margin-top:4px}@media(max-width:1150px){.review-filters{flex-wrap:wrap}.criteria-help{margin-left:0}.review-pipeline{gap:12px;margin:20px 0}.review-pipeline button{padding:12px 8px;gap:6px}.review-pipeline strong{font-size:11px}.review-pipeline b{width:20px;height:20px}}@media(max-width:760px){.sheet-selectors{grid-template-columns:1fr}.review-pipeline{grid-template-columns:repeat(2,1fr)}.review-pipeline li::after{display:none}.review-context{align-items:flex-start;flex-direction:column}.review-context p{max-width:none}.selection-bar{flex-wrap:wrap;padding:16px;gap:12px}.selection-bar .primary{min-width:0}.selection-bar .button-row{width:100%}.selection-bar .button{flex:1}.review-board{padding:16px}.review-filters label:last-child{margin-left:0}.organization-checks{grid-template-columns:1fr 1fr}}
</style>
