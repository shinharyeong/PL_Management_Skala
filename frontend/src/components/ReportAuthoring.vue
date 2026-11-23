<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useSession } from '../stores/session'
import { dateTime, errorText, saveBlob } from '../lib/display'
import { formatMoney } from '../lib/decimal-display'
import { copyReport, moveReportBlock, newReportBlock, reportSignature, reportStatus } from '../lib/report-authoring'
import ReportBlockResult from './ReportBlockResult.vue'

const session = useSession()
const controller = new AbortController()
const commandKeys = new Map()
const catalog = ref({ metrics: [], fonts: [], aiConfigured: false })
const materials = ref([])
const templates = ref([])
const organizations = ref([])
const reasonCatalog = ref({ version: 0, items: [], versions: [] })
const material = ref(null)
const selectedBlockId = ref('')
const draft = reactive({ name: '', baseYear: new Date().getFullYear(), baseMonth: new Date().getMonth() + 1, blocks: [] })
const proposal = ref(null)
const proposalSignature = ref('')
const savedSignature = ref('')
const busy = ref('')
const error = ref('')
const notice = ref('')
const libraryTab = ref('materials')
const selectedTemplateId = ref('')
const templateName = ref('')
const selectedVersion = ref('')
const versionPreview = ref(null)
const sqlQuestion = ref('')
const sqlProposal = ref(null)
const metricName = ref('')
const editInstruction = ref('')
const editProposal = ref(null)
const pdfFile = ref(null)
const pdfUrl = ref('')
const pdfMaskNumbers = ref(true)
const pdfAnalysis = ref(null)
const pdfConfirmed = ref(false)
const pdfSourcePage = ref(1)
const pdfSelectedBlock = ref('')
const reasonEditor = ref(false)
const reasonDraft = ref([])
const draggedBlock = ref('')
const draggedExplanation = ref('')
let viewGeneration = 0

const blockTypes = [{ value: 'TABLE', label: '표' }, { value: 'CHART', label: '차트' }, { value: 'LIST', label: '계층 목록' }, { value: 'TEXT', label: '문장' }, { value: 'GAP', label: 'Gap 설명' }]
const periodChoices = [{ value: 'CURRENT_MONTH', label: '공통 기준 월' }, { value: 'OFFSET', label: '기준 월에서 ±개월' }, { value: 'FIXED', label: '고정 연월' }, { value: 'ROLLING', label: '최근 N개월' }, { value: 'YTD', label: '기준 월까지 누적' }, { value: 'FULL_YEAR', label: '기준 연도 전체' }]
/** 가장 최근 공식 월간 마스터의 기간을 찾는다. Args: 없음. Returns: 기간/null. Raises: 없음. Side Effects: 없음. */
const latestFinalPeriod = computed(function latestFinalMonth() {
  const periods = (catalog.value.periods || []).filter(function official(item) { return item.type === 'MONTHLY_ACTUAL' && item.hasOfficialMaster })
  return periods.sort(function latest(left, right) { return (Number(right.year) * 12 + Number(right.month)) - (Number(left.year) * 12 + Number(left.month)) })[0] || null
})
/** 월간 마스터·요청 유무를 기준월 선택에 안내한다. Args: month 달. Returns: 안내. Raises: 없음. Side Effects: 없음. */
function monthState(month) {
  const period = (catalog.value.periods || []).find(function matches(item) { return item.type === 'MONTHLY_ACTUAL' && Number(item.year) === Number(draft.baseYear) && Number(item.month) === month })
  return period?.hasOfficialMaster ? '확정' : period ? '잠정·미리보기 확인' : '월간 자료 없음'
}

/** 현재 선택한 편집 블록을 찾는다. Args: 없음. Returns: 블록/null. Raises: 없음. Side Effects: 없음. */
const selectedBlock = computed(function activeBlock() { return draft.blocks.find(function matches(block) { return block.id === selectedBlockId.value }) || null })
/** 편집 내용 변경 여부를 확인한다. Args: 없음. Returns: boolean. Raises: 없음. Side Effects: 없음. */
const dirty = computed(function draftChanged() { return material.value !== null && reportSignature(draft) !== savedSignature.value })
/** 저장할 미리보기의 입력이 현재 편집과 같은지 검사한다. Args: 없음. Returns: boolean. Raises: 없음. Side Effects: 없음. */
const canApply = computed(function currentProposal() { return !!proposal.value?.proposalId && proposalSignature.value === reportSignature(draft) && !versionPreview.value })
/** 과거 버전 또는 현재 편집에 대응하는 결과만 반환한다. Args: 없음. Returns: 결과 목록. Raises: 없음. Side Effects: 없음. */
const results = computed(function visibleResults() { return versionPreview.value?.results || proposal.value?.results || material.value?.results || [] })
/** 과거 버전의 블록을 현재 편집 내용과 구분한다. Args: 없음. Returns: 블록 목록. Raises: 없음. Side Effects: 없음. */
const displayBlocks = computed(function visibleBlocks() { return versionPreview.value?.blocks || draft.blocks })
/** 사유를 넣을 수 있는 활성 말단 유형을 찾는다. Args: 없음. Returns: 유형 목록. Raises: 없음. Side Effects: 없음. */
const reasonLeaves = computed(function selectableReasonTypes() {
  const parentIds = new Set()
  for (const item of reasonCatalog.value.items) if (item.active !== false && item.parentId) parentIds.add(String(item.parentId))
  return reasonCatalog.value.items.filter(function leaf(item) { return item.active !== false && !parentIds.has(String(item.id)) })
})

/** 보고 API를 호출하며 같은 명령 본문의 재시도 키를 유지한다.
 * Args: path 하위 경로, method HTTP 메서드, body JSON 또는 multipart, blob 다운로드 여부.
 * Returns: 서버 응답. Raises: API/취소 오류. Side Effects: 명시한 HTTP 요청, 명령 성공 시 키 해제.
 */
async function api(path, method = 'GET', body = undefined, blob = false) {
  const mutation = !['GET', 'HEAD'].includes(method) && path !== '/query' && !path.startsWith('/ai/')
  const signature = `${method}:${path}:${body instanceof FormData ? pdfFile.value?.name : JSON.stringify(body)}`
  if (mutation && !commandKeys.has(signature)) commandKeys.set(signature, crypto.randomUUID())
  const response = await session.request(`/api/v1/report-authoring${path}`, { method, signal: controller.signal, blob,
    headers: mutation ? { 'Idempotency-Key': commandKeys.get(signature) } : undefined,
    body: body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body) })
  if (mutation) commandKeys.delete(signature)
  return response
}

