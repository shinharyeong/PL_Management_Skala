<script setup>
import { computed, ref, watch } from 'vue'
import PButton from 'primevue/button'
import PColumn from 'primevue/column'
import PDataTable from 'primevue/datatable'
import PPanel from 'primevue/panel'
import PTag from 'primevue/tag'
import PTextarea from 'primevue/textarea'
import { usePrototypeStore } from './live-store.js'
import { formatMoney as money } from '../lib/decimal-display.js'
import { subtract, compare } from './exact-money.js'
import { saveFile } from './live-api.js'
import { groupVersionRows } from './monthly-view-model.js'
import './monthly-live.css'

const props = defineProps({ id: [String, Number] })
const store = usePrototypeStore(), note = ref('')
const detail = computed(() => store.reviewDetail)
const latest = computed(() => detail.value?.explanations?.at(-1))
const columns = computed(() => store.versions?.columns || [])
const versionRows = computed(() => store.versions?.rows || [])

const sourceRows = computed(() => groupVersionRows(versionRows.value))
const history = computed(() => [...(detail.value?.explanations || [])].sort((a, b) => a.round - b.round))

/** 제출본 버전의 확정 상태를 표시한다.
 * Args: value 서버 상태. Returns: 한국어 상태명. Raises: 없음. Side Effects: 없음.
 */
function state(value) { return { CONFIRMED: '확정', CURRENT: '현재 기준', NEW: '신규 업로드 · 미확정' }[value] || value }

/** 소명 검토 결과를 표시한다.
 * Args: value 서버 검토 결과. Returns: 한국어 결과명. Raises: 없음. Side Effects: 없음.
 */
function verdict(value) { return { PENDING: '검토 대기', ACCEPTED: '소명 수락', REJECTED: '소명 보완 요청' }[value] || value }

/** 서버 시각을 서울 기준으로 표시하고 해석할 수 없는 원문은 보존한다.
 * Args: value 날짜·시간 문자열. Returns: 표시 문자열. Raises: 없음. Side Effects: 없음.
 */
function date(value) {
  if (!value) return '—'
  const timestamp = new Date(value)
  return Number.isNaN(timestamp.getTime()) ? value : new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(timestamp)
}

/** 정밀 차액의 부호와 원 단위 표시를 만든다.
 * Args: value 금액 문자열. Returns: 부호 포함 금액. Raises: 없음. Side Effects: 없음.
 */
function signed(value) { return `${compare(value, '0') > 0 ? '+' : ''}${money(value)}` }

const thresholdText = computed(() => {
  const t = detail.value?.summary?.threshold
  if (!t) return '설정 정보 없음'
  return [t.percentEnabled ? `차이율 ±${t.percent}% 이상` : '차이율 꺼짐', t.amountEnabled ? `최소 ${money(t.amount)}원 이상` : '금액 조건 꺼짐'].join(' · ')
})

/** 선택한 reviewItem의 상세·원천·버전을 조회한다.
 * Args: id reviewItem ID. Returns: 없음. Raises: 없음(화면 오류로 표시).
 * Side Effects: 이전 상세와 메모를 비우고 서버 응답으로 store를 갱신한다.
 */
function loadDetail(id) {
  note.value = ''; store.reviewDetail = null; store.evidence = null; store.versions = null
  if (id) store.openDetail(id).catch(error => store.error = error.message)
}

/** 현재 검토 항목에 속하는 소명 첨부파일을 내려받는다.
 * Args: file 첨부파일 메타데이터. Returns: Promise<boolean>. Raises: 없음(화면 오류로 표시).
 * Side Effects: 서버 파일을 조회하고 사용자가 요청한 브라우저 다운로드를 시작한다.
 */
function downloadAttachment(file) {
  return store.run(async () => { saveFile(await store.api(`/api/v1/review-items/${props.id}/attachments/${file.id}/file`, { blob: true }), file.filename || file.name) })
}

/** 현재 소명에 대한 수락 또는 보완 요청을 서버에 기록한다.
 * Args: decision ACCEPT 또는 REQUEST_AGAIN. Returns: Promise<boolean>. Raises: 없음(화면 오류로 표시).
 * Side Effects: 소명 검토 상태와 상세 조회 결과를 갱신한다.
 */
function decideExplanation(decision) { return store.run(() => store.decideExplanation(props.id, decision, note.value)) }
watch(() => props.id, loadDetail, { immediate: true })
</script>

