import { ref } from 'vue'
import { defineStore } from 'pinia'

/** AUTH-02·04의 MeResponse를 기존 역할 라우터에서 사용하는 사용자 형태로 변환한다. */
function actorFromMe(me) {
  if (!me?.user?.id || !['PLANNER', 'ORG_MANAGER'].includes(me.role)) throw new Error('로그인 사용자 정보를 확인하지 못했습니다.')
  return { ...me.user, role: me.role, organization: me.organization || null, homePath: me.homePath, menus: me.menus || [] }
}

/** 메모리 인증과 동일 출처 통신을 구성한다.
 * Args: 없음. Returns: Pinia 세션 상태와 동작. Raises: 없음.
 * Side Effects: 로그인·요청 시 HTTP 통신; 토큰을 저장소·로그에 기록하지 않음.
 */
export const useSession = defineStore('session', function sessionStore() {
  const actor = ref(null)
  const token = ref('')
  const organizations = ref([])
  const unreadCount = ref(0)
  const profileConfirmed = ref(false)
  const reviewWorkspaces = ref([])
  let lifetime = new AbortController()
  let logoutKey = ''

  /** 진행 중인 요청을 취소하고 역할을 초기화한다.
   * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 메모리 인증 제거, HTTP 취소.
   */
  function clear() {
    lifetime.abort()
    lifetime = new AbortController()
    actor.value = null
    token.value = ''
    organizations.value = []
    unreadCount.value = 0
    profileConfirmed.value = false
    reviewWorkspaces.value = []
    logoutKey = ''
  }

  /** 인증 헤더와 취소 신호를 적용하고 안전한 API 오류를 변환한다.
   * Args: path 동일 출처 API 경로, options fetch 옵션과 blob 플래그.
   * Returns: JSON 객체 또는 Blob. Raises: 안전한 API Error, AbortError.
   * Side Effects: HTTP 통신; 현재 세션의 401에서 인증 초기화.
   */
  async function request(path, options = {}) {
    const { blob = false, signal, ...init } = options
    const credential = token.value
    const headers = new Headers(init.headers)
    if (credential) headers.set('Authorization', `Bearer ${credential}`)
    if (init.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json')
    const combinedSignal = signal ? AbortSignal.any([signal, lifetime.signal]) : lifetime.signal
    let response
    try {
      response = await fetch(path, { ...init, headers, signal: combinedSignal, cache: 'no-store', credentials: 'omit' })
    } catch (error) {
      if (error.name === 'AbortError') throw error
      throw new Error('서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.')
    }
    if (!response.ok) {
      let problem = {}
      // JSON이 아닌 프록시 오류는 일반 메시지로 표시한다.
      try { problem = await response.json() } catch { problem = {} }
      if (response.status === 401 && credential && credential === token.value) clear()
      const error = new Error(problem.detail || '요청을 처리하지 못했습니다. 다시 시도해 주세요.')
      error.code = problem.code || `HTTP_${response.status}`
      error.requestId = problem.requestId
      throw error
    }
    if (response.status === 204) return null
    return blob ? response.blob() : response.json()
  }

  /** 검증된 코드 로그인 결과를 메모리에 반영한다.
   * Args: result 서버 세션. Returns: 없음. Raises: 없음. Side Effects: 이전 요청 취소·인증 교체.
   */
  function accept(result) {
    const user = actorFromMe(result.me)
    if (!result.token) throw new Error('로그인 토큰을 확인하지 못했습니다.')
    clear(); token.value = result.token; actor.value = user; profileConfirmed.value = true
  }

  /** 서버가 확인한 소속·역할 확인 결과를 반영한다.
   * Args: profile 서버 프로필 확인 DTO. Returns: 없음. Raises: 없음. Side Effects: 세션 사용자와 확인 상태 갱신.
   */
  function confirmProfile(me) { actor.value = actorFromMe(me); profileConfirmed.value = true }

  /** 확인 후 화면 이동 때 변경된 소속·역할을 다시 반영한다.
   * Args: 없음. Returns: Promise<void>. Raises: 없음(인증 오류는 request가 처리).
   * Side Effects: AUTH-04 GET·등록된 사용자 소속·역할 갱신.
   */
  async function refreshProfile() {
    if (!actor.value || !profileConfirmed.value) return
    const credential = token.value
    try { const me = await request('/api/v1/me'); if (credential === token.value) confirmProfile(me) }
    catch { return }
  }

  /** 선택 검토 항목을 요청·조직별 작업공간에 추가한다.
   * Args: workspace 요청·조직·제출·항목 식별자. Returns: 작업공간 키. Raises: 없음. Side Effects: 메모리 목록 갱신.
   */
  function openReviewWorkspace(workspace) {
    const key = `${workspace.requestId}:${workspace.organizationId}`
    const previous = reviewWorkspaces.value.find(item => item.key === key)
    const retained = previous?.submissionId === workspace.submissionId ? previous.reviewItemIds : []
    const next = { ...workspace, key, reviewItemIds: [...new Set([...retained, ...workspace.reviewItemIds])] }
    reviewWorkspaces.value = [...reviewWorkspaces.value.filter(item => item.key !== key), next]
    return key
  }

  /** 작업공간 탭만 닫고 서버 검토 선정·소명 이력은 유지한다.
   * Args: key 작업공간 키. Returns: 없음. Raises: 없음. Side Effects: 메모리 탐색 목록 제거.
   */
  function closeReviewWorkspace(key) { reviewWorkspaces.value = reviewWorkspaces.value.filter(item => item.key !== key) }

  /** 소명을 요청한 항목을 작업공간에서 제거하고 빈 탭을 닫는다.
   * Args: key 작업공간 키, ids 요청 성공한 검토 ID 목록. Returns: 없음. Raises: 없음. Side Effects: 메모리 탐색 목록 갱신.
   */
  function finishReviewWorkspace(key, ids) {
    reviewWorkspaces.value = reviewWorkspaces.value.map(item => item.key === key ? { ...item, reviewItemIds: item.reviewItemIds.filter(id => !ids.includes(id)) } : item).filter(item => item.reviewItemIds.length)
  }

  /** 실제 알림 수를 읽어 공통 메뉴에 반영한다.
   * Args: 없음. Returns: Promise<void>. Raises: 없음(알림 화면에서 오류 확인). Side Effects: GET·배지 갱신.
   */
  async function refreshUnread() {
    if (actor.value?.role !== 'PLANNER') return
    try { const value = await request('/api/v1/me/badges'); unreadCount.value = value.unreadNotifications || 0 }
    catch { return }
  }

  /** 서버 세션을 폐기하고 메모리 인증을 지운다.
   * Args: 없음. Returns: Promise<void>. Raises: API 오류. Side Effects: 세션 삭제·진행 요청 취소.
   */
  async function logout() {
    logoutKey ||= crypto.randomUUID()
    await request('/api/v1/auth/session', { method: 'DELETE', headers: { 'Idempotency-Key': logoutKey } })
    clear()
  }

  return { actor, token, organizations, unreadCount, profileConfirmed, reviewWorkspaces, clear, request, accept, confirmProfile, refreshProfile, openReviewWorkspace, closeReviewWorkspace, finishReviewWorkspace, refreshUnread, logout }
})
