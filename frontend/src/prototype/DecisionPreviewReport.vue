<script setup>
import { computed } from 'vue'
import PButton from 'primevue/button'
import PTag from 'primevue/tag'
import PDataTable from 'primevue/datatable'
import PColumn from 'primevue/column'
import { formatMoney } from '../lib/decimal-display.js'
import { workDateText } from './decision-workflow-api.js'

const props = defineProps({ result: { type: Object, required: true }, baselineLabel: { type: String, default: '' }, year: Number })
const emit = defineEmits(['source'])

/** 보고서에 사용할 확인된 목표 비교를 반환한다.
 * Args: 없음. Returns: 목표 비교 또는 null. Raises: 없음. Side Effects: 없음.
 */
function currentGoal() { return ['READY', 'PARTIAL'].includes(props.result.state) ? props.result.goal || null : null }
const goal = computed(currentGoal)
/** 한눈에 비교할 조직 행만 앞에서부터 선택한다.
 * Args: 없음. Returns: 최대 다섯 행. Raises: 없음. Side Effects: 원본 변경 없음.
 */
function organizationRows() { return ['READY', 'PARTIAL'].includes(props.result.state) ? (props.result.organizations || []).slice(0, 5) : [] }
const rows = computed(organizationRows)
/** 검증된 현재 실행의 출처 목록을 반환한다.
 * Args: 없음. Returns: 출처 목록. Raises: 없음. Side Effects: 없음.
 */
function sourceRows() { return ['READY', 'PARTIAL'].includes(props.result.state) ? props.result.sources || [] : [] }
const sources = computed(sourceRows)
/** 현재 기준에 연결된 분석 설명만 보고서에 표시한다.
 * Args: 없음. Returns: 분석 설명 또는 null. Raises: 없음. Side Effects: 없음.
 */
function currentBrief() { return ['READY', 'PARTIAL'].includes(props.result.state) ? props.result.brief || null : null }
const brief = computed(currentBrief)
/** 숫자를 반올림하거나 계산하지 않고 억원으로 표시한다.
 * Args: value 원 단위 십진 문자열. Returns: 정밀 숫자 또는 대시. Raises: 없음. Side Effects: 없음.
 */
function money(value) { return formatMoney(value, 'HUNDRED_MILLION') }
/** 긴 정밀 금액도 잘리지 않도록 표시 크기를 선택한다.
 * Args: value 원 단위 금액. Returns: 긴 숫자 여부. Raises: 없음. Side Effects: 없음.
 */
function longMoney(value) { return money(value).length > 14 }
/** 원본 차이의 부호를 보존하며 양수에만 더하기 표시를 붙인다.
 * Args: value 원 단위 차이 문자열. Returns: 부호 있는 억원 숫자. Raises: 없음. Side Effects: 없음.
 */
function signedMoney(value) { const text = money(value); return text !== '—' && text !== '0' && !text.startsWith('-') ? `+${text}` : text }
/** 서버 목표 비교만으로 핵심 판단 문구를 정한다.
 * Args: 없음. Returns: 숫자 창작이 없는 판단 문구. Raises: 없음. Side Effects: 없음.
 */
function judgment() {
  if (props.result.state === 'LOADING') return '현재 전망과 목표를 확인하고 있습니다.'
  if (props.result.state === 'STALE') return '기준이 바뀌어 다시 비교가 필요합니다.'
  if (goal.value?.forecastMeetsTarget === true) return '현재 전망은 목표 이상입니다.'
  if (goal.value?.forecastMeetsTarget === false) return '목표까지 추가 이익 확보가 필요합니다.'
  if (brief.value) return brief.value.unresolved.length ? '확인된 내용과 남은 질문을 정리했습니다.' : '요청한 내용을 근거와 함께 정리했습니다.'
  if (rows.value.length) return '조직별 계획 차이를 확인했습니다.'
  if (sources.value.length) return '목표 비교 자료를 추가로 확인해야 합니다.'
  if (props.result.state === 'ERROR') return '목표 비교를 완료하지 못했습니다.'
  return '검토할 목표를 기다리고 있습니다.'
}
const headline = computed(judgment)
/** 확인 범위와 실행 상태를 짧게 설명한다.
 * Args: 없음. Returns: 핵심 판단 보조 문장. Raises: 없음. Side Effects: 없음.
 */
