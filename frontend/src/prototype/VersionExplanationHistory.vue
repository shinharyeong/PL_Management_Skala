<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { usePrototypeStore } from './live-store.js'
import { explanationEvents, loadVersionExplanations } from './explanation-history.js'
import { saveFile } from './live-api.js'
const props=defineProps({detail:Object,columns:Array,currentSubmission:Object})
const store=usePrototypeStore(),blocks=ref([]),loading=ref(false)
let generation=0
onBeforeUnmount(()=>generation++)
watch(()=>[props.detail?.id,props.detail?.stateToken,(props.columns||[]).map(c=>c.submissionId).join(',')],async()=>{
  const current=++generation
  blocks.value=[]
  if(!props.detail)return
  loading.value=true
  const result=await loadVersionExplanations({detail:props.detail,columns:props.columns||[],currentSubmission:props.currentSubmission,api:(path)=>store.api(path)})
  if(current===generation){blocks.value=result;loading.value=false}
},{immediate:true})
const roundLabel=value=>({ACCEPTED:'소명 승인',REJECTED:'보완 요청',PENDING:'검토 대기'})[value]||'검토 대기'
const versionState=column=>column.state==='CURRENT'?'현재':column.state==='CONFIRMED'?'확정':'미확정'
const versionTone=column=>column.state==='NEW'?'new':column.state==='CURRENT'?'current':'confirmed'
function date(value,short=false){if(!value)return '—';const d=new Date(value);if(Number.isNaN(d.getTime()))return '—';return new Intl.DateTimeFormat('ko-KR',{timeZone:'Asia/Seoul',month:'2-digit',day:'2-digit',...(short?{}:{hour:'2-digit',minute:'2-digit',hour12:false})}).format(d)}
const rounds=block=>[...(block.detail?.explanations||[])].sort((a,b)=>a.round-b.round)
function download(file,reviewId){return store.run(async()=>saveFile(await store.api(`/api/v1/review-items/${reviewId}/attachments/${file.id}/file`,{blob:true}),file.filename||file.name))}
</script>

<template>
  <section class="version-explanations" aria-label="버전별 소명 이력·담당자 메모">
    <h2>버전별 소명 이력 · 담당자 메모</h2>
    <p v-if="loading" class="muted" role="status">버전별 소명 이력을 불러오는 중입니다.</p>
    <p v-else-if="!blocks.length" class="muted">조회할 버전별 소명 이력이 없습니다.</p>
    <details v-for="block in blocks" :key="block.column.submissionId" class="version-history-block" :class="versionTone(block.column)" open>
      <summary class="version-history-header">
        <strong>{{block.column.label}}</strong><span class="version-state">{{versionState(block.column)}}</span>
        <span class="version-history-meta"><template v-if="block.previousLabel">{{block.previousLabel}} 대비<span aria-hidden="true"> · </span></template><template v-if="block.column.approvedAt">{{date(block.column.approvedAt,true)}} 제출본 승인</template><template v-else>제출본 승인 전</template></span>
        <span class="version-disclosure" aria-hidden="true">▾</span>
      </summary>
      <div class="version-history-content">
        <section class="version-timeline" aria-label="소명 이력">
          <h3>소명 이력</h3>
          <ol><li v-for="event in explanationEvents(block.submission,block.detail?.explanations)" :key="event.key"><time :datetime="event.at || undefined" :title="event.at ? date(event.at) : '처리 시각 정보 없음'">{{date(event.at,true)}}</time><strong>{{event.label}}</strong><span v-if="event.actor" class="timeline-actor">{{event.actor}}</span></li></ol>
          <p v-if="block.error" class="error" role="status">{{block.error}}</p>
          <p v-else-if="!block.detail" class="muted history-empty">이 버전에는 해당 항목의 소명 이력이 없습니다.</p>
        </section>
        <section class="version-memos" aria-label="담당자 소명 메모">
          <article v-for="item in rounds(block)" :key="item.id" class="version-memo-card">
            <header><h3>담당자 소명 메모</h3><span class="memo-verdict" :class="(item.verdict||'PENDING').toLowerCase()">{{roundLabel(item.verdict)}}</span></header>
            <p class="memo-author">{{item.answeredBy?.name || '회신 대기'}}<template v-if="block.submission?.organization?.name"> · {{block.submission.organization.name}}</template><template v-if="item.answeredAt"> · {{date(item.answeredAt)}}</template></p>
            <p class="memo-answer">{{item.answer || '아직 소명 회신이 없습니다.'}}</p>
            <div v-if="item.attachments?.length" class="memo-attachments"><button v-for="file in item.attachments" :key="file.id" type="button" :disabled="store.busy" @click="download(file,block.detail.id)"><span>첨부</span> {{file.filename || file.name}} ↓</button></div>
            <p v-if="item.requestNote" class="memo-request"><strong>경영기획 요청</strong><span v-if="item.requestedBy?.name"> · {{item.requestedBy.name}}</span><span v-if="item.requestedAt"> · {{date(item.requestedAt)}}</span><br>{{item.requestNote}}</p>
            <p v-if="item.reviewNote" class="memo-request"><strong>검토 메모</strong><br>{{item.reviewNote}}</p>
            <p v-if="item.inheritedFrom" class="memo-inherited">{{item.inheritedFrom.label}}에서 이어받은 소명</p>
          </article>
          <p v-if="!rounds(block).length" class="history-empty muted">등록된 담당자 소명 메모가 없습니다.</p>
        </section>
      </div>
    </details>
  </section>
