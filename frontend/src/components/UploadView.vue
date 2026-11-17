<script setup>
import { computed, ref, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSession } from '../stores/session'
import { requestTitle, errorText, saveBlob } from '../lib/display'
import { inspectUploadFilename } from '../lib/upload-filename'
import { requestAction } from '../lib/request-workflow'

const route = useRoute()
const router = useRouter()
const session = useSession()
const request = ref(null)
const templateInfo = ref(null)
const templateError = ref('')
const file = ref(null)
const input = ref(null)
const loading = ref(true)
const busy = ref('')
const error = ref('')
const dragging = ref(false)
const useSubmissionFilename = ref(false)
const controller = new AbortController()
let uploadKey = ''

/** 직접 진입한 업로드 화면에도 서버가 계산한 조직별 접수 조건을 적용한다.
 * Args: 없음. Returns: 접수 가능 여부. Raises: 없음. Side Effects: 없음.
 */
const canUpload = computed(function allowsUpload() { return session.actor?.role === 'ORG_MANAGER' && request.value?.status === 'OPEN' && request.value.myWorkflow?.canUpload === true })

/** 접수가 막힌 경우 진행할 기존 제출본 또는 요청 상태 안내를 반환한다.
 * Args: 없음. Returns: 다음 작업 객체. Raises: 없음. Side Effects: 없음.
 */
const blockedAction = computed(function uploadBlocker() { return requestAction(request.value || {}) })

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

/** 기존 제출 안내에서 들어온 전체 재업로드의 돌아가기 위치를 제한한다.
 * Args: 없음. Returns: 유효한 제출 상세 경로 또는 null. Raises: 없음.
 * Side Effects: 없음; 숫자 ID 문맥만 사용하며 원본이나 접수 본문을 변경하지 않는다.
 */
const previousSubmissionLink = computed(function reuploadReturn() {
  const id = route.query.fromSubmission
  return typeof id === 'string' && /^[1-9]\d*$/.test(id) ? `/submissions/${id}` : null
})

/** 요청 상세를 읽는다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: HTTP 조회·상태 변경.
 */
async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await session.request(`/api/v1/submission-requests/${route.params.id}`, { signal: controller.signal })
    if (!controller.signal.aborted) request.value = result
    try { templateInfo.value = await session.request(`/api/v1/submission-requests/${route.params.id}/template`, { signal: controller.signal }) }
    catch (cause) { if (cause.name !== 'AbortError') templateError.value = errorText(cause) }
  }
  catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { loading.value = false }
}

/** 명시적 파일 선택 입력을 연다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 브라우저 파일 선택기 표시.
 */
function choose() { if (canUpload.value) input.value?.click() }

/** 한 개의 XLSX를 검사하고 원본 이름·바이트를 유지한 채 선택한다.
 * Args: files 파일 목록. Returns: 없음. Raises: 없음(화면 표시).
 * Side Effects: 메모리 파일·동의·멱등키·오류 갱신; 다른 조직이나 이름 근거가 맞지 않는 파일은 선택하지 않는다.
 */
function selectFiles(files) {
  if (busy.value || !canUpload.value || !files?.length) return
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
  if (!canUpload.value) throw new Error(blockedAction.value.message)
  const policy = inspectUploadFilename(file.value?.name, filename.value, session.actor.organization?.code)
  if (!policy.allowed) throw new Error(policy.reason)
  if (policy.requiresConsent && !useSubmissionFilename.value) throw new Error('제출 파일명으로 전송하는 데 동의해 주세요.')
  const body = new FormData()
  body.append('file', file.value, filename.value)
  const result = await session.request(`/api/v1/submission-requests/${route.params.id}/submissions`, {
    method: 'POST', body, headers: { 'Idempotency-Key': uploadKey }, signal: controller.signal,
  })
  if (!controller.signal.aborted) await router.push({ path: `/submissions/${result.id || result.submissionId}`, query: { validate: '1' } })
}

/** 선택 파일 업로드의 대기·오류 상태를 관리한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: 업로드 실행·상태 갱신.
 */
