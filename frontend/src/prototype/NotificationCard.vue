<script setup>
import {notificationWorkflowStatus} from './review-status.js'
import {notificationTime} from './presentation.js'
defineProps({item:Object,busy:Boolean})
const emit=defineEmits(['open'])
const types={SUBMISSION:'조직 제출',REPLY:'소명 회신',OVERDUE:'기한 경과'}
</script>
<template>
  <article class="card mg-notification" :class="[{'mg-read':item.read,'mg-unread':!item.read},'notification-kind-'+item.type.toLowerCase()]">
   <div class="notification-type-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><template v-if="item.type==='SUBMISSION'"><path d="M12 16V4m-4 4 4-4 4 4M5 15v4a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-4"/></template><template v-else-if="item.type==='REPLY'"><path d="M20 11a8 8 0 0 1-8 8H5l-3 3v-7a8 8 0 1 1 18-4Z"/><path d="M7 9h10M7 13h6"/></template><template v-else><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></template></svg></div>
   <div class="mg-notification-body">
    <div class="mg-notification-meta"><span class="notification-type-label">{{types[item.type]||item.type}}</span><span class="notification-read-label" :class="item.read?'is-read':'is-unread'"><span v-if="!item.read" class="notification-unread-indicator" aria-hidden="true"></span>{{item.read?'읽음':'안 읽음'}}</span></div>
    <h3>{{item.title}}</h3>
    <div class="notification-source"><strong>{{item.organization?.name}}</strong><span class="notification-org-code">{{item.organization?.code}}</span><span v-if="item.actor?.name" class="notification-actor">{{item.actor.name}} 회신</span></div>
   </div>
   <div class="mg-notification-action"><small :class="item.type==='OVERDUE'?'mg-red':'muted'">{{notificationTime(item)}}</small><div class="notification-action-line"><span class="badge" :class="'mg-status-'+notificationWorkflowStatus(item).tone">{{notificationWorkflowStatus(item).label}}</span><button class="btn" :disabled="busy" @click="emit('open',item)">{{notificationWorkflowStatus(item).label==='검토 필요'?'검토하기':'현황 보기'}} <span aria-hidden="true">→</span></button></div></div>
  </article>
</template>
