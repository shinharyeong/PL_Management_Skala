<script setup>
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useSession } from '../stores/session'
import { errorText, query } from '../lib/display'
import { formatMoney } from '../lib/decimal-display'
const props = defineProps({ submission: { type: Object, required: true }, modelValue: { type: Array, default: () => [] }, editable: Boolean, disabled: Boolean })
const emit = defineEmits(['update:modelValue'])
const session = useSession()
const reviews = ref([])
const catalog = ref([])
const loading = ref(false)
const error = ref('')
const draft = reactive({ reviewItemId: '', reportItemId: '', month: props.submission.month || 1, scenario: props.submission.type === 'ANNUAL_PLAN' ? 'PLAN' : 'ACTUAL', basis: 'PLAN', sheet: '', cell: '', reason: '' })
const controller = new AbortController()

/** 반려 지정을 위한 현재 검토 항목과 보고 항목을 모두 읽는다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: GET; 조회는 반려를 실행하지 않음.
 */
async function load() {
  if (!props.editable) return
  loading.value = true; error.value = ''
  try { [reviews.value, catalog.value] = await Promise.all([pages('/api/v1/review-items', { submissionId: props.submission.id }), pages('/api/v1/report-items', { active: true })]) }
  catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { loading.value = false }
}

/** 지정 목록의 모든 페이지를 읽는다.
 * Args: path 경로, params 필터. Returns: 항목 목록. Raises: API 오류. Side Effects: GET.
 */
async function pages(path, params) { const values = []; let cursor = null; do { const result = await session.request(path + query({ ...params, limit: 100, cursor }), { signal: controller.signal }); values.push(...result.items); cursor = result.nextCursor } while (cursor && !controller.signal.aborted); return values }

/** 금액을 임의 입력하지 않고 반려 대상·사유만 부모의 명령 초안에 추가한다.
 * Args: 없음. Returns: 없음. Raises: 없음(입력 오류 표시). Side Effects: update:modelValue 이벤트.
 */
function add() {
  error.value = ''
  if (!draft.reason.trim()) { error.value = '이 항목을 수정해야 하는 사유를 입력해 주세요.'; return }
  if (!draft.reviewItemId && !draft.reportItemId) { error.value = '검토 항목 또는 보고 항목을 선택해 주세요.'; return }
  if (!!draft.sheet.trim() !== !!draft.cell.trim()) { error.value = '원본 위치를 지정하려면 시트명과 셀 주소를 함께 입력해 주세요.'; return }
  if (props.modelValue.length >= 100) { error.value = '반려 항목은 최대 100개까지 지정할 수 있습니다.'; return }
  const item = { reason: draft.reason.trim(), basis: draft.basis }
  if (draft.reviewItemId) item.reviewItemId = draft.reviewItemId
  else Object.assign(item, { reportItemId: draft.reportItemId, year: props.submission.year, month: Number(draft.month), scenario: draft.scenario })
  if (draft.sheet.trim()) item.source = { sheet: draft.sheet.trim(), cell: draft.cell.trim().toUpperCase() }
  emit('update:modelValue', [...props.modelValue, item])
  draft.reason = ''; draft.sheet = ''; draft.cell = ''
}

/** 명령 초안에서 한 반려 대상을 제거한다.
 * Args: index 항목 위치. Returns: 없음. Raises: 없음. Side Effects: update:modelValue 이벤트.
 */
function remove(index) { emit('update:modelValue', props.modelValue.filter((item, position) => position !== index)) }

/** 저장 전 선택 또는 서버 스냅샷의 항목·기간 이름을 만든다.
 * Args: item 반려 DTO. Returns: 표시 문자열. Raises: 없음. Side Effects: 없음.
 */
function label(item) { const review = reviews.value.find(value => value.id === item.reviewItemId); const report = item.reportItem || review?.reportItem || catalog.value.find(value => value.id === item.reportItemId); return `${report?.name || '선택 항목'} · ${item.month || review?.month}월 ${{ PLAN: '계획', ACTUAL: '실적', ESTIMATE: '추정' }[item.scenario || review?.scenario] || ''}` }

/** 화면을 떠날 때 조회를 취소한다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소. */
function dispose() { controller.abort() }
onMounted(load)
onBeforeUnmount(dispose)
</script>

