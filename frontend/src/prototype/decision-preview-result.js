import { analysisBrief, successfulObservations } from './decision-conversation-api.js'
import { decimalText } from './decision-agent-api.js'
import { compare } from './exact-money.js'

const DIRECTIONS = new Set(['INCREASE', 'DECREASE', 'UNCHANGED', 'MISSING'])
const FAVORABILITY = new Set(['FAVORABLE', 'UNFAVORABLE', 'NEUTRAL', 'UNKNOWN'])

/** 현재 입력 기준과 같은 실행에서 실제로 성공한 조회만 단일 보고서로 옮긴다.
 * Args: execution 요청·응답·진행 상태, scope 현재 마스터·연도·목표·선택 분석.
 * Returns: 상태·원문 금액·조직 상위 다섯 행·실제 조회 수와 출처를 가진 표시 모델.
 * Raises: 없음. Side Effects: 없음; API·DB·저장·금액 재계산·원본 변경을 하지 않는다.
 * @param {object|null} execution
 * @param {object} scope
 * @returns {object}
 */
export function buildPreviewResult(execution, scope = {}) {
  if (!execution || execution.phase === 'IDLE') return emptyResult('EMPTY', '확정 기준과 목표를 선택해 조회 결과를 확인해 주세요.')
  if (!matchesScope(execution.payload, scope, 'expectedRevision') || !matchesForm(execution, scope)) {
    return emptyResult('STALE', '기준이나 목표 입력이 바뀌었습니다. 현재 조건으로 다시 조회해 주세요.')
  }
  if (execution.phase === 'PENDING') return emptyResult('LOADING', '선택한 기준의 자료를 조회하고 있습니다.')
  const response = execution.response
  if (!response || typeof response !== 'object') return emptyResult('ERROR', '조회 결과를 받지 못했습니다. 다시 조회해 주세요.')
  if (!matchesScope(response.context, scope, 'revision') || response.context.year !== scope.year) {
    return emptyResult('STALE', '현재 선택 기준과 응답 기준이 달라 결과를 표시하지 않았습니다.')
  }
  const candidates = []
  for (const value of Array.isArray(response.observations) ? response.observations : []) {
    if (value && typeof value === 'object') candidates.push(value)
  }
  const observations = successfulObservations({ ...response, observations: candidates })
  const brief = analysisBrief({ ...response, observations: candidates }, execution.payload?.message)
  const complete = execution.phase === 'RESULT' && response.mode === 'MODEL' && response.status === 'COMPLETED'
  const state = observations.length ? complete ? 'READY' : 'PARTIAL' : brief ? 'PARTIAL' : complete ? 'EMPTY' : 'ERROR'
  const result = emptyResult(state, state === 'READY' ? '실제로 조회한 자료를 같은 기준으로 정리했습니다.'
    : state === 'PARTIAL' ? '답변이 완료되지 않아 실제로 조회한 자료만 표시합니다.' : '표시할 성공 조회 자료가 없습니다.')
  result.context = { ...response.context }
  result.brief = brief
  result.status = response.status
  result.warnings = warningTexts(response.limitations)
  let planDifferences = null
  for (const observation of observations) {
    result.sources.push({ id: observation.id, label: textOrNull(observation.label) || textOrNull(observation.tool) || observation.id })
    const capturedAt = dateText(observation.basis?.capturedAt)
    if (capturedAt) result.capturedAt = capturedAt
    appendWarnings(result.warnings, observation.warnings)
    if (observation.tool === 'goal_preview') result.goal = observedGoal(observation, response.context, execution)
    if (observation.tool === 'organization_pnl') {
      const organizations = observedOrganizations(observation)
      result.organizations = organizations.rows
      result.organizationCount = organizations.count
      if (Array.isArray(observation.data?.summary?.rankedByOpPlanDifference)) result.organizationSource = 'organization_pnl'
    }
    if (observation.tool === 'profit_drivers') {
      const differences = observedPlanDifferences(observation)
      if (differences && (!planDifferences || differences.rows.length >= planDifferences.rows.length)) planDifferences = differences
    }
  }
  result.explanationCount = observedExplanationCount(observations)
  if (result.organizationSource !== 'organization_pnl' && planDifferences) {
    result.organizations = planDifferences.rows
    result.organizationCount = null
    result.organizationSource = 'profit_drivers'
  }
  return result
}

/** 금액 없는 상태의 일관된 표시 모델을 만든다.
 * Args: state 표시 상태, message 상태 설명. Returns: 빈 보고서.
 * Raises: 없음. Side Effects: 없음.
 */
function emptyResult(state, message) {
  return { state, context: null, capturedAt: null, goal: null, organizations: [], organizationCount: null, organizationSource: null,
    explanationCount: null, warnings: [], message, sources: [], brief: null, status: null }
}

