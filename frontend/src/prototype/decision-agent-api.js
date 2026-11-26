const ROOT = '/api/v1/decision-agent'
const CATEGORIES = ['OPERATIONS', 'PROCUREMENT', 'REVENUE', 'HEADCOUNT', 'RND', 'UNKNOWN']
const EXCLUSION_LABELS = {
  PROTECTED_CATEGORY: '보호할 비용에 해당',
  UNCLASSIFIED_PROTECTED_SCOPE: '보호 제약 확인에 필요한 분류 없음',
  ALREADY_ACTUAL_PERIOD: '기준 월까지 이미 지난 기간',
  OUTSIDE_TARGET_YEAR: '목표 연도 밖에 반영',
  DUPLICATE_EFFECT: '다른 후보와 효과 중복',
  NON_POSITIVE_NET_EFFECT: '차감 후 추가 이익이 0 이하',
}

/** 서버의 정확한 오류 코드로만 읽기 전용 환경을 식별한다.
 * Args: cause API 오류. Returns: 읽기 전용 안내 여부. Raises: 없음. Side Effects: 없음.
 * @param {object|null} cause
 * @returns {boolean}
 */
export function isReadOnlyPreviewError(cause) { return cause?.code === 'READ_ONLY_PREVIEW' }

/** 환경에 맞춰 저장 없는 비교 또는 기존 분석 생성 흐름 하나만 실행한다.
 * Args: readOnly 정확히 확인한 조회 전용 여부, preview 비교 콜백, create 기존 저장 콜백.
 * Returns: 선택한 작업의 반환값. Raises: 선택한 작업 오류. Side Effects: 선택 콜백만 실행한다.
 * @param {boolean} readOnly
 * @param {Function} preview
 * @param {Function} create
 * @returns {unknown}
 */
export function startAnalysisAction(readOnly, preview, create) { return readOnly === true ? preview() : create() }

/** 서버의 제외 사유 코드를 검토자가 이해할 수 있는 설명으로 바꾼다.
 * Args: reasons 서버 제외 사유 배열. Returns: 사유 설명 문장.
 * Raises: 없음. Side Effects: 없음; 알 수 없는 사유도 누락하지 않는다.
 * @param {string[]} reasons
 * @returns {string}
 */
export function exclusionText(reasons) {
  const labels = []
  for (const reason of reasons || []) labels.push(EXCLUSION_LABELS[reason] || '추가 제외 사유 확인 필요')
  return labels.join(' · ')
}

/** 십진 입력을 금액으로 해석할 수 있는지 확인하고 표준 문자열을 만든다.
 * Args: value 쉼표 없는 십진 문자열, label 오류에 표시할 필드명.
 * Returns: 앞뒤 0을 정리한 십진 문자열. Raises: 잘못된 형식의 Error.
 * Side Effects: 없음; Number 변환을 하지 않는다.
 * @param {string} value
 * @param {string} label
 * @returns {string}
 */
export function decimalText(value, label = '금액') {
  if (typeof value !== 'string' || !/^\d+(?:\.\d+)?$/.test(value.trim())) throw new Error(`${label}은 0 이상의 숫자로 입력해 주세요. 쉼표와 지수 표기는 사용할 수 없습니다.`)
  const [whole, fraction = ''] = value.trim().split('.')
  const integer = whole.replace(/^0+(?=\d)/, '')
  const tail = fraction.replace(/0+$/, '')
  return integer + (tail ? `.${tail}` : '')
}

/** 억원 입력의 소수점을 여덟 자리 이동하여 원 단위로 정확히 변환한다.
 * Args: value 억원 십진 문자열, label 오류 필드명.
 * Returns: 원 단위 십진 문자열. Raises: 입력 형식 오류.
 * Side Effects: 없음; 반올림하지 않는다.
 * @param {string} value
 * @param {string} label
 * @returns {string}
 */
