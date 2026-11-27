import { formatMoney } from '../lib/decimal-display.js'
import { compare } from './exact-money.js'
import { decimalText } from './decision-agent-api.js'
const ACTIONS = { OPEN_DISCOVERY: { label: '근거 조사·제안 열기', tab: 'discovery' }, OPEN_SCENARIOS: { label: '시나리오 비교 열기', tab: 'scenarios' }, OPEN_WORKFLOW: { label: '조직 검토·실행 열기', tab: 'workflow' } }
const HISTORY_TOOLS = new Set(['analysis_context', 'company_pnl', 'profit_drivers', 'organization_pnl', 'pnl_waterfall', 'approved_explanations', 'submission_versions', 'submission_comparison', 'scenario_preview', 'work_items', 'review_request_draft', 'goal_preview', 'observation_detail'])
const MONEY_FIELDS = {
  company_pnl: ['actual', 'plan', 'vsPlanAmount', 'vsPreviousAmount', 'op', 'revenue', 'cost', 'total'],
  profit_drivers: ['opDiff', 'actualOp', 'planOp', 'actual', 'plan', 'total'],
  organization_pnl: ['revenue', 'op', 'opDiff', 'vsPlanAmount', 'vsPreviousAmount', 'actual', 'plan', 'operatingProfit'],
  pnl_waterfall: ['total', 'amount', 'value', 'start', 'end', 'base', 'current'],
  submission_comparison: ['baseAmount', 'targetAmount', 'currentAmount', 'previousAmount', 'diff', 'gapAmount'],
}
const LABELS = { name: '이름', label: '항목', organizationName: '조직', organizationId: '조직 ID', code: '코드', actual: '현재 전망', plan: '계획', diff: '차이', gap: '차이', rate: '비율', revenueWon: '매출', costWon: '비용', profitWon: '영업이익', baselineProfitWon: '기준 영업이익', targetProfitWon: '목표 영업이익', netEffectWon: '추가 이익', title: '제목', content: '내용', text: '근거 내용', note: '검토 내용', status: '상태', state: '업무 상태', category: '분류', id: 'ID', amount: '금액', value: '값', year: '연도', month: '월', baseMonth: '기준 월', submissionId: '제출 ID' }
Object.assign(LABELS, { dataStatus: '자료 상태', kpis: '전사 손익 지표', rows: '손익 상세', items: '조회 내역', columns: '조직별 손익', total: '전체 합계', organizationCode: '조직 코드', opDiff: '영업이익 차이', opDiffRate: '영업이익 차이율 (%)', revenue: '매출', op: '영업이익', opContributionRate: '영업이익 기여도 (%)', opMarginRate: '영업이익률 (%)', opMargin: '영업이익률 (%)', vsPlanAmount: '계획 대비 차이', snapshot: '고정 기준', result: '계산 결과', message: '확인 요청 내용', sourceKind: '근거 유형', sourceId: '근거 ID', needsConfirmation: '사용자 확인 필요', analysisId: '분석 ID', expectedAnalysisRevision: '분석 버전' })
Object.assign(LABELS, { key: '손익 항목', actualWon: '현재 연간 전망', planDifferenceWon: '계획 대비 차이', direction: '변동 방향', favorability: '손익 영향', missing: '비교 자료 누락', rank: '순위', summary: '비교 범위', scopeOrganizationCount: '기준 조직 수', returnedOrganizationCount: '조회 조직 수', targetProfitEok: '입력 목표', gapWon: '목표까지 부족액', surplusWon: '목표 초과 전망액', signedGapWon: '목표 − 현재 전망', forecastMeetsTarget: '연간 전망이 목표 이상', capturedAt: '근거 포착 시각', refId: '근거 ID' })

/** 대화 기준의 변경을 식별한다.
 * Args: masterId 고정 기준, analysis 선택 분석. Returns: 대화 구분 키.
 * Raises: 없음. Side Effects: 없음.
 * @param {string|null} masterId
 * @param {object|null} analysis
 * @returns {string}
 */
export function conversationKey(masterId, analysis) { return `${masterId || ''}:${analysis?.id || ''}:${analysis?.revision || ''}` }

/** 이전 질문·실행 상태·검증된 분석 요약을 제한해 후속 질문의 참조 문맥을 만든다.
 * Args: turns 저장한 화면 문답. Returns: 최대 여덟 개의 2000자 이력.
 * Raises: 없음. Side Effects: 없음; 원문 관찰·수치 인용은 재전송하지 않고 현재 조회에서 재확인한다.
 * @param {object[]} turns
 * @returns {object[]}
 */
