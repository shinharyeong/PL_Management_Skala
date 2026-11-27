import { formatMoney } from '../lib/decimal-display.js'

export const REPORT_XLSX_TYPE = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
const IDENTIFIER = /^[1-9][0-9]{0,18}$/
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const MONEY = /^-?(?:0|[1-9][0-9]{0,39})(?:\.[0-9]{1,14})?$/
const COMPARISONS = { ACTUAL_VS_PLAN: '실적 vs 계획', ACTUAL_VS_PREVIOUS: '실적 vs 이전 제출', ESTIMATE_VS_PREVIOUS: '추정 vs 이전 추정', ESTIMATE_VS_PLAN: '추정 vs 계획' }

/** 같은 실행의 보고서·소명 관찰을 화면용 결과로 검증해 투영한다.
 * Args: observations 실제 관찰 목록, context 현재 응답 기준. Returns: 보고서·파일·소명 결과 및 형식 오류 수.
 * Raises: 없음. Side Effects: 없음; 원본 수정·새 계산·파일 요청을 하지 않는다.
 */
export function reportToolResults(observations = [], context) {
  const result = { templates: [], previews: [], artifacts: [], explanations: [], invalidCount: 0 }
  const exports = [], seen = new Set()
  for (const observation of observations) {
    if (!sameBasis(observation, context) || seen.has(observation.id)) continue
    seen.add(observation.id)
    const data = observation.data
    if (observation.tool === 'report_templates') {
      if (!Array.isArray(data?.items) || !data.items.every(validTemplate)) { result.invalidCount++; continue }
      result.templates.push({ observationId: observation.id, items: data.items, nextCursor: data.nextCursor || null })
    } else if (observation.tool === 'report_preview') {
      if (!validPreview(data, context)) { result.invalidCount++; continue }
      result.previews.push({ ...data, observationId: observation.id })
    } else if (observation.tool === 'report_export') exports.push(observation)
    else if (observation.tool === 'explanation_overview') {
      if (!validOverview(data, context)) { result.invalidCount++; continue }
      result.explanations.push({ ...data, observationId: observation.id, comparisonLabel: COMPARISONS[data.comparison] })
    }
  }
  for (const observation of exports) {
    const data = observation.data
    const preview = result.previews.find(item => item.observationId === data?.previewObservationId)
    if (!validArtifact(data) || data.masterId !== context.masterId || !preview || data.templateId !== preview.templateId || data.templateVersion !== preview.templateVersion) { result.invalidCount++; continue }
    result.artifacts.push({ ...data, observationId: observation.id })
  }
  return result
}

/** 현재 실행의 성공 관찰인지 기준·분석 버전을 대조한다.
 * Args: observation 도구 관찰, context 응답 기준. Returns: 동일 기준 여부.
 * Raises: 없음. Side Effects: 없음.
 */
function sameBasis(observation, context) {
  const basis = observation?.basis
  return Boolean(context?.masterId && observation?.status === 'SUCCESS' && typeof observation.id === 'string' && basis
    && basis.masterId === context.masterId && basis.year === context.year && basis.baseMonth === context.baseMonth
    && (basis.analysisId ?? null) === (context.analysisId ?? null) && (basis.revision ?? null) === (context.revision ?? null))
}

/** 보고서 자료 틀의 공개 메타데이터 형식을 확인한다.
 * Args: value 자료 틀 메타데이터. Returns: 유효 여부. Raises: 없음. Side Effects: 없음.
 */
function validTemplate(value) {
  return Boolean(value && IDENTIFIER.test(value.templateId) && text(value.name) && positive(value.version)
    && count(value.blockCount) && Array.isArray(value.blocks) && value.blocks.every(block => text(block?.blockId) && text(block.kind)))
}

/** 보고서 채우기의 기준과 서버가 반환한 블록을 확인한다.
 * Args: data 채운 보고서, context 고정 기준. Returns: 유효 여부. Raises: 없음. Side Effects: 없음.
 */
function validPreview(data, context) {
  return Boolean(data && validPeriod(data, context) && IDENTIFIER.test(data.templateId) && positive(data.templateVersion)
    && text(data.templateName) && data.saved === false && ['CONFIRMED', 'PROVISIONAL', 'NONE'].includes(data.dataStatus)
    && (data.blockId == null || text(data.blockId)) && Array.isArray(data.blocks)
    && data.blocks.every(validBlock))
}