function judgmentDetail() {
  if (props.result.state === 'LOADING') return '선택한 확정 기준으로 목표 차이와 조직별 손익을 조회합니다.'
  if (props.result.state === 'STALE') return props.result.message || '변경한 기준과 목표로 다시 비교해 주세요. 이전 결과는 표시하지 않습니다.'
  if (brief.value) return brief.value.summary
  if (goal.value) return '선택한 확정 기준의 연간 전망과 입력 목표를 비교한 결과입니다.'
  if (rows.value.length) return '목표 대비 판단은 목표 금액을 입력한 비교 조회에서 확인할 수 있습니다.'
  if (sources.value.length) return '현재 확인된 근거는 아래 출처에서 볼 수 있습니다. 목표와의 차이는 비교 조회 후 표시합니다.'
  if (props.result.state === 'ERROR') return props.result.message || '기준과 목표를 확인하고 다시 비교해 주세요.'
  return '왼쪽에서 기준과 목표를 정하면, 확인한 숫자와 근거가 이 보고서에 모입니다.'
}
const detail = computed(judgmentDetail)
/** 목표 이상 여부에 맞는 차이 금액을 서버 결과에서 선택한다.
 * Args: 없음. Returns: 원 단위 차이 또는 null. Raises: 없음. Side Effects: 계산 없음.
 */
function differenceAmount() { return goal.value ? goal.value.forecastMeetsTarget ? goal.value.surplusWon : goal.value.gapWon : null }
const difference = computed(differenceAmount)
/** 목표 차이의 정확한 의미를 지표 제목으로 표시한다.
 * Args: 없음. Returns: 지표 제목. Raises: 없음. Side Effects: 없음.
 */
function differenceLabel() { return goal.value?.forecastMeetsTarget === true ? '목표 초과 전망' : '목표까지 부족액' }
const differenceTitle = computed(differenceLabel)
/** 서버가 제공한 손익 영향을 색상으로만 보조한다.
 * Args: row 조직 결과. Returns: 색상 클래스. Raises: 없음. Side Effects: 유불리 재판단 없음.
 */
function directionClass(row) { return row.favorability === 'FAVORABLE' ? 'is-favorable' : row.favorability === 'UNFAVORABLE' ? 'is-unfavorable' : 'is-neutral' }
/** 조직 영업이익의 서버 비교 방향을 한국어로 표시한다.
 * Args: row 조직 결과. Returns: 계획 대비 방향. Raises: 없음. Side Effects: 수치 재판단 없음.
 */
function planDirection(row) { if (row.missing) return '자료 없음'; return { INCREASE: '계획 초과', DECREASE: '계획 미달', UNCHANGED: '계획 일치', MISSING: '비교 불가' }[row.direction] || '비교 확인 필요' }
/** 미조회와 실제 빈 조직 결과를 구별해 표시한다.
 * Args: 없음. Returns: 조직 표의 빈 상태 안내. Raises: 없음. Side Effects: 없음.
 */
function organizationEmptyText() {
  if (props.result.organizationCount === 0) return '조회된 조직별 비교 자료가 없습니다.'
  if (['READY', 'PARTIAL'].includes(props.result.state)) return '조직별 계획 차이는 추가 조회가 필요합니다.'
  if (props.result.state === 'ERROR') return '이번 실행에서 확인된 조직별 자료가 없습니다.'
  return '조직별 조회 결과가 이 자리에 정리됩니다.'
}
/** 사용자가 선택한 실제 관찰의 출처를 부모에 전달한다.
 * Args: id 관찰 ID. Returns: 없음. Raises: 없음. Side Effects: source 이벤트만 발생.
 */
function openSource(id) { if (id) emit('source', id) }
/** 사실과 해석·가설을 다른 의미의 표시 태그로 구분한다.
 * Args: kind 서버 설명 유형. Returns: PrimeVue 색상. Raises: 없음. Side Effects: 없음.
 */
function findingSeverity(kind) { return kind === 'FACT' ? 'secondary' : kind === 'INTERPRETATION' ? 'info' : 'warn' }
</script>

