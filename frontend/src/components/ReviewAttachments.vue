<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useSession } from '../stores/session'
import { dateTime, errorText, saveBlob } from '../lib/display'

const props = defineProps({ review: { type: Object, required: true }, editable: { type: Boolean, default: false } })
const emit = defineEmits(['changed', 'busy'])
const session = useSession()
const items = ref([])
const file = ref(null)
const input = ref(null)
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const lifetime = new AbortController()
let uploadKey = ''
let uploadBody = null

/** 검토 항목의 모든 소명 회차 첨부를 읽는다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 오류). Side Effects: GET·메모리 목록 갱신.
 */
async function load() {
  loading.value = true; error.value = ''
  try {
    const result = await session.request(`/api/v1/review-items/${props.review.id}/attachments`, { signal: lifetime.signal })
    if (!lifetime.signal.aborted) items.value = result.items
  } catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { loading.value = false }
}

/** 지원하는 원문 한 개를 메모리에 선택하고 이전 전송 키를 초기화한다.
 * Args: event 파일 입력 이벤트. Returns: 없음. Raises: 없음(화면 오류). Side Effects: 메모리 File·오류·멱등키 갱신.
 */
function selectFile(event) {
  const selected = event.target.files?.[0]
  event.target.value = ''; file.value = null; uploadBody = null; uploadKey = ''; error.value = ''; notice.value = ''
  if (!selected || !props.editable || busy.value) return
  if (!/\.(pdf|xlsx|png|jpe?g)$/i.test(selected.name)) { error.value = 'PDF·XLSX·PNG·JPEG 파일을 선택해 주세요.'; return }
  if (selected.size === 0 || selected.size > 16 * 1024 * 1024) { error.value = '비어 있지 않은 16MB 이하 파일을 선택해 주세요.'; return }
  file.value = selected; uploadKey = crypto.randomUUID()
}

/** 사용자가 선택한 근거를 현재 회차·토큰에 연결해 명시적으로 첨부한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 오류).
 * Side Effects: multipart POST·서버 목록 갱신·상위 token 갱신 이벤트. 통신 재시도는 같은 body·key를 유지한다.
 */
async function upload() {
  if (!props.editable || !file.value || busy.value) return
  if (!uploadBody) {
    uploadBody = new FormData()
    uploadBody.append('file', file.value)
    uploadBody.append('round', String(props.review.currentRound))
    uploadBody.append('expectedStateToken', props.review.stateToken)
  }
  busy.value = true; error.value = ''; notice.value = ''
  emit('busy', true)
  try {
    const result = await session.request(`/api/v1/review-items/${props.review.id}/attachments`, { method: 'POST', body: uploadBody, headers: { 'Idempotency-Key': uploadKey }, signal: lifetime.signal })
    if (lifetime.signal.aborted) return
    file.value = null; uploadBody = null; uploadKey = ''; notice.value = '소명 근거를 첨부했습니다.'
    emit('changed', result.review)
    await load()
  } catch (cause) {
    if (cause.name !== 'AbortError') {
      error.value = errorText(cause)
      if (['RESOURCE_CHANGED', 'EXPLANATION_ROUND_CHANGED', 'EXPLANATION_LOCKED', 'REVIEW_CONTEXT_CHANGED'].includes(cause.code)) { uploadBody = null; uploadKey = crypto.randomUUID() }
    }
  } finally { busy.value = false; emit('busy', false) }
}

/** 권한을 다시 확인한 저장 첨부 원문을 내려받는다.
 * Args: item 서버 첨부 메타데이터. Returns: Promise<void>. Raises: 없음(화면 오류). Side Effects: GET·사용자 다운로드.
 */
async function download(item) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { saveBlob(await session.request(`/api/v1/review-items/${props.review.id}/attachments/${item.id}/file`, { blob: true, signal: lifetime.signal }), item.filename) }
  catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { busy.value = false }
}

/** 파일 선택 버튼을 연결한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 브라우저 파일 선택기 표시.
 */
function choose() { if (props.editable && !busy.value) input.value?.click() }

/** 선택한 미전송 파일만 취소한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 메모리 선택 초기화; 저장된 첨부는 지우지 않는다.
 */
function clearFile() { if (!busy.value) { file.value = null; uploadBody = null; uploadKey = '' } }

/** 화면 이탈 시 첨부 요청을 취소한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소.
 */
function dispose() { lifetime.abort(); emit('busy', false) }
onMounted(load)
onBeforeUnmount(dispose)
</script>

<template>
  <section v-if="editable || items.length || loading || error" class="attachment-section" :aria-labelledby="`attachment-title-${review.id}`">
    <div class="section-heading"><h3 :id="`attachment-title-${review.id}`">첨부 파일 <span class="muted">{{ items.length }}개</span></h3><button v-if="editable" class="text-button" :disabled="busy" @click="choose">파일 선택</button></div>
    <p v-if="editable" class="attachment-help">PDF·XLSX·PNG·JPEG · 파일당 최대 16MB. 보완 소명을 제출하기 전에 근거 파일을 첨부해 주세요.</p>
    <input ref="input" class="visually-hidden" type="file" accept=".pdf,.xlsx,.png,.jpg,.jpeg" aria-label="소명 근거 파일 선택" :disabled="!editable || busy" @change="selectFile" />
    <div v-if="file" class="selected-attachment"><span>{{ file.name }} <small>{{ (file.size / 1024).toFixed(1) }} KB</small></span><div class="button-row"><button class="text-button" :disabled="busy" @click="clearFile">취소</button><button class="button" :disabled="busy || !editable" @click="upload">{{ busy ? '처리 중…' : '첨부하기' }}</button></div></div>
    <p v-if="loading" class="muted" role="status">첨부를 불러오는 중…</p><p v-if="error" class="alert error" role="alert">{{ error }} <button class="text-button" :disabled="loading || busy" @click="load">목록 다시 확인</button></p><p v-if="notice" class="notice" role="status">{{ notice }}</p>
    <ul v-if="items.length" class="attachment-list"><li v-for="item in items" :key="item.id"><button class="text-button" :disabled="busy" @click="download(item)">{{ item.filename }}</button><small>{{ item.round }}회차 · {{ (item.size / 1024).toFixed(1) }} KB · {{ dateTime(item.uploadedAt) }}</small></li></ul><p v-else-if="!loading && !error" class="attachment-help">첨부한 파일이 없습니다.</p>
  </section>
</template>

<style scoped>
.attachment-section { margin-top:4px; padding-top:14px; border-top:1px solid var(--line); display:flex; flex-direction:column; gap:8px; }.attachment-section h3 { font-size:12px; }.attachment-section h3 span { font-weight:400; font-size:11px; margin-left:5px; }.attachment-help { font-size:11px; line-height:1.7; color:var(--muted); }.attachment-list { padding:0; margin:0; list-style:none; display:flex; flex-direction:column; gap:8px; }.attachment-list li { padding:10px 12px; background:#faf9f6; border:1px solid var(--line); border-radius:7px; }.attachment-list button { text-align:left; overflow-wrap:anywhere; }.attachment-list small { display:block; margin-top:4px; color:var(--muted); font-size:10px; }.selected-attachment { display:flex; align-items:center; justify-content:space-between; gap:10px; border:1px solid var(--line); padding:8px 10px; border-radius:7px; font-size:12px; }.selected-attachment>span { overflow-wrap:anywhere; }.selected-attachment small { font-size:10px; color:var(--muted); }.attachment-section .alert { margin:0; }
</style>
