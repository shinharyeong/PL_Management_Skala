<script setup>
import { computed, ref, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSession } from '../stores/session'
import { requestTitle, errorText, saveBlob } from '../lib/display'
import { inspectUploadFilename } from '../lib/upload-filename'

const route = useRoute()
const router = useRouter()
const session = useSession()
const request = ref(null)
const file = ref(null)
const input = ref(null)
const loading = ref(true)
const busy = ref('')
const error = ref('')
const dragging = ref(false)
const useSubmissionFilename = ref(false)
const controller = new AbortController()
let uploadKey = ''

/** 조직과 요청 기간에서 서버 계약의 원본 파일명을 구성한다.
 * Args: 없음. Returns: 예상 파일명. Raises: 없음. Side Effects: 없음.
 */
const filename = computed(function expectedFilename() {
  if (!request.value) return ''
  const period = request.value.type === 'ANNUAL_PLAN' ? 'PLAN' : String(request.value.month).padStart(2, '0')
  return `${session.actor.organization?.code}_${request.value.year}-${period}.xlsx`
})

/** 선택 파일에 요청의 제출 이름을 적용하려면 동의가 필요한지 계산한다.
 * Args: 없음. Returns: boolean. Raises: 없음. Side Effects: 없음.
 */
const requiresFilenameConsent = computed(function requiresConsent() {
  return Boolean(file.value && file.value.name !== filename.value)
})

/** 요청 상세를 읽는다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: HTTP 조회·상태 변경.
 */
async function load() {
  loading.value = true
  error.value = ''
  try { request.value = await session.request(`/api/v1/submission-requests/${route.params.id}`, { signal: controller.signal }) }
  catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { loading.value = false }
}

/** 명시적 파일 선택 입력을 연다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 브라우저 파일 선택기 표시.
 */
function choose() { input.value?.click() }

/** 한 개의 XLSX를 검사하고 원본 이름·바이트를 유지한 채 선택한다.
 * Args: files 파일 목록. Returns: 없음. Raises: 없음(화면 표시).
 * Side Effects: 메모리 파일·동의·멱등키·오류 갱신; 다른 조직이나 이름 근거가 맞지 않는 파일은 선택하지 않는다.
 */
function selectFiles(files) {
  if (busy.value || !files?.length) return
  error.value = ''
  file.value = null
  useSubmissionFilename.value = false
  uploadKey = ''
  if (files.length !== 1) { error.value = '파일은 한 번에 1개만 선택해 주세요.'; return }
  const selected = files[0]
  if (!selected.name.toLowerCase().endsWith('.xlsx')) { error.value = '.xlsx 파일만 업로드할 수 있습니다.'; return }
  if (selected.size > 16 * 1024 * 1024 || selected.size === 0) { error.value = '비어 있지 않은 16MB 이하의 파일을 선택해 주세요.'; return }
  const policy = inspectUploadFilename(selected.name, filename.value, session.actor.organization?.code)
  if (!policy.allowed) { error.value = policy.reason; return }
  file.value = selected
  uploadKey = crypto.randomUUID()
}

/** 파일 입력 변경을 공통 선택 처리로 연결한다.
 * Args: event 입력 이벤트. Returns: 없음. Raises: 없음. Side Effects: 선택 갱신·입력 초기화.
 */
function onFile(event) { selectFiles(event.target.files); event.target.value = '' }

/** 드롭한 파일을 선택 처리한다.
 * Args: event 드롭 이벤트. Returns: 없음. Raises: 없음. Side Effects: 드롭 상태·선택 변경.
 */
function onDrop(event) { dragging.value = false; selectFiles(event.dataTransfer.files) }

/** 원본 바이트를 유지하고 동의한 제출 이름으로 접수해 상세로 이동한다.
 * Args: 없음. Returns: Promise<void>. Raises: API 오류·조직/동의 검증 오류.
 * Side Effects: multipart 업로드·초안 생성·라우트 이동; 로컬 파일 이름과 내용은 변경하지 않는다.
 */
async function sendFile() {
  const policy = inspectUploadFilename(file.value?.name, filename.value, session.actor.organization?.code)
  if (!policy.allowed) throw new Error(policy.reason)
  if (policy.requiresConsent && !useSubmissionFilename.value) throw new Error('제출 파일명으로 전송하는 데 동의해 주세요.')
  const body = new FormData()
  body.append('file', file.value, filename.value)
  const result = await session.request(`/api/v1/submission-requests/${route.params.id}/submissions`, {
    method: 'POST', body, headers: { 'Idempotency-Key': uploadKey }, signal: controller.signal,
  })
  await router.push(`/submissions/${result.id || result.submissionId}`)
}

/** 선택 파일 업로드의 대기·오류 상태를 관리한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: 업로드 실행·상태 갱신.
 */
async function upload() {
  if (!file.value || busy.value || (requiresFilenameConsent.value && !useSubmissionFilename.value)) return
  busy.value = 'upload'
  error.value = ''
  try { await sendFile() }
  catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { busy.value = '' }
}

/** 서버 예시 양식을 다운로드하거나 명시적으로 같은 업로드 경로에 접수한다.
 * Args: run 바로 파싱 실행 여부. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: 양식 HTTP 조회, 다운로드 또는 초안 생성·화면 이동.
 */
