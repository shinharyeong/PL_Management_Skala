<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import PButton from 'primevue/button'
import PMessage from 'primevue/message'
import PTag from 'primevue/tag'
import { workDateText, workflowRequest } from './decision-workflow-api.js'
const props = defineProps({ analysis: { type: Object, required: true }, api: { type: Function, required: true }, disabled: Boolean })
const emit = defineEmits(['request'])
const busy = ref(false), error = ref(''), research = ref(null)
let generation = 0
/** 현재 분석 ID를 감시 값으로 반환한다.
 * Args: 없음. Returns: 분석 ID. Raises: 없음. Side Effects: 없음.
 */
function analysisId() { return props.analysis.id }
/** 저장된 조사 근거와 가설을 읽는다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음; 오류를 표시한다.
 * Side Effects: 서버 조회와 조사 화면 변경.
 */
async function loadResearch() {
  const current = ++generation; research.value = null; error.value = ''; busy.value = true
  try { const response = await workflowRequest(props.api, 'research', { analysisId: props.analysis.id }); if (current === generation) research.value = response }
  catch (cause) { if (current === generation) error.value = cause.message }
  finally { if (current === generation) busy.value = false }
}
/** 고정 기준의 손익과 승인 소명을 조회해 검토할 가설을 만든다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음; 오류를 표시한다.
 * Side Effects: 조사 API 호출과 근거·가설 저장. 후보 금액은 만들지 않는다.
 */
async function discover() {
  if (busy.value || props.disabled) return
  const current = ++generation; busy.value = true; error.value = ''
  try { const response = await workflowRequest(props.api, 'discover', { analysisId: props.analysis.id, body: { expectedRevision: props.analysis.revision } }); if (current === generation) research.value = response }
  catch (cause) { if (current === generation) error.value = cause.message }
  finally { if (current === generation) busy.value = false }
}
/** 가설을 선택해 조직 검토 요청 작성으로 넘긴다.
 * Args: hypothesis 근거 연결 가설. Returns: 없음. Raises: 없음.
 * Side Effects: 요청 작성 이벤트만 발생; 서버 발송은 하지 않는다.
 */
function selectHypothesis(hypothesis) { emit('request', { ...hypothesis, kind: 'hypothesis' }) }
/** 고정한 조직 ID를 이름으로 표시한다.
 * Args: id 조직 ID. Returns: 조직명. Raises: 없음. Side Effects: 없음.
 */
function organization(id) { for (const item of props.analysis.snapshot.organizations) if (item.id === id) return item.name; return '조직 확인 필요' }
/** 화면 해제 후 이전 응답을 반영하지 않도록 한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 응답 세대 무효화.
 */
