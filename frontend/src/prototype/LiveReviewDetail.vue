<script setup>
import copy from './copy-text.js'
import {computed,ref,watch,onBeforeUnmount} from 'vue'
import {usePrototypeStore} from './live-store.js'
import {formatMoney as money} from '../lib/decimal-display.js'
import {compare} from './exact-money.js'
import {cellChange,loadCellVersionEvidence} from './cell-version-data.js'
import './monthly-live.css'
const props=defineProps({id:[String,Number]})
const store=usePrototypeStore()
const detail=computed(()=>store.reviewDetail)
const columns=computed(()=>store.versions?.columns || [])
const versionRows=computed(()=>store.versions?.rows || [])
const sourceGroups=computed(()=>{const groups=new Map();for(const row of versionRows.value){const sheet=row.cell?.includes('!')?row.cell.split('!')[0]:'원천 셀';if(!groups.has(sheet))groups.set(sheet,[]);groups.get(sheet).push(row)}return [...groups].map(([sheet,rows])=>({sheet,rows}))})
const signed=value=>`${compare(value,'0')>0?'+':''}${money(value)}`
const change=row=>cellChange(row,columns.value.length)
const state=value=>({CONFIRMED:'확정',CURRENT:'현재 기준',NEW:'신규 업로드 · 미확정'}[value] || value)
function date(value){if(!value)return '—';const d=new Date(value);return Number.isNaN(d.getTime())?value:new Intl.DateTimeFormat('ko-KR',{timeZone:'Asia/Seoul',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}).format(d)}
const evidenceByVersion=ref({})
// 조직이 회신한 최신 소명: 경영기획팀이 여기서 수락하거나 보완을 요청한다(#116에서 빠진 결정 폼 복원)
const latest=computed(()=>detail.value?.explanations?.at(-1))
const note=ref(''), decided=ref('')
function decide(verdict){const id=props.id;return store.run(async()=>{await store.decideExplanation(id,verdict,note.value.trim());if(mounted&&props.id===id){decided.value=verdict==='ACCEPT'?'소명을 수락했습니다.':'소명 보완을 요청했습니다.';note.value=''}})}
let generation=0,mounted=true
onBeforeUnmount(()=>{mounted=false;generation++;if(store.activeReviewId===props.id)store.activeReviewId=null})
watch(()=>props.id,id=>{generation++;note.value='';decided.value='';evidenceByVersion.value={};store.reviewDetail=null;store.evidence=null;store.versions=null;if(id)store.openDetail(id).catch(e=>{if(mounted&&props.id===id)store.error=e.message})},{immediate:true})
watch(()=>[detail.value,store.versions,store.evidence],async()=>{
 const g=++generation;evidenceByVersion.value={}
 if(!detail.value || !store.versions)return
 const result=await loadCellVersionEvidence({detail:detail.value,columns:columns.value,currentSubmission:store.currentSubmission,evidence:store.evidence,api:path=>store.api(path)})
 if(g===generation)evidenceByVersion.value=result
},{flush:'post'})
</script>

