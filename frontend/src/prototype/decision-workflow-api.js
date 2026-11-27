import { candidateDraft, candidatePayload, emptyCandidate } from './decision-agent-api.js'

const ROOT = '/api/v1/decision-agent'
const STATE_LABELS = { DRAFT: '요청 초안', REQUESTED: '조직 확인 중', REPLIED: '회신 검토 대기', CHANGES_REQUESTED: '보완 요청', ACCEPTED: '후보 편입 완료', REJECTED: '검토 반려', APPROVED: '추진 결정 기록', DECLINED: '추진 보류', IN_PROGRESS: '실행 중', COMPLETED: '후속 기준 연결', CANCELLED: '취소됨' }

/** 업무 상태를 사용자에게 읽기 쉬운 문구로 표시한다.
 * Args: state 서버 상태. Returns: 표시명. Raises: 없음. Side Effects: 없음.
 * @param {string} state
 * @returns {string}
 */
export function workStateLabel(state) { return STATE_LABELS[state] || '상태 확인 필요' }

/** 서버 시각을 한국 시간의 일시로 표시한다.
 * Args: value ISO 시각 또는 빈 값. Returns: YYYY-MM-DD HH:mm KST 또는 안내 문자열.
 * Raises: 없음. Side Effects: 없음.
 * @param {string|null|undefined} value
 * @returns {string}
 */
export function workDateText(value) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '날짜 확인 필요'
  const fields = {}
  for (const part of new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(date)) fields[part.type] = part.value
  return `${fields.year}-${fields.month}-${fields.day} ${fields.hour}:${fields.minute} KST`
}

/** 업무 상태의 태그 색상을 정한다.
 * Args: state 서버 상태. Returns: PrimeVue severity. Raises: 없음. Side Effects: 없음.
 * @param {string} state
 * @returns {string}
 */
export function workStateSeverity(state) {
  if (['ACCEPTED', 'APPROVED', 'COMPLETED'].includes(state)) return 'success'
  if (['CHANGES_REQUESTED', 'DECLINED'].includes(state)) return 'warn'
  if (['REJECTED', 'CANCELLED'].includes(state)) return 'danger'
  return ['REQUESTED', 'REPLIED', 'IN_PROGRESS'].includes(state) ? 'info' : 'secondary'
}

/** 날짜 입력을 한국 시간 해당 일의 마감 시각으로 변환한다.
 * Args: value YYYY-MM-DD 문자열. Returns: ISO 시각 문자열.
 * Raises: 날짜 형식 오류. Side Effects: 없음.
 * @param {string} value
 * @returns {string}
 */
export function dueAtKst(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value || '')) throw new Error('마감일을 선택해 주세요.')
  const date = new Date(`${value}T23:59:59+09:00`)
  if (Number.isNaN(date.getTime()) || date.toISOString().slice(0, 10) !== value) throw new Error('유효한 마감일을 선택해 주세요.')
  return date.toISOString()
}

/** 회신에 필요한 후보를 원본 또는 이전 회신에서 만들되 가설의 금액은 비워 둔다.
 * Args: item 조직 검토 요청. Returns: 편집 후보. Raises: 저장 금액 오류.
 * Side Effects: 없음; 요청 객체를 변경하지 않는다.
 * @param {object} item
 * @returns {object}
 */
export function replyCandidateDraft(item) {
  const original = item.reply?.candidate || item.sourceCandidate
  const draft = original ? candidateDraft(original) : emptyCandidate(item.candidateId)
  draft.id = item.candidateId; draft.organizationId = item.organizationId; draft.reviewed = false
  if (!original) { draft.title = item.title; draft.category = item.sourceHypothesis?.category || 'UNKNOWN' }
  return draft
}

/** 조직 회신을 정밀 금액·요청 조직 범위로 검증하여 변환한다.
 * Args: item 요청, form 회신 입력. Returns: 회신 DTO.
 * Raises: 필수 근거·후보 오류. Side Effects: 없음.
 * @param {object} item
 * @param {object} form
 * @returns {object}
 */
