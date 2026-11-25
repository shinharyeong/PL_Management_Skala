<script setup>
import {ref,watch,onBeforeUnmount} from 'vue'
import {usePrototypeStore} from './live-store.js'
import copy from './ui-copy.json'
import {submissionReviewStatus} from './review-status.js'
import {notificationDate,notificationTime,mergeNotificationGroups} from './presentation.js'
import PButton from 'primevue/button'
import PCard from 'primevue/card'
import PCheckbox from 'primevue/checkbox'
import PTag from 'primevue/tag'
const emit=defineEmits(['navigate']),store=usePrototypeStore()
const type=ref('ALL'),unreadOnly=ref(false),page=ref(null),loading=ref(false),error=ref(''),submissions=ref({})
const types={ALL:'전체',SUBMISSION:'조직 제출',REPLY:'소명 회신',OVERDUE:'기한 경과'}
const tones={ALL:'gray',SUBMISSION:'blue',REPLY:'purple',OVERDUE:'red'}
let generation=0
onBeforeUnmount(()=>generation++)
async function load(more=false){
 const g=++generation, cursor=more?page.value?.nextCursor:null
 loading.value=true;error.value=''
 if(!more)page.value=null
 try{
  const params=new URLSearchParams({limit:'100',sort:'LATEST',unreadOnly:String(unreadOnly.value)})
  if(type.value!=='ALL')params.set('type',type.value)
  if(cursor)params.set('cursor',cursor)
  let result=await store.api('/api/v1/notifications?'+params)
  const submitted=[]
  const ids=[...new Set([...(more?page.value?.groups||[]:[]),...result.groups].flatMap(group=>group.items).filter(item=>item.type==='SUBMISSION').map(item=>item.link?.query?.submissionId).filter(Boolean))]
  for(let offset=0;offset<ids.length;offset+=4){
    submitted.push(...await Promise.all(ids.slice(offset,offset+4).map(id=>store.api('/api/v1/submissions/'+id))))
    if(g!==generation)return
  }
  submissions.value=Object.fromEntries(submitted.map(s=>[s.id,s]))
  const reviewed=result.groups.flatMap(group=>group.items).filter(item=>item.type==='SUBMISSION'&&!item.read&&submissionReviewStatus(submissions.value[item.link?.query?.submissionId]).done).map(item=>item.id)
  if(reviewed.length){await store.api('/api/v1/notifications/read',{method:'POST',body:{ids:reviewed}});result=await store.api('/api/v1/notifications?'+params)}
  if(g!==generation)return
  page.value={...result,groups:more?mergeNotificationGroups(page.value?.groups||[],result.groups):result.groups}
  store.unreadCount=result.summary.unreadCount
 }catch(e){if(g===generation){error.value=e.message;store.notify(e.message,'error')}}finally{if(g===generation)loading.value=false}
}
watch([type,unreadOnly],()=>load(),{immediate:true})
async function openNotification(item){
 await store.run(async()=>{
  await store.api('/api/v1/notifications/read',{method:'POST',body:{ids:[item.id]}})
  await store.refreshBadge()
  const query=item.link?.query||{}
  store.activeOrg=query.organizationId||item.organization?.id||''
  const request=query.requestId?await store.api('/api/v1/submission-requests/'+query.requestId):null
  store.notificationTarget={...query,organizationId:store.activeOrg,year:request?.request.year,month:request?.request.month}
  if(request?.request.type==='ANNUAL_PLAN'||item.link?.path?.includes('annual-plan')){emit('navigate','annual');return}
  await store.loadMonthly();emit('navigate','monthly')
 })
}
</script>
<template>
 <header class="mg-heading"><div><p class="mg-breadcrumb">경영기획팀 / {{copy.pages.notifications}}</p><h1>{{copy.pages.notifications}}</h1></div></header>
 <p class="mg-description">조직 제출, 소명 회신, 기한 경과 알림을 확인합니다.</p>
 <div class="mg-filter-row">
  <div class="mg-tabs" role="group" aria-label="알림 유형"><PButton v-for="(label,key) in types" :key="key" class="btn" :severity="type===key?undefined:'secondary'" :outlined="type!==key" :class="[type===key?'primary':'','mg-type-'+tones[key]]" :aria-pressed="type===key" @click="type=key">{{label}} {{page?.typeCounts[key]??'—'}}</PButton></div>
  <label class="mg-checkbox"><PCheckbox v-model="unreadOnly" inputId="notifications-unread" binary />안 읽은 알림만</label>
 </div>
 <p v-if="error" class="notice error" role="alert">{{error}}</p><p v-if="loading" class="muted" role="status">알림을 불러오는 중입니다.</p>
 <section v-for="group in page?.groups" :key="group.date" class="mg-notification-group">
  <h2>{{notificationDate(group.date)}} {{group.items.length}}건 · 안 읽음 {{group.unreadCount}}</h2>
  <PCard v-for="item in group.items" :key="item.id" role="article" class="card mg-notification" :class="{'mg-read':item.read,'mg-unread':!item.read}"><template #content>
   <div class="mg-notification-body"><div class="mg-notification-meta"><img src="/assets/prototype-unread-dot.svg" alt="" width="8" height="8" class="mg-unread-dot" :class="{hidden:item.read}"><PTag :class="'mg-'+tones[item.type]" :severity="item.type==='OVERDUE'?'danger':item.type==='REPLY'?'contrast':'info'" :value="types[item.type]||item.type" /><PTag class="notification-read-label" :class="item.read?'is-read':'is-unread'" :severity="item.read?'secondary':'info'" :value="item.read?'읽음':'안 읽음'" /><PTag v-if="item.type==='SUBMISSION'" class="badge" :severity="({blue:'info',amber:'warn',red:'danger',gray:'secondary'})[submissionReviewStatus(submissions[item.link?.query?.submissionId]).tone]" :class="'mg-status-'+submissionReviewStatus(submissions[item.link?.query?.submissionId]).tone" :value="submissionReviewStatus(submissions[item.link?.query?.submissionId]).label" /></div><h3 :class="{'mg-red':item.type==='OVERDUE'}">{{item.title}}</h3><p>{{item.organization?.name}} · {{item.organization?.code}}</p><small class="muted">{{item.actor?.name ? item.actor.name+' 회신' : '회신 대기'}}</small></div>
   <div class="mg-notification-action"><small :class="item.type==='OVERDUE'?'mg-red':'muted'">{{notificationTime(item)}}</small><PButton class="btn" :severity="item.type==='OVERDUE'?'secondary':undefined" :class="{primary:item.type!=='OVERDUE'}" :disabled="store.busy" @click="openNotification(item)">{{item.type==='OVERDUE'?'현황 보기':item.type==='SUBMISSION'&&submissionReviewStatus(submissions[item.link?.query?.submissionId]).done?'처리 내역 보기':'검토하기'}}</PButton></div>
  </template></PCard>
 </section>
 <p v-if="page&&!page.groups.length&&!loading" class="card mg-empty">조건에 맞는 알림이 없습니다.</p>
 <PButton v-if="page?.nextCursor" class="btn" severity="secondary" :disabled="loading" @click="load(true)">알림 더 보기</PButton>
</template>

<style scoped>
.mg-notification :deep(.p-card-body),.mg-notification :deep(.p-card-content){display:contents;padding:0}
.mg-checkbox{display:inline-flex;align-items:center;gap:.5rem}
.mg-notification-meta :deep(.p-tag){white-space:normal}
</style>
