<script setup>
import { computed, ref, watch } from 'vue'
import { usePrototypeStore } from './store.js'
import { statuses, statusTones, comparisonTypes, matchesConditions, valuesFor, differenceRate, seoulPeriod, seoulDeadline } from './domain.js'
import { formatMoney } from '../lib/decimal-display.js'
import './monthly.css'

const props = defineProps({ page: String })
const emit = defineEmits(['navigate'])
const store = usePrototypeStore()
const { year, month } = seoulPeriod()
const expandedOrg = ref(store.activeOrg)
// Keep each organization's applied comparison across menu navigation and component remounts.
const session = computed(() => store.monthlySession(store.activeOrg))
const sessionField = key => computed({ get: () => session.value[key], set: value => { session.value[key] = value } })
const draft = sessionField('draft'), applied = sessionField('applied'), selection = sessionField('selection'), stage = sessionField('stage'), search = sessionField('search'), sort = sessionField('sort'), unit = sessionField('unit')
const conditionError = ref(''), feedback = ref('')
const activeCell = sessionField('activeCell'), detailSource = sessionField('detailSource'), dockOpen = ref(false), requestMessage = ref('차이 원인과 영향 기간, 거래별 산출 근거를 확인해 주세요.'), requestDue = ref(''), requestError = ref('')
const recipientIds = ref(store.organizations.map(o => o.id)), bulkDue = ref(''), bulkMessage = ref(`${month}월 실적 및 추정을 월간 시트 양식에 작성해 회신해 주세요.`), bulkError = ref('')
const currentOrg = computed(() => store.currentOrg)
const orgItems = computed(() => store.comparisonItems(store.activeOrg, applied.value))
const candidates = computed(() => applied.value ? orgItems.value.filter(i => matchesConditions(i, applied.value)) : [])
const counts = computed(() => Object.fromEntries(statuses.map(s => [s, candidates.value.filter(i => i.status === s).length])))
const rows = computed(() => candidates.value.filter(i => i.status === stage.value && `${i.name} ${i.group} ${i.cell}`.toLowerCase().includes(search.value.toLowerCase())).sort((a,b) => {
  const va = valuesFor(a, applied.value.type), vb = valuesFor(b, applied.value.type)
  const da = sort.value.includes('차이율') ? differenceRate(va.base, va.current) ?? Infinity : va.current - va.base
  const db = sort.value.includes('차이율') ? differenceRate(vb.base, vb.current) ?? Infinity : vb.current - vb.base
  return sort.value.includes('오름') ? da - db : db - da
}))
const groups = computed(() => [...new Set(rows.value.map(i => i.group))].map(name => ({ name, items: rows.value.filter(i => i.group === name) })))
const cart = computed(() => store.carts[store.activeOrg] || [])
const detailItems = computed(() => detailSource.value === 'cart' ? cart.value : candidates.value)
const detail = computed(() => detailItems.value.find(i => (i.key || i.id) === activeCell.value) || detailItems.value[0])
const sameCartContext = computed(() => new Set(cart.value.map(i => JSON.stringify(i.conditions))).size <= 1)
const detailConditions = computed(() => detail.value?.conditions || applied.value || draft.value)
const requestCount = computed(() => cart.value.length)
const dateLabel = value => value ? new Intl.DateTimeFormat('ko-KR', { timeZone:'Asia/Seoul', month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false }).format(new Date(seoulDeadline(value))) : '미설정'
watch(() => props.page, page => { if (page !== 'monthly') feedback.value = ''; conditionError.value = ''; if (page !== 'cell') dockOpen.value = false })
watch(() => store.activeOrg, org => { expandedOrg.value = org; dockOpen.value = false; requestError.value = ''; requestDue.value = ''; requestMessage.value = '차이 원인과 영향 기간, 거래별 산출 근거를 확인해 주세요.' })
watch(rows, visible => { selection.value = selection.value.filter(id => visible.some(item => item.id === id)) })
function calculate() {
  if (!draft.value.compareVersion) { conditionError.value = '비교 대상을 먼저 선택해 주세요.'; return }
  applied.value = { ...draft.value }; selection.value = []; conditionError.value = ''; stage.value = statuses[0]
}
function money(v) { return formatMoney(String(v), unit.value) }
function signed(v, displayUnit = unit.value) { return `${v > 0 ? '+' : ''}${formatMoney(String(v), displayUnit)}` }
function rate(base, current) { const r = differenceRate(base,current); return r === null ? '산정 불가' : `${r > 0 ? '+' : ''}${r.toFixed(1)}%` }
function total(items) { return items.reduce((r,i) => { const v = valuesFor(i, applied.value?.type || draft.value.type); r.base += v.base; r.current += v.current; return r }, { base:0,current:0 }) }
function toggle(id) { selection.value = selection.value.includes(id) ? selection.value.filter(x => x !== id) : [...selection.value,id] }
function selectVisible(checked) { selection.value = checked ? rows.value.map(i => i.id) : [] }
function addSelected() {
  const chosen = rows.value.filter(i => selection.value.includes(i.id))
  if (!chosen.length) return
  store.addToCart(store.activeOrg, chosen, applied.value)
  selection.value = []; emit('navigate','workspace')
}
function excludeSelected() { store.excludeItems(store.activeOrg, rows.value.filter(i => selection.value.includes(i.id)).map(i => i.id), applied.value); selection.value = [] }
function openDetail(item, source = 'cart') { activeCell.value = item.key || item.id; detailSource.value = source; emit('navigate','cell') }
function sourceValue(weight, version) {
  const v = valuesFor(detail.value, detailConditions.value.type)
  return Math.round((version === 2 ? v.current : version === 1 ? v.base : v.base * 0.98) * weight)
}
function sourceDelta(weight, version) { return sourceValue(weight,version) - sourceValue(weight,version - 1) }
function openCalculator(org) { store.activeOrg = org; emit('navigate','calculator') }
function sendRequest() {
  requestError.value = ''
  if (!requestMessage.value.trim() || !requestDue.value) { requestError.value = '회신 기한과 요청 메시지를 입력해 주세요.'; return }
  if (!Number.isFinite(seoulDeadline(requestDue.value)) || seoulDeadline(requestDue.value) <= Date.now()) { requestError.value = '현재 시각 이후의 회신 기한을 선택해 주세요.'; return }
  if (store.sendExplanation(store.activeOrg, requestMessage.value, requestDue.value)) { dockOpen.value = false; expandedOrg.value = store.activeOrg; emit('navigate','monthly'); feedback.value = '소명 요청 예시를 저장했습니다. 실제 메일은 발송하지 않습니다.' }
}
function sendMonthly() {
  if (!recipientIds.value.length || !bulkDue.value || !bulkMessage.value.trim()) { bulkError.value = '대상 조직·회신 기한·요청 메시지를 확인해 주세요.'; return }
  if (!Number.isFinite(seoulDeadline(bulkDue.value)) || seoulDeadline(bulkDue.value) <= Date.now()) { bulkError.value = '현재 시각 이후의 회신 기한을 선택해 주세요.'; return }
  store.organizations.filter(o => recipientIds.value.includes(o.id)).forEach(o => { o.requestDue = bulkDue.value; o.requestMessage = bulkMessage.value })
  emit('navigate','monthly'); feedback.value = '월간 제출 요청 예시를 저장했습니다. 실제 메일은 발송하지 않습니다.'
}
function exportCsv() {
  if (!applied.value) return
  const escape = v => `"${String(v).replaceAll('"','""')}"`
  const records = [['조직','항목','기준값(원)','비교값(원)','차액(원)','차이율','진행 단계'], ...candidates.value.map(i => { const v = valuesFor(i,applied.value.type); return [currentOrg.value.name,i.name,v.base,v.current,v.current-v.base,rate(v.base,v.current),i.status] })]
  const blob = new Blob(['\ufeff'+records.map(row => row.map(escape).join(',')).join('\r\n')],{type:'text/csv;charset=utf-8'})
  const url = URL.createObjectURL(blob), a = document.createElement('a'); a.href=url; a.download=`Gap_${store.activeOrg}_${year}-${month}.csv`; a.click(); URL.revokeObjectURL(url)
}
const pipeline = ['검토 필요','검토 완료','소명 진행중','재검토 필요','소명 승인 완료']
const pipelineTones = ['review','submitted','progress','recheck','approved']
</script>

