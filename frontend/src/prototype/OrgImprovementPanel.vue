<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import PButton from 'primevue/button'
import PInputText from 'primevue/inputtext'
import PTextarea from 'primevue/textarea'
import PSelect from 'primevue/select'
import PMessage from 'primevue/message'
import PTag from 'primevue/tag'
import PDialog from 'primevue/dialog'
import DecisionReplyFields from './DecisionReplyFields.vue'
import { usePrototypeStore } from './live-store.js'
import { formatMoney } from '../lib/decimal-display.js'
import { workDateText, replyCandidateDraft, workReplyPayload, workflowRequest, workStateLabel, workStateSeverity } from './decision-workflow-api.js'
const store = usePrototypeStore(), router = useRouter(), controller = new AbortController()
const items = ref([]), selected = ref(null), busy = ref(false), error = ref(''), notice = ref(''), followups = ref([])
const reply = ref(null), progress = ref({ status: 'IN_PROGRESS', note: '', evidenceRef: '', followupMasterId: null }), savedInput = ref('')
const confirmVisible = ref(false), confirmOperation = ref('')
const feasibilityOptions = [{ label: '개선 가능 · 금액과 조건 회신', value: 'FEASIBLE' }, { label: '개선 어려움 · 사유 회신', value: 'NOT_FEASIBLE' }]
const progressOptions = [{ label: '실행 중', value: 'IN_PROGRESS' }, { label: '후속 기준 연결하고 완료 기록', value: 'COMPLETED' }]
let removeGuard
/** 편집 중인 회신과 진척 상태를 비교 문자열로 만든다.
 * Args: 없음. Returns: JSON 문자열. Raises: 없음. Side Effects: 없음.
 */
function inputSnapshot() { return JSON.stringify({ reply: reply.value, progress: progress.value }) }
/** 아직 저장하지 않은 회신 또는 진척 변경이 있는지 확인한다.
 * Args: 없음. Returns: 변경 여부. Raises: 없음. Side Effects: 없음.
 */
function hasChanges() { return !!selected.value && inputSnapshot() !== savedInput.value }
const dirty = computed(hasChanges)
/** 인증된 요청에 화면 수명 중단 신호를 붙인다.
 * Args: path API 주소, options 요청 옵션. Returns: 응답 Promise.
 * Raises: HTTP·중단 오류. Side Effects: 인증된 서버 요청.
 */
async function api(path, options = {}) { return store.api(path, { ...options, signal: controller.signal }) }
/** 금액을 정밀 억원 문자열로 표시한다.
 * Args: value 원 단위 금액. Returns: 표시값. Raises: 없음. Side Effects: 없음.
 */
function money(value) { return formatMoney(value, 'HUNDRED_MILLION') }
/** 서버 요청을 직렬로 실행하고 화면 오류를 표시한다.
 * Args: work 비동기 작업. Returns: Promise<void>. Raises: 없음; 오류 표시.
 * Side Effects: 서버 작업과 진행 상태 변경.
 */
