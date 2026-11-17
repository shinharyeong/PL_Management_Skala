<script setup>
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useSession } from '../stores/session'
import { dateTime, errorText, saveBlob } from '../lib/display'

const props = defineProps({ request: { type: Object, required: true } })
const emit = defineEmits(['updated', 'refresh'])
const session = useSession()
const controller = new AbortController()
const initialDueAt = localDateTime(props.request.dueAt)
const form = reactive({ message: props.request.message || '', dueAt: initialDueAt, status: props.request.status })
const busy = ref(false)
const error = ref('')
const notice = ref('')
const keys = new Map()
const template = ref(null)
const templateBusy = ref(false)
const templateError = ref('')
const additionalOrganizations = ref([])
const templateKeys = new Map()

/** 요청에 등록된 실제 제출 양식의 메타데이터만 조회한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: GET과 양식 상태 갱신.
 */
async function loadTemplate() {
  templateError.value = ''
  try { const result = await session.request(`/api/v1/submission-requests/${props.request.id}/template`, { signal: controller.signal }); if (!controller.signal.aborted) template.value = result }
  catch (cause) { if (cause.name !== 'AbortError') templateError.value = errorText(cause) }
}

/** 기획자가 선택한 실제 XLSX 양식을 원본 그대로 등록한다.
 * Args: event 파일 선택 이벤트. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: multipart POST·양식 메타 조회; 제출 원본은 변경하지 않는다.
 */
async function uploadTemplate(event) {
  const file = event.target.files?.[0]
  if (!file || templateBusy.value) return
  templateBusy.value = true; templateError.value = ''
  try {
    const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', await file.arrayBuffer()))
    let fingerprint = file.name
    for (const value of digest) fingerprint += value.toString(16).padStart(2, '0')
    if (!templateKeys.has(fingerprint)) templateKeys.set(fingerprint, crypto.randomUUID())
    const form = new FormData(); form.append('file', file)
    await session.request(`/api/v1/submission-requests/${props.request.id}/template`, { method: 'POST', body: form, headers: { 'Idempotency-Key': templateKeys.get(fingerprint) }, signal: controller.signal })
    templateKeys.delete(fingerprint)
    await loadTemplate()
  } catch (cause) { if (cause.name !== 'AbortError') templateError.value = errorText(cause) }
  finally { templateBusy.value = false; event.target.value = '' }
}

/** 서버에 등록한 실제 제출 양식만 내려받는다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: GET·명시적 브라우저 다운로드.
 */
async function downloadTemplate() {
  if (!template.value?.available || templateBusy.value) return
  templateBusy.value = true; templateError.value = ''
  try { const file = await session.request(`/api/v1/submission-requests/${props.request.id}/template/file`, { blob: true, signal: controller.signal }); if (!controller.signal.aborted) saveBlob(file, template.value.filename) }
  catch (cause) { if (cause.name !== 'AbortError') templateError.value = errorText(cause) }
  finally { templateBusy.value = false }
}

/** 서버 시각을 브라우저 현지 시간의 입력 값으로 변환한다.
 * Args: value ISO 시각 또는 null. Returns: datetime-local 문자열 또는 빈 문자열.
 * Raises: 없음. Side Effects: 없음; 저장하지 않은 원래 초·시간대는 변경 본문에서 보존한다.
 */
function localDateTime(value) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const offset = date.getTimezoneOffset() * 60000
  return new Date(date.getTime() - offset).toISOString().slice(0, 16)
}

/** 변경한 안내·기한·개폐 상태만 현재 토큰과 함께 저장한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: 명시적 PATCH와 성공 이벤트; 같은 본문 재시도는 같은 멱등 키를 사용한다.
 */
async function save() {
  if (busy.value || session.actor?.role !== 'PLANNER') return
  const payload = { expectedStateToken: props.request.stateToken }
  if (form.message !== (props.request.message || '')) payload.message = form.message || null
  if (form.dueAt !== initialDueAt) {
    const date = form.dueAt ? new Date(form.dueAt) : null
    if (date && Number.isNaN(date.getTime())) { error.value = '회신 기한을 확인해 주세요.'; return }
    payload.dueAt = date ? date.toISOString() : null
  }
  if (form.status !== props.request.status) payload.status = form.status
  if (additionalOrganizations.value.length) {
    const selected = []
    for (const target of props.request.targets || []) if (target.selected !== false) selected.push(String(target.organization.id))
    payload.organizationIds = [...selected, ...additionalOrganizations.value]
  }
  if (Object.keys(payload).length === 1) { notice.value = '변경한 내용이 없습니다.'; error.value = ''; return }
  const body = JSON.stringify(payload)
  if (!keys.has(body)) keys.set(body, crypto.randomUUID())
  busy.value = true; error.value = ''; notice.value = ''
  try {
    const result = await session.request(`/api/v1/submission-requests/${props.request.id}`, {
      method: 'PATCH', body, headers: { 'Idempotency-Key': keys.get(body) }, signal: controller.signal,
    })
    if (controller.signal.aborted) return
    keys.delete(body); emit('updated', result)
  } catch (cause) {
    if (!controller.signal.aborted && cause.name !== 'AbortError') error.value = errorText(cause)
  } finally { if (!controller.signal.aborted) busy.value = false }
}

