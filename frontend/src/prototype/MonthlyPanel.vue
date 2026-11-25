<script setup>
import copy from './copy-text.js'
import PButton from 'primevue/button'
import PCheckbox from 'primevue/checkbox'
import PColumn from 'primevue/column'
import PDataTable from 'primevue/datatable'
import PInputText from 'primevue/inputtext'
import PSelect from 'primevue/select'
import PTag from 'primevue/tag'
import PTextarea from 'primevue/textarea'
import {calculatorStages,calculatorStageCounts,matchesCalculatorStage,canSelectForReview} from './calculator-stages.js'
import {calculatorConditionsSnapshot,calculatorConditionsError,convertMinimumUnit} from './calculator-conditions.js'
import {revealOrganization} from './reveal-organization.js'
import { computed, ref, watch } from 'vue'
import { usePrototypeStore } from './live-store.js'
import { comparisonTypes, matchesConditions, valuesFor, differenceRate, seoulPeriod, seoulDeadline } from './domain.js'
import { formatMoney } from '../lib/decimal-display.js'
import './monthly.css'
import LiveReviewDetail from './LiveReviewDetail.vue'
import LiveGapTable from './LiveGapTable.vue'
import {saveFile,status as submissionStatusLabel} from './live-api.js'
import { sum, subtract, percent, compare } from './exact-money.js'

