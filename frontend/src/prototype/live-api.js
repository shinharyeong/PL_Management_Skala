// Money stays in decimal strings. This transport never supplies example data on failure.
export function createApiClient(credentials) {
  const pendingKeys = new Map()
  return async function request(path, { method = 'GET', body, signal, blob = false } = {}) {
    if (!path.startsWith('/api/v1/')) throw new Error('지원하지 않는 API 경로입니다.')
    const token = credentials.token()
    const headers = new Headers()
    if (token) headers.set('Authorization', `Bearer ${token}`)
    const multipart = body instanceof FormData
    const encoded = body == null ? undefined : multipart ? body : JSON.stringify(body)
    if (encoded && !multipart) headers.set('Content-Type', 'application/json')
    const command = method !== 'GET' && !['/api/v1/auth/codes', '/api/v1/auth/sessions'].includes(path)
    const fingerprint = `${method}:${path}:${multipart ? [...body].map(([k,v]) => `${k}:${typeof v === 'string' ? v : `${v.name}:${v.size}:${v.lastModified}`}`).join('|') : encoded || ''}`
    if (command) {
      if (!pendingKeys.has(fingerprint)) pendingKeys.set(fingerprint, crypto.randomUUID())
      headers.set('Idempotency-Key', pendingKeys.get(fingerprint))
    }
    let response
    try { response = await fetch(path, { method, body: encoded, headers, signal, credentials: 'omit', cache: 'no-store' }) }
    catch (cause) { if (cause.name === 'AbortError') throw cause; throw new Error('서버에 연결하지 못했습니다. 다시 시도해 주세요.') }
    if (!response.ok) {
      const problem = await response.json().catch(() => ({}))
      if (response.status === 401 && token && token === credentials.token()) credentials.clear()
      const error = new Error(problem.detail || `요청 실패 (${response.status})`)
      error.code = problem.code; error.requestId = problem.requestId
      throw error
    }
    if (token && token !== credentials.token()) throw new DOMException('인증 정보가 변경되었습니다.', 'AbortError')
    if (command) pendingKeys.delete(fingerprint)
    return response.status === 204 ? null : blob ? response.blob() : response.json()
  }
}

export const comparisons = { '실적 vs 계획': 'ACTUAL_VS_PLAN', '추정 vs 추정': 'ESTIMATE_VS_PREVIOUS', '추정 vs 계획': 'ESTIMATE_VS_PLAN' }
export const stages = { NEEDS_REVIEW:'검토 필요', SELECTED:'검토 완료', IN_PROGRESS:'소명 진행중', RE_REVIEW:'재검토 필요', ACCEPTED:'소명 승인 완료', EXCLUDED:'제외됨' }
export const status = value => ({ SUBMITTED:'제출 완료', OVERDUE:'기한 경과', WAITING:'회신 대기', PENDING:'검토 대기', APPROVED:'승인 완료', REJECTED:'반려', DRAFT:'초안', RESUBMIT_REQUESTED:'재제출 요청' })[value] || value || '—'
export function flattenGapTree(tree, type, organizationName) {
  const result = []
  const normalize = name => String(name || '').replace(/\s/g, '')
  function classification(parents) {
    const org = normalize(organizationName), root = normalize(parents[0])
    // A selected organization's wrapper is context, not an account classification.
    return org && (root === org || root === org+'합계') ? parents.slice(1) : parents
  }
  function visit(nodes, parents = []) {
    for (const node of nodes || []) {
      const path = classification(parents)
      if (node.reviewItemId) result.push({ id: node.reviewItemId, key:node.reviewItemId, stateToken:node.stateToken,
        name:[path.at(-1),node.name].filter(Boolean).join(' · '), major:path[0] || '항목', group:path[1] || path[0] || '항목', category:path.slice(2).join(' / ') || path.at(-1) || '항목',
        cell:node.name, plan:node.plan, actual:node.actual, estimate:node.actual, previousEstimate:node.plan,
        baselineAmount:node.plan, currentAmount:node.actual, gapAmount:node.diff, gapRate:node.rate, status:stages[node.tab] || node.tab,
        conditions:{ type }, reportItemId:node.reportItemId })
      visit(node.children, node.reviewItemId ? parents : [...parents,node.name])
    }
  }
  visit(tree); return result
}
export function saveFile(blob, name) {
  const url = URL.createObjectURL(blob), a = document.createElement('a')
  a.href = url; a.download = name; a.click(); URL.revokeObjectURL(url)
}
