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
import PButton from 'primevue/button'
import PCard from 'primevue/card'
import PCheckbox from 'primevue/checkbox'
import PColumn from 'primevue/column'
import PDataTable from 'primevue/datatable'
import PDialog from 'primevue/dialog'
import PInputNumber from 'primevue/inputnumber'
import PInputText from 'primevue/inputtext'
import PTag from 'primevue/tag'
import PTextarea from 'primevue/textarea'
import './management.css'
import './management-live.css'
const props=defineProps({page:String}),emit=defineEmits(['navigate']),store=usePrototypeStore()
const year=ref(store.notificationTarget?.year||seoulPeriod().year),archiveYear=ref(seoulPeriod().year),month=ref(seoulPeriod().month)
const bulkSelected=ref([]),bulkDialog=ref(false)
const pendingTargets=computed(()=>store.annualRows.filter(t=>t.latestSubmission?.status==='PENDING'))
const allPending=computed({get:()=>pendingTargets.value.length>0&&pendingTargets.value.every(t=>bulkSelected.value.includes(t.organization.id)),set:value=>bulkSelected.value=value?pendingTargets.value.map(t=>t.organization.id):[]})
const recipients=ref([]),due=ref(''),message=ref('연간 계획을 작성해 제출해 주세요.'),expanded=ref(''),reply=ref(null),comment=ref(''),formError=ref('')
const annualMasters=ref(null),annualMasterError=ref('')
const org=ref(''),orgSearch=ref(''),archive=ref(null),changeHistory=ref(null),selectedVersion=ref(''),masters=ref([]),archiveLoading=ref(false)
const visibleOrganizations=computed(()=>store.organizations.filter(o=>(o.name+' '+o.code).toLowerCase().includes(orgSearch.value.toLowerCase())))
const selectedOrg=computed(()=>store.organizations.find(o=>o.id===org.value))
const acceptedCount=computed(()=>store.annualRows.filter(t=>t.latestSubmission?.status==='APPROVED').length)
const allRecipients=computed({get:()=>!!store.organizations.length&&recipients.value.length===store.organizations.length,set:value=>recipients.value=value?store.organizations.map(o=>o.id):[]})
const expandedAnnualRows=computed(annualExpansion)
const archiveChanges=computed(flattenArchiveChanges)
const dateTime=value=>value?new Intl.DateTimeFormat('ko-KR',{timeZone:'Asia/Seoul',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}).format(new Date(value)):'—'
let generation=0,archiveGeneration=0,replyGeneration=0,changeGeneration=0
onBeforeUnmount(()=>{generation++;archiveGeneration++;replyGeneration++;changeGeneration++})
/** 선택 화면·유효 연도의 연간 계획 또는 조직 목록을 조회한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음; 오류는 store에 안내한다.
 * Side Effects: 서버 조회와 화면 목록 초기화. 이전 조회 응답은 세대 번호로 차단한다.
 */