/** 공통 로딩·오류 표시로 단일 사용자 작업을 실행한다.
 * Args: name 작업명, action 비동기 작업. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: 로딩·오류·알림 갱신과 전달한 사용자 작업.
 */
async function run(name, action) {
  if (busy.value) return
  busy.value = name; error.value = ''; notice.value = ''
  try { await action() } catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { if (!controller.signal.aborted) busy.value = '' }
}

/** 목록·지표·사유 유형을 읽고 편집에 사용할 조직을 조회한다.
 * Args: 없음. Returns: Promise<void>. Raises: API 오류. Side Effects: GET과 메모리 목록 갱신.
 */
async function loadLibrary() {
  const responses = await Promise.all([api('/catalog'), api('/materials'), api('/templates'), api('/reason-types'), loadOrganizations()])
  if (controller.signal.aborted) return
  catalog.value = responses[0]; materials.value = responses[1].items; templates.value = responses[2].items
  reasonCatalog.value = responses[3]; organizations.value = responses[4]
}
/** 보고 범위 선택에 필요한 조직 전체 페이지를 조회한다. Args: 없음. Returns: 조직목록. Raises: API 오류. Side Effects: 읽기 HTTP만 수행. */
async function loadOrganizations() {
  const result = []; let cursor = null
  do {
    const query = new URLSearchParams({ limit: '100' }); if (cursor) query.set('cursor', cursor)
    const page = await session.request(`/api/v1/organizations?${query}`, { signal: controller.signal })
    result.push(...page.items); cursor = page.nextCursor
  } while (cursor)
  return result
}

/** 서버 최신 자료를 편집 상태에 적용하고 구 미리보기를 해제한다.
 * Args: value 자료 상세. Returns: 없음. Raises: 없음. Side Effects: 메모리 편집 상태 교체.
 */
function acceptMaterial(value) {
  material.value = value
  Object.assign(draft, { name: value.name, baseYear: value.baseYear, baseMonth: value.baseMonth, blocks: copyReport(value.blocks || []) })
  for (const block of draft.blocks) { block.style = { fontFamily: catalog.value.fonts?.[0] || 'sans-serif', fontSize: 14, width: 100, ...block.style }; block.organizationIds ||= []; block.reasonMappings ||= [] }
  savedSignature.value = reportSignature(draft); selectedBlockId.value = draft.blocks[0]?.id || ''
  proposal.value = null; proposalSignature.value = ''; versionPreview.value = null; selectedVersion.value = ''
  sqlProposal.value = null; editProposal.value = null
}

/** 고른 자료의 최신 버전을 읽는다.
 * Args: id 자료 식별자. Returns: Promise<void>. Raises: API 오류. Side Effects: GET·현재 편집 교체.
 */
async function openMaterial(id) {
  if (dirty.value && !window.confirm('저장하지 않은 편집 내용이 있습니다. 다른 자료를 열까요?')) return
  await run('자료 불러오기', async function loadMaterial() {
    const generation = ++viewGeneration; const value = await api(`/materials/${id}`)
    if (generation === viewGeneration) acceptMaterial(value)
  })
}

/** 확인한 틀 또는 빈 블록으로 새 자료를 만든다.
 * Args: source manual/template/pdf 시작 방식. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: 사용자 선택에 따른 자료 생성 POST, 목록 조회.
 */
async function createMaterial(source) {
  if (dirty.value && !window.confirm('저장하지 않은 편집 내용이 있습니다. 새 자료를 만들까요?')) return
  await run('자료 만들기', async function create() {
    let blocks = [newReportBlock()]
    let templateId, expectedTemplateRevision
    if (source === 'template') {
      if (!selectedTemplateId.value) throw new Error('저장된 자료 틀을 선택해 주세요.')
      const template = await api(`/templates/${selectedTemplateId.value}`); blocks = template.blocks; templateId = template.id; expectedTemplateRevision = template.revision
    }
    if (source === 'pdf') {
      if (!pdfConfirmed.value || !pdfAnalysis.value?.blocks?.length) throw new Error('분석한 블록과 기준 월을 확인한 뒤 구성 확인을 선택해 주세요.')
      blocks = pdfAnalysis.value.blocks
    }
    const value = await api('/materials', 'POST', { name: draft.name.trim() || '새 보고 자료', baseYear: Number(draft.baseYear), baseMonth: Number(draft.baseMonth), templateId, expectedTemplateRevision, blocks })
    acceptMaterial(value); await loadLibrary(); notice.value = '자료를 만들었습니다. 블록을 편집하고 미리보기 후 저장해 주세요.'
  })
}

/** 현재 편집 내용을 검증·조회해 저장 전 미리보기를 만든다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: 계산 결과를 만료되는 제안으로 저장; 자료 버전은 적용 버튼에서만 생성한다.
 */
async function previewMaterial() {
  await run('미리보기', async function preview() {
    if (!material.value || !draft.name.trim() || !draft.blocks.length) throw new Error('자료 제목과 한 개 이상의 블록이 필요합니다.')
    const signature = reportSignature(draft)
    const response = await api(`/materials/${material.value.id}/preview`, 'POST', { expectedRevision: material.value.revision, ...copyReport(draft) })
    proposal.value = response; proposalSignature.value = signature; versionPreview.value = null
    notice.value = '조회 결과·소명 분류·출처를 확인한 뒤 새 버전으로 저장해 주세요.'
  })
}

/** 현재 편집과 일치하는 미리보기만 불변 버전으로 저장한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: 명시 apply POST·새 버전·목록 갱신, 과거 버전 보존.
 */
async function applyMaterial() {
  if (!canApply.value) return
  await run('버전 저장', async function apply() {
    const value = await api(`/materials/${material.value.id}/apply`, 'POST', { proposalId: proposal.value.proposalId, expectedRevision: material.value.revision })
    acceptMaterial(value); await loadLibrary(); notice.value = '새 버전을 저장했습니다. 이전 버전도 보존됩니다.'
  })
}

/** 직전 저장 내용을 새 버전으로 복원한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: 명시 undo POST; 기존 버전은 삭제하지 않는다.
 */