const props = defineProps({ page: String })
const emit = defineEmits(['navigate'])
const store = usePrototypeStore()
const { year, month } = seoulPeriod()
const expandedOrg = ref(null)
const submissionReviewNotes = ref({})
async function decideMonthlySubmission(org, decision) {
  const submission = org.submission
  if (!submission || submission.status !== 'PENDING') return
  const note = (submissionReviewNotes.value[submission.id] || '').trim()
  if (decision === 'REJECT' && !note) { feedback.value = '재요청하려면 경영기획 코멘트를 입력해 주세요.'; return }
  if (await store.run(async () => {
    await store.decideSubmission(submission, decision, note)
    await store.loadMonthly()
    await store.reply(org.id)
    await store.refreshBadge()
  })) {
    delete submissionReviewNotes.value[submission.id]
    feedback.value = decision === 'APPROVE' ? '월간 제출본을 승인했습니다. 전사 반영 여부는 월별 master 확정 상태에서 확인할 수 있습니다.' : '월간 제출본을 반려했습니다.'
    expandedOrg.value = org.id
  }
}
// Keep each organization's applied comparison across menu navigation and component remounts.
const session = computed(() => store.monthlySession(store.activeOrg))
const sessionField = key => computed({ get: () => session.value[key], set: value => { session.value[key] = value } })
const draft = sessionField('draft'), applied = sessionField('applied'), selection = sessionField('selection'), stage = sessionField('stage'), search = sessionField('search'), sort = sessionField('sort'), unit = sessionField('unit')
const columnWidths = sessionField('columnWidths')
const minimumUnit = computed({ get: () => session.value.minimumUnit === 'HUNDRED_MILLION' ? 'HUNDRED_MILLION' : 'THOUSAND', set: value => { session.value.minimumUnit = value } })
// draft.minimum is always KRW; toggling only changes the input's display unit.
const amountUnitLabel = computed(() => unit.value === 'HUNDRED_MILLION' ? '억원' : '천원')
const minimumInput = computed({ get: () => convertMinimumUnit(draft.value.minimum,'KRW',minimumUnit.value), set: value => { draft.value.minimum = convertMinimumUnit(value,minimumUnit.value,'KRW') } })
const conditionError = ref(''), feedback = ref('')
const criteriaError = computed(() => calculatorConditionsError(draft.value))
let criteriaToastId
watch([() => props.page, () => draft.value.rateEnabled, () => draft.value.amountEnabled], ([page,rateEnabled,amountEnabled]) => {
  if (page==='calculator' && !rateEnabled && !amountEnabled) { store.notify(calculatorConditionsError(draft.value),'error'); criteriaToastId=store.toast?.id }
  else if(criteriaToastId) { store.dismissToast(criteriaToastId); criteriaToastId=null }
}, {immediate:true})
const activeCell = sessionField('activeCell'), detailSource = sessionField('detailSource'), dockOpen = ref(false), requestMessage = ref('차이 원인과 영향 기간, 거래별 산출 근거를 확인해 주세요.'), requestDue = ref(''), requestError = ref('')
const requestRecipients = computed(() => store.organizations.filter(o => !store.monthlyRequest || !o.monthlyRequested))
const recipientIds = ref(requestRecipients.value.map(o => o.id)), bulkDue = ref(''), bulkMessage = ref(`${month}월 실적 및 추정을 월간 시트 양식에 작성해 회신해 주세요.`), bulkError = ref('')
const currentOrg = computed(() => store.currentOrg)
// Start on all states so selected workspace items remain visible on return.
if (!calculatorStages.some(item => item.label === stage.value)) stage.value = '전체'
const orgItems = computed(() => store.comparisonItems(store.activeOrg, applied.value))
const candidates = computed(() => applied.value ? orgItems.value.filter(i => matchesConditions(i, applied.value)) : [])
const counts = computed(() => calculatorStageCounts(candidates.value))
const rows = computed(() => candidates.value.filter(i => matchesCalculatorStage(i, stage.value) && `${i.name} ${i.group} ${i.cell}`.toLowerCase().includes(search.value.toLowerCase())).sort((a,b) => {
  const va = valuesFor(a, applied.value.type), vb = valuesFor(b, applied.value.type)
  const da = sort.value.includes('차이율') ? percent(va.base, va.current) : subtract(va.current,va.base)
  const db = sort.value.includes('차이율') ? percent(vb.base, vb.current) : subtract(vb.current,vb.base)
  return sort.value.includes('오름') ? compare(da,db) : compare(db,da)
}))
const groups = computed(() => [...new Set(rows.value.map(i => i.group))].map(name => {
  const items = rows.value.filter(i => i.group === name)
  const categories = [...new Set(items.map(i => i.category))].map(category => ({
    name: category,
    items: items.filter(i => i.category === category),
  }))
  return { name, items, categories, childRowCount: categories.reduce((count, category) => count + category.items.length + 2, 1) }
}))
const majorRowCount = computed(() => groups.value.reduce((count, group) => count + group.childRowCount + 1, 1))
const cart = computed(() => store.carts[store.activeOrg] || [])
const detailItems = computed(() => cart.value)
const detail = computed(() => detailItems.value.find(i => (i.key || i.id) === activeCell.value) || detailItems.value[0])
const sameCartContext = computed(() => new Set(cart.value.map(i => JSON.stringify(i.conditions))).size <= 1)
const detailConditions = computed(() => detail.value?.conditions || applied.value || draft.value)
const requestCount = computed(() => cart.value.length)
const dateLabel = value => value ? new Intl.DateTimeFormat('ko-KR', { timeZone:'Asia/Seoul', month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false }).format(new Date(/Z$|[+-]\d\d:\d\d$/.test(value)?value:seoulDeadline(value))) : '미설정'
watch(() => props.page, page => { if (page !== 'monthly') feedback.value = ''; conditionError.value = ''; if (page !== 'cell') dockOpen.value = false })
watch(() => store.activeOrg, org => { expandedOrg.value = null; dockOpen.value = false; requestError.value = ''; requestDue.value = ''; requestMessage.value = '차이 원인과 영향 기간, 거래별 산출 근거를 확인해 주세요.' })
watch(rows, visible => { selection.value = selection.value.filter(id => visible.some(item => item.id === id)) })
async function calculate() {
  let snapshot
  try { snapshot=calculatorConditionsSnapshot(draft.value) } catch(error) { conditionError.value=error.message; return }
  if (!draft.value.compareVersion) { conditionError.value = '비교 대상을 먼저 선택해 주세요.'; return }
  if(await store.run(()=>store.loadGap(store.activeOrg,snapshot.type))){applied.value = snapshot; selection.value = []; conditionError.value = ''; stage.value = '전체'}
}
function money(v) { return formatMoney(v, unit.value) }
function signed(v, displayUnit = unit.value) { return `${v > 0 ? '+' : ''}${formatMoney(String(v), displayUnit)}` }
function rate(base,current) {const r=percent(base,current);return r===null?'산정 불가':r+'%'}
function total(items){const values=items.map(i=>valuesFor(i,applied.value?.type||draft.value.type));return {base:sum(values.map(v=>v.base)),current:sum(values.map(v=>v.current))}}
function toggle(id) { selection.value = selection.value.includes(id) ? selection.value.filter(x => x !== id) : [...selection.value,id] }
function selectVisible(checked,visibleIds) { selection.value = checked ? rows.value.filter(i=>canSelectForReview(i)&&(!visibleIds||visibleIds.includes(i.id))).map(i=>i.id) : [] }
async function addSelected() {
  const chosen = rows.value.filter(i => selection.value.includes(i.id) && canSelectForReview(i))
  if (!chosen.length) return
  if(!await store.run(()=>store.addToCart(store.activeOrg, chosen, applied.value)))return
  selection.value = []; emit('navigate','workspace')
}
async function excludeSelected(){if(await store.run(()=>store.excludeItems(store.activeOrg,selection.value,exclusionReason.value))){selection.value=[];exclusionReason.value=''}}
function openDetail(item){if(!cart.value.some(row=>row.id===item.id))return;activeCell.value=item.id;detailSource.value='cart';emit('navigate','cell')}
function openCalculator(org) {store.activeOrg=org;emit('navigate','calculator')}
async function sendRequest() {
  requestError.value = ''
  if (!requestMessage.value.trim() || !requestDue.value) { requestError.value = '회신 기한과 요청 메시지를 입력해 주세요.'; return }
  if (!Number.isFinite(seoulDeadline(requestDue.value)) || seoulDeadline(requestDue.value) <= Date.now()) { requestError.value = '현재 시각 이후의 회신 기한을 선택해 주세요.'; return }
  if (await store.run(()=>store.sendExplanation(store.activeOrg, requestMessage.value, requestDue.value))) { dockOpen.value = false; expandedOrg.value = store.activeOrg; emit('navigate','monthly'); feedback.value = '소명 요청을 보냈습니다.' }
}
async function sendMonthly() {
  if (!recipientIds.value.length || !bulkDue.value || !bulkMessage.value.trim()) { bulkError.value = '대상 조직·회신 기한·요청 메시지를 확인해 주세요.'; return }
  if (!Number.isFinite(seoulDeadline(bulkDue.value)) || seoulDeadline(bulkDue.value) <= Date.now()) { bulkError.value = '현재 시각 이후의 회신 기한을 선택해 주세요.'; return }
  const additional = !!store.monthlyRequest
  if(await store.run(()=>store.createRequest('MONTHLY_ACTUAL',recipientIds.value,bulkDue.value,bulkMessage.value))){emit('navigate','monthly');feedback.value=additional?'선택한 미요청 조직에 월간 시트 요청을 추가 발송했습니다.':'월간 시트 요청을 보냈습니다.'}

}
async function exportCsv(){if(!store.currentSubmission)return;await store.run(async()=>{saveFile(await store.api('/api/v1/submissions/'+store.currentSubmission.id+'/comparison/file',{blob:true}),'버전비교.xlsx')})}
watch([feedback,conditionError,bulkError,requestError],(values,old=[])=>{
  values.forEach((message,index)=>{if(message&&message!==old[index])store.notify(message,index?'error':'info')})
})
watch([()=>props.page,()=>store.monthlyRequest,()=>store.monthlyLoaded],([page,request,loaded])=>{
  if(loaded&&['monthly','monthly-request'].includes(page)&&!request)store.notify('이번 달 월간 시트 요청이 없습니다. 월실적/추정요청에서 요청을 작성해 주세요.')
},{immediate:true})
watch([() => props.page, requestRecipients], ([page, recipients]) => {
  if (page === 'monthly-request') { recipientIds.value = recipients.map(o => o.id); bulkError.value = '' }
}, { immediate: true })
const pipeline = ['검토','소명','재검토','승인']
const pipelineTones = ['review','progress','recheck','approved']
watch(() => props.page, async page => {
  const target = store.notificationTarget
  if (page !== 'monthly' || !target?.organizationId) return
  store.notificationTarget = null
  if (target.year && (target.year !== year || target.month !== month)) {
    feedback.value = '이 알림은 이전 기간의 요청입니다. 현재 화면에는 이번 달 조직 현황이 표시됩니다.'
    return
  }
  expandedOrg.value = target.organizationId
  try { await store.reply(target.organizationId); await revealOrganization('monthly-org-'+target.organizationId,()=>props.page==='monthly'&&expandedOrg.value===target.organizationId) } catch (error) { store.error = error.message }
}, { immediate: true })


