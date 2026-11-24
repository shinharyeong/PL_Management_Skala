<script setup>
import {ref,watch,computed,onBeforeUnmount} from 'vue'
import {usePrototypeStore} from './live-store.js'
import {seoulPeriod,seoulDeadline} from './domain.js'
import {status,saveFile} from './live-api.js'
import {statusTone} from './presentation.js'
import copy from './ui-copy.json'
import {revealOrganization} from './reveal-organization.js'
import {submissionReviewStatus} from './review-status.js'
import LiveNotifications from './LiveNotifications.vue'
import './management.css'
import './management-live.css'
const props=defineProps({page:String}),emit=defineEmits(['navigate']),store=usePrototypeStore()
const year=ref(store.notificationTarget?.year||seoulPeriod().year),archiveYear=ref(seoulPeriod().year),month=ref(seoulPeriod().month)
const bulkSelected=ref([]),bulkDialog=ref(null)
const pendingTargets=computed(()=>store.annualRows.filter(t=>t.latestSubmission?.status==='PENDING'))
const allPending=computed({get:()=>pendingTargets.value.length>0&&pendingTargets.value.every(t=>bulkSelected.value.includes(t.organization.id)),set:value=>bulkSelected.value=value?pendingTargets.value.map(t=>t.organization.id):[]})
const recipients=ref([]),due=ref(''),message=ref('연간 계획을 작성해 제출해 주세요.'),expanded=ref(''),reply=ref(null),comment=ref(''),formError=ref('')
const annualMasters=ref(null),annualMasterError=ref('')
const org=ref(''),orgSearch=ref(''),archive=ref(null),changeHistory=ref(null),selectedVersion=ref(''),masters=ref([]),archiveLoading=ref(false),hideNumbers=ref(true)
const visibleOrganizations=computed(()=>store.organizations.filter(o=>(o.name+' '+o.code).toLowerCase().includes(orgSearch.value.toLowerCase())))
const selectedOrg=computed(()=>store.organizations.find(o=>o.id===org.value))
const acceptedCount=computed(()=>store.annualRows.filter(t=>t.latestSubmission?.status==='APPROVED').length)
const allRecipients=computed({get:()=>!!store.organizations.length&&recipients.value.length===store.organizations.length,set:value=>recipients.value=value?store.organizations.map(o=>o.id):[]})
const dateTime=value=>value?new Intl.DateTimeFormat('ko-KR',{timeZone:'Asia/Seoul',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}).format(new Date(value)):'—'
let generation=0,archiveGeneration=0,replyGeneration=0,changeGeneration=0
onBeforeUnmount(()=>{generation++;archiveGeneration++;replyGeneration++;changeGeneration++})
async function load(){
 const g=++generation
 expanded.value='';reply.value=null;formError.value='';bulkSelected.value=[]
 try{
  if(props.page.startsWith('annual')){
   annualMasters.value=null;annualMasterError.value=''
   await store.loadAnnual(year.value);if(g!==generation)return
   try{const result=await store.api('/api/v1/annual-masters?year='+year.value);if(g===generation)annualMasters.value=result}catch(e){if(g===generation)annualMasterError.value=e.message}
   if(g!==generation)return
   recipients.value=store.organizations.map(o=>o.id)
   const target=store.notificationTarget
   if(props.page==='annual'&&target?.organizationId){const row=store.annualRows.find(t=>t.organization.id===target.organizationId);if(row){await openRow(row);await revealOrganization('annual-org-'+row.organization.id,()=>g===generation&&props.page==='annual'&&expanded.value===row.organization.id)}store.notificationTarget=null}
  }
  if(props.page==='organizations'){await store.loadOrganizations();if(g!==generation)return;org.value ||=store.organizations[0]?.id||'';await loadArchive()}
 }catch(e){if(g===generation)store.error=e.message}
}
watch([()=>props.page,year],load,{immediate:true})
watch(formError,message=>{if(message)store.notify(message,'error')})
async function openRow(target){
 const g=++replyGeneration;expanded.value=target.organization.id;reply.value=null;comment.value=''
 if(target.latestSubmission){const result=await store.api('/api/v1/submissions/'+target.latestSubmission.id);if(g===replyGeneration){reply.value=result;comment.value=result.reviewComment||''}}
}
async function toggleRow(target){if(expanded.value===target.organization.id){replyGeneration++;expanded.value='';reply.value=null}else await store.run(()=>openRow(target))}
async function send(){
 formError.value=''
 if(!recipients.value.length||!message.value.trim()||!Number.isFinite(seoulDeadline(due.value))||seoulDeadline(due.value)<=Date.now()){formError.value='요청 대상과 메시지, 현재 시각 이후의 회신 기한을 확인해 주세요.';return}
 if(await store.run(()=>store.createRequest('ANNUAL_PLAN',recipients.value,due.value,message.value,Number(year.value))))emit('navigate','annual')
}
async function decide(decision){if(await store.run(()=>store.decideSubmission(reply.value,decision,comment.value))){reply.value=null;await load()}}
async function bulkAccept(){
 const chosen=pendingTargets.value.filter(t=>bulkSelected.value.includes(t.organization.id))
 bulkDialog.value.close()
 let success=0;const failures=[]
 await store.run(async()=>{for(const target of chosen){try{
  const current=await store.api('/api/v1/submissions/'+target.latestSubmission.id)
  if(current.status!=='PENDING')throw new Error('제출본 상태가 변경됐습니다.')
  await store.decideSubmission(current,'APPROVE','');success++
 }catch(e){failures.push(target.organization.name+': '+e.message)}}})
 await load()
 store.notify(`${success}개 조직 수락 완료${failures.length?' · '+failures.join(' · '):''}`,failures.length?'error':'info')
}
async function loadArchive(){
 const g=++archiveGeneration;changeGeneration++;archive.value=null;changeHistory.value=null;selectedVersion.value='';masters.value=[];archiveLoading.value=true
 try{
  if(!org.value)return
  const [versions,master]=await Promise.all([store.api(`/api/v1/organizations/${org.value}/versions?year=${archiveYear.value}&month=${month.value}`),store.api(`/api/v1/masters?year=${archiveYear.value}`)])
  if(g!==archiveGeneration)return
  archive.value=versions;masters.value=master.items
 }catch(e){if(g===archiveGeneration)store.error=e.message}finally{if(g===archiveGeneration)archiveLoading.value=false}
}
async function selectMonth(value){month.value=value;await loadArchive()}
async function selectOrganization(id){org.value=id;await loadArchive()}
async function showChanges(version){
 const g=++changeGeneration;selectedVersion.value=version.submissionId;changeHistory.value=null
 await store.run(async()=>{const result=await store.api('/api/v1/submissions/'+version.submissionId+'/change-history');if(g===changeGeneration)changeHistory.value=result})
}
async function downloadAnnualMaster(master){await store.run(async()=>saveFile(await store.api('/api/v1/annual-masters/'+master.id+'/file',{blob:true}),master.label+'.xlsx'))}
async function downloadMaster(master){await store.run(async()=>saveFile(await store.api(`/api/v1/masters/${master.id}/file`,{blob:true}),master.label+'.xlsx'))}
</script>

