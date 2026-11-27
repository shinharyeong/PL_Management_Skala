<script setup>
import {usePrototypeStore} from './live-store.js'
import { onBeforeUnmount, ref, watch } from 'vue'
import PButton from 'primevue/button'
import PInputText from 'primevue/inputtext'
import PTextarea from 'primevue/textarea'
import PSelect from 'primevue/select'
import PMessage from 'primevue/message'
import PTag from 'primevue/tag'
import PDialog from 'primevue/dialog'
import { formatMoney } from '../lib/decimal-display.js'
import { workDateText, canApproveWork, dueAtKst, workCreatePayload, workflowRequest, workStateLabel, workStateSeverity } from './decision-workflow-api.js'
const props = defineProps({ analysis: { type: Object, required: true }, api: { type: Function, required: true }, refreshAnalysis: { type: Function, required: true }, requestSource: Object, disabled: Boolean })
const emit = defineEmits(['busy', 'items', 'loaded'])
const items = ref([]), selected = ref(null), busy = ref(false), error = ref(''), notice = ref('')
watch(notice, value => {if(value)usePrototypeStore().notify(value)})
const createVisible = ref(false), source = ref(null), candidateId = ref(null), createForm = ref({ message: '', dueDate: '' })
const actionVisible = ref(false), action = ref(''), actionForm = ref({ verdict: '', note: '', decisionMaker: '', decisionEvidenceRef: '', dueDate: '' })
const reviewOptions = [{ label: '근거 검토 후 후보 편입', value: 'ACCEPT' }, { label: '보완 요청', value: 'REQUEST_CHANGES' }, { label: '반려', value: 'REJECT' }]
const decisionOptions = [{ label: '추진 결정 기록', value: 'APPROVE' }, { label: '추진 보류 기록', value: 'DECLINE' }]
let generation = 0
/** 최신 분석 ID를 감시 값으로 반환한다.
 * Args: 없음. Returns: 분석 ID. Raises: 없음. Side Effects: 없음.
 */
function analysisId() { return props.analysis.id }
/** 부모에서 선택한 가설을 감시 값으로 반환한다.
 * Args: 없음. Returns: 요청 원천. Raises: 없음. Side Effects: 없음.
 */
function requestSource() { return props.requestSource }
/** 금액을 정확한 억원 문자열로 표시한다.
 * Args: value 원 금액. Returns: 표시 금액. Raises: 없음. Side Effects: 없음.
 */
function money(value) { return formatMoney(value, 'HUNDRED_MILLION') }
/** 요청 목록과 보고서에서 사용할 상태를 부모에 전달한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: items 이벤트 발생.
 */
function publishItems() { emit('items', items.value) }
/** 업무 요청 하나를 화면에 반영하고 목록도 갱신한다.
 * Args: value 서버 요청. Returns: 없음. Raises: 없음. Side Effects: 목록·선택 변경.
 */
function acceptItem(value) {
  selected.value = value
  let position = -1
  for (let index = 0; index < items.value.length; index++) if (items.value[index].id === value.id) position = index
  if (position < 0) items.value.unshift(value); else items.value.splice(position, 1, value)
  publishItems()
}
/** 저장 작업을 직렬 실행하고 부모의 이탈 방지에 진행 상태를 전달한다.
 * Args: work 비동기 작업. Returns: Promise<void>. Raises: 없음; 오류 표시.
 * Side Effects: 작업 실행 및 진행·안내 상태 변경.
 */
async function run(work) {
  if (busy.value || props.disabled) return
  busy.value = true; error.value = ''; notice.value = ''; emit('busy', true)
  try { await work() } catch (cause) { error.value = cause.message }
  finally { busy.value = false; emit('busy', false) }
}
/** 현재 분석의 조직 요청과 과제를 조회한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음; 오류 표시.
 * Side Effects: 서버 조회와 목록 변경.
 */
async function loadItems() {
  const current = ++generation; selected.value = null; createVisible.value = false; actionVisible.value = false; items.value = []; error.value = ''; busy.value = true
  emit('loaded', false)
  try { const response = await workflowRequest(props.api, 'items', { analysisId: props.analysis.id }); if (current === generation) { items.value = response.items || []; publishItems(); emit('loaded', true) } }
  catch (cause) { if (current === generation) error.value = cause.message }
  finally { if (current === generation) busy.value = false }
}
/** 선택 요청을 서버에서 다시 읽어 최신 revision으로 검토한다.
 * Args: item 목록 요청. Returns: Promise<void>. Raises: 없음; 오류 표시.
 * Side Effects: 단건 조회와 선택 변경.
 */