async function load(){
 const g=++generation
 expanded.value='';reply.value=null;formError.value='';bulkSelected.value=[]
 if(props.page.startsWith('annual')&&!validYear(year.value))return
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
/** 선택 조직과 한국 시간 기한으로 연간 계획을 요청한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음; 입력·서버 오류를 화면에 안내한다.
 * Side Effects: 명시된 요청을 서버에 생성하고 성공 시 연간 관리 화면으로 이동한다.
 */
async function send(){
 formError.value=''
 if(!validYear(year.value)||!recipients.value.length||!message.value.trim()||!Number.isFinite(seoulDeadline(due.value))||seoulDeadline(due.value)<=Date.now()){formError.value='계획 연도와 요청 대상, 메시지, 현재 시각 이후의 회신 기한을 확인해 주세요.';return}
 if(await store.run(()=>store.createRequest('ANNUAL_PLAN',recipients.value,due.value,message.value,Number(year.value))))emit('navigate','annual')
}
async function decide(decision){if(await store.run(()=>store.decideSubmission(reply.value,decision,comment.value))){reply.value=null;await load()}}
/** 확인한 대기 조직을 순서대로 승인하고 성공·실패 결과를 안내한다.
 * Args: 없음. Returns: Promise<void>.
 * Raises: 없음; 서버 오류는 조직별 안내로 남긴다.
 * Side Effects: 확인 대화상자를 닫고 승인 요청·최신 목록 조회를 실행한다.
 */
async function bulkAccept(){
 const chosen=pendingTargets.value.filter(t=>bulkSelected.value.includes(t.organization.id))
 bulkDialog.value=false
 let success=0;const failures=[]
 await store.run(async()=>{for(const target of chosen){try{
  const current=await store.api('/api/v1/submissions/'+target.latestSubmission.id)
  if(current.status!=='PENDING')throw new Error('제출본 상태가 변경됐습니다.')
  await store.decideSubmission(current,'APPROVE','');success++
 }catch(e){failures.push(target.organization.name+': '+e.message)}}})
 await load()
 store.notify(`${success}개 조직 수락 완료${failures.length?' · '+failures.join(' · '):''}`,failures.length?'error':'info')
}
/** 유효한 조직·연월의 제출 이력과 마스터 목록을 조회한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음; 조회 오류는 store에 안내한다.
 * Side Effects: 이전 표시를 지우고 읽기 전용 HTTP 요청을 실행한다.
 */
async function loadArchive(){
 const g=++archiveGeneration;changeGeneration++;archive.value=null;changeHistory.value=null;selectedVersion.value='';masters.value=[];archiveLoading.value=true
 try{
  if(!org.value||!validYear(archiveYear.value))return
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

/** 연도 입력이 서버에서 허용하는 정수 범위인지 확인한다.
 * Args: value 연도 또는 비어 있는 입력.
 * Returns: 2000~9999 정수 여부. Raises: 없음. Side Effects: 없음.
 */
function validYear(value){return Number.isInteger(value)&&value>=2000&&value<=9999}

/** PrimeVue 연도 모델을 반영한 뒤 유효한 기간의 이력을 조회한다.
 * Args: value InputNumber가 전달한 연도 또는 null.
 * Returns: Promise<void>. Raises: 없음; 조회 오류는 기존 화면 상태로 처리한다.
 * Side Effects: 기준 연도·이력 표시 갱신과 읽기 전용 HTTP 조회.
 */
async function changeArchiveYear(value){archiveYear.value=value;await loadArchive()}

/** 펼친 조직 행을 PrimeVue 표에서 강조한다.
 * Args: target 조직별 제출 상태.
 * Returns: 선택 행 클래스. Raises: 없음. Side Effects: 없음.
 */
function annualRowClass(target){return expanded.value===target.organization.id?'mg-selected-row':''}

/** 변경 이력을 조회 중인 제출 버전을 표시한다.
 * Args: version 서버 제출 버전.
 * Returns: 선택 행 클래스. Raises: 없음. Side Effects: 없음.
 */
function versionRowClass(version){return selectedVersion.value===version.submissionId?'mg-blue-row':''}

/** 현재 펼친 조직 ID를 PrimeVue 확장 행 모델로 변환한다.
 * Args: 없음. Returns: 조직 ID별 true 사전. Raises: 없음. Side Effects: 없음.
 */
function annualExpansion(){return expanded.value?{[expanded.value]:true}:{}}

/** 날짜별 변경 이력을 표시용 평면 행으로 구성한다.
 * Args: 없음. Returns: changedAt을 보존한 항목 목록.
 * Raises: 없음. Side Effects: 없음. 원본 항목·소명 값은 수정하지 않는다.
 */
function flattenArchiveChanges(){return (changeHistory.value?.dates||[]).flatMap(date=>date.items.map(item=>({...item,changedAt:date.changedAt})))}
</script>

<template>
 <div class="management-panels management-panel live-management">
  <header v-if="page!=='notifications'" class="mg-heading"><div><p class="mg-breadcrumb">경영기획팀 / {{copy.pages[page]}}</p><h1>{{copy.pages[page]}}</h1></div></header>
  <PDialog v-model:visible="bulkDialog" modal :header="`선택한 ${bulkSelected.length}개 조직을 수락할까요?`" :style="{width:'32rem'}" :breakpoints="{'640px':'92vw'}" :closable="!store.busy" :closeOnEscape="!store.busy">
   <p>각 제출본을 승인합니다. 일부가 실패하면 성공한 승인은 유지되고 실패 조직을 안내합니다.</p>
   <template #footer><PButton severity="secondary" outlined :disabled="store.busy" @click="bulkDialog=false">취소</PButton><PButton :disabled="store.busy" @click="bulkAccept">일괄 수락</PButton></template>
  </PDialog>
  <LiveNotifications v-if="page==='notifications'" @navigate="emit('navigate',$event)" />

  <template v-else-if="page==='annual'">
   <p class="mg-description">진행 중인 요청과 조직별 제출·회신·수락 상태를 확인합니다.</p>
   <div class="mg-section-heading"><h2>진행 중인 요청</h2><div class="mg-actions"><label class="mg-field">계획 연도<PInputNumber v-model="year" :min="2000" :max="9999" :useGrouping="false" :maxFractionDigits="0" inputId="annual-plan-year" /></label><PButton @click="emit('navigate','annual-request')">연간 계획 요청</PButton></div></div>
   <PCard class="card mg-annual-progress" aria-labelledby="annual-master-title"><template #content>
    <div class="mg-section-heading"><h2 id="annual-master-title">{{year}}년 연간 master</h2><PTag class="badge" :severity="annualMasters?.state==='CONFIRMED'?'info':'warn'" :class="annualMasters?.state==='CONFIRMED'?'mg-status-blue':'mg-status-amber'" :value="annualMasters?.state==='CONFIRMED'?'확정':annualMasters?.state==='NO_REQUEST'?'요청 전':annualMasters?.state==='WAITING'?'확정 대기':'조회 중'" /></div>
    <p v-if="annualMasterError" class="error" role="alert">연간 master를 조회하지 못했습니다. {{annualMasterError}}</p>
    <template v-else-if="annualMasters">
     <p v-if="!annualMasters.items.length" class="mg-small-note">{{annualMasters.state==='NO_REQUEST'?'선택한 연도의 연간 계획 요청이 없습니다.':'요청 대상 조직의 제출본을 모두 수락하면 연간 master가 자동 생성됩니다.'}}</p>
     <template v-else><p class="mg-small-note">최신 확정 버전 {{annualMasters.items[0].label}} · 이전 버전도 보관됩니다. 확정된 조직별 승인본의 계획값을 내려받습니다.</p>
      <PDataTable :value="annualMasters.items" dataKey="id" tableClass="mg-table" :pt="{table:{'aria-label':'연간 마스터 버전'}}">
       <PColumn header="master 버전"><template #body="{data,index}"><strong>{{data.label}}</strong> <PTag v-if="index===0" class="badge mg-status-blue" severity="info" value="최신" /></template></PColumn>
       <PColumn header="확정 시각"><template #body="{data}">{{dateTime(data.createdAt)}}</template></PColumn>
       <PColumn field="createdByName" header="확정 담당자" /><PColumn header="포함 조직"><template #body="{data}">{{data.organizationCount}}곳</template></PColumn>
       <PColumn header="파일"><template #body="{data}"><PButton severity="secondary" outlined :disabled="store.busy" @click="downloadAnnualMaster(data)">엑셀 다운로드</PButton></template></PColumn>
      </PDataTable>
     </template>
    </template>
   </template></PCard>
   <PCard class="card mg-annual-progress"><template #content>
    <h2>조직별 진행 현황</h2><p class="mg-small-note">{{year}}년 연간 계획 · 수락 {{acceptedCount}}/{{store.annualRows.length}}</p>
    <div class="mg-actions"><PButton :disabled="store.busy||!bulkSelected.length" @click="bulkDialog=true">선택 {{bulkSelected.length}}개 조직 일괄 수락</PButton><span class="muted">검토 대기 조직만 선택할 수 있습니다.</span></div>
    <PDataTable :value="store.annualRows" dataKey="organization.id" :expandedRows="expandedAnnualRows" :rowClass="annualRowClass" tableClass="mg-table" :pt="{table:{'aria-label':'조직별 연간 계획 진행 현황'}}">
     <PColumn header="선택" style="width:3rem"><template #header><PCheckbox v-model="allPending" binary aria-label="검토 대기 조직 전체 선택" :disabled="store.busy" /></template><template #body="{data}"><PCheckbox v-model="bulkSelected" :value="data.organization.id" :disabled="store.busy||data.latestSubmission?.status!=='PENDING'" :aria-label="data.organization.name+' 일괄 수락 선택'" /></template></PColumn>
     <PColumn header="조직명"><template #body="{data}"><span :id="'annual-org-'+data.organization.id" class="organization-reveal-row"><PButton class="mg-row-link" variant="link" :aria-expanded="expanded===data.organization.id" @click="toggleRow(data)">{{data.organization.name}}</PButton></span></template></PColumn>
     <PColumn field="organization.code" header="조직코드" /><PColumn header="발송일"><template #body="{data}">{{dateTime(data.requestedAt)}}</template></PColumn><PColumn header="회신 기한"><template #body="{data}">{{dateTime(data.dueAt)}}</template></PColumn>
     <PColumn header="제출·검토 상태"><template #body="{data}"><PTag class="badge" :severity="({blue:'info',amber:'warn',red:'danger',gray:'secondary'})[submissionReviewStatus(data.latestSubmission).tone]" :class="'mg-status-'+submissionReviewStatus(data.latestSubmission).tone" :value="submissionReviewStatus(data.latestSubmission).label" /><small class="mg-submission-state">{{status(data.displayStatus)}}</small></template></PColumn>
     <template #expansion="{data}"><div class="mg-expanded"><article v-if="reply" class="mg-reply">
      <h3>{{reply.submittedBy?.name||'—'}} · {{reply.organization.name}}</h3><small class="muted">{{dateTime(reply.submittedAt)}} 회신</small><p>{{reply.replyMessage||'회신 메시지 없음'}}</p>
      <PButton class="mg-file-btn" severity="secondary" outlined @click="store.run(()=>store.downloadSubmission(reply.id,reply.file.name))">{{reply.file.name}} ↓</PButton>
      <div class="mg-history-note"><strong>이력</strong><p>{{dateTime(data.requestedAt)}} · 연간 계획 제출 요청 발송</p><p>{{dateTime(reply.submittedAt)}} · {{reply.label}} · {{reply.file.name}}</p></div>
      <label class="mg-field">경영기획 코멘트 (재요청 시 필수)<PTextarea v-model="comment" rows="3" :placeholder="reply.status==='PENDING'?'회신에 대한 경영기획 코멘트를 입력하세요.':'저장된 코멘트가 없습니다.'" :readonly="reply.status!=='PENDING'" aria-describedby="annual-comment-help" /></label>
      <p id="annual-comment-help" class="mg-small-note" role="status">{{reply.status==='PENDING'?'코멘트는 수락 또는 재요청할 때 함께 저장됩니다.':'현재 상태: '+status(reply.status)+' · 처리가 완료된 제출본입니다. 코멘트 추가·수정은 현재 서버에서 지원하지 않습니다.'}}</p>
      <div class="mg-actions"><PButton severity="secondary" outlined :disabled="store.busy||!comment.trim()||reply.status!=='PENDING'" @click="decide('REJECT')">재요청</PButton><PButton :disabled="store.busy||reply.status!=='PENDING'" @click="decide('APPROVE')">{{reply.status==='APPROVED'?'수락 완료':'수락'}}</PButton></div>
     </article><p v-else class="mg-empty">{{data.latestSubmission?'회신을 불러오는 중입니다.':'아직 회신한 내용이 없습니다.'}}</p></div></template>
     <template #empty><span class="mg-empty">해당 연도의 요청이 없습니다.</span></template>
    </PDataTable>
    <p class="mg-small-note">모든 조직이 수락되면 서버가 연간 계획 마스터를 생성합니다.</p>
   </template></PCard>
  </template>

  <template v-else-if="page==='annual-request'">
   <p class="mg-description">전체 조직을 기본 선택하고 회신 기한과 요청 메시지를 설정합니다.</p>
   <PCard class="card mg-request-form"><template #content><form class="mg-request-fields" @submit.prevent="send">
    <div class="mg-section-heading"><h2>연간 계획 요청</h2><label class="mg-field">계획 연도<PInputNumber v-model="year" :min="2000" :max="9999" :useGrouping="false" :maxFractionDigits="0" inputId="annual-request-year" required /></label></div>
    <PDataTable :value="store.organizations" dataKey="id" tableClass="mg-table" :pt="{table:{'aria-label':'연간 계획 요청 대상'}}">
     <PColumn header="선택" style="width:3rem"><template #header><PCheckbox v-model="allRecipients" binary aria-label="모든 조직 선택" /></template><template #body="{data}"><PCheckbox v-model="recipients" :value="data.id" :aria-label="data.name+' 선택'" /></template></PColumn>
     <PColumn field="name" header="조직명" /><PColumn field="code" header="조직코드" /><PColumn field="author" header="수신 담당자" />
     <template #empty>요청할 조직이 없습니다.</template>
    </PDataTable>
    <label class="mg-field">회신 기한<PInputText v-model="due" type="datetime-local" required /></label><label class="mg-field">요청 메시지 (템플릿)<PTextarea v-model="message" rows="4" required /></label>
    <p v-if="formError" class="error" role="alert">{{formError}}</p><div class="mg-actions"><PButton class="mg-request-submit" type="submit" :disabled="store.busy||!recipients.length">{{recipients.length}}개 조직에 연간 계획 요청 발송</PButton><PButton severity="secondary" outlined type="button" @click="emit('navigate','annual')">취소</PButton></div>
   </form></template></PCard><PButton class="mg-back-link" variant="link" @click="emit('navigate','annual')">연간 계획 관리로</PButton>
  </template>

  <template v-else-if="page==='organizations'">
   <p class="mg-description">조직과 월을 선택해 제출 버전·변경 사유·소명 이력을 확인합니다.</p>
   <div class="mg-archive"><PCard class="card mg-org-picker" aria-label="조직 선택"><template #content>
    <h2>조직 선택</h2><PInputText v-model="orgSearch" type="search" placeholder="조직명·코드 검색" aria-label="조직 검색" />
    <PButton v-for="organization in visibleOrganizations" :key="organization.id" :severity="org===organization.id?undefined:'secondary'" :outlined="org!==organization.id" :class="{selected:org===organization.id}" :aria-pressed="org===organization.id" @click="selectOrganization(organization.id)"><span>{{organization.name}}<small>{{organization.code}}</small></span></PButton><p v-if="!visibleOrganizations.length" class="muted">일치하는 조직이 없습니다.</p>
   </template></PCard>
    <div class="mg-archive-main"><div class="mg-section-heading"><h2>{{selectedOrg?.name||'조직을 선택해 주세요'}} · {{archiveYear}}</h2><label class="mg-field">연도<PInputNumber :modelValue="archiveYear" :min="2000" :max="9999" :useGrouping="false" :maxFractionDigits="0" inputId="organization-archive-year" @update:modelValue="changeArchiveYear" /></label></div><p class="mg-small-note">담당 {{selectedOrg?.author||'—'}}</p>
     <div class="mg-month-grid"><PButton v-for="m in 12" :key="m" class="mg-month-button" :severity="month===m?undefined:'secondary'" :outlined="month!==m" :class="{selected:month===m}" :aria-pressed="month===m" @click="selectMonth(m)"><span><strong>{{m}}월</strong><small class="muted">최종 승인 버전 {{archive?.months.find(item=>item.month===m)?.finalLabel||'—'}}</small></span></PButton></div>
     <p v-if="archiveLoading" class="muted" role="status">버전 이력을 불러오는 중입니다.</p><h2>{{month}}월 버전 이력</h2>
     <PDataTable :value="archive?.history||[]" dataKey="submissionId" :rowClass="versionRowClass" tableClass="mg-table" :pt="{table:{'aria-label':'조직 제출 버전 이력'}}">
      <PColumn header="버전명"><template #body="{data}"><PButton class="mg-row-link" variant="link" @click="showChanges(data)">{{data.label}}</PButton></template></PColumn>
      <PColumn header="최종 수정 일시"><template #body="{data}">{{dateTime(data.lastModifiedAt)}}</template></PColumn>
      <PColumn header="파일"><template #body="{data}"><PButton icon="pi pi-download" text :disabled="store.busy" :aria-label="(data.file?.name||data.label)+' 다운로드'" @click="store.run(()=>store.downloadSubmission(data.submissionId,data.file?.name))" /></template></PColumn>
      <PColumn header="상태"><template #body="{data}"><PTag class="badge" :severity="({blue:'info',purple:'warn',red:'danger',gray:'secondary'})[statusTone(status(data.status))]" :class="'mg-status-'+statusTone(status(data.status))" :value="status(data.status)" /></template></PColumn>
      <template #empty><span class="mg-empty">제출된 버전이 없습니다.</span></template>
     </PDataTable>
     <template v-if="changeHistory"><h3>변경 이력</h3>
      <PDataTable :value="archiveChanges" tableClass="mg-table mg-changes-table" :pt="{table:{'aria-label':'제출본 변경 항목과 소명'}}">
       <PColumn header="변경 일자"><template #body="{data}">{{dateTime(data.changedAt)}}</template></PColumn><PColumn field="no" header="순서" /><PColumn field="code" header="코드" /><PColumn header="변경 항목"><template #body="{data}">{{data.name}} · {{data.month}}월 · {{data.scenario}}</template></PColumn><PColumn header="소명 사유"><template #body="{data}">{{data.explanation||'—'}}</template></PColumn>
       <template #empty><span class="mg-empty">변경 이력이 없습니다.</span></template>
      </PDataTable>
     </template>
     <h2>월별 master</h2><PDataTable :value="masters" dataKey="month" tableClass="mg-table" :pt="{table:{'aria-label':'월별 마스터'}}">
      <PColumn header="대상 월"><template #body="{data}">{{data.month}}월</template></PColumn><PColumn field="label" header="master 버전" /><PColumn header="확정일"><template #body="{data}">{{dateTime(data.createdAt)}}</template></PColumn><PColumn header="상태"><template #body="{data}"><PTag :severity="data.state==='CONFIRMED'?'success':'secondary'" :value="data.state==='CONFIRMED'?'확정':'생성 대기'" /></template></PColumn><PColumn header="파일"><template #body="{data}"><PButton icon="pi pi-download" text :disabled="store.busy||!data.id" :aria-label="(data.label||data.month+'월 master')+' 다운로드'" @click="downloadMaster(data)" /></template></PColumn>
      <template #empty>조회 가능한 마스터가 없습니다.</template>
     </PDataTable>
    </div>
   </div>
  </template>
 </div>
</template>

<style scoped>
.live-management :deep(.p-card-body),.live-management :deep(.p-card-content){display:contents;padding:0}
.live-management :deep(.p-datatable){max-width:100%;min-width:0}
.live-management :deep(.p-datatable-table-container){overflow:auto}
.live-management :deep(.p-inputnumber){max-width:140px}
.live-management :deep(.p-inputnumber-input){width:100%}
.live-management :deep(.p-datatable-row-expansion>td){white-space:normal;padding:0}
.live-management :deep(.p-datatable-row-expansion .mg-reply){padding:24px}
.live-management :deep(.mg-selected-row>td){background:var(--p-primary-50,#eef4ff)}
.live-management :deep(.mg-blue-row>td){background:var(--p-primary-50,#eef4ff)}
.mg-request-fields{display:grid;gap:20px;min-width:0}
.mg-month-button{white-space:normal;justify-content:flex-start;text-align:left;min-height:92px}
.mg-month-button span{display:grid;gap:8px}
.mg-org-picker :deep(.p-button){justify-content:flex-start;white-space:normal;text-align:left}
.mg-org-picker :deep(.p-button)>span{width:100%}
.mg-row-link{padding:0;text-align:left;white-space:normal}
</style>
