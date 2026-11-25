<script setup>
import copy from './ui-copy.json'
import {revealOrganization} from './reveal-organization.js'
import { computed, ref, watch } from 'vue'
import PButton from 'primevue/button'
import PCheckbox from 'primevue/checkbox'
import PColumn from 'primevue/column'
import PDataTable from 'primevue/datatable'
import PInputText from 'primevue/inputtext'
import PSelect from 'primevue/select'
import PTag from 'primevue/tag'
import PTextarea from 'primevue/textarea'
import { usePrototypeStore } from './live-store.js'
import { statuses, comparisonTypes, matchesConditions, valuesFor, seoulPeriod, seoulDeadline } from './domain.js'
import { formatMoney } from '../lib/decimal-display.js'
import './monthly.css'
import LiveReviewDetail from './LiveReviewDetail.vue'
import LiveGapTable from './LiveGapTable.vue'
import {saveFile,status as submissionStatusLabel} from './live-api.js'
import { subtract, percent, compare } from './exact-money.js'

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
// 조직별로 적용한 비교 조건은 메뉴 이동과 화면 재생성 후에도 유지한다.
const session = computed(() => store.monthlySession(store.activeOrg))
const sessionField = key => computed({ get: () => session.value[key], set: value => { session.value[key] = value } })
const draft = sessionField('draft'), applied = sessionField('applied'), selection = sessionField('selection'), stage = sessionField('stage'), search = sessionField('search'), sort = sessionField('sort'), unit = sessionField('unit')
const conditionError = ref(''), feedback = ref('')
const exclusionReason = ref('')
const activeCell = sessionField('activeCell'), detailSource = sessionField('detailSource'), dockOpen = ref(false), requestMessage = ref('차이 원인과 영향 기간, 거래별 산출 근거를 확인해 주세요.'), requestDue = ref(''), requestError = ref('')
const recipientIds = ref(store.organizations.map(o => o.id)), bulkDue = ref(''), bulkMessage = ref(`${month}월 실적 및 추정을 월간 시트 양식에 작성해 회신해 주세요.`), bulkError = ref('')
const currentOrg = computed(() => store.currentOrg)
// 계산기에는 다섯 진행 상태를 표시하고 검토 완료 항목은 작업공간에서 관리한다.
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
const cart = computed(() => store.carts[store.activeOrg] || [])
const detailItems = computed(() => detailSource.value === 'cart' ? cart.value : candidates.value)
const detail = computed(() => detailItems.value.find(i => (i.key || i.id) === activeCell.value) || detailItems.value[0])
const sameCartContext = computed(() => new Set(cart.value.map(i => JSON.stringify(i.conditions))).size <= 1)
const requestCount = computed(() => cart.value.length)
const dateLabel = value => value ? new Intl.DateTimeFormat('ko-KR', { timeZone:'Asia/Seoul', month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false }).format(new Date(/Z$|[+-]\d\d:\d\d$/.test(value)?value:seoulDeadline(value))) : '미설정'
watch(() => props.page, page => { if (page !== 'monthly') feedback.value = ''; conditionError.value = ''; if (page !== 'cell') dockOpen.value = false })
watch(() => store.activeOrg, org => { expandedOrg.value = null; dockOpen.value = false; requestError.value = ''; requestDue.value = ''; requestMessage.value = '차이 원인과 영향 기간, 거래별 산출 근거를 확인해 주세요.' })
watch(rows, visible => { selection.value = selection.value.filter(id => visible.some(item => item.id === id)) })
async function calculate() {
  if (!draft.value.compareVersion) { conditionError.value = '비교 대상을 먼저 선택해 주세요.'; return }
  if(await store.run(()=>store.loadGap(store.activeOrg,draft.value.type))){applied.value = { ...draft.value }; selection.value = []; conditionError.value = ''; stage.value = statuses[0]}
}
/** 금액 문자열의 정밀도를 유지하면서 차액의 부호와 표시 단위를 적용한다.
 * Args: v 원 단위 금액; displayUnit 표시 단위. Returns: 표시 문자열.
 * Raises: 없음. Side Effects: 없음.
 */
