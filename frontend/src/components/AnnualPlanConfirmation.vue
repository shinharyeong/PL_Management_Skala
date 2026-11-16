<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import { useSession } from '../stores/session'
import { dateTime, errorText } from '../lib/display'
import { formatMoney } from '../lib/decimal-display'

const props = defineProps({ year: { type: Number, required: true }, organizations: { type: Array, required: true } })
const session = useSession()
const plan = ref(null)
const contingency = ref('')
const acknowledged = ref(false)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const missing = ref(false)
const message = ref('')
let controller = new AbortController()
let generation = 0
const keys = new Map()

/** 선택 연도의 실제 계획 상태와 고정 후보를 읽는다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: 이전 조회 취소·GET·입력 초기화; 계획 미존재와 조회 실패를 구분한다.
 */
async function load() {
  controller.abort(); controller = new AbortController()
  const current = ++generation
  plan.value = null; contingency.value = ''; acknowledged.value = false; error.value = ''; missing.value = false; message.value = ''; loading.value = true
  if (!Number.isInteger(props.year) || props.year < 2000 || props.year > 9999) { loading.value = false; return }
  try {
    const result = await session.request(`/api/v1/annual-plans/${props.year}`, { signal: controller.signal })
    if (current === generation) plan.value = result
  } catch (cause) {
    if (current === generation && cause.name !== 'AbortError') {
      if (cause.status === 404 || cause.code === 'RESOURCE_NOT_FOUND') missing.value = true
      else error.value = errorText(cause)
    }
  } finally { if (current === generation) loading.value = false }
}

/** 명시적으로 입력한 Contingency와 최신 후보 토큰으로 최초 계획 확정을 요청한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: confirm POST·결과 갱신; 빈 값·동의 없는 확정은 전송하지 않으며 같은 재시도 키를 유지한다.
 */
async function confirm() {
  if (saving.value || loading.value || !acknowledged.value || !plan.value || plan.value.status === 'CONFIRMED') return
  if (!/^\d+(\.\d{1,6})?$/.test(contingency.value.trim())) { error.value = 'Contingency를 0 이상의 원 단위 숫자로 직접 입력해 주세요. 소수는 6자리까지 허용합니다.'; return }
  const payload = JSON.stringify({ expectedStateToken: plan.value.stateToken, contingencyAmount: contingency.value.trim() })
  const current = generation
  if (!keys.has(payload)) keys.set(payload, crypto.randomUUID())
  saving.value = true; error.value = ''; message.value = ''
  try {
    const result = await session.request(`/api/v1/annual-plans/${props.year}/confirm`, { method: 'POST', headers: { 'Idempotency-Key': keys.get(payload) }, body: payload, signal: controller.signal })
    if (current !== generation || controller.signal.aborted) return
    plan.value = result; keys.delete(payload); message.value = '조직별 계획본과 Contingency를 확정했습니다. 이제 월간 요청을 생성할 수 있습니다.'
  } catch (cause) { if (current === generation && cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { saving.value = false }
}

/** 후보 조직 ID를 현재 조회한 조직명으로 표시한다.
 * Args: id 조직 ID. Returns: 이름/조직 ID. Raises: 없음. Side Effects: 없음.
 */
function organizationName(id) { for (const item of props.organizations) if (String(item.id) === String(id)) return item.name; return `조직 ${id}` }

/** 계획 연도 반응형 입력을 watch에 제공한다.
 * Args: 없음. Returns: 연도. Raises: 없음. Side Effects: 없음.
 */
function selectedYear() { return props.year }

/** 이탈 시 읽기와 확정 응답 반영을 중단한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소·세대 무효화.
 */
function dispose() { generation++; controller.abort() }
watch(selectedYear, load, { immediate: true })
onBeforeUnmount(dispose)
</script>

<template>
  <section class="panel confirmation-panel" aria-labelledby="confirm-plan-title">
    <div class="section-heading"><div><h2 id="confirm-plan-title">{{ year }}년 연간 계획 확정</h2><p class="muted">전 조직 승인과 연간 마스터 발행 이후, 월간 비교에 사용할 계획본을 고정합니다.</p></div><button class="button" :disabled="loading || saving" @click="load">다시 조회</button></div>
    <p v-if="loading" class="empty compact" role="status">계획 상태를 불러오는 중…</p><p v-if="missing" class="notice">이 연도의 연간 계획 요청이 없습니다. 아래에서 요청을 먼저 생성해 주세요.</p><div v-if="error" class="alert error" role="alert">{{ error }}</div><p v-if="message" class="notice" role="status">{{ message }}</p>
    <template v-if="plan"><div class="metrics"><div><span>계획 상태</span><strong>{{ plan.status === 'CONFIRMED' ? '확정' : '수집 중' }}</strong></div><div><span>승인 조직</span><strong>{{ plan.approvedCount }}/{{ plan.targetCount }}</strong></div><div><span>Contingency · 원</span><strong>{{ formatMoney(plan.contingencyAmount) }}</strong></div></div>
      <p v-if="plan.status === 'CONFIRMED'" class="notice">{{ plan.confirmedBy?.name }} · {{ dateTime(plan.confirmedAt) }} 확정. 계획본과 Contingency는 고정되며 월별로 임의 배분하거나 손익에서 자동 차감하지 않습니다.</p>
      <form v-else @submit.prevent="confirm"><label>전사 연간 Contingency · 원<input v-model="contingency" inputmode="decimal" :disabled="saving" required placeholder="사용할 금액을 직접 입력하세요. 미사용은 0" /></label><label class="confirm-ack"><input v-model="acknowledged" type="checkbox" :disabled="saving" required /><span>현재 승인된 전 조직 계획본과 입력한 Contingency를 고정하고 연간 요청을 마감하는 것을 확인했습니다.</span></label><button class="button primary" :disabled="saving || loading || !acknowledged || !contingency.trim() || plan.approvedCount !== plan.targetCount || !plan.targetCount">{{ saving ? '확정 중…' : '연간 계획 확정' }}</button><p v-if="plan.approvedCount !== plan.targetCount" class="muted">전 조직의 최신 제출본을 승인한 뒤 확정할 수 있습니다.</p></form>
      <details><summary>조직별 {{ plan.status === 'CONFIRMED' ? '확정 계획본' : '승인 후보' }} 확인</summary><div class="table-scroll"><table><thead><tr><th>조직</th><th>상태</th><th>계획본</th></tr></thead><tbody><tr v-for="item in plan.sources" :key="item.organizationId"><th scope="row">{{ organizationName(item.organizationId) }}</th><td>{{ item.latestStatus }}</td><td><RouterLink v-if="item.confirmedPlanSubmissionId || item.candidateSubmissionId" :to="`/submissions/${item.confirmedPlanSubmissionId || item.candidateSubmissionId}`" class="text-button">제출본 확인 →</RouterLink><span v-else>승인 후보 없음</span></td></tr></tbody></table></div></details>
    </template>
  </section>
</template>

<style scoped>
.confirmation-panel { margin-top:24px; }.confirmation-panel form { margin:22px 0; max-width:650px; }.confirm-ack { display:flex; align-items:flex-start; gap:10px; margin:16px 0; font-size:12px; line-height:1.8; }.confirm-ack input { width:16px; min-height:16px; margin:4px 0 0; flex:none; }.confirmation-panel details { margin-top:20px; font-size:12px; }.confirmation-panel summary { cursor:pointer; }.confirmation-panel table { margin-top:12px; }
</style>