export function eokToWon(value, label = '금액') {
  const [whole, fraction = ''] = decimalText(value, label).split('.')
  const digits = whole + fraction.padEnd(8, '0')
  const split = whole.length + 8
  const won = decimalText(digits.slice(0, split) + (digits.length > split ? `.${digits.slice(split)}` : ''), label)
  const [integer, decimal = ''] = won.split('.')
  if (integer.length > 24 || decimal.length > 6) throw new Error(`${label}은 원 단위 정수 24자리·소수 6자리 이내로 입력해 주세요.`)
  return won
}

/** 서버 원 단위 금액을 편집 가능한 억원 십진 문자열로 변환한다.
 * Args: value 원 단위 십진 문자열.
 * Returns: 억원 문자열. Raises: 입력 형식 오류.
 * Side Effects: 없음; Number 변환·반올림하지 않는다.
 * @param {string} value
 * @returns {string}
 */
export function wonToEok(value) {
  const [whole, fraction = ''] = decimalText(value).split('.')
  const digits = whole.padStart(9, '0')
  return decimalText(`${digits.slice(0, -8)}.${digits.slice(-8)}${fraction}`)
}

/** 서버 후보를 화면 편집 필드로 복사한다.
 * Args: candidate 저장된 후보.
 * Returns: 원본을 참조하지 않는 편집 후보. Raises: 서버 금액 형식 오류.
 * Side Effects: 없음.
 * @param {object} candidate
 * @returns {object}
 */
export function candidateDraft(candidate) {
  return {
    id: candidate.id, organizationId: candidate.organizationId, title: candidate.title,
    type: candidate.type, amountEok: wonToEok(candidate.amountWon),
    contributionMarginPercent: candidate.contributionMarginPercent ?? '',
    implementationCostEok: wonToEok(candidate.implementationCostWon),
    embeddedEffectEok: wonToEok(candidate.embeddedEffectWon),
    recognitionMonth: candidate.recognitionMonth, category: candidate.category,
    duplicateGroup: candidate.duplicateGroup || '',
    reviewed: candidate.evidenceStatus === 'REVIEWED',
    evidenceNote: candidate.evidenceNote || '', evidenceRef: candidate.evidenceRef || '',
  }
}

/** 새 후보의 빈 편집 상태를 만든다.
 * Args: id 화면에서 발급한 후보 ID.
 * Returns: 효과·조직·시점이 미입력된 후보. Raises: 없음.
 * Side Effects: 없음; 예시 금액이나 가상 조직을 넣지 않는다.
 * @param {string} id
 * @returns {object}
 */
export function emptyCandidate(id) {
  return { id, organizationId: null, title: '', type: 'NET_PROFIT', amountEok: '', contributionMarginPercent: '', implementationCostEok: '0', embeddedEffectEok: '0', recognitionMonth: null, category: 'UNKNOWN', duplicateGroup: '', reviewed: false, evidenceNote: '', evidenceRef: '' }
}

/** 편집 후보를 검증하고 저장 DTO를 만든다. 최종 적격 여부는 서버가 계산한다.
 * Args: draft 편집 후보, snapshot 고정한 기준 조직과 월.
 * Returns: 원 단위 문자열의 후보 DTO. Raises: 필수 입력·조직·금액 오류.
 * Side Effects: 없음.
 * @param {object} draft
 * @param {object} snapshot
 * @returns {object}
 */
