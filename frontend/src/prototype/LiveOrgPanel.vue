<script setup>
import copy from './copy-text.js'
import {uploadGuidance} from './upload-guidance.js'
import {computed,ref,watch,onBeforeUnmount} from 'vue'
import {usePrototypeStore} from './live-store.js'
import {normalizeSubmission} from '../lib/submission-api.js'
import CurrentSubmissionReviews from '../components/CurrentSubmissionReviews.vue'
import CurrentSubmissionHistory from '../components/CurrentSubmissionHistory.vue'
import {baselineLabel} from '../lib/review-api.js'
import {status,saveFile} from './live-api.js'
import PButton from 'primevue/button'
import PSelect from 'primevue/select'
import PSelectButton from 'primevue/selectbutton'
import PTextarea from 'primevue/textarea'
import PDialog from 'primevue/dialog'
import PFileUpload from 'primevue/fileupload'
import PDataTable from 'primevue/datatable'
import PColumn from 'primevue/column'
import PTag from 'primevue/tag'
import PMessage from 'primevue/message'
import PPanel from 'primevue/panel'
import {submissionFileSelection} from '../lib/org-upload.js'
import './org.css'
import './org-live.css'
const props=defineProps({page:String}),emit=defineEmits(['navigate']),store=usePrototypeStore()
const filenameHelp=computed(()=>uploadGuidance(store.actor,store.activeOrgRequest))
const file=ref(null),submission=ref(null),diagnostics=ref(null),preview=ref(null),reply=ref(''),request=ref(null),loading=ref(false)
const dragOver=ref(false)
const statusClass=value=>['REJECTED','RESUBMIT_REQUESTED'].includes(value)?'recheck':value==='APPROVED'?'approved':value==='PENDING'?'progress':'waiting'
function dropFile(event){dragOver.value=false;chooseFile({files:Array.from(event.dataTransfer?.files||[])})}
const fileInput=ref(null),fileError=ref(''),historyType=ref('monthly'),historyPeriod=ref('all'),historyStatus=ref('all'),openHistoryId=ref('')
const historyKind=computed(()=>historyType.value==='annual'?'ANNUAL_PLAN':'MONTHLY_ACTUAL')
const historyByKind=computed(()=>store.orgHistory.filter(s=>s.kind===historyKind.value))
const historyState=s=>s.displayStatus || s.status
const historyStatusLabel=value=>value==='SUPERSEDED'?'이전 제출본':status(value)
const historyPeriods=computed(()=>[...new Set(historyByKind.value.map(s=>s.period).filter(Boolean))])
const historyStatuses=computed(()=>[...new Set(historyByKind.value.map(historyState).filter(Boolean))])
const historyRows=computed(()=>historyByKind.value.filter(s=>(historyPeriod.value==='all'||s.period===historyPeriod.value)&&(historyStatus.value==='all'||historyState(s)===historyStatus.value)))
watch(historyType,()=>{historyPeriod.value='all';historyStatus.value='all';openHistoryId.value=''})
const historyTypeOptions=[{label:'월간 실적·추정',value:'monthly'},{label:'연간 계획',value:'annual'}]
const historyPeriodOptions=computed(()=>[{label:'전체 연월',value:'all'},...historyPeriods.value.map(value=>({label:value,value}))])
const historyStatusOptions=computed(()=>[{label:'전체 상태',value:'all'},...historyStatuses.value.map(value=>({label:historyStatusLabel(value),value}))])
/** 제출 상태를 PrimeVue 상태 색상으로 표시한다.
 * Args: value 서버 상태. Returns: Tag severity. Raises: 없음. Side Effects: 없음.
 */
function statusSeverity(value){return ['REJECTED','RESUBMIT_REQUESTED'].includes(value)?'danger':value==='APPROVED'?'success':value==='PENDING'?'info':'secondary'}
/** 서버 날짜를 한국 시간으로 표시한다.
 * Args: value ISO 날짜 또는 빈 값. Returns: 날짜 표시 문자열. Raises: 없음.
 * Side Effects: 없음.
 */