export function conversationHistory(turns) {
  const history = []
  for (const turn of turns) if (!turn.pending && (turn.response || turn.error)) {
    if (turn.question) history.push({ role: 'user', content: turn.question.slice(0, 2000) })
    history.push({ role: 'assistant', content: historyExecutionContext(turn.response) })
  }
  return history.slice(-8)
}

/** 이전 실행과 검증된 분석 항목을 후속 지시의 참조 문맥으로만 요약한다.
 * Args: response 이전 응답 또는 undefined. Returns: 2000자 이내의 문맥 문자열.
 * Raises: 없음. Side Effects: 없음; 관찰 원문·수치 인용은 전달하지 않고 과거 분석을 현재 근거로 사용하지 않도록 명시한다.
 */
function historyExecutionContext(response) {
  const status = ['COMPLETED', 'NEEDS_INPUT', 'LIMIT_REACHED', 'FAILED'].includes(response?.status) ? response.status : '응답 미수신'
  const tools = []
  for (const call of response?.toolCalls || []) if (HISTORY_TOOLS.has(call.tool) && ['SUCCESS', 'ERROR', 'REUSED'].includes(call.status)) {
    tools.push(`${call.tool}(${call.status})`)
    if (tools.length === 8) break
  }
  const brief = analysisBrief(response), parts = []
  if (brief) {
    const counts = { FACT: 0, INTERPRETATION: 0, HYPOTHESIS: 0 }
    for (const [index, requirement] of brief.requirements.entries()) parts.push(`요구 ${index + 1} (${requirement.statusLabel}): ${requirement.question.slice(0, 100)}`)
    for (const finding of brief.findings) {
      counts[finding.kind]++
      parts.push(`${finding.kindLabel} ${counts[finding.kind]}: ${finding.text.slice(0, 130)}`)
    }
  }
  return `이전 응답 상태: ${status}. 실제 도구 실행: ${tools.length ? tools.join(', ') : '확인된 실행 없음'}. 이전 조회값·인용과 아래 분석은 현재 사실의 근거가 아닙니다. 후속 지시의 참조 문맥일 뿐이며 필요한 자료를 현재 실행에서 다시 조회하세요.${parts.length ? `\n${parts.join('\n')}` : ''}`.slice(0, 2000)
}

/** 답변 완료 여부와 무관하게 같은 실행 기준에서 성공한 관찰을 찾는다.
 * Args: response 현재 실행 응답. Returns: 중복 없는 성공 관찰 목록.
 * Raises: 없음. Side Effects: 없음; 다른 기준·실패 관찰은 표시 결과에서 제외한다.
 */
export function successfulObservations(response) {
  const context = response?.context, result = [], ids = new Set()
  if (!context?.masterId) return result
  for (const observation of response.observations || []) {
    const basis = observation.basis
    if (!observation.id || ids.has(observation.id) || observation.status !== 'SUCCESS' || !basis || basis.masterId !== context.masterId || basis.year !== context.year || basis.baseMonth !== context.baseMonth || (basis.analysisId || null) !== (context.analysisId || null) || (basis.revision ?? null) !== (context.revision ?? null)) continue
    result.push(observation); ids.add(observation.id)
  }
  return result
}

/** 실행마다 재사용되는 관찰 ID를 문답 ID와 함께 대조해 정확한 출처를 찾는다.
 * Args: turns 현재 기준 문답, executionId 문답 ID, observationId 관찰 ID.
 * Returns: 같은 문답의 성공 관찰 또는 null. Raises: 없음.
 * Side Effects: 없음; 다른 문답의 같은 관찰 ID로 대체하지 않는다.
 * @param {object[]} turns
 * @param {string} executionId
 * @param {string} observationId
 * @returns {object|null}
 */
export function observationForExecution(turns, executionId, observationId) {
  if (!executionId || !observationId) return null
  for (const turn of turns || []) {
    if (turn.id !== executionId) continue
    for (const observation of successfulObservations(turn.response)) if (observation.id === observationId) return observation
    return null
  }
  return null
}

/** 승인 소명 원문을 표 셀에 가두지 않고 읽을 수 있는 본문 목록으로 만든다.
 * Args: observation 승인 소명 관찰. Returns: 최대 20개 원문 카드.
 * Raises: 없음. Side Effects: 없음; 소명 문장을 요약하거나 사실을 추가하지 않는다.
 */
