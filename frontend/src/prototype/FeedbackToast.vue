<script setup>
import copy from './copy-text.js'
import {computed,watch,onBeforeUnmount} from 'vue'
import {usePrototypeStore} from './live-store.js'
const store=usePrototypeStore(),toast=computed(()=>store.toast)
const parts=computed(()=>toast.value?.message.split(' · ')||[])
let timer
watch(()=>toast.value?.id,()=>{
 clearTimeout(timer)
 const current=toast.value
 if(current&&current.tone!=='error')timer=setTimeout(()=>store.dismissToast(current.id),7000)
})
onBeforeUnmount(()=>clearTimeout(timer))
</script>
<template>
 <Teleport to="body"><div class="feedback-toast-host" :aria-live="toast?.tone==='error'?'assertive':'polite'" aria-atomic="true"><section v-if="toast" :key="toast.id" class="feedback-toast" :class="{'feedback-toast-error':toast.tone==='error'}" :role="toast.tone==='error'?'alert':'status'" aria-label="처리 결과"><div class="feedback-toast-content"><strong>{{toast.tone==='error'?'확인이 필요합니다':'안내'}}</strong><p>{{parts[0]}}</p><details v-if="parts.length>1"><summary><span data-copy-key="feedbackText.text001">{{ copy.feedbackText.text001 }}</span></summary><p>{{parts.slice(1).join(' · ')}}</p></details></div><button type="button" aria-label="안내 닫기" @click="store.dismissToast(toast.id)"><span data-copy-key="feedbackText.text002">{{ copy.feedbackText.text002 }}</span></button></section></div></Teleport>
</template>
<style>
.feedback-toast-host{position:fixed;z-index:1000;bottom:max(24px,env(safe-area-inset-bottom));left:50%;transform:translateX(-50%);width:min(620px,calc(100% - 32px));pointer-events:none}
.feedback-toast{display:flex;align-items:flex-start;gap:20px;padding:18px 20px;border:1px solid #cececd;border-left:6px solid #82827e;border-radius:14px;background:#f4f4f3;color:#424240;box-shadow:0 12px 48px #20202055;pointer-events:auto;max-height:40dvh;overflow:auto;font:14px/1.6 system-ui,sans-serif;overflow-wrap:anywhere}
.feedback-toast-error{background:#fff0e8;border:2px solid #ba6b57;border-left:7px solid #9e4e40;color:#762417}
.feedback-toast-content{flex:1;min-width:0}.feedback-toast strong{font-weight:700}.feedback-toast p{margin:5px 0 0}.feedback-toast details{margin-top:8px;font-size:12px}.feedback-toast summary{cursor:pointer;text-decoration:underline}.feedback-toast button{flex-shrink:0;border:1px solid currentColor;color:inherit;background:transparent;border-radius:8px;min-height:40px;padding:6px 12px;font:inherit;cursor:pointer}
@media(max-width:600px){.feedback-toast-host{bottom:max(12px,env(safe-area-inset-bottom))}.feedback-toast{padding:14px;gap:12px}}
</style>