function date(value){if(!value)return '—';const d=new Date(value);return Number.isNaN(d.getTime())?value:new Intl.DateTimeFormat('ko-KR',{timeZone:'Asia/Seoul',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}).format(d)}
/** PrimeVue가 선택한 Excel을 검증하고 업로드 전 상태에만 보관한다.
 * Args: event FileUpload select 이벤트. Returns: 없음. Raises: 없음.
 * Side Effects: 파일 선택·오류 상태만 변경하며 HTTP 요청은 보내지 않는다.
 */
function chooseFile(event){
  const selected=submissionFileSelection(event?.target?.files ? {files:Array.from(event.target.files)} : event)
  file.value=selected.file
  fileError.value=selected.error
  if(selected.error){fileInput.value?.clear?.();if(fileInput.value instanceof HTMLInputElement)fileInput.value.value='';fileError.value=selected.error}
}
/** 선택한 제출 파일을 해제한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 메모리 선택·오류만 초기화한다.
 */
function clearFile(){file.value=null;fileError.value=''}
/** 제출 이력의 상세 행을 한 건씩 열거나 닫는다.
 * Args: id 제출본 ID. Returns: 없음. Raises: 없음. Side Effects: 펼친 행 상태만 변경한다.
 */
function toggleHistory(id){openHistoryId.value=openHistoryId.value===id?'':id}
/** 제출 확인 창 닫기 요청을 반영한다.
 * Args: visible Dialog의 다음 표시 여부. Returns: 없음. Raises: 없음.
 * Side Effects: 서버 처리 중이 아닐 때만 확인 초안을 닫는다.
 */
function closePreview(visible){if(!visible&&!store.busy)preview.value=null}
watch(fileError,message=>{if(message)store.notify(message,'error')})
/** 제출 이력 조회 필터를 초기화한다.
 * Args: 없음. Returns: 없음. Raises: 없음.
 * Side Effects: 화면 필터만 초기화한다.
 */
function resetHistory(){historyPeriod.value='all';historyStatus.value='all'}
watch(()=>store.activeRequest,()=>{file.value=null;fileError.value='';reply.value=''})
let generation=0
onBeforeUnmount(()=>generation++)
watch([()=>props.page,()=>store.activeRequest],async()=>{const g=++generation;preview.value=null;diagnostics.value=null;submission.value=null;loading.value=true
try{if(['org-requests','org-history'].includes(props.page))await store.loadInbox();else{const item=store.activeOrgRequest;if(!item)return;request.value={request:{dueAt:item.dueAt}};if(item.submissionId){const s=await store.api('/api/v1/submissions/'+item.submissionId);if(g===generation)submission.value=normalizeSubmission(s)}}}catch(e){store.error=e.message}finally{if(g===generation)loading.value=false}},{immediate:true})
/** 선택한 요청의 제출 또는 소명 화면을 연다.
 * Args: item 요청 DTO. Returns: 없음. Raises: 없음.
 * Side Effects: 활성 요청과 현재 화면을 변경한다.
 */
function open(item){store.activateOrgRequest(item.id);emit('navigate',item.kind==='EXPLANATION'?'org-explanation':'org-upload')}
/** 현재 제출본의 최신 상태와 토큰을 읽는다.
 * Args: 없음. Returns: Promise<void>. Raises: API 오류를 호출부에 전달한다.
 * Side Effects: 제출본 조회와 화면 상태를 갱신한다.
 */
async function reload(){if(submission.value)submission.value=normalizeSubmission(await store.api('/api/v1/submissions/'+submission.value.id))}
/** 검증 시작을 눌렀을 때만 선택한 파일을 업로드하고 파싱 뒤 검증한다.
 * Args: 없음. Returns: Promise<void>. Raises: 오류는 store.run이 표시한다.
 * Side Effects: 파일 POST·파싱 상태 조회·검증 요청을 수행한다.
 */