const exclusionReason = ref('')
const pipelineSeverities = ['warn','info','danger','success']
const expandedOrganizationRows = computed(() => expandedOrg.value == null ? {} : { [expandedOrg.value]: true })
const allRecipientsSelected = computed(() => requestRecipients.value.length > 0 && requestRecipients.value.every(org => recipientIds.value.includes(org.id)))
const someRecipientsSelected = computed(() => !allRecipientsSelected.value && requestRecipients.value.some(org => recipientIds.value.includes(org.id)))
const baseVersionOptions = [{ label: '기준 대상 · 서버에 고정된 비교 기준', value: 'fixed' }]
const compareVersionOptions = [{ label: '비교 대상 · 선택하세요', value: '' }, { label: '최신 제출본', value: 'latest' }]
// 최소 금액 선택지는 모두 안전한 정수 범위이며 기존 Gap 필터의 값 형식을 유지한다.
const unitOptions = [{ label: '천원', value: 'THOUSAND' }, { label: '억원', value: 'HUNDRED_MILLION' }]
const sortOptions = ['차액 내림차순', '차액 오름차순', '차이율 내림차순', '차이율 오름차순']

/** 조직 행을 한 개씩 펼치고 해당 조직의 최신 제출본을 조회한다.
 * Args: org 조직 정보. Returns: 없음. Raises: 없음(화면 오류로 표시).
 * Side Effects: 행 펼침 상태와 서버 제출본 정보를 갱신한다.
 */
function toggleOrganization(org) {
  expandedOrg.value = expandedOrg.value === org.id ? null : org.id
  if (expandedOrg.value != null) store.run(() => store.reply(org.id))
}

/** 요청 대상 전체 선택을 체크박스의 모델 값으로 적용한다.
 * Args: checked 전체 선택 여부. Returns: 없음. Raises: 없음. Side Effects: 요청 대상 ID 목록을 갱신한다.
 */
function selectRecipients(checked) { recipientIds.value = checked ? requestRecipients.value.map(org => org.id) : [] }

/** 월간 제출본 다운로드를 사용자의 버튼 동작으로 실행한다.
 * Args: org 제출본이 있는 조직. Returns: Promise<boolean>. Raises: 없음(화면 오류로 표시).
 * Side Effects: 서버 파일 조회와 브라우저 다운로드를 실행한다.
 */
function downloadMonthlySubmission(org) { return store.run(() => store.downloadSubmission(org.submission.id, org.fileName)) }


</script>

