<script setup>
import copy from './copy-text.js'
import VersionExplanationHistory from './VersionExplanationHistory.vue'
import { computed, watch } from 'vue'
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
const store = usePrototypeStore()
const detail = computed(() => store.reviewDetail)
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


/** 선택한 reviewItem의 상세·원천·버전을 조회한다.
 * Args: id reviewItem ID. Returns: 없음. Raises: 없음(화면 오류로 표시).
 * Side Effects: 이전 상세와 메모를 비우고 서버 응답으로 store를 갱신한다.
 */
function loadDetail(id) {
  store.reviewDetail = null; store.evidence = null; store.versions = null
  if (id) store.openDetail(id).catch(error => store.error = error.message)
}

/** 현재 검토 항목에 속하는 소명 첨부파일을 내려받는다.
 * Args: file 첨부파일 메타데이터. Returns: Promise<boolean>. Raises: 없음(화면 오류로 표시).
 * Side Effects: 서버 파일을 조회하고 사용자가 요청한 브라우저 다운로드를 시작한다.
 */
function downloadAttachment(file) {
  return store.run(async () => { saveFile(await store.api(`/api/v1/review-items/${props.id}/attachments/${file.id}/file`, { blob: true }), file.filename || file.name) })
}

watch(() => props.id, loadDetail, { immediate: true })
</script>

<template>
  <section v-if="detail" class="cell-detail live-cell-detail">
    <h2><span data-copy-key="reviewText.text008">{{ copy.reviewText.text008 }}</span></h2>
    <div class="table-scroll version-grid live-version-grid" tabindex="0" role="region" aria-label="셀 단위 버전 비교"><table :style="{ minWidth: Math.max(760, (columns.length + 1) * 230) + 'px' }"><colgroup><col style="width:220px" /><col v-for="column in columns" :key="column.submissionId" style="width:230px" /></colgroup><thead><tr><th><span data-copy-key="reviewText.text009">{{ copy.reviewText.text009 }}</span></th><th v-for="column in columns" :key="column.submissionId" :class="{'current-version':column.state === 'CURRENT','new-version':column.state === 'NEW'}"><strong>{{ column.label }}</strong><span>{{ state(column.state) }}</span><small v-if="column.approvedAt">{{ date(column.approvedAt) }} <span data-copy-key="reviewText.text010">{{ copy.reviewText.text010 }}</span></small><small v-else-if="column.nextLabelOnApproval"><span data-copy-key="reviewText.text011">{{ copy.reviewText.text011 }}</span> {{ column.nextLabelOnApproval }}</small></th></tr></thead><tbody><template v-for="group in sourceGroups" :key="group.sheet"><tr class="source-sheet"><th :colspan="columns.length + 1">{{ group.sheet }}</th></tr><tr v-for="row in group.rows" :key="row.cell"><th scope="row"><strong>{{ row.cell }}</strong><small>{{ row.name }}</small></th><td v-for="(column,index) in columns" :key="column.submissionId" :class="{'current-cell':column.state === 'CURRENT','new-cell':column.state === 'NEW'}"><strong class="live-version-value">{{ money(row.values[index]) }}<small v-if="row.values[index] != null"><span data-copy-key="reviewText.text003">{{ copy.reviewText.text003 }}</span></small></strong><p v-if="index && row.values[index] != null && row.values[index-1] != null" class="live-version-change"><span data-copy-key="reviewText.text012">{{ copy.reviewText.text012 }}</span><br /><b>{{ signed(subtract(row.values[index],row.values[index-1])) }}<span data-copy-key="reviewText.text003">{{ copy.reviewText.text003 }}</span></b></p><p v-else class="muted">{{ row.values[index] == null ? '해당 버전에 셀 없음' : index ? '직전 버전 값 없음' : '비교 시작 버전' }}</p></td></tr></template></tbody></table></div>
    <p v-if="!versionRows.length" class="muted"><span data-copy-key="reviewText.text013">{{ copy.reviewText.text013 }}</span></p>
    <section v-if="versionRows.length" class="card live-source-notes"><h3><span data-copy-key="reviewText.text014">{{ copy.reviewText.text014 }}</span></h3><p class="muted caption"><span data-copy-key="reviewText.text015">{{ copy.reviewText.text015 }}</span></p><article v-for="row in versionRows" :key="row.cell"><strong>{{ row.cell }} · {{ row.name }}</strong><dl><div><dt><span data-copy-key="reviewText.text016">{{ copy.reviewText.text016 }}</span></dt><dd>{{ row.basis || '자료 없음' }}</dd></div><div><dt><span data-copy-key="reviewText.text017">{{ copy.reviewText.text017 }}</span></dt><dd>{{ row.uploadExplanation || '작성된 소명 없음' }}</dd></div></dl></article></section>
    <VersionExplanationHistory :detail="detail" :columns="columns" :current-submission="store.currentSubmission" />
  </section>
</template>