<template>
  <div class="page-stack monthly-panel">
    <header class="page-heading"><p class="breadcrumb">경영기획팀 / 월간 시트 관리</p><h1>{{ page === 'calculator' ? '월간 시트 관리 계산기' : page === 'workspace' ? `선택 항목 검토/${currentOrg.name}` : page === 'cell' ? 'Gap 상세 내역' : page === 'monthly-request' ? '월간 시트 요청' : '월간 시트 관리' }}</h1><p v-if="['calculator','workspace','cell'].includes(page)">{{ currentOrg.name }} · {{ currentOrg.code }} · {{ year }}년 {{ month }}월</p><p v-else-if="page === 'monthly'">당월 조직별 제출 상태와 회신을 확인하고 Gap 계산으로 이동합니다.</p></header>
    <div v-if="feedback" class="notice" role="status">{{ feedback }}</div>

    <template v-if="page === 'monthly'">
      <div class="row-between"><h2>{{ year }}년 {{ month }}월 · 이번 달</h2><button class="btn primary" @click="emit('navigate','monthly-request')">월실적/추정요청 및 현황</button></div>
      <p class="muted">전체 {{ store.organizations.length }}개 조직 · 제출 완료 {{ store.organizations.filter(o => o.submissionStatus === '제출 완료').length }} · 기한 경과 1 · 회신 대기 1</p>
      <section class="card org-status"><h2>조직별 진행 현황</h2><p class="muted caption">조직 행을 선택하면 해당 행 아래에 회신과 이력이 펼쳐집니다.</p>
        <div class="table-scroll"><table><thead><tr><th>조직명</th><th>조직코드</th><th>발송일</th><th>회신 기한</th><th>월간 제출 상태</th><th>소명 진행 상태</th></tr></thead><tbody><template v-for="org in store.organizations" :key="org.id"><tr class="org-row" :class="{expanded:expandedOrg === org.id}"><td><button class="text-button" :aria-expanded="expandedOrg === org.id" @click="expandedOrg = expandedOrg === org.id ? null : org.id">{{ org.name }} <span aria-hidden="true">{{ expandedOrg === org.id ? '⌃' : '⌄' }}</span></button></td><td>{{ org.code }}</td><td>{{ month }}.02 09:00</td><td>{{ org.requestDue ? dateLabel(org.requestDue) : `${month}.12 18:00` }}</td><td><span class="badge" :class="org.submissionStatus === '제출 완료' ? 'submitted' : org.submissionStatus === '기한 경과' ? 'overdue' : 'waiting'">{{ org.submissionStatus }}</span></td><td><span class="badge" :class="org.explanationStatus === '소명 진행중' ? 'progress' : org.explanationStatus === '소명 회신' ? 'recheck' : ''">{{ org.explanationStatus }}</span></td></tr>
        <tr v-if="expandedOrg === org.id"><td colspan="6" class="reply-cell"><div class="reply-card"><div class="row-between"><strong>{{ org.author }} · {{ org.department }}</strong><small class="muted">{{ org.fileName ? `${month}.12 14:20 회신 · 디자인 예시` : '회신 대기' }}</small></div><p>{{ org.message || '아직 회신이 없습니다.' }}</p><div class="row-between"><button v-if="org.fileName" class="btn subtle" disabled title="예시 첨부파일입니다. 원본 다운로드는 서버 연결 후 가능합니다.">{{ org.fileName }} ↓</button><button v-if="org.fileName" class="btn primary" @click="openCalculator(org.id)">Gap 계산하러 가기 →</button></div><small v-if="org.fileName" class="muted">첨부 파일과 회신 내용은 디자인 예시입니다.</small></div></td></tr></template></tbody></table></div>
      </section>
    </template>

    <form v-else-if="page === 'monthly-request'" class="page-stack" @submit.prevent="sendMonthly">
      <div class="card"><h2>요청 대상 조직</h2><p class="muted caption">모든 조직이 기본 선택됩니다.</p><div class="table-scroll"><table><thead><tr><th><input type="checkbox" aria-label="모든 조직 선택" :checked="recipientIds.length === store.organizations.length" @change="recipientIds = $event.target.checked ? store.organizations.map(o => o.id) : []" /></th><th>조직명</th><th>조직코드</th><th>수신 담당자</th></tr></thead><tbody><tr v-for="org in store.organizations" :key="org.id"><td><input v-model="recipientIds" type="checkbox" :value="org.id" :aria-label="`${org.name} 요청 대상 선택`" /></td><td>{{ org.name }}</td><td>{{ org.code }}</td><td>{{ org.author }}</td></tr></tbody></table></div></div>
      <div class="card request-fields"><label class="field">회신 기한<input v-model="bulkDue" type="datetime-local" required /></label><label class="field">요청 메시지<textarea v-model="bulkMessage" rows="5" required /></label><p v-if="bulkError" class="error" role="alert">{{ bulkError }}</p><p class="muted caption">프로토타입에서는 예시 요청 상태만 저장합니다. 실제 메일은 발송되지 않습니다.</p><div class="actions"><button type="button" class="btn" @click="emit('navigate','monthly')">취소</button><button class="btn primary" type="submit" :disabled="!recipientIds.length">{{ recipientIds.length }}개 조직에 월간 시트 요청 발송</button></div></div>
    </form>

    <template v-else-if="page === 'calculator'">
      <section class="card calculator-settings"><div class="field"><label>비교 대상</label><div class="version-controls"><label><span class="sr-only">기준 대상</span><select v-model="draft.baseVersion" aria-label="기준 대상"><option value="v1.02">기준 대상 · 조직 제출본 v1.02</option></select></label><label><span class="sr-only">현재 비교 대상</span><select v-model="draft.compareVersion" aria-label="현재 비교 대상"><option value="">비교 대상 · 선택하세요</option><option value="v1.03">조직 제출본 v1.03 · 신규 업로드</option></select></label><select v-model="draft.type" aria-label="비교 필터"><option v-for="type in comparisonTypes" :key="type">{{ type }}</option></select></div></div>
      <div class="field"><label>Gap 기준</label><div class="gap-controls"><button class="condition-toggle" :class="{on:draft.rateEnabled}" :aria-pressed="draft.rateEnabled" @click="draft.rateEnabled = !draft.rateEnabled"><span>차이율</span><b>±5%</b><span class="toggle-switch" :class="{on:draft.rateEnabled}" aria-hidden="true" /></button><div class="amount-condition" :class="{on:draft.amountEnabled}"><button :aria-pressed="draft.amountEnabled" @click="draft.amountEnabled = !draft.amountEnabled">최소 금액 <span class="toggle-switch" :class="{on:draft.amountEnabled}" aria-hidden="true" /></button><select v-model="draft.minimum" aria-label="최소 금액"><option :value="10000000">1천만원</option><option :value="50000000">5천만원</option><option :value="100000000">1억원</option></select></div><div class="actions"><button class="btn" :disabled="!applied" @click="exportCsv">CSV 다운로드</button><button class="btn primary" @click="calculate">적용</button></div></div></div><p v-if="conditionError" class="error" role="alert">{{ conditionError }}</p><p class="caption muted">두 조건을 켜면 모두 충족하는 항목을 표시합니다. 조건을 끄면 전체 변경 항목을 볼 수 있습니다.</p></section>
      <div class="status-tabs" role="group" aria-label="진행 단계별 현황"><button v-for="(status,index) in statuses" :key="status" :class="[statusTones[index],{selected:stage === status}]" :aria-pressed="stage === status" @click="stage = status; selection = []">{{ status }} <b>{{ counts[status] || 0 }}</b></button></div>
      <div class="pipeline" aria-label="검토 상태 흐름"><template v-for="(label,index) in pipeline" :key="label"><span v-if="index" class="pipeline-line" aria-hidden="true" /><span class="pipeline-step"><b class="badge" :class="pipelineTones[index]">{{ index + 1 }}</b><span>{{ label }}</span></span></template></div>
      <div class="result-toolbar"><div class="org-context"><small class="muted">조직</small><strong>{{ currentOrg.name }}</strong></div><label class="unit-field"><small class="muted">단위</small><select v-model="unit" aria-label="금액 표시 단위"><option value="THOUSAND">천원</option><option value="HUNDRED_MILLION">억원</option></select></label><label class="search-field"><span class="sr-only">항목 검색</span><input v-model="search" type="search" placeholder="항목명 검색" aria-label="항목명 검색" /></label><label class="sort-field"><small class="muted">정렬</small><select v-model="sort" aria-label="결과 정렬"><option>차액 내림차순</option><option>차액 오름차순</option><option>차이율 내림차순</option><option>차이율 오름차순</option></select></label></div>
      <div v-if="!applied" class="empty-state calculator-empty"><h3>비교 대상을 선택하고 기준을 적용해 주세요.</h3><p>계산 결과가 대·중·소·상세 계층형 표로 표시됩니다.</p></div>
      <div v-else-if="!rows.length" class="empty-state"><h3>조건에 맞는 항목이 없습니다.</h3><p>진행 단계, 검색어 또는 금액 조건을 확인해 주세요.</p></div>
      <div v-else class="table-scroll hierarchy-table"><table><thead><tr><th><input type="checkbox" aria-label="표시 항목 모두 선택" :checked="rows.length > 0 && rows.every(i => selection.includes(i.id))" @change="selectVisible($event.target.checked)" /> 항목 · 대 / 중 / 소 / 상세</th><th class="number">{{ applied.type === '추정 vs 추정' ? '직전 추정' : '계획값' }}</th><th class="number">{{ applied.type === '실적 vs 계획' ? '실적' : '추정' }}</th><th class="number">차액</th><th class="number">차이율</th><th>진행 단계</th></tr></thead><tbody><tr class="major-row"><td>대 · 영업비용</td><td class="number">{{ money(total(rows).base) }}</td><td class="number">{{ money(total(rows).current) }}</td><td class="number">{{ signed(total(rows).current-total(rows).base) }}</td><td class="number">{{ rate(total(rows).base,total(rows).current) }}</td><td>—</td></tr><template v-for="group in groups" :key="group.name"><tr class="middle-row"><td>중 · {{ group.name }}</td><td class="number">{{ money(total(group.items).base) }}</td><td class="number">{{ money(total(group.items).current) }}</td><td class="number">{{ signed(total(group.items).current-total(group.items).base) }}</td><td class="number">{{ rate(total(group.items).base,total(group.items).current) }}</td><td>—</td></tr><template v-for="item in group.items" :key="item.id"><tr class="minor-row"><td>소 · {{ item.category }}</td><td class="number">{{ money(valuesFor(item,applied.type).base) }}</td><td class="number">{{ money(valuesFor(item,applied.type).current) }}</td><td class="number positive">{{ signed(valuesFor(item,applied.type).current-valuesFor(item,applied.type).base) }}</td><td class="number positive">{{ rate(valuesFor(item,applied.type).base,valuesFor(item,applied.type).current) }}</td><td>—</td></tr><tr class="detail-row" :class="{checked:selection.includes(item.id)}"><td><input type="checkbox" :aria-label="`${item.name} 선택`" :checked="selection.includes(item.id)" @change="toggle(item.id)" /><button class="text-button cell-link" @click="openDetail(item,'results')">{{ item.cell }} · {{ item.name }}</button></td><td class="number">{{ money(valuesFor(item,applied.type).base) }}</td><td class="number">{{ money(valuesFor(item,applied.type).current) }}</td><td class="number positive">{{ signed(valuesFor(item,applied.type).current-valuesFor(item,applied.type).base) }}</td><td class="number positive">{{ rate(valuesFor(item,applied.type).base,valuesFor(item,applied.type).current) }}</td><td><span class="badge" :class="statusTones[statuses.indexOf(item.status)]">{{ item.status }}</span></td></tr></template></template></tbody></table></div>
      <div v-if="applied" class="selection-bar"><strong>{{ selection.length }}개 항목 선택</strong><div class="actions"><button class="btn text" :disabled="!selection.length" @click="selection = []">선택 해제</button><button class="btn" :disabled="!selection.length || ['소명 진행중','소명 승인 완료'].includes(stage)" @click="excludeSelected">제외</button><button class="btn primary" :disabled="!selection.length || ['제외됨','소명 진행중','소명 승인 완료'].includes(stage)" @click="addSelected">선택 항목 검토하기 →</button></div></div>
      <p class="muted caption">숫자와 버전은 디자인 예시입니다. 합계는 현재 조회된 상세 항목 기준입니다. 다운로드는 Excel에서 열 수 있는 CSV 형식입니다.</p>
    </template>

    <template v-else-if="page === 'workspace'">
      <p class="muted">계산기에서 검토 대상으로 넘긴 항목입니다.</p><div v-if="!cart.length" class="empty-state"><h3>담긴 항목이 없습니다.</h3><p>계산 결과에서 항목을 선택해 검토 대상으로 넘겨 주세요.</p><button class="btn primary" @click="emit('navigate','calculator')">계산기로 돌아가기</button></div>
      <template v-else><section v-if="sameCartContext" class="card snapshot-card"><div><small>조직 · 대상월</small><p>{{ currentOrg.name }} · {{ year }}년 {{ month }}월</p></div><div><small>기준 대상</small><p>조직 제출본 {{ cart[0].conditions.baseVersion }}</p></div><div><small>비교 대상</small><p>조직 제출본 {{ cart[0].conditions.compareVersion }}</p></div><div><small>비교 필터</small><p>{{ cart[0].conditions.type }}</p></div><div><small>차이율 조건</small><p>{{ cart[0].conditions.rateEnabled ? '±5%' : '꺼짐' }}</p></div><div><small>최소 금액 조건</small><p>{{ cart[0].conditions.amountEnabled ? `${formatMoney(String(cart[0].conditions.minimum))}원` : '꺼짐' }}</p></div></section><p v-else class="notice">여러 계산 조건으로 담은 항목입니다. 항목별 비교 기준을 확인해 주세요.</p><h2>검토 대상 {{ cart.length }}개</h2><div class="table-scroll"><table><thead><tr><th>선택 항목</th><th class="number">기준값 (천원)</th><th class="number">실적·추정 (천원)</th><th class="number">차액 (천원)</th><th>진행 단계</th></tr></thead><tbody><tr v-for="item in cart" :key="item.key"><td><button class="text-button cell-link" @click="openDetail(item)">{{ item.name }}</button><small v-if="!sameCartContext" class="muted snapshot-hint">{{ item.conditions.type }} · {{ item.conditions.baseVersion }} → {{ item.conditions.compareVersion }} · {{ item.conditions.rateEnabled ? '±5%' : '차이율 꺼짐' }} · {{ item.conditions.amountEnabled ? `최소 ${formatMoney(String(item.conditions.minimum))}원` : '금액 조건 꺼짐' }}</small></td><td class="number">{{ formatMoney(String(valuesFor(item,item.conditions.type).base),'THOUSAND') }}</td><td class="number">{{ formatMoney(String(valuesFor(item,item.conditions.type).current),'THOUSAND') }}</td><td class="number">{{ signed(valuesFor(item,item.conditions.type).current-valuesFor(item,item.conditions.type).base,'THOUSAND') }}</td><td><span class="badge review">검토 대상</span></td></tr></tbody></table></div><div class="actions"><button class="btn" @click="emit('navigate','calculator')">계산기로 돌아가기</button><button class="btn primary" @click="openDetail(cart[0])">Gap 상세 내역 →</button></div></template>
    </template>

    <template v-else-if="page === 'cell'">
      <button class="btn back-button" @click="emit('navigate',detailSource === 'cart' ? 'workspace' : 'calculator')">← {{ detailSource === 'cart' ? '선택 항목 검토' : '계산 결과' }}</button>
      <p class="muted caption">셀 주소·금액·근거·소명은 디자인 예시입니다. 원본 파일과 자동 대응된 결과가 아닙니다.</p>
      <div v-if="!detail" class="empty-state">계산 결과 또는 담긴 항목에서 상세 항목을 선택해 주세요.</div>
      <div v-else class="cell-layout"><aside class="answer-list"><h2>답안지 변경 항목</h2><small class="muted">셀 주소 오름차순</small><button v-for="item in [...detailItems].sort((a,b)=>a.cell.localeCompare(b.cell,undefined,{numeric:true}))" :key="item.key || item.id" class="answer-card" :class="{active:(detail.key || detail.id) === (item.key || item.id)}" @click="activeCell = item.key || item.id"><strong>{{ item.cell }} · {{ item.name }}</strong><small>{{ item.group }} &gt; {{ item.name }}</small><span>{{ item.conditions?.type || detailConditions.type }}<br />기준 {{ formatMoney(String(valuesFor(item,item.conditions?.type || detailConditions.type).base)) }}원<br />비교 {{ formatMoney(String(valuesFor(item,item.conditions?.type || detailConditions.type).current)) }}원<br />차액 {{ signed(valuesFor(item,item.conditions?.type || detailConditions.type).current-valuesFor(item,item.conditions?.type || detailConditions.type).base,'KRW') }}원</span></button><button v-if="detailSource === 'cart' && cart.length" class="btn" @click="store.removeCartItem(store.activeOrg,detail.key)">이 항목 제외</button></aside>
      <section class="cell-detail"><div class="card selected-summary"><h2>{{ detail.cell }} · {{ detail.group }} &gt; {{ detail.name }}</h2><p>기준 {{ formatMoney(String(valuesFor(detail,detailConditions.type).base)) }}원 ｜ 비교값 {{ formatMoney(String(valuesFor(detail,detailConditions.type).current)) }}원</p><p>차액 {{ signed(valuesFor(detail,detailConditions.type).current-valuesFor(detail,detailConditions.type).base) }} {{ unit === 'THOUSAND' ? '천원' : '억원' }} ｜ 차이율 {{ rate(valuesFor(detail,detailConditions.type).base,valuesFor(detail,detailConditions.type).current) }}</p><small class="muted">{{ detailConditions.type }} · {{ detailConditions.rateEnabled ? '±5%' : '차이율 꺼짐' }} · {{ detailConditions.amountEnabled ? `최소 ${formatMoney(String(detailConditions.minimum))}원` : '최소 금액 꺼짐' }}</small></div>
      <h2>답안지 셀 / 원천 셀 · 버전 비교</h2><div class="table-scroll version-grid"><table><thead><tr><th>원천 위치</th><th>v1.01 · 확정<br /><small>11.05 제출본 승인</small></th><th class="current-version">v1.02 · 현재 기준<br /><small>11.12 제출본 승인</small></th><th class="new-version">신규 업로드 · 미확정<br /><small>제출본 승인 시 v1.03</small></th></tr></thead><tbody><tr class="source-sheet"><th colspan="4">{{ detail.source.split('!')[0] }}</th></tr><tr v-for="(weight,index) in [0.6,0.4]" :key="index"><th>{{ detail.source }}{{ index ? ' (연관 셀)' : '' }}<br />{{ detail.name }} 근거</th><td v-for="(factor,v) in [0.98,1,1.1]" :key="v" :class="{'current-cell':v === 1,'new-cell':v === 2}"><strong>값 {{ formatMoney(String(sourceValue(weight,v))) }}원</strong><p>변동 {{ v ? `${signed(sourceDelta(weight,v),'KRW')}원` : '기준' }}</p><p>원천 금액 {{ formatMoney(String(sourceValue(weight,v))) }}원</p><p>산출 근거 {{ detail.name }} × 반영률</p><p>업로드 시 소명 {{ v === 2 ? '집행 일정·단가 변경' : '당시 제출 원문' }}</p><p>거래내역 원천 정산 자료</p></td></tr></tbody></table></div>
      <h2>버전별 소명 이력 · 담당자 메모</h2><details v-for="version in [1,2]" :key="version" class="card history-card" open><summary :class="{current:version === 2}">v1.0{{ version }} · {{ version === 1 ? '확정 · 11.05 승인' : '현재 기준 · 11.12 승인' }}</summary><div class="timeline"><p>최초 제출</p><p>경영기획 소명 요청</p><p>조직 담당자 소명 회신</p><p>소명 승인 이력</p><p>제출본 승인 · v1.0{{ version }} 확정</p></div><strong>담당자 소명 메모 원문 · 디자인 예시</strong><p>{{ detail.reason || '계약 일정 변경에 따라 인식 시점이 달라졌습니다. 거래별 근거 자료를 확인해 주세요.' }}</p><strong>경영기획 요청 메모</strong><p>차이 원인과 영향 기간, 거래별 산출 근거를 확인해 주세요.</p><button class="btn subtle" disabled title="예시 파일은 서버에 연결되지 않았습니다.">계약_변경합의서.pdf ↓</button></details></section></div>
      <div v-if="requestCount" class="selection-bar"><p>이 조직의 검토 대상 {{ requestCount }}개를 소명 요청합니다.</p><button class="btn primary" @click="dockOpen = !dockOpen">일괄 소명 요청 보내기</button></div><p class="muted caption">소명 승인과 제출본 승인·전사 반영은 별도 단계입니다. 신규 업로드는 제출본 승인 전까지 미확정입니다.</p>
    </template>
    <aside v-if="dockOpen" class="message-dock" role="region" aria-labelledby="request-dock-title"><header class="row-between"><div><h2 id="request-dock-title">{{ currentOrg.name }} · 일괄 소명 요청</h2><small class="muted">검토 대상 {{ requestCount }}개 · 화면을 보면서 메시지를 작성할 수 있습니다.</small></div><button class="btn text" aria-label="요청 작성 닫기" @click="dockOpen = false">닫기</button></header><label class="field">회신 기한<input v-model="requestDue" type="datetime-local" required /></label><label class="field">요청 메시지<textarea v-model="requestMessage" rows="3" required /></label><p v-if="requestError" class="error" role="alert">{{ requestError }}</p><div class="row-between"><small class="muted">예시 상태만 변경하며 실제 메일은 발송하지 않습니다.</small><button class="btn primary" @click="sendRequest">{{ requestCount }}개 항목 소명 요청 보내기</button></div></aside>
  </div>
</template>