export function workReplyPayload(item, form) {
  if (!['FEASIBLE', 'NOT_FEASIBLE'].includes(form.feasibility)) throw new Error('개선 가능 여부를 선택해 주세요.')
  if (!form.note.trim() || !form.evidenceRef.trim()) throw new Error('회신 내용과 근거 위치를 모두 입력해 주세요.')
  let candidate = null
  if (form.feasibility === 'FEASIBLE') {
    candidate = candidatePayload({ ...form.candidate, id: item.candidateId, organizationId: item.organizationId, reviewed: false }, { baseMonth: item.baseMonth, organizations: [{ id: item.organizationId }] })
    candidate.evidenceNote = form.note.trim(); candidate.evidenceRef = form.evidenceRef.trim()
  }
  return { expectedRevision: item.revision, feasibility: form.feasibility, candidate, note: form.note.trim(), evidenceRef: form.evidenceRef.trim() }
}

/** 근거와 연결한 조직 요청 초안을 만든다.
 * Args: analysis 고정 분석, source 후보 또는 가설, form 요청 내용·기한.
 * Returns: 요청 생성 DTO. Raises: 필수 입력 오류. Side Effects: 없음.
 * @param {object} analysis
 * @param {object} source
 * @param {object} form
 * @returns {object}
 */
export function workCreatePayload(analysis, source, form) {
  if (!form.message.trim()) throw new Error('조직에 확인할 내용을 입력해 주세요.')
  if (!source?.id || !['candidate', 'hypothesis'].includes(source.kind)) throw new Error('확인할 후보 또는 근거 기반 가설을 선택해 주세요.')
  return { expectedAnalysisRevision: analysis.revision, candidateId: source.kind === 'candidate' ? source.id : null, hypothesisId: source.kind === 'hypothesis' ? source.id : null, message: form.message.trim(), dueAt: dueAtKst(form.dueDate) }
}

/** 승인 기록이 현재 계산의 검토된 후보에 연결되는지 확인한다.
 * Args: analysis 최신 분석, item 검토 요청. Returns: 추진 결정 입력 가능 여부.
 * Raises: 없음. Side Effects: 없음; 최종 권한·상태 검증은 서버가 담당한다.
 * @param {object} analysis
 * @param {object} item
 * @returns {boolean}
 */
export function canApproveWork(analysis, item) {
  if (item.state !== 'ACCEPTED' || !analysis?.result) return false
  for (const effect of analysis.result.effects || []) if (effect.candidateId === item.candidateId) return effect.included && effect.reviewed
  return false
}

/** Agent 조사와 조직 검토·실행 업무 API를 호출한다.
 * Args: api 인증 요청 함수, operation 작업명, payload 식별자와 body.
 * Returns: 응답 Promise. Raises: 미지원 작업 또는 HTTP 오류.
 * Side Effects: 조회 또는 사용자가 선택한 요청·회신·검토·결정·진척 저장.
 * @param {Function} api
 * @param {string} operation
 * @param {object} payload
 * @returns {Promise<object|null>}
 */
export function workflowRequest(api, operation, payload = {}) {
  const analysisId = encodeURIComponent(payload.analysisId || '')
  const itemId = encodeURIComponent(payload.itemId || '')
  if (operation === 'goal') return api(`${ROOT}/goal-proposals`, { method: 'POST', body: payload.body })
  if (operation === 'research' && analysisId) return api(`${ROOT}/analyses/${analysisId}/research`)
  if (operation === 'discover' && analysisId) return api(`${ROOT}/analyses/${analysisId}/discover`, { method: 'POST', body: payload.body })
  if (operation === 'items') return api(`${ROOT}/work-items${analysisId ? `?analysisId=${analysisId}` : ''}`)
  if (operation === 'create' && analysisId) return api(`${ROOT}/analyses/${analysisId}/work-items`, { method: 'POST', body: payload.body })
  if (operation === 'item' && itemId) return api(`${ROOT}/work-items/${itemId}`)
  if (operation === 'followup' && itemId) return api(`${ROOT}/work-items/${itemId}/followup-baselines`)
  const actions = { issue: 'issue', reply: 'replies', review: 'reviews', decision: 'decisions', progress: 'progress', cancel: 'cancel' }
  if (actions[operation] && itemId) return api(`${ROOT}/work-items/${itemId}/${actions[operation]}`, { method: 'POST', body: payload.body })
  throw new Error('지원하지 않는 Agent 업무 또는 식별자입니다.')
}
