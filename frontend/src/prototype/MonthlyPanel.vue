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
import {calculatorStages,calculatorStageCounts,matchesCalculatorStage,canSelectForReview,calculatorStage,defaultCalculatorStage} from './calculator-stages.js'
import {versionLabel} from './presentation.js'
import {revealOrganization} from './reveal-organization.js'
import {calculatorConditionsSnapshot,calculatorConditionsError,convertMinimumUnit} from './calculator-conditions.js'
import { computed, ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { usePrototypeStore } from './live-store.js'
import { valuesFor, seoulPeriod, seoulDeadline } from './domain.js'
import { formatMoney } from '../lib/decimal-display.js'
import './monthly.css'
import LiveReviewDetail from './LiveReviewDetail.vue'
import LiveGapTable from './LiveGapTable.vue'
import { sum, subtract, percent, compare } from './exact-money.js'
import {monthlyOrganizationStatus} from './monthly-progress.js'
import {matchesCalculatorConditions} from './calculator-values.js'

const panelElement=ref(null),actionBarElement=ref(null),actionBarStyle=ref({}),actionBarHeight=ref(130)
let actionBarObserver
function alignActionBar(){
  if(!panelElement.value||!actionBarElement.value)return
  const rect=panelElement.value.getBoundingClientRect()
  actionBarStyle.value={left:Math.max(8,rect.left)+'px',width:Math.min(rect.width,window.innerWidth-16)+'px'}
  actionBarHeight.value=actionBarElement.value.getBoundingClientRect().height
  const resultTable=panelElement.value.querySelector('.live-gap-table')
  if(resultTable){
    const available=window.innerHeight-resultTable.getBoundingClientRect().top-actionBarHeight.value-24
    panelElement.value.style.setProperty('--gap-result-max-height',Math.max(132,Math.min(720,available))+'px')
  }
}
watch([panelElement,actionBarElement],()=>{
  actionBarObserver?.disconnect()
  if(!panelElement.value||!actionBarElement.value)return
  actionBarObserver=new ResizeObserver(alignActionBar)
  actionBarObserver.observe(panelElement.value);actionBarObserver.observe(actionBarElement.value)
  alignActionBar()
},{flush:'post'})
onMounted(()=>window.addEventListener('resize',alignActionBar))
onBeforeUnmount(()=>{actionBarObserver?.disconnect();window.removeEventListener('resize',alignActionBar)})
const props = defineProps({ page: String })
const emit = defineEmits(['navigate'])
const store = usePrototypeStore()
const { year, month } = seoulPeriod()
const expandedOrg = ref(null)
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
const reviewComments = ref({})
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
if (!calculatorStages.some(item => item.label === stage.value)) stage.value = defaultCalculatorStage
const orgItems = computed(() => store.comparisonItems(store.activeOrg, applied.value))
const candidates = computed(() => applied.value ? orgItems.value.filter(i => matchesCalculatorConditions(i, applied.value)) : [])
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
const detailItems = computed(() => detailSource.value === 'recent' && store.recentCellTarget?.organizationId === store.activeOrg ? [store.recentCellTarget.item, ...cart.value.filter(item => String(item.id) !== String(store.recentCellTarget.id))] : cart.value)
const detail = computed(() => detailItems.value.find(i => (i.key || i.id) === activeCell.value) || detailItems.value[0])
const sameCartContext = computed(() => new Set(cart.value.map(i => JSON.stringify(i.conditions))).size <= 1)
const detailConditions = computed(() => detail.value?.conditions || applied.value || draft.value)
const requestCount = computed(() => cart.value.filter(canSelectForReview).length)
const dateLabel = value => value ? new Intl.DateTimeFormat('ko-KR', { timeZone:'Asia/Seoul', month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false }).format(new Date(/Z$|[+-]\d\d:\d\d$/.test(value)?value:seoulDeadline(value))) : '미설정'
watch(() => props.page, page => { if (page !== 'monthly') feedback.value = ''; conditionError.value = ''; if (page !== 'cell') dockOpen.value = false })
watch(() => store.activeOrg, org => { expandedOrg.value = null; dockOpen.value = false; requestError.value = ''; requestDue.value = ''; requestMessage.value = '차이 원인과 영향 기간, 거래별 산출 근거를 확인해 주세요.' })
watch(rows, visible => { selection.value = selection.value.filter(id => visible.some(item => item.id === id)) })
async function calculate() {
  const org=store.activeOrg,targetSession=store.monthlySession(org)
  let snapshot
  try { snapshot=calculatorConditionsSnapshot(draft.value) } catch(error) { conditionError.value=error.message; return }
  if(await store.run(()=>store.loadCalculator(org))){targetSession.applied=snapshot;targetSession.selection=[];targetSession.stage=defaultCalculatorStage;if(store.activeOrg===org)conditionError.value=''}
}
function money(v) { return formatMoney(v, unit.value) }
function signed(v, displayUnit = unit.value) { return `${v > 0 ? '+' : ''}${formatMoney(String(v), displayUnit)}` }
function rate(base,current) {const r=percent(base,current);return r===null?'산정 불가':r+'%'}
function total(items){const values=items.map(i=>valuesFor(i,applied.value?.type||draft.value.type));return {base:sum(values.map(v=>v.base)),current:sum(values.map(v=>v.current))}}
function toggle(id) { selection.value = selection.value.includes(id) ? selection.value.filter(x => x !== id) : [...selection.value,id] }
function selectVisible(checked,visibleIds) { selection.value = checked ? rows.value.filter(i=>canSelectForReview(i)&&(!visibleIds||visibleIds.includes(i.id))).map(i=>i.id) : [] }
async function addSelected() {
  const org=store.activeOrg,targetSession=store.monthlySession(org)
  const chosen = rows.value.filter(i => selection.value.includes(i.id) && canSelectForReview(i))
  if (!chosen.length) return
  if(!await store.run(()=>store.addToCart(org, chosen, applied.value)))return
  targetSession.activeCell=chosen[0].id;targetSession.detailSource='cart';targetSession.selection=[]
  if(store.activeOrg===org&&props.page==='calculator')emit('navigate','cell')
}
async function excludeSelected(){if(await store.run(()=>store.excludeItems(store.activeOrg,selection.value,'')))selection.value=[]}
function openDetail(item){if(!cart.value.some(row=>row.id===item.id))return;activeCell.value=item.id;detailSource.value='cart';emit('navigate','cell')}
function openCalculator(org) {store.activeOrg=org;emit('navigate','calculator')}
async function sendRequest() {
  const org=store.activeOrg
  requestError.value = ''
  if (!requestMessage.value.trim() || !requestDue.value) { requestError.value = '회신 기한과 요청 메시지를 입력해 주세요.'; return }
  if (!Number.isFinite(seoulDeadline(requestDue.value)) || seoulDeadline(requestDue.value) <= Date.now()) { requestError.value = '현재 시각 이후의 회신 기한을 선택해 주세요.'; return }
  if (await store.run(()=>store.sendExplanation(org, requestMessage.value, requestDue.value))) { store.notify('소명 요청을 보냈습니다.');if(store.activeOrg===org&&props.page==='cell'){dockOpen.value=false;expandedOrg.value=org;emit('navigate','monthly')} }
}

async function decideMonthly(org,decision){
  const submission=org.submission,comment=reviewComments.value[org.id]?.trim()||''
  if(!submission||submission.status!=='PENDING')return
  if(decision==='REJECT'&&!comment){store.notify('재제출 요청 사유를 입력해 주세요.','error');return}
  await store.run(async()=>{
    const result=await store.decideSubmission(submission,decision,comment)
    reviewComments.value[org.id]=''
    await store.loadMonthly()
    if(props.page==='monthly'&&expandedOrg.value===org.id)await store.reply(org.id)
    store.notify(decision==='REJECT'?'파일 재제출을 요청했습니다.':result?.masterPublished?'제출본을 승인하고 월간 master를 생성했습니다.':'제출본을 승인했습니다.')
  })
}
async function sendMonthly() {
  if (!recipientIds.value.length || !bulkDue.value || !bulkMessage.value.trim()) { bulkError.value = '대상 조직·회신 기한·요청 메시지를 확인해 주세요.'; return }
  if (!Number.isFinite(seoulDeadline(bulkDue.value)) || seoulDeadline(bulkDue.value) <= Date.now()) { bulkError.value = '현재 시각 이후의 회신 기한을 선택해 주세요.'; return }
  const additional = !!store.monthlyRequest
  if(await store.run(()=>store.createRequest('MONTHLY_ACTUAL',recipientIds.value,bulkDue.value,bulkMessage.value))){emit('navigate','monthly');feedback.value=additional?'선택한 미요청 조직에 월간 시트 요청을 추가 발송했습니다.':'월간 시트 요청을 보냈습니다.'}

}
watch([feedback,conditionError,bulkError,requestError],(values,old=[])=>{
  values.forEach((message,index)=>{if(message&&message!==old[index])store.notify(message,index?'error':'info')})
})
watch([()=>props.page,()=>store.monthlyRequest,()=>store.monthlyLoaded],([page,request,loaded])=>{
  if(loaded&&['monthly','monthly-request'].includes(page)&&!request)store.notify('이번 달 월간 시트 요청이 없습니다. 월간 시트 요청에서 요청을 작성해 주세요.')
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


const pipelineSeverities = ['warn','info','danger','success']
const expandedOrganizationRows = computed(() => expandedOrg.value == null ? {} : { [expandedOrg.value]: true })
const allRecipientsSelected = computed(() => requestRecipients.value.length > 0 && requestRecipients.value.every(org => recipientIds.value.includes(org.id)))
const someRecipientsSelected = computed(() => !allRecipientsSelected.value && requestRecipients.value.some(org => recipientIds.value.includes(org.id)))
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
  <div ref="panelElement" class="page-stack monthly-panel" :data-page="page"><p v-if="store.monthlyLoaded && !store.monthlyRequest" class="notice"><span data-copy-key="monthlyText.text001">{{ copy.monthlyText.text001 }}</span></p>
    <header class="page-heading"><h1><span :data-copy-key="'pages.'+page">{{ copy.pages[page] }}</span><template v-if="['calculator','workspace'].includes(page)">/{{currentOrg.name}}</template></h1><p v-if="['calculator','workspace','cell'].includes(page)">{{ currentOrg.name }} · {{ currentOrg.code }} · {{ year }}<span data-copy-key="monthlyText.text003">{{ copy.monthlyText.text003 }}</span> {{ month }}<span data-copy-key="monthlyText.text004">{{ copy.monthlyText.text004 }}</span></p><div v-if="page==='monthly'" class="monthly-heading-actions"><h2>{{ year }}<span data-copy-key="monthlyText.text003">{{ copy.monthlyText.text003 }}</span> {{ month }}<span data-copy-key="monthlyText.text005">{{ copy.monthlyText.text005 }}</span></h2><button class="btn primary" @click="emit('navigate','monthly-request')"><span data-copy-key="monthlyText.text006">{{ copy.monthlyText.text006 }}</span></button></div><button v-if="page==='cell'" class="btn" @click="emit('navigate','calculator')">← Gap 화면</button></header>
    <div v-if="feedback" class="notice" role="status">{{ feedback }}</div>


    <form v-if="page === 'monthly-request'" class="page-stack monthly-request-form" @submit.prevent="sendMonthly">

      <div class="card"><h2>{{ store.monthlyRequest ? '추가 요청 대상 조직' : '요청 대상 조직' }}</h2><div class="table-scroll"><table><thead><tr><th><input type="checkbox" aria-label="미요청 조직 전체 선택" :checked="requestRecipients.length > 0 && recipientIds.length === requestRecipients.length" @change="recipientIds = $event.target.checked ? requestRecipients.map(o => o.id) : []" /></th><th><span data-copy-key="monthlyText.text014">{{ copy.monthlyText.text014 }}</span></th><th><span data-copy-key="monthlyText.text015">{{ copy.monthlyText.text015 }}</span></th><th><span data-copy-key="monthlyText.text032">{{ copy.monthlyText.text032 }}</span></th></tr></thead><tbody><tr v-for="org in requestRecipients" :key="org.id"><td><input v-model="recipientIds" type="checkbox" :value="org.id" :aria-label="`${org.name} 요청 대상 선택`" /></td><td>{{ org.name }}</td><td>{{ org.code }}</td><td>{{ org.author }}</td></tr><tr v-if="!requestRecipients.length"><td colspan="4" class="muted"><span data-copy-key="monthlyText.text033">{{ copy.monthlyText.text033 }}</span></td></tr></tbody></table></div></div>
      <div class="card request-fields"><label class="field"><span data-copy-key="monthly.replyDeadline">{{ copy.monthly.replyDeadline }}</span><input v-model="bulkDue" type="datetime-local" required /></label><label class="field"><span data-copy-key="monthly.requestMessage">{{ copy.monthly.requestMessage }}</span><textarea v-model="bulkMessage" rows="5" required /></label><p v-if="bulkError" class="error" role="alert">{{ bulkError }}</p><div class="actions"><button type="button" class="btn" @click="emit('navigate','monthly')"><span data-copy-key="monthlyText.text035">{{ copy.monthlyText.text035 }}</span></button><button class="btn primary" type="submit" :disabled="store.busy || !recipientIds.length">{{ recipientIds.length }}<span :data-copy-key="store.monthlyRequest ? 'monthly.additionalSend' : 'monthly.initialSend'">{{ store.monthlyRequest ? copy.monthly.additionalSend : copy.monthly.initialSend }}</span></button></div></div>
    </form>
    <template v-if="page === 'monthly'">
      <section class="card org-status"><h2><span data-copy-key="monthlyText.text013">{{ copy.monthlyText.text013 }}</span></h2>
        <div class="table-scroll" tabindex="0" role="region" aria-label="월간 시트 조직별 진행 현황"><table><thead><tr><th><span data-copy-key="monthlyText.text014">{{ copy.monthlyText.text014 }}</span></th><th><span data-copy-key="monthlyText.text015">{{ copy.monthlyText.text015 }}</span></th><th><span data-copy-key="monthlyText.text016">{{ copy.monthlyText.text016 }}</span></th><th><span data-copy-key="monthly.replyDeadline">{{ copy.monthly.replyDeadline }}</span></th><th>수신 담당자</th><th><span data-copy-key="monthly.organizationStatus">{{ copy.monthly.organizationStatus }}</span></th><th><span data-copy-key="monthly.submissionVersion">{{ copy.monthly.submissionVersion }}</span></th></tr></thead><tbody><template v-for="org in store.organizations" :key="org.id"><tr :id="'monthly-org-'+org.id" class="org-row organization-reveal-row" :class="{expanded:expandedOrg === org.id}" @click="toggleOrganization(org)"><td><button class="text-button" :aria-expanded="expandedOrg === org.id" @click.stop="toggleOrganization(org)">{{ org.name }}</button></td><td>{{ org.code }}</td><td>{{ org.requestedAt ? dateLabel(org.requestedAt) : '—' }}</td><td>{{ org.requestDue ? dateLabel(org.requestDue) : '—' }}</td><td>{{ org.managers?.length ? org.managers.map(m => m.name + ' · ' + m.email).join(', ') : '—' }}</td><td><span class="badge monthly-organization-status" :class="monthlyOrganizationStatus(org)==='승인 완료'?'approved':monthlyOrganizationStatus(org)==='검토 필요'?'review':monthlyOrganizationStatus(org)==='요청 필요'?'not-requested':'waiting'">{{ monthlyOrganizationStatus(org) }}</span></td><td class="monthly-version">{{ org.latestSubmission ? versionLabel(org.latestSubmission.label) || '—' : '—' }}</td></tr>
        <tr v-if="expandedOrg === org.id"><td colspan="7" class="reply-cell"><div class="reply-card"><div class="row-between"><strong>{{ org.author }} · {{ org.department }}</strong><small class="muted">{{ org.fileName ? dateLabel(org.submittedAt) : org.monthlyRequested ? '회신 대기' : org.submissionStatus }}</small></div><p :data-copy-key="!org.message &amp;&amp; !org.monthlyRequested &amp;&amp; !!store.monthlyRequest ? 'monthly.notRequestedMessage' : undefined">{{ org.message || (org.monthlyRequested ? '아직 회신이 없습니다.' : !!store.monthlyRequest ? copy.monthly.notRequestedMessage : '이번 달 월간 시트 요청이 없습니다.') }}</p><div class="row-between"><button v-if="org.fileName" class="btn subtle" @click="store.run(()=>store.downloadSubmission(org.submission.id,org.fileName))" :disabled="!org.submission || store.busy">{{ org.fileName }} ↓</button><div class="actions"><button v-if="org.submission?.status==='PENDING'" type="button" class="btn primary" :disabled="store.busy" @click="decideMonthly(org,'APPROVE')">제출본 승인</button><button v-if="org.fileName" class="btn primary" @click="openCalculator(org.id)"><span data-copy-key="monthlyText.text020">{{ copy.monthlyText.text020 }}</span></button></div></div>

        </div></td></tr></template></tbody></table></div>
      </section>
    </template>

    <template v-if="page === 'calculator'">
      <section class="card calculator-settings">
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
          <div class="actions"><button class="btn primary" :disabled="store.busy || !!criteriaError" :title="criteriaError || undefined" :aria-describedby="criteriaError ? 'gap-criteria-error' : undefined" @click="calculate"><span data-copy-key="monthly.apply">{{ copy.monthly.apply }}</span></button></div>
        </div>
        <p v-if="criteriaError" id="gap-criteria-error" class="gap-criteria-warning" role="alert">{{ criteriaError }}</p>

      </div><p v-if="conditionError" class="error" role="alert">{{ conditionError }}</p></section>
      <p v-if="store.currentSubmission?.status==='APPROVED'" class="notice"><span data-copy-key="monthlyText.text043">{{ copy.monthlyText.text043 }}</span></p><div class="status-tabs calculator-status-tabs" role="group" aria-label="진행 단계별 현황"><button v-for="filter in calculatorStages" :key="filter.label" :class="[filter.tone,{selected:stage === filter.label}]" :aria-pressed="stage === filter.label" @click="stage = filter.label; selection = []">{{ filter.label }} <b>{{ counts[filter.label] || 0 }}</b></button></div>
      <div class="pipeline calculator-pipeline" tabindex="0" role="region" aria-label="검토 상태 흐름"><template v-for="(label,index) in pipeline" :key="label"><span v-if="index" class="pipeline-line" aria-hidden="true" /><span class="pipeline-step"><b class="badge" :class="pipelineTones[index]">{{ index + 1 }}</b><span>{{ label }}</span></span></template></div>

      <div class="result-toolbar"><div class="org-context"><small class="muted"><span data-copy-key="monthlyText.text044">{{ copy.monthlyText.text044 }}</span></small><strong>{{ currentOrg.name }}</strong></div><label class="unit-field"><small class="muted"><span data-copy-key="monthlyText.text045">{{ copy.monthlyText.text045 }}</span></small><select v-model="unit" aria-label="금액 표시 단위"><option value="THOUSAND">천원</option><option value="HUNDRED_MILLION">억원</option></select></label><label class="search-field"><span class="sr-only"><span data-copy-key="monthlyText.text046">{{ copy.monthlyText.text046 }}</span></span><input v-model="search" type="search" placeholder="항목명 검색" aria-label="항목명 검색" /></label><label class="sort-field"><small class="muted"><span data-copy-key="monthlyText.text047">{{ copy.monthlyText.text047 }}</span></small><select v-model="sort" aria-label="결과 정렬"><option value="차액 내림차순">계획 대비 차액 내림차순</option><option value="차액 오름차순">계획 대비 차액 오름차순</option><option value="차이율 내림차순">계획 대비 차이율 내림차순</option><option value="차이율 오름차순">계획 대비 차이율 오름차순</option></select></label></div>
      <div v-if="!applied" class="empty-state calculator-empty"><h3><span data-copy-key="monthlyText.text048">{{ copy.monthlyText.text048 }}</span></h3></div>
      <div v-else-if="!rows.length" class="empty-state"><h3><span data-copy-key="monthlyText.text049">{{ copy.monthlyText.text049 }}</span></h3><p><span data-copy-key="monthlyText.text050">{{ copy.monthlyText.text050 }}</span></p></div>
      <LiveGapTable v-else v-model:column-widths="columnWidths" grouped-statuses :rows="rows" :selected="selection" :unit="unit" :type="applied.type" :editable="store.currentSubmission?.status==='PENDING'" :openable-ids="cart.map(item => item.id)" @select="toggle" @all="selectVisible" @detail="openDetail" />
      <div v-if="applied" class="calculator-actions-space" :style="{height:(actionBarHeight+24)+'px'}" aria-hidden="true" /><div v-if="applied" ref="actionBarElement" class="selection-bar calculator-actions-dock" :style="actionBarStyle" role="region" aria-label="선택 항목 처리"><strong>{{ selection.length }}<span data-copy-key="monthlyText.text051">{{ copy.monthlyText.text051 }}</span></strong><div class="actions"><button class="btn text" :disabled="!selection.length" @click="selection = []"><span data-copy-key="monthly.clearSelection">{{ copy.monthly.clearSelection }}</span></button><button class="btn" :disabled="!selection.length || store.busy" @click="excludeSelected"><span data-copy-key="monthly.exclude">{{ copy.monthly.exclude }}</span></button><button class="btn primary" :disabled="!selection.length || store.busy" @click="addSelected"><span data-copy-key="monthly.reviewSelected">{{ copy.monthly.reviewSelected }}</span></button></div></div>

    </template>


    <template v-if="page === 'cell'">

      <div v-if="!detail" class="empty-state"><span data-copy-key="monthlyText.text069">{{ copy.monthlyText.text069 }}</span></div>
      <div v-else class="cell-layout"><aside class="answer-list"><h2><span data-copy-key="monthly.reviewItems">{{ copy.monthly.reviewItems }}</span></h2><button v-for="item in detailItems" :key="item.id" class="answer-card" :class="{active:activeCell===item.id}" @click="activeCell=item.id"><strong>{{ item.name }}</strong><small>{{ item.group }} / {{ item.category }}</small></button></aside><LiveReviewDetail :id="detail.id" /></div>
      <div v-if="requestCount" class="selection-bar"><p><span data-copy-key="monthlyText.text070">{{ copy.monthlyText.text070 }}</span> {{ requestCount }}<span data-copy-key="monthlyText.text071">{{ copy.monthlyText.text071 }}</span></p><button class="btn primary" @click="dockOpen = !dockOpen"><span data-copy-key="monthly.requestExplanation">{{ copy.monthly.requestExplanation }}</span></button></div>
    </template>
    <aside v-if="dockOpen" class="message-dock" role="region" aria-labelledby="request-dock-title"><header class="row-between"><div><h2 id="request-dock-title">{{ currentOrg.name }} <span data-copy-key="monthlyText.text073">{{ copy.monthlyText.text073 }}</span></h2><small class="muted"><span data-copy-key="monthlyText.text061">{{ copy.monthlyText.text061 }}</span> {{ requestCount }}개</small></div><button class="btn text" aria-label="요청 작성 닫기" @click="dockOpen = false"><span data-copy-key="monthly.close">{{ copy.monthly.close }}</span></button></header><label class="field"><span data-copy-key="monthly.replyDeadline">{{ copy.monthly.replyDeadline }}</span><input v-model="requestDue" type="datetime-local" required /></label><label class="field"><span data-copy-key="monthly.requestMessage">{{ copy.monthly.requestMessage }}</span><textarea v-model="requestMessage" rows="3" required /></label><p v-if="requestError" class="error" role="alert">{{ requestError }}</p><div class="row-between"><small class="muted"><span data-copy-key="monthlyText.text075">{{ copy.monthlyText.text075 }}</span></small><button class="btn primary" :disabled="store.busy" @click="sendRequest">{{ requestCount }}<span data-copy-key="monthlyText.text076">{{ copy.monthlyText.text076 }}</span></button></div></aside>
  </div>
</template>