<template>
  <article class="preview-report" aria-label="손익 목표 검토 보고서" :aria-busy="result.state === 'LOADING'">
    <header class="preview-report__header">
      <div>
        <p class="preview-report__eyebrow">P&amp;L INTELLIGENCE / EXECUTIVE NOTE</p>
        <h2 class="preview-report__title">연간 손익 목표 검토</h2>
        <p class="preview-report__basis">{{ result.context?.masterLabel || baselineLabel || '확정 기준 선택 전' }}<span v-if="result.context?.baseMonth"> · {{ result.context?.baseMonth }}월 기준</span></p>
      </div>
      <div class="preview-report__status"><PTag value="미저장 검토" severity="secondary" /><span>{{ result.context?.year || year }} ANNUAL OUTLOOK</span></div>
    </header>

    <section class="preview-report__judgment" :class="{ 'has-surplus': goal?.forecastMeetsTarget === true }">
      <p class="preview-report__section-label">이번 검토의 핵심</p>
      <h3>{{ headline }}</h3>
      <p :class="{ 'preview-report__brief-summary': brief }">{{ detail }}</p>
      <p v-if="result.state === 'PARTIAL'" class="preview-report__partial"><i class="pi pi-info-circle" aria-hidden="true" />{{ result.status === 'NEEDS_INPUT' ? '추가 확인 필요 · 아래 질문에 필요한 자료를 확인해 주세요.' : '답변 일부 미완료 · 조회에 성공한 자료로 정리했습니다.' }}</p>
    </section>

    <section class="preview-report__metrics" aria-label="전망과 목표 비교">
      <div><span>현재 연간 전망</span><div v-if="result.state === 'LOADING'" class="preview-report__skeleton metric-skeleton" aria-hidden="true" /><strong v-else :class="{ 'long-number': longMoney(goal?.baselineProfitWon) }">{{ money(goal?.baselineProfitWon) }}<small v-if="goal">억 원</small></strong><p>확정 기준의 전망</p></div>
      <div><span>경영 목표</span><div v-if="result.state === 'LOADING'" class="preview-report__skeleton metric-skeleton" aria-hidden="true" /><strong v-else :class="{ 'long-number': longMoney(goal?.targetProfitWon) }">{{ money(goal?.targetProfitWon) }}<small v-if="goal">억 원</small></strong><p>입력한 영업이익 목표</p></div>
      <div :class="goal ? goal.forecastMeetsTarget ? 'is-favorable' : 'is-unfavorable' : ''"><span>{{ differenceTitle }}</span><div v-if="result.state === 'LOADING'" class="preview-report__skeleton metric-skeleton" aria-hidden="true" /><strong v-else :class="{ 'long-number': longMoney(difference) }">{{ money(difference) }}<small v-if="goal">억 원</small></strong><p>{{ goal ? goal.forecastMeetsTarget ? '현재 전망 기준' : '현재 전망 대비 추가 필요' : '목표 비교 조회 후 확인' }}</p></div>
    </section>

    <section class="preview-report__section">
      <div class="preview-report__section-heading"><h3><span>01</span> 조직별 계획 차이</h3><span class="preview-report__unit">단위: 억 원</span></div>
      <p class="preview-report__caption">영업이익 전망 − 계획 · 계획 초과액 큰 순</p>
      <template v-if="result.state === 'LOADING'"><div v-for="line in 3" :key="line" class="preview-report__skeleton row-skeleton" aria-hidden="true" /></template>
      <PDataTable v-else-if="rows.length" :value="rows" data-key="id" class="preview-report__table" size="small" :pt="{ table: { 'aria-label': '조직별 영업이익 계획 차이' } }">
        <PColumn field="name" header="조직"><template #body="{ data }"><strong class="organization-name">{{ data.name || data.code || '조직명 확인 필요' }}</strong></template></PColumn>
        <PColumn header="연간 전망" body-class="report-amount" header-class="report-amount"><template #body="{ data }">{{ money(data.actualWon) }}</template></PColumn>
        <PColumn header="계획 대비 차이" body-class="report-amount" header-class="report-amount"><template #body="{ data }"><strong :class="directionClass(data)">{{ signedMoney(data.planDifferenceWon) }}</strong></template></PColumn>
        <PColumn header="비교 판단" body-class="report-direction"><template #body="{ data }"><span :class="directionClass(data)">{{ planDirection(data) }}</span></template></PColumn>
      </PDataTable>
      <div v-else class="preview-report__empty-line"><i class="pi pi-chart-bar" aria-hidden="true" /><p>{{ organizationEmptyText() }}</p></div>
      <p v-if="rows.length" class="preview-report__table-note"><template v-if="result.organizationCount != null">기준 {{ result.organizationCount }}개 조직 중 </template>{{ rows.length }}개 표시<span v-if="result.organizationCount != null && result.organizationCount > rows.length"> · 전체 내용은 아래 조회 근거에서 확인</span>. 목표와의 차이와 계획 대비 차이는 별개입니다.</p>
    </section>

    <section v-if="brief" class="preview-report__section preview-report__analysis" aria-label="근거를 연결한 분석">
      <div class="preview-report__section-heading"><h3><span>02</span> 근거를 연결한 분석</h3><span class="preview-report__source-count">{{ brief.findings.length }}개 분석 항목</span></div>
      <p class="preview-report__caption">사실·해석·검토 가설을 구분했습니다. 해석과 가설은 근거를 바탕으로 한 검토 의견입니다.</p>
      <ol v-if="brief.findings.length" class="preview-report__findings">
        <li v-for="finding in brief.findings" :key="finding.id">
          <PTag :value="finding.kindLabel" :severity="findingSeverity(finding.kind)" />
          <p>{{ finding.text }}</p>
          <p v-if="finding.uncertainty" class="preview-report__uncertainty"><strong>판단의 한계</strong> {{ finding.uncertainty }}</p>
          <details class="preview-report__finding-sources"><summary>연결된 근거 {{ finding.citations.length }}건</summary>
            <PButton v-for="(citation, index) in finding.citations" :key="`${finding.id}-${index}`" text size="small" @click="openSource(citation.observationId)"><span>{{ citation.label }} · {{ citation.field }}<strong>{{ citation.displayQuote }}</strong></span><i class="pi pi-external-link" aria-hidden="true" /></PButton>
          </details>
        </li>
      </ol>
      <p v-else class="preview-report__caption">현재 자료로 근거를 연결한 결론을 만들지 못했습니다. 아래의 추가 확인 질문을 검토해 주세요.</p>
    </section>

    <section class="preview-report__section preview-report__evidence">
      <div class="preview-report__section-heading"><h3><span>{{ brief ? '03' : '02' }}</span> 판단을 뒷받침하는 자료</h3><span class="preview-report__source-count">{{ sources.length ? `${sources.length}개 조회 출처` : '조회 전' }}</span></div>
      <p class="preview-report__caption">{{ result.explanationCount == null ? '승인 소명은 추가 확인이 필요합니다.' : `승인 소명 ${result.explanationCount}건 조회 · 개선 효과의 검증이나 추진 승인을 뜻하지 않습니다.` }}</p>
      <div v-if="sources.length" class="preview-report__sources"><PButton v-for="source in sources" :key="source.id" :label="source.label" icon="pi pi-external-link" text size="small" @click="openSource(source.id)" /></div>
      <p v-else class="preview-report__source-placeholder">확인한 출처와 원문으로 연결됩니다.</p><details v-if="result.warnings?.length" class="preview-report__conditions"><summary>검토 조건 {{ result.warnings.length }}건</summary><ul><li v-for="warning in result.warnings" :key="warning">{{ warning }}</li></ul></details>
    </section>

    <section class="preview-report__next">
      <div class="preview-report__section-heading"><h3><span>{{ brief ? '04' : '03' }}</span> 요청 항목과 다음 확인</h3><span v-if="brief" class="preview-report__source-count">{{ brief.answeredCount }} / {{ brief.requirements.length }}개 답변</span></div>
      <ol v-if="brief" class="preview-report__requirements">
        <li v-for="requirement in brief.requirements" :key="requirement.id">
          <div><strong>{{ requirement.question }}</strong><PTag :value="requirement.statusLabel" :severity="requirement.status === 'ANSWERED' ? 'secondary' : 'warn'" /></div>
          <template v-if="requirement.status !== 'ANSWERED'"><p>{{ requirement.missingInformation }}</p><p class="preview-report__question"><i class="pi pi-question-circle" aria-hidden="true" />{{ requirement.followUpQuestion }}</p></template>
        </li>
      </ol>
      <p v-else class="preview-report__caption">{{ ['READY', 'PARTIAL'].includes(result.state) ? '이번 결과에는 질문별 분석 설명이 없습니다. 위 조회 원문을 확인하거나 필요한 내용을 질문해 주세요.' : '조회 후 답변된 항목과 추가로 확인할 질문을 함께 정리합니다.' }}</p>
    </section>

    <footer class="preview-report__footer"><p>{{ result.context?.masterLabel || baselineLabel || '기준 미선택' }}<span v-if="result.capturedAt"> · 조회 {{ workDateText(result.capturedAt) }}</span></p><p><template v-if="goal">목표 비교에는 개선 효과·보호 제약을 적용하지 않았습니다. </template>조회와 검토 의견이며 실적 달성이나 추진 승인 기록이 아닙니다.</p><span>P&amp;L AGENT</span></footer>
  </article>
