/** Current backend DTO → existing display fields. Numeric strings remain untouched. */
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
    parsing: { ...raw.parsingJob, status: raw.parsingJob?.status || 'QUEUED', retryAllowed: raw.status === 'DRAFT' && raw.parsingJob?.status === 'FAILED' },
    readiness: { canSubmit: raw.validation?.canSubmit === true, validationState: raw.validation?.canSubmit === true ? 'CURRENT' : 'UNKNOWN', blockers: [] },
  }
}

/** Flatten the server's kind/month hierarchy without recalculating amounts. */
export function normalizeOrgReviewPage(page) {
  const items = []
  for (const group of page.groups || []) for (const period of group.months || []) for (const row of period.items || []) {
    items.push({ ...row, id: row.reviewItemId, reportItem: { name: row.name, kind: group.group }, month: period.month, scenario: period.scenario, currentAmount: row.current, baselineAmount: row.baseline })
  }
  return { ...page, items }
}

/** Diagnostics are not paginated in the current API. Keep the provided location and guide. */
export function normalizeDiagnostics(page) {
  return { ...page, items: (page.items || []).map(row => ({ ...row, code: row.rule, message: row.rule, action: row.guide, guide: row.guide, severity: 'ERROR' })), nextCursor: null }
}

/** Basic request eligibility only; the upload endpoint checks the full server policy. */
export function normalizeUploadRequest(detail, actor) {
  const target = detail.targets?.find(row => String(row.organization.id) === String(actor.organization?.id))
  return { ...detail.request, targets: detail.targets, myWorkflow: { canUpload: detail.request?.status === 'OPEN' && target?.selected === true && target.latestSubmission?.status !== 'PENDING', latestStatus: target?.latestSubmission?.status, latestSubmittedSubmissionId: target?.latestSubmission?.id, blockingCode: detail.request?.status !== 'OPEN' ? 'REQUEST_CLOSED' : null } }
}