/** 기존 보고서 렌더러가 읽는 중첩 배열의 형식을 확인한다.
 * Args: block 서버가 채운 블록. Returns: 안전하게 표시할 수 있는지 여부.
 * Raises: 없음. Side Effects: 내용·숫자를 수정하거나 HTML을 해석하지 않는다.
 */
function validBlock(block) {
  if (!block || !text(block.id) || !text(block.kind)) return false
  if (block.table != null && (typeof block.table !== 'object' || Array.isArray(block.table)
    || (block.table.columns != null && !Array.isArray(block.table.columns))
    || (block.table.headers != null && !Array.isArray(block.table.headers))
    || (block.table.rows != null && !Array.isArray(block.table.rows)))) return false
  const columns = block.table?.columns || block.table?.headers || []
  if (columns.some(column => column == null || !['string', 'object'].includes(typeof column))) return false
  if (block.rows != null && (!Array.isArray(block.rows) && (!Array.isArray(block.rows.headers) || !Array.isArray(block.rows.rows)))) return false
  if (Array.isArray(block.rows) && !block.rows.every(row => row && typeof row === 'object')) return false
  if (block.rows?.headers && block.rows.rows.some(row => !Array.isArray(row))) return false
  return block.cards == null || (Array.isArray(block.cards) && block.cards.every(card => card && typeof card === 'object'))
}

/** 연월·마스터·보고서 뷰 출처를 현재 요청과 대조한다.
 * Args: data 도구 자료, context 고정 기준. Returns: 동일 출처 여부. Raises: 없음. Side Effects: 없음.
 */
function validPeriod(data, context) {
  return data.masterId === context.masterId && data.year === context.year && data.month === context.baseMonth
    && data.sourceVersion === `master-${context.year}-${String(context.baseMonth).padStart(2, '0')}`
}

/** 소명 분류·검토 현황의 수량과 원 단위 금액을 검증한다.
 * Args: data 현황 자료, context 기준. Returns: 유효 여부. Raises: 없음. Side Effects: 합계나 이익 영향을 재계산하지 않는다.
 */
function validOverview(data, context) {
  const summary = data?.summary
  return Boolean(data && validPeriod(data, context) && Object.hasOwn(COMPARISONS, data.comparison)
    && summary && count(summary.acceptedCount) && count(summary.reviewingCount) && count(summary.unclassifiedAcceptedCount)
    && typeof summary.fullyClassified === 'boolean' && money(data.gapTotalWon) && money(data.classifiedTotalWon)
    && typeof data.hasApplicablePeriod === 'boolean' && ['ACTUAL', 'ESTIMATE'].includes(data.itemScenario)
    && (!data.hasApplicablePeriod || (Number.isInteger(data.itemYear) && Number.isInteger(data.itemMonth) && data.itemMonth >= 1 && data.itemMonth <= 12))
    && Array.isArray(data.categories?.items)
    && typeof data.matchesGapTotal === 'boolean' && count(data.offset) && positive(data.limit)
    && typeof data.hasMore === 'boolean' && (data.nextOffset == null || count(data.nextOffset))
    && Array.isArray(data.categoryTotals) && data.categoryTotals.every(item => text(item?.categoryId) && text(item.name) && money(item.gapAmountWon) && typeof item.subtotal === 'boolean')
    && Array.isArray(data.items) && data.items.every(item => text(item?.explanationId) && item.organization && text(item.organization.name) && text(item.item) && typeof item.answer === 'string' && money(item.gapAmount))
    && Array.isArray(data.reviewing) && data.reviewing.every(item => item?.organization && text(item.organization.name) && text(item.item) && money(item.gapAmount)))
}

/** 인증 다운로드로 이어질 서버 산출물 메타데이터를 검사한다.
 * Args: value 산출물 자료. Returns: 유효 여부. Raises: 없음. Side Effects: URL을 신뢰하거나 파일에 접근하지 않는다.
 */
