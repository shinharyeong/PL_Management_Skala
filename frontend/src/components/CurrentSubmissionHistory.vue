<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { useSession } from '../stores/session.js'
import { normalizeSubmission } from '../lib/submission-api.js'
import { baselineLabel } from '../lib/review-api.js'
import { dateTime, errorText, saveBlob } from '../lib/display.js'

const props = defineProps({ submissionId: { type: String, required: true } })
const session = useSession(), detail = ref(null), error = ref(''), loading = ref(false)
let controller = new AbortController()

/** HIST-02의 제출·소명·반려·처리 이력을 한 응답으로 읽는다.
 * Args: id 제출본 ID. Returns: Promise<void>. Raises: 없음(오류 표시).
 * Side Effects: 이전 HTTP 취소·새 상세 조회·화면 갱신.
 */
async function load(id) {
  controller.abort(); controller = new AbortController()
  const signal = controller.signal
  detail.value = null; error.value = ''; loading.value = true
  try {
    const result = await session.request(`/api/v1/submissions/${id}`, { signal })
    if (!signal.aborted) detail.value = normalizeSubmission(result)
  } catch (cause) { if (!signal.aborted) error.value = errorText(cause) }
  finally { if (!signal.aborted) loading.value = false }
}

/** 선택한 제출본의 저장된 원본을 다운로드한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(오류 표시).
 * Side Effects: HTTP 조회·브라우저 다운로드. 원본 파일을 수정하지 않는다.
 */
async function download() {
  try {
    const blob = await session.request(`/api/v1/submissions/${props.submissionId}/file`, { blob: true, signal: controller.signal })
    saveBlob(blob, detail.value.originalFilename)
  } catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
}

/** 서버 이벤트 이름을 사용자용 처리 상태로 표시한다.
 * Args: event 서버 이력 이벤트. Returns: 표시 이름. Raises: 없음. Side Effects: 없음.
 */
function eventLabel(event) {
  return { SUBMITTED: '제출', RESUBMITTED: '재제출', RESUBMIT_REQUESTED: '재제출 요청', APPROVED: '승인' }[event] || event
}

/** 화면 종료 시 진행 중인 HTTP 요청을 취소한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소.
 */
function dispose() { controller.abort() }
watch(() => props.submissionId, load, { immediate: true })
onBeforeUnmount(dispose)
</script>

<template>
  <section class="submission-history" aria-label="제출본 상세 이력">
    <p v-if="loading" role="status">제출본 이력을 불러오는 중입니다.</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <template v-if="detail">
      <h3>{{ detail.requestTitle }} · {{ detail.label }}</h3>
      <p>비교 기준 · {{ baselineLabel(detail.baseline) }}</p>
      <p v-if="detail.reviewComment" class="preserve-lines">경영기획 코멘트 · {{ detail.reviewComment }}</p>
      <button class="btn" @click="download">{{ detail.originalFilename }} 다운로드</button>
      <h4>작성한 소명</h4>
      <article v-for="item in detail.explanations" :key="item.reviewItemId"><strong>{{ item.item }} · {{ item.month }}월</strong><p class="preserve-lines">{{ item.answer }}</p></article>
      <p v-if="!detail.explanations.length" class="muted">이 제출본에서 작성한 소명이 없습니다.</p>
      <h4>이어받은 소명</h4>
      <article v-for="item in detail.inheritedExplanations" :key="item.id"><strong>{{ item.approvedLabel || '수락된 소명' }} · {{ item.round }}회차</strong><p class="preserve-lines">{{ item.answer }}</p></article>
      <p v-if="!detail.inheritedExplanations.length" class="muted">이어받은 소명이 없습니다.</p>
      <h4>재제출 요청 항목</h4>
      <article v-for="item in detail.rejectedItems" :key="item.reviewItemId"><strong>{{ item.item }} · {{ item.sourceCell || '위치 확인 필요' }}</strong><p class="preserve-lines">{{ item.reason }}</p><p v-if="item.previousExplanation?.answer">이전 소명 · {{ item.previousExplanation.answer }}</p></article>
      <p v-if="!detail.rejectedItems.length" class="muted">지정된 재제출 요청 항목이 없습니다.</p>
      <h4>처리 이력</h4>
      <ol><li v-for="(event,index) in detail.history" :key="index">{{ dateTime(event.at) }} · {{ eventLabel(event.event) }} · {{ event.label }} · {{ event.actor?.name || '—' }}</li></ol>
    </template>
  </section>
</template>

<style scoped>
.submission-history { display: grid; gap: 12px; text-align: left; }.submission-history article { border-left: 3px solid #d4d9df; padding-left: 12px; }.submission-history p { white-space: pre-wrap; }.submission-history ol { padding-left: 24px; }
</style>