export function candidatePayload(draft, snapshot) {
  if (!draft.title.trim()) throw new Error('모든 후보의 과제명을 입력해 주세요.')
  let organizationExists = false
  for (const organization of snapshot.organizations) if (organization.id === draft.organizationId) organizationExists = true
  if (!organizationExists) throw new Error(`${draft.title}: 기준 마스터에 포함된 조직을 선택해 주세요.`)
  if (!['NET_PROFIT', 'REVENUE'].includes(draft.type) || !CATEGORIES.includes(draft.category)) throw new Error(`${draft.title}: 효과 유형과 비용 분류를 확인해 주세요.`)
  if (!Number.isInteger(draft.recognitionMonth) || draft.recognitionMonth <= snapshot.baseMonth || draft.recognitionMonth > 12) throw new Error(`${draft.title}: 기준 월 이후부터 12월 사이의 반영 월을 선택해 주세요.`)
  const amountWon = eokToWon(draft.amountEok, `${draft.title} 효과 금액`)
  if (amountWon === '0') throw new Error(`${draft.title}: 효과 금액은 0보다 커야 합니다.`)
  const margin = draft.type === 'REVENUE' ? decimalText(draft.contributionMarginPercent, '공헌이익률') : null
  if (margin !== null) {
    const [whole, fraction = ''] = margin.split('.')
    if (BigInt(whole) > 100n || (BigInt(whole) === 100n && /[1-9]/.test(fraction))) throw new Error('공헌이익률은 0~100% 사이로 입력해 주세요.')
    if (fraction.length > 6) throw new Error('공헌이익률은 소수 6자리 이내로 입력해 주세요.')
  }
  if (draft.reviewed && (!draft.evidenceNote.trim() || !draft.evidenceRef.trim())) throw new Error(`${draft.title}: 근거 검토를 표시하려면 검토 내용과 근거 위치를 모두 입력해 주세요.`)
  return {
    id: draft.id, organizationId: draft.organizationId, title: draft.title.trim(), type: draft.type,
    amountWon, contributionMarginPercent: margin,
    implementationCostWon: eokToWon(draft.implementationCostEok, '실행 부대비용'),
    embeddedEffectWon: eokToWon(draft.embeddedEffectEok, '기존 전망에 반영된 효과'),
    recognitionMonth: draft.recognitionMonth, category: draft.category,
    duplicateGroup: draft.duplicateGroup.trim(), evidenceStatus: draft.reviewed ? 'REVIEWED' : 'ASSUMPTION',
    evidenceNote: draft.evidenceNote.trim(), evidenceRef: draft.evidenceRef.trim(),
  }
}

/** 목표 생성 입력을 서버 계약으로 변환한다.
 * Args: form 제목·요청·마스터·억원 목표·보호 제약.
 * Returns: 분석 생성 DTO. Raises: 필수 값 또는 금액 오류.
 * Side Effects: 없음.
 * @param {object} form
 * @returns {object}
 */
export function analysisPayload(form) {
  if (!form.title.trim() || !form.prompt.trim() || !form.masterId) throw new Error('분석 이름, 경영 요청, 확정 마스터를 모두 입력해 주세요.')
  const targetProfitWon = eokToWon(form.targetEok, '연간 영업이익 목표')
  if (targetProfitWon === '0') throw new Error('연간 영업이익 목표는 0보다 커야 합니다.')
  return { title: form.title.trim(), prompt: form.prompt.trim(), masterId: form.masterId, targetProfitWon, protectedCategories: [...form.protectedCategories] }
}

/** 화면 작업을 의사결정 Agent의 공개 API로 전송한다.
 * Args: api 인증 요청 함수, operation 작업명, payload 작업별 입력.
 * Returns: 서버 응답 Promise. Raises: 지원하지 않는 작업 또는 HTTP 오류.
 * Side Effects: 조회 또는 명시한 분석·후보·실행 상태 저장.
 * @param {Function} api
 * @param {'baselines'|'analyses'|'create'|'load'|'save'|'run'} operation
 * @param {object} payload
 * @returns {Promise<object>}
 */
export function decisionRequest(api, operation, payload = {}) {
  if (operation === 'baselines') return api(`${ROOT}/baselines?year=${encodeURIComponent(payload.year)}`)
  if (operation === 'analyses') return api(`${ROOT}/analyses`)
  if (operation === 'create') return api(`${ROOT}/analyses`, { method: 'POST', body: payload })
  const id = encodeURIComponent(payload.id || '')
  if (!id) throw new Error('저장된 분석을 먼저 선택해 주세요.')
  if (operation === 'load') return api(`${ROOT}/analyses/${id}`)
  if (operation === 'save') return api(`${ROOT}/analyses/${id}/candidates`, { method: 'PUT', body: { expectedRevision: payload.expectedRevision, candidates: payload.candidates } })
  if (operation === 'run') return api(`${ROOT}/analyses/${id}/runs`, { method: 'POST', body: { expectedRevision: payload.expectedRevision } })
  throw new Error('지원하지 않는 Agent 작업입니다.')
}
