/** 값이 있는 필터만 URL 쿼리로 변환한다.
 * Args: values 필터 객체. Returns: ? 포함 쿼리 문자열. Raises: 없음. Side Effects: 없음.
 * @param {Record<string, string | number | boolean | null | undefined>} values
 * @returns {string}
 */
export function query(values) {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(values)) {
    if (value !== '' && value !== null && value !== undefined) params.set(key, String(value))
  }
  const result = params.toString()
  return result ? `?${result}` : ''
}

/** 요청의 연도·월·유형으로 사용자용 제목을 만든다.
 * Args: item 요청/제출 객체. Returns: 제목. Raises: 없음. Side Effects: 없음.
 * @param {{type: string, year: number, month?: number} | null} item
 * @returns {string}
 */
export function requestTitle(item) {
  if (!item) return '제출 요청'
  return item.type === 'ANNUAL_PLAN' ? `${item.year}년 연간 계획 제출` : `${item.year}년 ${item.month}월 실적·추정 제출`
}

/** 서버의 시각을 한국 시간대로 표시한다.
 * Args: value ISO 시각 또는 null. Returns: 표시 문자열. Raises: 없음. Side Effects: 없음.
 * @param {string | null | undefined} value
 * @returns {string}
 */
export function dateTime(value) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(date)
}

/** 십진 금액의 정밀도를 유지하며 소수부 끝의 0을 생략하고 천 단위 구분자를 붙인다.
 * Args: value 십진 문자열 또는 null. Returns: 유효 소수 자릿수를 보존한 금액 문자열. Raises: 없음. Side Effects: 없음.
 * @param {string | null | undefined} value
 * @returns {string}
 */
export function amount(value) {
  if (value === null || value === undefined || value === '') return '—'
  const [integer, fraction] = String(value).split('.')
  const decimal = fraction?.replace(/0+$/, '')
  return integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + (decimal ? `.${decimal}` : '')
}

/** 제출과 파싱 상태를 최종 제출과 혼동하지 않게 표시한다.
 * Args: item 제출 객체. Returns: 표시 이름. Raises: 없음. Side Effects: 없음.
 * @param {{status: string, parsing?: {status: string}, parsingStatus?: string}} item
 * @returns {string}
 */
export function submissionStatus(item) {
  const status = item.parsing?.status || item.parsingStatus
  if (item.status !== 'DRAFT') return ({ PENDING: '검토 대기', APPROVED: '승인', REJECTED: '반려' })[item.status] || item.status
  if (item.readiness?.canSubmit) return '검증 완료 · 제출 가능'
  if (item.readiness?.validationState === 'STALE') return '재검증 필요 · 제출 전'
  if (item.readiness?.validationState === 'CURRENT') return '검증 확인 필요 · 제출 전'
  return ({ QUEUED: '파싱 대기', RUNNING: '파싱 중', SUCCEEDED: '파싱 완료 · 제출 전', NEEDS_REVIEW: '확인 필요 · 제출 전', FAILED: '파싱 실패' })[status] || '작성 중'
}

/** 사용자에게 표시할 오류와 추적값을 구성한다.
 * Args: error API 오류. Returns: 표시 문자열. Raises: 없음. Side Effects: 없음.
 * @param {Error & {requestId?: string}} error
 * @returns {string}
 */
export function errorText(error) {
  return `${error.message || '요청을 처리하지 못했습니다.'}${error.requestId ? ` (문의 번호: ${error.requestId})` : ''}`
}

/** 다운로드 Blob URL을 해제하는 콜백을 포함해 파일을 저장한다.
 * Args: blob 파일, filename 서버 또는 계약에서 정한 파일명.
 * Returns: 없음. Raises: 브라우저 다운로드 오류. Side Effects: 명시적 파일 다운로드, 임시 URL 생성·해제.
 * @param {Blob} blob
 * @param {string} filename
 * @returns {void}
 */
export function saveBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.append(link)
  link.click()
  link.remove()
  setTimeout(function releaseDownload() { URL.revokeObjectURL(url) }, 1000)
}
