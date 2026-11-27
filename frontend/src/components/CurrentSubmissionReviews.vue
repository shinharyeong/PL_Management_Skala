<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useSession } from '../stores/session'
import { dateTime, errorText, saveBlob } from '../lib/display'
import { formatMoney } from '../lib/decimal-display'
import { normalizeOrgReviewPage } from '../lib/submission-api'
import { baselineLabel, reviewEvidencePath } from '../lib/review-api.js'
import {canAnswerExplanation} from '../lib/explanation-editing.js'
import {readReviewDraft, saveReviewDraft, clearReviewDraft, resubmissionChange, scenarioLabel} from '../lib/org-review-display.js'
import { evidenceFileSelection } from '../lib/org-upload.js'
import PButton from 'primevue/button'
import PTextarea from 'primevue/textarea'
import PFileUpload from 'primevue/fileupload'
import PDataTable from 'primevue/datatable'
import PColumn from 'primevue/column'
import PMessage from 'primevue/message'
import PPanel from 'primevue/panel'

const props = defineProps({ submission: { type: Object, required: true } })
const emit = defineEmits(['changed', 'loaded'])
const session = useSession()
const groups = ref([]), progress = ref(null), draftKey = ref(''), draftRestored = ref(false)
const items = ref([]), detail = ref(null), selectedId = ref(''), answer = ref(''), note = ref('')
const loading = ref(false), busy = ref(false), error = ref(''), message = ref(''), attachments = ref([])
const evidence = ref([]), comparisonBaseline = ref(null), attachmentFile = ref(null), attachmentInput = ref(null)
const controller = new AbortController()
let detailController = new AbortController(), generation = 0
const keys = new Map()
const latest = computed(() => detail.value?.explanations?.at(-1))
const editable = computed(() => canAnswerExplanation(session.actor?.role,props.submission.status,latest.value))
const hasPrevious = computed(() => detail.value?.rows?.some(row => row.previous != null))
const displayRows = computed(() => (detail.value?.rows || []).map(row => ({...row, correction:resubmissionChange(row.current,row.previous)})))
watch(answer, value => { if(editable.value && draftKey.value) saveReviewDraft(draftKey.value,value) }, {flush:'sync'})
const canDecide = computed(() => session.actor?.role === 'PLANNER' && props.submission.status === 'PENDING' && latest.value?.answer && latest.value.verdict === 'PENDING')

/** 조직 소명 목록과 서버가 선택한 비교 기준을 읽는다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(오류 표시).
 * Side Effects: HTTP 조회·선택 항목 갱신.
 */
async function load() {
  loading.value = true; error.value = ''
  try {
    const page = normalizeOrgReviewPage(await session.request(`/api/v1/review-items?view=ORG&submissionId=${props.submission.id}`, { signal: controller.signal }))
    if (controller.signal.aborted) return
    items.value = page.items; groups.value = page.groups || []; progress.value = page.progress; comparisonBaseline.value = page.baseline; emit('loaded', page)
    const id = items.value.some(item => item.id === selectedId.value) ? selectedId.value : items.value[0]?.id
    if (id) await select(id)
    else detail.value = null
  } catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { loading.value = false }
}

/** 소명 항목 ID로 상세·첨부·원천 근거를 조회한다.
 * Args: id reviewItemId. Returns: Promise<void>. Raises: 없음(오류 표시).
 * Side Effects: 이전 조회 취소·HTTP 조회·화면 상태 갱신.
 */