<template>
  <div class="page-stack monthly-panel"><p v-if="store.monthlyLoaded && !store.monthlyRequest" class="notice"><span data-copy-key="monthlyText.text001">{{ copy.monthlyText.text001 }}</span></p>
    <header class="page-heading"><p class="breadcrumb"><span data-copy-key="monthlyText.text002">{{ copy.monthlyText.text002 }}</span></p><h1><span :data-copy-key="'pages.'+page">{{ copy.pages[page] }}</span><template v-if="page==='workspace'">/{{currentOrg.name}}</template></h1><p v-if="['calculator','workspace','cell'].includes(page)">{{ currentOrg.name }} · {{ currentOrg.code }} · {{ year }}<span data-copy-key="monthlyText.text003">{{ copy.monthlyText.text003 }}</span> {{ month }}<span data-copy-key="monthlyText.text004">{{ copy.monthlyText.text004 }}</span></p></header>
    <div v-if="feedback" class="notice" role="status">{{ feedback }}</div>

    <template v-if="page === 'monthly'">
      <div class="row-between"><h2>{{ year }}<span data-copy-key="monthlyText.text003">{{ copy.monthlyText.text003 }}</span> {{ month }}<span data-copy-key="monthlyText.text005">{{ copy.monthlyText.text005 }}</span></h2><button class="btn primary" @click="emit('navigate','monthly-request')"><span data-copy-key="monthlyText.text006">{{ copy.monthlyText.text006 }}</span></button></div>
      <p class="muted"><span data-copy-key="monthlyText.text007">{{ copy.monthlyText.text007 }}</span> {{ store.organizations.length }}<span data-copy-key="monthlyText.text008">{{ copy.monthlyText.text008 }}</span> {{ store.organizations.filter(o => o.monthlyRequested).length }}<span data-copy-key="monthlyText.text009">{{ copy.monthlyText.text009 }}</span> {{ store.organizations.filter(o => !o.monthlyRequested).length }}<span data-copy-key="monthlyText.text010">{{ copy.monthlyText.text010 }}</span> {{ store.organizations.filter(o => o.submissionStatus === '제출 완료').length }} <span data-copy-key="monthlyText.text011">{{ copy.monthlyText.text011 }}</span> {{ store.organizations.filter(o=>o.submissionStatus==='기한 경과').length }} <span data-copy-key="monthlyText.text012">{{ copy.monthlyText.text012 }}</span> {{ store.organizations.filter(o=>o.monthlyRequested && o.submissionStatus==='회신 대기').length }}</p>
      <section class="card org-status"><h2><span data-copy-key="monthlyText.text013">{{ copy.monthlyText.text013 }}</span></h2>
        <div class="table-scroll"><table><thead><tr><th><span data-copy-key="monthlyText.text014">{{ copy.monthlyText.text014 }}</span></th><th><span data-copy-key="monthlyText.text015">{{ copy.monthlyText.text015 }}</span></th><th><span data-copy-key="monthlyText.text016">{{ copy.monthlyText.text016 }}</span></th><th><span data-copy-key="monthly.replyDeadline">{{ copy.monthly.replyDeadline }}</span></th><th><span data-copy-key="monthlyText.text017">{{ copy.monthlyText.text017 }}</span></th><th><span data-copy-key="monthlyText.text018">{{ copy.monthlyText.text018 }}</span></th></tr></thead><tbody><template v-for="org in store.organizations" :key="org.id"><tr :id="'monthly-org-'+org.id" class="org-row organization-reveal-row" :class="{expanded:expandedOrg === org.id}"><td><button class="text-button" :aria-expanded="expandedOrg === org.id" @click="expandedOrg = expandedOrg === org.id ? null : org.id; expandedOrg && store.run(()=>store.reply(org.id))">{{ org.name }} <span aria-hidden="true">{{ expandedOrg === org.id ? '⌃' : '⌄' }}</span></button></td><td>{{ org.code }}</td><td>{{ org.requestedAt ? dateLabel(org.requestedAt) : '—' }}</td><td>{{ org.requestDue ? dateLabel(org.requestDue) : '—' }}</td><td><span class="badge" :class="org.submissionStatus === '제출 완료' ? 'submitted' : org.submissionStatus === '기한 경과' ? 'overdue' : org.submissionStatus === '회신 대기' ? 'waiting' : 'not-requested'">{{ org.submissionStatus }}</span><small v-if="org.latestSubmission" class="monthly-submission-state">{{ org.latestSubmission.label || '버전명 없음' }} | {{ submissionStatusLabel(org.latestSubmission.status) }}</small></td><td><span class="badge" :class="org.explanationStatus === '소명 진행중' ? 'progress' : org.explanationStatus === '소명 회신' ? 'recheck' : ''">{{ org.explanationStatus }}</span></td></tr>
        <tr v-if="expandedOrg === org.id"><td colspan="6" class="reply-cell"><div class="reply-card"><div class="row-between"><strong>{{ org.author }} · {{ org.department }}</strong><small class="muted">{{ org.fileName ? dateLabel(org.submittedAt) : org.monthlyRequested ? '회신 대기' : org.submissionStatus }}</small></div><p :data-copy-key="!org.message &amp;&amp; !org.monthlyRequested &amp;&amp; !!store.monthlyRequest ? 'monthly.notRequestedMessage' : undefined">{{ org.message || (org.monthlyRequested ? '아직 회신이 없습니다.' : !!store.monthlyRequest ? copy.monthly.notRequestedMessage : '이번 달 월간 시트 요청이 없습니다.') }}</p><div class="row-between"><button v-if="org.fileName" class="btn subtle" @click="store.run(()=>store.downloadSubmission(org.submission.id,org.fileName))" :disabled="!org.submission || store.busy">{{ org.fileName }} ↓</button><button v-if="org.fileName" class="btn primary" @click="openCalculator(org.id)"><span data-copy-key="monthlyText.text020">{{ copy.monthlyText.text020 }}</span></button></div>