export function observationExplanations(observation) {
  if (observation.tool !== 'approved_explanations' || !Array.isArray(observation.data?.items)) return []
  const items = []
  for (const [index, item] of observation.data.items.slice(0, 20).entries()) {
    if (typeof item?.text !== 'string') continue
    items.push({ id: item.refId || `explanation-${index}`, label: item.label || item.organizationName || '승인된 소명', text: item.text, refId: item.refId || '', organizationId: item.organizationId || '', reviewedAt: item.facts?.reviewedAt || null })
  }
  return items
}

/** 질문과 고정 기준을 공개 읽기 Agent 계약으로 변환한다.
 * Args: masterId 기준, analysis 선택 분석, message 질문, turns 이력.
 * Returns: 요청 DTO. Raises: 필수 기준·질문 오류. Side Effects: 없음.
 * @param {string} masterId
 * @param {object|null} analysis
 * @param {string} message
 * @param {object[]} turns
 * @returns {object}
 */
export function assistantPayload(masterId, analysis, message, turns = []) {
  if (!masterId) throw new Error('대화에 사용할 확정 마스터를 선택해 주세요.')
  if (!message?.trim() || message.trim().length > 2000) throw new Error('질문을 1~2000자로 입력해 주세요.')
  if (analysis && analysis.snapshot.masterId !== masterId) throw new Error('선택 분석과 대화 기준이 다릅니다. 기준을 다시 확인해 주세요.')
  return { masterId, analysisId: analysis?.id || null, expectedRevision: analysis?.revision ?? null, message: message.trim(), history: conversationHistory(turns) }
}

/** 목표 폼의 정확한 억원 입력을 목표 비교와 조직별 계획 차이 조회 질문으로 바꾼다.
 * Args: input 마스터 ID·연도·목표 억원 문자열. Returns: 실제 조회 요청 두 가지를 담은 질문; 고정 기준·조회 전용 정책은 API와 화면에서 별도로 유지한다.
 * Raises: 기준·연도·금액 범위 오류. Side Effects: 저장·금액 반올림 없음.
 * @param {{masterId:string,year:number,targetProfitEok:string}} input
 * @returns {string}
 */
export function goalPreviewQuestion(input) {
  if (!input?.masterId) throw new Error('목표와 비교할 확정 마스터를 선택해 주세요.')
  if (!Number.isInteger(input.year) || input.year < 2000 || input.year > 9999) throw new Error('비교할 목표 연도를 확인해 주세요.')
  const target = decimalText(input.targetProfitEok, '연간 영업이익 목표')
  const [whole, fraction = ''] = target.split('.')
  if (target === '0' || whole.length > 16 || fraction.length > 8) throw new Error('연간 영업이익 목표는 0보다 크고 억원 단위 정수 16자리·소수 8자리 이내로 입력해 주세요.')
  return `${input.year}년 현재 연간 영업이익 전망을 목표 ${target}억 원과 비교해 부족액 또는 초과액을 보여주고, 조직별 영업이익 계획 차이를 보여주세요.`
}

/** 목표 비교가 기존 질문 초안이나 진행 중인 대화를 덮어쓰지 못하게 검사한다.
 * Args: draft 작성 질문, busy 진행 중 여부, disabled 외부 편집 상태, requestedMaster 요청 기준, currentMaster 현재 대화 기준.
 * Returns: 실행 가능 여부. Raises: 없음. Side Effects: 없음.
 */
export function canStartGoalPreview(draft, busy, disabled, requestedMaster, currentMaster) {
  return !busy && !disabled && !String(draft || '').trim() && Boolean(requestedMaster) && requestedMaster === currentMaster
}

/** 동일한 고정 기준의 응답인지 검사한다.
 * Args: response Agent 응답, payload 보낸 요청. Returns: 원본 응답.
 * Raises: 기준·상태 계약 오류. Side Effects: 없음; 설명만 잘못된 경우 원본 관찰을 보존한 실패 응답 사본을 반환한다.
 * @param {object} response
 * @param {object} payload
 * @returns {object}
 */
