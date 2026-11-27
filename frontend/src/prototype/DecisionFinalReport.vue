<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import PButton from 'primevue/button'
import { formatMoney } from '../lib/decimal-display.js'
import { exclusionText } from './decision-agent-api.js'
import { workDateText, workStateLabel } from './decision-workflow-api.js'
const props = defineProps({ analysis: { type: Object, required: true }, workItems: Array, workItemsReady: Boolean, disabled: Boolean })
const report = ref(null)
/** 원 단위 금액을 정밀 억원 표시로 변환한다.
 * Args: value 금액. Returns: 표시값. Raises: 없음. Side Effects: 없음.
 */
function money(value) { return formatMoney(value, 'HUNDRED_MILLION') }
/** A4 요약 문장을 한정하고 생략 여부를 표시한다.
 * Args: value 원문, length 최대 글자 수. Returns: 요약 문자열.
 * Raises: 없음. Side Effects: 없음; 전체 원문은 근거·업무 화면에 남는다.
 */
function brief(value, length = 110) { const text = String(value || ''); return text.length > length ? `${text.slice(0, length)}…` : text }
/** 조직 ID를 고정 기준의 조직명으로 변환한다.
 * Args: id 조직 ID. Returns: 조직명. Raises: 없음. Side Effects: 없음.
 */
function organization(id) { for (const row of props.analysis.snapshot.organizations) if (row.id === id) return row.name; return '조직 확인 필요' }
/** 계산 효과에 저장 후보를 연결해 핵심 표를 구성한다.
 * Args: 없음. Returns: 보고서 과제 행. Raises: 없음. Side Effects: 없음.
 */
function rows() {
  const output = []
  for (const effect of props.analysis.result?.effects || []) {
    for (const candidate of props.analysis.candidates) if (candidate.id === effect.candidateId) output.push({ ...candidate, ...effect })
  }
  return output
}
const reportRows = computed(rows)
/** 보호 비용을 요약한다.
 * Args: 없음. Returns: 제약 문구. Raises: 없음. Side Effects: 없음.
 */
function protectionText() { const labels = []; for (const category of props.analysis.protectedCategories) labels.push(category === 'HEADCOUNT' ? '인력 관련 효과 제외' : 'R&D 효과 제외'); return labels.join(' · ') || '별도 보호 제약 없음' }
const protection = computed(protectionText)
/** 완료·취소를 포함한 실제 조직 검토 상태만 요약한다.
 * Args: 없음. Returns: 최근 업무 행. Raises: 없음. Side Effects: 없음.
 */
function relevantItems() { const output = []; for (const item of props.workItems || []) if (item.analysisId === props.analysis.id) output.push(item); return output }
const workRows = computed(relevantItems)
/** 원천 목록을 한 장에서 추적 가능한 범위로 요약한다.
 * Args: 없음. Returns: 원본 제출 상위 여섯 건·전체 건수·상세 위치.
 * Raises: 없음. Side Effects: 없음; 전체 원천은 고정 스냅샷에 유지한다.
 */
function sourceReferences() {
  const ids = props.analysis.snapshot.sourceSubmissionIds
  return `${ids.slice(0, 6).join(', ')}${ids.length > 6 ? ` 외 ${ids.length - 6}건` : ''} · 전체 ${ids.length}건 · 전체 원천은 근거와 기준 탭`
}
const sourceSummary = computed(sourceReferences)
/** 인쇄 종료 또는 취소 후 출력용 DOM과 상태를 정리한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 출력 복제 DOM 제거.
 */
function finishPrint() { document.getElementById('agent-decision-print-root')?.remove(); document.body.classList.remove('agent-decision-printing'); window.removeEventListener('afterprint', finishPrint) }
/** 현재 검증 결과의 한 장 요약을 브라우저 인쇄 대화상자로 보낸다.
 * Args: 없음. Returns: 없음. Raises: 브라우저 인쇄 오류.
 * Side Effects: 출력 전용 DOM 복제와 인쇄 대화상자; 자동 파일 저장은 하지 않는다.
 */
