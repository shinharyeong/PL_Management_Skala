<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useSession } from '../stores/session'
import { errorText } from '../lib/display'

const session = useSession()
const tab = ref('users')
const lists = reactive({ organizations: [], users: [], 'message-templates': [] })
const selected = ref(null)
const form = reactive({ code: '', name: '', email: '', role: 'ORG_MANAGER', organizationId: '', active: true, type: 'ANNUAL_PLAN', message: '' })
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const controller = new AbortController()
const keys = new Map()
const categories = [{ key: 'users', label: '담당자·권한' }, { key: 'organizations', label: '조직' }, { key: 'message-templates', label: '요청 메시지 템플릿' }]

/** 현재 설정 목록을 반환한다. Args: 없음. Returns: 현재 탭 목록. Raises: 없음. Side Effects: 없음. */
const rows = computed(function currentRows() { return lists[tab.value] })

/** 선택을 초기화하거나 저장된 설정을 편집 입력에 복사한다.
 * Args: item 선택 DTO 또는 null. Returns: 없음. Raises: 없음. Side Effects: 입력 상태 갱신.
 */
function edit(item = null) {
  selected.value = item
  Object.assign(form, { code: item?.code || '', name: item?.name || '', email: item?.email || '', role: item?.role || 'ORG_MANAGER', organizationId: item?.organization?.id || '', active: item?.active !== false, type: item?.type || 'ANNUAL_PLAN', message: item?.message || '' })
  error.value = ''; notice.value = ''
}

/** 설정 범주를 전환하고 이전 편집 대상을 해제한다.
 * Args: key 설정 범주. Returns: 없음. Raises: 없음. Side Effects: 탭·입력 갱신.
 */
function changeTab(key) { tab.value = key; edit() }

/** 권한 있는 조직·사용자·메시지 목록을 읽는다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: GET·목록 갱신.
 */
async function load() {
  loading.value = true; error.value = ''
  const results = await Promise.allSettled(categories.map(fetchCategory))
  for (let index = 0; index < results.length; index++) { const result = results[index]; if (result.status === 'fulfilled') lists[categories[index].key] = result.value.items; else if (result.reason.name !== 'AbortError') error.value += `${errorText(result.reason)} ` }
  loading.value = false
}

/** 한 설정 범주의 목록 API를 호출한다.
 * Args: category 범주 객체. Returns: 목록 응답 Promise. Raises: API 오류. Side Effects: GET.
 */
function fetchCategory(category) { return session.request(`/api/v1/settings/${category.key}`, { signal: controller.signal }) }

/** 현재 설정을 서버 토큰과 멱등키로 등록하거나 변경한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: POST/PATCH·목록 갱신.
 */
async function save() {
  if (saving.value || loading.value) return
  if (tab.value === 'message-templates' && form.message.trim().length > (form.type === 'EXPLANATION' ? 4000 : 5000)) { error.value = `메시지는 ${form.type === 'EXPLANATION' ? '4,000' : '5,000'}자 이내로 입력해 주세요.`; return }
  const payload = { name: form.name.trim(), active: form.active }
  if (tab.value === 'organizations' && !selected.value) payload.code = form.code.trim()
  if (tab.value === 'users') { if (!selected.value) payload.email = form.email.trim(); Object.assign(payload, { role: form.role, organizationId: form.role === 'ORG_MANAGER' ? form.organizationId : null }) }
  if (tab.value === 'message-templates') Object.assign(payload, { type: form.type, message: form.message.trim() })
  if (tab.value === 'message-templates' && !selected.value) delete payload.active
  if (selected.value) payload.expectedStateToken = selected.value.stateToken
  const path = `/api/v1/settings/${tab.value}${selected.value ? '/' + selected.value.id : ''}`
  const body = JSON.stringify(payload); const fingerprint = `${path}:${body}`
  if (!keys.has(fingerprint)) keys.set(fingerprint, crypto.randomUUID())
  saving.value = true; error.value = ''; notice.value = ''
  try { await session.request(path, { method: selected.value ? 'PATCH' : 'POST', body, headers: { 'Idempotency-Key': keys.get(fingerprint) }, signal: controller.signal }); keys.delete(fingerprint); edit(); await Promise.allSettled([load(), session.refreshProfile()]); notice.value = '설정을 저장했습니다.' }
  catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { saving.value = false }
}

/** 목록의 범주별 상세 설명을 만든다.
 * Args: item 설정 DTO. Returns: 설명 문자열. Raises: 없음. Side Effects: 없음.
 */
function describe(item) { if (tab.value === 'organizations') return item.code; if (tab.value === 'users') return `${item.email} · ${item.role === 'PLANNER' ? '경영기획팀' : item.organization?.name || '소속 미지정'}`; return { ANNUAL_PLAN: '연간 계획', MONTHLY_ACTUAL: '월간 실적·추정', EXPLANATION: '소명 요청' }[item.type] }

/** 화면을 떠날 때 요청을 취소한다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소. */
function dispose() { controller.abort() }
onMounted(load)
onBeforeUnmount(dispose)
</script>