/** 요청 또는 응답의 마스터·분석·revision이 현재 범위와 정확히 같은지 검사한다.
 * Args: value 요청/응답 기준, scope 현재 범위, revisionKey 버전 필드 이름. Returns: 일치 여부.
 * Raises: 없음. Side Effects: 없음.
 */
function matchesScope(value, scope, revisionKey) {
  return Boolean(value && typeof scope.masterId === 'string' && scope.masterId
    && value.masterId === scope.masterId && (value.analysisId ?? null) === (scope.analysisId ?? null)
    && (value[revisionKey] ?? null) === (scope.revision ?? null))
}

/** 폼에서 요청한 경우에만 당시 목표 입력과 현재 입력을 대조한다.
 * Args: execution 실행, scope 현재 입력. Returns: 목표·연도·마스터 일치 여부.
 * Raises: 없음. Side Effects: 없음; CHAT의 실제 조회 목표를 폼 값으로 덮지 않는다.
 */
function matchesForm(execution, scope) {
  if (execution.source !== 'FORM') return true
  const input = execution.goalInput
  const previous = normalizedGoal(input?.targetProfitEok), current = normalizedGoal(scope.targetProfitEok)
  return Boolean(input && input.masterId === scope.masterId && input.year === scope.year && previous && previous === current)
}

/** 목표 입력의 표현만 정규화하고 도구의 억원 범위를 검사한다.
 * Args: value 목표 억원 문자열. Returns: 정규화 문자열 또는 null.
 * Raises: 없음. Side Effects: 없음; 단위 변환·금액 계산을 하지 않는다.
 */
function normalizedGoal(value) {
  try {
    const normalized = decimalText(value), [whole, fraction = ''] = normalized.split('.')
    return normalized !== '0' && whole.length <= 16 && fraction.length <= 8 ? normalized : null
  } catch { return null }
}

/** 실제 저장 없는 목표 도구의 완전한 금액 응답만 보존한다.
 * Args: observation 성공 관찰, context 고정 기준, execution 요청 입력.
 * Returns: 원문 목표 비교 또는 null. Raises: 없음. Side Effects: 없음; 부족액·초과액을 다시 계산하지 않는다.
 */
function observedGoal(observation, context, execution) {
  const data = observation.data
  if (!data || data.saved !== false || data.unit !== 'KRW' || data.basis !== 'FIXED_ANNUAL_OUTLOOK'
    || data.year !== context.year || data.baseMonth !== context.baseMonth || typeof data.forecastMeetsTarget !== 'boolean') return null
  for (const key of ['baselineProfitWon', 'targetProfitWon', 'gapWon', 'surplusWon']) if (!moneyText(data[key])) return null
  if (compare(data.targetProfitWon, '0') <= 0 || compare(data.gapWon, '0') < 0 || compare(data.surplusWon, '0') < 0) return null
  if (execution.source === 'FORM' && normalizedGoal(data.targetProfitEok) !== normalizedGoal(execution.goalInput?.targetProfitEok)) return null
  return { baselineProfitWon: data.baselineProfitWon, targetProfitWon: data.targetProfitWon,
    gapWon: data.gapWon, surplusWon: data.surplusWon, forecastMeetsTarget: data.forecastMeetsTarget,
    basis: data.basis, capturedAt: dateText(observation.basis?.capturedAt) }
}

/** 실제 조직 요약을 복사하고 계획 차이를 정밀 내림차순으로 정렬한다.
 * Args: observation 성공 조직 조회. Returns: 상위 다섯 행과 전체 범위 수.
 * Raises: 없음. Side Effects: 없음; 원본 순위·금액·업무 판정을 변경하거나 새로 계산하지 않는다.
 */
function observedOrganizations(observation) {
  const summary = observation.data?.summary, values = summary?.rankedByOpPlanDifference
  const count = countOrNull(summary?.scopeOrganizationCount)
  if (!Array.isArray(values)) return { rows: [], count: count === 0 ? 0 : null }
  const rows = [], ids = new Set()
  for (const value of values) {
    const organization = value?.organization, id = textOrNull(organization?.id)
    if (!id || ids.has(id)) continue
    ids.add(id)
    const actualWon = moneyText(value.actualWon), planDifferenceWon = moneyText(value.planDifferenceWon)
    rows.push({ id, name: textOrNull(organization.name), code: textOrNull(organization.code), actualWon, planDifferenceWon,
      favorability: FAVORABILITY.has(value.favorability) ? value.favorability : null,
      direction: DIRECTIONS.has(value.direction) ? value.direction : null,
      missing: value.missing === true || actualWon === null || planDifferenceWon === null, sourceObservationId: observation.id })
  }
  rows.sort(byPlanDifference)
  return { rows: rows.slice(0, 5), count: count ?? rows.length }
}

