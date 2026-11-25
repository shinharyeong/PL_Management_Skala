<script setup>
import copy from './copy-text.js'
import VersionExplanationHistory from './VersionExplanationHistory.vue'
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

const sourceGroups = computed(()=>{const groups=new Map();for(const row of versionRows.value){const sheet=row.cell?.includes('!')?row.cell.split('!')[0]:'원천 셀';if(!groups.has(sheet))groups.set(sheet,[]);groups.get(sheet).push(row)}return [...groups].map(([sheet,rows])=>({sheet,rows}))})
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
    <div class="card selected-summary"><h2>{{ detail.path }}</h2><p>{{ detail.month }}<span data-copy-key="reviewText.text001">{{ copy.reviewText.text001 }}</span> {{ {PLAN:'계획',ACTUAL:'실적',ESTIMATE:'추정'}[detail.scenario] || detail.scenario }}</p><div v-if="detail.summary" class="live-summary-values"><div><small><span data-copy-key="reviewText.text002">{{ copy.reviewText.text002 }}</span></small><strong>{{ money(detail.summary.plan) }}<span data-copy-key="reviewText.text003">{{ copy.reviewText.text003 }}</span></strong></div><div><small><span data-copy-key="reviewText.text004">{{ copy.reviewText.text004 }}</span></small><strong>{{ money(detail.summary.actual) }}<span data-copy-key="reviewText.text003">{{ copy.reviewText.text003 }}</span></strong></div><div><small><span data-copy-key="reviewText.text005">{{ copy.reviewText.text005 }}</span></small><strong>{{ signed(detail.summary.diff) }}<span data-copy-key="reviewText.text003">{{ copy.reviewText.text003 }}</span></strong></div><div><small><span data-copy-key="reviewText.text006">{{ copy.reviewText.text006 }}</span></small><strong>{{ detail.summary.rate == null ? '산정 불가' : detail.summary.rate + '%' }}</strong></div></div><small class="muted"><span data-copy-key="reviewText.text007">{{ copy.reviewText.text007 }}</span> {{ thresholdText }}</small></div>
    <h2><span data-copy-key="reviewText.text008">{{ copy.reviewText.text008 }}</span></h2>
    <div class="table-scroll version-grid live-version-grid" tabindex="0" role="region" aria-label="셀 단위 버전 비교"><table :style="{ minWidth: Math.max(760, (columns.length + 1) * 230) + 'px' }"><colgroup><col style="width:220px" /><col v-for="column in columns" :key="column.submissionId" style="width:230px" /></colgroup><thead><tr><th><span data-copy-key="reviewText.text009">{{ copy.reviewText.text009 }}</span></th><th v-for="column in columns" :key="column.submissionId" :class="{'current-version':column.state === 'CURRENT','new-version':column.state === 'NEW'}"><strong>{{ column.label }}</strong><span>{{ state(column.state) }}</span><small v-if="column.approvedAt">{{ date(column.approvedAt) }} <span data-copy-key="reviewText.text010">{{ copy.reviewText.text010 }}</span></small><small v-else-if="column.nextLabelOnApproval"><span data-copy-key="reviewText.text011">{{ copy.reviewText.text011 }}</span> {{ column.nextLabelOnApproval }}</small></th></tr></thead><tbody><template v-for="group in sourceGroups" :key="group.sheet"><tr class="source-sheet"><th :colspan="columns.length + 1">{{ group.sheet }}</th></tr><tr v-for="row in group.rows" :key="row.cell"><th scope="row"><strong>{{ row.cell }}</strong><small>{{ row.name }}</small></th><td v-for="(column,index) in columns" :key="column.submissionId" :class="{'current-cell':column.state === 'CURRENT','new-cell':column.state === 'NEW'}"><strong class="live-version-value">{{ money(row.values[index]) }}<small v-if="row.values[index] != null"><span data-copy-key="reviewText.text003">{{ copy.reviewText.text003 }}</span></small></strong><p v-if="index && row.values[index] != null && row.values[index-1] != null" class="live-version-change"><span data-copy-key="reviewText.text012">{{ copy.reviewText.text012 }}</span><br /><b>{{ signed(subtract(row.values[index],row.values[index-1])) }}<span data-copy-key="reviewText.text003">{{ copy.reviewText.text003 }}</span></b></p><p v-else class="muted">{{ row.values[index] == null ? '해당 버전에 셀 없음' : index ? '직전 버전 값 없음' : '비교 시작 버전' }}</p></td></tr></template></tbody></table></div>
    <p v-if="!versionRows.length" class="muted"><span data-copy-key="reviewText.text013">{{ copy.reviewText.text013 }}</span></p>
    <section v-if="versionRows.length" class="card live-source-notes"><h3><span data-copy-key="reviewText.text014">{{ copy.reviewText.text014 }}</span></h3><p class="muted caption"><span data-copy-key="reviewText.text015">{{ copy.reviewText.text015 }}</span></p><article v-for="row in versionRows" :key="row.cell"><strong>{{ row.cell }} · {{ row.name }}</strong><dl><div><dt><span data-copy-key="reviewText.text016">{{ copy.reviewText.text016 }}</span></dt><dd>{{ row.basis || '자료 없음' }}</dd></div><div><dt><span data-copy-key="reviewText.text017">{{ copy.reviewText.text017 }}</span></dt><dd>{{ row.uploadExplanation || '작성된 소명 없음' }}</dd></div></dl></article></section>
    <details class="card live-evidence"><summary><span data-copy-key="reviewText.text018">{{ copy.reviewText.text018 }}</span></summary><div class="table-scroll"><table><thead><tr><th><span data-copy-key="reviewText.text009">{{ copy.reviewText.text009 }}</span></th><th><span data-copy-key="reviewText.text019">{{ copy.reviewText.text019 }}</span></th><th class="number"><span data-copy-key="reviewText.text020">{{ copy.reviewText.text020 }}</span></th><th><span data-copy-key="reviewText.text021">{{ copy.reviewText.text021 }}</span></th></tr></thead><tbody><tr v-for="(item,index) in store.evidence?.items" :key="index"><th>{{ item.sourceSheet }}!{{ item.sourceCell }}</th><td>{{ item.account ? `${item.account.name} · ${item.account.code}` : '계정 정보 없음' }}</td><td class="number">{{ money(item.amount) }}</td><td>{{ item.description || '산출 근거 없음' }}</td></tr><tr v-if="!store.evidence?.items?.length"><td colspan="4" class="muted"><span data-copy-key="reviewText.text022">{{ copy.reviewText.text022 }}</span></td></tr></tbody></table></div></details>
    <VersionExplanationHistory :detail="detail" :columns="columns" :current-submission="store.currentSubmission" />
    <form v-if="latest?.answer && latest.verdict === 'PENDING'" class="card request-fields" @submit.prevent="store.run(()=>store.decideExplanation(id,'ACCEPT',note))"><label class="field"><span data-copy-key="reviewText.text031">{{ copy.reviewText.text031 }}</span><textarea v-model="note" /></label><div class="actions"><button type="button" class="btn" :disabled="store.busy || !note.trim()" @click="store.run(()=>store.decideExplanation(id,'REQUEST_AGAIN',note))"><span data-copy-key="reviewText.text032">{{ copy.reviewText.text032 }}</span></button><button class="btn primary" :disabled="store.busy"><span data-copy-key="reviewText.text033">{{ copy.reviewText.text033 }}</span></button></div><p class="muted"><span data-copy-key="reviewText.text034">{{ copy.reviewText.text034 }}</span></p></form>
  </section>
</template>