async function openItem(item) {
  if (busy.value) return
  const current = generation
  busy.value = true; error.value = ''
  try { const response = await workflowRequest(props.api, 'item', { itemId: item.id }); if (current === generation) acceptItem(response) }
  catch (cause) { if (current === generation) error.value = cause.message }
  finally { if (current === generation) busy.value = false }
}
/** 선택한 후보·가설로 검토 요청 작성 창을 연다.
 * Args: value 요청 원천. Returns: 없음. Raises: 없음. Side Effects: 요청 초안 입력 초기화.
 */
function openCreate(value) {
  if (!value) return
  source.value = value; createForm.value = { message: value.questions?.join('\n') || '', dueDate: '' }; createVisible.value = true
}
/** 저장된 후보를 조직 확인 요청 원천으로 선택한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 작성 창 표시.
 */
function chooseCandidate() { for (const candidate of props.analysis.candidates) if (candidate.id === candidateId.value) openCreate({ ...candidate, kind: 'candidate' }) }
/** 사용자 입력을 검증하고 조직 요청을 초안으로 저장한다.
 * Args: 없음. Returns: Promise<void>. Raises: 입력·HTTP 오류.
 * Side Effects: 요청 초안 저장; 실제 전달은 별도 issue에서 수행한다.
 */
async function createWork() { const value = await workflowRequest(props.api, 'create', { analysisId: props.analysis.id, body: workCreatePayload(props.analysis, source.value, createForm.value) }); acceptItem(value); createVisible.value = false; notice.value = '요청 초안을 저장했습니다. 내용을 확인한 뒤 조직에 전달해 주세요.' }
/** 초안 저장 작업을 직렬로 시작한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음. Side Effects: 초안 저장 요청.
 */
async function createItem() { return run(createWork) }
/** 요청 전달·회신 검토·추진 결정·취소의 확인 창을 연다.
 * Args: name 작업명. Returns: 없음. Raises: 없음. Side Effects: 확인 입력 초기화.
 */
function openAction(name) { action.value = name; actionForm.value = { verdict: name === 'review' ? 'REQUEST_CHANGES' : 'DECLINE', note: '', decisionMaker: '', decisionEvidenceRef: '', dueDate: '' }; actionVisible.value = true }
/** 확인한 업무 상태 변경을 서버에 저장한다.
 * Args: 없음. Returns: Promise<void>. Raises: 입력·버전 충돌·HTTP 오류.
 * Side Effects: 요청 상태 저장. 후보 채택 시 분석을 다시 조회한다.
 */
async function actionWork() {
  const body = { expectedRevision: selected.value.revision }
  if (action.value !== 'issue') { if (!actionForm.value.note.trim()) throw new Error('검토 또는 결정 내용을 입력해 주세요.'); body.note = actionForm.value.note.trim() }
  if (['review', 'decision'].includes(action.value)) body.verdict = actionForm.value.verdict
  if (['review', 'decision', 'cancel'].includes(action.value)) body.expectedAnalysisRevision = props.analysis.revision
  if (action.value === 'review' && body.verdict === 'ACCEPT' && selected.value.reply?.feasibility !== 'FEASIBLE') throw new Error('실행 가능 금액이 회신된 요청만 후보로 편입할 수 있습니다.')
  if (action.value === 'decision') {
    if (!actionForm.value.decisionMaker.trim() || !actionForm.value.decisionEvidenceRef.trim()) throw new Error('결정권자와 결정 근거를 입력해 주세요.')
    if (body.verdict === 'APPROVE' && !canApproveWork(props.analysis, selected.value)) throw new Error('근거 검토 후보를 최신 시나리오에 포함해 계산한 뒤 추진 결정을 기록해 주세요.')
    body.decisionMaker = actionForm.value.decisionMaker.trim(); body.decisionEvidenceRef = actionForm.value.decisionEvidenceRef.trim(); body.dueAt = dueAtKst(actionForm.value.dueDate)
  }
  const response = await workflowRequest(props.api, action.value, { itemId: selected.value.id, body }); acceptItem(response)
  if (['review', 'decision', 'cancel'].includes(action.value)) await props.refreshAnalysis()
  actionVisible.value = false; notice.value = action.value === 'review' && body.verdict === 'ACCEPT' ? '근거를 검토한 회신을 후보에 편입했습니다. 시나리오를 다시 계산해 주세요.' : '업무 상태와 이력을 저장했습니다.'
}
/** 확인한 상태 변경을 직렬 실행한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음. Side Effects: 업무 저장 요청.
 */