/** 조직 전체 조회가 없을 때 실제 손익 차이 조회의 정밀 비교액만 보존한다.
 * Args: observation 같은 기준의 성공한 profit_drivers 관찰.
 * Returns: 조회된 조직의 최대 다섯 차이 행 또는 null. Raises: 없음.
 * Side Effects: 없음; 현재 전망·전체 조직 수·비교 방향을 추정하거나 금액을 재계산하지 않는다.
 */
function observedPlanDifferences(observation) {
  const values = observation.data?.items
  if (!Array.isArray(values)) return null
  const rows = [], ids = new Set()
  for (const value of values) {
    const organization = value?.organization, id = textOrNull(organization?.id)
    if (!id || ids.has(id)) continue
    ids.add(id)
    const planDifferenceWon = moneyText(value.opDiff)
    rows.push({ id, name: textOrNull(organization.name), code: textOrNull(organization.code), actualWon: null,
      planDifferenceWon, direction: null, favorability: null, missing: planDifferenceWon === null,
      comparisonOnly: true, sourceObservationId: observation.id })
  }
  rows.sort(byPlanDifference)
  return { rows: rows.slice(0, 5) }
}

/** 실제 조회한 수락 소명을 전역 근거 ID로 중복 제거해 집계한다.
 * Args: observations 현재 기준에서 검증된 성공 관찰 목록.
 * Returns: 고유 조회 건수, 미조회·식별 불가이면 null. Raises: 없음.
 * Side Effects: 없음; 서버 refId는 review_explanation의 전역 기본키에서 만들어지며 조직·조회별 개수가 아니다.
 */
function observedExplanationCount(observations) {
  const ids = new Set()
  let queried = false
  for (const observation of observations) {
    if (observation.tool !== 'approved_explanations') continue
    if (!Array.isArray(observation.data?.items)) return null
    queried = true
    for (const item of observation.data.items) {
      const id = textOrNull(item?.refId)
      if (!id) return null
      ids.add(id)
    }
  }
  return queried ? ids.size : null
}

/** 비교 자료가 있는 조직을 부호 유지 계획 차이 큰 순으로 비교한다.
 * Args: left 왼쪽 행, right 오른쪽 행. Returns: 정렬 비교값.
 * Raises: 없음. Side Effects: 없음; 금액을 Number로 변환하지 않는다.
 */
function byPlanDifference(left, right) {
  if (left.missing !== right.missing) return left.missing ? 1 : -1
  if (!left.missing) {
    const difference = compare(right.planDifferenceWon, left.planDifferenceWon)
    if (difference) return difference
  }
  const a = left.code || left.id, b = right.code || right.id
  return a < b ? -1 : a > b ? 1 : 0
}

/** 정밀 금액 문자열만 허용하고 누락·지수·숫자 JSON을 거부한다.
 * Args: value 원문 금액. Returns: 원문 문자열 또는 null.
 * Raises: 없음. Side Effects: 없음.
 */
function moneyText(value) { return typeof value === 'string' && /^-?(?:0|[1-9][0-9]{0,39})(?:\.[0-9]{1,14})?$/.test(value) ? value : null }

/** 실제 제공된 비어 있지 않은 문자열을 보존한다.
 * Args: value 값. Returns: 원문 문자열 또는 null. Raises: 없음. Side Effects: 없음.
 */
function textOrNull(value) { return typeof value === 'string' && value.trim() ? value : null }

/** 원문에 존재하는 유효한 조회 시각만 보존한다.
 * Args: value ISO 시각. Returns: 원문 문자열 또는 null. Raises: 없음. Side Effects: 없음; 현재 시각을 생성하지 않는다.
 */
function dateText(value) { return typeof value === 'string' && Number.isFinite(Date.parse(value)) ? value : null }

/** 미조회와 실제 영 건을 구분하는 개수 검증이다.
 * Args: value 응답 개수. Returns: 비음수 정수 또는 null. Raises: 없음. Side Effects: 없음.
 */
function countOrNull(value) { return Number.isSafeInteger(value) && value >= 0 ? value : null }

/** 실제 응답의 경고 문자열만 중복 없이 복사한다.
 * Args: values 응답 경고. Returns: 경고 사본. Raises: 없음. Side Effects: 없음.
 */
function warningTexts(values) { const result = []; appendWarnings(result, values); return result }

/** 표시 모델의 로컬 경고 목록에 실제 경고만 추가한다.
 * Args: target 새 표시 목록, values 원문 경고. Returns: 없음.
 * Raises: 없음. Side Effects: target만 갱신하며 원본 응답은 변경하지 않는다.
 */
function appendWarnings(target, values) {
  for (const value of Array.isArray(values) ? values : []) if (textOrNull(value) && !target.includes(value)) target.push(value)
}