/** 최신 요청을 다시 읽도록 부모에게 명시적으로 요청한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: refresh 이벤트만 전달한다.
 */
function refresh() { if (!busy.value) emit('refresh') }

/** 화면 이탈 이후 저장 응답이 다른 요청 화면에 반영되지 않게 중단한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 대기 HTTP 취소.
 */
function dispose() { controller.abort() }
onBeforeUnmount(dispose)
onMounted(loadTemplate)
</script>

<template>
  <details v-if="session.actor?.role === 'PLANNER'" class="request-editor">
    <summary>요청 안내·기한·접수 상태 변경 <span class="badge">{{ request.status === 'OPEN' ? '접수 중' : '마감' }}</span></summary>
    <form @submit.prevent="save">
      <p class="muted">마감하면 새 업로드·최종 제출을 막습니다. 기존 제출본 검토와 소명 보완은 계속할 수 있습니다. 회신 기한은 안내용이며 자동 마감하지 않습니다.</p>
      <div class="filters"><label>회신 기한<input v-model="form.dueAt" type="datetime-local" :disabled="busy" /></label><label>접수 상태<select v-model="form.status" :disabled="busy"><option value="OPEN">접수 중</option><option value="CLOSED">마감</option></select></label></div>
      <label>요청 안내<textarea v-model="form.message" maxlength="5000" rows="3" :disabled="busy"></textarea></label>
      <fieldset v-if="request.targets?.some(target => target.selected === false)" class="additional-organizations"><legend>아직 발송하지 않은 조직에 추가 요청</legend><label v-for="target in request.targets.filter(target => target.selected === false)" :key="target.organization.id"><input v-model="additionalOrganizations" type="checkbox" :value="String(target.organization.id)" :disabled="busy" />{{ target.organization.name }}</label></fieldset>
      <p class="muted">연도·월과 기존 제출본은 유지합니다. 확정된 연간 계획 요청은 다시 열 수 없습니다.</p>
      <div v-if="error" class="alert error" role="alert">{{ error }} <button type="button" class="text-button" :disabled="busy" @click="refresh">최신 요청 다시 읽기</button></div>
      <p v-if="notice" class="notice" role="status">{{ notice }}</p>
      <button class="button primary" :disabled="busy">{{ busy ? '저장 중…' : '변경 내용 저장' }}</button>
    </form>
    <section class="template-settings"><h3>제출 양식</h3><p>{{ template?.available ? template.filename : '등록된 제출 양식이 없습니다.' }}<small v-if="template?.updatedAt"> · {{ dateTime(template.updatedAt) }}</small></p><div class="button-row"><button type="button" class="button" :disabled="!template?.available || templateBusy" @click="downloadTemplate">양식 다운로드</button><label class="button">{{ templateBusy ? '처리 중…' : template?.available ? '양식 교체' : '양식 등록' }}<input class="visually-hidden" type="file" accept=".xlsx" :disabled="templateBusy" @change="uploadTemplate" /></label></div><p v-if="templateError" class="alert error" role="alert">{{ templateError }}</p></section>
  </details>
</template>

<style scoped>
.request-editor { margin:20px 0; border:1px solid var(--line); border-radius:9px; padding:16px; background:#fff; }
.request-editor summary { display:flex; align-items:center; gap:10px; cursor:pointer; font-size:13px; font-weight:600; }
.request-editor form { margin-top:16px; }.request-editor form>p { margin:12px 0; font-size:12px; line-height:1.7; }
.request-editor .button { margin-top:8px; }
.template-settings { border-top:1px solid var(--line); margin-top:20px; padding-top:16px; }.template-settings p { margin:8px 0; font-size:12px; color:var(--muted); }
.additional-organizations { margin-top:18px; gap:8px; }.additional-organizations legend { font-size:12px; margin-bottom:8px; }.additional-organizations label { display:flex; gap:8px; align-items:center; font-weight:400; }.additional-organizations input { width:15px; min-height:15px; margin:0; }
</style>