function dispose() { generation++ }
watch(analysisId, loadResearch, { immediate: true })
onBeforeUnmount(dispose)
</script>
<template><section class="evidence-workbench"><div class="workbench-heading"><div><h3>확정 자료에서 검토할 기회 찾기</h3><p>고정한 조직 손익과 승인된 소명에서 확인할 질문을 제안합니다.</p></div><PButton :label="research ? '근거 다시 조사' : '근거 조회하고 후보 제안'" icon="pi pi-sparkles" :loading="busy" :disabled="disabled || busy" @click="discover" /></div><PMessage v-if="error" severity="error" :closable="false">{{ error }}</PMessage><PMessage v-if="disabled" severity="warn" :closable="false">후보 변경을 먼저 저장한 뒤 조사와 조직 검토 요청을 진행해 주세요.</PMessage><div v-if="!research && !busy" class="research-empty">조사를 시작하면 사용한 근거와 검토 가설이 여기에 남습니다. 미확인 개선 금액은 생성하지 않습니다.</div><template v-if="research"><div class="research-meta"><PTag :value="research.discovery?.mode === 'MODEL' ? 'AI 근거 제안' : '규칙 기반 근거 제안'" severity="info" /><span>분석 v{{ research.revision }} · 근거 조회 {{ workDateText(research.capturedAt) }}</span></div><div v-if="research.discovery?.steps?.length" class="research-steps"><span v-for="step in research.discovery.steps" :key="step.key">{{ step.label }} · {{ step.status === 'COMPLETED' ? '완료' : step.status === 'SKIPPED' ? '생략' : step.status }}</span></div><PMessage severity="info" :closable="false">아래 항목은 검토 가설입니다. 효과 금액과 반영 월은 조직 회신으로 확인하며, 선택만으로 전망에 반영되지 않습니다.</PMessage><div class="hypotheses"><article v-for="hypothesis in research.discovery?.hypotheses || []" :key="hypothesis.id"><div><PTag value="효과 미정 · 검토 가설" severity="warn" /><span>{{ organization(hypothesis.organizationId) }}</span></div><h4>{{ hypothesis.title }}</h4><p>{{ hypothesis.rationale }}</p><blockquote v-if="hypothesis.sourceQuote">{{ hypothesis.sourceQuote }}</blockquote><ul><li v-for="question in hypothesis.questions || []" :key="question">{{ question }}</li></ul><small>근거 {{ hypothesis.evidenceRefs.join(' · ') }}</small><PButton label="선택하고 조직 확인 요청 작성" icon="pi pi-arrow-right" outlined size="small" :disabled="disabled || busy" @click="selectHypothesis(hypothesis)" /></article></div><p v-if="!research.discovery?.hypotheses?.length" class="research-empty">현재 확인한 근거로 제안할 가설이 없습니다. 조직과 과제를 직접 입력하거나 추가 근거를 확인해 주세요.</p><section class="source-library"><h4>조사에 사용한 원본 근거</h4><article v-for="evidence in research.evidence || []" :key="evidence.refId"><div><strong>{{ evidence.label }}</strong><PTag :value="evidence.kind === 'PNL' ? '확정 조직 손익' : '승인 소명'" severity="secondary" /></div><p>{{ evidence.text }}</p><small>{{ evidence.refId }} · {{ organization(evidence.organizationId) }}</small></article><p v-if="!research.evidence?.length">조회된 근거가 없습니다.</p></section><PMessage v-for="limitation in research.discovery?.limitations || []" :key="limitation" severity="warn" :closable="false">{{ limitation }}</PMessage></template></section></template>
<style scoped>.workbench-heading{display:flex;justify-content:space-between;align-items:start;gap:18px;margin-bottom:18px}.workbench-heading h3{font-size:17px;margin:0 0 8px}.workbench-heading p,.research-meta,.source-library p{font-size:12px;color:#7a7a76;line-height:1.7;margin:0}.research-empty{padding:26px;background:#f9f9f8;border:1px dashed #e3e3e3;font-size:13px;color:#7a7a76;line-height:1.8;border-radius:6px}.research-meta{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.research-steps{display:flex;flex-wrap:wrap;gap:9px;margin-top:12px;font-size:11px;color:#7a7a76}.hypotheses{display:grid;gap:14px;margin-top:18px}.hypotheses article{border:1px solid #e3e3e3;border-left:3px solid #5c5c59;border-radius:5px;padding:18px}.hypotheses article>div{display:flex;justify-content:space-between;align-items:center;gap:12px;font-size:12px;color:#7a7a76}.hypotheses h4{font-size:16px;margin:14px 0 10px}.hypotheses p,.hypotheses li{font-size:13px;line-height:1.8;white-space:pre-line}.hypotheses blockquote{margin:12px 0;padding:12px 15px;background:#f8f8f8;color:#7a7a76;font-size:12px;line-height:1.8;white-space:pre-line}.hypotheses ul{padding-left:18px}.hypotheses small{display:block;font-size:11px;color:#7a7a76;margin-bottom:14px;overflow-wrap:anywhere}.source-library{margin-top:25px}.source-library h4{font-size:15px}.source-library article{padding:16px 0;border-bottom:1px solid #e3e3e3}.source-library article>div{display:flex;justify-content:space-between;gap:10px;font-size:13px}.source-library p{margin:12px 0;white-space:pre-line;overflow-wrap:anywhere}.source-library small{font-size:11px;color:#7a7a76}.evidence-workbench :deep(.p-message){margin:14px 0}.evidence-workbench :deep(.p-message-text){font-size:12px;line-height:1.8}.evidence-workbench :deep(.p-button){font-size:12px}.evidence-workbench :deep(.p-tag){font-size:10px}@media(max-width:760px){.workbench-heading{flex-direction:column}}</style>