export function validateAssistantResponse(response, payload) {
  if (!['MODEL', 'UNAVAILABLE'].includes(response?.mode) || !['COMPLETED', 'NEEDS_INPUT', 'LIMIT_REACHED', 'FAILED'].includes(response?.status)) throw new Error('Agent 응답 상태를 확인하지 못했습니다.')
  if (response.context?.masterId !== payload.masterId || (response.context?.analysisId || null) !== payload.analysisId || (response.context?.revision ?? null) !== payload.expectedRevision) throw new Error('요청과 응답의 기준이 달라 답변을 표시하지 않았습니다. 다시 질문해 주세요.')
  if (response.brief != null && !analysisBrief(response, payload.message)) {
    const limitations = []
    for (const value of Array.isArray(response.limitations) ? response.limitations : []) if (typeof value === 'string' && value.trim() && limitations.length < 29) limitations.push(value)
    limitations.push('분석 설명의 근거 연결을 확인하지 못해 설명을 표시하지 않았습니다. 이번 실행의 실제 조회 자료는 아래에서 확인할 수 있습니다.')
    return { ...response, brief: null, status: 'FAILED', answer: successfulObservations(response).length
      ? '조회 자료는 확보했지만 분석 설명을 확인하지 못했습니다.' : '분석 설명을 확인하지 못했습니다. 기준과 질문을 확인해 주세요.', limitations }
  }
  return response
}

/** 실행 한도와 답변 중단을 구분하되 모델 미설정 등 구체 원인을 추정하지 않는다.
 * Args: response 실제 Agent 응답. Returns: 상태를 설명하는 한국어 태그 문구.
 * Raises: 없음. Side Effects: 없음; 원인·재시도 안내는 서버의 안전한 answer를 그대로 표시한다.
 * @param {object|null|undefined} response
 * @returns {string}
 */
export function assistantStatusLabel(response) {
  if (response?.status === 'LIMIT_REACHED') return '실행 한도 도달'
  if (response?.mode === 'UNAVAILABLE') return '답변 생성 중단'
  return { COMPLETED: response?.brief ? '요청 항목 확인 완료' : '근거 조회 완료', NEEDS_INPUT: '추가 정보 필요', FAILED: '조회·답변 처리 실패' }[response?.status] || '상태 확인 필요'
}

/** 인용 경로를 관찰의 자체 속성만 따라 읽는다.
 * Args: observation 관찰, path RFC6901 pointer. Returns: 스칼라 또는 undefined.
 * Raises: 없음. Side Effects: 없음; prototype 속성은 읽지 않는다.
 * @param {object} observation
 * @param {string} path
 * @returns {unknown}
 */
export function citationValue(observation, path) {
  if (typeof path !== 'string' || !path.startsWith('/')) return undefined
  let value = observation
  for (const encoded of path.slice(1).split('/')) {
    if (/~(?![01])/.test(encoded)) return undefined
    const key = encoded.replace(/~1/g, '/').replace(/~0/g, '~')
    if (['__proto__', 'constructor', 'prototype'].includes(key) || value === null || typeof value !== 'object' || !Object.hasOwn(value, key)) return undefined
    value = value[key]
  }
  return value !== null && typeof value === 'object' ? undefined : value
}

/** 응답의 인용이 실제 관찰 값과 연결되는지 확인해 표시용 목록을 만든다.
 * Args: response 서버 응답. Returns: 검증된 인용 목록.
 * Raises: 없음. Side Effects: 없음; 잘못된 인용을 생성하지 않는다.
 * @param {object} response
 * @returns {object[]}
 */
export function answerCitations(response) {
  const rows = []
  for (const citation of response.citations || []) {
    for (const observation of response.observations || []) if (observation.id === citation.observationId && observation.status === 'SUCCESS') {
      const value = citationValue(observation, citation.path)
      if (value === undefined || typeof citation.quote !== 'string' || !citation.quote) continue
      const numeric = typeof value === 'string' && /^-?\d+(?:\.\d+)?$/.test(value)
      if (!(typeof value === 'string' && !numeric ? value.includes(citation.quote) : String(value) === citation.quote)) continue
      const origin = detailCitationOrigin(observation, citation.path, response.observations || [])
      const field = origin.field
      const displayQuote = value === null ? '조회값 없음 (null)' : numeric ? observationCell(field, value, origin.tool) : citation.quote
      rows.push({ ...citation, displayQuote, label: observation.label, field: LABELS[field] || field, observation })
    }
  }
  return rows
}

/** 상세 관찰에서 읽은 값은 원래 도구의 금액 필드 의미로만 표시한다.
 * Args: observation 상세 또는 일반 관찰, path 인용 경로, observations 같은 응답의 관찰들.
 * Returns: 원본 도구명과 표시 필드. Raises: 없음. Side Effects: 재조회·금액 계산 없음.
 */
