/** 현재 제출 DTO를 표시 필드로 변환하고 HIST-02 이력을 보존한다.
 * Args: raw 서버 제출 DTO. Returns: 표시용 제출 객체. Raises: 없음.
 * Side Effects: 없음. 금액·상태 토큰을 변환하거나 제출 가능 여부를 추측하지 않는다.
 */
export function normalizeSubmission(raw) {
  const match = /^(\d{4})(?:\.(\d{2}))?$/.exec(raw.period || '')
  return {
    ...raw,
    type: raw.kind,
    year: match ? Number(match[1]) : null,
    month: match?.[2] ? Number(match[2]) : null,
    organizationId: raw.organization?.id,
    originalFilename: raw.file?.name,
    versionLabel: raw.label,
    rejectReason: raw.reviewComment,
    explanations: raw.explanations || [],
    inheritedExplanations: raw.inheritedExplanations || [],
    rejectedItems: raw.rejectedItems || [],
    history: raw.history || [],
    parsing: { ...raw.parsingJob, status: raw.parsingJob?.status || 'QUEUED', retryAllowed: raw.status === 'DRAFT' && raw.parsingJob?.status === 'FAILED' },
    readiness: { canSubmit: raw.validation?.canSubmit === true, validationState: raw.validation?.canSubmit === true ? 'CURRENT' : 'UNKNOWN', blockers: [] },
  }
}

/** 서버의 항목·월 계층을 금액 재계산 없이 목록으로 펼친다.
 * Args: page ORG 검토 목록 DTO. Returns: 계층과 평탄화 목록. Raises: 없음.
 * Side Effects: 없음. 서버 display 값과 기준 버전도 원문대로 보존한다.
 */
export function normalizeOrgReviewPage(page) {
  const items = []
  for (const group of page.groups || []) for (const period of group.months || []) for (const row of period.items || []) {
    items.push({ ...row, id: row.reviewItemId, reportItem: { name: row.name, kind: group.group }, month: period.month, scenario: period.scenario, currentAmount: row.current, baselineAmount: row.baseline })
  }
  return { ...page, items }
}

/** 페이지 구분 없는 진단 DTO의 위치와 수정 안내를 보존한다.
 * Args: page 진단 DTO. Returns: 표시용 진단 목록. Raises: 없음. Side Effects: 없음.
 */
export function normalizeDiagnostics(page) {
  return { ...page, items: (page.items || []).map(row => ({ ...row, code: row.rule, message: row.rule, action: row.guide, guide: row.guide, severity: 'ERROR' })), nextCursor: null }
}

/** 요청 상세에서 자기 조직의 기본 업로드 가능 상태를 표시한다.
 * Args: detail 요청 상세 DTO, actor 인증 사용자.
 * Returns: 표시용 요청과 접수 상태. Raises: 없음. Side Effects: 없음.
 * 최종 권한·동시 제출·파일 검증은 서버가 다시 판정한다.
 */
export function normalizeUploadRequest(detail, actor) {
  const target = detail.targets?.find(row => String(row.organization.id) === String(actor.organization?.id))
  return { ...detail.request, targets: detail.targets, myWorkflow: { canUpload: detail.request?.status === 'OPEN' && target?.selected === true && target.latestSubmission?.status !== 'PENDING', latestStatus: target?.latestSubmission?.status, latestSubmittedSubmissionId: target?.latestSubmission?.id, blockingCode: detail.request?.status !== 'OPEN' ? 'REQUEST_CLOSED' : null } }
}