async function select(id, preserveDraft = false) {
  const pendingAnswer = preserveDraft && editable.value && selectedId.value === id ? answer.value : null
  const current = ++generation
  if(editable.value && draftKey.value) saveReviewDraft(draftKey.value,answer.value)
  draftKey.value = ''; draftRestored.value = false
  detailController.abort(); detailController = new AbortController()
  attachmentFile.value = null
  selectedId.value = id; detail.value = null; attachments.value = []; evidence.value = []; answer.value = ''; note.value = ''; error.value = ''
  try {
    const result = await session.request(`/api/v1/review-items/${id}`, { signal: detailController.signal })
    if (current !== generation || controller.signal.aborted) return
    detail.value = result
    const key = `${session.actor?.id}:${props.submission.id}:${id}:${result.stateToken}`
    const saved = result.explanations?.at(-1)?.answer || ''
    answer.value = pendingAnswer ?? readReviewDraft(key,saved); draftRestored.value = answer.value !== saved; draftKey.value = key
    const [files, source] = await Promise.all([
      session.request(`/api/v1/review-items/${id}/attachments`, { signal: detailController.signal }),
      session.request(reviewEvidencePath(id), { signal: detailController.signal }),
    ])
    if (current === generation) { attachments.value = files.items; evidence.value = source.items }
  } catch (cause) { if (current === generation && cause.name !== 'AbortError') error.value = errorText(cause) }
}

/** 현재 소명을 저장하거나 기획자의 수락·보완 결정을 전송한다.
 * Args: action answer/ACCEPT/REQUEST_AGAIN. Returns: Promise<void>.
 * Raises: 없음(오류 표시). Side Effects: 서버 소명 변경·목록 재조회.
 */
async function command(action) {
  if (busy.value || !detail.value) return
  if (action === 'answer' && (!editable.value || !answer.value.trim())) return
  if (action !== 'answer' && (!canDecide.value || (action === 'REQUEST_AGAIN' && !note.value.trim()))) return
  const id = detail.value.id
  const body = action === 'answer' ? { answer: answer.value.trim(), expectedStateToken: detail.value.stateToken } : { verdict: action, note: note.value.trim() || null, expectedStateToken: detail.value.stateToken }
  const fingerprint = `${id}:${action}:${JSON.stringify(body)}`
  if (!keys.has(fingerprint)) keys.set(fingerprint, crypto.randomUUID())
  busy.value = true; error.value = ''; message.value = ''
  try {
    await session.request(`/api/v1/review-items/${id}/${action === 'answer' ? 'answer' : 'decisions'}`, { method: action === 'answer' ? 'PUT' : 'POST', headers: { 'Idempotency-Key': keys.get(fingerprint) }, body: JSON.stringify(body), signal: controller.signal })
    if (controller.signal.aborted) return
    if(action === 'answer') { clearReviewDraft(draftKey.value); draftKey.value = '' }
    keys.delete(fingerprint); message.value = action === 'answer' ? '소명을 저장했습니다.' : action === 'ACCEPT' ? '소명을 수락했습니다.' : '소명 보완을 요청했습니다.'
    await load(); emit('changed')
  } catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { busy.value = false }
}

/** 선택한 근거 파일을 검증하고 첨부 전 상태에만 보관한다.
 * Args: event PrimeVue FileUpload select 이벤트. Returns: 없음. Raises: 없음.
 * Side Effects: 파일·오류 상태를 변경하며 서버에 전송하지 않는다.
 */
function chooseAttachment(event) {
  const selected = evidenceFileSelection(event)
  attachmentFile.value = selected.file
  error.value = selected.error
  if (selected.error) attachmentInput.value?.clear()
}

/** 아직 첨부하지 않은 근거 파일 선택을 해제한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 메모리 선택만 초기화한다.
 */
function clearAttachment() { attachmentFile.value = null }

/** 첨부하기를 눌렀을 때 현재 회차의 근거 파일을 16MiB 계약으로 전송한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(오류 표시).
 * Side Effects: 첨부 업로드·목록 갱신. 제출 Excel의 20MiB 한도와 구분한다.
 */
async function attach() {
  const file = attachmentFile.value
  if (!file || !editable.value || busy.value) return
  const body = new FormData(); body.append('file', file); body.append('expectedStateToken', detail.value.stateToken)
  busy.value = true; error.value = ''
  try {
    await session.request(`/api/v1/review-items/${detail.value.id}/attachments`, { method: 'POST', body, headers: { 'Idempotency-Key': crypto.randomUUID() }, signal: controller.signal })
    if (!controller.signal.aborted) { await select(selectedId.value,true); emit('changed') }
  } catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { busy.value = false }
}