async function upload() {
  if (!canUpload.value || !file.value || busy.value || (requiresFilenameConsent.value && !useSubmissionFilename.value)) return
  busy.value = 'upload'
  error.value = ''
  try { await sendFile() }
  catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { busy.value = '' }
}

/** 경영기획팀이 이 요청에 등록한 실제 제출 양식을 내려받는다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: 권한 확인된 원문을 조직·기간별 제출 파일명으로 다운로드한다. 저장된 양식 원문·파일명은 변경하지 않는다.
 */
async function template() {
  if (busy.value || !canUpload.value || !templateInfo.value?.available) return
  busy.value = 'download'
  error.value = ''
  try {
    const blob = await session.request(`/api/v1/submission-requests/${route.params.id}/template/file`, { blob: true, signal: controller.signal })
    if (controller.signal.aborted) return
    saveBlob(blob, filename.value)
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
  <header class="page-heading"><div><p class="overline">{{ session.actor.organization?.name }} · {{ session.actor.organization?.code }}</p><h1>{{ requestTitle(request) }}</h1></div><span v-if="request" class="badge">{{ request.year }}년<template v-if="request.month"> {{ request.month }}월</template></span></header>
  <div v-if="error" class="alert error" role="alert">{{ error }} <button v-if="!request" class="text-button" :disabled="loading" @click="load">다시 시도</button></div>
  <p v-if="loading" class="panel empty" role="status">요청을 불러오고 있습니다…</p>
  <section v-else-if="request" class="panel">
    <div class="section-heading"><h2>{{ previousSubmissionLink ? '전체 파일 다시 업로드' : '엑셀 파일 업로드' }}</h2><span class="badge">{{ request.type === 'ANNUAL_PLAN' ? '연간 계획' : '월간 실적·추정' }}</span></div>
    <div v-if="!canUpload" class="alert" role="status"><p>{{ blockedAction.message }}</p><RouterLink v-if="blockedAction.path" :to="blockedAction.path" class="text-button">{{ blockedAction.label }} →</RouterLink><button class="text-button" :disabled="loading" @click="load">접수 상태 다시 확인</button></div>
    <template v-else>
      <div v-if="previousSubmissionLink" class="notice reupload-guide"><strong>확인한 전체 파일을 올려 주세요.</strong><p>원본 수정이 필요한 경우 이전 안내의 시트·행·월·셀을 확인하고 수정·저장한 파일을 선택하세요. 변경하지 않은 항목도 모두 포함해야 합니다. 업로드하면 새 초안에서 파싱 결과를 확인하고 업무 검증·필수 소명·최종 제출을 진행합니다.</p></div>
      <div class="template-row"><div><h3>제출 양식</h3><p class="muted">{{ filename }}</p><small v-if="templateInfo?.available && templateInfo.filename !== filename" class="muted">등록 양식: {{ templateInfo.filename }}</small><small v-if="!templateInfo?.available" class="muted">{{ templateError ? '제출 양식을 확인하지 못했습니다.' : '경영기획팀의 양식 등록이 필요합니다.' }}</small></div><button class="button" :disabled="!!busy || !templateInfo?.available" @click="template">{{ busy === 'download' ? '다운로드 중…' : '양식 다운로드' }}</button></div>
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
      <div class="panel-footer upload-footer"><RouterLink :to="previousSubmissionLink || '/requests'" class="text-button">{{ previousSubmissionLink ? '이전 제출본 보기' : '요청함으로' }}</RouterLink><button class="button primary" :disabled="!file || !!busy || (requiresFilenameConsent && !useSubmissionFilename)" @click="upload">{{ busy === 'upload' ? '파일을 올리는 중…' : '검증 시작' }}</button></div>
    </template>
  </section>
</template>

<style scoped>
.template-row small { display:block; margin-top:3px; font-size:11px; }.upload-footer { margin-top:2px; }
.reupload-guide { display:block; }.reupload-guide p { margin:6px 0 0; line-height:1.8; }
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
