/** 보고자료 편집용 블록을 생성한다.
 * Args: type 자료 종류, index 표시 순서. Returns: 새 블록. Raises: 없음.
 * Side Effects: 메모리 식별자 생성만 수행한다.
 * @param {string} type
 * @param {number} index
 * @returns {Record<string, unknown>}
 */
export function newReportBlock(type = 'TABLE', index = 0) {
  return { id: crypto.randomUUID(), title: `${({ TABLE: '손익 표', CHART: '추이 차트', TEXT: '설명', LIST: '항목 목록', GAP: 'Gap 설명' })[type] || '자료'} ${index + 1}`,
    type, metricCode: 'OP', periodRule: 'CURRENT_MONTH', offsetMonths: 0, fixedYear: new Date().getFullYear(), fixedMonth: 1,
    windowMonths: 12, scenario: 'ACTUAL', organizationIds: [], comparisonBasis: 'PLAN', chartKind: 'BAR',
    sql: '', text: '', style: { fontFamily: 'sans-serif', fontSize: 14, width: 100 }, reasonMappings: [] }
}

/** 편집값을 직렬화 가능한 독립 객체로 복사한다.
 * Args: value JSON 호환 입력. Returns: 독립 복사본. Raises: 순환 참조 오류.
 * Side Effects: 없음.
 * @template T
 * @param {T} value
 * @returns {T}
 */
export function copyReport(value) { return JSON.parse(JSON.stringify(value)) }

/** 편집 순서만 바꾸고 없는 식별자는 원래 배열로 유지한다.
 * Args: blocks 블록 목록, sourceId 이동 대상, targetIndex 도착 위치. Returns: 새 배열.
 * Raises: 없음. Side Effects: 원본 배열과 블록을 수정하지 않는다.
 * @param {Array<Record<string, unknown>>} blocks
 * @param {string} sourceId
 * @param {number} targetIndex
 * @returns {Array<Record<string, unknown>>}
 */
export function moveReportBlock(blocks, sourceId, targetIndex) {
  const index = blocks.findIndex(function matches(block) { return block.id === sourceId })
  if (index < 0 || !Number.isInteger(targetIndex) || targetIndex < 0 || targetIndex >= blocks.length) return [...blocks]
  const next = [...blocks]
  const [block] = next.splice(index, 1)
  next.splice(targetIndex, 0, block)
  return next
}

/** 미리보기 대상과 현재 편집 내용의 동일성을 확인할 문자열을 만든다.
 * Args: draft 제목·기준월·블록. Returns: 결정적 JSON 문자열. Raises: 직렬화 오류.
 * Side Effects: 없음; 오래된 미리보기 저장을 막는 화면 보조이며 서버 검증을 대신하지 않는다.
 * @param {{name: string, baseYear: number, baseMonth: number, blocks: Array<unknown>}} draft
 * @returns {string}
 */
export function reportSignature(draft) {
  return JSON.stringify({ name: draft.name, baseYear: Number(draft.baseYear), baseMonth: Number(draft.baseMonth), blocks: draft.blocks })
}

/** 서버의 결과 상태를 한국어로 표시한다.
 * Args: status 서버 상태. Returns: 상태명. Raises: 없음. Side Effects: 없음.
 * @param {string | null} status
 * @returns {string}
 */
export function reportStatus(status) {
  return ({ FINAL: '확정', PROVISIONAL: '잠정', NO_DATA: '데이터 없음', AVAILABLE: '조회 완료', UNRESOLVED: '확인 필요' })[status] || status || '조회 전'
}

/** 결과 표의 열을 표시할 때 서버의 명시적 키를 보존한다.
 * Args: columns 서버 열 목록. Returns: 키·표시명 목록. Raises: 없음. Side Effects: 없음.
 * @param {Array<string | {key: string, label?: string}> | undefined} columns
 * @returns {Array<{key: string, label: string}>}
 */
export function reportColumns(columns) {
  const result = []
  for (const column of columns || []) result.push(typeof column === 'string' ? { key: column, label: column } : { key: column.key, label: column.label || column.key })
  return result
}

/** 양수 금액의 비중을 원금액 정밀도를 보존해 차트 좌표로 바꾼다.
 * Args: values 십진 금액 목록. Returns: 백분율 구간 또는 음수·누락·총합0이면 null.
 * Raises: 없음. Side Effects: 없음; Number는 최종 차트 좌표에만 사용한다.
 * @param {Array<string>} values
 * @returns {Array<{percent: number, offset: number}> | null}
 */
export function reportPieGeometry(values) {
  const parts = []; let scale = 0
  for (const value of values) {
    const match = /^(\d+)(?:\.(\d+))?$/.exec(String(value))
    if (!match) return null
    const fraction = match[2] || ''; scale = Math.max(scale, fraction.length)
    parts.push({ digits: match[1] + fraction, scale: fraction.length })
  }
  const numbers = []; let total = 0n
  for (const part of parts) { const amount = BigInt(part.digits) * 10n ** BigInt(scale - part.scale); numbers.push(amount); total += amount }
  if (total === 0n) return null
  const result = []; let cumulative = 0n
  for (const amount of numbers) {
    const start = Number(cumulative * 1000000n / total) / 10000
    cumulative += amount
    const end = Number(cumulative * 1000000n / total) / 10000
    result.push({ percent: end - start, offset: -start })
  }
  return result
}