</template>

<style scoped>
/* 공통 업무 화면의 밀도 규칙이 보고서의 제목 위계와 근거 링크 간격을 덮지 않도록 범위를 한정한다. */
.preview-report{--report-ink:#172e46;--report-muted:#6a7d92;--report-line:#dce5ed;--report-blue:#28629b;--report-green:#19785f;--report-red:#ae5148;position:relative;color:var(--report-ink);background:#fff;border:1px solid #d5e0e9;border-top:5px solid #19324a;border-radius:8px;padding:24px 32px 18px;box-shadow:0 10px 30px #18344d07;min-width:0;overflow:hidden}.preview-report__header{display:flex;justify-content:space-between;gap:20px;align-items:flex-start;padding-bottom:17px;border-bottom:1px solid var(--report-line)}.preview-report__eyebrow{color:var(--report-blue);font-size:9px;font-weight:750;letter-spacing:.16em;margin:0 0 7px!important}.preview-report__title{font-size:25px!important;line-height:1.35!important;font-weight:750;letter-spacing:-.055em;margin:0 0 7px!important}.preview-report__basis{font-size:10px;color:var(--report-muted);line-height:1.7;overflow-wrap:anywhere}.preview-report__status{display:flex;flex-direction:column;align-items:flex-end;gap:12px;white-space:nowrap}.preview-report__status>span:last-child{font-size:8px;color:var(--report-muted);letter-spacing:.09em}.preview-report__status :deep(.p-tag){font-size:9px;color:#566b81;background:#f0f4f7;border-radius:4px;padding:4px 7px;font-weight:550}.preview-report__judgment{padding:17px 0 16px}.preview-report__section-label{font-size:10px;font-weight:650;color:var(--report-blue);margin:0 0 7px!important}article.preview-report .preview-report__judgment h3{font-size:20px;line-height:1.5;letter-spacing:-.045em;margin:0 0 7px;font-weight:750}.preview-report__judgment>p:not(.preview-report__section-label){font-size:11px;color:var(--report-muted);line-height:1.8}.preview-report__judgment.has-surplus h3{color:var(--report-green)}.preview-report__judgment .preview-report__partial{font-size:10px!important;color:#97713c!important;margin-top:9px!important;display:flex;align-items:center;gap:7px}.preview-report__metrics{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));padding:16px 0;border-top:1px solid var(--report-line);border-bottom:1px solid var(--report-line);margin-bottom:18px}.preview-report__metrics>div{padding:0 20px;border-left:1px solid var(--report-line);min-width:0}.preview-report__metrics>div:first-child{padding-left:0;border-left:0}.preview-report__metrics>div:last-child{padding-right:0}.preview-report__metrics>div>span{font-size:10px;color:var(--report-muted);font-weight:650}.preview-report__metrics strong{display:block;font-size:clamp(22px,2.1vw,32px);font-weight:750;letter-spacing:-.05em;line-height:1.5;margin:7px 0 5px;font-variant-numeric:tabular-nums;overflow-wrap:anywhere}.preview-report__metrics strong.long-number{font-size:20px;line-height:1.6;letter-spacing:-.035em}.preview-report__metrics strong>small{display:block;font-size:9px;line-height:1.4;margin:2px 0 0;letter-spacing:0;white-space:nowrap;color:var(--report-muted);font-weight:500}.preview-report__metrics p{font-size:9px;color:var(--report-muted);line-height:1.6}.is-favorable{color:var(--report-green)}.is-unfavorable{color:var(--report-red)}.is-neutral{color:var(--report-muted)}.preview-report__section{padding:0 0 16px;margin-bottom:16px;border-bottom:1px solid var(--report-line)}.preview-report__section-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}article.preview-report .preview-report__section-heading h3{font-size:14px;letter-spacing:-.025em;line-height:1.5;font-weight:700;margin:0}.preview-report__section-heading h3>span{display:inline-block;color:var(--report-blue);font-size:10px;letter-spacing:.05em;margin-right:10px}.preview-report__unit,.preview-report__source-count{color:var(--report-muted);font-size:9px;white-space:nowrap}.preview-report__caption{font-size:10px;color:var(--report-muted);line-height:1.7;margin:7px 0 10px!important}.preview-report__table{max-width:100%;margin-top:6px}.preview-report__table :deep(.p-datatable-table-container){overflow-x:auto}.preview-report__table :deep(.p-datatable-table){min-width:420px!important;font-variant-numeric:tabular-nums}.preview-report__table :deep(th){background:#f0f4f7!important;color:#62768c!important;font-size:10px!important;padding:10px 12px!important;font-weight:650;border:0!important}.preview-report__table :deep(td){font-size:11px!important;padding:11px 12px!important;line-height:1.6!important;border:0!important;border-bottom:1px solid var(--report-line)!important;vertical-align:middle!important;overflow-wrap:anywhere}.preview-report__table :deep(tbody tr:last-child td){border-bottom:0!important}.preview-report__table :deep(.report-amount){text-align:right}.preview-report__table :deep(th.report-amount .p-datatable-column-header-content){justify-content:flex-end}.preview-report__table :deep(.report-direction){font-size:10px!important;white-space:nowrap}.organization-name{font-weight:600;line-height:1.6;display:block}.preview-report__table-note{font-size:9px;color:var(--report-muted);line-height:1.7;margin:11px 0 0!important}.preview-report__empty-line{display:flex;align-items:center;gap:12px;padding:22px 0;color:#8292a4;font-size:11px;border-top:1px solid #edf1f5;border-bottom:1px solid #edf1f5}.preview-report__empty-line>i{font-size:20px;color:#9cafc1}.preview-report__sources{display:flex;align-items:center;flex-wrap:wrap;gap:3px 15px}article.preview-report .preview-report__sources :deep(.p-button){min-height:24px;font-size:10px;padding:3px 0;color:var(--report-blue);font-weight:500;box-shadow:none}.preview-report__sources :deep(.p-button-icon){font-size:9px}.preview-report__conditions{font-size:9px;color:var(--report-muted);line-height:1.8;margin-top:10px}.preview-report__conditions summary{cursor:pointer}.preview-report__conditions ul{margin:8px 0 0;padding-left:16px}.preview-report__source-placeholder{font-size:10px;color:#92a0af;padding:3px 0}.preview-report__next{padding-bottom:16px}.preview-report__next-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:20px;margin-top:11px}.preview-report__next-grid>div{min-width:0}.preview-report__next-grid strong{font-size:11px;font-weight:650}.preview-report__next-grid p{font-size:10px;line-height:1.8;color:var(--report-muted);margin-top:7px!important;word-break:keep-all;overflow-wrap:anywhere}.preview-report__footer{position:relative;border-top:2px solid #203a53;padding-top:10px;padding-right:70px;color:var(--report-muted)}.preview-report__footer p{font-size:8px;line-height:1.8;overflow-wrap:anywhere}.preview-report__footer>span{position:absolute;right:0;bottom:2px;font-size:8px;font-weight:700;letter-spacing:.05em}.preview-report__skeleton{background:linear-gradient(90deg,#edf2f6,#f7f9fb,#edf2f6);background-size:200% 100%;animation:report-pulse 1.6s ease-in-out infinite;border-radius:3px}.metric-skeleton{height:34px;width:80%;margin:10px 0}.row-skeleton{height:26px;margin:12px 0}.row-skeleton:nth-child(odd){width:86%}@keyframes report-pulse{to{background-position:-200% 0}}@media(prefers-reduced-motion:reduce){.preview-report__skeleton{animation:none}}@media(max-width:1200px){.preview-report{padding:22px 24px 18px}.preview-report__metrics>div{padding:0 14px}.preview-report__metrics strong{font-size:25px}.preview-report__next-grid{gap:14px}}@media(max-width:600px){.preview-report{padding:20px 17px 17px;border-radius:6px}.preview-report__header{gap:12px}.preview-report__title{font-size:21px!important}.preview-report__eyebrow{font-size:8px;letter-spacing:.1em}.preview-report__status>span:last-child{display:none}article.preview-report .preview-report__judgment h3{font-size:18px}.preview-report__metrics{gap:0;padding:15px 0}.preview-report__metrics>div{padding:0 10px}.preview-report__metrics strong{font-size:21px}.preview-report__metrics strong.long-number{font-size:15px}.preview-report__metrics>div>span{font-size:9px}.preview-report__metrics p{font-size:8px}.preview-report__metrics strong>small{display:block;margin-left:0;font-size:8px}.preview-report__next-grid{gap:12px}.preview-report__next-grid p{font-size:9px}.preview-report__footer{padding-right:0}.preview-report__footer>span{position:static;display:block;margin-top:8px}article.preview-report .preview-report__section-heading h3{font-size:13px}}
.preview-report__judgment>p.preview-report__brief-summary{font-size:12px;color:var(--report-ink);white-space:pre-line;overflow-wrap:anywhere}.preview-report__findings,.preview-report__requirements{list-style:none;margin:0;padding:0}.preview-report__findings>li{padding:12px 0;border-bottom:1px solid #edf1f5}.preview-report__findings>li:last-child,.preview-report__requirements>li:last-child{border-bottom:0}.preview-report__findings :deep(.p-tag),.preview-report__requirements :deep(.p-tag){font-size:9px;padding:2px 6px;white-space:nowrap;flex-shrink:0}.preview-report__findings>li>p{font-size:12px;line-height:1.8;margin:8px 0 0;white-space:pre-line;overflow-wrap:anywhere}.preview-report__findings>li>p.preview-report__uncertainty{color:var(--report-muted);font-size:10px}.preview-report__uncertainty strong{font-weight:600}.preview-report__finding-sources{margin-top:8px;font-size:9px;color:var(--report-blue)}.preview-report__finding-sources summary{cursor:pointer;line-height:1.6}article.preview-report .preview-report__finding-sources :deep(.p-button){min-height:24px;display:flex;width:100%;justify-content:space-between;gap:12px;text-align:left;color:var(--report-muted);font-size:9px;padding:8px 0;border-radius:0;border-bottom:1px solid #edf1f5;box-shadow:none;align-items:flex-start}.preview-report__finding-sources strong{display:block;color:var(--report-ink);font-size:10px;font-weight:500;line-height:1.8;margin-top:3px;white-space:pre-wrap;overflow-wrap:anywhere}.preview-report__finding-sources .pi{font-size:9px;padding-top:4px;flex-shrink:0}.preview-report__requirements{margin-top:9px}.preview-report__requirements>li{padding:10px 0;border-bottom:1px solid #edf1f5}.preview-report__requirements>li>div{display:flex;align-items:flex-start;justify-content:space-between;gap:10px}.preview-report__requirements strong{font-size:11px;line-height:1.7;font-weight:600;overflow-wrap:anywhere}.preview-report__requirements p{font-size:10px;line-height:1.8;color:var(--report-muted);margin:6px 0 0;overflow-wrap:anywhere}.preview-report__requirements p.preview-report__question{display:flex;align-items:flex-start;gap:7px;color:var(--report-blue)}.preview-report__question .pi{font-size:10px;margin-top:4px;flex-shrink:0}
</style>
