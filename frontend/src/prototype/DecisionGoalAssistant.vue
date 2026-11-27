<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import PButton from 'primevue/button'
import PMessage from 'primevue/message'
import PTag from 'primevue/tag'
import { formatMoney } from '../lib/decimal-display.js'
import { workflowRequest } from './decision-workflow-api.js'
const props = defineProps({ api: { type: Function, required: true }, prompt: String, year: Number, disabled: Boolean })
const emit = defineEmits(['apply'])
const busy = ref(false), error = ref(''), proposal = ref(null), mode = ref('')
let generation = 0
/** 입력이 바뀌면 이전 문장에 대한 제안을 무효화한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 제안·오류 초기화.
 */
function invalidate() { generation++; proposal.value = null; error.value = '' }
/** 자연어에서 목표·연도·보호 비용의 제안을 요청한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음; 오류를 표시한다.
 * Side Effects: 인증된 제안 요청과 화면 상태 변경; 목표는 자동 변경하지 않는다.
 */
async function propose() {
  if (busy.value || !props.prompt?.trim()) return
  const current = ++generation; busy.value = true; error.value = ''; proposal.value = null
  try {
    const response = await workflowRequest(props.api, 'goal', { body: { prompt: props.prompt.trim(), year: props.year } })
    if (current === generation) { proposal.value = response.goalProposal; mode.value = response.mode }
  } catch (cause) { if (current === generation) error.value = cause.message }
  finally { busy.value = false }
}
/** 사용자가 확인한 제안만 부모 목표 폼으로 전달한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: apply 이벤트 발생.
 */
function applyProposal() { if (proposal.value) emit('apply', proposal.value) }
/** 원 단위 제안 금액을 정밀 억원 문자열로 표시한다.
 * Args: value 금액. Returns: 표시 문자열. Raises: 없음. Side Effects: 없음.
 */
function money(value) { return formatMoney(value, 'HUNDRED_MILLION') }
/** 제안된 보호 비용을 한국어로 표시한다.
 * Args: values 보호 분류 배열. Returns: 표시 문자열. Raises: 없음. Side Effects: 없음.
 */
function protectionLabels(values) { const labels = []; for (const value of values || []) labels.push(value === 'HEADCOUNT' ? '인력' : 'R&D'); return labels.join(' · ') || '제안 없음' }
/** 감시할 자연어와 기준 연도를 반환한다.
 * Args: 없음. Returns: 입력 배열. Raises: 없음. Side Effects: 없음.
 */
function inputs() { return [props.prompt, props.year] }
watch(inputs, invalidate)
onBeforeUnmount(invalidate)
</script>
<template><div class="goal-assistant"><PButton label="요청에서 목표 제안 읽기" icon="pi pi-sparkles" text size="small" :loading="busy" :disabled="disabled || busy || !prompt?.trim()" @click="propose" /><PMessage v-if="error" severity="error" :closable="false">{{ error }}</PMessage><section v-if="proposal" class="goal-proposal"><div><strong>목표 해석 제안</strong><PTag :value="mode === 'MODEL' ? 'AI 제안' : '규칙 기반 제안'" severity="info" /></div><dl><dt>영업이익 목표</dt><dd>{{ proposal.targetProfitWon == null ? '추가 확인 필요' : `${money(proposal.targetProfitWon)}억 원` }}</dd><dt>목표 연도</dt><dd>{{ proposal.targetYear || '추가 확인 필요' }}</dd><dt>보호 비용</dt><dd>{{ protectionLabels(proposal.protectedCategories) }}</dd></dl><p v-for="uncertainty in proposal.uncertainties || []" :key="uncertainty">{{ uncertainty }}</p><small>확인 후 아래 목표 입력에 반영됩니다. 분석을 만들거나 기준을 변경하지는 않습니다.</small><PButton label="확인하고 목표 입력에 반영" size="small" outlined :disabled="disabled || busy" @click="applyProposal" /></section></div></template>
<style scoped>.goal-assistant{margin:-7px 0 17px}.goal-proposal{padding:13px;border:1px solid #dcdcda;background:#f9f9f8;border-radius:6px;font-size:12px}.goal-proposal>div{display:flex;justify-content:space-between;align-items:center;gap:8px}.goal-proposal dl{display:grid;grid-template-columns:85px 1fr;gap:8px;margin:14px 0}.goal-proposal dt{color:#7a7a76}.goal-proposal dd{margin:0;font-weight:650;overflow-wrap:anywhere}.goal-proposal p,.goal-proposal small{font-size:11px;line-height:1.65;color:#7a7a76;margin:8px 0;display:block}.goal-proposal :deep(.p-tag){font-size:9px}.goal-proposal :deep(.p-button){font-size:11px}.goal-assistant :deep(.p-message-text){font-size:12px}</style>
