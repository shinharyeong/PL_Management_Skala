<script setup>
import {ref,watch,onBeforeUnmount} from 'vue'
import {usePrototypeStore} from './live-store.js'
import copy from './copy-text.js'
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
async function readAll(){if(await store.run(()=>store.api('/api/v1/notifications/read',{method:'POST',body:{all:true}})))await load()}
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
 <header class="mg-heading"><div><p class="mg-breadcrumb"><span data-copy-key="notificationText.text001">{{ copy.notificationText.text001 }}</span> <span data-copy-key="pages.notifications">{{copy.pages.notifications}}</span></p><h1><span data-copy-key="pages.notifications">{{copy.pages.notifications}}</span></h1></div><button class="btn" :disabled="store.busy||!page?.summary.unreadCount" @click="readAll">모두 읽음 처리</button></header>
 <p class="mg-description"><span data-copy-key="notificationText.text002">{{ copy.notificationText.text002 }}</span></p>
 <div class="mg-kpis" role="group" aria-label="알림 요약">
  <div class="card mg-kpi"><small>안 읽은 알림</small><strong class="mg-kpi-red">{{page?.summary.unreadCount??'—'}}<span>건</span></strong></div>
  <div class="card mg-kpi"><small>회신 검토 대기</small><strong class="mg-kpi-amber">{{page?.summary.replyPendingCount??'—'}}<span>건</span></strong></div>
  <div class="card mg-kpi"><small>기한 경과 조직</small><strong class="mg-kpi-red">{{page?.summary.overdueOrganizationCount??'—'}}<span>곳</span></strong></div>
 </div>
 <div class="mg-filter-row">
  <div class="mg-tabs" role="group" aria-label="알림 유형"><button v-for="(label,key) in types" :key="key" class="btn" :class="[type===key?'primary':'','mg-type-'+tones[key]]" :aria-pressed="type===key" @click="type=key">{{label}} {{page?.typeCounts[key]??'—'}}</button></div>
  <label class="mg-checkbox"><input v-model="unreadOnly" type="checkbox"><span data-copy-key="notificationText.text003">{{ copy.notificationText.text003 }}</span></label>
 </div>
 <p v-if="error" class="notice error" role="alert">{{error}}</p><p v-if="loading" class="muted" role="status"><span data-copy-key="notificationText.text004">{{ copy.notificationText.text004 }}</span></p>
 <section v-for="group in page?.groups" :key="group.date" class="mg-notification-group">
  <h2>{{notificationDate(group.date)}} {{group.items.length}}<span data-copy-key="notificationText.text005">{{ copy.notificationText.text005 }}</span> {{group.unreadCount}}</h2>
  <article v-for="item in group.items" :key="item.id" class="card mg-notification" :class="{'mg-read':item.read,'mg-unread':!item.read}">
   <div class="mg-notification-body"><div class="mg-notification-meta"><img src="/assets/prototype-unread-dot.svg" alt="" width="8" height="8" class="mg-unread-dot" :class="{hidden:item.read}"><span :class="'mg-'+tones[item.type]">{{types[item.type]||item.type}}</span><span class="notification-read-label" :class="item.read?'is-read':'is-unread'">{{item.read?'읽음':'안 읽음'}}</span><span v-if="item.type==='SUBMISSION'" class="badge" :class="'mg-status-'+submissionReviewStatus(submissions[item.link?.query?.submissionId]).tone">{{submissionReviewStatus(submissions[item.link?.query?.submissionId]).label}}</span></div><h3 :class="{'mg-red':item.type==='OVERDUE'}">{{item.title}}</h3><p>{{item.organization?.name}} · {{item.organization?.code}}</p><small class="muted">{{item.actor?.name ? item.actor.name+' 회신' : '회신 대기'}}</small></div>
   <div class="mg-notification-action"><small :class="item.type==='OVERDUE'?'mg-red':'muted'">{{notificationTime(item)}}</small><button class="btn" :class="{primary:item.type!=='OVERDUE'}" :disabled="store.busy" @click="openNotification(item)">{{item.type==='OVERDUE'?'현황 보기':item.type==='SUBMISSION'&&submissionReviewStatus(submissions[item.link?.query?.submissionId]).done?'처리 내역 보기':'검토하기'}}</button></div>
  </article>
 </section>
 <p v-if="page&&!page.groups.length&&!loading" class="card mg-empty"><span data-copy-key="notificationText.text006">{{ copy.notificationText.text006 }}</span></p>
 <button v-if="page?.nextCursor" class="btn" :disabled="loading" @click="load(true)"><span data-copy-key="notificationText.text007">{{ copy.notificationText.text007 }}</span></button>
</template>