async function run(work) {
  if (busy.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try { await work() } catch (cause) { if (!controller.signal.aborted) error.value = cause.message }
  finally { busy.value = false }
}
/** 서버 요청을 선택하고 회신·진척 폼을 마지막 저장 상태로 되돌린다.
 * Args: item 현재 요청. Returns: 없음. Raises: 서버 금액 형식 오류.
 * Side Effects: 선택·편집·변경 기준 갱신.
 */
function acceptItem(item) {
  selected.value = item
  reply.value = { feasibility: item.reply?.feasibility || 'FEASIBLE', candidate: replyCandidateDraft(item), note: item.reply?.note || '', evidenceRef: item.reply?.evidenceRef || '' }
  progress.value = { status: item.progress?.status || 'IN_PROGRESS', note: item.progress?.note || '', evidenceRef: item.progress?.evidenceRef || '', followupMasterId: item.progress?.followupMasterId || null }
  savedInput.value = inputSnapshot()
  for (let index = 0; index < items.value.length; index++) if (items.value[index].id === item.id) items.value.splice(index, 1, item)
}
/** 자기 조직 범위로 제한된 요청 목록을 조회한다.
 * Args: 없음. Returns: Promise<void>. Raises: HTTP 오류. Side Effects: 목록 조회·변경.
 */
async function loadWork() { const response = await workflowRequest(api, 'items'); if (!controller.signal.aborted) items.value = response.items || [] }
/** 미저장 변경이 없을 때 목록을 새로 읽는다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음. Side Effects: 목록 조회 또는 오류 안내.
 */
async function refresh() { if (dirty.value) { error.value = '작성 내용을 저장하거나 되돌린 뒤 새로 조회해 주세요.'; return } return run(loadWork) }
/** 선택한 요청의 최신 내용과 사용 가능한 후속 기준을 읽는다.
 * Args: item 목록 항목. Returns: Promise<void>. Raises: 없음; 오류 표시.
 * Side Effects: 요청·후속 기준 조회와 편집 폼 변경.
 */
async function openItem(item) {
  if (dirty.value) { error.value = '작성 내용을 저장하거나 되돌린 뒤 다른 요청을 선택해 주세요.'; return }
  /** 단건 요청과 실행 단계에서의 후속 확정 기준을 조회한다.
   * Args: 없음. Returns: Promise<void>. Raises: HTTP 오류. Side Effects: 조회·선택 변경.
   */
  async function read() {
    const response = await workflowRequest(api, 'item', { itemId: item.id })
    if (controller.signal.aborted) return
    acceptItem(response); followups.value = []
    if (['APPROVED', 'IN_PROGRESS'].includes(response.state)) { const baselines = await workflowRequest(api, 'followup', { itemId: item.id }); if (!controller.signal.aborted) followups.value = baselines.items || [] }
  }
  return run(read)
}
/** 입력을 마지막 서버 응답 상태로 되돌린다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 편집 상태 복원.
 */
function discard() { if (selected.value) acceptItem(selected.value); error.value = '' }
/** 회신 또는 실행 기록의 확인 창을 연다.
 * Args: operation 업무 종류. Returns: 없음. Raises: 없음. Side Effects: 확인 창 표시.
 */
function confirm(operation) { confirmOperation.value = operation; confirmVisible.value = true }
/** 확인한 조직 회신 또는 실행 진척을 저장한다.
 * Args: 없음. Returns: Promise<void>. Raises: 입력·충돌·HTTP 오류.
 * Side Effects: 업무 저장·변경 기준 갱신. 분석 후보를 직접 변경하지 않는다.
 */
async function submitWork() {
  let body
  if (confirmOperation.value === 'reply') body = workReplyPayload(selected.value, reply.value)
  else {
    if (!progress.value.note.trim() || !progress.value.evidenceRef.trim()) throw new Error('실행 내용과 근거 위치를 모두 입력해 주세요.')
    if (progress.value.status === 'COMPLETED' && !progress.value.followupMasterId) throw new Error('완료 기록에는 후속 확정 마스터를 연결해 주세요.')
    body = { expectedRevision: selected.value.revision, status: progress.value.status, note: progress.value.note.trim(), evidenceRef: progress.value.evidenceRef.trim(), followupMasterId: progress.value.followupMasterId }
  }
  const response = await workflowRequest(api, confirmOperation.value, { itemId: selected.value.id, body })
  if (!controller.signal.aborted) { acceptItem(response); confirmVisible.value = false; notice.value = confirmOperation.value === 'reply' ? '회신을 저장했습니다. 경영기획의 근거 검토를 기다립니다.' : '실행 기록을 저장했습니다. 후속 전망 변화는 과제 단독 성과와 구분됩니다.' }
}
/** 확인한 저장 작업을 직렬 실행한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음. Side Effects: 저장 요청.
 */
async function submit() { return run(submitWork) }
/** 작성 중인 회신과 실행 기록이 사라지는 화면 이동을 막는다.
 * Args: to 목적 라우트. Returns: 이동 허용 여부. Raises: 없음. Side Effects: 안내 변경.
 */
function guard(to) { if (to.name === 'org-improvements' || (!dirty.value && !busy.value)) return true; error.value = '작성 내용을 저장하거나 되돌린 뒤 이동해 주세요.'; return false }
/** 새로고침이나 창 닫기에서 미저장 변경을 경고한다.
 * Args: event 브라우저 이탈 이벤트. Returns: 없음. Raises: 없음. Side Effects: 브라우저 확인 요청.
 */
function beforeUnload(event) { if (dirty.value || busy.value) { event.preventDefault(); event.returnValue = '' } }
/** 역할 전용 목록을 조회하고 이탈 감시를 시작한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 조회·이벤트 등록.
 */
function mount() { removeGuard = router.beforeEach(guard); window.addEventListener('beforeunload', beforeUnload); refresh() }
/** 해제된 화면의 요청과 이벤트를 정리한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 요청 중단·이벤트 해제.
 */
function dispose() { controller.abort(); removeGuard?.(); window.removeEventListener('beforeunload', beforeUnload) }
onMounted(mount)
onBeforeUnmount(dispose)
</script>
<template><section class="org-improvements"><header class="org-improvement-heading"><div><p class="eyebrow">P&amp;L · ORGANIZATION WORKSPACE</p><h1>개선 검토와 실행 과제</h1><p>{{ store.actor?.department }}에 전달된 요청을 확인하고 근거와 실행 경과를 남깁니다.</p></div><PButton label="새로 조회" icon="pi pi-refresh" outlined :disabled="busy" @click="refresh" /></header><PMessage v-if="error" severity="error" :closable="false">{{ error }}</PMessage><PMessage v-if="notice" severity="success" :closable="false">{{ notice }}</PMessage><div class="org-work-layout"><aside><p v-if="!items.length && !busy" class="empty">아직 전달받은 개선 검토 요청이 없습니다.</p><PButton v-for="item in items" :key="item.id" :unstyled="true" class="org-work-card" :class="{ active: selected?.id === item.id }" :disabled="busy" @click="openItem(item)"><PTag :value="workStateLabel(item.state)" :severity="workStateSeverity(item.state)" /><strong>{{ item.title }}</strong><small>{{ item.round }}차 검토 · 기한 {{ workDateText(item.dueAt) }}</small></PButton></aside><article v-if="selected" class="org-work-content"><header><PTag :value="workStateLabel(selected.state)" :severity="workStateSeverity(selected.state)" /><h2>{{ selected.title }}</h2><p>{{ selected.masterLabel }} · {{ selected.organizationName }} · 요청 v{{ selected.revision }}</p></header><div class="request-message"><strong>경영기획 확인 요청</strong><p>{{ selected.message }}</p></div><PMessage v-if="selected.review?.verdict === 'REQUEST_CHANGES'" severity="warn" :closable="false">보완 요청: {{ selected.review.note }}</PMessage><PMessage v-if="dirty" severity="warn" :closable="false">아직 저장하지 않은 입력입니다. <PButton label="입력 되돌리기" text size="small" :disabled="busy" @click="discard" /></PMessage><template v-if="['REQUESTED','CHANGES_REQUESTED'].includes(selected.state)"><h3>확인한 조건과 효과 회신</h3><div class="field"><label for="reply-feasibility">개선 가능 여부</label><PSelect input-id="reply-feasibility" v-model="reply.feasibility" :options="feasibilityOptions" option-label="label" option-value="value" :disabled="busy" /></div><DecisionReplyFields v-if="reply.feasibility === 'FEASIBLE'" :draft="reply.candidate" :item="selected" :disabled="busy" /><div class="field"><label for="reply-note">회신 내용과 확인한 조건</label><PTextarea id="reply-note" v-model="reply.note" rows="5" maxlength="4000" placeholder="가능액의 계산 근거, 반영 조건 또는 개선하기 어려운 사유" :disabled="busy" /></div><div class="field"><label for="reply-evidence">근거 위치</label><PInputText id="reply-evidence" v-model="reply.evidenceRef" maxlength="2000" placeholder="계약·일정·승인 소명 또는 문서 위치" :disabled="busy" /></div><PMessage severity="info" :closable="false">회신은 경영기획의 근거 검토를 거쳐 후보에 편입됩니다. 회신만으로 확정 전망이나 실적이 바뀌지 않습니다.</PMessage><PButton label="회신 내용 확인하고 제출" icon="pi pi-send" :disabled="busy" @click="confirm('reply')" /></template><section v-else-if="selected.reply" class="saved-reply"><h3>제출한 회신</h3><p>{{ selected.reply.feasibility === 'FEASIBLE' ? '개선 가능' : '개선 어려움' }} · {{ selected.reply.note }}</p><p v-if="selected.reply.candidate">효과 금액 {{ money(selected.reply.candidate.amountWon) }}억 원 · {{ selected.reply.candidate.recognitionMonth }}월 반영</p><small>근거 {{ selected.reply.evidenceRef }}</small></section><section v-if="selected.decision" class="saved-decision"><h3>추진 결정</h3><p>{{ selected.decision.verdict === 'APPROVE' ? '추진' : '보류' }} · {{ selected.decision.note }}</p><small>{{ selected.decision.decisionMaker }} · 실행 기한 {{ workDateText(selected.decision.dueAt) }}</small></section><template v-if="['APPROVED','IN_PROGRESS'].includes(selected.state)"><h3>실행 경과 기록</h3><div class="field"><label for="progress-status">진행 상태</label><PSelect input-id="progress-status" v-model="progress.status" :options="progressOptions" option-label="label" option-value="value" :disabled="busy" /></div><div class="field"><label for="progress-note">실행 내용과 남은 조건</label><PTextarea id="progress-note" v-model="progress.note" rows="4" maxlength="4000" :disabled="busy" /></div><div class="field"><label for="progress-evidence">실행 근거 위치</label><PInputText id="progress-evidence" v-model="progress.evidenceRef" maxlength="2000" :disabled="busy" /></div><div class="field"><label for="progress-baseline">후속 확정 마스터 {{ progress.status === 'COMPLETED' ? '· 필수' : '· 선택' }}</label><PSelect input-id="progress-baseline" v-model="progress.followupMasterId" :options="followups" option-label="label" option-value="masterId" show-clear placeholder="후속 전망과 비교할 확정 기준" :disabled="busy" /><small v-if="!followups.length">선택 가능한 후속 확정 기준이 없습니다. 기준이 확정되기 전에는 실행 중으로 기록해 주세요.</small></div><PMessage severity="info" :closable="false">조직 연간 전망 변화이며 이 과제의 실현 성과를 확정하지 않습니다. 여러 요인이 함께 반영된 값입니다.</PMessage><PButton label="실행 기록 확인하고 저장" :disabled="busy" @click="confirm('progress')" /></template><section v-if="selected.progress" class="saved-progress"><h3>최근 실행 기록</h3><p>{{ selected.progress.note }}</p><small>근거 {{ selected.progress.evidenceRef }}</small><p v-if="selected.progress.followupMasterId">{{ selected.progress.followupMasterLabel }} · 조직 전망 {{ money(selected.progress.baselineProfitWon) }} → {{ money(selected.progress.followupProfitWon) }}억 원</p></section><section class="work-history"><h3>요청·회신 이력</h3><ol><li v-for="event in selected.events || []" :key="event.sequence"><p>{{ event.note || '업무 상태 변경' }}</p><small>{{ workDateText(event.at) }}</small></li></ol></section></article><div v-else class="empty">요청을 선택하면 회신과 실행 기록을 작성할 수 있습니다.</div></div><PDialog v-model:visible="confirmVisible" modal :header="confirmOperation === 'reply' ? '회신을 제출할까요?' : '실행 기록을 저장할까요?'" :style="{ width: '32rem', maxWidth: '95vw' }" :closable="!busy"><p>{{ selected?.title }}</p><p>{{ confirmOperation === 'reply' ? '입력한 금액·조건·근거가 경영기획 검토함으로 전달됩니다.' : '입력한 실행 내용과 후속 기준 연결이 요청 이력에 기록됩니다.' }}</p><PMessage v-if="error" severity="error" :closable="false">{{ error }}</PMessage><template #footer><PButton label="계속 작성" text :disabled="busy" @click="confirmVisible = false" /><PButton label="확인하고 저장" :loading="busy" :disabled="busy" @click="submit" /></template></PDialog></section></template>
<style scoped>.org-improvements{color:#2d2d2b;max-width:1400px;margin:auto}.org-improvement-heading{display:flex;justify-content:space-between;gap:20px;margin:10px 0 28px}.eyebrow{font-size:11px;font-weight:700;letter-spacing:.16em;color:#5c5c59}.org-improvement-heading h1{font-size:28px;letter-spacing:-.04em;margin:10px 0}.org-improvement-heading p:last-child{font-size:13px;color:#7a7a76;line-height:1.8}.org-work-layout{display:grid;grid-template-columns:300px minmax(0,1fr);gap:22px;margin-top:22px}.org-work-layout aside{display:flex;flex-direction:column;gap:12px}.org-work-card{display:flex;flex-direction:column;align-items:start;text-align:left;gap:11px;padding:18px;background:white;border:1px solid #e3e3e3;border-radius:7px;color:#2d2d2b;cursor:pointer}.org-work-card.active{background:#f8f8f7;border-color:#5c5c59}.org-work-card strong{font-size:14px;line-height:1.6}.org-work-card small{font-size:11px;color:#7a7a76}.org-work-content{background:white;border:1px solid #e3e3e3;border-radius:8px;padding:28px;min-width:0}.org-work-content header h2{font-size:24px;letter-spacing:-.03em;margin:13px 0}.org-work-content header p{font-size:12px;color:#7a7a76}.request-message{padding:18px;background:#f7f7f6;margin:22px 0;font-size:13px}.request-message p,.saved-reply p,.saved-decision p,.saved-progress p,.work-history p{font-size:13px;line-height:1.8;white-space:pre-line;overflow-wrap:anywhere}.org-work-content h3{font-size:16px;margin:28px 0 15px}.field{display:flex;flex-direction:column;gap:8px;margin:18px 0}.field label{font-size:12px;font-weight:650}.field small,.saved-reply small,.saved-decision small,.saved-progress small,.work-history small{font-size:11px;line-height:1.8;color:#7a7a76}.field :deep(.p-inputtext),.field :deep(.p-textarea),.field :deep(.p-select){width:100%;font-size:13px}.empty{padding:30px;border:1px dashed #e3e3e3;border-radius:6px;font-size:13px;color:#7a7a76;line-height:1.8}.work-history{margin-top:30px;border-top:1px solid #e3e3e3}.work-history ol{padding-left:20px}.work-history li{padding-bottom:15px}.work-history p{margin:0 0 4px}.org-improvements :deep(.p-message){margin:14px 0}.org-improvements :deep(.p-message-text){font-size:12px;line-height:1.8}.org-improvements :deep(.p-tag){font-size:10px}.org-improvements :deep(.p-button){font-size:12px}@media(max-width:900px){.org-work-layout{grid-template-columns:1fr}.org-work-layout aside{display:grid;grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:650px){.org-work-layout aside{grid-template-columns:1fr}.org-work-content{padding:18px}.org-improvement-heading{flex-direction:column}}</style>