async function undoMaterial() {
  await run('되돌리기', async function undo() { acceptMaterial(await api(`/materials/${material.value.id}/undo`, 'POST', { expectedRevision: material.value.revision })); await loadLibrary(); notice.value = '직전 내용을 새 버전으로 복원했습니다.' })
}

/** 과거 저장 버전을 읽기 전용으로 표시한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: GET; 현재 편집값 유지.
 */
async function loadVersion() {
  if (!selectedVersion.value) { versionPreview.value = null; return }
  await run('이전 버전 조회', async function version() { versionPreview.value = await api(`/materials/${material.value.id}/versions/${selectedVersion.value}`) })
}

/** 저장된 단일 자료 버전을 내보낸다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: 명시 다운로드 GET·사용자 파일 저장.
 */
async function exportMaterial() {
  await run('내보내기', async function download() {
    const version = selectedVersion.value || material.value.revision
    const blob = await api(`/materials/${material.value.id}/file?version=${version}`, 'GET', undefined, true)
    saveBlob(blob, `보고자료-${material.value.id}-v${version}.html`)
  })
}

/** 현재 확인한 블록 구성을 재사용할 자료 틀로 저장한다.
 * Args: update 기존 선택 틀의 새 버전 여부. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: 틀 생성 또는 불변 새 버전 POST.
 */
async function saveTemplate(update = false) {
  await run('자료 틀 저장', async function save() {
    if (!templateName.value.trim()) throw new Error('자료 틀 이름을 입력해 주세요.')
    const input = { name: templateName.value.trim(), blocks: copyReport(draft.blocks) }
    if (update) {
      const current = await api(`/templates/${selectedTemplateId.value}`)
      await api(`/templates/${current.id}/versions`, 'POST', { ...input, expectedRevision: current.revision })
    } else await api('/templates', 'POST', input)
    await loadLibrary(); notice.value = '자료 틀을 저장했습니다. 기준 월을 바꿔 다시 사용할 수 있습니다.'
  })
}

/** 선택 블록의 결과를 식별자로 찾는다. Args: id 블록 ID. Returns: 결과/null. Raises: 없음. Side Effects: 없음. */
function resultFor(id) { return results.value.find(function matches(item) { return item.blockId === id }) || null }
/** 새 블록을 편집 목록에 넣는다. Args: type 자료 종류. Returns: 없음. Raises: 없음. Side Effects: 편집 상태만 변경. */
function addBlock(type) { const block = newReportBlock(type, draft.blocks.length); block.style.fontFamily = catalog.value.fonts?.[0] || 'sans-serif'; draft.blocks.push(block); selectedBlockId.value = block.id }
/** 고른 블록을 편집에서 제거한다. Args: id 블록 ID. Returns: 없음. Raises: 없음. Side Effects: 편집 상태만 변경. */
function removeBlock(id) { draft.blocks = draft.blocks.filter(function keep(block) { return block.id !== id }); if (selectedBlockId.value === id) selectedBlockId.value = draft.blocks[0]?.id || '' }
/** 블록을 버튼이나 드롭 위치로 이동한다. Args: id 블록, index 순서. Returns: 없음. Raises: 없음. Side Effects: 메모리 편집 순서 변경. */
function moveBlock(id, index) { draft.blocks = moveReportBlock(draft.blocks, id, index); draggedBlock.value = '' }
/** 소명 카드를 승인된 사유 유형에 매핑한다. Args: explanationId 소명, reasonTypeId 유형 또는 빈값. Returns: 없음. Raises: 없음. Side Effects: 현재 블록 분류만 변경. */
function mapReason(explanationId, reasonTypeId) {
  if (!selectedBlock.value || !explanationId || resultFor(selectedBlock.value.id)?.reasonAllocationAvailable === false) return
  selectedBlock.value.reasonMappings = selectedBlock.value.reasonMappings.filter(function keep(item) { return String(item.explanationId) !== String(explanationId) })
  if (reasonTypeId) selectedBlock.value.reasonMappings.push({ explanationId: String(explanationId), reasonTypeId: String(reasonTypeId) })
  draggedExplanation.value = ''
}
/** 현재 카드에 지정한 분류를 읽는다. Args: explanationId 소명. Returns: 유형 ID/빈값. Raises: 없음. Side Effects: 없음. */
function mappedReason(explanationId) { return selectedBlock.value?.reasonMappings.find(function matches(item) { return String(item.explanationId) === String(explanationId) })?.reasonTypeId || '' }
/** 서버가 제시한 분류 후보의 활성 유형을 찾는다. Args: item 소명 후보. Returns: 유형/null. Raises: 없음. Side Effects: 없음. */
function suggestedReason(item) { return reasonLeaves.value.find(function matches(type) { return String(type.id) === String(item.suggestedReasonTypeId || item.reasonSuggestion?.reasonTypeId || '') }) || null }
/** 후보 분류를 사용자가 확인해 편집에 반영한다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 미분류 카드의 메모리 매핑만 갱신. */
function acceptReasonSuggestions() {
  for (const item of resultFor(selectedBlock.value?.id)?.explanations || []) {
    const type = suggestedReason(item)
    if (type && !mappedReason(item.explanationId)) mapReason(item.explanationId, type.id)
  }
}
/** 원본의 해당 페이지와 블록 위치를 확인한다. Args: block 분석 블록. Returns: 없음. Raises: 없음. Side Effects: 브라우저 메모리 선택만 변경. */
function inspectPdfBlock(block) { pdfSourcePage.value = Number(block.sourcePage) || 1; pdfSelectedBlock.value = block.id }
/** PDF의 상대좌표를 페이지 배치 안내에 표시한다. Args: block 분석 블록. Returns: CSS 위치. Raises: 없음. Side Effects: 없음. */
function pdfBounds(block) {
  const bounds = block.sourceBounds
  if (!Array.isArray(bounds) || bounds.length !== 4 || bounds.some(function invalid(value) { return !Number.isFinite(Number(value)) })) return null
  return { left: `${Math.max(0, Math.min(1, Number(bounds[0]))) * 100}%`, top: `${Math.max(0, Math.min(1, Number(bounds[1]))) * 100}%`, width: `${Math.max(0, Math.min(1, Number(bounds[2]))) * 100}%`, height: `${Math.max(0, Math.min(1, Number(bounds[3]))) * 100}%` }
}

/** 자연어로 SQL 초안을 요청하며 적용 전 편집에 반영하지 않는다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: AI 제안 요청만 수행.
 */