function printReport() {
  if (!report.value || !props.analysis.result || !props.workItemsReady || props.disabled) return
  finishPrint()
  const container = document.createElement('div'); container.id = 'agent-decision-print-root'; container.append(report.value.cloneNode(true)); document.body.append(container)
  document.body.classList.add('agent-decision-printing'); window.addEventListener('afterprint', finishPrint, { once: true })
  try { window.print() } catch (cause) { finishPrint(); throw cause }
}
onBeforeUnmount(finishPrint)
</script>
<template><div v-if="analysis.result" class="final-report-container"><div class="report-toolbar"><div><strong>회의용 의사결정 요약</strong><p>고정 기준, 검토 후보, 실제 결정·후속 기록을 한 장으로 정리합니다.</p></div><PButton label="A4 의사결정서 인쇄" icon="pi pi-print" outlined :disabled="disabled || !workItemsReady" @click="printReport" /></div><p v-if="!workItemsReady" class="report-small">조직 검토·실행 기록을 조회한 뒤 인쇄할 수 있습니다. 조회에 실패했다면 해당 탭에서 다시 조회해 주세요.</p><article ref="report" class="agent-decision-sheet"><header><p class="report-eyebrow">P&amp;L INTELLIGENCE / EXECUTIVE DECISION NOTE</p><h3>{{ brief(analysis.title, 50) }}</h3><p>{{ analysis.snapshot.year }}년 목표 · {{ brief(analysis.snapshot.masterLabel, 35) }} · 분석 v{{ analysis.revision }}</p></header><section class="report-conclusion"><div><h4>전체 후보 반영 시 {{ money(analysis.result.conditionalOutlookWon) }}억 원 전망</h4><p>{{ brief(analysis.narrative?.summary || '목표와 최신 확정 전망의 차이를 기준으로, 개선 근거와 추가 확인할 조건을 검토합니다.', 160) }}</p></div><div class="report-gap"><span>조건부 반영 후 남은 Gap</span><strong>{{ money(analysis.result.conditionalGapWon) }}<small>억 원</small></strong><span>미래 실현을 전제로 한 전망</span></div></section><section class="report-numbers"><div><span>현재 연간 전망</span><strong>{{ money(analysis.result.baselineProfitWon) }}<small>억</small></strong></div><div><span>근거 검토 후보 반영</span><strong>{{ money(analysis.result.reviewedOutlookWon) }}<small>억</small></strong></div><div><span>가정 포함 전체 후보</span><strong>{{ money(analysis.result.conditionalOutlookWon) }}<small>억</small></strong></div><div><span>경영 목표</span><strong>{{ money(analysis.result.targetProfitWon) }}<small>억</small></strong></div></section><section class="report-section"><h4><span>01</span> 숫자를 뒷받침하는 후보와 조건</h4><table><thead><tr><th>조직 / 과제</th><th>확인 근거와 반영 조건</th><th>추가 이익</th></tr></thead><tbody><tr v-for="row in reportRows.slice(0, 3)" :key="row.candidateId"><td><strong>{{ brief(organization(row.organizationId), 20) }}</strong><small>{{ brief(row.title, 35) }}</small></td><td>{{ brief(row.evidenceNote || '근거 추가 확인 필요', 48) }}<small>{{ row.included ? `${row.recognitionMonth}월 · ${row.reviewed ? '담당자 근거 검토' : '가정 단계'}` : exclusionText(row.exclusionReasons) }}</small></td><td :class="row.included ? 'report-positive' : 'report-excluded'">{{ row.included ? '+' : '' }}{{ money(row.netEffectWon) }}억<small v-if="!row.included">합계 제외</small></td></tr><tr v-if="!reportRows.length"><td colspan="3">입력된 개선 후보가 없습니다. 추가 근거를 확보해 주세요.</td></tr></tbody></table><p class="report-small">{{ reportRows.length > 3 ? `전체 ${reportRows.length}건 중 3건 표시. 합계는 전체 적격 후보를 반영하며, 전체 과제·근거는 분석 화면에서 확인합니다.` : '근거 검토 후보는 전체 후보 시나리오에 포함됩니다. 두 효과를 합산하지 않습니다.' }}</p></section><section class="report-section"><h4><span>02</span> 조직 검토와 결정·후속 추적</h4><table><thead><tr><th>조직 / 과제</th><th>현재 업무 상태</th><th>확인한 결정 / 후속 기준</th></tr></thead><tbody><tr v-for="item in workRows.slice(0, 2)" :key="item.id"><td>{{ brief(item.organizationName, 20) }}<small>{{ brief(item.title, 30) }}</small></td><td>{{ workStateLabel(item.state) }}</td><td>{{ item.decision ? `${brief(item.decision.decisionMaker, 20)} · ${brief(item.decision.note, 45)}` : '추진 결정 전' }}<small v-if="item.progress?.followupMasterId">{{ brief(item.progress.followupMasterLabel, 30) }} · 조직 전망 변화 {{ money(item.progress.profitChangeWon) }}억</small></td></tr><tr v-if="!workRows.length"><td colspan="3">{{ workItemsReady ? '아직 조직 검토 요청·추진 결정이 기록되지 않았습니다.' : '조직 검토·실행 기록 조회가 필요합니다.' }}</td></tr></tbody></table><p class="report-small">{{ workRows.length > 2 ? `전체 ${workRows.length}건 중 최근 2건. 상세는 조직 검토·실행 탭에서 확인합니다. ` : '' }}조직 연간 전망 변화이며 이 과제의 실현 성과를 확정하지 않습니다.</p></section><section class="report-section report-decisions"><h4><span>03</span> 이번 회의의 검토 안건</h4><ol><li v-for="request in (analysis.narrative?.decisionRequests || []).slice(0, 3)" :key="request">{{ brief(request, 105) }}</li><li v-if="!analysis.narrative?.decisionRequests?.length">근거와 실행 조건을 확인하고, 남은 Gap {{ money(analysis.result.conditionalGapWon) }}억 원의 추가 개선 방향을 정합니다.</li></ol></section><aside class="report-assumptions"><strong>판단의 전제</strong><p>{{ protection }} · {{ analysis.snapshot.baseMonth < 12 ? `${analysis.snapshot.baseMonth + 1}월부터 연말까지의 후보 효과 검토` : '기준 연도 잔여 검토 기간 없음' }}. 모든 개선 전망은 조건부이며 근거 검토·추진 결정·실현 성과를 구분합니다.</p><p>{{ brief((analysis.narrative?.limitations || []).join(' '), 180) }}</p><p>분석 {{ analysis.id }} · 기준 마스터 {{ analysis.snapshot.masterId }} · 기준일 {{ workDateText(analysis.snapshot.asOf) }} · 원본 제출 {{ sourceSummary }}</p></aside><footer><span>{{ analysis.narrative?.mode === 'TEMPLATE' ? '표준 설명 초안 · 서버 계산 결과 사용' : '서버 계산 결과와 연결 근거에 따른 검토 요약' }}</span><strong>P&amp;L AGENT / 01</strong></footer></article></div></template>
<style scoped>.report-toolbar{display:flex;align-items:start;justify-content:space-between;gap:20px;margin-bottom:20px}.report-toolbar strong{font-size:15px}.report-toolbar p{font-size:12px;color:#7a7a76;line-height:1.7;margin:7px 0 0}.report-toolbar :deep(.p-button){font-size:12px}.agent-decision-sheet{color:#2d2d2b;background:white;border-top:4px solid #2d2d2b;padding:24px 0 0;font-size:12px;line-height:1.6}.agent-decision-sheet h3,.agent-decision-sheet h4,.agent-decision-sheet p{margin:0}.report-eyebrow{font-size:10px;font-weight:750;letter-spacing:.17em;color:#5c5c59}.agent-decision-sheet header h3{font-size:26px;letter-spacing:-.04em;line-height:1.35;margin:12px 0 9px;overflow-wrap:anywhere}.agent-decision-sheet header>p:last-child{font-size:11px;color:#7a7a76}.report-conclusion{display:grid;grid-template-columns:minmax(0,1fr) 185px;gap:22px;padding:24px 0;border-bottom:1px solid #e3e3e3}.report-conclusion h4{font-size:19px;letter-spacing:-.03em;line-height:1.5;margin-bottom:9px}.report-conclusion p{color:#7a7a76;font-size:12px;line-height:1.8}.report-gap{display:flex;flex-direction:column;gap:8px;border-left:1px solid #e3e3e3;padding-left:20px}.report-gap span{font-size:10px;color:#7a7a76}.report-gap strong{font-size:32px;line-height:1.2;letter-spacing:-.04em;color:#ac4e46;overflow-wrap:anywhere}.report-gap small{font-size:13px;margin-left:4px}.report-numbers{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));padding:20px 0;border-bottom:1px solid #e3e3e3}.report-numbers>div{border-left:1px solid #e3e3e3;padding-left:14px}.report-numbers>div:first-child{border:0;padding:0}.report-numbers span{display:block;color:#7a7a76;font-size:10px;margin-bottom:6px}.report-numbers strong{font-size:24px;letter-spacing:-.04em;overflow-wrap:anywhere}.report-numbers small{font-size:12px;color:#7a7a76}.report-section{margin-top:23px}.report-section h4{font-size:15px;margin-bottom:13px}.report-section h4>span{font-size:11px;margin-right:8px;color:#5c5c59}.report-section table{width:100%;border-collapse:collapse;table-layout:fixed;font-size:11px}.report-section th{background:#f4f4f3;color:#7a7a76;font-weight:650}.report-section th,.report-section td{text-align:left;border-bottom:1px solid #e3e3e3;padding:10px 8px;vertical-align:top;overflow-wrap:anywhere}.report-section th:first-child{width:29%}.report-section th:last-child{width:24%}.report-section small{display:block;color:#7a7a76;font-size:10px;margin-top:4px}.report-section .report-positive{font-weight:650;color:#355635}.report-excluded{color:#7a7a76}.report-small{font-size:10px;color:#7a7a76;margin-top:8px!important;line-height:1.6}.report-decisions ol{padding-left:19px;margin:0}.report-decisions li{margin:0 0 7px;padding-left:3px;line-height:1.75;font-size:12px}.report-assumptions{background:#f6f6f6;padding:14px 16px;margin-top:22px;font-size:10px;line-height:1.7}.report-assumptions strong{font-size:11px}.report-assumptions p{margin-top:6px;color:#7a7a76;overflow-wrap:anywhere}.agent-decision-sheet footer{display:flex;justify-content:space-between;gap:16px;border-top:2px solid #2d2d2b;margin-top:20px;padding-top:12px;color:#7a7a76;font-size:9px}.agent-decision-sheet footer strong{white-space:nowrap}@media(max-width:700px){.report-toolbar{flex-direction:column}.report-conclusion{grid-template-columns:1fr}.report-gap{border:0;padding:0}.report-numbers{grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.report-numbers strong{font-size:22px}}
</style>
<style>
#agent-decision-print-root{display:none}
@media print{
  @page{size:A4 portrait;margin:10mm}
  body.agent-decision-printing> :not(#agent-decision-print-root){display:none!important}
  body.agent-decision-printing{margin:0!important;padding:0!important;background:white!important}
  body.agent-decision-printing #agent-decision-print-root{display:block!important;width:190mm;margin:0;padding:0}
  #agent-decision-print-root .agent-decision-sheet{border-top-width:3px;padding-top:4mm;font-size:8pt;line-height:1.35;print-color-adjust:exact;-webkit-print-color-adjust:exact}
  #agent-decision-print-root .agent-decision-sheet header h3{font-size:19pt;margin:2mm 0}
  #agent-decision-print-root .report-eyebrow{font-size:6.5pt}
  #agent-decision-print-root .agent-decision-sheet header>p:last-child{font-size:7pt}
  #agent-decision-print-root .report-conclusion{grid-template-columns:minmax(0,1fr) 43mm;gap:5mm;padding:4mm 0}
  #agent-decision-print-root .report-conclusion h4{font-size:12pt;margin-bottom:2mm}
  #agent-decision-print-root .report-conclusion p{font-size:8pt;line-height:1.5}
  #agent-decision-print-root .report-gap{border-left:1px solid #e3e3e3;padding-left:4mm;gap:2mm}
  #agent-decision-print-root .report-gap strong{font-size:23pt}
  #agent-decision-print-root .report-gap span{font-size:7pt}
  #agent-decision-print-root .report-numbers{grid-template-columns:repeat(4,minmax(0,1fr));padding:4mm 0;gap:0}
  #agent-decision-print-root .report-numbers strong{font-size:17pt}
  #agent-decision-print-root .report-numbers span{font-size:7pt}
  #agent-decision-print-root .report-section{margin-top:4mm;break-inside:avoid}
  #agent-decision-print-root .report-section h4{font-size:10pt;margin-bottom:2mm}
  #agent-decision-print-root .report-section table{font-size:7.5pt}
  #agent-decision-print-root .report-section th,#agent-decision-print-root .report-section td{padding:1.5mm 1.5mm;line-height:1.35}
  #agent-decision-print-root .report-section small,#agent-decision-print-root .report-small{font-size:6.5pt;line-height:1.35}
  #agent-decision-print-root .report-decisions li{font-size:8pt;line-height:1.5;margin-bottom:1.5mm}
  #agent-decision-print-root .report-assumptions{font-size:6.5pt;line-height:1.4;margin-top:4mm;padding:2.5mm;break-inside:avoid}
  #agent-decision-print-root .report-assumptions p{margin-top:1mm}
  #agent-decision-print-root .agent-decision-sheet footer{font-size:6pt;margin-top:3mm;padding-top:2mm}
}
</style>