<template>
  <section v-if="editable || submission.rejectionItems?.length" class="rejection-items"><h3>반려 항목{{ editable ? ' 지정 (선택)' : '' }}</h3><template v-if="editable"><p class="muted">수정할 항목과 원본 위치를 지정하면 담당자의 재제출 화면에 표시됩니다. 기준값·제출값은 서버의 제출 기록으로 보존합니다.</p><p v-if="loading" role="status">검토 항목을 불러오는 중…</p><div class="rejection-fields"><label>검토 항목<select v-model="draft.reviewItemId" :disabled="loading || disabled"><option value="">보고 항목 직접 선택</option><option v-for="review in reviews" :key="review.id" :value="review.id">{{ review.reportItem.name }} · {{ review.month }}월 {{ review.scenario }}</option></select></label><template v-if="!draft.reviewItemId"><label>보고 항목<select v-model="draft.reportItemId" :disabled="disabled"><option value="">선택하세요</option><option v-for="item in catalog" :key="item.id" :value="item.id">{{ item.name }}</option></select></label><label>대상 월<select v-model="draft.month" :disabled="disabled"><option v-for="month in 12" :key="month" :value="month">{{ month }}월</option></select></label><label>구분<select v-model="draft.scenario" :disabled="disabled"><option v-if="submission.type === 'ANNUAL_PLAN'" value="PLAN">계획</option><template v-else><option value="ACTUAL">실적</option><option value="ESTIMATE">추정</option></template></select></label></template><label>비교 기준<select v-model="draft.basis" :disabled="disabled"><option value="PLAN">연간 계획</option><option value="PREVIOUS">직전 제출본</option></select></label><label>원본 시트 (선택)<input v-model="draft.sheet" :disabled="disabled" maxlength="100" /></label><label>원본 셀 (선택)<input v-model="draft.cell" :disabled="disabled" placeholder="N40" maxlength="12" /></label></div><label>항목별 수정 사유<textarea v-model="draft.reason" :disabled="disabled" rows="2" maxlength="2000"></textarea></label><p v-if="error" class="alert error" role="alert">{{ error }}</p><button type="button" class="button" :disabled="disabled || loading" @click="add">반려 항목 추가</button><ul class="rejection-drafts"><li v-for="(item, index) in modelValue" :key="index"><strong>{{ label(item) }}</strong><span v-if="item.source">{{ item.source.sheet }}!{{ item.source.cell }}</span><p>{{ item.reason }}</p><button type="button" class="text-button" :disabled="disabled" @click="remove(index)">지정 취소</button></li></ul></template><div v-else class="table-scroll"><table><caption class="visually-hidden">반려 당시 항목별 비교값과 수정 사유, 단위 원</caption><thead><tr><th>항목 · 대상 월</th><th>원본 위치</th><th class="number">항목 기준합계 (원)</th><th class="number">항목 제출합계 (원)</th><th>수정 요청</th></tr></thead><tbody><tr v-for="item in submission.rejectionItems" :key="item.id"><th>{{ label(item) }}</th><td>{{ item.source ? `${item.source.sheet}!${item.source.cell}` : '항목 전체' }}<small v-if="item.source" class="source-amount">선택 셀 {{ item.sourceAmount == null ? '누락 · 비교 불가' : formatMoney(item.sourceAmount) + '원' }}</small></td><td class="number">{{ item.baselineAmount == null ? '비교 불가' : formatMoney(item.baselineAmount) }}</td><td class="number">{{ item.currentAmount == null ? '누락 · 비교 불가' : formatMoney(item.currentAmount) }}</td><td class="preserve-lines">{{ item.reason }}</td></tr></tbody></table></div></section>
</template>

<style scoped>
.rejection-items{display:grid;gap:12px;margin:18px 0;padding:18px;border:1px solid var(--line);border-radius:8px}.rejection-fields{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.rejection-items h3{font-size:14px}.rejection-items .muted{font-size:12px;line-height:1.7}.rejection-drafts{list-style:none;padding:0;display:grid;gap:10px}.rejection-drafts li{border-bottom:1px solid var(--line);padding-bottom:10px;font-size:12px}.rejection-drafts span{display:block;color:var(--muted);margin-top:4px}.rejection-drafts p{white-space:pre-wrap;margin:7px 0}.rejection-items th,.rejection-items td{font-size:12px}.preserve-lines{white-space:pre-wrap}.source-amount{display:block;margin-top:5px;color:var(--muted)}@media(max-width:700px){.rejection-fields{grid-template-columns:1fr}}
</style>