async function proposeSql() {
  await run('질의 제안', async function propose() {
    const block = copyReport(selectedBlock.value)
    const inputSignature = reportSignature(draft)
    const value = await api('/ai/sql', 'POST', { question: sqlQuestion.value, block, baseYear: draft.baseYear, baseMonth: draft.baseMonth })
    sqlProposal.value = { ...value, blockId: block.id, inputSignature }
  })
}
/** 확인한 SQL을 블록의 조회식으로 채택한다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 편집만 변경, 데이터는 미리보기에서 조회. */
function acceptSql() {
  if (selectedBlock.value?.id !== sqlProposal.value?.blockId) { error.value = '질의 제안을 요청했던 블록을 선택해 주세요.'; return }
  if (sqlProposal.value.inputSignature !== reportSignature(draft)) { error.value = '질의 제안 후 편집 내용이 바뀌었습니다. 변경된 조건으로 다시 요청해 주세요.'; return }
  if (sqlProposal.value?.sql) { selectedBlock.value.sql = sqlProposal.value.sql; sqlProposal.value = null; notice.value = '조회식을 반영했습니다. 미리보기로 결과를 확인한 뒤 저장해 주세요.' }
}
/** 사용자 확인 조회식을 재사용 지표로 등록한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: 명시 지표 등록 POST·카탈로그 갱신.
 */
async function saveMetric() {
  await run('지표 등록', async function registerMetric() {
    if (!metricName.value.trim() || !selectedBlock.value?.sql?.trim()) throw new Error('지표 이름과 확인한 조회식을 입력해 주세요.')
    if (!canApply.value) throw new Error('현재 조회식으로 데이터 미리보기를 확인한 뒤 지표 사전에 등록해 주세요.')
    const block = selectedBlock.value
    const selected = catalog.value.metrics.find(function matches(item) { return item.code === block.metricCode })
    const value = await api('/metrics', 'POST', { name: metricName.value.trim(), sql: block.sql, baseMetricCode: selected?.baseMetricCode || block.metricCode })
    catalog.value = await api('/catalog'); block.metricCode = value.code
    metricName.value = ''; notice.value = '지표 사전에 등록했습니다.'
  })
}
/** 대화 편집안을 요청한다. Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: AI 제안 요청만 수행. */
async function proposeEdit() { await run('편집안 제안', async function propose() { const signature = reportSignature(draft); editProposal.value = { ...await api('/ai/edit', 'POST', { instruction: editInstruction.value, blocks: copyReport(draft.blocks) }), inputSignature: signature } }) }
/** 확인한 편집안을 메모리 편집에 적용한다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 블록 편집만 변경. */
function acceptEdit() {
  if (editProposal.value?.inputSignature !== reportSignature(draft)) { error.value = '변경안을 요청한 뒤 편집 내용이 달라졌습니다. 변경안을 다시 요청해 주세요.'; return }
  if (editProposal.value?.blocks) { draft.blocks = copyReport(editProposal.value.blocks); selectedBlockId.value = draft.blocks[0]?.id || ''; editProposal.value = null; notice.value = '편집안을 반영했습니다. 저장 전에 미리보기를 확인해 주세요.' }
}

/** 선택 PDF의 브라우저 미리보기만 준비한다.
 * Args: event 파일 입력. Returns: 없음. Raises: 없음. Side Effects: 메모리 Blob URL 생성·이전 URL 해제; 업로드는 분석 클릭 때 실행.
 */
function selectPdf(event) {
  if (pdfUrl.value) URL.revokeObjectURL(pdfUrl.value)
  pdfFile.value = event.target.files?.[0] || null; pdfUrl.value = pdfFile.value ? URL.createObjectURL(pdfFile.value) : ''
  pdfAnalysis.value = null; pdfConfirmed.value = false; pdfSourcePage.value = 1; pdfSelectedBlock.value = ''
}
/** PDF 분석 결과를 원본과 확인할 임시 구성으로 받는다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: 명시 PDF 업로드·AI 분석; 자료·틀 자동 저장 없음.
 */
async function analyzePdf() {
  await run('PDF 분석', async function analyze() {
    if (!pdfFile.value) throw new Error('분석할 PDF를 선택해 주세요.')
    const form = new FormData(); form.append('file', pdfFile.value); form.append('maskNumbers', String(pdfMaskNumbers.value))
    const response = await api('/ai/pdf', 'POST', form)
    pdfAnalysis.value = { ...response, blocks: response.blocks || [], pages: response.pages || [] }; pdfConfirmed.value = false
    if (pdfAnalysis.value.blocks.length) inspectPdfBlock(pdfAnalysis.value.blocks[0])
    if (pdfAnalysis.value.inferredBaseYear) draft.baseYear = pdfAnalysis.value.inferredBaseYear
    if (pdfAnalysis.value.inferredBaseMonth) draft.baseMonth = pdfAnalysis.value.inferredBaseMonth
  })
}

/** 사유 유형 편집을 현재 버전 복사본으로 연다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 메모리 편집만 변경. */
function openReasons() { reasonDraft.value = copyReport(reasonCatalog.value.items); reasonEditor.value = true }
/** 사유 유형을 메모리에 추가한다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 메모리 편집만 변경. */
function addReason() { reasonDraft.value.push({ id: crypto.randomUUID(), name: '', parentId: null, active: true, keywords: [] }) }
/** 유형별 키워드 입력을 배열로 보존한다. Args: item 유형, value 쉼표 구분 키워드. Returns: 없음. Raises: 없음. Side Effects: 메모리 편집만 갱신. */
function setReasonKeywords(item, value) { item.keywords = value.split(',').map(function trim(keyword) { return keyword.trim() }).filter(Boolean) }
/** 유형 분류표의 새 버전을 저장한다. Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: 명시 POST와 카탈로그 재조회. */
async function saveReasons() { await run('사유 유형 저장', async function save() { await api('/reason-types', 'POST', { expectedVersion: reasonCatalog.value.version, items: reasonDraft.value }); reasonCatalog.value = await api('/reason-types'); reasonEditor.value = false; notice.value = '분류표의 새 버전을 저장했습니다.' }) }
/** 화면 초기 데이터를 읽는다. Args: 없음. Returns: Promise<void>. Raises: 없음. Side Effects: 읽기 요청만 수행. */
async function initialize() { await run('목록 조회', async function loadInitialLibrary() { await loadLibrary(); if (latestFinalPeriod.value && !material.value) { draft.baseYear = Number(latestFinalPeriod.value.year); draft.baseMonth = Number(latestFinalPeriod.value.month) } }) }
/** 이탈 시 응답과 PDF URL을 해제한다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 요청 취소·메모리 URL 해제. */
function dispose() { viewGeneration++; controller.abort(); if (pdfUrl.value) URL.revokeObjectURL(pdfUrl.value) }
onMounted(initialize)
onBeforeUnmount(dispose)
</script>