async function upload(){if(!file.value)return;await store.run(async()=>{const g=generation,body=new FormData();body.set('file',file.value);let s;try{s=await store.api(`/api/v1/submission-requests/${store.activeOrgRequest.requestId}/submissions`,{method:'POST',body})}catch(error){if(['INVALID_FILENAME','ORGANIZATION_MISMATCH'].includes(error.code))error.message=filenameHelp.value;throw error};submission.value=normalizeSubmission(s)
for(let n=0;n<40&&['QUEUED','RUNNING'].includes(s.parsingJob?.status);n++){await new Promise(r=>setTimeout(r,500));if(g!==generation)return;s=await store.api('/api/v1/submissions/'+s.id);if(g!==generation)return;submission.value=normalizeSubmission(s)}
if(s.parsingJob?.status==='SUCCEEDED')await validate();else if(s.parsingJob?.status==='FAILED')diagnostics.value=await store.api(`/api/v1/submissions/${s.id}/diagnostics`)
})}
/** 현재 제출본 상태 토큰으로 파일 검증을 실행한다.
 * Args: 없음. Returns: Promise<void>. Raises: API 오류를 호출부에 전달한다.
 * Side Effects: 검증 POST와 최신 진단 조회를 수행한다.
 */
async function validate(){if(!submission.value)return;await store.api(`/api/v1/submissions/${submission.value.id}/validation`,{method:'POST',body:{expectedStateToken:submission.value.stateToken}});await reload();diagnostics.value=await store.api(`/api/v1/submissions/${submission.value.id}/diagnostics`)}
/** 최신 제출본의 제출 미리보기와 상태 토큰을 받는다.
 * Args: 없음. Returns: Promise<void>. Raises: 오류는 store.run이 표시한다.
 * Side Effects: 조회 후 확인 Dialog를 연다.
 */
async function confirm(){await store.run(async()=>{await reload();preview.value=await store.api(`/api/v1/submissions/${submission.value.id}/submit-preview`)})}
/** 확인한 상태 토큰과 회신 메시지로 제출을 확정한다.
 * Args: 없음. Returns: Promise<void>. Raises: 오류는 store.run이 표시한다.
 * Side Effects: 제출 POST·요청함 갱신 후 이력 화면으로 이동한다.
 */
async function submit(){await store.run(async()=>{await store.api(`/api/v1/submissions/${submission.value.id}/submit`,{method:'POST',body:{replyMessage:reply.value,expectedStateToken:preview.value.stateToken}});preview.value=null;await store.loadInbox();emit('navigate','org-history')})}
/** 서버 제출 양식을 다운로드한다.
 * Args: 없음. Returns: Promise<void>. Raises: 오류는 store.run이 표시한다.
 * Side Effects: 파일 조회 후 사용자 다운로드를 시작한다.
 */
