<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useSession } from '../stores/session'
import { dateTime, errorText } from '../lib/display'

const props = defineProps({ requestId: { type: String, default: '' } })
const emit = defineEmits(['changed'])
const session = useSession()
const policy = ref(null)
const mode = ref('GAP_ONLY')
const preview = ref(null)
const previewSignature = ref('')
const busy = ref('')
const error = ref('')
const notice = ref('')
const controller = new AbortController()
const keys = new Map()

/** 선택 요청과 후보 정책이 미리보기 입력과 같은지 판정한다.
 * Args: 없음. Returns: 유효한 미리보기 여부. Raises: 없음. Side Effects: 없음.
 */
const previewCurrent = computed(function currentPolicyPreview() { return !!preview.value && previewSignature.value === JSON.stringify([props.requestId, mode.value]) })

/** 소명 요구 정책을 업무 이름으로 표시한다.
 * Args: value 서버 정책 모드. Returns: 정책 이름. Raises: 없음. Side Effects: 없음.
 */
function policyName(value) { return ({ GAP_ONLY: 'Gap 초과 항목 중심', ALL_CHANGES: '변경 항목 전체' })[value] || '조회 전' }

/** 현재 전역 소명 정책을 조회한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(영역 표시).
 * Side Effects: GET·현재 정책과 후보 모드 갱신. 이전 영향 미리보기는 해제한다.
 */
async function load() {
  if (busy.value) return
  busy.value = 'load'; error.value = ''; preview.value = null
  try { const value = await session.request('/api/v1/review-policy/current', { signal: controller.signal }); if (!controller.signal.aborted) { policy.value = value; mode.value = value.requirementMode } }
  catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { if (!controller.signal.aborted) busy.value = '' }
}

/** 현재 요청의 저장된 변경·Gap·별도 요청으로 후보 정책의 필수 건수를 조회한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(영역 표시).
 * Side Effects: 읽기 전용 POST·영향 표시. 요청이나 후보 모드가 바뀐 뒤 도착한 응답은 적용하지 않는다.
 */
async function previewPolicy() {
  if (busy.value || !props.requestId || !policy.value) return
  const signature = JSON.stringify([props.requestId, mode.value])
  const body = JSON.stringify({ requestId: props.requestId, requirementMode: mode.value })
  busy.value = 'preview'; error.value = ''; notice.value = ''; preview.value = null
  try {
    const value = await session.request('/api/v1/review-policy/preview', { method: 'POST', body, signal: controller.signal })
    if (!controller.signal.aborted && signature === JSON.stringify([props.requestId, mode.value])) { preview.value = value; previewSignature.value = signature }
  } catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { if (!controller.signal.aborted) busy.value = '' }
}

/** 확인한 소명 정책을 최신 토큰과 멱등키로 저장한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(영역 표시).
 * Side Effects: 정책 POST·현재 정책 갱신·changed 이벤트. 제출 완료본의 고정 소명은 변경하지 않는다.
 */
async function applyPolicy() {
  if (busy.value || !policy.value || mode.value === policy.value.requirementMode) return
  const body = JSON.stringify({ expectedStateToken: policy.value.stateToken, requirementMode: mode.value })
  if (!keys.has(body)) keys.set(body, crypto.randomUUID())
  busy.value = 'apply'; error.value = ''; notice.value = ''
  try {
    const value = await session.request('/api/v1/review-policy', { method: 'POST', body, headers: { 'Idempotency-Key': keys.get(body) }, signal: controller.signal })
    if (controller.signal.aborted) return
    keys.delete(body); policy.value = value; mode.value = value.requirementMode; preview.value = null
    notice.value = '소명 정책을 저장했습니다. 작성 중인 초안은 업무 검증을 다시 실행해야 합니다.'; emit('changed')
  } catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { if (!controller.signal.aborted) busy.value = '' }
}

/** 요청 선택이 달라지면 이전 영향 건수를 숨긴다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 미리보기 해제. */
function clearPreview() { preview.value = null; previewSignature.value = '' }
/** 화면 이탈 이후 요청 응답을 폐기한다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소. */
function dispose() { controller.abort() }
watch(() => props.requestId, clearPreview)
onMounted(load)
onBeforeUnmount(dispose)
</script>

