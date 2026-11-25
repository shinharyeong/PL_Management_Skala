<script setup>
import { watch, onBeforeUnmount } from 'vue'
import PToast from 'primevue/toast'
import { useToast } from 'primevue/usetoast'
import { usePrototypeStore } from './live-store.js'

const store = usePrototypeStore(), toast = useToast()

/** 전역 처리 결과를 PrimeVue 알림으로 교체한다.
 * Args: current store의 현재 알림 또는 null.
 * Returns: 없음. Raises: 없음.
 * Side Effects: feedback 그룹을 교체한다. 오류는 사용자가 닫을 때까지 유지한다.
 */
function showFeedback(current) {
  toast.removeGroup('feedback')
  if (!current) return
  toast.add({
    group: 'feedback', sourceId: current.id,
    severity: current.tone === 'error' ? 'error' : current.tone === 'success' ? 'success' : 'info',
    summary: current.tone === 'error' ? '확인이 필요합니다' : '안내',
    detail: current.message,
    ...(current.tone === 'error' ? {} : { life: 7000 }),
  })
}

/** 닫힌 알림 ID만 저장소에서 해제한다.
 * Args: event PrimeVue close/life-end 이벤트.
 * Returns: 없음. Raises: 없음. Side Effects: 일치하는 store 알림을 해제한다.
 */
function dismissFeedback(event) { store.dismissToast(event.message.sourceId) }

watch(() => store.toast, showFeedback, { immediate: true })
onBeforeUnmount(() => toast.removeGroup('feedback'))
</script>

<template>
  <PToast group="feedback" position="bottom-center" class="app-feedback" :breakpoints="{'640px': {width: 'calc(100% - 24px)'}}" @close="dismissFeedback" @life-end="dismissFeedback">
    <template #message="{ message }">
      <div class="feedback-content"><strong>{{ message.summary }}</strong><p>{{ message.detail.split(' · ')[0] }}</p><details v-if="message.detail.includes(' · ')"><summary>상세 보기</summary><p>{{ message.detail.split(' · ').slice(1).join(' · ') }}</p></details></div>
    </template>
  </PToast>
</template>

<style scoped>
.feedback-content{min-width:0;overflow-wrap:anywhere;max-height:40dvh;overflow:auto;line-height:1.6}.feedback-content p{margin:.4rem 0 0}.feedback-content details{margin-top:.5rem}.feedback-content summary{cursor:pointer}
</style>