async function template(){await store.run(async()=>saveFile(await store.api('/api/v1/submission-template/file',{blob:true}),'제출양식.xlsx'))}
</script>
<template>
<section class="org-panel org-live">
  <p v-if="loading" class="muted" role="status"><span data-copy-key="organizationText.text001">{{ copy.organizationText.text001 }}</span></p>
  <template v-if="page==='org-requests'">
    <h1>{{ copy.pages['org-requests'] }}</h1>
    <section class="card org-card" aria-labelledby="org-open-title">
      <div class="org-section-heading"><h2 id="org-open-title"><span data-copy-key="organizationText.text002">{{ copy.organizationText.text002 }}</span></h2><span class="muted">{{store.orgRequests.length}}<span data-copy-key="organizationText.text003">{{ copy.organizationText.text003 }}</span></span></div>
      <article v-for="item in store.orgRequests" :key="item.id" class="org-request-card" :class="{'org-resubmit':item.isResubmit}">
        <div class="org-live-request-content"><span class="badge" :class="item.isResubmit?'recheck':item.kind==='EXPLANATION'?'progress':'submitted'">{{item.isResubmit?'재제출 필요':item.kind==='EXPLANATION'?'소명 요청':'제출 요청'}}</span><h3>{{item.title}}</h3><div v-if="item.reviewComment" class="org-rerequest-comment"><strong><span data-copy-key="organizationText.text004">{{ copy.organizationText.text004 }}</span></strong><p>{{item.reviewComment}}</p></div><p v-if="item.message" class="org-request-message">{{item.message}}</p><p class="muted"><span data-copy-key="organizationText.text005">{{ copy.organizationText.text005 }}</span> {{date(item.dueAt)}}</p></div>
        <button class="btn primary" :disabled="store.busy" @click="open(item)">{{item.isResubmit?'재제출 시작':item.kind==='EXPLANATION'?'소명 시작':'제출 시작'}}</button>
      </article>
      <p v-if="!store.orgRequests.length" class="muted org-empty"><span data-copy-key="organizationText.text006">{{ copy.organizationText.text006 }}</span></p>
    </section>
  </template>
  <template v-else-if="page==='org-history'">
    <h1>{{ copy.pages['org-history'] }}</h1>
    <section class="card org-card">
      <div class="org-section-heading"><h2><span data-copy-key="organizationText.text007">{{ copy.organizationText.text007 }}</span></h2><span class="muted"><span data-copy-key="organizationText.text008">{{ copy.organizationText.text008 }}</span> {{historyRows.length}}<span data-copy-key="organizationText.text003">{{ copy.organizationText.text003 }}</span></span></div>
      <div class="org-history-tabs" role="group" aria-label="제출 구분"><button :aria-pressed="historyType==='monthly'" :class="{active:historyType==='monthly'}" @click="historyType='monthly'"><span data-copy-key="organizationText.text009">{{ copy.organizationText.text009 }}</span></button><button :aria-pressed="historyType==='annual'" :class="{active:historyType==='annual'}" @click="historyType='annual'"><span data-copy-key="organizationText.text010">{{ copy.organizationText.text010 }}</span></button></div>
      <div class="org-history-filters"><label><span data-copy-key="organizationText.text011">{{ copy.organizationText.text011 }}</span><select v-model="historyPeriod"><option value="all">전체 연월</option><option v-for="period in historyPeriods" :key="period" :value="period">{{period}}</option></select></label><label><span data-copy-key="organizationText.text012">{{ copy.organizationText.text012 }}</span><select v-model="historyStatus"><option value="all">전체 상태</option><option v-for="value in historyStatuses" :key="value" :value="value">{{historyStatusLabel(value)}}</option></select></label><button class="org-link-button" @click="resetHistory"><span data-copy-key="organizationText.text013">{{ copy.organizationText.text013 }}</span></button></div>
      <div class="org-table-scroll" role="region" aria-label="제출 이력" tabindex="0"><table class="org-history-table"><thead><tr><th><span data-copy-key="organizationText.text014">{{ copy.organizationText.text014 }}</span></th><th><span data-copy-key="organizationText.text015">{{ copy.organizationText.text015 }}</span></th><th><span data-copy-key="organizationText.text016">{{ copy.organizationText.text016 }}</span></th><th><span data-copy-key="organizationText.text017">{{ copy.organizationText.text017 }}</span></th><th><span data-copy-key="organizationText.text018">{{ copy.organizationText.text018 }}</span></th></tr></thead><tbody><template v-for="s in historyRows" :key="s.id"><tr><td>{{s.requestTitle}}</td><td>{{s.label}}</td><td>{{date(s.submittedAt)}}</td><td><span class="badge" :class="statusClass(historyState(s))">{{historyStatusLabel(historyState(s))}}</span></td><td><button class="org-link-button" :aria-expanded="openHistoryId===s.id" @click="openHistoryId=openHistoryId===s.id?'':s.id"><span data-copy-key="organizationText.text019">{{ copy.organizationText.text019 }}</span></button></td></tr><tr v-if="openHistoryId===s.id"><td colspan="5" class="org-history-detail"><strong>{{s.requestTitle}}</strong><p v-if="s.reviewComment" class="org-rerequest-comment"><span data-copy-key="organizationText.text020">{{ copy.organizationText.text020 }}</span> {{s.reviewComment}}</p><p class="muted">{{s.label}} · {{date(s.submittedAt)}} · {{historyStatusLabel(historyState(s))}}</p><button class="btn" :disabled="store.busy" @click="store.run(()=>store.downloadSubmission(s.id,s.file?.name))">{{s.file?.name || '원본'}} ↓</button></td></tr></template></tbody></table></div>
      <p v-if="!historyRows.length" class="muted org-empty"><span data-copy-key="organizationText.text021">{{ copy.organizationText.text021 }}</span></p>
    </section>
  </template>
  <template v-else>
    <h1>{{store.activeOrgRequest?.title || '제출 요청을 선택해 주세요'}}</h1>
    <section v-if="store.activeOrgRequest" class="card org-card">
      <template v-if="page==='org-upload'">
        <h2>{{store.activeOrgRequest?.isResubmit?'엑셀 파일 재제출':'엑셀 파일 업로드'}}</h2><aside v-if="store.activeOrgRequest?.reviewComment" class="org-rerequest-comment"><strong><span data-copy-key="organizationText.text004">{{ copy.organizationText.text004 }}</span></strong><p>{{store.activeOrgRequest.reviewComment}}</p><small><span data-copy-key="organizationText.text022">{{ copy.organizationText.text022 }}</span></small></aside>
        <p v-if="request?.request?.message" class="org-baseline muted org-live-message">{{request.request.message}}</p><p class="muted"><span data-copy-key="organizationText.text005">{{ copy.organizationText.text005 }}</span> {{date(request?.request?.dueAt)}}</p>
        <div class="org-template"><div><strong><span data-copy-key="organizationText.text023">{{ copy.organizationText.text023 }}</span></strong><p class="muted"><span data-copy-key="organizationText.text024">{{ copy.organizationText.text024 }}</span></p></div><button class="btn" :disabled="store.busy" @click="template"><span data-copy-key="organizationText.text025">{{ copy.organizationText.text025 }}</span></button></div>
        <p class="notice">{{filenameHelp}}</p>
        <div class="org-dropzone" :class="{dragging:dragOver}" @dragover.prevent="dragOver=true" @dragleave.prevent="dragOver=false" @drop.prevent="!store.busy && dropFile($event)">
          <strong>{{file?.name || '엑셀 파일을 여기에 끌어다 놓으세요'}}</strong><span class="muted"><span data-copy-key="organizationText.text026">{{ copy.organizationText.text026 }}</span></span>
          <input ref="fileInput" class="org-file-input" type="file" accept=".xlsx,.xlsb" aria-label="제출할 엑셀 파일" :disabled="store.busy" @change="chooseFile" />
          <div class="actions"><button class="btn" :disabled="store.busy" @click="fileInput?.click()">{{file?'파일 다시 선택':'파일 선택'}}</button><button v-if="file" class="btn danger" :disabled="store.busy" @click="file=null;fileError=''"><span data-copy-key="organizationText.text027">{{ copy.organizationText.text027 }}</span></button></div>
        </div>
        <p v-if="fileError" class="org-inline-message" role="alert">{{fileError}}</p>
        <p class="org-upload-notice muted"><span data-copy-key="organizationText.text028">{{ copy.organizationText.text028 }}</span></p>
        <div class="org-upload-actions"><button class="btn" @click="emit('navigate','org-requests')"><span data-copy-key="organizationText.text029">{{ copy.organizationText.text029 }}</span></button><button class="btn primary" :disabled="!file || store.busy" @click="upload"><span data-copy-key="organizationText.text030">{{ copy.organizationText.text030 }}</span></button></div>
      </template>
      <template v-else><h2><span data-copy-key="organizationText.text031">{{ copy.organizationText.text031 }}</span></h2><p v-if="request?.request?.message" class="org-baseline muted org-live-message">{{request.request.message}}</p><p class="muted"><span data-copy-key="organizationText.text005">{{ copy.organizationText.text005 }}</span> {{date(request?.request?.dueAt)}} <span data-copy-key="organizationText.text032">{{ copy.organizationText.text032 }}</span></p></template>
      <template v-if="submission">
        <div class="org-live-validation"><div><strong>{{submission.versionLabel}}</strong><span class="badge" :class="statusClass(submission.status)">{{status(submission.status)}}</span><p class="muted"><span data-copy-key="organizationText.text033">{{ copy.organizationText.text033 }}</span> {{ {QUEUED:'대기',RUNNING:'진행 중',SUCCEEDED:'완료',FAILED:'실패'}[submission.parsing?.status] || submission.parsing?.status }}</p></div><div class="actions"><button class="btn" :disabled="store.busy" @click="store.run(reload)"><span data-copy-key="organizationText.text034">{{ copy.organizationText.text034 }}</span></button><button v-if="submission.status==='DRAFT'" class="btn" :disabled="store.busy || submission.parsing?.status!=='SUCCEEDED'" @click="store.run(validate)"><span data-copy-key="organizationText.text035">{{ copy.organizationText.text035 }}</span></button></div></div>
        <p v-if="['QUEUED','RUNNING'].includes(submission.parsing?.status)" class="notice"><span data-copy-key="organizationText.text036">{{ copy.organizationText.text036 }}</span></p>
        <details v-if="diagnostics" class="org-live-diagnostics" open><summary><span data-copy-key="organizationText.text037">{{ copy.organizationText.text037 }}</span> {{diagnostics.errorCount}}<span data-copy-key="organizationText.text003">{{ copy.organizationText.text003 }}</span></summary><div class="org-table-scroll"><table class="org-history-table"><thead><tr><th><span data-copy-key="organizationText.text038">{{ copy.organizationText.text038 }}</span></th><th><span data-copy-key="organizationText.text039">{{ copy.organizationText.text039 }}</span></th><th><span data-copy-key="organizationText.text040">{{ copy.organizationText.text040 }}</span></th><th><span data-copy-key="organizationText.text041">{{ copy.organizationText.text041 }}</span></th></tr></thead><tbody><tr v-for="(item,i) in diagnostics.items" :key="i"><td>{{item.sourceCell || '위치 확인 필요'}}</td><td>{{item.item || '—'}}</td><td>{{item.rule}}</td><td>{{item.guide}}</td></tr></tbody></table></div></details>
        <CurrentSubmissionReviews :key="submission.id" :submission="submission" @changed="reload" />
        <div v-if="submission.status==='DRAFT'" class="org-explanation-footer"><p v-if="!submission.validation?.canSubmit" class="org-restriction"><span data-copy-key="organizationText.text042">{{ copy.organizationText.text042 }}</span></p><span v-else class="muted"><span data-copy-key="organizationText.text043">{{ copy.organizationText.text043 }}</span></span><div><button class="btn" @click="emit('navigate','org-requests')"><span data-copy-key="organizationText.text029">{{ copy.organizationText.text029 }}</span></button><button class="btn primary" :disabled="store.busy || !submission.validation?.canSubmit" @click="confirm"><span data-copy-key="organizationText.text044">{{ copy.organizationText.text044 }}</span></button></div></div>
        <section v-if="preview" class="card org-card"><h2><span data-copy-key="organizationText.text044">{{ copy.organizationText.text044 }}</span></h2><dl class="org-review-summary"><dt><span data-copy-key="organizationText.text045">{{ copy.organizationText.text045 }}</span></dt><dd>{{preview.file.name}}</dd><dt><span data-copy-key="organizationText.text046">{{ copy.organizationText.text046 }}</span></dt><dd>{{preview.file.savedAs}}</dd><dt><span data-copy-key="organizationText.text015">{{ copy.organizationText.text015 }}</span></dt><dd>{{preview.expectedLabel}}</dd><dt><span data-copy-key="organizationText.text047">{{ copy.organizationText.text047 }}</span></dt><dd>{{preview.items.length}}<span data-copy-key="organizationText.text048">{{ copy.organizationText.text048 }}</span></dd></dl><label class="field"><span data-copy-key="organizationText.text049">{{ copy.organizationText.text049 }}</span><textarea v-model="reply" /></label><div class="org-upload-actions"><button class="btn" @click="preview=null"><span data-copy-key="organizationText.text050">{{ copy.organizationText.text050 }}</span></button><button class="btn primary" :disabled="store.busy" @click="submit"><span data-copy-key="organizationText.text051">{{ copy.organizationText.text051 }}</span></button></div></section>
      </template>
      <p v-else-if="page==='org-explanation' && !loading" class="muted org-empty"><span data-copy-key="organizationText.text052">{{ copy.organizationText.text052 }}</span></p>
    </section>
  </template>
</section>
</template>
