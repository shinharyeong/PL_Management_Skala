<script setup>
import {ref,watch,computed,onBeforeUnmount} from 'vue'
import {usePrototypeStore} from './live-store.js'
import {seoulPeriod,seoulDeadline} from './domain.js'
import {status,saveFile} from './live-api.js'
import {statusTone,versionLabel} from './presentation.js'
import copy from './copy-text.js'
import {revealOrganization} from './reveal-organization.js'
import {annualTargetStatus} from './review-status.js'
import {scenarioLabel} from '../lib/org-review-display.js'
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
const archiveYears=[seoulPeriod().year-1,seoulPeriod().year,seoulPeriod().year+1]
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
   // 와이어프레임 6: 월을 고르면 최신 버전의 변경 이력(일자·항목·소명 사유)을 바로 보여 준다.
   const latest=versions.history?.[0]
   if(latest){const c=++changeGeneration;selectedVersion.value=latest.submissionId;const result=await store.api('/api/v1/submissions/'+latest.submissionId+'/change-history');if(c===changeGeneration&&g===archiveGeneration)changeHistory.value=result}
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
function managerOf(id){const o=store.organizations.find(item=>item.id===id);return o?.managers?.length?o.managers.map(m=>m.name+' · '+m.email).join(', '):'—'}
/** 변경 이력 항목 코드(["REVENUE",["매출","기본료","음성기본료"]])를 '매출 › 기본료 › 음성기본료'로 보여 준다.
 * Args: item 변경 항목. Returns: 계정 경로 문자열(코드를 해석할 수 없으면 항목명). Raises: 없음.
 */
