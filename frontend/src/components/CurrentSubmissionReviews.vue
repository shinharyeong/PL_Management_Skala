<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useSession } from '../stores/session'
import { dateTime, errorText, saveBlob } from '../lib/display'
import { formatMoney } from '../lib/decimal-display'
import { normalizeOrgReviewPage } from '../lib/submission-api'

const props = defineProps({ submission: { type: Object, required: true } })
const emit = defineEmits(['changed', 'loaded'])
const session = useSession()
const items = ref([]), detail = ref(null), selectedId = ref(''), answer = ref(''), note = ref('')
const loading = ref(false), busy = ref(false), error = ref(''), message = ref(''), attachments = ref([])
const controller = new AbortController()
let detailController = new AbortController(), generation = 0
const keys = new Map()
const latest = computed(() => detail.value?.explanations?.at(-1))
const editable = computed(() => session.actor?.role === 'ORG_MANAGER' && latest.value?.verdict === 'PENDING' && !latest.value.inheritedFrom && (props.submission.status === 'DRAFT' || (props.submission.status === 'PENDING' && latest.value.round > 1)))
const canDecide = computed(() => session.actor?.role === 'PLANNER' && props.submission.status === 'PENDING' && latest.value?.answer && latest.value.verdict === 'PENDING')

async function load() {
  loading.value = true; error.value = ''
  try {
    const page = normalizeOrgReviewPage(await session.request(`/api/v1/review-items?view=ORG&submissionId=${props.submission.id}`, { signal: controller.signal }))
    if (controller.signal.aborted) return
    items.value = page.items; emit('loaded', page)
    const id = items.value.some(item => item.id === selectedId.value) ? selectedId.value : items.value[0]?.id
    if (id) await select(id)
    else detail.value = null
  } catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { loading.value = false }
}

async function select(id) {
  const current = ++generation
  detailController.abort(); detailController = new AbortController()
  selectedId.value = id; detail.value = null; attachments.value = []; answer.value = ''; note.value = ''; error.value = ''
  try {
    const result = await session.request(`/api/v1/review-items/${id}`, { signal: detailController.signal })
    if (current !== generation || controller.signal.aborted) return
    detail.value = result; answer.value = result.explanations?.at(-1)?.answer || ''
    const files = await session.request(`/api/v1/review-items/${id}/attachments`, { signal: detailController.signal })
    if (current === generation) attachments.value = files.items
  } catch (cause) { if (current === generation && cause.name !== 'AbortError') error.value = errorText(cause) }
}

async function command(action) {
  if (busy.value || !detail.value) return
  if (action === 'answer' && (!editable.value || !answer.value.trim())) return
  if (action !== 'answer' && (!canDecide.value || (action === 'REQUEST_AGAIN' && !note.value.trim()))) return
  const id = detail.value.id
  const body = action === 'answer' ? { answer: answer.value.trim(), expectedStateToken: detail.value.stateToken } : { verdict: action, note: note.value.trim() || null, expectedStateToken: detail.value.stateToken }
  const fingerprint = `${id}:${action}:${JSON.stringify(body)}`
  if (!keys.has(fingerprint)) keys.set(fingerprint, crypto.randomUUID())
  busy.value = true; error.value = ''; message.value = ''
  try {
    await session.request(`/api/v1/review-items/${id}/${action === 'answer' ? 'answer' : 'decisions'}`, { method: action === 'answer' ? 'PUT' : 'POST', headers: { 'Idempotency-Key': keys.get(fingerprint) }, body: JSON.stringify(body), signal: controller.signal })
    if (controller.signal.aborted) return
    keys.delete(fingerprint); message.value = action === 'answer' ? '소명을 저장했습니다.' : action === 'ACCEPT' ? '소명을 수락했습니다.' : '소명 보완을 요청했습니다.'
    await load(); emit('changed')
  } catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { busy.value = false }
}

async function attach(event) {
  const file = event.target.files?.[0]; event.target.value = ''
  if (!file || !editable.value || busy.value) return
  if (!/\.(pdf|xlsx|png|jpe?g)$/i.test(file.name) || !file.size || file.size > 16 * 1024 * 1024) { error.value = 'PDF·XLSX·PNG·JPEG 형식의 비어 있지 않은 16MB 이하 파일을 선택해 주세요.'; return }
  const body = new FormData(); body.append('file', file); body.append('expectedStateToken', detail.value.stateToken)
  busy.value = true; error.value = ''
  try {
    await session.request(`/api/v1/review-items/${detail.value.id}/attachments`, { method: 'POST', body, headers: { 'Idempotency-Key': crypto.randomUUID() }, signal: controller.signal })
    if (!controller.signal.aborted) { await select(selectedId.value); emit('changed') }
  } catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { busy.value = false }
}

async function download(item) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { saveBlob(await session.request(`/api/v1/review-items/${detail.value.id}/attachments/${item.id}/file`, { blob: true, signal: controller.signal }), item.name) }
  catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { busy.value = false }
}

function dispose() { generation++; controller.abort(); detailController.abort() }
onMounted(load)
onBeforeUnmount(dispose)
</script>