function signed(v, displayUnit = unit.value) { return `${compare(v, '0') > 0 ? '+' : ''}${formatMoney(String(v), displayUnit)}` }
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
/** 계산기에서 연 상세 항목의 되돌아갈 화면을 보존한다.
 * Args: item 검토 항목. Returns: 없음. Raises: 없음. Side Effects: 상세 선택과 화면을 변경한다.
 */
function openCalculatorDetail(item) { openDetail(item, 'calculator') }
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
const pipelineSeverities = ['warn', 'info', 'info', 'danger', 'success']
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

const expandedOrganizationRows = computed(() => expandedOrg.value == null ? {} : { [expandedOrg.value]: true })
const allRecipientsSelected = computed(() => store.organizations.length > 0 && store.organizations.every(org => recipientIds.value.includes(org.id)))
const someRecipientsSelected = computed(() => !allRecipientsSelected.value && store.organizations.some(org => recipientIds.value.includes(org.id)))
const baseVersionOptions = [{ label: '기준 대상 · 서버에 고정된 비교 기준', value: 'fixed' }]
const compareVersionOptions = [{ label: '비교 대상 · 선택하세요', value: '' }, { label: '최신 제출본', value: 'latest' }]
// 최소 금액 선택지는 모두 안전한 정수 범위이며 기존 Gap 필터의 값 형식을 유지한다.
const minimumOptions = [{ label: '1천만원', value: 10000000 }, { label: '5천만원', value: 50000000 }, { label: '1억원', value: 100000000 }]
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
function selectRecipients(checked) { recipientIds.value = checked ? store.organizations.map(org => org.id) : [] }

/** 월간 제출본 다운로드를 사용자의 버튼 동작으로 실행한다.
 * Args: org 제출본이 있는 조직. Returns: Promise<boolean>. Raises: 없음(화면 오류로 표시).
 * Side Effects: 서버 파일 조회와 브라우저 다운로드를 실행한다.
 */
function downloadMonthlySubmission(org) { return store.run(() => store.downloadSubmission(org.submission.id, org.fileName)) }

</script>

