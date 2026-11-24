// Data/dummy의 manifest에서 확인한 사업부 순서이며 원본 파일 내용을 추론하는 용도로 쓰지 않는다.
const DUMMY_ORGANIZATION_CODES = Object.freeze([
  'F10', 'F12', 'F20', 'F40', 'F39', 'F50', 'F16', 'F13', 'F25',
  'F43', 'F42', 'F44', 'F73', 'F38', 'F66', 'F36', 'F37',
])

/** 제출 파일명의 확장자를 선택한 실제 통합 문서 형식에 맞춘다.
 * Args: originalName 원본 이름, expectedName 요청의 XLSX/XLSB 계약 이름.
 * Returns: 원본 확장자를 유지한 제출 이름 또는 검증 전의 기본 이름.
 * Raises: 없음. Side Effects: 없음. 파일 바이트를 변환하지 않는다.
 * @param {string|undefined} originalName
 * @param {string} expectedName
 * @returns {string}
 */
export function preserveWorkbookExtension(originalName, expectedName) {
  const extension = /\.(xlsx|xlsb)$/i.exec(originalName || '')?.[1].toLowerCase()
  return extension ? expectedName.replace(/\.(xlsx|xlsb)$/i, `.${extension}`) : expectedName
}

/** 원본명을 바꾸지 않고 제출 이름 보정을 허용할 수 있는 파일인지 확인한다.
 * Args: originalName 선택 파일명, expectedName 요청의 계약 파일명, organizationCode 현재 조직 코드.
 * Returns: allowed 허용 여부, requiresConsent 이름 보정 동의 필요 여부, reason 거절 사유.
 * Raises: 없음. Side Effects: 없음; 비교 문자열만 NFC로 정규화하며 원본 이름·바이트는 변경하지 않는다.
 * 월마감 원본은 파일명 연도와 요청 연도, 월간 요청이면 마감 월도 일치해야 한다.
 * 파일 바이트·로컬 파일·업무 내용의 조직 일치는 검사하지 않는다.
 * @param {string} originalName
 * @param {string} expectedName
 * @param {string} organizationCode
 * @returns {{allowed: boolean, requiresConsent: boolean, reason: string}}
 */
export function inspectUploadFilename(originalName, expectedName, organizationCode) {
  if (!originalName || !organizationCode || !expectedName?.startsWith(`${organizationCode}_`)) {
    return { allowed: false, requiresConsent: false, reason: '요청과 제출 조직을 먼저 확인해 주세요.' }
  }
  const comparisonName = originalName.normalize('NFC').replace(/\.(xlsx|xlsb)$/i, extension => extension.toLowerCase())
  const comparisonExpectedName = preserveWorkbookExtension(originalName, expectedName).normalize('NFC')
  if (comparisonName === comparisonExpectedName) return { allowed: true, requiresConsent: false, reason: '' }
  const match = /^(0[1-9]|1[0-7])_(F\d{2})_사업부(\d{1,2})_(?:재무제표|(\d{4})_(0[1-9]|1[0-2])월마감)\.(?:xlsx|xlsb)$/.exec(comparisonName)
  if (match) {
    const index = Number(match[1]) - 1
    if (match[3] !== String(index + 1) || DUMMY_ORGANIZATION_CODES[index] !== match[2]) {
      return { allowed: false, requiresConsent: false, reason: '파일명의 사업부 번호와 조직 코드가 일치하지 않습니다.' }
    }
    if (match[2] !== organizationCode) {
      return { allowed: false, requiresConsent: false, reason: `이 파일은 ${match[2]} 조직의 파일입니다. 요청함에서 해당 조직으로 전환해 주세요.` }
    }
    if (match[4]) {
      const period = new RegExp(`^${organizationCode}_(\\d{4})-(PLAN|0[1-9]|1[0-2])\\.(?:xlsx|xlsb)$`).exec(comparisonExpectedName)
      if (!period || period[1] !== match[4] || (period[2] !== 'PLAN' && period[2] !== match[5])) {
        return { allowed: false, requiresConsent: false, reason: '원본 파일명의 연도·마감 월이 현재 제출 요청과 일치하지 않습니다.' }
      }
    }
    return { allowed: true, requiresConsent: true, reason: '' }
  }
  const otherCode = /^([A-Z]\d{2,4})_/.exec(comparisonName)?.[1]
  if (otherCode && otherCode !== organizationCode) {
    return { allowed: false, requiresConsent: false, reason: `이 파일의 조직 코드는 ${otherCode}입니다. 현재 선택한 ${organizationCode} 조직으로 전송할 수 없습니다.` }
  }
  return { allowed: false, requiresConsent: false, reason: `파일명은 ${expectedName} 또는 현재 조직의 사업부 재무제표 원본이어야 합니다.` }
}
