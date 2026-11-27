<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import PButton from 'primevue/button'
import PMessage from 'primevue/message'
import PTag from 'primevue/tag'
import ReportDataTable from '../components/ReportDataTable.vue'
import ReportChart from '../components/ReportChart.vue'
import { reportCell, reportRows } from './report-api.js'
import { artifactExpired, fetchReportArtifact, reportToolMoney, saveReportBlob } from './decision-report-tools.js'
import { workDateText } from './decision-workflow-api.js'

const props = defineProps({ result: Object, api: Function, scopeKey: { type: String, default: '' } })
const emit = defineEmits(['source'])
const downloads = reactive({}), now = ref(Date.now())
let generation = 0, timer = null
const controllers = new Map()
/** 표시할 실제 보고서·소명 결과가 있는지 확인한다.
 * Args: 없음. Returns: 표시 여부. Raises: 없음. Side Effects: 없음.
 */
function hasResults() { return Boolean(props.result && (props.result.invalidCount || ['templates', 'previews', 'artifacts', 'explanations'].some(key => props.result[key]?.length))) }
const visible = computed(hasResults)
/** 현재 결과와 연결된 출처를 요청한다.
 * Args: observationId 실제 관찰 ID. Returns: 없음. Raises: 없음. Side Effects: source 이벤트만 발생한다.
 */
function source(observationId) { emit('source', observationId) }
/** 기준·실행이 바뀌면 진행 요청과 표시 상태를 비운다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 요청 취소·메모리 상태 초기화.
 */
function resetDownloads() { generation++; for (const controller of controllers.values()) controller.abort(); controllers.clear(); for (const key of Object.keys(downloads)) delete downloads[key] }
/** 성공 산출물의 인증 파일을 사용자 클릭에 따라 내려받는다.
 * Args: artifact 현재 실행에서 검증된 산출물. Returns: Promise<void>.
 * Raises: 없음; 만료·HTTP·중단 오류는 화면 상태로 처리한다. Side Effects: 인증 GET·브라우저 파일 다운로드. 업무 데이터 쓰기 없음.
 */
async function download(artifact) {
  if (!props.api || downloads[artifact.artifactId]?.busy) return
  const current = generation, controller = new AbortController()
  controllers.set(artifact.artifactId, controller)
  downloads[artifact.artifactId] = { busy: true, error: '', done: false }
  try {
    const blob = await fetchReportArtifact(props.api, artifact, controller.signal)
    if (current !== generation || controller.signal.aborted) return
    saveReportBlob(blob, artifact.filename)
    downloads[artifact.artifactId] = { busy: false, error: '', done: true }
  } catch (cause) {
    if (current === generation) downloads[artifact.artifactId] = { busy: false, done: false,
      error: cause.name === 'AbortError' ? '다운로드를 중단했습니다.' : [404, 410].includes(cause.status) ? '보고서 다운로드가 만료됐거나 파일을 찾을 수 없습니다. 다시 생성해 주세요.' : cause.message || '보고서 다운로드에 실패했습니다.' }
  } finally { if (controllers.get(artifact.artifactId) === controller) controllers.delete(artifact.artifactId) }
}
/** 화면에 있는 파일의 만료 표시 시각을 갱신한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 메모리 시각 갱신만 수행한다.
 */
function updateNow() { now.value = Date.now() }
/** 표시한 다운로드의 만료 상태를 주기적으로 갱신한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 화면 생존 기간의 타이머만 시작한다.
 */
function mount() { updateNow(); timer = setInterval(updateNow, 1000) }
/** 화면이 사라질 때 네트워크 요청·타이머를 정리한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 진행 요청 취소·타이머 해제.
 */
function dispose() { resetDownloads(); if (timer != null) clearInterval(timer) }
/** 자료 틀 블록의 표·차트·본문 존재 여부를 확인한다.
 * Args: block 채운 블록. Returns: 표시 내용 유무. Raises: 없음. Side Effects: 없음.
 */
function hasBlockContent(block) { return Boolean(block.text || reportRows(block).length || block.cards?.length || block.items?.length || (block.kind === 'chart' && block.rows?.length)) }
/** 결측 사유가 문자열이나 배열인 기존 응답을 평문으로 표시한다.
 * Args: block 채운 블록. Returns: 결측 사유 평문. Raises: 없음. Side Effects: HTML 해석 없음.
 */
function missingReasons(block) { const reasons = block.why || block.reasons; return Array.isArray(reasons) ? reasons.map(reportCell).join(' · ') : reportCell(reasons ?? '') }
/** 원본 차이 분류 합계를 기존 표 렌더러의 행으로 전달한다.
 * Args: overview 소명 현황. Returns: 표 계약. Raises: 없음. Side Effects: 금액을 합산하거나 순위를 만들지 않는다.
 */