</template>

<style scoped>
.version-explanations{display:flex;flex-direction:column;gap:16px}.version-explanations>h2{font-size:18px;margin-bottom:2px}.version-history-block{border:1px solid #dfdbd4;border-radius:12px;overflow:hidden;background:#fff}.version-history-block.current{border-color:#b8894c}.version-history-block.new{border-color:#c3c3c1}.version-history-header{display:flex;align-items:center;gap:10px;padding:14px 18px;background:#faf9f7;cursor:pointer;list-style:none}.version-history-header::-webkit-details-marker{display:none}.current>.version-history-header{background:#f5eee3}.new>.version-history-header{background:#f6f6f5}.version-history-header strong{font-size:15px;font-variant-numeric:tabular-nums}.version-state{border-radius:12px;padding:2px 8px;font-size:11px;color:#5b5b58;background:#ededec}.current .version-state{background:#b28c57;color:white}.new .version-state{background:#f0f0f0;color:#6a6a66}.version-history-meta{font-size:12px;color:#7c7c78}.version-disclosure{margin-left:auto;color:#70746f;transition:transform .15s}.version-history-block:not([open]) .version-disclosure{transform:rotate(-90deg)}.version-history-content{display:grid;grid-template-columns:minmax(180px,.95fr) minmax(0,1.15fr);gap:24px;padding:18px}.version-history-content h3{font-size:13px;font-weight:650;margin:0}.version-timeline ol{list-style:none;padding:0;margin:12px 0 0;display:flex;flex-direction:column;gap:11px}.version-timeline li{position:relative;display:flex;gap:8px;align-items:baseline;flex-wrap:wrap;padding-left:15px;font-size:12px}.version-timeline li::before{content:'';position:absolute;left:0;top:7px;width:6px;height:6px;border-radius:50%;background:#40946a}.version-timeline time{font-size:11px;color:#91958f;font-variant-numeric:tabular-nums;min-width:38px}.version-timeline strong{font-weight:500}.timeline-actor{font-size:11px;color:#848982}.version-memos{display:flex;flex-direction:column;gap:12px;min-width:0}.version-memo-card{padding:14px;border:1px solid #e4e1dc;border-radius:9px;background:#faf9f7;min-width:0}.version-memo-card header{display:flex;align-items:center;justify-content:space-between;gap:10px}.memo-verdict{font-size:11px;border-radius:12px;padding:2px 8px;white-space:nowrap}.memo-verdict.accepted{background:#e6f3eb;color:#496849}.memo-verdict.pending{background:#fff0cf;color:#8b640f}.memo-verdict.rejected{background:#fde7e0;color:#99503e}.memo-author{font-size:11px;color:#919590;margin:7px 0}.memo-answer{font-size:13px;line-height:1.7;white-space:pre-wrap;overflow-wrap:anywhere;margin:0}.memo-attachments{display:flex;gap:6px;flex-wrap:wrap;margin-top:10px}.memo-attachments button{max-width:100%;padding:4px 7px;border:1px solid #e5e1d9;background:#fff;border-radius:5px;font:inherit;font-size:11px;text-align:left;overflow-wrap:anywhere;color:#656562}.memo-attachments span{color:#90958f;margin-right:4px}.memo-request,.memo-inherited{font-size:11px;color:#848982;line-height:1.65;white-space:pre-wrap;overflow-wrap:anywhere;margin:10px 0 0}.memo-request strong{font-weight:500}.history-empty{font-size:12px;line-height:1.7;margin:12px 0}.version-history-header:focus-visible{outline:2px solid #6d6d69;outline-offset:-3px}@media(max-width:1050px){.version-history-content{grid-template-columns:1fr;gap:18px}}@media(max-width:600px){.version-history-header{flex-wrap:wrap;padding:12px}.version-history-meta{order:4;width:100%}.version-history-content{padding:14px}}
</style>
