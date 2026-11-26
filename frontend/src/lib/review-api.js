/** 소명 항목 식별자로 원천 근거 조회 경로를 만든다.
 * Args: reviewItemId 서버가 발급한 소명 항목 ID.
 * Returns: GAP-09 동일 출처 경로. Raises: Error 잘못된 식별자.
 * Side Effects: 없음. 제출본 ID나 셀 주소로 소명 항목을 추측하지 않는다.
 * @param {string|number} reviewItemId
 * @returns {string}
 */
export function reviewEvidencePath(reviewItemId) {
  if (!/^[1-9]\d*$/.test(String(reviewItemId))) throw new Error('원천 근거를 조회할 소명 항목을 선택해 주세요.')
  return `/api/v1/review-items/${reviewItemId}/evidence`
}

/** 선택한 실제 소명 항목으로 GAP-07 명령 본문을 만든다.
 * Args: action 명령, rows ID·현재 상태 토큰이 있는 항목, extra 사유·기한·메시지.
 * Returns: 백엔드 명세의 action/items/note/dueAt/message 본문.
 * Raises: Error 지원하지 않는 명령, 빈 항목, 상태 토큰 누락, 필수 사유·요청 정보 누락.
 * Side Effects: 없음. 원본 배열을 변경하거나 임의 항목·토큰을 만들지 않는다.
 * @param {string} action
 * @param {Array<{id:string,stateToken:string}>} rows
 * @param {{note?:string,dueAt?:string,message?:string}} extra
 * @returns {object}
 */
export function reviewActionBody(action, rows, extra = {}) {
  if (!['SELECT', 'EXCLUDE', 'REQUEST_EXPLANATION'].includes(action)) throw new Error('지원하지 않는 검토 처리입니다.')
  if (!rows?.length || rows.length > 500) throw new Error('검토할 항목을 1~500개 선택해 주세요.')
  const items = []
  const seen = new Set()
  for (const row of rows) {
    if (!/^[1-9]\d*$/.test(String(row.id)) || !row.stateToken || seen.has(String(row.id))) throw new Error('선택 항목의 최신 상태를 다시 조회해 주세요.')
    seen.add(String(row.id))
    items.push({ reviewItemId: row.id, expectedStateToken: row.stateToken })
  }
  const body = { action, items }
  if (action === 'EXCLUDE') {
    // 제외 사유는 선택이다(와이어프레임 4-2의 하단 바에는 사유 입력이 없다). 서버는 note를 비워도 받는다.
    const note = extra.note?.trim()
    if (note && note.length > 4000) throw new Error('제외 사유는 4000자 이내로 입력해 주세요.')
    if (note) body.note = note
  }
  if (action === 'REQUEST_EXPLANATION') {
    const message = extra.message?.trim()
    if (!message || message.length > 5000 || !Number.isFinite(Date.parse(extra.dueAt))) throw new Error('소명 요청 메시지와 회신 기한을 확인해 주세요.')
    body.message = message
    body.dueAt = extra.dueAt
  }
  return body
}

/** 서버가 선택한 비교 기준을 한국어로 표시한다.
 * Args: baseline Baseline DTO 또는 null.
 * Returns: 기준 종류·버전 이름. Raises: 없음. Side Effects: 없음.
 * @param {{kind?:string,label?:string}|null} baseline
 * @returns {string}
 */
export function baselineLabel(baseline) {
  if (!baseline || baseline.kind === 'NONE') return '비교 기준 없음'
  const kind = { ANNUAL_PLAN: '확정 연간 계획', PREV_MONTH_APPROVED: '승인 비교본', REJECTED: '직전 반려본' }[baseline.kind] || '비교 기준'
  return [kind, baseline.label].filter(Boolean).join(' · ')
}