<template>
  <section v-if="detail" class="cell-detail live-cell-detail">
    <h2><span data-copy-key="reviewText.text008">{{ copy.reviewText.text008 }}</span></h2>
    <div class="table-scroll version-grid live-version-grid" tabindex="0" role="region" aria-label="셀 단위 버전 비교"><table :style="{ minWidth: Math.max(760, (columns.length + 2) * 200) + 'px' }"><colgroup><col style="width:220px" /><col v-for="column in columns" :key="column.submissionId" style="width:200px" /><col style="width:180px" /></colgroup><thead><tr><th><span data-copy-key="reviewText.text009">{{ copy.reviewText.text009 }}</span></th><th v-for="column in columns" :key="column.submissionId" :class="{'current-version':column.state === 'CURRENT','new-version':column.state === 'NEW'}"><strong>{{ column.label }}</strong><span>{{ state(column.state) }}</span><small v-if="column.approvedAt">{{ date(column.approvedAt) }} <span data-copy-key="reviewText.text010">{{ copy.reviewText.text010 }}</span></small><small v-else-if="column.nextLabelOnApproval"><span data-copy-key="reviewText.text011">{{ copy.reviewText.text011 }}</span> {{ column.nextLabelOnApproval }}</small></th><th class="cell-version-change"><strong>차액 · 차이율</strong><small>현재 − 이전 제출본</small></th></tr></thead><tbody><template v-for="group in sourceGroups" :key="group.sheet"><tr class="source-sheet"><th :colspan="columns.length + 2">{{ group.sheet }}</th></tr><tr v-for="row in group.rows" :key="row.cell"><th scope="row"><strong>{{ row.cell }}</strong><small>{{ row.name }}</small></th><td v-for="(column,index) in columns" :key="column.submissionId" :class="{'current-cell':column.state === 'CURRENT','new-cell':column.state === 'NEW'}"><strong class="live-version-value">{{ money(row.values[index]) }}<small v-if="row.values[index] != null"><span data-copy-key="reviewText.text003">{{ copy.reviewText.text003 }}</span></small></strong><div class="cell-version-evidence"><small>거래내역 및 산출근거</small><p v-if="!evidenceByVersion[String(column.submissionId)]" class="muted">불러오는 중…</p><p v-else-if="evidenceByVersion[String(column.submissionId)].state==='unavailable'" class="muted">이 버전의 원문을 조회할 수 없습니다.</p><template v-else><p v-for="(text,i) in evidenceByVersion[String(column.submissionId)].texts[row.cell] || []" :key="i">{{ text }}</p><p v-if="!evidenceByVersion[String(column.submissionId)].texts[row.cell]?.length" class="muted">{{ row.values[index]==null ? '해당 버전에 셀 없음' : '기재 없음' }}</p></template></div></td><td class="cell-version-change" :class="change(row).diff===null?'muted':compare(change(row).diff,'0')>0?'positive':compare(change(row).diff,'0')<0?'negative':'muted'"><strong class="live-version-value">{{ change(row).diff===null?'—':signed(change(row).diff)+'원' }}</strong><p>{{ change(row).rate===null?'산정 불가':(compare(change(row).rate,'0')>0?'+':'')+change(row).rate+'%' }}</p></td></tr></template></tbody></table></div>
    <p v-if="!versionRows.length" class="muted"><span data-copy-key="reviewText.text013">{{ copy.reviewText.text013 }}</span></p>
    <section v-if="latest" class="card live-reply" aria-label="조직 소명 회신">
      <h3>조직 회신 · {{ latest.round }}회차</h3>
      <p v-if="latest.requestNote" class="muted preserve-lines">요청 · {{ latest.requestNote }}</p>
      <template v-if="latest.answer"><p class="preserve-lines">{{ latest.answer }}</p><small class="muted">{{ latest.answeredBy?.name || '조직 담당자' }} · {{ date(latest.answeredAt) }}</small></template>
      <p v-else class="muted">조직 회신을 기다리고 있습니다. 회신 기한 {{ date(latest.dueAt) }}</p>
      <form v-if="latest.answer && latest.verdict === 'PENDING'" class="request-fields" @submit.prevent="decide('ACCEPT')">
        <label class="field"><span data-copy-key="reviewText.text031">{{ copy.reviewText.text031 }}</span><textarea v-model="note" rows="3" maxlength="4000" placeholder="보완을 요청할 때는 무엇을 더 확인할지 적어 주세요." /></label>
        <div class="actions"><button type="button" class="btn" :disabled="store.busy || !note.trim()" @click="decide('REQUEST_AGAIN')"><span data-copy-key="reviewText.text032">{{ copy.reviewText.text032 }}</span></button><button class="btn primary" :disabled="store.busy"><span data-copy-key="reviewText.text033">{{ copy.reviewText.text033 }}</span></button></div>
        <p class="muted caption"><span data-copy-key="reviewText.text034">{{ copy.reviewText.text034 }}</span></p>
      </form>
      <p v-else-if="latest.verdict === 'ACCEPTED'" class="muted">수락한 소명입니다.</p>
      <p v-if="decided" role="status">{{ decided }}</p>
    </section>
  </section>
</template>

<style scoped>
.cell-version-change{background:#f5f8fe;text-align:right}
.live-reply{display:flex;flex-direction:column;gap:10px}
.live-reply h3{font-size:15px;margin:0}
.live-reply .preserve-lines{white-space:pre-wrap;overflow-wrap:anywhere;margin:0}
.cell-version-change small{display:block;font-size:11px;margin-top:5px;font-weight:400}
.cell-version-change p{margin-top:8px;font-weight:600}
.cell-version-evidence{margin-top:12px;display:grid;gap:5px;font-size:12px;line-height:1.6}
.cell-version-evidence>small{color:var(--muted);font-size:11px}
.cell-version-evidence>p{margin:0;white-space:pre-wrap;overflow-wrap:anywhere}
</style>