<template>
  <section class="panel policy-settings" aria-labelledby="policy-title"><div class="section-heading"><div><h2 id="policy-title">필수 소명 정책</h2><p class="muted">연간 계획과 월간 제출에 적용할 소명 범위를 선택합니다.</p></div><button class="text-button" :disabled="!!busy" @click="load">현재 정책 새로고침</button></div><p v-if="policy" class="current-policy">현재 정책 <strong>{{ policyName(policy.requirementMode) }}</strong><small v-if="policy.appliedAt">{{ dateTime(policy.appliedAt) }} · {{ policy.appliedBy?.name || '기본 정책' }}</small></p><p v-if="error" class="alert error" role="alert">{{ error }}</p><p v-if="notice" class="notice" role="status">{{ notice }}</p><form @submit.prevent="applyPolicy"><fieldset :disabled="!!busy || !policy" class="policy-options"><label><input v-model="mode" type="radio" value="GAP_ONLY" name="requirement-mode" /><span><strong>Gap 초과 항목 중심</strong><small>Gap 기준을 초과한 항목과 경영기획팀이 별도로 소명을 요청한 항목이 필수입니다.</small></span></label><label><input v-model="mode" type="radio" value="ALL_CHANGES" name="requirement-mode" /><span><strong>변경 항목 전체</strong><small>비교 기준과 달라진 모든 항목에 더해 Gap 초과·별도 요청 항목의 소명도 필수입니다. 비교본이 없는 최초 값은 변경으로 간주하지 않습니다.</small></span></label></fieldset><div class="policy-actions"><p>새 정책은 초안의 재검증에 반영됩니다. 이미 제출·승인·반려된 자료는 검증 당시 정책을 보존합니다.</p><div class="button-row"><button type="button" class="button" :disabled="!!busy || !requestId || !policy" @click="previewPolicy">이 요청의 필수 건수 미리보기</button><button class="button primary" :disabled="!!busy || !policy || mode === policy.requirementMode">{{ busy === 'apply' ? '저장 중…' : '정책 적용' }}</button></div></div></form><div v-if="previewCurrent" class="policy-preview"><strong>{{ policyName(preview.requirementMode) }} 적용 시 필수 {{ preview.requiredItemCount }}건 · {{ preview.requiredOrganizationCount }}개 조직</strong><dl><div><dt>변경 항목</dt><dd>{{ preview.changedItemCount }}건</dd></div><div><dt>Gap 초과</dt><dd>{{ preview.gapExceededItemCount }}건</dd></div><div><dt>별도 소명 요청</dt><dd>{{ preview.explicitlyRequestedItemCount }}건</dd></div><div><dt>제외 항목</dt><dd>{{ preview.excludedItemCount }}건</dd></div></dl><p>조직별 최신 제출 완료본 {{ preview.sourceSubmissionIds?.length || 0 }}건의 저장된 비교 결과로 계산했습니다. 각 유형은 겹칠 수 있으며 필수 건수는 중복 없이 집계합니다. 위 Gap 임계값 입력 후보를 함께 적용한 결과는 아닙니다.</p><small>{{ dateTime(preview.evaluatedAt) }} 기준 · 미리보기는 기존 제출본을 변경하지 않습니다.</small></div></section>
</template>

<style scoped>
.policy-settings{display:grid;gap:14px}.policy-settings h2{font-size:15px}.policy-settings .muted{font-size:12px;margin-top:6px}.current-policy{display:flex;gap:10px;align-items:center;flex-wrap:wrap;font-size:13px}.current-policy small{color:var(--muted);font-size:11px}.policy-options{display:grid;grid-template-columns:1fr 1fr;gap:12px;border:0;padding:0;margin:0}.policy-options label{display:flex;align-items:flex-start;gap:10px;padding:14px;border:1px solid var(--line);border-radius:8px;background:#fcfbf9}.policy-options input{width:16px;min-height:16px;margin:2px 0 0;flex-shrink:0}.policy-options strong{font-size:13px}.policy-options small{display:block;line-height:1.7;font-size:12px;color:var(--muted);margin-top:7px}.policy-actions{display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap;margin-top:14px}.policy-actions p,.policy-preview p{font-size:12px;color:var(--muted);line-height:1.7}.policy-preview{padding:16px;border:1px solid #d9cebb;border-radius:8px;background:#faf6ee;font-size:13px}.policy-preview dl{display:flex;flex-wrap:wrap;gap:20px;margin:12px 0}.policy-preview dl>div{display:flex;gap:8px}.policy-preview dd{margin:0;font-weight:600}.policy-preview small{display:block;color:var(--muted);margin-top:7px}@media(max-width:760px){.policy-options{grid-template-columns:1fr}}
</style>