function detailCitationOrigin(observation, path, observations) {
  const field = path.split('/').at(-1)
  if (observation.tool !== 'observation_detail' || !(path === '/data/value' || path.startsWith('/data/value/'))) return { field, tool: observation.tool }
  const source = observations.find(detailSource)
  /** 상세 조회가 지목한 성공 원천을 찾는다.
   * Args: candidate 같은 응답 관찰. Returns: 일치 여부. Raises: 없음. Side Effects: 없음.
   */
  function detailSource(candidate) { return candidate.id === observation.data?.sourceObservationId && candidate.status === 'SUCCESS' && candidate.tool !== 'observation_detail' }
  return { field: path === '/data/value' ? observation.data?.path?.split('/').at(-1) || field : field, tool: source?.tool || observation.tool }
}

/** 분석 문장의 근거와 요구 항목을 현재 실행에 대조해 표시용 사본을 만든다.
 * Args: response 현재 응답, requestMessage 선택한 실제 사용자 질문.
 * Returns: 정밀 인용이 연결된 분석 또는 null. Raises: 없음.
 * Side Effects: 없음; 사실·해석·가설을 재판정하지 않으며 잘못된 근거를 대신 만들지 않는다.
 * @param {object} response
 * @param {string|null} requestMessage
 * @returns {object|null}
 */
export function analysisBrief(response, requestMessage = null) {
  const brief = response?.brief
  if (!brief || !briefText(brief.summary, 1000) || !Array.isArray(brief.findings) || brief.findings.length > 8
    || !Array.isArray(brief.requirements) || brief.requirements.length < 1 || brief.requirements.length > 6) return null
  const observations = successfulObservations(response), findings = [], findingIds = new Set(), requirements = [], requirementIds = new Set()
  for (const finding of brief.findings) {
    if (!finding || !briefText(finding.id, 100) || findingIds.has(finding.id) || !['FACT', 'INTERPRETATION', 'HYPOTHESIS'].includes(finding.kind)
      || !briefText(finding.text, 700) || !Array.isArray(finding.citations) || finding.citations.length < 1 || finding.citations.length > 4) return null
    if (finding.uncertainty != null && !briefText(finding.uncertainty, 400)) return null
    if (finding.kind !== 'FACT' && !briefText(finding.uncertainty, 400)) return null
    const citations = answerCitations({ observations, citations: finding.citations })
    if (citations.length !== finding.citations.length || new Set(citations.map(citationIdentity)).size !== citations.length) return null
    findingIds.add(finding.id)
    findings.push({ id: finding.id, kind: finding.kind, kindLabel: findingKindLabel(finding.kind), text: finding.text, uncertainty: finding.uncertainty || null, citations })
  }
  for (const requirement of brief.requirements) {
    if (!requirement || !briefText(requirement.id, 100) || requirementIds.has(requirement.id)
      || !briefText(requirement.question, 400) || !briefText(requirement.requestQuote, 500)
      || (typeof requestMessage === 'string' && !requestMessage.includes(requirement.requestQuote))
      || !['ANSWERED', 'NEEDS_INPUT', 'UNSUPPORTED'].includes(requirement.status)
      || !Array.isArray(requirement.findingIds) || requirement.findingIds.length > 8 || new Set(requirement.findingIds).size !== requirement.findingIds.length) return null
    for (const id of requirement.findingIds) if (!findingIds.has(id)) return null
    if (requirement.status === 'ANSWERED' && !requirement.findingIds.length) return null
    if (requirement.status !== 'ANSWERED' && (!briefText(requirement.missingInformation, 400) || !briefText(requirement.followUpQuestion, 400))) return null
    for (const value of [requirement.missingInformation, requirement.followUpQuestion]) if (value != null && !briefText(value, 400)) return null
    requirementIds.add(requirement.id)
    requirements.push({ id: requirement.id, requestQuote: requirement.requestQuote, question: requirement.question,
      status: requirement.status, statusLabel: requirementStatusLabel(requirement.status), findingIds: [...requirement.findingIds],
      missingInformation: requirement.missingInformation || null, followUpQuestion: requirement.followUpQuestion || null })
  }
  const unresolved = requirements.filter(unansweredRequirement)
  if (response.status === 'COMPLETED' && unresolved.length) return null
  return { summary: brief.summary, findings, requirements, unresolved, answeredCount: requirements.length - unresolved.length }
}

/** 분석 계약의 실제 문자열 길이를 확인한다.
 * Args: value 원문, max 최대 문자 수. Returns: 유효 여부. Raises: 없음. Side Effects: 없음.
 */
