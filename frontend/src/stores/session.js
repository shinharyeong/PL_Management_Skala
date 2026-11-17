import { ref } from 'vue'
import { defineStore } from 'pinia'
import { errorText } from '../lib/display'

/** 메모리 인증과 동일 출처 통신을 구성한다.
 * Args: 없음. Returns: Pinia 세션 상태와 동작. Raises: 없음.
 * Side Effects: 로그인·요청 시 HTTP 통신; 토큰을 저장소·로그에 기록하지 않음.
 */
export const useSession = defineStore('session', function sessionStore() {
  const actor = ref(null)
  const token = ref('')
  const organizations = ref([])
  const entering = ref(false)
  const entryError = ref('')
  const unreadCount = ref(0)
  let lifetime = new AbortController()

  /** 진행 중인 요청을 취소하고 역할을 초기화한다.
   * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 메모리 인증 제거, HTTP 취소.
   */
  function clear() {
    lifetime.abort()
    lifetime = new AbortController()
    actor.value = null
    token.value = ''
    organizations.value = []
    entering.value = false
    entryError.value = ''
    unreadCount.value = 0
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
    return blob ? response.blob() : response.json()
  }

  /** 기존 세션을 지우고 선택 역할·조직의 서버 인증 결과를 메모리에 보관한다.
   * Args: role PLANNER/ORG_MANAGER, signal 선택적 취소 신호, organizationCode 조직 담당자의 선택적 조직 코드.
   * Returns: Promise<void>. Raises: API 오류·AbortError.
   * Side Effects: 이전 요청 취소·인증 초기화·개발 세션 요청·조직 목록 갱신; 실패는 입장 화면에 표시한다.
   */
  async function enter(role, signal, organizationCode) {
    clear()
    const attempt = lifetime
    entering.value = true
    try {
      const input = { role }
      if (organizationCode !== undefined) input.organizationCode = organizationCode
      const result = await request('/api/dev/session', { method: 'POST', body: JSON.stringify(input), signal })
      if (attempt !== lifetime || attempt.signal.aborted) throw new DOMException('세션 전환이 취소되었습니다.', 'AbortError')
      organizations.value = result.organizations || []
      token.value = result.token
      actor.value = result.actor
    } catch (cause) {
      if (attempt !== lifetime || attempt.signal.aborted) throw new DOMException('세션 전환이 취소되었습니다.', 'AbortError')
      if (attempt === lifetime && cause.name !== 'AbortError') entryError.value = errorText(cause)
      throw cause
    } finally {
      if (attempt === lifetime) entering.value = false
    }
  }

  /** 검증된 코드 로그인 결과를 메모리에 반영한다.
   * Args: result 서버 세션. Returns: 없음. Raises: 없음. Side Effects: 이전 요청 취소·인증 교체.
   */
  function accept(result) { clear(); token.value = result.token; actor.value = result.actor; organizations.value = result.organizations || [] }

  /** 실제 알림 수를 읽어 공통 메뉴에 반영한다.
   * Args: 없음. Returns: Promise<void>. Raises: 없음(알림 화면에서 오류 확인). Side Effects: GET·배지 갱신.
   */
  async function refreshUnread() {
    if (actor.value?.role !== 'PLANNER') return
    try { const value = await request('/api/v1/notifications?limit=1'); unreadCount.value = value.summary.unreadCount }
    catch { return }
  }

  /** 서버 세션을 폐기하고 메모리 인증을 지운다.
   * Args: 없음. Returns: Promise<void>. Raises: API 오류. Side Effects: 세션 삭제·진행 요청 취소.
   */
  async function logout() { await request('/api/v1/auth/session', { method: 'DELETE' }); clear() }

  return { actor, token, organizations, entering, entryError, unreadCount, clear, request, enter, accept, refreshUnread, logout }
})
