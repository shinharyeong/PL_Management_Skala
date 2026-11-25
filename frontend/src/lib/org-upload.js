/** PrimeVue 파일 선택 결과를 단일 파일 계약으로 검증한다.
 * Args: event FileUpload select 이벤트, extension 허용 확장자 정규식, maxBytes 바이트 상한, message 오류 안내.
 * Returns: 선택 파일 또는 null과 오류 문자열. Raises: 없음.
 * Side Effects: 없음. 파일을 읽거나 업로드하지 않는다.
 */
function singleFileSelection(event, extension, maxBytes, message) {
  const files = Array.from(event?.files || [])
  const originalFiles = event?.originalEvent?.dataTransfer?.files || event?.originalEvent?.target?.files
  if (originalFiles?.length > 1) return { file: null, error: message }
  if (!files.length) return { file: null, error: '' }
  if (files.length !== 1 || !extension.test(files[0].name) || !files[0].size || files[0].size > maxBytes) {
    return { file: null, error: message }
  }
  return { file: files[0], error: '' }
}

/** 제출용 Excel 선택을 기존 20MiB·XLSX/XLSB 계약으로 검증한다.
 * Args: event PrimeVue FileUpload select 이벤트. Returns: file·error 객체.
 * Raises: 없음. Side Effects: 없음. 실제 업로드는 검증 시작 동작에서 수행한다.
 */
export function submissionFileSelection(event) {
  return singleFileSelection(event, /\.(xlsx|xlsb)$/i, 20 * 1024 * 1024,
    '.xlsx 또는 .xlsb 형식의 비어 있지 않은 20MB 이하 파일 1개를 선택해 주세요.')
}

/** 소명 근거 선택을 제출 Excel과 별개인 16MiB 첨부 계약으로 검증한다.
 * Args: event PrimeVue FileUpload select 이벤트. Returns: file·error 객체.
 * Raises: 없음. Side Effects: 없음. 선택만으로 서버 요청을 보내지 않는다.
 */
export function evidenceFileSelection(event) {
  return singleFileSelection(event, /\.(pdf|xlsx|png|jpe?g)$/i, 16 * 1024 * 1024,
    'PDF·XLSX·PNG·JPEG 형식의 비어 있지 않은 16MB 이하 파일 1개를 선택해 주세요.')
}