<template>
  <section v-if="detail" class="cell-detail live-cell-detail">
    <div class="card selected-summary"><h2>{{ detail.path }}</h2><p>{{ detail.month }}월 · {{ { PLAN: '계획', ACTUAL: '실적', ESTIMATE: '추정' }[detail.scenario] || detail.scenario }}</p><div v-if="detail.summary" class="live-summary-values"><div><small>계획값</small><strong>{{ money(detail.summary.plan) }}원</strong></div><div><small>실적·추정</small><strong>{{ money(detail.summary.actual) }}원</strong></div><div><small>차액</small><strong>{{ signed(detail.summary.diff) }}원</strong></div><div><small>차이율</small><strong>{{ detail.summary.rate == null ? '산정 불가' : detail.summary.rate + '%' }}</strong></div></div><small class="muted">제출 시 적용된 Gap 기준 · {{ thresholdText }}</small></div>
    <h2>답안지 셀 / 원천 셀 · 버전 비교</h2>
    <PDataTable :value="sourceRows" dataKey="cell" rowGroupMode="subheader" groupRowsBy="sheet" class="live-version-grid" scrollable :tableStyle="{ minWidth: Math.max(760, (columns.length + 1) * 230) + 'px' }" :pt="{ table: { 'aria-label': '셀 단위 버전 비교' } }">
      <template #groupheader="{ data }"><strong>{{ data.sheet }}</strong></template>
      <template #empty>비교 가능한 원천 셀이 없습니다.</template>
      <PColumn field="sheet" header="원천 시트" />
      <PColumn header="원천 위치" style="min-width: 220px"><template #body="{ data }"><strong>{{ data.cell }}</strong><small class="version-source-name">{{ data.name }}</small></template></PColumn>
      <PColumn v-for="(column, index) in columns" :key="column.submissionId" :headerClass="column.state === 'CURRENT' ? 'current-version' : column.state === 'NEW' ? 'new-version' : ''" :bodyClass="column.state === 'CURRENT' ? 'current-cell' : column.state === 'NEW' ? 'new-cell' : ''" style="min-width: 230px">
        <template #header><div class="version-heading"><strong>{{ column.label }}</strong><PTag :value="state(column.state)" :severity="column.state === 'NEW' ? 'warn' : column.state === 'CURRENT' ? 'info' : 'success'" /><small v-if="column.approvedAt">{{ date(column.approvedAt) }} 제출본 승인</small><small v-else-if="column.nextLabelOnApproval">제출본 승인 시 {{ column.nextLabelOnApproval }}</small></div></template>
        <template #body="{ data }"><strong class="live-version-value">{{ money(data.values[index]) }}<small v-if="data.values[index] != null">원</small></strong><p v-if="index && data.values[index] != null && data.values[index - 1] != null" class="live-version-change">직전 버전 대비<br /><b>{{ signed(subtract(data.values[index], data.values[index - 1])) }}원</b></p><p v-else class="muted">{{ data.values[index] == null ? '해당 버전에 셀 없음' : index ? '직전 버전 값 없음' : '비교 시작 버전' }}</p></template>
      </PColumn>
    </PDataTable>
    <section v-if="versionRows.length" class="card live-source-notes"><h3>최근 비교 자료 · 산출 근거 및 업로드 소명</h3><p class="muted caption">서버가 제공하는 최근 자료의 근거입니다.</p><article v-for="row in versionRows" :key="row.cell"><strong>{{ row.cell }} · {{ row.name }}</strong><dl><div><dt>산출 근거</dt><dd>{{ row.basis || '자료 없음' }}</dd></div><div><dt>업로드 시 소명</dt><dd>{{ row.uploadExplanation || '작성된 소명 없음' }}</dd></div></dl></article></section>
    <PPanel header="원천 금액·거래 내역" toggleable collapsed class="live-evidence">
      <PDataTable :value="store.evidence?.items || []" scrollable tableStyle="min-width: 800px" :pt="{ table: { 'aria-label': '원천 금액과 거래 내역' } }">
        <template #empty>조회 가능한 원천 내역이 없습니다.</template>
        <PColumn header="원천 위치"><template #body="{ data }">{{ data.sourceSheet }}!{{ data.sourceCell }}</template></PColumn>
        <PColumn header="계정"><template #body="{ data }">{{ data.account ? `${data.account.name} · ${data.account.code}` : '계정 정보 없음' }}</template></PColumn>
        <PColumn header="원천 금액 (원)" bodyClass="number"><template #body="{ data }">{{ money(data.amount) }}</template></PColumn>
        <PColumn header="거래 내역·산출 근거"><template #body="{ data }">{{ data.description || '산출 근거 없음' }}</template></PColumn>
      </PDataTable>
    </PPanel>
    <h2>버전별 소명 이력 · 담당자 메모</h2>
    <p v-if="!history.length" class="muted">등록된 소명 이력이 없습니다.</p>
    <PPanel v-for="item in history" :key="item.id" toggleable class="live-history-panel" :class="{ 'current-history': item.id === latest?.id }" :header="`${item.approvedLabel ? item.approvedLabel + ' · ' : ''}${item.round}회차 · ${verdict(item.verdict)}`">
      <div class="timeline"><p v-if="item.requestedAt">{{ date(item.requestedAt) }} · 소명 요청 · {{ item.requestedBy?.name }}</p><p v-if="item.answeredAt">{{ date(item.answeredAt) }} · 소명 회신 · {{ item.answeredBy?.name }}</p><p v-if="item.verdict !== 'PENDING'">{{ verdict(item.verdict) }}<template v-if="item.approvedLabel"> · {{ item.approvedLabel }}</template></p></div><div class="history-memo"><strong>경영기획 요청 메모</strong><p>{{ item.requestNote || '—' }}</p><strong>담당자 소명 메모 원문</strong><p>{{ item.answer || '회신 대기' }}</p><template v-if="item.reviewNote"><strong>경영기획 검토 메모</strong><p>{{ item.reviewNote }}</p></template></div><div class="actions"><PButton v-for="file in item.attachments" :key="file.id" severity="secondary" variant="outlined" :label="(file.filename || file.name) + ' ↓'" :disabled="store.busy" @click="downloadAttachment(file)" /></div>
    </PPanel>
    <form v-if="latest?.answer && latest.verdict === 'PENDING'" class="card request-fields" @submit.prevent="decideExplanation('ACCEPT')"><label class="field">검토 메모<PTextarea v-model="note" rows="3" autoResize /></label><div class="actions"><PButton type="button" severity="secondary" variant="outlined" :disabled="store.busy || !note.trim()" label="소명 보완 요청" @click="decideExplanation('REQUEST_AGAIN')" /><PButton type="submit" :disabled="store.busy" label="소명 수락" /></div><p class="muted">제출본 승인과 별도로 처리됩니다.</p></form>
  </section>
</template>
