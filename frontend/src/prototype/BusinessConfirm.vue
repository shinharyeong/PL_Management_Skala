<script setup>
import PDialog from 'primevue/dialog'
import { onBeforeUnmount } from 'vue'
import { businessConfirmation as confirmation, settleBusinessConfirmation as settle } from './business-confirm.js'
onBeforeUnmount(() => settle(false))
</script>
<template>
  <PDialog :visible="!!confirmation" modal :header="confirmation?.title" :style="{width:'min(440px, calc(100vw - 32px))'}" :draggable="false" :dismissable-mask="false" @update:visible="value => { if(!value) settle(false) }">
    <div v-if="confirmation" class="business-confirm-content"><p>{{confirmation.message}}</p><dl v-if="confirmation.details?.length"><template v-for="(detail,index) in confirmation.details" :key="index"><dt>{{detail.label}}</dt><dd>{{detail.value}}</dd></template></dl></div>
    <template #footer><button type="button" class="btn" autofocus @click="settle(false)">취소</button><button type="button" class="btn primary" @click="settle(true)">{{confirmation?.confirmLabel}}</button></template>
  </PDialog>
</template>
<style>
.business-confirm-content {font-size:13px;color:#565654;line-height:1.7;}
.business-confirm-content p {margin:0 0 14px;white-space:pre-line;}
.business-confirm-content dl {display:grid;grid-template-columns:75px minmax(0,1fr);gap:8px 12px;margin:0;padding:12px;background:#f8f8f7;border-radius:8px;font-size:12px;}
.business-confirm-content dt {color:#8b8b88;}
.business-confirm-content dd {margin:0;white-space:pre-wrap;overflow-wrap:anywhere;max-height:170px;overflow:auto;}
</style>
