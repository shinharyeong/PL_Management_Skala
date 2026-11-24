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
import './org.css'
import './org-live.css'
const props=defineProps({page:String}),emit=defineEmits(['navigate']),store=usePrototypeStore()
const filenameHelp=computed(()=>uploadGuidance(store.actor,store.activeOrgRequest))
const file=ref(null),submission=ref(null),diagnostics=ref(null),preview=ref(null),reply=ref(''),request=ref(null),loading=ref(false)
const fileInput=ref(null),dragOver=ref(false),fileError=ref(''),historyType=ref('monthly'),historyPeriod=ref('all'),historyStatus=ref('all'),openHistoryId=ref('')
const historyKind=computed(()=>historyType.value==='annual'?'ANNUAL_PLAN':'MONTHLY_ACTUAL')
const historyByKind=computed(()=>store.orgHistory.filter(s=>s.kind===historyKind.value))
const historyState=s=>s.displayStatus || s.status
const historyStatusLabel=value=>value==='SUPERSEDED'?'이전 제출본':status(value)
const historyPeriods=computed(()=>[...new Set(historyByKind.value.map(s=>s.period).filter(Boolean))])
const historyStatuses=computed(()=>[...new Set(historyByKind.value.map(historyState).filter(Boolean))])
const historyRows=computed(()=>historyByKind.value.filter(s=>(historyPeriod.value==='all'||s.period===historyPeriod.value)&&(historyStatus.value==='all'||historyState(s)===historyStatus.value)))
watch(historyType,()=>{historyPeriod.value='all';historyStatus.value='all';openHistoryId.value=''})
const statusClass=value=>['REJECTED','RESUBMIT_REQUESTED'].includes(value)?'recheck':value==='APPROVED'?'approved':value==='PENDING'?'progress':'waiting'
function date(value){if(!value)return '—';const d=new Date(value);return Number.isNaN(d.getTime())?value:new Intl.DateTimeFormat('ko-KR',{timeZone:'Asia/Seoul',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}).format(d)}
function selectFiles(files){fileError.value='';if(!files?.length)return;if(files.length!==1||!/\.(xlsx|xlsb)$/i.test(files[0].name)||!files[0].size||files[0].size>20*1024*1024){fileError.value='.xlsx 또는 .xlsb 형식의 비어 있지 않은 20MB 이하 파일 1개를 선택해 주세요.';return}file.value=files[0]}
watch(fileError,message=>{if(message)store.notify(message,'error')})
function chooseFile(event){selectFiles(event.target.files);event.target.value=''}
function dropFile(event){dragOver.value=false;selectFiles(event.dataTransfer.files)}
function resetHistory(){historyPeriod.value='all';historyStatus.value='all'}
watch(()=>store.activeRequest,()=>{file.value=null;fileError.value='';reply.value=''})
let generation=0
onBeforeUnmount(()=>generation++)
watch([()=>props.page,()=>store.activeRequest],async()=>{const g=++generation;preview.value=null;diagnostics.value=null;submission.value=null;loading.value=true
try{if(['org-requests','org-history'].includes(props.page))await store.loadInbox();else{const item=store.activeOrgRequest;if(!item)return;request.value={request:{dueAt:item.dueAt}};if(item.submissionId){const s=await store.api('/api/v1/submissions/'+item.submissionId);if(g===generation)submission.value=normalizeSubmission(s)}}}catch(e){store.error=e.message}finally{if(g===generation)loading.value=false}},{immediate:true})
function open(item){store.activateOrgRequest(item.id);emit('navigate',item.kind==='EXPLANATION'?'org-explanation':'org-upload')}
async function reload(){if(submission.value)submission.value=normalizeSubmission(await store.api('/api/v1/submissions/'+submission.value.id))}
async function upload(){if(!file.value)return;await store.run(async()=>{const g=generation,body=new FormData();body.set('file',file.value);let s;try{s=await store.api(`/api/v1/submission-requests/${store.activeOrgRequest.requestId}/submissions`,{method:'POST',body})}catch(error){if(['INVALID_FILENAME','ORGANIZATION_MISMATCH'].includes(error.code))error.message=filenameHelp.value;throw error};submission.value=normalizeSubmission(s)
for(let n=0;n<40&&['QUEUED','RUNNING'].includes(s.parsingJob?.status);n++){await new Promise(r=>setTimeout(r,500));if(g!==generation)return;s=await store.api('/api/v1/submissions/'+s.id);if(g!==generation)return;submission.value=normalizeSubmission(s)}
if(s.parsingJob?.status==='SUCCEEDED')await validate();else if(s.parsingJob?.status==='FAILED')diagnostics.value=await store.api(`/api/v1/submissions/${s.id}/diagnostics`)
})}
async function validate(){if(!submission.value)return;await store.api(`/api/v1/submissions/${submission.value.id}/validation`,{method:'POST',body:{expectedStateToken:submission.value.stateToken}});await reload();diagnostics.value=await store.api(`/api/v1/submissions/${submission.value.id}/diagnostics`)}
async function confirm(){await store.run(async()=>{await reload();preview.value=await store.api(`/api/v1/submissions/${submission.value.id}/submit-preview`)})}
async function submit(){await store.run(async()=>{await store.api(`/api/v1/submissions/${submission.value.id}/submit`,{method:'POST',body:{replyMessage:reply.value,expectedStateToken:preview.value.stateToken}});preview.value=null;await store.loadInbox();emit('navigate','org-history')})}
async function template(){await store.run(async()=>saveFile(await store.api('/api/v1/submission-template/file',{blob:true}),'제출양식.xlsx'))}
</script>
<template>
<section class="org-panel org-live">
  <p v-if="loading" class="muted" role="status">불러오는 중…</p>
  <template v-if="page==='org-requests'">
    <h1>{{ copy.pages['org-requests'] }}</h1>
    <section class="card org-card" aria-labelledby="org-open-title">
      <div class="org-section-heading"><h2 id="org-open-title">열린 요청</h2><span class="muted">{{store.orgRequests.length}}건</span></div>
      <article v-for="item in store.orgRequests" :key="item.id" class="org-request-card" :class="{'org-resubmit':item.isResubmit}">
        <div class="org-live-request-content"><span class="badge" :class="item.isResubmit?'recheck':item.kind==='EXPLANATION'?'progress':'submitted'">{{item.isResubmit?'재제출 필요':item.kind==='EXPLANATION'?'소명 요청':'제출 요청'}}</span><h3>{{item.title}}</h3><div v-if="item.reviewComment" class="org-rerequest-comment"><strong>경영기획 재요청 코멘트</strong><p>{{item.reviewComment}}</p></div><p class="muted">회신 기한 {{date(item.dueAt)}}</p></div>
        <button class="btn primary" :disabled="store.busy" @click="open(item)">{{item.isResubmit?'재제출 시작':item.kind==='EXPLANATION'?'소명 시작':'제출 시작'}}</button>
      </article>
      <p v-if="!store.orgRequests.length" class="muted org-empty">열린 요청이 없습니다.</p>
    </section>
  </template>
  <template v-else-if="page==='org-history'">
    <h1>{{ copy.pages['org-history'] }}</h1>
    <section class="card org-card">
      <div class="org-section-heading"><h2>제출본</h2><span class="muted">조회 {{historyRows.length}}건</span></div>
      <div class="org-history-tabs" role="group" aria-label="제출 구분"><button :aria-pressed="historyType==='monthly'" :class="{active:historyType==='monthly'}" @click="historyType='monthly'">월간 실적·추정</button><button :aria-pressed="historyType==='annual'" :class="{active:historyType==='annual'}" @click="historyType='annual'">연간 계획</button></div>
      <div class="org-history-filters"><label>대상 연월<select v-model="historyPeriod"><option value="all">전체 연월</option><option v-for="period in historyPeriods" :key="period" :value="period">{{period}}</option></select></label><label>처리 상태<select v-model="historyStatus"><option value="all">전체 상태</option><option v-for="value in historyStatuses" :key="value" :value="value">{{historyStatusLabel(value)}}</option></select></label><button class="org-link-button" @click="resetHistory">초기화</button></div>
      <div class="org-table-scroll" role="region" aria-label="제출 이력" tabindex="0"><table class="org-history-table"><thead><tr><th>제출 요청</th><th>제출 버전</th><th>제출일</th><th>상태</th><th>상세</th></tr></thead><tbody><template v-for="s in historyRows" :key="s.id"><tr><td>{{s.requestTitle}}</td><td>{{s.label}}</td><td>{{date(s.submittedAt)}}</td><td><span class="badge" :class="statusClass(historyState(s))">{{historyStatusLabel(historyState(s))}}</span></td><td><button class="org-link-button" :aria-expanded="openHistoryId===s.id" @click="openHistoryId=openHistoryId===s.id?'':s.id">보기</button></td></tr><tr v-if="openHistoryId===s.id"><td colspan="5" class="org-history-detail"><CurrentSubmissionHistory :submission-id="s.id" /></td></tr></template></tbody></table></div>
      <p v-if="!historyRows.length" class="muted org-empty">선택한 조건의 제출 이력이 없습니다.</p>
    </section>
  </template>
  <template v-else>
    <h1>{{store.activeOrgRequest?.title || '제출 요청을 선택해 주세요'}}</h1>
    <section v-if="store.activeOrgRequest" class="card org-card">
      <template v-if="page==='org-upload'">
        <h2>{{store.activeOrgRequest?.isResubmit?'엑셀 파일 재제출':'엑셀 파일 업로드'}}</h2><aside v-if="store.activeOrgRequest?.reviewComment" class="org-rerequest-comment"><strong>경영기획 재요청 코멘트</strong><p>{{store.activeOrgRequest.reviewComment}}</p><small>코멘트를 확인하고 수정한 파일을 다시 제출해 주세요.</small></aside>
        <p v-if="request?.request?.message" class="org-baseline muted org-live-message">{{request.request.message}}</p><p class="muted">회신 기한 {{date(request?.request?.dueAt)}}</p>
        <div class="org-template"><div><strong>제출 양식</strong><p class="muted">요청에 맞는 엑셀 양식으로 제출해 주세요.</p></div><button class="btn" :disabled="store.busy" @click="template">양식 다운로드</button></div>
        <p class="notice">{{filenameHelp}}</p>
        <div class="org-dropzone" :class="{dragging:dragOver}" @dragover.prevent="dragOver=true" @dragleave.prevent="dragOver=false" @drop.prevent="!store.busy && dropFile($event)">
          <strong>{{file?.name || '엑셀 파일을 여기에 끌어다 놓으세요'}}</strong><span class="muted">.xlsx / .xlsb · 요청당 파일 1개 · 최대 20MB</span>
          <input ref="fileInput" class="org-file-input" type="file" accept=".xlsx,.xlsb" aria-label="제출할 엑셀 파일" :disabled="store.busy" @change="chooseFile" />
          <div class="actions"><button class="btn" :disabled="store.busy" @click="fileInput?.click()">{{file?'파일 다시 선택':'파일 선택'}}</button><button v-if="file" class="btn danger" :disabled="store.busy" @click="file=null;fileError=''">선택한 파일 제거</button></div>
        </div>
        <p v-if="fileError" class="org-inline-message" role="alert">{{fileError}}</p>
        <p class="org-upload-notice muted">검증 시작 시 파일을 서버에 업로드하고 형식·조직 정보를 확인합니다.</p>
        <div class="org-upload-actions"><button class="btn" @click="emit('navigate','org-requests')">요청함으로</button><button class="btn primary" :disabled="!file || store.busy" @click="upload">검증 시작</button></div>
      </template>
      <template v-else><h2>변경 확인·소명</h2><p v-if="request?.request?.message" class="org-baseline muted org-live-message">{{request.request.message}}</p><p class="muted">회신 기한 {{date(request?.request?.dueAt)}} · 기존 파일과 숫자는 변경되지 않습니다.</p></template>
      <template v-if="submission">
        <div class="org-live-validation"><div><strong>{{submission.versionLabel}}</strong><span class="badge" :class="statusClass(submission.status)">{{status(submission.status)}}</span><p class="muted">파싱 {{ {QUEUED:'대기',RUNNING:'진행 중',SUCCEEDED:'완료',FAILED:'실패'}[submission.parsing?.status] || submission.parsing?.status }}</p></div><div class="actions"><button class="btn" :disabled="store.busy" @click="store.run(reload)">검증 상태 새로고침</button><button v-if="submission.status==='DRAFT'" class="btn" :disabled="store.busy || submission.parsing?.status!=='SUCCEEDED'" @click="store.run(validate)">검증 다시 실행</button></div></div>
        <p v-if="['QUEUED','RUNNING'].includes(submission.parsing?.status)" class="notice">파싱 중입니다. 검증 상태 새로고침으로 진행 상태를 확인해 주세요.</p>
        <details v-if="diagnostics" class="org-live-diagnostics" open><summary>파일 검증 결과 · 수정 필요 {{diagnostics.errorCount}}건</summary><div class="org-table-scroll"><table class="org-history-table"><thead><tr><th>원본 위치</th><th>항목</th><th>검증 결과</th><th>수정 안내</th></tr></thead><tbody><tr v-for="(item,i) in diagnostics.items" :key="i"><td>{{item.sourceCell || '위치 확인 필요'}}</td><td>{{item.item || '—'}}</td><td>{{item.rule}}</td><td>{{item.guide}}</td></tr></tbody></table></div></details>
        <CurrentSubmissionReviews :key="submission.id" :submission="submission" @changed="reload" />
        <div v-if="submission.status==='DRAFT'" class="org-explanation-footer"><p v-if="!submission.validation?.canSubmit" class="org-restriction">파일 검증과 필수 변경 확인·소명 작성을 완료해야 제출할 수 있습니다.</p><span v-else class="muted">제출할 내용을 확인해 주세요.</span><div><button class="btn" @click="emit('navigate','org-requests')">요청함으로</button><button class="btn primary" :disabled="store.busy || !submission.validation?.canSubmit" @click="confirm">제출 내용 확인</button></div></div>
        <section v-if="preview" class="card org-card"><h2>제출 내용 확인</h2><dl class="org-review-summary"><dt>파일</dt><dd>{{preview.file.name}}</dd><dt>저장 파일명</dt><dd>{{preview.file.savedAs}}</dd><dt>제출 버전</dt><dd>{{preview.expectedLabel}}</dd><dt>비교 기준</dt><dd>{{baselineLabel(preview.baseline)}}</dd><dt>변경 항목</dt><dd>{{preview.items.length}}개</dd></dl><label class="field">회신 메시지<textarea v-model="reply" /></label><div class="org-upload-actions"><button class="btn" @click="preview=null">작성으로 돌아가기</button><button class="btn primary" :disabled="store.busy" @click="submit">제출하기</button></div></section>
      </template>
      <p v-else-if="page==='org-explanation' && !loading" class="muted org-empty">요청함에서 현재 제출본이 연결된 소명 요청을 다시 선택해 주세요.</p>
    </section>
  </template>
</section>
</template>
