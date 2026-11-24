<script setup>
import { computed, ref, watch } from 'vue'
import { usePrototypeStore } from './live-store.js'
import { formatMoney as money } from '../lib/decimal-display.js'
import { subtract, compare } from './exact-money.js'
import { saveFile } from './live-api.js'
import './monthly-live.css'

const props = defineProps({ id: String })
const store = usePrototypeStore(), note = ref('')
const detail = computed(() => store.reviewDetail)
const latest = computed(() => detail.value?.explanations?.at(-1))
const columns = computed(() => store.versions?.columns || [])
const versionRows = computed(() => store.versions?.rows || [])
const sourceGroups = computed(() => {
  const groups = new Map()
  for (const row of versionRows.value) {
    const sheet = row.cell?.includes('!') ? row.cell.split('!')[0] : '원천 셀'
    if (!groups.has(sheet)) groups.set(sheet, [])
    groups.get(sheet).push(row)
  }
  return [...groups].map(([sheet, rows]) => ({ sheet, rows }))
})
const history = computed(() => [...(detail.value?.explanations || [])].sort((a,b) => a.round - b.round))
const state = value => ({ CONFIRMED:'확정', CURRENT:'현재 기준', NEW:'신규 업로드 · 미확정' }[value] || value)
const verdict = value => ({ PENDING:'검토 대기', ACCEPTED:'소명 수락', REJECTED:'소명 보완 요청' }[value] || value)
const date = value => {
  if (!value) return '—'
  const timestamp = new Date(value)
  return Number.isNaN(timestamp.getTime()) ? value : new Intl.DateTimeFormat('ko-KR',{timeZone:'Asia/Seoul',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}).format(timestamp)
}
const signed = value => `${compare(value,'0') > 0 ? '+' : ''}${money(value)}`
const thresholdText = computed(() => {
  const t = detail.value?.summary?.threshold
  if (!t) return '설정 정보 없음'
  return [t.percentEnabled ? `차이율 ±${t.percent}% 이상` : '차이율 꺼짐', t.amountEnabled ? `최소 ${money(t.amount)}원 이상` : '금액 조건 꺼짐'].join(' · ')
})
watch(() => props.id, id => {
  note.value = ''; store.reviewDetail = null; store.evidence = null; store.versions = null
  if (id) store.openDetail(id).catch(error => store.error = error.message)
}, { immediate: true })
</script>