<template>
  <div class="authoring-page">
    <header class="authoring-heading"><div><p class="muted">경영기획팀 / 보고서 생성</p><h1>보고서 자료 만들기</h1><p>자료 틀을 고르고 기준 월의 데이터를 연결해 보고 자료를 만듭니다.</p></div><button class="button" :disabled="!!busy" @click="openReasons">사유 유형 관리</button></header>
    <p v-if="error" class="alert error" role="alert">{{ error }}</p><p v-if="notice" class="notice" role="status">{{ notice }}</p><p v-if="busy" class="muted" role="status">{{ busy }} 중…</p>
    <section class="panel library-panel">
      <div class="tabs"><button :class="{ active: libraryTab === 'materials' }" @click="libraryTab = 'materials'">저장한 자료</button><button :class="{ active: libraryTab === 'templates' }" @click="libraryTab = 'templates'">자료 틀</button><button :class="{ active: libraryTab === 'pdf' }" @click="libraryTab = 'pdf'">PDF로 시작</button></div>
      <div v-if="libraryTab === 'materials'" class="saved-materials"><p v-if="!materials.length" class="muted">저장된 자료가 없습니다. 직접 만들거나 PDF에서 구성을 가져오세요.</p><button v-for="item in materials" :key="item.id" class="saved-card" :disabled="!!busy" @click="openMaterial(item.id)"><strong>{{ item.name }}</strong><span>{{ item.baseYear }}.{{ String(item.baseMonth).padStart(2,'0') }} · v{{ item.revision }}</span><small>{{ dateTime(item.updatedAt || item.createdAt) }}</small></button></div>
      <div v-if="libraryTab === 'templates'" class="inline-controls"><label>저장된 틀<select v-model="selectedTemplateId" :disabled="!!busy"><option value="">선택하세요</option><option v-for="item in templates" :key="item.id" :value="item.id">{{ item.name }} · v{{ item.revision }}</option></select></label><button class="button" :disabled="!!busy || !selectedTemplateId" @click="createMaterial('template')">이 틀로 자료 만들기</button></div>
      <div v-if="libraryTab === 'pdf'" class="pdf-workspace">
        <p class="muted">원본 숫자를 복사하지 않고 블록의 종류·순서·기간 관계를 가져옵니다. 분석 후 구성을 직접 확인해 주세요.</p>
        <div class="inline-controls"><label>참고 PDF<input type="file" accept="application/pdf,.pdf" :disabled="!!busy" @change="selectPdf" /></label><label class="check"><input v-model="pdfMaskNumbers" type="checkbox" :disabled="!!busy" />AI 전송 전 숫자 가림</label><button class="button" :disabled="!!busy || !pdfFile || !catalog.aiConfigured" @click="analyzePdf">PDF 분석</button></div>
        <p v-if="!catalog.aiConfigured" class="notice">AI 연결이 설정되지 않았습니다. 아래 ‘직접 만들기’로 같은 자료 편집 기능을 이용할 수 있습니다.</p>
        <div v-if="pdfAnalysis" class="pdf-review"><div class="pdf-source"><iframe v-if="pdfUrl" :key="pdfSourcePage" :src="`${pdfUrl}#page=${pdfSourcePage}`" title="선택한 원본 PDF 확인" /><p class="muted">원본 {{ pdfSourcePage }}쪽 · 아래는 AI가 찾은 위치 안내입니다.</p><div class="pdf-page-map" aria-label="선택 페이지의 분석 영역"><template v-for="(block, index) in pdfAnalysis.blocks" :key="block.id"><button v-if="Number(block.sourcePage) === pdfSourcePage && pdfBounds(block)" :class="{ selected: pdfSelectedBlock === block.id }" :style="pdfBounds(block)" :title="block.title" @click="inspectPdfBlock(block)">{{ index + 1 }}</button></template></div></div><div><h3>자료 틀 확인</h3><p v-for="(warning, index) in pdfAnalysis.warnings" :key="index" class="notice">{{ typeof warning === 'string' ? warning : warning.message }}</p><article v-for="(block, index) in pdfAnalysis.blocks" :key="block.id" class="pdf-block" :class="{ selected: pdfSelectedBlock === block.id }"><button class="text-button" @click="inspectPdfBlock(block)">원본 위치 보기</button><label>블록 {{ index + 1 }} 제목<input v-model="block.title" /></label><label>종류<select v-model="block.type"><option v-for="type in blockTypes" :key="type.value" :value="type.value">{{ type.label }}</option></select></label><label>원본 페이지<input v-model.number="block.sourcePage" type="number" min="1" /></label><label>기간 관계<select v-model="block.periodRule"><option v-for="choice in periodChoices" :key="choice.value" :value="choice.value">{{ choice.label }}</option></select></label><label>지표<select v-model="block.metricCode"><option v-for="metric in catalog.metrics" :key="metric.code" :value="metric.code">{{ metric.name }}</option></select></label><button class="text-button" @click="pdfAnalysis.blocks.splice(index, 1)">블록 제외</button></article><button class="button" @click="pdfAnalysis.blocks.push(newReportBlock('TABLE', pdfAnalysis.blocks.length))">빠진 블록 추가</button><label class="check"><input v-model="pdfConfirmed" type="checkbox" />블록 구성과 아래 기준 월을 확인했습니다</label><button class="button primary" :disabled="!!busy || !pdfConfirmed || !pdfAnalysis.blocks?.length" @click="createMaterial('pdf')">확인한 구성으로 자료 만들기</button></div></div>
      </div>
      <div class="inline-controls new-material"><label>자료 제목<input v-model="draft.name" maxlength="200" placeholder="예: 월간 손익 검토" :disabled="!!busy || !!versionPreview" /></label><label>기준 연도<input v-model.number="draft.baseYear" type="number" min="2000" max="9999" :disabled="!!busy || !!versionPreview" /></label><label>기준 월<select v-model.number="draft.baseMonth" :disabled="!!busy || !!versionPreview"><option v-for="value in 12" :key="value" :value="value">{{ value }}월 · {{ monthState(value) }}</option></select></label><button class="button" :disabled="!!busy" @click="createMaterial('manual')">직접 만들기</button></div>
    </section>

    <p v-if="latestFinalPeriod" class="muted">최신 확정 월: {{ latestFinalPeriod.year }}년 {{ latestFinalPeriod.month }}월. 월 표시는 공식 월간 마스터 기준이며 각 블록의 계획·추정·조직 범위는 미리보기에서 확인합니다.</p>
    <template v-if="material">
      <section class="editor-toolbar panel"><div><strong>{{ material.name }}</strong><span class="muted"> · 저장 v{{ material.revision }}{{ dirty ? ' · 편집 중' : '' }}</span></div><div class="button-row"><button class="button" :disabled="!!busy || !!versionPreview" @click="previewMaterial">데이터 미리보기</button><button class="button primary" :disabled="!!busy || !canApply" @click="applyMaterial">확인 후 새 버전 저장</button><button class="button" :disabled="!!busy || material.revision < 2" @click="undoMaterial">직전 저장으로 되돌리기</button><button class="button" :disabled="!!busy || dirty" @click="exportMaterial">이 자료 HTML 내보내기</button></div><label>저장 버전<select v-model="selectedVersion" :disabled="!!busy" @change="loadVersion"><option value="">현재 편집</option><option v-for="item in material.versions || []" :key="item.revision || item.seq" :value="item.revision || item.seq">v{{ item.revision || item.seq }} · {{ dateTime(item.createdAt) }}</option></select></label></section>
      <p v-if="versionPreview" class="notice">저장된 v{{ selectedVersion }}을 읽기 전용으로 보고 있습니다. ‘현재 편집’을 선택하면 편집을 이어갈 수 있습니다.</p>
      <p v-else-if="proposal && !canApply" class="notice">미리보기 이후 편집 내용이 바뀌었습니다. 다시 미리보기를 실행해야 저장할 수 있습니다.</p>
      <ul v-if="proposal?.warnings?.length" class="notice"><li v-for="(warning, index) in proposal.warnings" :key="index">{{ typeof warning === 'string' ? warning : warning.message }}</li></ul>
      <div class="authoring-layout">
        <fieldset v-if="!versionPreview" class="block-sidebar panel" :disabled="!!busy"><h2>자료 구성</h2><div class="block-add"><button v-for="type in blockTypes" :key="type.value" class="button small" @click="addBlock(type.value)">+ {{ type.label }}</button></div><article v-for="(block, index) in draft.blocks" :key="block.id" :class="['block-choice', { selected: selectedBlockId === block.id }]" draggable="true" @dragstart="draggedBlock = block.id" @dragover.prevent @drop.prevent="moveBlock(draggedBlock, index)"><button class="block-title" @click="selectedBlockId = block.id">{{ index + 1 }}. {{ block.title }}</button><div class="block-actions"><button :disabled="index === 0" :aria-label="`${block.title} 위로`" @click="moveBlock(block.id, index - 1)">↑</button><button :disabled="index === draft.blocks.length - 1" :aria-label="`${block.title} 아래로`" @click="moveBlock(block.id, index + 1)">↓</button><button @click="removeBlock(block.id)">삭제</button></div></article><details class="template-save"><summary>현재 구성을 자료 틀로 저장</summary><label>틀 이름<input v-model="templateName" maxlength="200" /></label><button class="button" :disabled="!!busy || !draft.blocks.length" @click="saveTemplate(false)">새 틀 저장</button><button v-if="selectedTemplateId" class="button" :disabled="!!busy" @click="saveTemplate(true)">선택한 틀에 새 버전</button></details></fieldset>
        <main class="block-canvas"><p v-if="!versionPreview && dirty && (!proposal || !canApply)" class="notice">표시된 숫자는 마지막 저장 또는 미리보기 기준입니다. 변경한 기간·지표·분류는 새 미리보기에서 계산됩니다.</p><p v-if="!displayBlocks.length" class="panel empty">표·차트·문장·Gap 설명 블록을 추가해 주세요.</p><article v-for="block in displayBlocks" :key="block.id" :style="{ width: `${block.style?.width || 100}%` }" @click="!busy && (selectedBlockId = block.id)"><ReportBlockResult :block="block" :result="resultFor(block.id)" /></article></main>
        <fieldset v-if="selectedBlock && !versionPreview" class="block-inspector panel" :disabled="!!busy"><h2>블록 편집</h2><label>제목<input v-model="selectedBlock.title" maxlength="200" /></label><label>종류<select v-model="selectedBlock.type"><option v-for="type in blockTypes" :key="type.value" :value="type.value">{{ type.label }}</option></select></label><label>지표 사전<select v-model="selectedBlock.metricCode" @change="selectedBlock.sql = ''"><option v-for="metric in catalog.metrics" :key="metric.code" :value="metric.code">{{ metric.name }}</option></select></label>
          <label>데이터<select v-model="selectedBlock.scenario"><option value="ACTUAL">실적</option><option value="PLAN">계획</option><option value="ESTIMATE">추정</option></select></label><label>기간<select v-model="selectedBlock.periodRule"><option v-for="choice in periodChoices" :key="choice.value" :value="choice.value">{{ choice.label }}</option></select></label><label v-if="selectedBlock.periodRule === 'OFFSET'">기준 월 ±개월<input v-model.number="selectedBlock.offsetMonths" type="number" min="-120" max="120" /></label><div v-if="selectedBlock.periodRule === 'FIXED'" class="split-fields"><label>연도<input v-model.number="selectedBlock.fixedYear" type="number" min="2000" max="9999" /></label><label>월<input v-model.number="selectedBlock.fixedMonth" type="number" min="1" max="12" /></label></div><label v-if="selectedBlock.periodRule === 'ROLLING'">최근 개월 수<input v-model.number="selectedBlock.windowMonths" type="number" min="1" max="24" /></label><label>비교 기준<select v-model="selectedBlock.comparisonBasis"><option value="PLAN">계획</option><option value="PREVIOUS">직전 추정</option><option value="YEAR_AGO">전년 동월</option></select></label>
          <fieldset><legend>조직 범위</legend><p class="muted">선택하지 않으면 전사</p><label v-for="org in organizations" :key="org.id" class="check"><input v-model="selectedBlock.organizationIds" type="checkbox" :value="String(org.id)" />{{ org.name }}</label></fieldset>
          <label v-if="selectedBlock.type === 'CHART'">차트 종류<select v-model="selectedBlock.chartKind"><option value="BAR">막대 차트</option><option value="PIE">원형 차트</option></select></label><label>내용<textarea v-model="selectedBlock.text" rows="3" placeholder="보고 자료에 넣을 설명" /></label><label>글꼴<select v-model="selectedBlock.style.fontFamily"><option v-for="font in catalog.fonts" :key="font" :value="font">{{ font }}</option></select></label><div class="split-fields"><label>글자 크기<input v-model.number="selectedBlock.style.fontSize" type="number" min="8" max="48" /></label><label>너비(%)<input v-model.number="selectedBlock.style.width" type="number" min="25" max="100" step="25" /></label></div>
          <details><summary>데이터 조회식 확인·자연어 질의</summary><label>질문<textarea v-model="sqlQuestion" rows="2" placeholder="조회할 지표와 기간을 입력하세요" /></label><button class="button" :disabled="!!busy || !catalog.aiConfigured || !sqlQuestion.trim()" @click="proposeSql">조회식 제안</button><div v-if="sqlProposal" class="proposal-box"><p v-for="(warning, index) in sqlProposal.warnings" :key="index">{{ warning }}</p><pre>{{ sqlProposal.sql }}</pre><button class="button" :disabled="!sqlProposal.sql" @click="acceptSql">이 조회식 적용</button><button class="text-button" @click="sqlProposal = null">취소</button></div><label>블록 조회식<textarea v-model="selectedBlock.sql" rows="5" placeholder="비워 두면 선택한 지표의 검증된 조회식을 사용합니다." /></label><p class="muted">미리보기로 결과를 확인한 후 저장하면 다음 달에도 다시 사용할 수 있습니다.</p><label>지표 사전에 등록할 이름<input v-model="metricName" maxlength="200" /></label><button class="button" :disabled="!!busy || !canApply || !metricName.trim() || !selectedBlock.sql" @click="saveMetric">확인한 조회식 지표로 등록</button></details>
          <section v-if="['GAP', 'LIST'].includes(selectedBlock.type)" class="reason-mapping"><h3>소명 사유 분류</h3><p class="muted">수락된 소명만 분류할 수 있습니다. 미분류는 기타에 포함됩니다. 금액은 서버에서 계산합니다.</p><p v-if="resultFor(selectedBlock.id)?.reasonAllocationAvailable === false" class="notice">합계가 아닌 집계에는 사유 금액을 배분하지 않습니다.</p><button class="button" :disabled="resultFor(selectedBlock.id)?.reasonAllocationAvailable === false" @click="acceptReasonSuggestions">미분류 항목에 추천 분류 채우기</button><div class="reason-bins"><button v-for="type in reasonLeaves" :key="type.id" class="reason-bin" @dragover.prevent @drop.prevent="mapReason(draggedExplanation, type.id)">{{ type.name }}</button></div><article v-for="item in resultFor(selectedBlock.id)?.explanations || []" :key="item.explanationId" class="reason-card" draggable="true" @dragstart="draggedExplanation = item.explanationId"><strong>{{ item.organizationName }} · {{ formatMoney(item.gapAmount) }}원</strong><p>{{ item.answer }}</p><p v-if="suggestedReason(item)" class="muted">추천: {{ suggestedReason(item).name }} · {{ item.reasonSuggestion?.source === 'PREVIOUS_MATERIAL' ? '전월 자료' : '키워드 일치' }}<button class="text-button" @click="mapReason(item.explanationId, suggestedReason(item).id)">이 추천 적용</button></p><label>분류<select :disabled="resultFor(selectedBlock.id)?.reasonAllocationAvailable === false" :value="mappedReason(item.explanationId)" @change="mapReason(item.explanationId, $event.target.value)"><option value="">기타(미분류)</option><option v-for="type in reasonLeaves" :key="type.id" :value="type.id">{{ type.name }}</option></select></label></article><p v-if="!resultFor(selectedBlock.id)?.explanations?.length" class="muted">미리보기를 실행해 이 자료에 포함되는 수용 소명을 확인하세요.</p></section>
        </fieldset>
      </div>
      <section v-if="!versionPreview" class="panel ai-editor"><h2>대화로 자료 편집</h2><p class="muted">추가·삭제·순서·서식 변경안을 확인한 뒤 적용합니다. 데이터 숫자는 조회 결과를 사용합니다.</p><div class="inline-controls"><textarea v-model="editInstruction" rows="2" placeholder="예: 영업이익 표를 첫 번째로 옮기고 제목 글씨를 키워 줘" /><button class="button" :disabled="!!busy || !catalog.aiConfigured || !editInstruction.trim()" @click="proposeEdit">변경안 보기</button></div><p v-if="!catalog.aiConfigured" class="muted">AI 연결 전에는 오른쪽 블록 편집에서 같은 구성을 직접 바꿀 수 있습니다.</p><div v-if="editProposal" class="proposal-box"><p v-for="(warning, index) in editProposal.warnings" :key="index">{{ warning }}</p><ol><li v-for="block in editProposal.blocks" :key="block.id">{{ block.title }} · {{ block.type }} · {{ block.style?.fontSize }}px</li></ol><button class="button primary" :disabled="!editProposal.blocks?.length" @click="acceptEdit">변경안 적용</button><button class="button" @click="editProposal = null">취소</button></div></section>
    </template>
    <section v-if="reasonEditor" class="panel reason-editor" aria-labelledby="reason-title"><h2 id="reason-title">소명 사유 유형 · v{{ reasonCatalog.version }}</h2><p class="muted">현재 보고자료의 분류를 바꾸지 않고 새 분류표 버전을 만듭니다.</p><div v-for="item in reasonDraft" :key="item.id" class="inline-controls"><label>유형명<input v-model="item.name" maxlength="200" /></label><label>상위 유형<select v-model="item.parentId"><option :value="null">최상위</option><template v-for="parent in reasonDraft" :key="parent.id"><option v-if="parent.id !== item.id" :value="parent.id">{{ parent.name || '이름 미입력' }}</option></template></select></label><label>추천 키워드(쉼표 구분)<input :value="(item.keywords || []).join(', ')" @input="setReasonKeywords(item, $event.target.value)" /></label><label class="check"><input v-model="item.active" type="checkbox" />사용</label></div><div class="button-row"><button class="button" @click="addReason">유형 추가</button><button class="button primary" :disabled="!!busy" @click="saveReasons">새 버전 저장</button><button class="button" @click="reasonEditor = false">취소</button></div></section>
  </div>