function briefText(value, max) { return typeof value === 'string' && value.trim().length > 0 && value.length <= max }

/** 같은 원문 인용의 중복 여부를 비교할 키를 만든다.
 * Args: citation 검증 인용. Returns: 식별 문자열. Raises: 없음. Side Effects: 없음.
 */
function citationIdentity(citation) { return JSON.stringify([citation.observationId, citation.path, citation.quote]) }

/** 모델이 구분한 설명의 성격을 표시한다.
 * Args: kind 설명 유형. Returns: 한국어 유형. Raises: 없음. Side Effects: 진위를 새로 판정하지 않는다.
 */
export function findingKindLabel(kind) { return { FACT: '확인된 사실', INTERPRETATION: '근거 해석', HYPOTHESIS: '검토 가설' }[kind] || '분석 내용' }

/** 요구 항목의 실제 해결 상태를 표시한다.
 * Args: status 서버 상태. Returns: 한국어 상태. Raises: 없음. Side Effects: 없음.
 */
export function requirementStatusLabel(status) { return { ANSWERED: '답변됨', NEEDS_INPUT: '추가 확인', UNSUPPORTED: '현재 범위 밖' }[status] || '상태 확인' }

/** 아직 답하지 못한 요구 항목을 구분한다.
 * Args: requirement 검증한 항목. Returns: 미해결 여부. Raises: 없음. Side Effects: 없음.
 */
function unansweredRequirement(requirement) { return requirement.status !== 'ANSWERED' }

/** 서버가 허용한 탐색 동작만 기존 업무 탭으로 연결한다.
 * Args: values 서버 nextActions. Returns: 알려진 탐색 동작 목록.
 * Raises: 없음. Side Effects: 없음; 저장·승인 동작을 만들지 않는다.
 * @param {string[]} values
 * @returns {object[]}
 */
export function answerActions(values) { const result = []; for (const value of new Set(values || [])) if (ACTIONS[value]) result.push({ key: value, ...ACTIONS[value] }); return result }

/** 현재 분석에서 검증한 요청 초안 관찰만 기존 요청 작성 원천으로 변환한다.
 * Args: observation 도구 관찰, analysis 현재 저장 분석. Returns: 요청 원천 또는 null.
 * Raises: 없음. Side Effects: 없음; 금액·기한을 추가하거나 요청을 저장하지 않는다.
 * @param {object} observation
 * @param {object|null} analysis
 * @returns {object|null}
 */
export function reviewDraftSource(observation, analysis) {
  const data = observation?.data
  if (!analysis || observation.tool !== 'review_request_draft' || observation.status !== 'SUCCESS' || data?.needsConfirmation !== true) return null
  if (observation.basis?.masterId !== analysis.snapshot.masterId || observation.basis?.analysisId !== analysis.id || observation.basis?.revision !== analysis.revision || data.analysisId !== analysis.id || data.expectedAnalysisRevision !== analysis.revision) return null
  if (!['candidate', 'hypothesis'].includes(data.sourceKind) || typeof data.sourceId !== 'string' || !data.sourceId || typeof data.organizationId !== 'string' || typeof data.title !== 'string' || typeof data.message !== 'string') return null
  let organizationExists = false
  for (const organization of analysis.snapshot.organizations) if (organization.id === data.organizationId) organizationExists = true
  if (!organizationExists) return null
  return { kind: data.sourceKind, id: data.sourceId, organizationId: data.organizationId, title: data.title, questions: [data.message] }
}

/** 명시적 원 금액은 정밀 억원으로, 다른 스칼라는 원문으로 표시한다.
 * Args: key 필드명, value 값, tool 원 단위 필드 계약을 선택할 도구명.
 * Returns: 표시 문자열. Raises: 없음. Side Effects: 없음.
 * @param {string} key
 * @param {unknown} value
 * @param {string} tool
 * @returns {string}
 */