<template>
  <section v-if="detail" class="cell-detail live-cell-detail">
    <div class="card selected-summary"><h2>{{ detail.path }}</h2><p>{{ detail.month }}월 · {{ {PLAN:'계획',ACTUAL:'실적',ESTIMATE:'추정'}[detail.scenario] || detail.scenario }}</p><div v-if="detail.summary" class="live-summary-values"><div><small>계획값</small><strong>{{ money(detail.summary.plan) }}원</strong></div><div><small>실적·추정</small><strong>{{ money(detail.summary.actual) }}원</strong></div><div><small>차액</small><strong>{{ signed(detail.summary.diff) }}원</strong></div><div><small>차이율</small><strong>{{ detail.summary.rate == null ? '산정 불가' : detail.summary.rate + '%' }}</strong></div></div><small class="muted">제출 시 적용된 Gap 기준 · {{ thresholdText }}</small></div>
    <h2>답안지 셀 / 원천 셀 · 버전 비교</h2>
    <div class="table-scroll version-grid live-version-grid" tabindex="0" role="region" aria-label="셀 단위 버전 비교"><table :style="{ minWidth: Math.max(760, (columns.length + 1) * 230) + 'px' }"><colgroup><col style="width:220px" /><col v-for="column in columns" :key="column.submissionId" style="width:230px" /></colgroup><thead><tr><th>원천 위치</th><th v-for="column in columns" :key="column.submissionId" :class="{'current-version':column.state === 'CURRENT','new-version':column.state === 'NEW'}"><strong>{{ column.label }}</strong><span>{{ state(column.state) }}</span><small v-if="column.approvedAt">{{ date(column.approvedAt) }} 제출본 승인</small><small v-else-if="column.nextLabelOnApproval">제출본 승인 시 {{ column.nextLabelOnApproval }}</small></th></tr></thead><tbody><template v-for="group in sourceGroups" :key="group.sheet"><tr class="source-sheet"><th :colspan="columns.length + 1">{{ group.sheet }}</th></tr><tr v-for="row in group.rows" :key="row.cell"><th scope="row"><strong>{{ row.cell }}</strong><small>{{ row.name }}</small></th><td v-for="(column,index) in columns" :key="column.submissionId" :class="{'current-cell':column.state === 'CURRENT','new-cell':column.state === 'NEW'}"><strong class="live-version-value">{{ money(row.values[index]) }}<small v-if="row.values[index] != null">원</small></strong><p v-if="index && row.values[index] != null && row.values[index-1] != null" class="live-version-change">직전 버전 대비<br /><b>{{ signed(subtract(row.values[index],row.values[index-1])) }}원</b></p><p v-else class="muted">{{ row.values[index] == null ? '해당 버전에 셀 없음' : index ? '직전 버전 값 없음' : '비교 시작 버전' }}</p></td></tr></template></tbody></table></div>
    <p v-if="!versionRows.length" class="muted">비교 가능한 원천 셀이 없습니다.</p>
    <section v-if="versionRows.length" class="card live-source-notes"><h3>최근 비교 자료 · 산출 근거 및 업로드 소명</h3><p class="muted caption">서버가 제공하는 최근 자료의 근거입니다.</p><article v-for="row in versionRows" :key="row.cell"><strong>{{ row.cell }} · {{ row.name }}</strong><dl><div><dt>산출 근거</dt><dd>{{ row.basis || '자료 없음' }}</dd></div><div><dt>업로드 시 소명</dt><dd>{{ row.uploadExplanation || '작성된 소명 없음' }}</dd></div></dl></article></section>
    <details class="card live-evidence"><summary>원천 금액·거래 내역</summary><div class="table-scroll"><table><thead><tr><th>원천 위치</th><th>계정</th><th class="number">원천 금액 (원)</th><th>거래 내역·산출 근거</th></tr></thead><tbody><tr v-for="(item,index) in store.evidence?.items" :key="index"><th>{{ item.sourceSheet }}!{{ item.sourceCell }}</th><td>{{ item.account ? `${item.account.name} · ${item.account.code}` : '계정 정보 없음' }}</td><td class="number">{{ money(item.amount) }}</td><td>{{ item.description || '산출 근거 없음' }}</td></tr><tr v-if="!store.evidence?.items?.length"><td colspan="4" class="muted">조회 가능한 원천 내역이 없습니다.</td></tr></tbody></table></div></details>
    <h2>버전별 소명 이력 · 담당자 메모</h2>
    <p v-if="!history.length" class="muted">등록된 소명 이력이 없습니다.</p>
    <details v-for="item in history" :key="item.id" class="card history-card" open><summary :class="{current:item.id === latest?.id}">{{ item.approvedLabel ? item.approvedLabel + ' · ' : '' }}{{ item.round }}회차 · {{ verdict(item.verdict) }}</summary><div class="timeline"><p v-if="item.requestedAt">{{ date(item.requestedAt) }} · 소명 요청 · {{ item.requestedBy?.name }}</p><p v-if="item.answeredAt">{{ date(item.answeredAt) }} · 소명 회신 · {{ item.answeredBy?.name }}</p><p v-if="item.verdict !== 'PENDING'">{{ verdict(item.verdict) }}<template v-if="item.approvedLabel"> · {{ item.approvedLabel }}</template></p></div><strong>경영기획 요청 메모</strong><p>{{ item.requestNote || '—' }}</p><strong>담당자 소명 메모 원문</strong><p>{{ item.answer || '회신 대기' }}</p><template v-if="item.reviewNote"><strong>경영기획 검토 메모</strong><p>{{ item.reviewNote }}</p></template><button v-for="file in item.attachments" :key="file.id" class="btn" @click="store.run(async()=>{saveFile(await store.api(`/api/v1/review-items/${id}/attachments/${file.id}/file`,{blob:true}),file.filename || file.name)})">{{ file.filename || file.name }} ↓</button></details>
    <form v-if="latest?.answer && latest.verdict === 'PENDING'" class="card request-fields" @submit.prevent="store.run(()=>store.decideExplanation(id,'ACCEPT',note))"><label class="field">검토 메모<textarea v-model="note" /></label><div class="actions"><button type="button" class="btn" :disabled="store.busy || !note.trim()" @click="store.run(()=>store.decideExplanation(id,'REQUEST_AGAIN',note))">소명 보완 요청</button><button class="btn primary" :disabled="store.busy">소명 수락</button></div><p class="muted">제출본 승인과 별도로 처리됩니다.</p></form>
  </section>
</template>