<section v-if="org.submission" class="monthly-submission-review" :aria-label="org.name+' 제출본 검토'">
  <div class="row-between"><h3><span data-copy-key="monthlyText.text021">{{ copy.monthlyText.text021 }}</span></h3><span class="badge" :class="org.submission.status==='PENDING'?'review':'approved'">{{ submissionStatusLabel(org.submission.status) }}</span></div>
  <p class="muted">{{ org.submission.label }} <span data-copy-key="monthlyText.text022">{{ copy.monthlyText.text022 }}</span></p>
  <template v-if="org.submission.status==='PENDING'"><label class="field"><span data-copy-key="monthlyText.text023">{{ copy.monthlyText.text023 }}</span><textarea v-model="submissionReviewNotes[org.submission.id]" rows="3" maxlength="4000" placeholder="제출본에 대한 검토 코멘트를 입력하세요." /></label><p class="muted caption"><span data-copy-key="monthlyText.text024">{{ copy.monthlyText.text024 }}</span></p><div class="actions"><button class="btn" :disabled="store.busy || !submissionReviewNotes[org.submission.id]?.trim()" @click="decideMonthlySubmission(org,'REJECT')"><span data-copy-key="monthlyText.text025">{{ copy.monthlyText.text025 }}</span></button><button class="btn primary" :disabled="store.busy" @click="decideMonthlySubmission(org,'APPROVE')"><span data-copy-key="monthlyText.text026">{{ copy.monthlyText.text026 }}</span></button></div></template>
  <template v-else><p class="muted">{{ org.submission.status==='APPROVED' ? '승인 완료된 제출본입니다.' : '현재 제출본은 검토 대기 상태가 아닙니다.' }}</p><p v-if="org.submission.reviewComment"><span data-copy-key="monthlyText.text027">{{ copy.monthlyText.text027 }}</span> {{ org.submission.reviewComment }}</p></template>