async function submitAction() { return run(actionWork) }
/** 해제된 화면에 응답이 반영되지 않도록 조회 세대를 변경한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 조회 세대·부모 진행 상태 변경.
 */
function dispose() { generation++; emit('busy', false) }
watch(analysisId, loadItems, { immediate: true })
watch(requestSource, openCreate, { immediate: true })
onBeforeUnmount(dispose)
</script>
<template><section class="workflow-panel"><div class="workflow-heading"><div><h3>조직 검토에서 실행 추적까지</h3><p>회신을 검토해 후보로 편입하고, 계산된 근거로 추진 결정을 기록합니다.</p></div><PButton label="새로 조회" icon="pi pi-refresh" text :disabled="busy" @click="loadItems" /></div><PMessage v-if="error" severity="error" :closable="false">{{ error }}</PMessage><PMessage v-if="notice" severity="success" :closable="false">{{ notice }}</PMessage><PMessage v-if="disabled" severity="warn" :closable="false">후보 변경을 저장한 뒤 조직 검토 업무를 진행해 주세요.</PMessage><div class="candidate-request"><PSelect v-model="candidateId" :options="analysis.candidates" option-label="title" option-value="id" placeholder="직접 입력한 후보도 조직에 확인 요청" :disabled="disabled || busy" aria-label="조직 확인을 요청할 후보" /><PButton label="요청 작성" outlined :disabled="disabled || busy || !candidateId" @click="chooseCandidate" /></div><div class="workflow-layout"><div class="work-list"><div v-if="!items.length" class="work-empty">아직 조직 검토 요청이 없습니다. 근거 조사에서 가설을 선택하거나 저장한 후보로 요청을 작성해 주세요.</div><PButton :unstyled="true" v-for="item in items" :key="item.id" type="button" class="work-item-button" :class="{ selected: selected?.id === item.id }" :disabled="busy" @click="openItem(item)"><PTag :value="workStateLabel(item.state)" :severity="workStateSeverity(item.state)" /><strong>{{ item.title }}</strong><span>{{ item.organizationName }} · {{ item.round }}차 검토</span></PButton></div><article v-if="selected" class="work-detail"><header><PTag :value="workStateLabel(selected.state)" :severity="workStateSeverity(selected.state)" /><h4>{{ selected.title }}</h4><p>{{ selected.organizationName }} · {{ selected.masterLabel }} · 요청 v{{ selected.revision }}</p></header><div class="work-message"><strong>조직에 확인할 내용</strong><p>{{ selected.message }}</p><small>회신 기한 {{ workDateText(selected.dueAt) }}</small></div><section v-if="selected.reply" class="work-section"><h5>조직 회신 · {{ selected.reply.feasibility === 'FEASIBLE' ? '개선 가능' : '개선 어려움' }}</h5><p>{{ selected.reply.note }}</p><p v-if="selected.reply.candidate">{{ selected.reply.candidate.type === 'REVENUE' ? '추가 매출' : '비용 차감 전 직접 증익' }} {{ money(selected.reply.candidate.amountWon) }}억 원 · {{ selected.reply.candidate.recognitionMonth }}월 반영</p><small>근거 {{ selected.reply.evidenceRef }}</small></section><section v-if="selected.review" class="work-section"><h5>경영기획 검토</h5><p>{{ selected.review.note }}</p></section><section v-if="selected.decision" class="work-section"><h5>추진 결정 기록 · {{ selected.decision.verdict === 'APPROVE' ? '추진' : '보류' }}</h5><p>{{ selected.decision.note }}</p><p>결정권자 {{ selected.decision.decisionMaker }} · 근거 {{ selected.decision.decisionEvidenceRef }}</p><p v-if="selected.decision.candidateEffectWon != null">추진 결정 당시 후보 순효과 {{ money(selected.decision.candidateEffectWon) }}억 원 · 조건부</p><small>판단한 분석 v{{ selected.decision.analysisRevision }} · 실행 기한 {{ workDateText(selected.decision.dueAt) }}</small></section><section v-if="selected.progress" class="work-section"><h5>후속 실행 기록</h5><p>{{ selected.progress.note }}</p><p>근거 {{ selected.progress.evidenceRef }}</p><template v-if="selected.progress.followupMasterId"><p>{{ selected.progress.followupMasterLabel }} · 조직 전망 {{ money(selected.progress.baselineProfitWon) }} → {{ money(selected.progress.followupProfitWon) }}억 원</p><PMessage severity="info" :closable="false">조직 연간 전망 변화 {{ money(selected.progress.profitChangeWon) }}억 원이며 이 과제의 실현 성과를 확정하지 않습니다.</PMessage></template></section><div class="work-actions"><PButton v-if="selected.state === 'DRAFT'" label="조직에 검토 요청 전달" icon="pi pi-send" :disabled="disabled || busy" @click="openAction('issue')" /><PButton v-if="selected.state === 'REPLIED'" label="회신 근거 검토" :disabled="disabled || busy" @click="openAction('review')" /><PButton v-if="selected.state === 'ACCEPTED'" label="추진 결정 기록" :disabled="disabled || busy" @click="openAction('decision')" /><PButton v-if="['DRAFT','REQUESTED','CHANGES_REQUESTED','REPLIED','ACCEPTED'].includes(selected.state)" label="요청 취소" text severity="danger" :disabled="disabled || busy" @click="openAction('cancel')" /></div><p v-if="selected.state === 'ACCEPTED' && !canApproveWork(analysis, selected)" class="work-hint">후보 편입으로 이전 계산이 무효화되었습니다. 시나리오를 실행해 효과와 제약을 확인한 뒤 추진 결정을 기록해 주세요.</p><section class="work-events"><h5>요청·검토 이력</h5><ol><li v-for="event in selected.events || []" :key="event.sequence"><strong>{{ event.note || '업무 상태 변경' }}</strong><small>{{ workDateText(event.at) }} · 기록자 {{ event.actorId }}</small></li></ol></section></article><div v-else class="work-empty">요청을 선택하면 회신, 검토, 결정과 실행 이력을 확인할 수 있습니다.</div></div>
<PDialog v-model:visible="createVisible" modal header="조직 검토 요청 초안" :style="{ width: '35rem', maxWidth: '95vw' }" :closable="!busy"><p><strong>{{ source?.title }}</strong></p><PMessage v-if="source?.kind === 'hypothesis'" severity="info" :closable="false">근거에서 찾은 가설입니다. 금액과 시점은 조직에 확인하며, 아직 전망에 반영되지 않습니다.</PMessage><div class="field"><label for="work-message">확인할 내용</label><PTextarea id="work-message" v-model="createForm.message" rows="5" maxlength="4000" :disabled="busy" /></div><div class="field"><label for="work-due">회신 기한 · 한국 시간</label><PInputText id="work-due" v-model="createForm.dueDate" type="date" :disabled="busy" /></div><PMessage v-if="error" severity="error" :closable="false">{{ error }}</PMessage><template #footer><PButton label="닫기" text :disabled="busy" @click="createVisible = false" /><PButton label="요청 초안 저장" :loading="busy" :disabled="disabled || busy" @click="createItem" /></template></PDialog>
<PDialog v-model:visible="actionVisible" modal :header="action === 'issue' ? '조직에 요청을 전달할까요?' : action === 'review' ? '회신 근거 검토' : action === 'decision' ? '추진 결정 기록' : '요청 취소'" :style="{ width: '36rem', maxWidth: '95vw' }" :closable="!busy"><template v-if="selected"><p><strong>{{ selected.organizationName }} · {{ selected.title }}</strong></p><PMessage v-if="action === 'issue'" severity="info" :closable="false">저장한 요청 내용과 기한이 해당 조직의 개선 검토 요청함에 전달됩니다.</PMessage><div v-if="['review','decision'].includes(action)" class="field"><label for="work-verdict">검토 결과</label><PSelect input-id="work-verdict" v-model="actionForm.verdict" :options="action === 'review' ? reviewOptions : decisionOptions" option-label="label" option-value="value" :disabled="busy" /></div><PMessage v-if="action === 'review' && actionForm.verdict === 'ACCEPT'" severity="warn" :closable="false">회신의 금액·기간·근거를 확인한 경우에만 채택해 주세요. 채택하면 근거 검토 후보로 편입되고 이전 계산 결과를 다시 생성해야 합니다.</PMessage><template v-if="action === 'decision'"><PMessage severity="info" :closable="false">이미 확인한 결정권자의 판단을 기록합니다. 이 입력 자체가 외부 결재를 대신하지 않습니다.</PMessage><div class="field"><label for="decision-maker">결정권자</label><PInputText id="decision-maker" v-model="actionForm.decisionMaker" maxlength="200" :disabled="busy" /></div><div class="field"><label for="decision-evidence">회의·결재 근거 위치</label><PInputText id="decision-evidence" v-model="actionForm.decisionEvidenceRef" maxlength="2000" :disabled="busy" /></div><div class="field"><label for="decision-due">실행 또는 재검토 기한 · 한국 시간</label><PInputText id="decision-due" v-model="actionForm.dueDate" type="date" :disabled="busy" /></div></template><div v-if="action !== 'issue'" class="field"><label for="work-note">검토·결정 내용</label><PTextarea id="work-note" v-model="actionForm.note" rows="4" maxlength="4000" :disabled="busy" /></div><PMessage v-if="error" severity="error" :closable="false">{{ error }}</PMessage></template><template #footer><PButton label="닫기" text :disabled="busy" @click="actionVisible = false" /><PButton :label="action === 'issue' ? '확인하고 조직에 전달' : '확인한 내용 저장'" :loading="busy" :disabled="disabled || busy" @click="submitAction" /></template></PDialog></section></template>
<style scoped>.workflow-heading{display:flex;justify-content:space-between;gap:15px}.workflow-heading h3{font-size:17px;margin:0 0 8px}.workflow-heading p{font-size:12px;color:#64768c;line-height:1.8;margin:0}.candidate-request{display:flex;gap:10px;margin:22px 0}.candidate-request>.p-select{flex:1;min-width:0}.workflow-layout{display:grid;grid-template-columns:230px minmax(0,1fr);gap:18px}.work-list{display:flex;flex-direction:column;gap:9px}.work-item-button{display:flex;flex-direction:column;gap:9px;align-items:start;text-align:left;border:1px solid #dbe3eb;background:white;padding:14px;border-radius:6px;cursor:pointer;color:#193047}.work-item-button.selected{border-color:#28619c;background:#f3f8fc}.work-item-button strong{font-size:13px;line-height:1.6}.work-item-button>span:last-child{font-size:11px;color:#64768c}.work-empty{padding:25px;background:#f6f8fb;border:1px dashed #dbe3eb;color:#64768c;font-size:12px;line-height:1.8;border-radius:6px}.work-detail{border:1px solid #dbe3eb;border-radius:6px;padding:20px;min-width:0}.work-detail header h4{font-size:18px;margin:12px 0}.work-detail header p,.work-detail small{font-size:11px;color:#64768c;line-height:1.8}.work-message{padding:15px;background:#f4f7fa;margin-top:20px}.work-message strong{font-size:12px}.work-message p,.work-section p{font-size:13px;line-height:1.8;white-space:pre-line;overflow-wrap:anywhere}.work-section{padding:16px 0;border-bottom:1px solid #dbe3eb}.work-section h5,.work-events h5{font-size:13px;margin:0 0 12px}.work-actions{display:flex;gap:9px;flex-wrap:wrap;margin-top:20px}.work-hint{font-size:12px;line-height:1.8;color:#64768c}.work-events{margin-top:25px}.work-events ol{padding-left:18px}.work-events li{padding:0 0 15px 3px}.work-events strong{font-size:12px;font-weight:500;white-space:pre-line}.work-events small{display:block;margin-top:4px}.field{display:flex;flex-direction:column;gap:8px;margin:18px 0}.field label{font-size:12px;font-weight:650}.field :deep(.p-inputtext),.field :deep(.p-select),.field :deep(.p-textarea){width:100%}.workflow-panel :deep(.p-message){margin:14px 0}.workflow-panel :deep(.p-message-text){font-size:12px;line-height:1.8}.workflow-panel :deep(.p-button){font-size:12px}.workflow-panel :deep(.p-tag){font-size:10px}@media(max-width:1100px){.workflow-layout{grid-template-columns:1fr}.work-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:650px){.work-list{grid-template-columns:1fr}.candidate-request{flex-direction:column}}
</style>