export function observationCell(key, value, tool = '') {
  if (value == null) return '—'
  if (key === 'dataStatus') return { CONFIRMED: '확정', PROVISIONAL: '잠정' }[value] || String(value)
  if (key === 'sourceKind') return { candidate: '저장 후보', hypothesis: '검토 가설' }[value] || String(value)
  if (key === 'key') return { REVENUE: '매출', COST: '비용', OP: '영업이익' }[value] || String(value)
  if (key === 'direction') return { INCREASE: '증가', DECREASE: '감소', UNCHANGED: '변동 없음', MISSING: '비교 불가' }[value] || String(value)
  if (key === 'favorability') return { FAVORABLE: '유리', UNFAVORABLE: '불리', NEUTRAL: '중립', UNKNOWN: '판단 불가' }[value] || String(value)
  if (key === 'targetProfitEok' && typeof value === 'string') return `${formatMoney(value)}억 원`
  if ((/Won$/.test(key) || MONEY_FIELDS[tool]?.includes(key)) && typeof value === 'string') {
    const sign = ['opDiff', 'vsPlanAmount', 'planDifferenceWon'].includes(key) && compare(value, '0') > 0 ? '+' : ''
    return `${sign}${formatMoney(value, 'HUNDRED_MILLION')}억 원`
  }
  return typeof value === 'boolean' ? value ? '예' : '아니요' : String(value)
}

/** 관찰의 스칼라·행 목록을 기존 ReportDataTable 계약으로 정리한다.
 * Args: observation 실제 도구 관찰, order 조직 OP 차이의 SIGNED_DESC 또는 ABS_DESC 순서.
 * Returns: 표시 표와 제한 안내.
 * Raises: 없음. Side Effects: 없음; 생략한 원문은 접힌 상세에서 확인한다.
 * @param {object} observation
 * @param {string} order
 * @returns {object[]}
 */
export function observationTables(observation, order = 'SIGNED_DESC') {
  const tables = [], data = observation.data
  if (!data || typeof data !== 'object') return tables
  if (observation.tool === 'goal_preview') return goalPreviewTables(data)
  const ranked = order === 'ABS_DESC' ? data.summary?.rankedByAbsoluteOpPlanDifference : data.summary?.rankedByOpPlanDifference || data.summary?.rankedByAbsoluteOpPlanDifference
  if (observation.tool === 'organization_pnl' && Array.isArray(ranked)) tables.push(rowsTable('조직별 영업이익', ranked, observation.tool, 'planDifferenceWon', ['organizationName', 'actualWon', 'planDifferenceWon', 'direction', 'favorability', 'missing'], order))
  if (observation.tool === 'company_pnl' && Array.isArray(data.summary?.metrics)) tables.push(rowsTable('전사 손익의 계획 대비 차이', data.summary.metrics, observation.tool))
  const groups = Array.isArray(data) ? [['조회 결과', data]] : Object.entries(data)
  const facts = []
  for (const [key, value] of groups) {
    if (value == null || typeof value !== 'object') { facts.push([LABELS[key] || key, observationCell(key, value, observation.tool)]); continue }
    if (!Array.isArray(value)) {
      const rows = []
      for (const [field, scalar] of Object.entries(displayRow(value))) rows.push([LABELS[field] || field, observationCell(field, scalar, observation.tool)])
      if (rows.length) tables.push({ label: LABELS[key] || key, total: rows.length, table: { columns: ['항목', '조회 값'], rows: rows.slice(0, 20) } })
      continue
    }
    if (observation.tool === 'approved_explanations' && key === 'items') continue
    const orderKey = observation.tool === 'profit_drivers' && key === 'items' ? 'opDiff' : observation.tool === 'organization_pnl' && key === 'columns' ? 'vsPlanAmount' : null
    tables.push(rowsTable(LABELS[key] || key, value, observation.tool, orderKey, [], order))
  }
  if (facts.length) tables.push({ label: '조회 요약', total: facts.length, table: { columns: ['항목', '조회 값'], rows: facts.slice(0, 20) } })
  return tables.slice(0, 4)
}

/** 도구의 행 목록을 정밀 비교와 명확한 순서 안내가 있는 표로 만든다.
 * Args: label 표 제목, values 원본 행, tool 도구명, orderKey OP 차이 필드, preferred 우선 열, order 순서.
 * Returns: 최대 20행·8열 표시 표. Raises: 없음. Side Effects: 복사 행만 정렬한다.
 */
