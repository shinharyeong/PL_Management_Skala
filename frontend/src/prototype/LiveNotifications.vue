<script setup>
import {ref,watch,onBeforeUnmount,onMounted} from 'vue'
import {usePrototypeStore} from './live-store.js'
import copy from './copy-text.js'
import {submissionReviewStatus,notificationWorkflowStatus} from './review-status.js'
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
let refreshTimer
onMounted(()=>{refreshTimer=setInterval(()=>{if(!document.hidden&&!loading.value&&!store.busy)load(false,true)},30000)})
onBeforeUnmount(()=>{generation++;clearInterval(refreshTimer)})
async function load(more=false,quiet=false){
 const g=++generation, cursor=more?page.value?.nextCursor:null
 loading.value=true;error.value=''
 if(!more)page.value=null
 try{
  const params=new URLSearchParams({limit:'100',sort:'LATEST',unreadOnly:String(unreadOnly.value)})
  if(type.value!=='ALL')params.set('type',type.value)
  if(cursor)params.set('cursor',cursor)
  let result=await store.api('/api/v1/notifications?'+params)
  if(g!==generation)return
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
 }catch(e){if(g===generation){if(!quiet){error.value=e.message;store.notify(e.message,'error')}}}finally{if(g===generation)loading.value=false}
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
 <header class="mg-heading"><div><p class="mg-breadcrumb"><span data-copy-key="notificationText.text001">{{ copy.notificationText.text001 }}</span> <span data-copy-key="pages.notifications">{{copy.pages.notifications}}</span></p><h1><span data-copy-key="pages.notifications">{{copy.pages.notifications}}</span></h1></div></header>
 <div class="mg-filter-row">
  <div class="mg-tabs" role="group" aria-label="알림 유형"><button v-for="(label,key) in types" :key="key" class="btn" :class="[type===key?'primary':'','mg-type-'+tones[key]]" :aria-pressed="type===key" @click="type=key">{{label}} {{page?.typeCounts[key]??'—'}}</button></div>
  <label class="mg-checkbox"><input v-model="unreadOnly" type="checkbox"><span data-copy-key="notificationText.text003">{{ copy.notificationText.text003 }}</span></label>
 </div>
 <p v-if="error" class="notice error" role="alert">{{error}}</p><p v-if="loading" class="muted" role="status"><span data-copy-key="notificationText.text004">{{ copy.notificationText.text004 }}</span></p>
 <section v-for="group in page?.groups" :key="group.date" class="mg-notification-group">
  <div class="notification-date-heading"><h2>{{notificationDate(group.date)}}</h2><span>{{group.items.length}}건</span><span v-if="group.unreadCount" class="notification-date-unread">안 읽음 {{group.unreadCount}}</span><span class="notification-date-line" aria-hidden="true"></span></div>
  <article v-for="item in group.items" :key="item.id" class="card mg-notification" :class="[{'mg-read':item.read,'mg-unread':!item.read},'notification-kind-'+item.type.toLowerCase()]">
   <div class="notification-type-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><template v-if="item.type==='SUBMISSION'"><path d="M12 16V4m-4 4 4-4 4 4M5 15v4a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-4"/></template><template v-else-if="item.type==='REPLY'"><path d="M20 11a8 8 0 0 1-8 8H5l-3 3v-7a8 8 0 1 1 18-4Z"/><path d="M7 9h10M7 13h6"/></template><template v-else><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></template></svg></div>
   <div class="mg-notification-body">
    <div class="mg-notification-meta"><span class="notification-type-label">{{types[item.type]||item.type}}</span><span class="notification-read-label" :class="item.read?'is-read':'is-unread'"><span v-if="!item.read" class="notification-unread-indicator" aria-hidden="true"></span>{{item.read?'읽음':'안 읽음'}}</span></div>
    <h3>{{item.title}}</h3>
    <div class="notification-source"><strong>{{item.organization?.name}}</strong><span class="notification-org-code">{{item.organization?.code}}</span><span v-if="item.actor?.name" class="notification-actor">{{item.actor.name}} 회신</span></div>
   </div>
   <div class="mg-notification-action"><small :class="item.type==='OVERDUE'?'mg-red':'muted'">{{notificationTime(item)}}</small><div class="notification-action-line"><span class="badge" :class="'mg-status-'+notificationWorkflowStatus(item).tone">{{notificationWorkflowStatus(item).label}}</span><button class="btn" :disabled="store.busy" @click="openNotification(item)">{{notificationWorkflowStatus(item).label==='검토 필요'?'검토하기':'현황 보기'}} <span aria-hidden="true">→</span></button></div></div>
  </article>
 </section>
 <p v-if="page&&!page.groups.length&&!loading" class="card mg-empty"><span data-copy-key="notificationText.text006">{{ copy.notificationText.text006 }}</span></p>
 <button v-if="page?.nextCursor" class="btn" :disabled="loading" @click="load(true)"><span data-copy-key="notificationText.text007">{{ copy.notificationText.text007 }}</span></button>
</template>