/** 권한이 있는 소명 첨부 원문을 다운로드한다.
 * Args: item 첨부 DTO. Returns: Promise<void>. Raises: 없음(오류 표시).
 * Side Effects: HTTP 조회·사용자 다운로드.
 */
async function download(item) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { saveBlob(await session.request(`/api/v1/review-items/${detail.value.id}/attachments/${item.id}/file`, { blob: true, signal: controller.signal }), item.name) }
  catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { busy.value = false }
}

/** 화면 종료 시 진행 중인 조회를 취소한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소.
 */
function dispose() { generation++; controller.abort(); detailController.abort() }
onMounted(load)
onBeforeUnmount(dispose)
</script>

<template>
  <section class="panel current-reviews">
    <div class="section-heading"><h2>{{ comparisonBaseline?.kind === 'NONE' ? '제출 항목 확인' : '변경 항목·소명' }}</h2><span class="muted">{{submission.status === 'DRAFT' ? '소명' : '요청 소명'}} {{ progress?.answered ?? 0 }}/{{ progress?.required ?? items.length }} 작성</span></div>
    <p class="muted">비교 기준 · {{ baselineLabel(comparisonBaseline || submission.baseline) }}</p>
    <p v-if="!loading && !error && comparisonBaseline?.kind === 'NONE'" class="first-submission-notice">이전 비교 제출본 없음 · 현재 제출값만 표시합니다. 이전 값과 차액·차이율은 —로 표시합니다.</p>
    <PMessage v-if="error" severity="error" :closable="false">{{ error }}</PMessage>
    <PMessage v-if="message" severity="success" :closable="false">{{ message }}</PMessage>
    <PMessage v-if="loading" severity="info" :closable="false">소명 항목을 불러오는 중…</PMessage>
    <PMessage v-else-if="!items.length" severity="secondary" :closable="false">{{ submission.type === 'ANNUAL_PLAN' ? '연간 계획은 비교 기준 없이 제출 파일을 확인해 제출할 수 있습니다.' : '현재 제출본에 작성할 소명 대상이 없습니다.' }}</PMessage>
    <div v-if="items.length" class="review-layout">
      <aside class="review-list" aria-label="소명 항목 선택">
        <details v-for="group in groups" :key="group.group" class="review-group" open>
          <summary>{{ group.group === 'REVENUE' ? '매출' : '영업비용' }} <small>{{ group.cellCount }}개 셀</small></summary>
          <details v-for="period in group.months" :key="`${period.month}-${period.scenario}`" class="review-period" open>
            <summary>{{ period.month }}월 {{ scenarioLabel(period.scenario) }} <small>{{ period.items.length }}개 항목</small></summary>
            <PButton v-for="item in period.items" :key="item.reviewItemId" class="review-item" :class="{active:selectedId === item.reviewItemId}" :severity="selectedId === item.reviewItemId ? 'primary' : 'secondary'" :outlined="selectedId !== item.reviewItemId" :aria-pressed="selectedId === item.reviewItemId" :disabled="busy" @click="select(item.reviewItemId)">
              <span class="review-item-content"><strong>{{ item.name }}</strong><small>{{ item.sourceCell || '원본 셀은 상세에서 확인' }}</small><span class="answer-state" :class="item.answerState">{{ {NOT_REQUIRED:'소명 작성 불필요',EMPTY:'미작성',WRITTEN:'작성 완료',INHERITED:'이전 소명 유지'}[item.answerState] || item.answerState }}</span></span>
            </PButton>
          </details>
        </details>
      </aside>
      <div v-if="detail" class="review-detail">
        <h3>{{ detail.path }}</h3>
        <p class="muted">금액 단위: 원<span v-if="hasPrevious"> · 승인 기준 차이와 직전 반려본 대비 수정 차이를 구분합니다.</span></p>
        <PDataTable :value="displayRows" stripedRows scrollable :tableStyle="{minWidth:'36rem'}" :pt="{table:{'aria-label':'원본 셀별 금액 비교 · 원'}}">
          <PColumn header="원본 셀"><template #body="{data}">{{data.sourceCell || '—'}}</template></PColumn>
          <PColumn header="승인 기준값" bodyClass="number"><template #body="{data}">{{formatMoney(data.baseline)}}</template></PColumn>
          <PColumn v-if="hasPrevious" header="직전 반려 제출값" bodyClass="number"><template #body="{data}">{{formatMoney(data.previous)}}</template></PColumn>
          <PColumn header="이번 제출값" bodyClass="number"><template #body="{data}">{{formatMoney(data.current)}}</template></PColumn>
          <PColumn header="승인 기준 차액" bodyClass="number"><template #body="{data}">{{formatMoney(data.diff)}}</template></PColumn>
          <PColumn header="승인 기준 차이율"><template #body="{data}">{{data.rate === null ? '—' : data.rate + '%'}}</template></PColumn>
          <PColumn v-if="hasPrevious" header="직전 반려본 대비 수정액" bodyClass="number"><template #body="{data}">{{formatMoney(data.correction.diff)}}</template></PColumn>
          <PColumn v-if="hasPrevious" header="직전 반려본 대비 수정률"><template #body="{data}">{{data.correction.rate == null ? '—' : data.correction.rate + '%'}}</template></PColumn>
          <template #empty>비교할 원본 셀이 없습니다.</template>
        </PDataTable>
        <PMessage v-if="latest?.requestNote" severity="info" :closable="false" class="preserve-lines">{{latest.requestNote}}</PMessage>
        <PPanel header="원천 근거 확인" toggleable :collapsed="true">
          <PDataTable :value="evidence" stripedRows scrollable :tableStyle="{minWidth:'32rem'}" :pt="{table:{'aria-label':'선택한 소명 항목의 원천 근거 · 원'}}">
            <PColumn header="원본 위치"><template #body="{data}">{{data.sourceSheet}}!{{data.sourceCell}}</template></PColumn>
            <PColumn header="계정"><template #body="{data}">{{data.account?.name || '—'}}</template></PColumn>
            <PColumn header="산출 근거"><template #body="{data}">{{data.description || '—'}}</template></PColumn>
            <PColumn header="금액" bodyClass="number"><template #body="{data}">{{formatMoney(data.amount)}}</template></PColumn>
            <template #empty>조회 가능한 원천 근거가 없습니다.</template>
          </PDataTable>
        </PPanel>
        <section v-if="detail.previousExplanation" class="previous-explanation"><h4>지난 승인 소명</h4><p class="preserve-lines">{{detail.previousExplanation.answer}}</p><small>{{detail.previousExplanation.approvedLabel || '승인된 소명'}} · {{detail.previousExplanation.answeredBy?.name || '—'}} · {{dateTime(detail.previousExplanation.answeredAt)}}</small></section>
        <form v-if="editable" @submit.prevent="command('answer')">
          <p v-if="draftRestored" class="muted" role="status">저장 전 작성하던 내용을 유지했습니다. 소명 저장을 눌러 반영해 주세요.</p>
          <label :for="`review-answer-${detail.id}`">소명 작성<PTextarea :id="`review-answer-${detail.id}`" v-model="answer" rows="5" autoResize required maxlength="4000" :disabled="busy" /></label>
          <div class="attachment-input">
            <strong>근거 파일 첨부</strong>
            <span class="muted">PDF·XLSX·PNG·JPEG · 파일당 최대 16MB · 첨부하기를 누르면 전송됩니다.</span>
            <div class="attachment-actions">
              <PFileUpload :key="detail.id" ref="attachmentInput" mode="basic" customUpload :auto="false" :multiple="false" accept=".pdf,.xlsx,.png,.jpg,.jpeg" :maxFileSize="16*1024*1024" chooseLabel="근거 파일 선택" :disabled="busy" invalidFileTypeMessage="{0}: PDF·XLSX·PNG·JPEG 파일을 선택해 주세요." invalidFileSizeMessage="{0}: 16MB 이하 파일을 선택해 주세요." @select="chooseAttachment" @clear="clearAttachment" />
              <PButton v-if="attachmentFile" type="button" label="선택 해제" severity="secondary" text :disabled="busy" @click="attachmentInput?.clear()" />
              <PButton type="button" label="첨부하기" icon="pi pi-paperclip" severity="secondary" outlined :disabled="!attachmentFile || busy" @click="attach" />
            </div>
          </div>
          <PButton type="submit" label="소명 저장" :loading="busy" :disabled="busy || !answer.trim()" class="review-save" />
        </form>
        <div v-if="attachments.length" class="attachment-list">
          <PButton v-for="file in attachments" :key="file.id" :label="file.name" icon="pi pi-download" text :disabled="busy" @click="download(file)" />
        </div>
        <form v-if="canDecide" @submit.prevent="command('ACCEPT')">
          <label :for="`review-note-${detail.id}`">경영기획 메모<PTextarea :id="`review-note-${detail.id}`" v-model="note" rows="3" autoResize maxlength="4000" :disabled="busy" /></label>
          <div class="button-row"><PButton type="button" label="소명 보완 요청" severity="warn" outlined :disabled="busy || !note.trim()" @click="command('REQUEST_AGAIN')" /><PButton type="submit" label="소명 수락" :loading="busy" :disabled="busy" /></div>
        </form>
        <PPanel v-for="explanation in detail.explanations" :key="explanation.id" :header="`${explanation.round}회차 · ${{ PENDING: '검토 중', ACCEPTED: '승인', REJECTED: '소명 보완 요청', REQUESTED_AGAIN: '보완 요청' }[explanation.verdict] || explanation.verdict}`" toggleable>
          <p class="preserve-lines">{{ explanation.answer || '아직 작성된 소명이 없습니다.' }}</p><small>{{ explanation.answeredBy?.name }} · {{ dateTime(explanation.answeredAt) }}</small><p v-if="explanation.reviewNote" class="preserve-lines">{{ explanation.reviewNote }}</p>
        </PPanel>
      </div>
    </div>
    <slot name="footer" />
  </section>