<template>
  <div class="settings-page"><header class="page-heading"><h1>설정</h1><button class="button" :disabled="loading || saving" @click="load">새로고침</button></header><p class="muted">조직과 수신 담당자, 요청에 사용할 메시지를 관리합니다. 제출 엑셀 양식은 각 연간·월간 요청의 설정에서 등록합니다.</p><div class="settings-tabs" role="tablist" aria-label="설정 범주"><button v-for="category in categories" :key="category.key" role="tab" :aria-selected="tab === category.key" :class="{ active: tab === category.key }" :disabled="saving" @click="changeTab(category.key)">{{ category.label }}</button></div><p v-if="error" class="alert error" role="alert">{{ error }}</p><p v-if="notice" class="alert success" role="status">{{ notice }}</p><div class="settings-grid"><section class="panel"><div class="section-heading"><h2>{{ categories.find(item => item.key === tab).label }}</h2><button class="button" :disabled="saving" @click="edit()">새로 등록</button></div><p v-if="loading" role="status">불러오는 중…</p><p v-else-if="!rows.length" class="empty compact">등록된 항목이 없습니다.</p><ul class="setting-list"><li v-for="item in rows" :key="item.id"><button :class="{ selected: selected?.id === item.id }" :disabled="saving" @click="edit(item)"><span><strong>{{ item.name }}</strong><small>{{ describe(item) }}</small></span><span class="badge">{{ item.active ? '사용' : '사용 중지' }}</span></button></li></ul></section><form class="panel settings-form" @submit.prevent="save"><h2>{{ selected ? '설정 변경' : '새로 등록' }}</h2><fieldset :disabled="saving || loading"><label v-if="tab === 'organizations'">조직 코드<input v-model="form.code" required maxlength="50" :disabled="!!selected" /></label><label>이름<input v-model="form.name" required maxlength="200" /></label><template v-if="tab === 'users'"><label>이메일<input v-model="form.email" type="email" required maxlength="254" :disabled="!!selected" /></label><label>역할<select v-model="form.role" :disabled="selected?.id === session.actor.id"><option value="ORG_MANAGER">조직 담당자</option><option value="PLANNER">경영기획팀</option></select></label><label v-if="form.role === 'ORG_MANAGER'">소속 조직<select v-model="form.organizationId" required><option value="">조직 선택</option><option v-for="organization in lists.organizations.filter(item => item.active || item.id === form.organizationId)" :key="organization.id" :value="organization.id">{{ organization.name }} · {{ organization.code }}</option></select></label></template><template v-if="tab === 'message-templates'"><label>사용할 요청<select v-model="form.type"><option value="ANNUAL_PLAN">연간 계획</option><option value="MONTHLY_ACTUAL">월간 실적·추정</option><option value="EXPLANATION">소명 요청</option></select></label><label>메시지<textarea v-model="form.message" rows="7" required :maxlength="form.type === 'EXPLANATION' ? 4000 : 5000"></textarea></label></template><label class="check-setting"><input v-model="form.active" type="checkbox" :disabled="(!selected && tab === 'message-templates') || (tab === 'users' && selected?.id === session.actor.id)" />사용</label><p v-if="tab === 'users' && selected?.id === session.actor.id" class="muted">자신의 권한 변경과 사용 중지는 다른 경영기획 담당자가 처리할 수 있습니다.</p><p v-if="tab === 'organizations'" class="muted">조직 코드는 등록 후 바꿀 수 없습니다. 진행 중인 요청의 필수 조직은 사용 중지할 수 없습니다.</p></fieldset><button class="button primary" :disabled="saving || loading">{{ saving ? '저장 중…' : '저장' }}</button></form></div></div>
</template>

<style scoped>
.settings-page{display:grid;gap:20px}.settings-tabs{display:flex;gap:8px}.settings-tabs button{background:white;padding:11px 18px;border:1px solid var(--line);border-radius:8px}.settings-tabs .active{background:#191919;color:white}.settings-grid{display:grid;grid-template-columns:minmax(300px,1fr) minmax(300px,1fr);gap:22px;align-items:start}.setting-list{list-style:none;padding:0;display:grid;gap:8px}.setting-list button{display:flex;justify-content:space-between;gap:14px;width:100%;padding:13px;text-align:left;border:1px solid var(--line);border-radius:8px;background:white}.setting-list button.selected{border-color:var(--gold);background:#faf6ee}.setting-list small{display:block;margin-top:5px;color:var(--muted);overflow-wrap:anywhere}.setting-list .badge{align-self:start;white-space:nowrap}.settings-form{display:grid;gap:18px}.settings-form fieldset{display:grid;gap:14px}.check-setting{display:flex;align-items:center;gap:8px}.check-setting input{width:16px;min-height:16px;margin:0}.muted{line-height:1.7;font-size:13px}@media(max-width:850px){.settings-grid{grid-template-columns:1fr}.settings-tabs{flex-wrap:wrap}}
</style>