</section></div></td></tr></template></tbody></table></div>
      </section>
    </template>

    <form v-else-if="page === 'monthly-request'" class="page-stack" @submit.prevent="sendMonthly">
      <p v-if="store.monthlyRequest" class="notice"><span data-copy-key="monthlyText.text028">{{ copy.monthlyText.text028 }}</span></p>
      <div class="card"><h2>{{ store.monthlyRequest ? '추가 요청 대상 조직' : '요청 대상 조직' }}</h2><div class="table-scroll"><table><thead><tr><th><input type="checkbox" aria-label="미요청 조직 전체 선택" :checked="requestRecipients.length > 0 && recipientIds.length === requestRecipients.length" @change="recipientIds = $event.target.checked ? requestRecipients.map(o => o.id) : []" /></th><th><span data-copy-key="monthlyText.text014">{{ copy.monthlyText.text014 }}</span></th><th><span data-copy-key="monthlyText.text015">{{ copy.monthlyText.text015 }}</span></th><th><span data-copy-key="monthlyText.text032">{{ copy.monthlyText.text032 }}</span></th></tr></thead><tbody><tr v-for="org in requestRecipients" :key="org.id"><td><input v-model="recipientIds" type="checkbox" :value="org.id" :aria-label="`${org.name} 요청 대상 선택`" /></td><td>{{ org.name }}</td><td>{{ org.code }}</td><td>{{ org.author }}</td></tr><tr v-if="!requestRecipients.length"><td colspan="4" class="muted"><span data-copy-key="monthlyText.text033">{{ copy.monthlyText.text033 }}</span></td></tr></tbody></table></div></div>
      <div class="card request-fields"><label class="field"><span data-copy-key="monthly.replyDeadline">{{ copy.monthly.replyDeadline }}</span><input v-model="bulkDue" type="datetime-local" required /></label><label class="field"><span data-copy-key="monthly.requestMessage">{{ copy.monthly.requestMessage }}</span><textarea v-model="bulkMessage" rows="5" required /></label><p v-if="bulkError" class="error" role="alert">{{ bulkError }}</p><p class="muted caption"><span data-copy-key="monthlyText.text034">{{ copy.monthlyText.text034 }}</span></p><div class="actions"><button type="button" class="btn" @click="emit('navigate','monthly')"><span data-copy-key="monthlyText.text035">{{ copy.monthlyText.text035 }}</span></button><button class="btn primary" type="submit" :disabled="store.busy || !recipientIds.length">{{ recipientIds.length }}<span :data-copy-key="store.monthlyRequest ? 'monthly.additionalSend' : 'monthly.initialSend'">{{ store.monthlyRequest ? copy.monthly.additionalSend : copy.monthly.initialSend }}</span></button></div></div>
    </form>

    <template v-else-if="page === 'calculator'">
      <section class="card calculator-settings"><div class="field"><label><span data-copy-key="monthlyText.text036">{{ copy.monthlyText.text036 }}</span></label><div class="version-controls"><label><span class="sr-only"><span data-copy-key="monthlyText.text037">{{ copy.monthlyText.text037 }}</span></span><select v-model="draft.baseVersion" aria-label="기준 대상" disabled title="현재 Gap 조회 API는 제출 시 고정된 기준을 사용합니다."><option value="fixed">기준 대상 · 서버에 고정된 비교 기준</option></select></label><label><span class="sr-only"><span data-copy-key="monthlyText.text038">{{ copy.monthlyText.text038 }}</span></span><select v-model="draft.compareVersion" aria-label="현재 비교 대상"><option value="">비교 대상 · 선택하세요</option><option value="latest">최신 제출본</option></select></label><select v-model="draft.type" aria-label="비교 필터"><option v-for="type in comparisonTypes" :key="type">{{ type }}</option></select></div></div>
      <div class="field"><label><span data-copy-key="monthlyText.text039">{{ copy.monthlyText.text039 }}</span></label>
        <div class="gap-controls">
          <div class="custom-gap-condition" :class="{on:draft.rateEnabled}">
            <button type="button" class="gap-condition-toggle" :aria-pressed="draft.rateEnabled" @click="draft.rateEnabled = !draft.rateEnabled"><span data-copy-key="monthlyText.text040">{{ copy.monthlyText.text040 }}</span><span class="toggle-switch" :class="{on:draft.rateEnabled}" aria-hidden="true" /></button>
            <label class="gap-condition-input"><span class="sr-only">차이율 기준 (%)</span><input v-model="draft.rate" type="text" inputmode="decimal" aria-label="차이율 기준 (%)" :disabled="!draft.rateEnabled" :aria-invalid="draft.rateEnabled && !!criteriaError" placeholder="예: 5"><span>% 이상</span></label>
          </div>
          <div class="custom-gap-condition" :class="{on:draft.amountEnabled}">
            <button type="button" class="gap-condition-toggle" :aria-pressed="draft.amountEnabled" @click="draft.amountEnabled = !draft.amountEnabled"><span data-copy-key="monthlyText.text042">{{ copy.monthlyText.text042 }}</span><span class="toggle-switch" :class="{on:draft.amountEnabled}" aria-hidden="true" /></button>
            <label class="gap-condition-input amount-input"><span class="sr-only">최소 금액 기준</span><input v-model="minimumInput" type="text" inputmode="decimal" :aria-label="'최소 금액 기준 ('+(minimumUnit==='THOUSAND'?'천원':'억원')+')'" :disabled="!draft.amountEnabled" :aria-invalid="draft.amountEnabled && !!criteriaError" :placeholder="minimumUnit==='THOUSAND'?'예: 10,000':'예: 0.1'"></label>
            <div class="gap-amount-units" role="group" aria-label="최소 금액 입력 단위"><button type="button" :aria-pressed="minimumUnit==='THOUSAND'" :disabled="!draft.amountEnabled" @click="minimumUnit='THOUSAND'">천원</button><button type="button" :aria-pressed="minimumUnit==='HUNDRED_MILLION'" :disabled="!draft.amountEnabled" @click="minimumUnit='HUNDRED_MILLION'">억원</button></div><span class="gap-amount-suffix">이상</span>
          </div>
          <div class="actions"><button class="btn" :disabled="!applied" @click="exportCsv" title="현재 Gap 필터 결과가 아닌 서버의 버전 비교 전체를 다운로드합니다."><span data-copy-key="monthly.exportComparison">{{ copy.monthly.exportComparison }}</span></button><button class="btn primary" :disabled="store.busy || !!criteriaError" :title="criteriaError || undefined" :aria-describedby="criteriaError ? 'gap-criteria-error' : undefined" @click="calculate"><span data-copy-key="monthly.apply">{{ copy.monthly.apply }}</span></button></div>
        </div>
        <p v-if="criteriaError" id="gap-criteria-error" class="gap-criteria-warning" role="alert">{{ criteriaError }}</p>
        <p v-else class="muted caption"><span data-copy-key="monthly.criteriaHelp">{{copy.monthly.criteriaHelp}}</span></p>
      </div><p v-if="conditionError" class="error" role="alert">{{ conditionError }}</p></section>
      <p v-if="store.currentSubmission?.status==='APPROVED'" class="notice"><span data-copy-key="monthlyText.text043">{{ copy.monthlyText.text043 }}</span></p><div class="status-tabs" role="group" aria-label="진행 단계별 현황"><button v-for="filter in calculatorStages" :key="filter.label" :class="[filter.tone,{selected:stage === filter.label}]" :aria-pressed="stage === filter.label" @click="stage = filter.label; selection = []">{{ filter.label }} <b>{{ counts[filter.label] || 0 }}</b></button></div>
      <div class="pipeline calculator-pipeline" tabindex="0" role="region" aria-label="검토 상태 흐름"><template v-for="(label,index) in pipeline" :key="label"><span v-if="index" class="pipeline-line" aria-hidden="true" /><span class="pipeline-step"><b class="badge" :class="pipelineTones[index]">{{ index + 1 }}</b><span>{{ label }}</span></span></template></div>
      <div class="result-toolbar"><div class="org-context"><small class="muted"><span data-copy-key="monthlyText.text044">{{ copy.monthlyText.text044 }}</span></small><strong>{{ currentOrg.name }}</strong></div><label class="unit-field"><small class="muted"><span data-copy-key="monthlyText.text045">{{ copy.monthlyText.text045 }}</span></small><select v-model="unit" aria-label="금액 표시 단위"><option value="THOUSAND">천원</option><option value="HUNDRED_MILLION">억원</option></select></label><label class="search-field"><span class="sr-only"><span data-copy-key="monthlyText.text046">{{ copy.monthlyText.text046 }}</span></span><input v-model="search" type="search" placeholder="항목명 검색" aria-label="항목명 검색" /></label><label class="sort-field"><small class="muted"><span data-copy-key="monthlyText.text047">{{ copy.monthlyText.text047 }}</span></small><select v-model="sort" aria-label="결과 정렬"><option>차액 내림차순</option><option>차액 오름차순</option><option>차이율 내림차순</option><option>차이율 오름차순</option></select></label></div>
      <div v-if="!applied" class="empty-state calculator-empty"><h3><span data-copy-key="monthlyText.text048">{{ copy.monthlyText.text048 }}</span></h3></div>
      <div v-else-if="!rows.length" class="empty-state"><h3><span data-copy-key="monthlyText.text049">{{ copy.monthlyText.text049 }}</span></h3><p><span data-copy-key="monthlyText.text050">{{ copy.monthlyText.text050 }}</span></p></div>
      <LiveGapTable v-else v-model:column-widths="columnWidths" grouped-statuses :rows="rows" :selected="selection" :unit="unit" :type="applied.type" :editable="store.currentSubmission?.status==='PENDING'" @select="toggle" @all="selectVisible" />
      <div v-if="applied" class="selection-bar"><strong>{{ selection.length }}<span data-copy-key="monthlyText.text051">{{ copy.monthlyText.text051 }}</span></strong><label class="field">제외 사유<input v-model="exclusionReason" maxlength="4000" placeholder="제외할 때 입력해 주세요" /></label><div class="actions"><button class="btn text" :disabled="!selection.length" @click="selection = []"><span data-copy-key="monthly.clearSelection">{{ copy.monthly.clearSelection }}</span></button><button class="btn" :disabled="!selection.length || store.busy || !exclusionReason.trim()" @click="excludeSelected"><span data-copy-key="monthly.exclude">{{ copy.monthly.exclude }}</span></button><button class="btn primary" :disabled="!selection.length || store.busy" @click="addSelected"><span data-copy-key="monthly.reviewSelected">{{ copy.monthly.reviewSelected }}</span></button></div></div>

    </template>

    <template v-else-if="page === 'workspace'">
      <div v-if="cart.length" class="workspace-unit-bar"><span>금액 표시 단위</span><div class="gap-amount-units" role="group" aria-label="검토 화면 금액 표시 단위"><button type="button" :aria-pressed="unit==='THOUSAND'" @click="unit='THOUSAND'">천원</button><button type="button" :aria-pressed="unit==='HUNDRED_MILLION'" @click="unit='HUNDRED_MILLION'">억원</button></div></div>
      <div v-if="!cart.length" class="empty-state"><h3><span data-copy-key="monthlyText.text052">{{ copy.monthlyText.text052 }}</span></h3><p><span data-copy-key="monthlyText.text053">{{ copy.monthlyText.text053 }}</span></p><button class="btn primary" @click="emit('navigate','calculator')"><span data-copy-key="monthlyText.text054">{{ copy.monthlyText.text054 }}</span></button></div>
      <template v-else><section v-if="sameCartContext" class="card snapshot-card"><div><small><span data-copy-key="monthlyText.text055">{{ copy.monthlyText.text055 }}</span></small><p>{{ currentOrg.name }} · {{ year }}<span data-copy-key="monthlyText.text003">{{ copy.monthlyText.text003 }}</span> {{ month }}<span data-copy-key="monthlyText.text004">{{ copy.monthlyText.text004 }}</span></p></div><div><small><span data-copy-key="monthlyText.text037">{{ copy.monthlyText.text037 }}</span></small><p><span data-copy-key="monthlyText.text056">{{ copy.monthlyText.text056 }}</span></p></div><div><small><span data-copy-key="monthlyText.text036">{{ copy.monthlyText.text036 }}</span></small><p>{{ store.currentSubmission?.label || '최신 제출본' }}</p></div><div><small><span data-copy-key="monthlyText.text057">{{ copy.monthlyText.text057 }}</span></small><p>{{ cart[0].conditions.type }}</p></div><div><small><span data-copy-key="monthlyText.text058">{{ copy.monthlyText.text058 }}</span></small><p>{{ cart[0].conditions.rateEnabled ? `±${cart[0].conditions.rate}%` : '꺼짐' }}</p></div><div><small><span data-copy-key="monthlyText.text059">{{ copy.monthlyText.text059 }}</span></small><p>{{ cart[0].conditions.amountEnabled ? `${formatMoney(String(cart[0].conditions.minimum),unit)} ${amountUnitLabel}` : '꺼짐' }}</p></div></section><p v-else class="notice"><span data-copy-key="monthlyText.text060">{{ copy.monthlyText.text060 }}</span></p><h2><span data-copy-key="monthlyText.text061">{{ copy.monthlyText.text061 }}</span> {{ cart.length }}<span data-copy-key="monthlyText.text062">{{ copy.monthlyText.text062 }}</span></h2><div class="table-scroll"><table><thead><tr><th><span data-copy-key="monthlyText.text063">{{ copy.monthlyText.text063 }}</span></th><th class="number"><span data-copy-key="monthlyText.text064">{{ copy.monthlyText.text064 }}</span> ({{ amountUnitLabel }})</th><th class="number"><span data-copy-key="monthlyText.text065">{{ copy.monthlyText.text065 }}</span> ({{ amountUnitLabel }})</th><th class="number"><span data-copy-key="monthlyText.text066">{{ copy.monthlyText.text066 }}</span> ({{ amountUnitLabel }})</th><th><span data-copy-key="monthlyText.text067">{{ copy.monthlyText.text067 }}</span></th></tr></thead><tbody><tr v-for="item in cart" :key="item.key"><td><button class="text-button cell-link" @click="openDetail(item)">{{ item.name }}</button><small v-if="!sameCartContext" class="muted snapshot-hint">{{ item.conditions.type }} · {{ item.conditions.baseVersion }} → {{ item.conditions.compareVersion }} · {{ item.conditions.rateEnabled ? `±${item.conditions.rate}%` : '차이율 꺼짐' }} · {{ item.conditions.amountEnabled ? `최소 ${formatMoney(String(item.conditions.minimum),unit)} ${amountUnitLabel}` : '금액 조건 꺼짐' }}</small></td><td class="number">{{ formatMoney(String(valuesFor(item,item.conditions.type).base),unit) }}</td><td class="number">{{ formatMoney(String(valuesFor(item,item.conditions.type).current),unit) }}</td><td class="number">{{ signed(subtract(valuesFor(item,item.conditions.type).current,valuesFor(item,item.conditions.type).base),unit) }}</td><td><span class="badge review"><span data-copy-key="monthlyText.text061">{{ copy.monthlyText.text061 }}</span></span></td></tr></tbody></table></div><div class="actions"><button class="btn" @click="emit('navigate','calculator')"><span data-copy-key="monthlyText.text054">{{ copy.monthlyText.text054 }}</span></button><button class="btn primary" @click="openDetail(cart[0])"><span data-copy-key="monthlyText.text068">{{ copy.monthlyText.text068 }}</span></button></div></template>
    </template>

    <template v-else-if="page === 'cell'">
      <button class="btn back-button" @click="emit('navigate','workspace')">← 선택 항목 검토</button>

      <div v-if="!detail" class="empty-state"><span data-copy-key="monthlyText.text069">{{ copy.monthlyText.text069 }}</span></div>
      <div v-else class="cell-layout"><aside class="answer-list"><h2><span data-copy-key="monthly.reviewItems">{{ copy.monthly.reviewItems }}</span></h2><button v-for="item in detailItems" :key="item.id" class="answer-card" :class="{active:activeCell===item.id}" @click="activeCell=item.id"><strong>{{ item.name }}</strong><small>{{ item.group }} / {{ item.category }}</small></button></aside><LiveReviewDetail :id="detail.id" /></div>
      <div v-if="requestCount" class="selection-bar"><p><span data-copy-key="monthlyText.text070">{{ copy.monthlyText.text070 }}</span> {{ requestCount }}<span data-copy-key="monthlyText.text071">{{ copy.monthlyText.text071 }}</span></p><button class="btn primary" @click="dockOpen = !dockOpen"><span data-copy-key="monthly.requestExplanation">{{ copy.monthly.requestExplanation }}</span></button></div><p class="muted caption"><span data-copy-key="monthlyText.text072">{{ copy.monthlyText.text072 }}</span></p>
    </template>
    <aside v-if="dockOpen" class="message-dock" role="region" aria-labelledby="request-dock-title"><header class="row-between"><div><h2 id="request-dock-title">{{ currentOrg.name }} <span data-copy-key="monthlyText.text073">{{ copy.monthlyText.text073 }}</span></h2><small class="muted"><span data-copy-key="monthlyText.text061">{{ copy.monthlyText.text061 }}</span> {{ requestCount }}<span data-copy-key="monthlyText.text074">{{ copy.monthlyText.text074 }}</span></small></div><button class="btn text" aria-label="요청 작성 닫기" @click="dockOpen = false"><span data-copy-key="monthly.close">{{ copy.monthly.close }}</span></button></header><label class="field"><span data-copy-key="monthly.replyDeadline">{{ copy.monthly.replyDeadline }}</span><input v-model="requestDue" type="datetime-local" required /></label><label class="field"><span data-copy-key="monthly.requestMessage">{{ copy.monthly.requestMessage }}</span><textarea v-model="requestMessage" rows="3" required /></label><p v-if="requestError" class="error" role="alert">{{ requestError }}</p><div class="row-between"><small class="muted"><span data-copy-key="monthlyText.text075">{{ copy.monthlyText.text075 }}</span></small><button class="btn primary" :disabled="store.busy" @click="sendRequest">{{ requestCount }}<span data-copy-key="monthlyText.text076">{{ copy.monthlyText.text076 }}</span></button></div></aside>
  </div>
</template>
