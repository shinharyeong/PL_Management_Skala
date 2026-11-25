<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { useSession } from '../stores/session.js'
import { normalizeSubmission } from '../lib/submission-api.js'
import { baselineLabel } from '../lib/review-api.js'
import { dateTime, errorText, saveBlob } from '../lib/display.js'
import PButton from 'primevue/button'
import PDataTable from 'primevue/datatable'
import PColumn from 'primevue/column'
import PMessage from 'primevue/message'
import PPanel from 'primevue/panel'
import PTag from 'primevue/tag'

const props = defineProps({ submissionId: { type: String, required: true } })
const session = useSession(), detail = ref(null), error = ref(''), loading = ref(false), downloading = ref(false)
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
  if (downloading.value || !detail.value) return
  const filename = detail.value.originalFilename, signal = controller.signal
  downloading.value = true
  try {
    const blob = await session.request(`/api/v1/submissions/${props.submissionId}/file`, { blob: true, signal })
    if (!signal.aborted) saveBlob(blob, filename)
  } catch (cause) { if (!signal.aborted) error.value = errorText(cause) }
  finally { downloading.value = false }
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
    <PMessage v-if="loading" severity="info" :closable="false">제출본 이력을 불러오는 중입니다.</PMessage>
    <PMessage v-if="error" severity="error" :closable="false">{{ error }}</PMessage>
    <template v-if="detail">
      <h3>{{ detail.requestTitle }} · {{ detail.label }}</h3>
      <p>비교 기준 · {{ baselineLabel(detail.baseline) }}</p>
      <PMessage v-if="detail.reviewComment" severity="warn" :closable="false" class="preserve-lines">경영기획 코멘트 · {{ detail.reviewComment }}</PMessage>
      <PButton :label="`${detail.originalFilename} 다운로드`" icon="pi pi-download" severity="secondary" outlined :loading="downloading" :disabled="downloading" class="history-download" @click="download" />
      <PPanel header="작성한 소명" toggleable>
        <article v-for="item in detail.explanations" :key="item.reviewItemId"><strong>{{ item.item }} · {{ item.month }}월</strong><p class="preserve-lines">{{ item.answer }}</p></article>
        <p v-if="!detail.explanations.length" class="muted">이 제출본에서 작성한 소명이 없습니다.</p>
      </PPanel>
      <PPanel header="이어받은 소명" toggleable>
        <article v-for="item in detail.inheritedExplanations" :key="item.id"><strong>{{ item.approvedLabel || '수락된 소명' }} · {{ item.round }}회차</strong><p class="preserve-lines">{{ item.answer }}</p></article>
        <p v-if="!detail.inheritedExplanations.length" class="muted">이어받은 소명이 없습니다.</p>
      </PPanel>
      <PPanel header="재제출 요청 항목" toggleable>
        <article v-for="item in detail.rejectedItems" :key="item.reviewItemId"><strong>{{ item.item }} · {{ item.sourceCell || '위치 확인 필요' }}</strong><p class="preserve-lines">{{ item.reason }}</p><p v-if="item.previousExplanation?.answer">이전 소명 · {{ item.previousExplanation.answer }}</p></article>
        <p v-if="!detail.rejectedItems.length" class="muted">지정된 재제출 요청 항목이 없습니다.</p>
      </PPanel>
      <PPanel header="처리 이력" toggleable>
        <PDataTable :value="detail.history" stripedRows scrollable :tableStyle="{minWidth:'32rem'}" :pt="{table:{'aria-label':'제출본 처리 이력'}}">
          <PColumn header="처리 시각"><template #body="{data}">{{dateTime(data.at)}}</template></PColumn>
          <PColumn header="처리"><template #body="{data}"><PTag :value="eventLabel(data.event)" :severity="data.event==='APPROVED'?'success':data.event==='RESUBMIT_REQUESTED'?'warn':'info'" /></template></PColumn>
          <PColumn field="label" header="제출 버전" />
          <PColumn header="처리자"><template #body="{data}">{{data.actor?.name || '—'}}</template></PColumn>
          <template #empty>등록된 처리 이력이 없습니다.</template>
        </PDataTable>
      </PPanel>
    </template>
  </section>
</template>

<style scoped>
.submission-history { display:grid; gap:12px; text-align:left; min-width:0; }
.submission-history article + article { margin-top:16px; }
.submission-history p { white-space:pre-wrap; overflow-wrap:anywhere; }
.history-download { justify-self:start; max-width:100%; }
.history-download :deep(.p-button-label) { overflow-wrap:anywhere; }
</style>
