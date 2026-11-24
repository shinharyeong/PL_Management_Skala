<script setup>
import copy from './ui-copy.json'
import {revealOrganization} from './reveal-organization.js'
import { computed, ref, watch } from 'vue'
import { usePrototypeStore } from './live-store.js'
import { statuses, statusTones, comparisonTypes, matchesConditions, valuesFor, differenceRate, seoulPeriod, seoulDeadline } from './domain.js'
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
const conditionError = ref(''), feedback = ref('')
const exclusionReason = ref('')
const activeCell = sessionField('activeCell'), detailSource = sessionField('detailSource'), dockOpen = ref(false), requestMessage = ref('차이 원인과 영향 기간, 거래별 산출 근거를 확인해 주세요.'), requestDue = ref(''), requestError = ref('')
const recipientIds = ref(store.organizations.map(o => o.id)), bulkDue = ref(''), bulkMessage = ref(`${month}월 실적 및 추정을 월간 시트 양식에 작성해 회신해 주세요.`), bulkError = ref('')
const currentOrg = computed(() => store.currentOrg)
// Preserve the original five-filter layout; reviewed items remain in the workspace.
const calculatorStatuses = statuses.filter(status => status !== '검토 완료')
if (!calculatorStatuses.includes(stage.value)) stage.value = calculatorStatuses[0]
const orgItems = computed(() => store.comparisonItems(store.activeOrg, applied.value))
const candidates = computed(() => applied.value ? orgItems.value.filter(i => matchesConditions(i, applied.value)) : [])
const counts = computed(() => Object.fromEntries(statuses.map(s => [s, candidates.value.filter(i => i.status === s).length])))
const rows = computed(() => candidates.value.filter(i => i.status === stage.value && `${i.name} ${i.group} ${i.cell}`.toLowerCase().includes(search.value.toLowerCase())).sort((a,b) => {
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
const detailItems = computed(() => detailSource.value === 'cart' ? cart.value : candidates.value)
const detail = computed(() => detailItems.value.find(i => (i.key || i.id) === activeCell.value) || detailItems.value[0])
const sameCartContext = computed(() => new Set(cart.value.map(i => JSON.stringify(i.conditions))).size <= 1)
const detailConditions = computed(() => detail.value?.conditions || applied.value || draft.value)
const requestCount = computed(() => cart.value.length)
const dateLabel = value => value ? new Intl.DateTimeFormat('ko-KR', { timeZone:'Asia/Seoul', month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false }).format(new Date(/Z$|[+-]\d\d:\d\d$/.test(value)?value:seoulDeadline(value))) : '미설정'
watch(() => props.page, page => { if (page !== 'monthly') feedback.value = ''; conditionError.value = ''; if (page !== 'cell') dockOpen.value = false })
watch(() => store.activeOrg, org => { expandedOrg.value = null; dockOpen.value = false; requestError.value = ''; requestDue.value = ''; requestMessage.value = '차이 원인과 영향 기간, 거래별 산출 근거를 확인해 주세요.' })
watch(rows, visible => { selection.value = selection.value.filter(id => visible.some(item => item.id === id)) })
async function calculate() {
  if (!draft.value.compareVersion) { conditionError.value = '비교 대상을 먼저 선택해 주세요.'; return }
  if(await store.run(()=>store.loadGap(store.activeOrg,draft.value.type))){applied.value = { ...draft.value }; selection.value = []; conditionError.value = ''; stage.value = statuses[0]}
}
function money(v) { return formatMoney(v, unit.value) }
function signed(v, displayUnit = unit.value) { return `${v > 0 ? '+' : ''}${formatMoney(String(v), displayUnit)}` }
function rate(base,current) {const r=percent(base,current);return r===null?'산정 불가':r+'%'}
function total(items){const values=items.map(i=>valuesFor(i,applied.value?.type||draft.value.type));return {base:sum(values.map(v=>v.base)),current:sum(values.map(v=>v.current))}}
function toggle(id) { selection.value = selection.value.includes(id) ? selection.value.filter(x => x !== id) : [...selection.value,id] }
function selectVisible(checked) { selection.value = checked ? rows.value.filter(i=>['검토 필요','검토 완료'].includes(i.status)).map(i => i.id) : [] }
async function addSelected() {
  const chosen = rows.value.filter(i => selection.value.includes(i.id))
  if (!chosen.length) return
  if(!await store.run(()=>store.addToCart(store.activeOrg, chosen, applied.value)))return
  selection.value = []; emit('navigate','workspace')
}
/** 선택한 항목의 제외 사유를 서버에 기록한 뒤 선택을 비운다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 오류로 표시).
 * Side Effects: 검토 상태 변경; 실패하면 선택과 사유를 유지한다.
 */
async function excludeSelected(){
  if(await store.run(()=>store.excludeItems(store.activeOrg,selection.value,exclusionReason.value))){selection.value=[];exclusionReason.value=''}
}
function openDetail(item,source='cart'){activeCell.value=item.id;detailSource.value=source;emit('navigate','cell')}
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
  if(await store.run(()=>store.createRequest('MONTHLY_ACTUAL',recipientIds.value,bulkDue.value,bulkMessage.value))){emit('navigate','monthly');feedback.value='월간 시트 요청을 보냈습니다.'}

}
async function exportCsv(){if(!store.currentSubmission)return;await store.run(async()=>{saveFile(await store.api('/api/v1/submissions/'+store.currentSubmission.id+'/comparison/file',{blob:true}),'버전비교.xlsx')})}
watch([feedback,conditionError,bulkError,requestError],(values,old=[])=>{
  values.forEach((message,index)=>{if(message&&message!==old[index])store.notify(message,index?'error':'info')})
})
watch([()=>props.page,()=>store.monthlyRequest],([page,request])=>{
  if(['monthly','monthly-request'].includes(page)&&!request)store.notify('이번 달 월간 시트 요청이 없습니다. 월실적/추정요청에서 요청을 작성해 주세요.')
},{immediate:true})
const pipeline = ['검토 필요','검토 완료','소명 진행중','재검토 필요','소명 승인 완료']
const pipelineTones = ['review','submitted','progress','recheck','approved']
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

</script>

<template>
  <div class="page-stack monthly-panel"><p v-if="!store.monthlyRequest" class="notice">이번 달 월간 시트 요청이 없습니다. 월실적/추정요청에서 요청을 작성해 주세요.</p>
    <header class="page-heading"><p class="breadcrumb">경영기획팀 / 월간 시트 관리</p><h1>{{ page === 'workspace' ? `${copy.pages.workspace}/${currentOrg.name}` : copy.pages[page] }}</h1><p v-if="['calculator','workspace','cell'].includes(page)">{{ currentOrg.name }} · {{ currentOrg.code }} · {{ year }}년 {{ month }}월</p></header>
    <div v-if="feedback" class="notice" role="status">{{ feedback }}</div>

    <template v-if="page === 'monthly'">
      <div class="row-between"><h2>{{ year }}년 {{ month }}월 · 이번 달</h2><button class="btn primary" @click="emit('navigate','monthly-request')">월실적/추정요청 및 현황</button></div>
      <p class="muted">전체 {{ store.organizations.length }}개 조직 · 제출 완료 {{ store.organizations.filter(o => o.submissionStatus === '제출 완료').length }} · 기한 경과 {{ store.organizations.filter(o=>o.submissionStatus==='기한 경과').length }} · 회신 대기 {{ store.organizations.filter(o=>o.submissionStatus==='회신 대기').length }}</p>
      <section class="card org-status"><h2>조직별 진행 현황</h2>
        <div class="table-scroll"><table><thead><tr><th>조직명</th><th>조직코드</th><th>발송일</th><th>{{ copy.monthly.replyDeadline }}</th><th>월간 제출 상태</th><th>소명 진행 상태</th></tr></thead><tbody><template v-for="org in store.organizations" :key="org.id"><tr :id="'monthly-org-'+org.id" class="org-row organization-reveal-row" :class="{expanded:expandedOrg === org.id}"><td><button class="text-button" :aria-expanded="expandedOrg === org.id" @click="expandedOrg = expandedOrg === org.id ? null : org.id; expandedOrg && store.run(()=>store.reply(org.id))">{{ org.name }} <span aria-hidden="true">{{ expandedOrg === org.id ? '⌃' : '⌄' }}</span></button></td><td>{{ org.code }}</td><td>{{ org.requestedAt ? dateLabel(org.requestedAt) : '—' }}</td><td>{{ org.requestDue ? dateLabel(org.requestDue) : '—' }}</td><td><span class="badge" :class="org.submissionStatus === '제출 완료' ? 'submitted' : org.submissionStatus === '기한 경과' ? 'overdue' : 'waiting'">{{ org.submissionStatus }}</span><small v-if="org.latestSubmission" class="monthly-submission-state">제출본 · {{ submissionStatusLabel(org.latestSubmission.status) }}</small></td><td><span class="badge" :class="org.explanationStatus === '소명 진행중' ? 'progress' : org.explanationStatus === '소명 회신' ? 'recheck' : ''">{{ org.explanationStatus }}</span></td></tr>
        <tr v-if="expandedOrg === org.id"><td colspan="6" class="reply-cell"><div class="reply-card"><div class="row-between"><strong>{{ org.author }} · {{ org.department }}</strong><small class="muted">{{ org.fileName ? dateLabel(org.submittedAt) : '회신 대기' }}</small></div><p>{{ org.message || '아직 회신이 없습니다.' }}</p><div class="row-between"><button v-if="org.fileName" class="btn subtle" @click="store.run(()=>store.downloadSubmission(org.submission.id,org.fileName))" :disabled="!org.submission || store.busy">{{ org.fileName }} ↓</button><button v-if="org.fileName" class="btn primary" @click="openCalculator(org.id)">Gap 계산하러 가기 →</button></div>
<section v-if="org.submission" class="monthly-submission-review" :aria-label="org.name+' 제출본 검토'">
  <div class="row-between"><h3>제출본 검토</h3><span class="badge" :class="org.submission.status==='PENDING'?'review':'approved'">{{ submissionStatusLabel(org.submission.status) }}</span></div>
  <p class="muted">{{ org.submission.label }} · 소명 승인 완료와 제출본 승인은 별도입니다. 제출본 승인은 월별 master 생성 조건에 반영됩니다.</p>
  <template v-if="org.submission.status==='PENDING'"><label class="field">경영기획 코멘트 (재요청 시 필수)<textarea v-model="submissionReviewNotes[org.submission.id]" rows="3" maxlength="4000" placeholder="제출본에 대한 검토 코멘트를 입력하세요." /></label><p class="muted caption">Gap 계산기에서 항목과 소명을 확인한 뒤 제출본을 승인해 주세요. 처리되지 않은 소명이 있으면 서버가 승인을 제한합니다.</p><div class="actions"><button class="btn" :disabled="store.busy || !submissionReviewNotes[org.submission.id]?.trim()" @click="decideMonthlySubmission(org,'REJECT')">재요청</button><button class="btn primary" :disabled="store.busy" @click="decideMonthlySubmission(org,'APPROVE')">월간 제출본 승인</button></div></template>
  <template v-else><p class="muted">{{ org.submission.status==='APPROVED' ? '승인 완료된 제출본입니다.' : '현재 제출본은 검토 대기 상태가 아닙니다.' }}</p><p v-if="org.submission.reviewComment">경영기획 코멘트 · {{ org.submission.reviewComment }}</p></template>
</section></div></td></tr></template></tbody></table></div>
      </section>
    </template>

    <form v-else-if="page === 'monthly-request'" class="page-stack" @submit.prevent="sendMonthly">
      <div class="card"><h2>요청 대상 조직</h2><div class="table-scroll"><table><thead><tr><th><input type="checkbox" aria-label="모든 조직 선택" :checked="recipientIds.length === store.organizations.length" @change="recipientIds = $event.target.checked ? store.organizations.map(o => o.id) : []" /></th><th>조직명</th><th>조직코드</th><th>수신 담당자</th></tr></thead><tbody><tr v-for="org in store.organizations" :key="org.id"><td><input v-model="recipientIds" type="checkbox" :value="org.id" :aria-label="`${org.name} 요청 대상 선택`" /></td><td>{{ org.name }}</td><td>{{ org.code }}</td><td>{{ org.author }}</td></tr></tbody></table></div></div>
      <div class="card request-fields"><label class="field">{{ copy.monthly.replyDeadline }}<input v-model="bulkDue" type="datetime-local" required /></label><label class="field">{{ copy.monthly.requestMessage }}<textarea v-model="bulkMessage" rows="5" required /></label><p v-if="bulkError" class="error" role="alert">{{ bulkError }}</p><p class="muted caption">요청 발송 후 조직별 진행 현황으로 이동합니다.</p><div class="actions"><button type="button" class="btn" @click="emit('navigate','monthly')">취소</button><button class="btn primary" type="submit" :disabled="store.busy || !recipientIds.length">{{ recipientIds.length }}개 조직에 월간 시트 요청 발송</button></div></div>
    </form>

    <template v-else-if="page === 'calculator'">
      <section class="card calculator-settings"><div class="field"><label>비교 대상</label><div class="version-controls"><label><span class="sr-only">기준 대상</span><select v-model="draft.baseVersion" aria-label="기준 대상" disabled title="현재 Gap 조회 API는 제출 시 고정된 기준을 사용합니다."><option value="fixed">기준 대상 · 서버에 고정된 비교 기준</option></select></label><label><span class="sr-only">현재 비교 대상</span><select v-model="draft.compareVersion" aria-label="현재 비교 대상"><option value="">비교 대상 · 선택하세요</option><option value="latest">최신 제출본</option></select></label><select v-model="draft.type" aria-label="비교 필터"><option v-for="type in comparisonTypes" :key="type">{{ type }}</option></select></div></div>
      <div class="field"><label>Gap 기준</label><div class="gap-controls"><button class="condition-toggle" :class="{on:draft.rateEnabled}" :aria-pressed="draft.rateEnabled" @click="draft.rateEnabled = !draft.rateEnabled"><span>차이율</span><b>5% 이상</b><span class="toggle-switch" :class="{on:draft.rateEnabled}" aria-hidden="true" /></button><div class="amount-condition" :class="{on:draft.amountEnabled}"><button :aria-pressed="draft.amountEnabled" @click="draft.amountEnabled = !draft.amountEnabled">최소 금액 <span class="toggle-switch" :class="{on:draft.amountEnabled}" aria-hidden="true" /></button><select v-model="draft.minimum" aria-label="최소 금액"><option :value="10000000">1천만원</option><option :value="50000000">5천만원</option><option :value="100000000">1억원</option></select></div><div class="actions"><button class="btn" :disabled="!applied" @click="exportCsv" title="현재 Gap 필터 결과가 아닌 서버의 버전 비교 전체를 다운로드합니다.">{{ copy.monthly.exportComparison }}</button><button class="btn primary" :disabled="store.busy" @click="calculate">{{ copy.monthly.apply }}</button></div></div></div><p v-if="conditionError" class="error" role="alert">{{ conditionError }}</p></section>
      <p v-if="store.currentSubmission?.status==='APPROVED'" class="notice">승인 완료된 제출본입니다. 비교 결과와 이력은 조회할 수 있으며 항목 선정은 검토 대기 제출본에서 가능합니다.</p><div class="status-tabs" role="group" aria-label="진행 단계별 현황"><button v-for="status in calculatorStatuses" :key="status" :class="[statusTones[statuses.indexOf(status)],{selected:stage === status}]" :aria-pressed="stage === status" @click="stage = status; selection = []">{{ status }} <b>{{ counts[status] || 0 }}</b></button></div>
      <div class="pipeline" tabindex="0" role="region" aria-label="검토 상태 흐름"><template v-for="(label,index) in pipeline" :key="label"><span v-if="index" class="pipeline-line" aria-hidden="true" /><span class="pipeline-step"><b class="badge" :class="pipelineTones[index]">{{ index + 1 }}</b><span>{{ label }}</span></span></template></div>
      <div class="result-toolbar"><div class="org-context"><small class="muted">조직</small><strong>{{ currentOrg.name }}</strong></div><label class="unit-field"><small class="muted">단위</small><select v-model="unit" aria-label="금액 표시 단위"><option value="THOUSAND">천원</option><option value="HUNDRED_MILLION">억원</option></select></label><label class="search-field"><span class="sr-only">항목 검색</span><input v-model="search" type="search" placeholder="항목명 검색" aria-label="항목명 검색" /></label><label class="sort-field"><small class="muted">정렬</small><select v-model="sort" aria-label="결과 정렬"><option>차액 내림차순</option><option>차액 오름차순</option><option>차이율 내림차순</option><option>차이율 오름차순</option></select></label></div>
      <div v-if="!applied" class="empty-state calculator-empty"><h3>비교 대상을 선택하고 기준을 적용해 주세요.</h3></div>
      <div v-else-if="!rows.length" class="empty-state"><h3>조건에 맞는 항목이 없습니다.</h3><p>진행 단계, 검색어 또는 금액 조건을 확인해 주세요.</p></div>
      <LiveGapTable v-else :rows="rows" :selected="selection" :unit="unit" :type="applied.type" :editable="store.currentSubmission?.status==='PENDING'" @select="toggle" @all="selectVisible" @detail="item=>openDetail(item,'calculator')" />
      <div v-if="applied" class="selection-bar"><strong>{{ selection.length }}개 항목 선택</strong><label class="field">제외 사유<input v-model="exclusionReason" maxlength="4000" placeholder="제외할 때 입력해 주세요" /></label><div class="actions"><button class="btn text" :disabled="!selection.length" @click="selection = []">{{ copy.monthly.clearSelection }}</button><button class="btn" :disabled="store.busy || !selection.length || !exclusionReason.trim() || ['소명 진행중','소명 승인 완료'].includes(stage)" @click="excludeSelected">{{ copy.monthly.exclude }}</button><button class="btn primary" :disabled="!selection.length || ['제외됨','소명 진행중','소명 승인 완료'].includes(stage)" @click="addSelected">{{ copy.monthly.reviewSelected }}</button></div></div>

    </template>

    <template v-else-if="page === 'workspace'">
      <div v-if="!cart.length" class="empty-state"><h3>담긴 항목이 없습니다.</h3><p>계산 결과에서 항목을 선택해 검토 대상으로 넘겨 주세요.</p><button class="btn primary" @click="emit('navigate','calculator')">계산기로 돌아가기</button></div>
      <template v-else><section v-if="sameCartContext" class="card snapshot-card"><div><small>조직 · 대상월</small><p>{{ currentOrg.name }} · {{ year }}년 {{ month }}월</p></div><div><small>기준 대상</small><p>제출 시 고정 기준</p></div><div><small>비교 대상</small><p>{{ store.currentSubmission?.label || '최신 제출본' }}</p></div><div><small>비교 필터</small><p>{{ cart[0].conditions.type }}</p></div><div><small>차이율 조건</small><p>{{ cart[0].conditions.rateEnabled ? '±5%' : '꺼짐' }}</p></div><div><small>최소 금액 조건</small><p>{{ cart[0].conditions.amountEnabled ? `${formatMoney(String(cart[0].conditions.minimum))}원` : '꺼짐' }}</p></div></section><p v-else class="notice">여러 계산 조건으로 담은 항목입니다. 항목별 비교 기준을 확인해 주세요.</p><h2>검토 대상 {{ cart.length }}개</h2><div class="table-scroll"><table><thead><tr><th>선택 항목</th><th class="number">기준값 (천원)</th><th class="number">실적·추정 (천원)</th><th class="number">차액 (천원)</th><th>진행 단계</th></tr></thead><tbody><tr v-for="item in cart" :key="item.key"><td><button class="text-button cell-link" @click="openDetail(item)">{{ item.name }}</button><small v-if="!sameCartContext" class="muted snapshot-hint">{{ item.conditions.type }} · {{ item.conditions.baseVersion }} → {{ item.conditions.compareVersion }} · {{ item.conditions.rateEnabled ? '±5%' : '차이율 꺼짐' }} · {{ item.conditions.amountEnabled ? `최소 ${formatMoney(String(item.conditions.minimum))}원` : '금액 조건 꺼짐' }}</small></td><td class="number">{{ formatMoney(String(valuesFor(item,item.conditions.type).base),'THOUSAND') }}</td><td class="number">{{ formatMoney(String(valuesFor(item,item.conditions.type).current),'THOUSAND') }}</td><td class="number">{{ signed(subtract(valuesFor(item,item.conditions.type).current,valuesFor(item,item.conditions.type).base),'THOUSAND') }}</td><td><span class="badge review">검토 대상</span></td></tr></tbody></table></div><div class="actions"><button class="btn" @click="emit('navigate','calculator')">계산기로 돌아가기</button><button class="btn primary" @click="openDetail(cart[0])">Gap 상세 내역 →</button></div></template>
    </template>

    <template v-else-if="page === 'cell'">
      <button class="btn back-button" @click="emit('navigate',detailSource === 'cart' ? 'workspace' : 'calculator')">← {{ detailSource === 'cart' ? '선택 항목 검토' : '계산 결과' }}</button>

      <div v-if="!detail" class="empty-state">계산 결과 또는 담긴 항목에서 상세 항목을 선택해 주세요.</div>
      <div v-else class="cell-layout"><aside class="answer-list"><h2>{{ copy.monthly.reviewItems }}</h2><button v-for="item in detailItems" :key="item.id" class="answer-card" :class="{active:activeCell===item.id}" @click="activeCell=item.id"><strong>{{ item.name }}</strong><small>{{ item.group }} / {{ item.category }}</small></button></aside><LiveReviewDetail :id="detail.id" /></div>
      <div v-if="requestCount" class="selection-bar"><p>이 조직의 검토 대상 {{ requestCount }}개를 소명 요청합니다.</p><button class="btn primary" @click="dockOpen = !dockOpen">{{ copy.monthly.requestExplanation }}</button></div><p class="muted caption">소명 승인과 제출본 승인·전사 반영은 별도 단계입니다. 신규 업로드는 제출본 승인 전까지 미확정입니다.</p>
    </template>
    <aside v-if="dockOpen" class="message-dock" role="region" aria-labelledby="request-dock-title"><header class="row-between"><div><h2 id="request-dock-title">{{ currentOrg.name }} · 일괄 소명 요청</h2><small class="muted">검토 대상 {{ requestCount }}개 · 화면을 보면서 메시지를 작성할 수 있습니다.</small></div><button class="btn text" aria-label="요청 작성 닫기" @click="dockOpen = false">{{ copy.monthly.close }}</button></header><label class="field">{{ copy.monthly.replyDeadline }}<input v-model="requestDue" type="datetime-local" required /></label><label class="field">{{ copy.monthly.requestMessage }}<textarea v-model="requestMessage" rows="3" required /></label><p v-if="requestError" class="error" role="alert">{{ requestError }}</p><div class="row-between"><small class="muted">선택한 항목에만 소명 요청을 보냅니다.</small><button class="btn primary" :disabled="store.busy" @click="sendRequest">{{ requestCount }}개 항목 소명 요청 보내기</button></div></aside>
  </div>
</template>
