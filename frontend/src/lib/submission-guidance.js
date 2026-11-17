/** 서버가 확인한 시트·셀·월을 사람이 찾을 수 있는 위치로 표시한다.
 * Args: issue source와 선택 month를 가진 진단 또는 제출 차단 항목.
 * Returns: 위치 문자열. Raises: 없음. Side Effects: 없음; 열 문자로 월을 추측하지 않는다.
 */
export function issueLocation(issue) {
  const source = issue?.source || {}
  const address = typeof source.cell === 'string' ? source.cell : ''
  const match = /^([A-Z]+)([1-9]\d*)$/.exec(address)
  const month = Number.isInteger(issue?.month) && issue.month >= 1 && issue.month <= 12 ? issue.month : null
  const parts = []
  if (source.sheet) parts.push(source.sheet)
  if (match) parts.push(`${match[2]}행`)
  if (month) parts.push(`${month}월`)
  if (address) parts.push(`셀 ${address}`)
  return parts.length ? parts.join(' · ') : '파일·제출 상태'
}

/** 검증 안내를 원본 수정·업무 절차·처리 실패에 맞는 다음 행동으로 구분한다.
 * Args: issue 서버 진단/차단 항목, parsingStatus 현재 파싱 상태.
 * Returns: 확인된 코드에 따른 안내 문자열. Raises: 없음.
 * Side Effects: 없음; 값을 제안하거나 재업로드·재검증 명령을 실행하지 않는다.
 */
export function issueNextAction(issue, parsingStatus = '') {
  const code = typeof issue?.code === 'string' ? issue.code : ''
  if (parsingStatus === 'FAILED') return '파일 처리에 실패했습니다. 같은 파일 다시 파싱이 가능하면 재시도하고, 계속 실패하면 진단 정보와 함께 운영 담당자에게 문의해 주세요.'
  const actions = {
    VALIDATION_REQUIRED: '위의 업무 검증을 실행한 뒤 새로 표시되는 결과를 확인해 주세요.',
    VALIDATION_STALE: '위의 업무 검증 다시 실행을 누르고 소명·제출 가능 상태를 다시 확인해 주세요.',
    PARSING_NOT_READY: '파일 처리가 끝날 때까지 기다려 주세요. 결과는 자동으로 갱신됩니다.',
    PARSING_REQUIRES_ATTENTION: '아래 파싱·업무 검증 안내에서 위치와 사유를 확인해 주세요. 원본을 수정했다면 전체 파일을 다시 업로드해 주세요.',
    REQUIRED_EXPLANATIONS_MISSING: '소명 목록에서 금액 변경·Gap의 필수 사유를 작성한 뒤 최종 제출해 주세요.',
    EXPLANATIONS_NOT_ACCEPTED: '소명 검토 상태를 확인해 주세요. 보완 요청이 있으면 해당 소명에 답변해 주세요.',
    REQUEST_CLOSED: '경영기획팀에 해당 요청의 접수 상태를 확인해 주세요.',
    ANNUAL_PLAN_LOCKED: '경영기획팀에 연간 계획의 확정 상태와 후속 제출 절차를 확인해 주세요.',
    ANNUAL_PLAN_NOT_CONFIRMED: '경영기획팀에 비교 기준이 되는 연간 계획 확정을 요청해 주세요.',
    PENDING_ALREADY_EXISTS: '검토 중인 제출본의 결과를 확인한 뒤 제출을 진행해 주세요.',
    RESUBMISSION_SCOPE_CONFLICT: '반려·재제출 요청을 받은 기간을 확인하고 해당 요청에서 먼저 제출해 주세요.',
    REQUEST_TARGET_EXCLUDED: '경영기획팀에 현재 조직의 제출 대상 여부를 확인해 주세요.',
    SOURCE_HASH_MISMATCH: '업로드 원본과 처리 결과의 연결을 확인하지 못했습니다. 진단 정보와 함께 운영 담당자에게 문의해 주세요.',
    SUBMISSION_ADAPTER_REQUIRED: '현재 처리 기준으로 확인할 수 있도록 전체 파일을 다시 업로드해 주세요.',
    CONTROL_CACHE_PRECISION: '저장된 계산값과 상세 합계의 근거를 확인해 주세요. 최종 제출 가능 여부는 위의 제출 상태를 따릅니다.',
    PLAN_AMOUNT_UNRESOLVED: '표시된 월·셀의 금액과 수식을 원본 엑셀에서 확인하고, 필요한 수정을 마친 전체 파일을 다시 업로드해 주세요.',
    DUPLICATE_DETAIL_ID: '원본에서 같은 상세 ID의 중복 행·수정본 관계를 확인한 뒤 전체 파일을 다시 업로드해 주세요.',
    PLAN_MONTH_HEADERS_INCOMPLETE: '원본의 연도·월·계획/실적/추정 헤더가 제출 요청과 맞는지 확인한 뒤 전체 파일을 다시 업로드해 주세요.',
  }
  if (Object.hasOwn(actions, code)) return actions[code]
  if (code.includes('ORGANIZATION')) return '표시된 행의 조직명·조직 코드와 원본 조직코드 표를 확인한 뒤 수정한 전체 파일을 다시 업로드해 주세요.'
  if (code.includes('FORMULA_CACHE') || code.includes('CONTROL_TOTAL')) return '원본 엑셀의 수식·참조·저장된 계산 결과를 확인한 뒤 파일을 저장하고 전체 파일을 다시 업로드해 주세요.'
  if (issue?.source?.sheet) return '표시된 위치와 사유를 원본 엑셀에서 확인해 주세요. 수정이 필요하면 변경하지 않은 항목까지 포함한 전체 파일을 다시 업로드해 주세요.'
  return '안내 사유를 확인해 주세요. 원본 수정이 필요한 경우 전체 파일을 다시 업로드하고, 제출 기준·상태에 관한 내용은 경영기획팀에 확인해 주세요.'
}