<template>
 <div class="management-panels management-panel live-management">
  <header v-if="page!=='notifications'" class="mg-heading"><div><p class="mg-breadcrumb">경영기획팀 / {{copy.pages[page]}}</p><h1>{{copy.pages[page]}}</h1></div></header>
  <dialog ref="bulkDialog" class="confirm-dialog"><h2>선택한 {{bulkSelected.length}}개 조직을 수락할까요?</h2><p>각 제출본을 승인합니다. 일부가 실패하면 성공한 승인은 유지되고 실패 조직을 안내합니다.</p><div class="actions"><button class="btn" @click="bulkDialog.close()">취소</button><button class="btn primary" :disabled="store.busy" @click="bulkAccept">일괄 수락</button></div></dialog>
  <LiveNotifications v-if="page==='notifications'" @navigate="emit('navigate',$event)" />

  <template v-else-if="page==='annual'">
   <p class="mg-description">진행 중인 요청과 조직별 제출·회신·수락 상태를 확인합니다.</p>
   <div class="mg-section-heading"><h2>진행 중인 요청</h2><div class="mg-actions"><label class="mg-field">계획 연도<input v-model="year" type="number" min="2000" max="9999"></label><button class="btn primary" @click="emit('navigate','annual-request')">연간 계획 요청</button></div></div>
   <section class="card mg-annual-progress" aria-labelledby="annual-master-title"><div class="mg-section-heading"><h2 id="annual-master-title">{{year}}년 연간 master</h2><span class="badge" :class="annualMasters?.state==='CONFIRMED'?'mg-status-blue':'mg-status-amber'">{{annualMasters?.state==='CONFIRMED'?'확정':annualMasters?.state==='NO_REQUEST'?'요청 전':annualMasters?.state==='WAITING'?'확정 대기':'조회 중'}}</span></div><p v-if="annualMasterError" class="error" role="alert">연간 master를 조회하지 못했습니다. {{annualMasterError}}</p><template v-else-if="annualMasters"><p v-if="!annualMasters.items.length" class="mg-small-note">{{annualMasters.state==='NO_REQUEST'?'선택한 연도의 연간 계획 요청이 없습니다.':'요청 대상 조직의 제출본을 모두 수락하면 연간 master가 자동 생성됩니다.'}}</p><template v-else><p class="mg-small-note">최신 확정 버전 {{annualMasters.items[0].label}} · 이전 버전도 보관됩니다. 확정된 조직별 승인본의 계획값을 내려받습니다.</p><div class="mg-table-scroll"><table class="mg-table"><thead><tr><th>master 버전</th><th>확정 시각</th><th>확정 담당자</th><th>포함 조직</th><th>파일</th></tr></thead><tbody><tr v-for="(master,index) in annualMasters.items" :key="master.id"><td><strong>{{master.label}}</strong><span v-if="index===0" class="badge mg-status-blue">최신</span></td><td>{{dateTime(master.createdAt)}}</td><td>{{master.createdByName}}</td><td>{{master.organizationCount}}곳</td><td><button class="btn" :disabled="store.busy" @click="downloadAnnualMaster(master)">엑셀 다운로드</button></td></tr></tbody></table></div></template></template></section>
   <section class="card mg-annual-progress"><h2>조직별 진행 현황</h2><p class="mg-small-note">{{year}}년 연간 계획 · 수락 {{acceptedCount}}/{{store.annualRows.length}}</p>
    <div class="mg-actions"><button class="btn primary" :disabled="store.busy||!bulkSelected.length" @click="bulkDialog.showModal()">선택 {{bulkSelected.length}}개 조직 일괄 수락</button><span class="muted">검토 대기 조직만 선택할 수 있습니다.</span></div><div class="mg-table-scroll"><table class="mg-table"><thead><tr><th><input v-model="allPending" type="checkbox" aria-label="검토 대기 조직 전체 선택" :disabled="store.busy"></th><th>조직명</th><th>조직코드</th><th>발송일</th><th>회신 기한</th><th>제출·검토 상태</th></tr></thead><tbody>
     <template v-for="target in store.annualRows" :key="target.organization.id">
      <tr :id="'annual-org-'+target.organization.id" class="organization-reveal-row" :class="{'mg-selected-row':expanded===target.organization.id}"><td><input v-model="bulkSelected" type="checkbox" :value="target.organization.id" :disabled="store.busy||target.latestSubmission?.status!=='PENDING'" :aria-label="target.organization.name+' 일괄 수락 선택'"></td><td><button class="mg-row-link" :aria-expanded="expanded===target.organization.id" @click="toggleRow(target)">{{target.organization.name}}</button></td><td>{{target.organization.code}}</td><td>{{dateTime(target.requestedAt)}}</td><td>{{dateTime(target.dueAt)}}</td><td><span class="badge" :class="'mg-status-'+submissionReviewStatus(target.latestSubmission).tone">{{submissionReviewStatus(target.latestSubmission).label}}</span><small class="mg-submission-state">{{status(target.displayStatus)}}</small></td></tr>
      <tr v-if="expanded===target.organization.id" class="mg-expanded"><td colspan="6"><article v-if="reply" class="mg-reply"><h3>{{reply.submittedBy?.name||'—'}} · {{reply.organization.name}}</h3><small class="muted">{{dateTime(reply.submittedAt)}} 회신</small><p>{{reply.replyMessage||'회신 메시지 없음'}}</p><button class="btn mg-file-btn" @click="store.run(()=>store.downloadSubmission(reply.id,reply.file.name))">{{reply.file.name}} ↓</button><div class="mg-history-note"><strong>이력</strong><p>{{dateTime(target.requestedAt)}} · 연간 계획 제출 요청 발송</p><p>{{dateTime(reply.submittedAt)}} · {{reply.label}} · {{reply.file.name}}</p></div><label class="mg-field">경영기획 코멘트 (재요청 시 필수)<textarea v-model="comment" rows="3" :placeholder="reply.status==='PENDING' ? '회신에 대한 경영기획 코멘트를 입력하세요.' : '저장된 코멘트가 없습니다.'" :readonly="reply.status!=='PENDING'" aria-describedby="annual-comment-help"></textarea></label><p id="annual-comment-help" class="mg-small-note" role="status">{{ reply.status==='PENDING' ? '코멘트는 수락 또는 재요청할 때 함께 저장됩니다.' : '현재 상태: '+status(reply.status)+' · 처리가 완료된 제출본입니다. 코멘트 추가·수정은 현재 서버에서 지원하지 않습니다.' }}</p><div class="mg-actions"><button class="btn" :disabled="store.busy||!comment.trim()||reply.status!=='PENDING'" @click="decide('REJECT')">재요청</button><button class="btn primary" :disabled="store.busy||reply.status!=='PENDING'" @click="decide('APPROVE')">{{reply.status==='APPROVED'?'수락 완료':'수락'}}</button></div></article><p v-else class="mg-empty">{{target.latestSubmission?'회신을 불러오는 중입니다.':'아직 회신한 내용이 없습니다.'}}</p></td></tr>
     </template><tr v-if="!store.annualRows.length"><td colspan="6" class="mg-empty">해당 연도의 요청이 없습니다.</td></tr>
    </tbody></table></div><p class="mg-small-note">모든 조직이 수락되면 서버가 연간 계획 마스터를 생성합니다.</p>
   </section>
  </template>

  <template v-else-if="page==='annual-request'">
   <p class="mg-description">전체 조직을 기본 선택하고 회신 기한과 요청 메시지를 설정합니다.</p>
   <form class="card mg-request-form" @submit.prevent="send"><div class="mg-section-heading"><h2>연간 계획 요청</h2><label class="mg-field">계획 연도<input v-model="year" type="number" min="2000" max="9999" required></label></div>
    <div class="mg-table-scroll"><table class="mg-table"><thead><tr><th><input v-model="allRecipients" type="checkbox" aria-label="모든 조직 선택"></th><th>조직명</th><th>조직코드</th><th>수신 담당자</th></tr></thead><tbody><tr v-for="organization in store.organizations" :key="organization.id"><td><input v-model="recipients" type="checkbox" :value="organization.id" :aria-label="organization.name+' 선택'"></td><td>{{organization.name}}</td><td>{{organization.code}}</td><td>{{organization.author}}</td></tr></tbody></table></div>
    <label class="mg-field">회신 기한<input v-model="due" type="datetime-local" required></label><label class="mg-field">요청 메시지 (템플릿)<textarea v-model="message" rows="4" required></textarea></label><p v-if="formError" class="error" role="alert">{{formError}}</p><div class="mg-actions"><button class="btn primary mg-request-submit" type="submit" :disabled="store.busy||!recipients.length">{{recipients.length}}개 조직에 연간 계획 요청 발송</button><button class="btn" type="button" @click="emit('navigate','annual')">취소</button></div>
   </form><button class="btn mg-back-link" @click="emit('navigate','annual')">연간 계획 관리로</button>
  </template>

  <template v-else-if="page==='organizations'">
   <p class="mg-description">조직과 월을 선택해 제출 버전·변경 사유·소명 이력을 확인합니다.</p>
   <div class="mg-archive"><aside class="card mg-org-picker"><h2>조직 선택</h2><input v-model="orgSearch" type="search" placeholder="조직명·코드 검색" aria-label="조직 검색"><button v-for="organization in visibleOrganizations" :key="organization.id" :class="{selected:org===organization.id}" :aria-pressed="org===organization.id" @click="selectOrganization(organization.id)">{{organization.name}}<small>{{organization.code}}</small></button><p v-if="!visibleOrganizations.length" class="muted">일치하는 조직이 없습니다.</p></aside>
    <div class="mg-archive-main"><div class="mg-section-heading"><h2>{{selectedOrg?.name||'조직을 선택해 주세요'}} · {{archiveYear}}</h2><label class="mg-field">연도<input v-model="archiveYear" type="number" min="2000" max="9999" @change="loadArchive"></label></div><p class="mg-small-note">담당 {{selectedOrg?.author||'—'}}</p>
     <div class="mg-month-grid"><button v-for="m in 12" :key="m" class="card" :class="{selected:month===m}" :aria-pressed="month===m" @click="selectMonth(m)"><strong>{{m}}월</strong><small class="muted">최종 승인 버전 {{archive?.months.find(item=>item.month===m)?.finalLabel||'—'}}</small></button></div>
     <p v-if="archiveLoading" class="muted" role="status">버전 이력을 불러오는 중입니다.</p><h2>{{month}}월 버전 이력</h2>
     <div class="mg-table-scroll"><table class="mg-table"><thead><tr><th>버전명</th><th>최종 수정 일시</th><th>파일</th><th>상태</th></tr></thead><tbody><tr v-for="version in archive?.history" :key="version.submissionId" :class="{'mg-blue-row':selectedVersion===version.submissionId}"><td><button class="mg-row-link" @click="showChanges(version)">{{version.label}}</button></td><td>{{dateTime(version.lastModifiedAt)}}</td><td><button class="mg-icon-button" :disabled="store.busy" :aria-label="(version.file?.name||version.label)+' 다운로드'" @click="store.run(()=>store.downloadSubmission(version.submissionId,version.file?.name))">↓</button></td><td><span class="badge" :class="'mg-status-'+statusTone(status(version.status))">{{status(version.status)}}</span></td></tr><tr v-if="archive&&!archive.history.length"><td colspan="4" class="mg-empty">제출된 버전이 없습니다.</td></tr></tbody></table></div>
     <template v-if="changeHistory"><h3>변경 이력</h3><div class="mg-table-scroll"><table class="mg-table mg-changes-table"><thead><tr><th>변경 일자</th><th>순서</th><th>코드</th><th>변경 항목</th><th>소명 사유</th></tr></thead><tbody><template v-for="date in changeHistory.dates" :key="date.changedAt"><tr v-for="item in date.items" :key="item.no"><td>{{dateTime(date.changedAt)}}</td><td>{{item.no}}</td><td>{{item.code}}</td><td>{{item.name}} · {{item.month}}월 · {{item.scenario}}</td><td>{{item.explanation||'—'}}</td></tr></template><tr v-if="!changeHistory.dates.length"><td colspan="5" class="mg-empty">변경 이력이 없습니다.</td></tr></tbody></table></div></template>
     <h2>월별 master</h2><div class="mg-table-scroll"><table class="mg-table"><thead><tr><th>대상 월</th><th>master 버전</th><th>확정일</th><th>상태</th><th>파일</th></tr></thead><tbody><tr v-for="master in masters" :key="master.month"><td>{{master.month}}월</td><td>{{master.label}}</td><td>{{dateTime(master.createdAt)}}</td><td><span :class="master.state==='CONFIRMED'?'mg-green':'muted'">{{master.state==='CONFIRMED'?'확정':'생성 대기'}}</span></td><td><button class="mg-icon-button" :disabled="store.busy||!master.id" :aria-label="master.label+' 다운로드'" @click="downloadMaster(master)">↓</button></td></tr></tbody></table></div>
    </div>
   </div>
  </template>

  <template v-else-if="page==='reports'">
   <div class="mg-report-stepper"><strong>AI 보고서 만들기</strong><span v-for="(step,i) in ['시작','구성 확인','데이터','소명 분류','생성·편집']" :key="step" :class="{active:i===0}">{{i+1}} {{step}}</span></div>
   <p class="notice" role="status">보고서 API 연결 준비 중입니다. PDF 분석·틀 저장·데이터 조회·내보내기는 아직 사용할 수 없습니다.</p>
   <div class="mg-report-grid"><article class="card mg-report-card"><span class="mg-blue mg-small-note">1-1 새 틀 만들기</span><h2>보고서 PDF 업로드</h2><p class="muted">쓰고 싶은 보고서 양식을 올리면 AI가 표·문장·구조 구성을 뽑습니다. 숫자는 가져오지 않습니다.</p><div class="mg-dropzone mg-unavailable" aria-disabled="true"><img src="/assets/prototype-report-upload.svg" alt="" width="24" height="24"><strong>PDF를 끌어오거나 눌러서 선택</strong><small class="muted">PDF 업로드 · 분석 연결 준비 중</small></div><label class="mg-checkbox"><input v-model="hideNumbers" type="checkbox" disabled>AI에 보내기 전에 PDF의 숫자 가리기</label><button class="btn primary" disabled>자료 틀 분석</button><button class="mg-text-button" disabled>자료 틀 직접 만들기 →</button></article><article class="card mg-report-card"><span class="mg-blue mg-small-note">1-2 저장한 틀 불러오기</span><h2>내 틀</h2><p class="muted">불러오면 구성 확인을 건너뛰고 데이터 선택으로 갑니다.</p><input type="search" placeholder="틀 이름으로 찾기" aria-label="자료 틀 검색" disabled><p class="mg-empty">저장된 틀 조회는 API 연결 후 사용할 수 있습니다.</p><div class="mg-right"><button class="btn primary" disabled>이 틀로 시작</button></div></article></div>
  </template>
 </div>
</template>