<template>
  <section class="panel current-reviews">
    <div class="section-heading"><h2>변경 항목·소명</h2><span class="muted">{{ items.length }}개 항목</span></div>
    <p v-if="error" class="alert error" role="alert">{{ error }}</p><p v-if="message" class="notice" role="status">{{ message }}</p>
    <p v-if="loading" role="status">소명 항목을 불러오는 중…</p>
    <p v-else-if="!items.length" class="notice">현재 제출본에 소명 대상이 없습니다.</p>
    <div v-if="items.length" class="review-layout">
      <aside class="review-list"><button v-for="item in items" :key="item.id" class="review-item" :class="{ active: selectedId === item.id }" :disabled="busy" @click="select(item.id)"><span>{{ item.reportItem.name }}</span><small>{{ item.month }}월 · {{ { PLAN: '계획', ACTUAL: '실적', ESTIMATE: '추정' }[item.scenario] }} · {{ { EMPTY: '미작성', WRITTEN: '작성 완료', INHERITED: '이전 소명 유지' }[item.answerState] }}</small></button></aside>
      <div v-if="detail" class="review-detail">
        <h3>{{ detail.path }}</h3>
        <div class="table-scroll"><table><caption class="visually-hidden">원본 셀별 금액 비교 · 원</caption><thead><tr><th>원본 셀</th><th>기준</th><th v-if="detail.rows.some(row => row.previous !== null)">직전 반려 제출값</th><th>현재</th><th>차액</th><th>차이율</th></tr></thead><tbody><tr v-for="row in detail.rows" :key="row.sourceCell"><td>{{ row.sourceCell || '—' }}</td><td class="number">{{ formatMoney(row.baseline) }}</td><td v-if="detail.rows.some(row => row.previous !== null)" class="number">{{ formatMoney(row.previous) }}</td><td class="number">{{ formatMoney(row.current) }}</td><td class="number">{{ formatMoney(row.diff) }}</td><td>{{ row.rate === null ? '—' : row.rate + '%' }}</td></tr></tbody></table></div>
        <p v-if="latest?.requestNote" class="notice preserve-lines">{{ latest.requestNote }}</p>
        <form v-if="editable" @submit.prevent="command('answer')"><label>소명 작성<textarea v-model="answer" rows="5" required maxlength="4000" :disabled="busy" /></label><label class="attachment-input">근거 파일 첨부<input type="file" accept=".pdf,.xlsx,.png,.jpg,.jpeg" :disabled="busy" @change="attach" /></label><button class="button primary" :disabled="busy || !answer.trim()">{{ busy ? '저장 중…' : '소명 저장' }}</button></form>
        <div v-if="attachments.length" class="attachment-list"><button v-for="file in attachments" :key="file.id" class="text-button" :disabled="busy" @click="download(file)">{{ file.name }}</button></div>
        <form v-if="canDecide" @submit.prevent="command('ACCEPT')"><label>경영기획 메모<textarea v-model="note" rows="3" maxlength="4000" :disabled="busy" /></label><div class="button-row"><button class="button" type="button" :disabled="busy || !note.trim()" @click="command('REQUEST_AGAIN')">소명 보완 요청</button><button class="button primary" :disabled="busy">소명 수락</button></div></form>
        <details v-for="explanation in detail.explanations" :key="explanation.id" open><summary>{{ explanation.round }}회차 · {{ { PENDING: '검토 중', ACCEPTED: '수락', REQUESTED_AGAIN: '보완 요청' }[explanation.verdict] || explanation.verdict }}</summary><p class="preserve-lines">{{ explanation.answer || '아직 작성된 소명이 없습니다.' }}</p><small>{{ explanation.answeredBy?.name }} · {{ dateTime(explanation.answeredAt) }}</small><p v-if="explanation.reviewNote" class="preserve-lines">{{ explanation.reviewNote }}</p></details>
      </div>
    </div>
    <slot name="footer" />
  </section>
</template>

<style scoped>
.review-layout { display:grid; grid-template-columns:minmax(200px,1fr) minmax(0,3fr); gap:20px; }.review-list { max-height:600px; overflow:auto; border:1px solid var(--line); border-radius:8px; }.review-item { display:block; padding:12px; width:100%; border:0; border-bottom:1px solid var(--line); background:transparent; text-align:left; color:var(--ink); cursor:pointer; }.review-item small { display:block; margin-top:4px; color:var(--muted); }.review-item.active { background:#e8f0ff; box-shadow:inset 3px 0 #3b69d7; }.review-detail {
  min-width: 0; display:flex; flex-direction:column; gap:16px; }.review-detail table { font-size:12px; min-width:550px; }.review-detail details { padding:12px; border:1px solid var(--line); border-radius:8px; }.review-detail details p { margin:10px 0; }.review-detail form { display:flex; flex-direction:column; gap:12px; }.attachment-list { display:flex; flex-wrap:wrap; gap:12px; }.attachment-input { font-size:12px; }.review-detail small { color:var(--muted); }@media(max-width:800px) { .review-layout { grid-template-columns:1fr; }.review-list { max-height:230px; } }
</style>