function categoryTable(overview) { return { table: { columns: ['분류', '분류된 Gap 합계'], rows: overview.categoryTotals.map(item => [item.name + (item.subtotal ? ' (상위 합계)' : ''), reportToolMoney(item.gapAmountWon)]) } } }
/** 수락 소명이 없는 항목을 정확한 이름으로 표에 전달한다.
 * Args: overview 소명 현황. Returns: 표 계약. Raises: 없음. Side Effects: 미회신·지연 상태를 추측하지 않는다.
 */
function reviewingTable(overview) { return { table: { columns: ['조직', '항목', 'Gap'], rows: overview.reviewing.map(item => [item.organization.name, item.item, reportToolMoney(item.gapAmount)]) } } }
/** 서버에 있는 분류명을 표시한다.
 * Args: overview 소명 현황, id 분류 ID. Returns: 분류명 또는 미분류 안내. Raises: 없음. Side Effects: 자동 분류하지 않는다.
 */
function categoryName(overview, id) { return (overview.categories?.items || []).find(item => item.id === id)?.name || (id ? '분류 이름 확인 필요' : '미분류') }
watch(() => props.scopeKey, resetDownloads, { flush: 'sync' })
onMounted(mount)
onBeforeUnmount(dispose)
</script>

<template>
  <section v-if="visible" class="agent-report-results" aria-label="보고서와 전사 소명 조회 결과">
    <PMessage v-if="result.invalidCount" severity="warn" :closable="false">보고서·소명 결과 일부의 기준이나 형식을 확인하지 못해 표시하지 않았습니다. 해당 조회 출처를 확인해 주세요.</PMessage>
    <article v-for="artifact in result.artifacts" :key="artifact.artifactId" class="agent-report-artifact">
      <div class="artifact-icon"><i class="pi pi-file-excel" aria-hidden="true" /></div>
      <div class="artifact-copy"><strong>{{ artifact.filename }}</strong><p>확인한 자료 틀 v{{ artifact.templateVersion }} · XLSX · {{ artifact.byteSize.toLocaleString('ko-KR') }} bytes</p><small>{{ workDateText(artifact.expiresAt) }}까지 다운로드 가능 · 서버 메모리에만 보관</small><small v-if="downloads[artifact.artifactId]?.done" role="status">브라우저 다운로드를 시작했습니다.</small></div>
      <PButton :label="artifactExpired(artifact, now) ? '다운로드 만료' : 'XLSX 다운로드'" icon="pi pi-download" :disabled="!api || artifactExpired(artifact, now)" :loading="downloads[artifact.artifactId]?.busy" @click="download(artifact)" />
      <PButton label="파일 근거" text severity="secondary" size="small" @click="source(artifact.observationId)" />
      <PMessage v-if="downloads[artifact.artifactId]?.error" severity="error" :closable="false">{{ downloads[artifact.artifactId].error }}</PMessage>
      <small v-if="artifactExpired(artifact, now)" class="artifact-expired">같은 기준으로 보고서를 다시 생성하면 새 파일을 받을 수 있습니다.</small>
    </article>

    <article v-for="preview in result.previews" :key="preview.observationId" class="agent-filled-report">
      <header><div><p class="result-eyebrow">REPORT PREVIEW</p><h3>{{ preview.templateName }}</h3><p>{{ preview.year }}년 {{ preview.month }}월 · 자료 틀 v{{ preview.templateVersion }} · {{ preview.sourceVersion }}</p></div><PButton label="보고서 근거" text size="small" @click="source(preview.observationId)" /></header>
      <p class="result-scope">현재 기준으로 채운 보고서 미리보기입니다. 선택한 블록의 기간·단위와 결측 표시를 함께 확인하세요.</p>
      <PMessage v-if="preview.dataStatus !== 'CONFIRMED'" severity="warn" :closable="false">{{ preview.dataStatus === 'NONE' ? '채울 수 있는 자료가 없습니다.' : '잠정 자료가 포함되어 있습니다.' }}</PMessage>
      <div v-for="block in preview.blocks" :key="block.id" class="filled-report-block">
        <p v-if="block.period" class="result-scope">적용 기간 {{ block.period }}</p>
        <PMessage v-if="block.missing" severity="warn" :closable="false">일부 데이터가 없습니다. {{ missingReasons(block) }}</PMessage>
        <h4 v-if="block.kind === 'title'">{{ block.text }}</h4><p v-else-if="block.text" class="filled-report-text">{{ block.text }}</p>
        <ReportDataTable v-if="reportRows(block).length" :data="block" />
        <ReportChart v-if="block.kind === 'chart' && Array.isArray(block.rows) && !reportRows(block).length" :block="block" />
        <div v-if="Array.isArray(block.cards)" class="filled-report-cards"><div v-for="(card, index) in block.cards" :key="index"><span>{{ card.label }}</span><strong>{{ card.missing ? '데이터 없음' : card.won != null ? reportToolMoney(card.won) : card.value ?? card.text ?? '데이터 없음' }}</strong><small>{{ card.delta }}</small></div></div>
        <ul v-if="Array.isArray(block.items)"><li v-for="(item, index) in block.items" :key="index">{{ reportCell(item) }}</li></ul>
        <p v-if="!hasBlockContent(block)" class="result-scope">이 블록에 표시할 자료가 없습니다.</p>
      </div>
      <p v-if="!preview.blocks.length" class="result-scope">표시할 보고서 블록이 없습니다.</p>
    </article>

    <article v-for="overview in result.explanations" :key="overview.observationId" class="agent-explanation-overview">
      <header><div><p class="result-eyebrow">EXPLANATION OVERVIEW</p><h3>전사 소명 분류·검토 현황</h3><p>{{ overview.year }}년 {{ overview.month }}월 기준 · {{ overview.comparisonLabel }}</p></div><PButton label="현황 근거" text size="small" @click="source(overview.observationId)" /></header>
      <p class="result-scope">{{ overview.hasApplicablePeriod === false ? '현재 기준에 적용할 다음 추정 기간이 없습니다.' : '소명 대상: ' + overview.itemYear + '년 ' + overview.itemMonth + '월 · ' + (overview.itemScenario === 'ACTUAL' ? '실적' : '추정') }}</p>
      <div class="overview-counts"><div><span>전사 수락 소명</span><strong>{{ overview.summary.acceptedCount }}건</strong></div><div><span>수락 소명 없는 항목</span><strong>{{ overview.summary.reviewingCount }}건</strong></div><div><span>수락 소명 중 미분류</span><strong>{{ overview.summary.unclassifiedAcceptedCount }}건</strong></div></div>
      <p class="result-scope">전사 합계: Gap {{ reportToolMoney(overview.gapTotalWon) }} · 분류된 Gap {{ reportToolMoney(overview.classifiedTotalWon) }}. Gap의 단순 합계이며 영업이익 영향이나 개선 가능액이 아닙니다.</p>
      <PTag :value="overview.summary.fullyClassified ? '분류·수락 범위 확인 완료' : '분류 또는 수락 확인 필요'" :severity="overview.summary.fullyClassified ? 'secondary' : 'warn'" />
      <p v-if="!overview.matchesGapTotal" class="result-scope">분류 합계와 전체 Gap 합계가 일치하지 않습니다.</p>
      <ReportDataTable v-if="overview.categoryTotals.length" :data="categoryTable(overview)" />
      <p class="result-scope">상위 분류 합계에는 하위 항목이 포함됩니다. 분류 행을 다시 더하지 않습니다.</p>
      <p class="result-scope">{{ overview.selectedOrganizationId ? '아래 상세는 선택 조직의 조회 범위입니다. 위 분류 합계·건수는 전사 기준입니다.' : '아래 상세는 현재 조회 페이지에 포함된 조직의 자료입니다.' }} 수락 소명이 없는 항목을 모두 미회신으로 판단하지 않습니다.</p>
      <details v-if="overview.items.length" class="overview-explanations" open><summary>조회한 수락 소명 {{ overview.items.length }}건</summary><div v-for="(item, index) in overview.items" :key="item.explanationId + '-' + index"><h4>{{ item.organization.name }} · {{ item.item }}</h4><p class="result-scope">{{ reportToolMoney(item.gapAmount) }} · {{ categoryName(overview, item.categoryId) }}</p><p>{{ item.answer }}</p><small>소명 {{ item.explanationId }} · 담당자 설명이며 인과 증명·추진 승인을 뜻하지 않습니다.</small></div></details>
      <details v-if="overview.reviewing.length" class="overview-explanations"><summary>수락 소명 없는 조회 항목 {{ overview.reviewing.length }}건</summary><ReportDataTable :data="reviewingTable(overview)" /></details>
      <p v-if="!overview.items.length && !overview.reviewing.length" class="result-scope">현재 상세 조회 범위에 표시할 항목이 없습니다.</p>
      <p v-if="overview.hasMore" class="overview-more">상세 자료가 더 있습니다. 다음 페이지부터 이어서 조회하도록 요청할 수 있습니다.</p>
    </article>

    <details v-for="catalog in result.templates" :key="catalog.observationId" class="agent-template-list"><summary>조회한 보고서 자료 틀 {{ catalog.items.length }}개</summary><ul><li v-for="item in catalog.items" :key="item.templateId"><strong>{{ item.name }}</strong> · v{{ item.version }} · {{ item.blockCount }}개 블록</li></ul><p v-if="!catalog.items.length">사용할 수 있는 저장 자료 틀이 없습니다. 보고서 화면에서 자료 틀을 먼저 저장해 주세요.</p><p v-if="catalog.nextCursor">자료 틀 목록에 다음 페이지가 있습니다.</p><PButton label="자료 틀 출처" text size="small" @click="source(catalog.observationId)" /></details>
  </section>