</template>

<style scoped>
.first-submission-notice {padding:12px 16px;background:#f6f6f5;border-radius:8px;color:#5b5b58;}
.review-group > summary,.review-period > summary {cursor:pointer;font-weight:700;padding:10px;border-radius:7px;}
.review-group > summary {background:#414140;color:white;}
.review-period {margin:8px 0 8px 8px;border-left:2px solid #e4e4e3;padding-left:8px;}
.review-period > summary {background:#e4f2f3;color:#195866;margin-bottom:8px;}
summary small {float:right;font-weight:400;}
.review-period .review-item {margin-bottom:8px;}
.answer-state {align-self:flex-start;font-size:12px;border-radius:6px;padding:2px 7px;background:#fff2d5;color:#8a5300;}
.answer-state.NOT_REQUIRED,.answer-state.WRITTEN,.answer-state.INHERITED {background:#ececeb;color:#444442;}
.previous-explanation {padding:16px;background:#f5f5f4;border:1px solid #e1e1e1;border-radius:10px;}
.previous-explanation h4 {margin:0;}

.review-layout { display:grid; grid-template-columns:minmax(200px,1fr) minmax(0,3fr); gap:20px; }
.review-list { display:flex; flex-direction:column; gap:8px; max-height:600px; overflow:auto; }
.review-item { width:100%; justify-content:flex-start; text-align:left; }
.review-item-content { display:flex; flex-direction:column; gap:4px; overflow-wrap:anywhere; }
.review-item-content small { color:inherit; opacity:.8; }
.review-detail { min-width:0; display:flex; flex-direction:column; gap:16px; }
.review-detail form, .review-detail form > label, .attachment-input { display:flex; flex-direction:column; gap:12px; }
.attachment-list, .attachment-actions, .button-row { display:flex; flex-wrap:wrap; align-items:center; gap:12px; }
.attachment-list :deep(.p-button-label) { overflow-wrap:anywhere; }
.button-row { justify-content:flex-end; }
.review-save { align-self:flex-end; }
.preserve-lines { white-space:pre-wrap; overflow-wrap:anywhere; }
@media(max-width:800px) { .review-layout { grid-template-columns:1fr; }.review-list { max-height:230px; } }
</style>
