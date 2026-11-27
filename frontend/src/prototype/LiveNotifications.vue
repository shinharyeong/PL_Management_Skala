<script setup>
import {ref,watch,onBeforeUnmount,onMounted} from 'vue'
import {usePrototypeStore} from './live-store.js'
import copy from './copy-text.js'
import NotificationCard from './NotificationCard.vue'
import {notificationDate,notificationTime,mergeNotificationGroups} from './presentation.js'
const emit=defineEmits(['navigate']),store=usePrototypeStore()
const type=ref('ALL'),page=ref(null),loading=ref(false),error=ref('')
const readPage=ref(null),readLoading=ref(false),readError=ref(''),readOpen=ref(false),content=ref(null),dock=ref(null),dockStyle=ref({})
const types={ALL:'전체',SUBMISSION:'조직 제출',REPLY:'소명 회신',OVERDUE:'기한 경과'}
const tones={ALL:'gray',SUBMISSION:'blue',REPLY:'purple',OVERDUE:'red'}
let generation=0,readGeneration=0,refreshTimer,observer
function alignDock(){
 if(!content.value)return
 const rect=content.value.getBoundingClientRect()
 if(dock.value)content.value.style.setProperty('--read-dock-space',(dock.value.getBoundingClientRect().height+24)+'px')
 dockStyle.value={left:Math.max(8,rect.left)+'px',width:Math.max(0,Math.min(rect.width,window.innerWidth-Math.max(8,rect.left)-8))+'px'}
}
onMounted(()=>{
 observer=new ResizeObserver(alignDock);observer.observe(content.value);observer.observe(dock.value);alignDock()
 window.addEventListener('resize',alignDock)
 refreshTimer=setInterval(()=>{if(!document.hidden&&!loading.value&&!readLoading.value&&!store.busy){load(false,true);loadRead(false,true)}},30000)
})
onBeforeUnmount(()=>{generation++;readGeneration++;clearInterval(refreshTimer);observer?.disconnect();window.removeEventListener('resize',alignDock)})
function query(read,cursor){
 const params=new URLSearchParams({limit:'100',sort:'LATEST',[read?'readOnly':'unreadOnly']:'true'})
 if(type.value!=='ALL')params.set('type',type.value)
 if(cursor)params.set('cursor',cursor)
 return '/api/v1/notifications?'+params
}
async function load(more=false,quiet=false){
 const g=++generation,cursor=more?page.value?.nextCursor:null
 loading.value=true;error.value=''
 if(!more&&!quiet)page.value=null
 try{
  const result=await store.api(query(false,cursor))
  if(g!==generation)return
  page.value={...result,groups:more?mergeNotificationGroups(page.value?.groups||[],result.groups):result.groups}
  store.unreadCount=result.summary.unreadCount
 }catch(e){if(g===generation&&!quiet){error.value=e.message;store.notify(e.message,'error')}}finally{if(g===generation)loading.value=false}
}
async function loadRead(more=false,quiet=false){
 const g=++readGeneration,cursor=more?readPage.value?.nextCursor:null
 readLoading.value=true;readError.value=''
 try{
  const result=await store.api(query(true,cursor))
  if(g!==readGeneration)return
  readPage.value={...result,groups:more?mergeNotificationGroups(readPage.value?.groups||[],result.groups):result.groups}
 }catch(e){if(g===readGeneration&&!quiet)readError.value=e.message}finally{if(g===readGeneration)readLoading.value=false}
}
watch(type,()=>{readPage.value=null;readOpen.value=false;load();loadRead()},{immediate:true})
async function openNotification(item){
 await store.run(async()=>{
  await store.api('/api/v1/notifications/read',{method:'POST',body:{ids:[item.id]}})
  await Promise.all([store.refreshBadge(),load(false,true),loadRead(false,true)])
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
 <div ref="content" class="notification-inbox">
 <header class="mg-heading"><div><p class="mg-breadcrumb"><span data-copy-key="notificationText.text001">{{ copy.notificationText.text001 }}</span> <span data-copy-key="pages.notifications">{{copy.pages.notifications}}</span></p><h1><span data-copy-key="pages.notifications">{{copy.pages.notifications}}</span></h1></div></header>
 <div class="mg-filter-row">
  <div class="mg-tabs" role="group" aria-label="알림 유형"><button v-for="(label,key) in types" :key="key" class="btn" :class="[type===key?'primary':'','mg-type-'+tones[key]]" :aria-pressed="type===key" @click="type=key">{{label}} {{page?.typeCounts[key]??'—'}}</button></div>

 </div>
 <p v-if="error" class="notice error" role="alert">{{error}}</p><p v-if="loading" class="muted" role="status"><span data-copy-key="notificationText.text004">{{ copy.notificationText.text004 }}</span></p>
 <section v-for="group in page?.groups" :key="group.date" class="mg-notification-group">
  <div class="notification-date-heading"><h2>{{notificationDate(group.date)}}</h2><span>{{group.items.length}}건</span><span v-if="group.unreadCount" class="notification-date-unread">안 읽음 {{group.unreadCount}}</span><span class="notification-date-line" aria-hidden="true"></span></div>
  <NotificationCard v-for="item in group.items" :key="item.id" :item="item" :busy="store.busy" @open="openNotification" />
 </section>
 <p v-if="page&&!page.groups.length&&!loading" class="card mg-empty">안 읽은 알림이 없습니다.</p>
 <button v-if="page?.nextCursor" class="btn" :disabled="loading" @click="load(true)"><span data-copy-key="notificationText.text007">{{ copy.notificationText.text007 }}</span></button>
 <section ref="dock" class="notification-read-dock" :style="dockStyle" aria-label="읽은 알림">
  <button class="notification-read-toggle" type="button" :aria-expanded="readOpen" aria-controls="read-notification-list" @click="readOpen=!readOpen"><span>읽은 알림 <b>{{readPage?.typeCounts[type]??'—'}}</b></span><span class="notification-read-toggle-hint">{{readOpen?'접기':'펼치기'}} <span aria-hidden="true">{{readOpen?'⌄':'⌃'}}</span></span></button>
  <div v-if="readOpen" id="read-notification-list" class="notification-read-list" tabindex="0" aria-label="읽은 알림 목록 · 최신순">
   <p v-if="readError" class="notice error" role="alert">{{readError}} <button class="btn" @click="loadRead()">다시 불러오기</button></p>
   <p v-if="readLoading" class="muted" role="status">불러오는 중입니다.</p>
   <section v-for="group in readPage?.groups" :key="group.date" class="mg-notification-group">
    <div class="notification-date-heading"><h2>{{notificationDate(group.date)}}</h2><span>{{group.items.length}}건</span><span class="notification-date-line" aria-hidden="true"></span></div>
    <NotificationCard v-for="item in group.items" :key="item.id" :item="item" :busy="store.busy" @open="openNotification" />
   </section>
   <p v-if="readPage&&!readPage.groups.length&&!readLoading" class="mg-empty">읽은 알림이 없습니다.</p>
   <button v-if="readPage?.nextCursor" class="btn" :disabled="readLoading" @click="loadRead(true)">더 보기</button>
  </div>
 </section>
 </div>
</template>