function rowsTable(label, values, tool, orderKey = null, preferred = [], order = 'SIGNED_DESC') {
  if (values.every(isScalar)) return { label, total: values.length, table: { columns: ['내용'], rows: values.slice(0, 20).map(scalarRow) } }
  const ordered = values.slice()
  /** 영업이익 계획 차이를 선택한 순서로 비교하며 누락은 뒤에 둔다.
   * Args: left 오른쪽과 비교할 행, right 비교 행. Returns: 정렬 값.
   * Raises: 없음. Side Effects: 없음; 금액은 Number로 변환하지 않는다.
   */
  function byDifference(left, right) {
    const a = left?.[orderKey], b = right?.[orderKey]
    const validA = typeof a === 'string' && /^-?\d+(?:\.\d+)?$/.test(a), validB = typeof b === 'string' && /^-?\d+(?:\.\d+)?$/.test(b)
    if (!validA || !validB) return validA ? -1 : validB ? 1 : 0
    return order === 'ABS_DESC' ? compare(b.replace(/^-/, ''), a.replace(/^-/, '')) : compare(b, a)
  }
  if (orderKey) ordered.sort(byDifference)
  const visibleRows = [], columns = []
  for (const row of ordered.slice(0, 20)) { const copy = displayRow(row); if (orderKey) delete copy.rank; visibleRows.push(copy) }
  for (const column of preferred) for (const row of visibleRows) if (Object.hasOwn(row, column) && !columns.includes(column)) columns.push(column)
  for (const row of visibleRows) for (const column of Object.keys(row)) if (!columns.includes(column) && columns.length < 8) columns.push(column)
  const headers = [], rows = []
  for (const column of columns) headers.push(LABELS[column] || column)
  for (const row of visibleRows) { const cells = []; for (const column of columns) cells.push(observationCell(column, row?.[column], tool)); rows.push(cells) }
  const suffix = order === 'ABS_DESC' ? '계획 대비 차이 절댓값 큰 순' : '계획 초과액 큰 순'
  const orderNote = orderKey ? order === 'ABS_DESC' ? '영업이익 계획 차이의 절댓값이 큰 순입니다. 큰 계획 미달도 앞에 올 수 있으므로 +/− 부호와 손익 영향을 함께 확인하세요. 비교 자료가 없는 조직은 뒤에 표시합니다.' : '영업이익 전망 − 계획 금액을 큰 순으로 표시합니다. 계획 초과가 먼저, 계획 미달은 뒤에 표시하며 비교 자료가 없는 조직은 마지막에 둡니다.' : ''
  return { label: orderKey ? `${label} · ${suffix}` : label, total: values.length, orderNote, table: { columns: headers, rows } }
}

/** 저장하지 않은 연간 목표 검토 결과를 현재 전망과 나란히 표시한다.
 * Args: data goal_preview 응답. Returns: 목표 검토 표. Raises: 없음. Side Effects: 저장·재계산 없음.
 */
function goalPreviewTables(data) {
  const rows = []
  for (const key of ['baselineProfitWon', 'targetProfitWon', 'gapWon', 'surplusWon', 'signedGapWon', 'forecastMeetsTarget']) if (Object.hasOwn(data, key)) rows.push([LABELS[key], observationCell(key, data[key], 'goal_preview')])
  return [{ label: '연간 영업이익 목표 검토', total: rows.length, table: { columns: ['검토 항목', '조회 결과'], rows } }]
}
/** 표에 필요한 자체 스칼라와 조직 이름만 평탄하게 복사한다.
 * Args: row 원본 행. Returns: 표시용 복사. Raises: 없음. Side Effects: 원본 변경 없음.
 */
function displayRow(row) {
  const output = {}
  if (!row || typeof row !== 'object' || Array.isArray(row)) return output
  if (row.organization?.name) output.organizationName = row.organization.name
  if (row.organization?.code) output.organizationCode = row.organization.code
  for (const [key, value] of Object.entries(row)) if (value == null || typeof value !== 'object') output[key] = value
  return output
}
/** 값이 표의 단일 셀로 표시 가능한지 확인한다.
 * Args: value 값. Returns: 스칼라 여부. Raises: 없음. Side Effects: 없음.
 */
function isScalar(value) { return value === null || typeof value !== 'object' }
/** 단일 값을 표 한 행으로 바꾼다.
 * Args: value 값. Returns: 한 셀 행. Raises: 없음. Side Effects: 없음.
 */
function scalarRow(value) { return [observationCell('', value)] }

/** 인증된 공개 Agent 질문을 호출한다.
 * Args: api 인증 함수, payload 요청, signal 중단 신호. Returns: 검증 응답 Promise.
 * Raises: HTTP·기준 계약 오류. Side Effects: 조회·초안 Tool 실행; 업무 저장 없음.
 * @param {Function} api
 * @param {object} payload
 * @param {AbortSignal} signal
 * @returns {Promise<object>}
 */
export async function askAssistant(api, payload, signal) { return validateAssistantResponse(await api('/api/v1/decision-agent/assistant', { method: 'POST', body: payload, signal }), payload) }