function itemPath(item){try{const path=JSON.parse(item.code)?.[1];if(Array.isArray(path)&&path.length)return path.join(' › ')}catch{};return item.name}
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
  <header v-if="page!=='notifications'" class="mg-heading"><div><p class="mg-breadcrumb"><span data-copy-key="managementText.text001">{{ copy.managementText.text001 }}</span> <span :data-copy-key="'pages.'+page">{{copy.pages[page]}}</span></p><h1><span :data-copy-key="'pages.'+page">{{copy.pages[page]}}</span></h1></div></header>
  <dialog ref="bulkDialog" class="confirm-dialog"><h2><span data-copy-key="managementText.text002">{{ copy.managementText.text002 }}</span> {{bulkSelected.length}}<span data-copy-key="managementText.text003">{{ copy.managementText.text003 }}</span></h2><p><span data-copy-key="managementText.text004">{{ copy.managementText.text004 }}</span></p><div class="actions"><button class="btn" @click="bulkDialog.close()"><span data-copy-key="managementText.text005">{{ copy.managementText.text005 }}</span></button><button class="btn primary" :disabled="store.busy" @click="bulkAccept"><span data-copy-key="managementText.text006">{{ copy.managementText.text006 }}</span></button></div></dialog>
  <LiveNotifications v-if="page==='notifications'" @navigate="emit('navigate',$event)" />

  <template v-else-if="page==='annual'">
   <p class="mg-description"><span data-copy-key="managementText.text007">{{ copy.managementText.text007 }}</span></p>
   <div class="mg-section-heading"><h2><span data-copy-key="managementText.text008">{{ copy.managementText.text008 }}</span></h2><div class="mg-actions"><label class="mg-field"><span data-copy-key="managementText.text009">{{ copy.managementText.text009 }}</span><input v-model="year" type="number" min="2000" max="9999"></label><button class="btn primary" @click="emit('navigate','annual-request')"><span data-copy-key="managementText.text010">{{ copy.managementText.text010 }}</span></button></div></div>
   <section class="card mg-annual-progress" aria-labelledby="annual-master-title"><div class="mg-section-heading"><h2 id="annual-master-title">{{year}}<span data-copy-key="managementText.text011">{{ copy.managementText.text011 }}</span></h2><span class="badge" :class="annualMasters?.state==='CONFIRMED'?'mg-status-blue':'mg-status-amber'">{{annualMasters?.state==='CONFIRMED'?'확정':annualMasters?.state==='NO_REQUEST'?'요청 전':annualMasters?.state==='WAITING'?'확정 대기':'조회 중'}}</span></div><p v-if="annualMasterError" class="error" role="alert"><span data-copy-key="managementText.text012">{{ copy.managementText.text012 }}</span> {{annualMasterError}}</p><template v-else-if="annualMasters"><p v-if="!annualMasters.items.length" class="mg-small-note">{{annualMasters.state==='NO_REQUEST'?'선택한 연도의 연간 계획 요청이 없습니다.':'요청 대상 조직의 제출본을 모두 수락하면 연간 master가 자동 생성됩니다.'}}</p><template v-else><p class="mg-small-note"><span data-copy-key="managementText.text013">{{ copy.managementText.text013 }}</span> {{annualMasters.items[0].label}} <span data-copy-key="managementText.text014">{{ copy.managementText.text014 }}</span></p><div class="mg-table-scroll"><table class="mg-table"><thead><tr><th><span data-copy-key="managementText.text015">{{ copy.managementText.text015 }}</span></th><th><span data-copy-key="managementText.text016">{{ copy.managementText.text016 }}</span></th><th><span data-copy-key="managementText.text017">{{ copy.managementText.text017 }}</span></th><th><span data-copy-key="managementText.text018">{{ copy.managementText.text018 }}</span></th><th><span data-copy-key="managementText.text019">{{ copy.managementText.text019 }}</span></th></tr></thead><tbody><tr v-for="(master,index) in annualMasters.items" :key="master.id"><td><strong>{{master.label}}</strong><span v-if="index===0" class="badge mg-status-blue"><span data-copy-key="managementText.text020">{{ copy.managementText.text020 }}</span></span></td><td>{{dateTime(master.createdAt)}}</td><td>{{master.createdByName}}</td><td>{{master.organizationCount}}<span data-copy-key="managementText.text021">{{ copy.managementText.text021 }}</span></td><td><button class="btn" :disabled="store.busy" @click="downloadAnnualMaster(master)"><span data-copy-key="managementText.text022">{{ copy.managementText.text022 }}</span></button></td></tr></tbody></table></div></template></template></section>
   <section class="card mg-annual-progress"><h2><span data-copy-key="managementText.text023">{{ copy.managementText.text023 }}</span></h2><p class="mg-small-note">{{year}}<span data-copy-key="managementText.text024">{{ copy.managementText.text024 }}</span> {{acceptedCount}}/{{store.annualRows.length}}</p>
    <div class="mg-actions"><button class="btn primary" :disabled="store.busy||!bulkSelected.length" @click="bulkDialog.showModal()"><span data-copy-key="managementText.text025">{{ copy.managementText.text025 }}</span> {{bulkSelected.length}}<span data-copy-key="managementText.text026">{{ copy.managementText.text026 }}</span></button><span class="muted"><span data-copy-key="managementText.text027">{{ copy.managementText.text027 }}</span></span></div><div class="mg-table-scroll"><table class="mg-table"><thead><tr><th><input v-model="allPending" type="checkbox" aria-label="검토 대기 조직 전체 선택" :disabled="store.busy"></th><th><span data-copy-key="managementText.text028">{{ copy.managementText.text028 }}</span></th><th><span data-copy-key="managementText.text029">{{ copy.managementText.text029 }}</span></th><th><span data-copy-key="managementText.text030">{{ copy.managementText.text030 }}</span></th><th><span data-copy-key="managementText.text031">{{ copy.managementText.text031 }}</span></th><th>수신 담당자</th><th><span data-copy-key="managementText.text032">{{ copy.managementText.text032 }}</span></th></tr></thead><tbody>
     <template v-for="target in store.annualRows" :key="target.organization.id">
      <tr :id="'annual-org-'+target.organization.id" class="organization-reveal-row" :class="{'mg-selected-row':expanded===target.organization.id}"><td><input v-model="bulkSelected" type="checkbox" :value="target.organization.id" :disabled="store.busy||target.latestSubmission?.status!=='PENDING'" :aria-label="target.organization.name+' 일괄 수락 선택'"></td><td><button class="mg-row-link" :aria-expanded="expanded===target.organization.id" @click="toggleRow(target)">{{target.organization.name}}</button></td><td>{{target.organization.code}}</td><td>{{dateTime(target.requestedAt)}}</td><td>{{dateTime(target.dueAt)}}</td><td>{{managerOf(target.organization.id)}}</td><td><span class="badge" :class="'mg-status-'+annualTargetStatus(target).tone">{{annualTargetStatus(target).label}}</span><small v-if="target.selected && target.latestSubmission" class="mg-submission-state">{{status(target.displayStatus)}}</small></td></tr>
      <tr v-if="expanded===target.organization.id" class="mg-expanded"><td colspan="7"><article v-if="reply" class="mg-reply"><h3>{{reply.submittedBy?.name||'—'}} · {{reply.organization.name}}</h3><small class="muted">{{dateTime(reply.submittedAt)}} <span data-copy-key="managementText.text033">{{ copy.managementText.text033 }}</span></small><p>{{reply.replyMessage||'회신 메시지 없음'}}</p><button class="btn mg-file-btn" @click="store.run(()=>store.downloadSubmission(reply.id,reply.file.name))">{{reply.file.name}} ↓</button><div class="mg-history-note"><strong><span data-copy-key="managementText.text034">{{ copy.managementText.text034 }}</span></strong><p>{{dateTime(target.requestedAt)}} <span data-copy-key="managementText.text035">{{ copy.managementText.text035 }}</span></p><p>{{dateTime(reply.submittedAt)}} · {{reply.label}} · {{reply.file.name}}</p></div><label class="mg-field"><span data-copy-key="managementText.text036">{{ copy.managementText.text036 }}</span><textarea v-model="comment" rows="3" :placeholder="reply.status==='PENDING' ? '회신에 대한 경영기획 코멘트를 입력하세요.' : '저장된 코멘트가 없습니다.'" :readonly="reply.status!=='PENDING'" aria-describedby="annual-comment-help"></textarea></label><p id="annual-comment-help" class="mg-small-note" role="status">{{ reply.status==='PENDING' ? '코멘트는 수락 또는 재요청할 때 함께 저장됩니다.' : '현재 상태: '+status(reply.status)+' · 처리가 완료된 제출본입니다. 코멘트 추가·수정은 현재 서버에서 지원하지 않습니다.' }}</p><div class="mg-actions"><button class="btn" :disabled="store.busy||!comment.trim()||reply.status!=='PENDING'" @click="decide('REJECT')"><span data-copy-key="managementText.text037">{{ copy.managementText.text037 }}</span></button><button class="btn primary" :disabled="store.busy||reply.status!=='PENDING'" @click="decide('APPROVE')">{{reply.status==='APPROVED'?'수락 완료':'수락'}}</button></div></article><p v-else class="mg-empty">{{target.latestSubmission?'회신을 불러오는 중입니다.':target.selected?'아직 회신한 내용이 없습니다.':'아직 연간 계획 요청을 보내지 않았습니다.'}}</p></td></tr>
     </template><tr v-if="!store.annualRows.length"><td colspan="7" class="mg-empty"><span data-copy-key="managementText.text038">{{ copy.managementText.text038 }}</span></td></tr>
    </tbody></table></div><p class="mg-small-note"><span data-copy-key="managementText.text039">{{ copy.managementText.text039 }}</span></p>
   </section>
  </template>

  <template v-else-if="page==='annual-request'">
   <p class="mg-description"><span data-copy-key="managementText.text040">{{ copy.managementText.text040 }}</span></p>
   <form class="card mg-request-form" @submit.prevent="send"><div class="mg-section-heading"><h2><span data-copy-key="managementText.text010">{{ copy.managementText.text010 }}</span></h2><label class="mg-field"><span data-copy-key="managementText.text009">{{ copy.managementText.text009 }}</span><input v-model="year" type="number" min="2000" max="9999" required></label></div>
    <div class="mg-table-scroll"><table class="mg-table"><thead><tr><th><input v-model="allRecipients" type="checkbox" aria-label="모든 조직 선택"></th><th><span data-copy-key="managementText.text028">{{ copy.managementText.text028 }}</span></th><th><span data-copy-key="managementText.text029">{{ copy.managementText.text029 }}</span></th><th><span data-copy-key="managementText.text041">{{ copy.managementText.text041 }}</span></th></tr></thead><tbody><tr v-for="organization in store.organizations" :key="organization.id"><td><input v-model="recipients" type="checkbox" :value="organization.id" :aria-label="organization.name+' 선택'"></td><td>{{organization.name}}</td><td>{{organization.code}}</td><td>{{organization.author}}</td></tr></tbody></table></div>
    <label class="mg-field"><span data-copy-key="managementText.text031">{{ copy.managementText.text031 }}</span><input v-model="due" type="datetime-local" required></label><label class="mg-field"><span data-copy-key="managementText.text042">{{ copy.managementText.text042 }}</span><textarea v-model="message" rows="4" required></textarea></label><p v-if="formError" class="error" role="alert">{{formError}}</p><div class="mg-actions"><button class="btn primary mg-request-submit" type="submit" :disabled="store.busy||!recipients.length">{{recipients.length}}<span data-copy-key="managementText.text043">{{ copy.managementText.text043 }}</span></button><button class="btn" type="button" @click="emit('navigate','annual')"><span data-copy-key="managementText.text005">{{ copy.managementText.text005 }}</span></button></div>
   </form><button class="btn mg-back-link" @click="emit('navigate','annual')"><span data-copy-key="managementText.text044">{{ copy.managementText.text044 }}</span></button>
  </template>

  <template v-else-if="page==='organizations'">
   <p class="mg-description"><span data-copy-key="managementText.text045">{{ copy.managementText.text045 }}</span></p>
   <div class="mg-archive"><aside class="card mg-org-picker"><h2><span data-copy-key="managementText.text046">{{ copy.managementText.text046 }}</span></h2><input v-model="orgSearch" type="search" placeholder="조직명·코드 검색" aria-label="조직 검색"><button v-for="organization in visibleOrganizations" :key="organization.id" :class="{selected:org===organization.id}" :aria-pressed="org===organization.id" @click="selectOrganization(organization.id)">{{organization.name}}<small>{{organization.code}}</small></button><p v-if="!visibleOrganizations.length" class="muted"><span data-copy-key="managementText.text047">{{ copy.managementText.text047 }}</span></p></aside>
    <div class="mg-archive-main"><div class="mg-section-heading"><h2>{{selectedOrg?.name||'조직을 선택해 주세요'}} · {{archiveYear}}</h2><div class="mg-year-tabs" role="group" aria-label="연도"><button v-for="y in archiveYears" :key="y" type="button" :class="{selected:archiveYear===y}" :aria-pressed="archiveYear===y" @click="changeArchiveYear(y)">{{y}}</button></div></div><p class="mg-small-note"><span data-copy-key="managementText.text049">{{ copy.managementText.text049 }}</span> {{selectedOrg?.author||'—'}}</p>
     <div class="mg-month-grid"><button v-for="m in 12" :key="m" class="card" :class="{selected:month===m}" :aria-pressed="month===m" @click="selectMonth(m)"><strong>{{m}}<span data-copy-key="managementText.text050">{{ copy.managementText.text050 }}</span></strong><small class="muted"><span data-copy-key="managementText.text051">{{ copy.managementText.text051 }}</span> {{archive?.months.find(item=>item.month===m)?.finalLabel||'—'}}</small></button></div>
     <p v-if="archiveLoading" class="muted" role="status"><span data-copy-key="managementText.text052">{{ copy.managementText.text052 }}</span></p><h2>{{month}}<span data-copy-key="managementText.text053">{{ copy.managementText.text053 }}</span></h2>
     <div class="mg-table-scroll"><table class="mg-table"><thead><tr><th><span data-copy-key="managementText.text054">{{ copy.managementText.text054 }}</span></th><th><span data-copy-key="managementText.text055">{{ copy.managementText.text055 }}</span></th><th><span data-copy-key="managementText.text019">{{ copy.managementText.text019 }}</span></th><th><span data-copy-key="managementText.text056">{{ copy.managementText.text056 }}</span></th></tr></thead><tbody><tr v-for="version in archive?.history" :key="version.submissionId" :class="{'mg-blue-row':selectedVersion===version.submissionId}"><td><button class="mg-row-link" @click="showChanges(version)">{{versionLabel(version.label)}}</button></td><td>{{dateTime(version.lastModifiedAt)}}</td><td><button class="mg-icon-button" :disabled="store.busy" :aria-label="(version.file?.name||version.label)+' 다운로드'" @click="store.run(()=>store.downloadSubmission(version.submissionId,version.file?.name))">↓</button></td><td><span class="badge" :class="'mg-status-'+statusTone(status(version.status))">{{status(version.status)}}</span></td></tr><tr v-if="archive&&!archive.history.length"><td colspan="4" class="mg-empty"><span data-copy-key="managementText.text057">{{ copy.managementText.text057 }}</span></td></tr></tbody></table></div>
     <template v-if="changeHistory"><h3><span data-copy-key="managementText.text058">{{ copy.managementText.text058 }}</span></h3><div class="mg-table-scroll"><table class="mg-table mg-changes-table"><thead><tr><th><span data-copy-key="managementText.text059">{{ copy.managementText.text059 }}</span></th><th><span data-copy-key="managementText.text062">{{ copy.managementText.text062 }}</span></th><th><span data-copy-key="managementText.text063">{{ copy.managementText.text063 }}</span></th></tr></thead><tbody><template v-for="date in changeHistory.dates" :key="date.changedAt"><tr v-for="(item,index) in date.items" :key="item.no"><td v-if="index===0" :rowspan="date.items.length" class="mg-change-date">{{dateTime(date.changedAt)}}<small v-if="date.state==='RE_EXPLAINED'" class="mg-change-state">재소명</small></td><td><span class="mg-change-no">{{index+1}}</span>{{itemPath(item)}} · {{item.month}}월 {{scenarioLabel(item.scenario)}}</td><td>{{item.explanation||'—'}}</td></tr></template><tr v-if="!changeHistory.dates.length"><td colspan="3" class="mg-empty"><span data-copy-key="managementText.text065">{{ copy.managementText.text065 }}</span></td></tr></tbody></table></div></template>
     <h2><span data-copy-key="managementText.text066">{{ copy.managementText.text066 }}</span></h2><div class="mg-table-scroll"><table class="mg-table"><thead><tr><th><span data-copy-key="managementText.text067">{{ copy.managementText.text067 }}</span></th><th><span data-copy-key="managementText.text015">{{ copy.managementText.text015 }}</span></th><th><span data-copy-key="managementText.text068">{{ copy.managementText.text068 }}</span></th><th><span data-copy-key="managementText.text056">{{ copy.managementText.text056 }}</span></th><th><span data-copy-key="managementText.text019">{{ copy.managementText.text019 }}</span></th></tr></thead><tbody><tr v-for="master in masters" :key="master.month"><td>{{master.month}}<span data-copy-key="managementText.text050">{{ copy.managementText.text050 }}</span></td><td>{{master.label}}</td><td>{{dateTime(master.createdAt)}}</td><td><span :class="master.state==='CONFIRMED'?'mg-green':'muted'">{{master.state==='CONFIRMED'?'확정':'생성 대기'}}</span></td><td><button class="mg-icon-button" :disabled="store.busy||!master.id" :aria-label="master.label+' 다운로드'" @click="downloadMaster(master)">↓</button></td></tr></tbody></table></div>
    </div>
   </div>
  </template>


 </div>
</template>