</template>

<style scoped>
.agent-report-results{display:grid;gap:18px;margin:20px 0;color:#193047;min-width:0}.agent-report-artifact{display:flex;align-items:center;gap:14px;flex-wrap:wrap;background:#f0f7f6;border:1px solid #bcd6d0;border-radius:8px;padding:16px}.artifact-icon{font-size:26px;color:#19765f}.artifact-copy{flex:1;min-width:180px}.artifact-copy strong{font-size:13px;overflow-wrap:anywhere}.artifact-copy p,.artifact-copy small{display:block;font-size:10px;line-height:1.7;color:#526f68;margin:4px 0}.artifact-expired{flex-basis:100%;color:#94613c;font-size:11px}.agent-report-artifact>.p-message{flex-basis:100%}.agent-filled-report,.agent-explanation-overview{border:1px solid #dbe3eb;border-radius:7px;padding:19px;min-width:0}.agent-filled-report>header,.agent-explanation-overview>header{display:flex;justify-content:space-between;align-items:flex-start;gap:14px}.result-eyebrow{font-size:9px!important;font-weight:700;letter-spacing:.1em;color:#28619c;margin:0 0 7px!important}.agent-report-results h3{font-size:16px;margin:0 0 7px}.agent-report-results header p,.result-scope{font-size:10px;line-height:1.8;color:#64768c;margin:7px 0;overflow-wrap:anywhere}.filled-report-block{margin-top:15px;padding-top:12px;border-top:1px solid #edf1f5}.filled-report-block h4{font-size:17px;margin:0 0 10px}.filled-report-text{font-size:12px;line-height:1.9;white-space:pre-wrap;overflow-wrap:anywhere}.filled-report-block ul{font-size:11px;line-height:1.8;padding-left:20px}.filled-report-cards,.overview-counts{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin:15px 0}.filled-report-cards>div,.overview-counts>div{padding:12px;background:#f4f7fa;border-radius:4px;min-width:0}.filled-report-cards span,.overview-counts span{display:block;font-size:10px;color:#64768c}.filled-report-cards strong,.overview-counts strong{display:block;font-size:18px;line-height:1.8;overflow-wrap:anywhere}.filled-report-cards small{font-size:10px;color:#64768c}.overview-explanations{margin-top:14px}.overview-explanations summary,.agent-template-list summary{font-size:11px;color:#28619c;line-height:1.8;cursor:pointer}.overview-explanations>div{border-left:3px solid #a1b8ce;padding:10px 14px;margin-top:10px;background:#f5f8fb}.overview-explanations h4{font-size:12px;line-height:1.6;margin:0}.overview-explanations div>p:not(.result-scope){font-size:12px;line-height:1.9;white-space:pre-wrap;overflow-wrap:anywhere}.overview-explanations small,.overview-more{font-size:10px;color:#64768c;line-height:1.8}.overview-more{color:#28619c}.agent-template-list{border-bottom:1px solid #dbe3eb;padding:10px 0}.agent-template-list ul{padding-left:18px;font-size:11px;line-height:1.9}.agent-template-list p{font-size:11px;line-height:1.8;color:#64768c}.agent-report-results :deep(.p-button){font-size:11px}.agent-report-results :deep(.p-tag){font-size:9px}.agent-report-results :deep(.p-message-text){font-size:11px;line-height:1.8}.agent-report-results :deep(.p-datatable-table-container){max-width:100%;overflow:auto}.agent-report-results :deep(td),.agent-report-results :deep(th){font-size:11px;line-height:1.7}@media(max-width:650px){.agent-filled-report,.agent-explanation-overview{padding:13px}.agent-filled-report>header,.agent-explanation-overview>header{flex-wrap:wrap}.overview-counts,.filled-report-cards{gap:6px}.overview-counts>div,.filled-report-cards>div{padding:9px}.overview-counts strong,.filled-report-cards strong{font-size:15px}.overview-counts span{font-size:9px}.agent-report-artifact{padding:13px}}
</style>
