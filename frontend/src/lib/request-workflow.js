/** 파싱 상태를 현재 초안에서 처리할 일로 설명한다.
 * Args: status 서버의 파싱 상태. Returns: 표시 문자열. Raises: 없음. Side Effects: 없음.
 */
export function draftStatus(status) {
  const names = { QUEUED: '파싱 대기', RUNNING: '파싱 중', NEEDS_REVIEW: '원본 확인 필요', FAILED: '처리 실패', SUCCEEDED: '검증·소명 준비' }
  return Object.hasOwn(names, status) ? names[status] : '작성 중'
}

/** 조직별 요청의 서버 상태로 다음 작업과 직접 이동 대상을 선택한다.
 * Args: request myWorkflow를 가진 요청 DTO. Returns: label/message/path 작업 객체.
 * Raises: 없음. Side Effects: 없음; 권한·접수 가능 여부를 클라이언트에서 변경하지 않는다.
 */
export function requestAction(request) {
  const work = request.myWorkflow
  if (!work) return { label: '제출 이력 확인', message: '현재 제출 상태를 이력에서 확인해 주세요.', path: '/history' }
  const reasons = {
    ANNUAL_PLAN_LOCKED: '연간 계획이 확정되어 새 파일을 접수할 수 없습니다.',
    ANNUAL_PLAN_NOT_CONFIRMED: '연간 계획 확정 후 월간 파일을 제출할 수 있습니다.',
    PLAN_SOURCE_MISSING: '이 조직의 확정 계획 원천이 없어 경영기획팀 확인이 필요합니다.',
    PARSER_BUSY: '현재 조직의 파싱 대기·진행 한도(10개)에 도달했습니다. 처리가 끝난 뒤 접수 상태를 다시 확인해 주세요.',
    REQUEST_CLOSED: '이 요청은 마감되어 새 파일을 접수하지 않습니다.',
  }
  const blockedMessage = Object.hasOwn(reasons, work.blockingCode) ? reasons[work.blockingCode] : '이 요청은 현재 새 파일을 접수하지 않습니다.'
  const submitted = work.latestSubmittedSubmissionId
  if (work.latestStatus === 'PENDING' && submitted) return {
    label: work.unansweredCount > 0 ? '소명 보완하기' : '검토 상태 확인',
    message: work.unansweredCount > 0 ? `보완할 소명 ${work.unansweredCount}건이 있습니다.` : '최종 제출이 완료되어 경영기획팀에서 검토 중입니다.',
    path: `/submissions/${submitted}`,
  }
  if (work.blockingSubmissionId) return {
    label: work.blockingCode === 'RESUBMISSION_SCOPE_CONFLICT' ? '반려된 요청 확인' : '검토 중 제출본 확인',
    message: work.blockingCode === 'RESUBMISSION_SCOPE_CONFLICT' ? '재제출 요청을 받은 다른 기간부터 처리해 주세요.' : '검토 중인 제출본을 먼저 확인해 주세요.',
    path: `/submissions/${work.blockingSubmissionId}`,
  }
  if (work.latestDraftSubmissionId) return {
    label: ['NEEDS_REVIEW', 'FAILED'].includes(work.latestDraftParsingStatus) ? '초안 오류 확인' : '작성 중 초안 이어하기',
    message: `${draftStatus(work.latestDraftParsingStatus)}${work.unansweredCount > 0 ? ` · 필수 소명 ${work.unansweredCount}건` : ''}${work.canUpload === false ? ` · ${blockedMessage}` : ''}`,
    path: `/submissions/${work.latestDraftSubmissionId}`,
  }
  if (work.canUpload === true) return {
    label: work.latestStatus === 'REJECTED' ? '전체 파일 재제출' : work.latestStatus === 'APPROVED' ? '수정본 업로드' : '제출 시작',
    message: work.latestStatus === 'REJECTED' ? '반려 사유를 확인하고 수정한 전체 파일을 올려 주세요.' : work.latestStatus === 'APPROVED' ? '이전 제출본이 승인되었습니다. 수정이 필요하면 전체 파일을 새로 올릴 수 있습니다.' : '전체 항목 파일을 업로드하고 검증 후 최종 제출해 주세요.',
    path: `/requests/${request.id}/upload`,
  }
  return {
    label: submitted ? '제출본 확인' : work.blockingCode === 'REQUEST_CLOSED' ? '접수 마감' : '접수 조건 확인',
    message: blockedMessage,
    path: submitted ? `/submissions/${submitted}` : null,
  }
}
