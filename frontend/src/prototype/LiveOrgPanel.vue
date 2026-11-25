<script setup>
import copy from './ui-copy.json'
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
  const selected=submissionFileSelection(event)
  file.value=selected.file
  fileError.value=selected.error
  if(selected.error){fileInput.value?.clear();fileError.value=selected.error}
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
    <PMessage v-if="loading" severity="info" :closable="false">불러오는 중…</PMessage>
    <template v-if="page==='org-requests'">
      <h1>{{ copy.pages['org-requests'] }}</h1>
      <section class="card org-card" aria-labelledby="org-open-title">
        <div class="org-section-heading"><h2 id="org-open-title">열린 요청</h2><span class="muted">{{store.orgRequests.length}}건</span></div>
        <article v-for="item in store.orgRequests" :key="item.id" class="org-request-card" :class="{'org-resubmit':item.isResubmit}">
          <div class="org-live-request-content">
            <PTag :severity="item.isResubmit?'danger':item.kind==='EXPLANATION'?'warn':'info'" :value="item.isResubmit?'재제출 필요':item.kind==='EXPLANATION'?'소명 요청':'제출 요청'" />
            <h3>{{item.title}}</h3>
            <PMessage v-if="item.reviewComment" severity="warn" :closable="false" class="org-live-message"><strong>경영기획 재요청 코멘트</strong><p>{{item.reviewComment}}</p></PMessage>
            <p class="muted">회신 기한 {{date(item.dueAt)}}</p>
          </div>
          <PButton :label="item.isResubmit?'재제출 시작':item.kind==='EXPLANATION'?'소명 시작':'제출 시작'" :disabled="store.busy" @click="open(item)" />
        </article>
        <PMessage v-if="!store.orgRequests.length" severity="secondary" :closable="false">열린 요청이 없습니다.</PMessage>
      </section>
    </template>
    <template v-else-if="page==='org-history'">
      <h1>{{ copy.pages['org-history'] }}</h1>
      <section class="card org-card">
        <div class="org-section-heading"><h2>제출본</h2><span class="muted">조회 {{historyRows.length}}건</span></div>
        <PSelectButton v-model="historyType" :options="historyTypeOptions" optionLabel="label" optionValue="value" :allowEmpty="false" aria-label="제출 구분" />
        <div class="org-history-filters">
          <label for="org-history-period">대상 연월<PSelect inputId="org-history-period" v-model="historyPeriod" :options="historyPeriodOptions" optionLabel="label" optionValue="value" /></label>
          <label for="org-history-status">처리 상태<PSelect inputId="org-history-status" v-model="historyStatus" :options="historyStatusOptions" optionLabel="label" optionValue="value" /></label>
          <PButton label="초기화" severity="secondary" text @click="resetHistory" />
        </div>
        <PDataTable :value="historyRows" dataKey="id" :expandedRows="openHistoryId?{[openHistoryId]:true}:{}" stripedRows scrollable :tableStyle="{minWidth:'42rem'}" :pt="{table:{'aria-label':'제출 이력'}}">
          <PColumn field="requestTitle" header="제출 요청" />
          <PColumn field="label" header="제출 버전" />
          <PColumn header="제출일"><template #body="{data}">{{date(data.submittedAt)}}</template></PColumn>
          <PColumn header="상태"><template #body="{data}"><PTag :severity="statusSeverity(historyState(data))" :value="historyStatusLabel(historyState(data))" /></template></PColumn>
          <PColumn header="상세"><template #body="{data}"><PButton :label="openHistoryId===data.id?'닫기':'보기'" text :aria-expanded="openHistoryId===data.id" @click="toggleHistory(data.id)" /></template></PColumn>
          <template #expansion="{data}"><CurrentSubmissionHistory :submission-id="data.id" /></template>
          <template #empty>선택한 조건의 제출 이력이 없습니다.</template>
        </PDataTable>
      </section>
    </template>
    <template v-else>
      <h1>{{store.activeOrgRequest?.title || '제출 요청을 선택해 주세요'}}</h1>
      <section v-if="store.activeOrgRequest" class="card org-card">
        <template v-if="page==='org-upload'">
          <h2>{{store.activeOrgRequest?.isResubmit?'엑셀 파일 재제출':'엑셀 파일 업로드'}}</h2>
          <PMessage v-if="store.activeOrgRequest?.reviewComment" severity="warn" :closable="false" class="org-live-message"><strong>경영기획 재요청 코멘트</strong><p>{{store.activeOrgRequest.reviewComment}}</p><small>코멘트를 확인하고 수정한 파일을 다시 제출해 주세요.</small></PMessage>
          <p v-if="request?.request?.message" class="org-baseline muted org-live-message">{{request.request.message}}</p>
          <p class="muted">회신 기한 {{date(request?.request?.dueAt)}}</p>
          <div class="org-template"><div><strong>제출 양식</strong><p class="muted">요청에 맞는 엑셀 양식으로 제출해 주세요.</p></div><PButton label="양식 다운로드" severity="secondary" outlined :disabled="store.busy" @click="template" /></div>
          <PMessage severity="info" :closable="false">{{filenameHelp}}</PMessage>
          <PFileUpload :key="store.activeRequest" ref="fileInput" class="org-prime-upload" name="file" customUpload :auto="false" :multiple="false" accept=".xlsx,.xlsb" :maxFileSize="20*1024*1024" :fileLimit="1" :disabled="store.busy" :showUploadButton="false" :showCancelButton="false" invalidFileTypeMessage="{0}: .xlsx 또는 .xlsb 파일을 선택해 주세요." invalidFileSizeMessage="{0}: 20MB 이하 파일을 선택해 주세요." invalidFileLimitMessage="한 번에 파일 1개만 선택해 주세요." @select="chooseFile" @clear="clearFile">
            <template #header="{chooseCallback,clearCallback}">
              <PButton label="파일 선택" icon="pi pi-folder-open" severity="secondary" outlined :disabled="store.busy || Boolean(file)" @click="chooseCallback" />
              <PButton v-if="file" label="선택한 파일 제거" icon="pi pi-times" severity="danger" text :disabled="store.busy" @click="clearCallback" />
            </template>
            <template #content="{messages}">
              <PMessage v-for="message in messages" :key="message" severity="error" :closable="false">{{message}}</PMessage>
              <div class="org-upload-prompt"><strong>{{file?.name || '엑셀 파일을 여기에 끌어다 놓으세요'}}</strong><span class="muted">.xlsx / .xlsb · 요청당 파일 1개 · 최대 20MB</span></div>
            </template>
          </PFileUpload>
          <PMessage v-if="fileError" severity="error" :closable="false">{{fileError}}</PMessage>
          <p class="org-upload-notice muted">검증 시작 시 파일을 서버에 업로드하고 형식·조직 정보를 확인합니다.</p>
          <div class="org-upload-actions"><PButton label="요청함으로" severity="secondary" outlined @click="emit('navigate','org-requests')" /><PButton label="검증 시작" :disabled="!file || store.busy" :loading="store.busy" @click="upload" /></div>
        </template>
        <template v-else><h2>변경 확인·소명</h2><p v-if="request?.request?.message" class="org-baseline muted org-live-message">{{request.request.message}}</p><p class="muted">회신 기한 {{date(request?.request?.dueAt)}} · 기존 파일과 숫자는 변경되지 않습니다.</p></template>
        <template v-if="submission">
          <div class="org-live-validation">
            <div><strong>{{submission.versionLabel}}</strong><PTag :severity="statusSeverity(submission.status)" :value="status(submission.status)" /><p class="muted">파싱 {{ {QUEUED:'대기',RUNNING:'진행 중',SUCCEEDED:'완료',FAILED:'실패'}[submission.parsing?.status] || submission.parsing?.status }}</p></div>
            <div class="actions"><PButton label="검증 상태 새로고침" severity="secondary" outlined :disabled="store.busy" @click="store.run(reload)" /><PButton v-if="submission.status==='DRAFT'" label="검증 다시 실행" severity="secondary" outlined :disabled="store.busy || submission.parsing?.status!=='SUCCEEDED'" @click="store.run(validate)" /></div>
          </div>
          <PMessage v-if="['QUEUED','RUNNING'].includes(submission.parsing?.status)" severity="info" :closable="false">파싱 중입니다. 검증 상태 새로고침으로 진행 상태를 확인해 주세요.</PMessage>
          <PPanel v-if="diagnostics" :header="`파일 검증 결과 · 수정 필요 ${diagnostics.errorCount}건`" toggleable>
            <PDataTable :value="diagnostics.items" stripedRows scrollable :tableStyle="{minWidth:'38rem'}" :pt="{table:{'aria-label':'파일 검증 결과'}}">
              <PColumn header="원본 위치"><template #body="{data}">{{data.sourceCell || '위치 확인 필요'}}</template></PColumn>
              <PColumn header="항목"><template #body="{data}">{{data.item || '—'}}</template></PColumn>
              <PColumn field="rule" header="검증 결과" /><PColumn field="guide" header="수정 안내" />
              <template #empty>표시할 검증 항목이 없습니다.</template>
            </PDataTable>
          </PPanel>
          <CurrentSubmissionReviews :key="submission.id" :submission="submission" @changed="reload" />
          <div v-if="submission.status==='DRAFT'" class="org-explanation-footer">
            <PMessage v-if="!submission.validation?.canSubmit" severity="warn" :closable="false">파일 검증과 필수 변경 확인·소명 작성을 완료해야 제출할 수 있습니다.</PMessage><span v-else class="muted">제출할 내용을 확인해 주세요.</span>
            <div><PButton label="요청함으로" severity="secondary" outlined @click="emit('navigate','org-requests')" /><PButton label="제출 내용 확인" :disabled="store.busy || !submission.validation?.canSubmit" @click="confirm" /></div>
          </div>
          <PDialog :visible="Boolean(preview)" header="제출 내용 확인" modal blockScroll :closable="!store.busy" :closeOnEscape="!store.busy" :style="{width:'42rem',maxWidth:'94vw'}" @update:visible="closePreview">
            <template v-if="preview"><dl class="org-review-summary"><dt>파일</dt><dd>{{preview.file.name}}</dd><dt>저장 파일명</dt><dd>{{preview.file.savedAs}}</dd><dt>제출 버전</dt><dd>{{preview.expectedLabel}}</dd><dt>비교 기준</dt><dd>{{baselineLabel(preview.baseline)}}</dd><dt>변경 항목</dt><dd>{{preview.items.length}}개</dd></dl><label for="org-submission-reply" class="org-dialog-field">회신 메시지<PTextarea id="org-submission-reply" v-model="reply" rows="4" autoResize :disabled="store.busy" /></label></template>
            <template #footer><PButton label="작성으로 돌아가기" severity="secondary" outlined :disabled="store.busy" @click="closePreview(false)" /><PButton label="제출하기" :disabled="store.busy" :loading="store.busy" @click="submit" /></template>
          </PDialog>
        </template>
        <PMessage v-else-if="page==='org-explanation' && !loading" severity="secondary" :closable="false">요청함에서 현재 제출본이 연결된 소명 요청을 다시 선택해 주세요.</PMessage>
      </section>
    </template>
  </section>
</template>