export function validArtifact(value) {
  return Boolean(value && UUID.test(value.artifactId) && IDENTIFIER.test(value.masterId) && IDENTIFIER.test(value.templateId)
    && positive(value.templateVersion) && value.ready === true && value.saved === false && value.format === 'XLSX'
    && value.contentType === REPORT_XLSX_TYPE && positive(value.byteSize) && value.byteSize <= 32 * 1024 * 1024
    && text(value.filename) && value.filename.length <= 255 && /\.xlsx$/i.test(value.filename) && !/[\\/\u0000-\u001f\u007f]/.test(value.filename)
    && typeof value.expiresAt === 'string' && Number.isFinite(Date.parse(value.expiresAt)))
}

/** 산출물 유효 기간이 지났는지 확인한다.
 * Args: artifact 메타데이터, now 현재 시각 밀리초. Returns: 만료·형식 오류 여부.
 * Raises: 없음. Side Effects: 없음.
 */
export function artifactExpired(artifact, now = Date.now()) { return !validArtifact(artifact) || Date.parse(artifact.expiresAt) <= now }

/** 서버의 동일 파일을 인증 경로로 내려받아 형식·크기를 검증한다.
 * Args: api 인증 HTTP 함수, artifact 검증한 산출물, signal 중단 신호, now 검증 시각.
 * Returns: 검증된 XLSX Blob Promise. Raises: 만료·HTTP·파일 계약 오류 및 AbortError.
 * Side Effects: 고정 API의 GET 한 번; DB 변경·외부 URL 접속·파일 저장 없음.
 */
export async function fetchReportArtifact(api, artifact, signal, now = Date.now()) {
  if (artifactExpired(artifact, now)) throw new Error('보고서 다운로드가 만료됐습니다. 같은 기준으로 다시 생성해 주세요.')
  signal?.throwIfAborted()
  const blob = await api(`/api/v1/decision-agent/report-artifacts/${artifact.artifactId}`, { blob: true, signal })
  signal?.throwIfAborted()
  if (!(blob instanceof Blob) || blob.size !== artifact.byteSize || blob.type !== REPORT_XLSX_TYPE) throw new Error('보고서 파일의 형식이나 크기를 확인하지 못했습니다. 다시 생성해 주세요.')
  return blob
}

/** 사용자 클릭으로 확인한 파일의 브라우저 다운로드를 시작한다.
 * Args: blob 검증 파일, filename 표시 이름, document DOM, urls 객체 URL 구현.
 * Returns: 없음. Raises: DOM·브라우저 다운로드 오류. Side Effects: 임시 링크를 클릭한 뒤 링크와 객체 URL을 항상 해제한다.
 */
export function saveReportBlob(blob, filename, document = globalThis.document, urls = globalThis.URL) {
  const objectUrl = urls.createObjectURL(blob)
  let anchor
  try {
    anchor = document.createElement('a'); anchor.href = objectUrl; anchor.download = filename
    anchor.hidden = true; document.body.appendChild(anchor); anchor.click()
  } finally { anchor?.remove(); urls.revokeObjectURL(objectUrl) }
}

/** 소명 차이의 원 단위 정밀 값을 표시한다.
 * Args: value 원 금액. Returns: 억 원 서식 또는 대시. Raises: 없음. Side Effects: 금액 합산·이익 영향 변환 없음.
 */
export function reportToolMoney(value) { return money(value) ? `${formatMoney(value, 'HUNDRED_MILLION')}억 원` : '—' }

/** 비어 있지 않은 문자열인지 확인한다.
 * Args: value 입력. Returns: 유효 여부. Raises: 없음. Side Effects: 없음.
 */
function text(value) { return typeof value === 'string' && Boolean(value.trim()) }
/** 비음수 안전 정수인지 확인한다.
 * Args: value 입력. Returns: 유효 여부. Raises: 없음. Side Effects: 없음.
 */
function count(value) { return Number.isSafeInteger(value) && value >= 0 }
/** 양의 안전 정수인지 확인한다.
 * Args: value 입력. Returns: 유효 여부. Raises: 없음. Side Effects: 없음.
 */
function positive(value) { return count(value) && value > 0 }
/** 원 단위 십진 문자열인지 확인한다.
 * Args: value 입력. Returns: 유효 여부. Raises: 없음. Side Effects: 없음.
 */
function money(value) { return typeof value === 'string' && MONEY.test(value) }