<template>
  <div class="page-stack monthly-panel">
    <p v-if="!store.monthlyRequest" class="notice">이번 달 월간 시트 요청이 없습니다. 월실적/추정요청에서 요청을 작성해 주세요.</p>
    <header class="page-heading"><p class="breadcrumb">경영기획팀 / 월간 시트 관리</p><h1>{{ page === 'workspace' ? `${copy.pages.workspace}/${currentOrg.name}` : copy.pages[page] }}</h1><p v-if="['calculator', 'workspace', 'cell'].includes(page)">{{ currentOrg.name }} · {{ currentOrg.code }} · {{ year }}년 {{ month }}월</p></header>
    <div v-if="feedback" class="notice" role="status">{{ feedback }}</div>

    <template v-if="page === 'monthly'">
      <div class="row-between"><h2>{{ year }}년 {{ month }}월 · 이번 달</h2><PButton label="월실적/추정요청 및 현황" @click="emit('navigate', 'monthly-request')" /></div>
      <p class="muted">전체 {{ store.organizations.length }}개 조직 · 제출 완료 {{ store.organizations.filter(o => o.submissionStatus === '제출 완료').length }} · 기한 경과 {{ store.organizations.filter(o => o.submissionStatus === '기한 경과').length }} · 회신 대기 {{ store.organizations.filter(o => o.submissionStatus === '회신 대기').length }}</p>
      <section class="card org-status"><h2>조직별 진행 현황</h2>
        <PDataTable :value="store.organizations" :expandedRows="expandedOrganizationRows" dataKey="id" scrollable tableStyle="min-width: 900px" :pt="{ table: { 'aria-label': '조직별 월간 진행 현황' } }">
          <template #empty>조회 가능한 조직이 없습니다.</template>
          <PColumn header="조직명"><template #body="{ data: org }"><div :id="'monthly-org-' + org.id" class="organization-reveal-row"><PButton variant="text" :aria-expanded="expandedOrg === org.id" :label="org.name" :icon="expandedOrg === org.id ? 'pi pi-chevron-up' : 'pi pi-chevron-down'" iconPos="right" @click="toggleOrganization(org)" /></div></template></PColumn>
          <PColumn field="code" header="조직코드" />
          <PColumn header="발송일"><template #body="{ data: org }">{{ org.requestedAt ? dateLabel(org.requestedAt) : '—' }}</template></PColumn>
          <PColumn :header="copy.monthly.replyDeadline"><template #body="{ data: org }">{{ org.requestDue ? dateLabel(org.requestDue) : '—' }}</template></PColumn>
          <PColumn header="월간 제출 상태"><template #body="{ data: org }"><PTag :value="org.submissionStatus" :severity="org.submissionStatus === '제출 완료' ? 'success' : org.submissionStatus === '기한 경과' ? 'danger' : 'secondary'" /><small v-if="org.latestSubmission" class="monthly-submission-state">제출본 · {{ submissionStatusLabel(org.latestSubmission.status) }}</small></template></PColumn>
          <PColumn header="소명 진행 상태"><template #body="{ data: org }"><PTag :value="org.explanationStatus" :severity="org.explanationStatus === '소명 진행중' ? 'info' : org.explanationStatus === '소명 회신' ? 'warn' : 'secondary'" /></template></PColumn>
          <template #expansion="{ data: org }">
            <div class="reply-card"><div class="row-between"><strong>{{ org.author }} · {{ org.department }}</strong><small class="muted">{{ org.fileName ? dateLabel(org.submittedAt) : '회신 대기' }}</small></div><p>{{ org.message || '아직 회신이 없습니다.' }}</p>
              <div class="row-between"><PButton v-if="org.fileName" severity="secondary" variant="outlined" :label="org.fileName + ' ↓'" :disabled="!org.submission || store.busy" @click="downloadMonthlySubmission(org)" /><PButton v-if="org.fileName" label="Gap 계산하러 가기 →" @click="openCalculator(org.id)" /></div>
              <section v-if="org.submission" class="monthly-submission-review" :aria-label="org.name + ' 제출본 검토'">
                <div class="row-between"><h3>제출본 검토</h3><PTag :value="submissionStatusLabel(org.submission.status)" :severity="org.submission.status === 'PENDING' ? 'warn' : org.submission.status === 'APPROVED' ? 'success' : 'secondary'" /></div>
                <p class="muted">{{ org.submission.label }} · 소명 승인 완료와 제출본 승인은 별도입니다. 제출본 승인은 월별 master 생성 조건에 반영됩니다.</p>
                <template v-if="org.submission.status === 'PENDING'"><label class="field">경영기획 코멘트 (재요청 시 필수)<PTextarea v-model="submissionReviewNotes[org.submission.id]" rows="3" maxlength="4000" autoResize placeholder="제출본에 대한 검토 코멘트를 입력하세요." /></label><p class="muted caption">Gap 계산기에서 항목과 소명을 확인한 뒤 제출본을 승인해 주세요. 처리되지 않은 소명이 있으면 서버가 승인을 제한합니다.</p><div class="actions"><PButton severity="secondary" variant="outlined" :disabled="store.busy || !submissionReviewNotes[org.submission.id]?.trim()" label="재요청" @click="decideMonthlySubmission(org, 'REJECT')" /><PButton :disabled="store.busy" label="월간 제출본 승인" @click="decideMonthlySubmission(org, 'APPROVE')" /></div></template>
                <template v-else><p class="muted">{{ org.submission.status === 'APPROVED' ? '승인 완료된 제출본입니다.' : '현재 제출본은 검토 대기 상태가 아닙니다.' }}</p><p v-if="org.submission.reviewComment">경영기획 코멘트 · {{ org.submission.reviewComment }}</p></template>
              </section>
            </div>
          </template>
        </PDataTable>
      </section>
    </template>

    <form v-else-if="page === 'monthly-request'" class="page-stack" @submit.prevent="sendMonthly">
      <div class="card"><h2>요청 대상 조직</h2>
        <PDataTable :value="store.organizations" dataKey="id" scrollable tableStyle="min-width: 600px" :pt="{ table: { 'aria-label': '월간 요청 대상 조직' } }">
          <template #empty>요청 가능한 조직이 없습니다.</template>
          <PColumn style="width: 60px"><template #header><PCheckbox binary :modelValue="allRecipientsSelected" :indeterminate="someRecipientsSelected" :disabled="!store.organizations.length" aria-label="모든 조직 선택" @update:modelValue="selectRecipients" /></template><template #body="{ data: org }"><PCheckbox v-model="recipientIds" :value="org.id" :aria-label="`${org.name} 요청 대상 선택`" /></template></PColumn>
          <PColumn field="name" header="조직명" /><PColumn field="code" header="조직코드" /><PColumn field="author" header="수신 담당자" />
        </PDataTable>
      </div>
      <div class="card request-fields"><label class="field">{{ copy.monthly.replyDeadline }}<PInputText v-model="bulkDue" type="datetime-local" required /></label><label class="field">{{ copy.monthly.requestMessage }}<PTextarea v-model="bulkMessage" rows="5" autoResize required /></label><p v-if="bulkError" class="error" role="alert">{{ bulkError }}</p><p class="muted caption">요청 발송 후 조직별 진행 현황으로 이동합니다.</p><div class="actions"><PButton type="button" severity="secondary" variant="outlined" label="취소" @click="emit('navigate', 'monthly')" /><PButton type="submit" :disabled="store.busy || !recipientIds.length" :label="`${recipientIds.length}개 조직에 월간 시트 요청 발송`" /></div></div>
    </form>

    <template v-else-if="page === 'calculator'">
      <section class="card calculator-settings"><div class="field"><label>비교 대상</label><div class="version-controls"><PSelect v-model="draft.baseVersion" :options="baseVersionOptions" optionLabel="label" optionValue="value" aria-label="기준 대상" disabled title="현재 Gap 조회 API는 제출 시 고정된 기준을 사용합니다." /><PSelect v-model="draft.compareVersion" :options="compareVersionOptions" optionLabel="label" optionValue="value" aria-label="현재 비교 대상" /><PSelect v-model="draft.type" :options="comparisonTypes" aria-label="비교 필터" /></div></div>
        <div class="field"><label>Gap 기준</label><div class="gap-controls"><label class="gap-toggle-control"><PCheckbox v-model="draft.rateEnabled" binary aria-label="차이율 조건 사용" /><span>차이율 <strong>±5% 이상</strong></span></label><div class="gap-amount-control"><label class="gap-toggle-control"><PCheckbox v-model="draft.amountEnabled" binary aria-label="최소 금액 조건 사용" /><span>최소 금액</span></label><PSelect v-model="draft.minimum" :options="minimumOptions" optionLabel="label" optionValue="value" aria-label="최소 금액" /></div><div class="actions"><PButton severity="secondary" variant="outlined" :disabled="!applied || store.busy" :label="copy.monthly.exportComparison" title="현재 Gap 필터 결과가 아닌 서버의 버전 비교 전체를 다운로드합니다." @click="exportCsv" /><PButton :disabled="store.busy" :label="copy.monthly.apply" @click="calculate" /></div></div></div><p v-if="conditionError" class="error" role="alert">{{ conditionError }}</p>
      </section>
      <p v-if="store.currentSubmission?.status === 'APPROVED'" class="notice">승인 완료된 제출본입니다. 비교 결과와 이력은 조회할 수 있으며 항목 선정은 검토 대기 제출본에서 가능합니다.</p>
      <div class="status-tabs" role="group" aria-label="진행 단계별 현황"><PButton v-for="status in calculatorStatuses" :key="status" severity="secondary" :variant="stage === status ? undefined : 'outlined'" :class="{ selected: stage === status }" :aria-pressed="stage === status" @click="stage = status; selection = []">{{ status }} <b>{{ counts[status] || 0 }}</b></PButton></div>
      <div class="pipeline" tabindex="0" role="region" aria-label="검토 상태 흐름"><template v-for="(label, index) in pipeline" :key="label"><span v-if="index" class="pipeline-line" aria-hidden="true" /><span class="pipeline-step"><PTag :value="String(index + 1)" :severity="pipelineSeverities[index]" rounded /><span>{{ label }}</span></span></template></div>
      <div class="result-toolbar"><div class="org-context"><small class="muted">조직</small><strong>{{ currentOrg.name }}</strong></div><label class="unit-field"><small class="muted">단위</small><PSelect v-model="unit" :options="unitOptions" optionLabel="label" optionValue="value" aria-label="금액 표시 단위" /></label><label class="search-field"><span class="sr-only">항목 검색</span><PInputText v-model="search" type="search" placeholder="항목명 검색" aria-label="항목명 검색" /></label><label class="sort-field"><small class="muted">정렬</small><PSelect v-model="sort" :options="sortOptions" aria-label="결과 정렬" /></label></div>
      <div v-if="!applied" class="empty-state calculator-empty"><h3>비교 대상을 선택하고 기준을 적용해 주세요.</h3></div>
      <div v-else-if="!rows.length" class="empty-state"><h3>조건에 맞는 항목이 없습니다.</h3><p>진행 단계, 검색어 또는 금액 조건을 확인해 주세요.</p></div>
      <LiveGapTable v-else :rows="rows" :selected="selection" :unit="unit" :type="applied.type" :editable="store.currentSubmission?.status === 'PENDING' && !store.busy" @select="toggle" @all="selectVisible" @detail="openCalculatorDetail" />
      <div v-if="applied" class="selection-bar"><strong>{{ selection.length }}개 항목 선택</strong><label class="field">제외 사유<PInputText v-model="exclusionReason" maxlength="4000" placeholder="제외할 때 입력해 주세요" /></label><div class="actions"><PButton severity="secondary" variant="text" :disabled="!selection.length" :label="copy.monthly.clearSelection" @click="selection = []" /><PButton severity="secondary" variant="outlined" :disabled="store.busy || !selection.length || !exclusionReason.trim() || ['소명 진행중', '소명 승인 완료'].includes(stage)" :label="copy.monthly.exclude" @click="excludeSelected" /><PButton :disabled="store.busy || !selection.length || ['제외됨', '소명 진행중', '소명 승인 완료'].includes(stage)" :label="copy.monthly.reviewSelected" @click="addSelected" /></div></div>
    </template>

    <template v-else-if="page === 'workspace'">
      <div v-if="!cart.length" class="empty-state"><h3>담긴 항목이 없습니다.</h3><p>계산 결과에서 항목을 선택해 검토 대상으로 넘겨 주세요.</p><PButton label="계산기로 돌아가기" @click="emit('navigate', 'calculator')" /></div>
      <template v-else>
        <section v-if="sameCartContext" class="card snapshot-card"><div><small>조직 · 대상월</small><p>{{ currentOrg.name }} · {{ year }}년 {{ month }}월</p></div><div><small>기준 대상</small><p>제출 시 고정 기준</p></div><div><small>비교 대상</small><p>{{ store.currentSubmission?.label || '최신 제출본' }}</p></div><div><small>비교 필터</small><p>{{ cart[0].conditions.type }}</p></div><div><small>차이율 조건</small><p>{{ cart[0].conditions.rateEnabled ? '±5%' : '꺼짐' }}</p></div><div><small>최소 금액 조건</small><p>{{ cart[0].conditions.amountEnabled ? `${formatMoney(String(cart[0].conditions.minimum))}원` : '꺼짐' }}</p></div></section>
        <p v-else class="notice">여러 계산 조건으로 담은 항목입니다. 항목별 비교 기준을 확인해 주세요.</p><h2>검토 대상 {{ cart.length }}개</h2>
        <PDataTable :value="cart" dataKey="id" scrollable tableStyle="min-width: 850px" :pt="{ table: { 'aria-label': '선택한 검토 대상' } }">
          <PColumn header="선택 항목"><template #body="{ data: item }"><PButton variant="text" class="cell-link" :label="item.name" @click="openDetail(item)" /><small v-if="!sameCartContext" class="muted snapshot-hint">{{ item.conditions.type }} · {{ item.conditions.baseVersion }} → {{ item.conditions.compareVersion }} · {{ item.conditions.rateEnabled ? '±5%' : '차이율 꺼짐' }} · {{ item.conditions.amountEnabled ? `최소 ${formatMoney(String(item.conditions.minimum))}원` : '금액 조건 꺼짐' }}</small></template></PColumn>
          <PColumn header="기준값 (천원)" bodyClass="number"><template #body="{ data: item }">{{ formatMoney(String(valuesFor(item, item.conditions.type).base), 'THOUSAND') }}</template></PColumn>
          <PColumn header="실적·추정 (천원)" bodyClass="number"><template #body="{ data: item }">{{ formatMoney(String(valuesFor(item, item.conditions.type).current), 'THOUSAND') }}</template></PColumn>
          <PColumn header="차액 (천원)" bodyClass="number"><template #body="{ data: item }">{{ signed(subtract(valuesFor(item, item.conditions.type).current, valuesFor(item, item.conditions.type).base), 'THOUSAND') }}</template></PColumn>
          <PColumn header="진행 단계"><template #body><PTag value="검토 대상" severity="info" /></template></PColumn>
        </PDataTable>
        <div class="actions"><PButton severity="secondary" variant="outlined" label="계산기로 돌아가기" @click="emit('navigate', 'calculator')" /><PButton label="Gap 상세 내역 →" @click="openDetail(cart[0])" /></div>
      </template>
    </template>

    <template v-else-if="page === 'cell'">
      <PButton severity="secondary" variant="outlined" class="back-button" :label="`← ${detailSource === 'cart' ? '선택 항목 검토' : '계산 결과'}`" @click="emit('navigate', detailSource === 'cart' ? 'workspace' : 'calculator')" />
      <div v-if="!detail" class="empty-state">계산 결과 또는 담긴 항목에서 상세 항목을 선택해 주세요.</div>
      <div v-else class="cell-layout"><aside class="answer-list"><h2>{{ copy.monthly.reviewItems }}</h2><PButton v-for="item in detailItems" :key="item.id" severity="secondary" variant="outlined" class="answer-card" :class="{ active: activeCell === item.id }" :aria-pressed="activeCell === item.id" @click="activeCell = item.id"><strong>{{ item.name }}</strong><small>{{ item.group }} / {{ item.category }}</small></PButton></aside><LiveReviewDetail :id="detail.id" /></div>
      <div v-if="requestCount" class="selection-bar"><p>이 조직의 검토 대상 {{ requestCount }}개를 소명 요청합니다.</p><PButton :label="copy.monthly.requestExplanation" @click="dockOpen = !dockOpen" /></div><p class="muted caption">소명 승인과 제출본 승인·전사 반영은 별도 단계입니다. 신규 업로드는 제출본 승인 전까지 미확정입니다.</p>
    </template>
    <aside v-if="dockOpen" class="message-dock" role="region" aria-labelledby="request-dock-title"><header class="row-between"><div><h2 id="request-dock-title">{{ currentOrg.name }} · 일괄 소명 요청</h2><small class="muted">검토 대상 {{ requestCount }}개 · 화면을 보면서 메시지를 작성할 수 있습니다.</small></div><PButton severity="secondary" variant="text" aria-label="요청 작성 닫기" :label="copy.monthly.close" @click="dockOpen = false" /></header><label class="field">{{ copy.monthly.replyDeadline }}<PInputText v-model="requestDue" type="datetime-local" required /></label><label class="field">{{ copy.monthly.requestMessage }}<PTextarea v-model="requestMessage" rows="3" autoResize required /></label><p v-if="requestError" class="error" role="alert">{{ requestError }}</p><div class="row-between"><small class="muted">선택한 항목에만 소명 요청을 보냅니다.</small><PButton :disabled="store.busy" :label="`${requestCount}개 항목 소명 요청 보내기`" @click="sendRequest" /></div></aside>
  </div>
</template>