async function template(run = false) {
  busy.value = run ? 'example' : 'download'
  error.value = ''
  try {
    const blob = await session.request(`/api/dev/template?requestId=${encodeURIComponent(route.params.id)}`, { blob: true, signal: controller.signal })
    if (run) {
      file.value = new File([blob], filename.value, { type: blob.type })
      useSubmissionFilename.value = false
      uploadKey = crypto.randomUUID()
      await sendFile()
    } else saveBlob(blob, filename.value)
  } catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { busy.value = '' }
}

/** 화면을 떠나면 조회·업로드를 취소한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소.
 */
function dispose() { controller.abort() }
onMounted(load)
onBeforeUnmount(dispose)
</script>

<template>
  <RouterLink to="/requests" class="back-link">← 요청함</RouterLink>
  <header class="page-heading"><div><p class="overline">{{ session.actor.organization?.name }} · {{ session.actor.organization?.code }}</p><h1>{{ requestTitle(request) }}</h1></div><span v-if="request" class="badge">{{ request.year }}년</span></header>
  <div v-if="error" class="alert error" role="alert">{{ error }} <button v-if="!request" class="text-button" :disabled="loading" @click="load">다시 시도</button></div>
  <p v-if="loading" class="panel empty" role="status">요청을 불러오고 있습니다…</p>
  <section v-else-if="request" class="panel">
    <div class="section-heading"><h2>엑셀 파일 업로드</h2><span class="badge">{{ request.type === 'ANNUAL_PLAN' ? '연간 계획' : '월간 실적·추정' }}</span></div>
    <p v-if="request.status !== 'OPEN'" class="alert">마감된 요청입니다. 새로운 파일을 업로드할 수 없습니다.</p>
    <template v-else-if="session.actor.role === 'ORG_MANAGER'">
      <div class="template-row"><div><h3>제출 양식</h3><p class="muted">{{ filename }}</p></div><div class="button-row"><button class="button" :disabled="!!busy" @click="template(false)">{{ busy === 'download' ? '다운로드 중…' : '테스트 양식 다운로드' }}</button><button class="button soft" :disabled="!!busy" @click="template(true)">{{ busy === 'example' ? '예시를 업로드하는 중…' : '테스트 양식으로 실행' }}</button></div></div>
      <div :class="['drop-zone', { dragging }]" @dragover.prevent="dragging = true" @dragleave.prevent="dragging = false" @drop.prevent="onDrop">
        <h3>엑셀 파일을 여기에 끌어다 놓으세요</h3><p>.xlsx · 요청당 파일 1개 · 최대 16MB</p>
        <input ref="input" type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" class="visually-hidden" :disabled="!!busy" aria-label="엑셀 파일 선택" @change="onFile" />
        <button class="button" :disabled="!!busy" @click="choose">파일 선택</button>
      </div>
      <div v-if="file" class="selected-upload">
        <div class="selected-file">
          <span class="file-label">XLSX</span>
          <div>
            <dl class="filename-details"><div><dt>원본 파일명</dt><dd>{{ file.name }}</dd></div><div><dt>전송 예정 파일명</dt><dd>{{ filename }}</dd></div></dl>
            <small>{{ (file.size / 1024).toFixed(1) }} KB · 전체 항목 파일</small>
          </div>
          <span class="badge">{{ requiresFilenameConsent && !useSubmissionFilename ? '이름 확인 필요' : '선택 완료' }}</span>
        </div>
        <label v-if="requiresFilenameConsent" class="filename-consent">
          <input v-model="useSubmissionFilename" type="checkbox" :disabled="!!busy" />
          <span><strong>제출 파일명으로 전송</strong><small>원본 파일은 수정하지 않습니다. 파일 내용은 그대로 두고 업로드에 사용할 이름만 변경합니다.</small></span>
        </label>
      </div>
      <div class="panel-footer"><span class="muted">업로드 후 파싱 결과를 확인할 수 있습니다.</span><button class="button primary" :disabled="!file || !!busy || (requiresFilenameConsent && !useSubmissionFilename)" @click="upload">{{ busy === 'upload' ? '업로드 중…' : '파싱 시작' }}</button></div>
    </template>
    <p v-else class="alert">파일 업로드는 조직 담당자 역할에서 할 수 있습니다.</p>
  </section>
</template>

<style scoped>
.filename-details { display: flex; flex-direction: column; gap: 4px; margin: 0; font-size: 12px; }
.filename-details > div { display: flex; align-items: baseline; gap: 14px; }
.filename-details dt { width: 94px; flex-shrink: 0; color: #7c808a; font-size: 11px; }
.filename-details dd { margin: 0; overflow-wrap: anywhere; font-weight: 500; }
.filename-consent { display: flex; align-items: flex-start; gap: 9px; margin-top: 10px; padding: 12px 14px; border-radius: 8px; background: #faf7f1; }
.filename-consent input { width: 16px; min-height: 16px; height: 16px; margin: 3px 0 0; flex-shrink: 0; accent-color: #a9875b; }
.filename-consent strong { font-size: 12px; font-weight: 600; }
.filename-consent small { display: block; margin-top: 3px; color: #7c808a; font-size: 11px; font-weight: 400; line-height: 1.7; }
@media (max-width: 760px) {
  .filename-details > div { flex-direction: column; gap: 2px; }
  .filename-details dt { width: auto; }
}
</style>