</template>

<style scoped>
.pdf-page-map{position:relative;width:160px;height:220px;border:1px solid #8998a8;background:#fff;margin:12px auto}.pdf-page-map button{position:absolute;border:1px solid #5e81ac;background:#b8d5f677;font-size:12px;overflow:hidden}.pdf-page-map .selected,.pdf-block.selected{outline:2px solid #466ba0}.pdf-source{overflow:auto}.pdf-review .pdf-source{max-height:none}.block-canvas>.notice{width:100%}
.authoring-page{max-width:1600px;margin:0 auto}.authoring-heading{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-bottom:24px}.authoring-heading h1{margin:6px 0 10px}.authoring-heading p{font-size:13px;color:var(--muted)}.library-panel{margin:20px 0}.tabs{display:flex;gap:8px;border-bottom:1px solid var(--line);margin-bottom:18px}.tabs button{border:0;background:none;padding:10px 14px;cursor:pointer;color:var(--muted)}.tabs .active{color:var(--ink);border-bottom:2px solid var(--ink);font-weight:700}.saved-materials{display:flex;gap:12px;overflow:auto;padding:4px 0 14px}.saved-card{min-width:190px;padding:16px;border:1px solid var(--line);background:#fff;border-radius:8px;display:grid;gap:8px;text-align:left;cursor:pointer}.saved-card span,.saved-card small{font-size:12px;color:var(--muted)}.inline-controls{display:flex;flex-wrap:wrap;gap:14px;align-items:flex-end}.inline-controls label{margin:0;flex:1;min-width:100px}.inline-controls input,.inline-controls select,.inline-controls textarea{margin-bottom:0}.new-material{padding-top:18px;border-top:1px solid var(--line);margin-top:14px}.new-material label:first-child{flex:3}.check{display:flex;align-items:center;gap:8px;font-size:13px}.check input{width:16px;min-height:16px;margin:0;accent-color:var(--ink)}.editor-toolbar{display:flex;gap:14px;flex-wrap:wrap;align-items:center;justify-content:space-between}.editor-toolbar label{margin:0;min-width:170px}.editor-toolbar select{margin:0}.authoring-layout{display:grid;grid-template-columns:210px minmax(280px,1fr) 290px;gap:18px;align-items:start;margin-top:20px}.block-sidebar,.block-inspector{padding:18px;min-width:0}.block-sidebar h2,.block-inspector h2,.ai-editor h2{font-size:16px;margin-bottom:18px}.block-add{display:flex;gap:6px;flex-wrap:wrap;margin-bottom:16px}.button.small{padding:6px 9px;min-height:30px;font-size:12px}.block-choice{border:1px solid var(--line);padding:10px;margin-bottom:8px;border-radius:7px;background:#fff}.block-choice.selected{border-color:#54779d;background:#f1f5fa}.block-title{background:none;border:0;text-align:left;cursor:pointer;font-size:13px;line-height:1.6;width:100%;overflow-wrap:anywhere}.block-actions{display:flex;gap:12px;margin-top:8px}.block-actions button{border:0;background:none;cursor:pointer;color:var(--muted);font-size:12px}.block-canvas{display:flex;flex-wrap:wrap;gap:16px;min-width:0}.block-canvas article{min-width:0;max-width:100%;flex-shrink:1}.block-inspector label{font-size:12px;margin-bottom:12px}.block-inspector input,.block-inspector select,.block-inspector textarea{font-size:13px;margin-bottom:0}.block-inspector fieldset{border:1px solid var(--line);padding:12px;max-height:200px;overflow:auto;margin:14px 0}.block-inspector legend{font-size:12px}.block-inspector fieldset label{margin:8px 0}.split-fields{display:flex;gap:10px}.split-fields label{width:50%}.block-inspector details{margin:16px 0;font-size:12px}.block-inspector summary,.template-save summary{cursor:pointer;margin-bottom:12px}.template-save{margin-top:20px;font-size:12px}.template-save .button{margin-bottom:8px}.proposal-box{background:#f2f5f9;border:1px solid var(--line);border-radius:8px;padding:14px;margin:14px 0}.proposal-box pre{white-space:pre-wrap;word-break:break-word;font-size:12px;line-height:1.7}.proposal-box button{margin:8px 8px 0 0}.pdf-review{display:grid;grid-template-columns:1fr 1fr;gap:20px;margin-top:20px}.pdf-review iframe{width:100%;height:540px;border:1px solid var(--line)}.pdf-review>div{max-height:540px;overflow:auto}.pdf-block{padding:14px;border:1px solid var(--line);margin:12px 0}.pdf-review .check{margin:18px 0}.ai-editor{margin-top:22px}.ai-editor textarea{flex:1;min-width:220px}.ai-editor .inline-controls{margin-top:14px}.reason-bins{display:flex;flex-wrap:wrap;gap:6px;margin:12px 0}.reason-bin{padding:10px;border:1px dashed #54779d;border-radius:6px;background:#f2f5fa;font-size:12px}.reason-card{padding:12px;margin:10px 0;border:1px solid var(--line);border-radius:6px;font-size:12px}.reason-card p{white-space:pre-wrap;line-height:1.7;margin:8px 0}.reason-editor{margin-top:20px}.reason-editor .inline-controls{margin-bottom:12px}@media(max-width:1250px){.authoring-layout{grid-template-columns:180px minmax(280px,1fr)}.block-inspector{grid-column:1/-1;display:grid;grid-template-columns:1fr 1fr;gap:12px}.block-inspector h2,.block-inspector fieldset,.block-inspector details,.reason-mapping{grid-column:1/-1}}@media(max-width:720px){.authoring-heading{align-items:flex-start;flex-direction:column}.authoring-layout{display:flex;flex-direction:column}.block-sidebar,.block-inspector,.block-canvas{width:100%}.block-inspector{display:block}.pdf-review{grid-template-columns:1fr}.inline-controls .button{width:100%}.editor-toolbar .button-row{flex-wrap:wrap}}
</style>
