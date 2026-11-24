/** 현재 역할이 화면을 마운트할 수 있는지 검사한다.
 * Args: route Vue Router 경로, role 로그인 역할. Returns: 허용 여부.
 * Raises: 없음. Side Effects: 없음; 개별 자료의 조직 권한은 서버가 검사한다.
 */
export function routeAllowed(route, role) {
  if (route.meta?.plannerCanvas && role !== 'PLANNER') return false
  if (['requests', 'upload'].includes(route.name) && role !== 'ORG_MANAGER') return false
  return true
}

/** 재입장 시 허용된 상세 경로와 조회 문맥을 복원한다.
 * Args: route 현재 경로, role 로그인 역할. Returns: 복원 경로 또는 역할 기본 경로.
 * Raises: 없음. Side Effects: 없음; 토큰을 저장하거나 역할을 자동 선택하지 않는다.
 */
export function roleDestination(route, role) {
  return route.name !== 'home' && routeAllowed(route, role) ? route.fullPath : role === 'PLANNER' ? '/planning' : '/requests'
}
