<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { usePrototypeStore } from './store.js'
import { differenceRate, replyKey, valuesFor } from './domain.js'
import { formatMoney } from '../lib/decimal-display.js'
import './org.css'

const props = defineProps({ page: { type: String, default: 'org-requests' } })
const emit = defineEmits(['navigate'])
const store = usePrototypeStore()
const file = ref(null)
const fileInput = ref(null)
const uploadMessage = ref('')
const submissionMessage = ref('')
const dragOver = ref(false)
const selectedId = ref('')
const confirming = ref(false)
const confirmationTitle = ref(null)
const reasonInput = ref(null)
const historyType = ref('monthly')
const historyPeriod = ref('all')
const historyStatus = ref('all')
const openHistoryId = ref(null)
const activeRequest = computed(() => store.activeOrgRequest)
const unavailableRequest = computed(() => {
  if (!['org-upload', 'org-explanation'].includes(props.page)) return false
  const request = activeRequest.value
  return !request || request.status === '제출 완료' ||
    (props.page === 'org-explanation' ? request.type !== 'explanation' : !['monthly', 'annual'].includes(request.type))
})
const requests = computed(() => store.visibleOrgRequests.filter(item => item.status !== '제출 완료'))
const items = computed(() => store.explanationItems || [])
const selectedItem = computed(() => items.value.find(item => replyKey(item) === selectedId.value) || items.value[0])
const groups = computed(() => [...new Set(items.value.map(item => item.group))])
const missing = computed(() => items.value.filter(item => !String(store.orgReasons[replyKey(item)] || '').trim()).length)
const completed = computed(() => items.value.length - missing.value)
const historyRows = computed(() => (store.orgHistory || []).filter(item => item.org === store.actor?.orgId &&
  (item.type === historyType.value || (historyType.value === 'monthly' && item.type === 'explanation')) &&
  (historyStatus.value === 'all' || item.status === historyStatus.value) &&
  (historyPeriod.value === 'all' || historyMonth(item.submittedAt) === historyPeriod.value)))
const historyPeriods = computed(() => [...new Set((store.orgHistory || []).filter(item => item.org === store.actor?.orgId).map(item => historyMonth(item.submittedAt)).filter(Boolean))])

watch(() => props.page, () => { confirming.value = false; uploadMessage.value = ''; submissionMessage.value = '' })
watch(() => activeRequest.value?.id, () => { confirming.value = false; selectedId.value = ''; file.value = null })

function openRequest(request) {
  if (!store.activateOrgRequest(request.id)) return
  selectedId.value = ''
  submissionMessage.value = ''
  file.value = null
  emit('navigate', request.type === 'explanation' ? 'org-explanation' : 'org-upload')
}

function chooseFile(event) {
  selectFiles(event.target.files)
  // File is held in component state; allow choosing the same file again after an error/removal.
  event.target.value = ''
}
function removeFile() {
  file.value = null
  if (fileInput.value) fileInput.value.value = ''
  uploadMessage.value = '선택한 파일을 제거했습니다.'
}
/** 비활성 데모의 파일 선택도 현재 제출 형식·크기 안내와 맞춘다.
 * Args: files 선택 파일 목록. Returns: 없음. Raises: 없음(화면 안내).
 * Side Effects: 메모리 선택·안내 갱신. 파일 업로드·변환은 하지 않는다.
 */
function selectFiles(files) {
  uploadMessage.value = ''
  if (!files?.length) return
  file.value = null
  if (files.length !== 1) { uploadMessage.value = '요청당 엑셀 파일 1개를 선택해 주세요.'; return }
  if (!/\.(xlsx|xlsb)$/i.test(files[0].name)) { uploadMessage.value = '.xlsx 또는 .xlsb 파일을 선택해 주세요.'; return }
  if (files[0].size === 0) { uploadMessage.value = '빈 파일은 선택할 수 없습니다. 파일 내용을 확인해 주세요.'; return }
  if (files[0].size > 20 * 1024 * 1024) { uploadMessage.value = '20MB 이하의 엑셀 파일을 선택해 주세요.'; return }
  file.value = files[0]
}
function dropFile(event) { dragOver.value = false; selectFiles(event.dataTransfer.files) }
function validateFile() {
  uploadMessage.value = '파일 선택까지만 지원하는 프로토타입입니다. 파서 연결 전이므로 검증·제출은 진행되지 않습니다.'
}
function amount(value) { return value == null ? '자료 없음' : formatMoney(String(value), 'KRW') }
function comparison(item) { return valuesFor(item, item.conditions?.type) }
function version(item, side) { return item.conditions?.[side === 'base' ? 'baseVersion' : 'compareVersion'] || '예시 제출본' }
function comparisonLabel(item) { return `${item.conditions?.type || '실적 vs 계획'} · ${version(item, 'base')} → ${version(item, 'current')}` }
function change(item) {
  const { base, current } = comparison(item)
  if (base == null || current == null) return '산정 불가'
  const delta = current - base
  return `${delta > 0 ? '+' : delta < 0 ? '−' : ''}${amount(Math.abs(delta))}`
}
function rate(item) {
  const { base, current } = comparison(item)
  const value = differenceRate(base, current)
  return value == null ? '산정 불가' : `${value >= 0 ? '+' : ''}${value.toFixed(1)}%`
}
async function confirmSubmission() {
  if (missing.value || !items.value.length) return
  confirming.value = true
  await nextTick()
  confirmationTitle.value?.focus()
}
async function editReasons() {
  confirming.value = false
  await nextTick()
  reasonInput.value?.focus()
}
function submitReasons() {
  if (missing.value || !items.value.length) return
  if (!store.submitOrgExplanations({ ...store.orgReasons })) {
    submissionMessage.value = '이 요청에는 회신할 수 없습니다. 요청함에서 현재 요청을 다시 확인해 주세요.'
    return
  }
  confirming.value = false
  emit('navigate', 'org-history')
}
function resetHistory() { historyPeriod.value = 'all'; historyStatus.value = 'all' }
function statusClass(status) { return status.includes('반려') || status.includes('재제출') ? 'recheck' : status.includes('승인') ? 'approved' : status.includes('소명') || status.includes('재검토') ? 'progress' : 'waiting' }
function historyMonth(value) {
  if (!value) return ''
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return ''
  const parts = new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit' }).formatToParts(parsed)
  return `${parts.find(part => part.type === 'year').value}-${parts.find(part => part.type === 'month').value}`
}
function date(value) {
  if (!value) return '—'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return String(value)
  return new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(parsed)
}
</script>

<template>
  <section class="org-panel">
    <template v-if="unavailableRequest">
      <h1>요청을 다시 확인해 주세요</h1>
      <section class="card org-card">
        <p role="status">이미 회신했거나 이 화면에서 처리할 수 없는 요청입니다. 요청함에서 현재 요청을 선택해 주세요.</p>
        <div class="org-upload-actions"><button class="btn primary" @click="emit('navigate', 'org-requests')">요청함으로 돌아가기</button></div>
      </section>
    </template>
    <template v-else-if="page === 'org-requests'">
      <h1>요청함</h1>
      <section class="card org-card" aria-labelledby="org-open-title">
        <div class="org-section-heading"><h2 id="org-open-title">열린 요청</h2><span class="muted">{{ requests.length }}건</span></div>
        <div v-for="request in requests" :key="request.id" class="org-request-card">
          <div><span class="badge" :class="request.type === 'explanation' ? 'progress' : 'submitted'">{{ request.type === 'explanation' ? '소명 요청' : '제출 요청' }}</span><h3>{{ request.title }}</h3></div>
          <button class="btn primary" @click="openRequest(request)">{{ request.type === 'explanation' ? '소명 시작' : '제출 시작' }}</button>
        </div>
        <p v-if="!requests.length" class="muted org-empty">열린 요청이 없습니다.</p>
      </section>
    </template>

    <template v-else-if="page === 'org-upload'">
      <h1>{{ activeRequest?.title || '엑셀 제출' }}</h1>
      <section class="card org-card">
        <h2>엑셀 파일 업로드</h2>
        <div class="org-template"><div><strong>제출 양식</strong><p class="muted">{{ activeRequest?.type === 'annual' ? 'D100_2027_연간계획.xlsx' : 'D100_2026-11.xlsx' }}</p></div><button class="btn" disabled title="양식 다운로드는 서버 연결 후 제공됩니다.">양식 다운로드</button></div>
        <div class="org-dropzone" :class="{ dragging: dragOver }" @dragover.prevent="dragOver = true" @dragleave.prevent="dragOver = false" @drop.prevent="dropFile">
          <strong>{{ file ? file.name : '엑셀 파일을 여기에 끌어다 놓으세요' }}</strong>
          <span class="muted">.xlsx / .xlsb · 요청당 파일 1개 · 최대 20MB</span>
          <input ref="fileInput" class="org-file-input" type="file" accept=".xlsx,.xlsb" aria-label="제출할 엑셀 파일" @change="chooseFile" />
          <div class="actions"><button class="btn" @click="fileInput?.click()">{{ file ? '파일 다시 선택' : '파일 선택' }}</button><button v-if="file" class="btn danger" @click="removeFile">선택한 파일 제거</button></div>
        </div>
        <p class="org-upload-notice muted">선택한 파일은 서버에 업로드되지 않습니다. 실제 검증에는 파서 연결이 필요합니다.</p>
        <div class="org-upload-actions"><button class="btn" @click="emit('navigate', 'org-requests')">요청함으로</button><button class="btn primary" :disabled="!file" @click="validateFile">검증 시작</button></div>
        <p v-if="uploadMessage" class="org-inline-message" role="status">{{ uploadMessage }}</p>
      </section>
    </template>

    <template v-else-if="page === 'org-explanation'">
      <h1>{{ activeRequest?.title || '월간 시트 소명' }}</h1>
      <section v-if="!confirming" class="card org-card org-explanation-card">
        <h2>변경 확인·소명</h2>
        <p class="org-baseline muted">요청 당시 비교 스냅샷을 확인하고 항목별 소명을 작성해 주세요. 파일과 숫자는 변경되지 않습니다.</p>
        <div class="org-explanation-grid">
          <aside class="org-item-list" aria-label="소명 대상 항목">
            <h3>소명 대상</h3><p class="muted">소명 {{ completed }}/{{ items.length }} 작성</p>
            <section v-for="group in groups" :key="group" class="org-item-group">
              <div class="org-group-title"><span>− {{ group }}</span><span>{{ items.filter(item => item.group === group).length }}개 셀</span></div>
              <button v-for="item in items.filter(item => item.group === group)" :key="replyKey(item)" class="org-explanation-item" :class="{ selected: selectedItem && replyKey(selectedItem) === replyKey(item) }" :aria-pressed="!!selectedItem && replyKey(selectedItem) === replyKey(item)" @click="selectedId = replyKey(item)">
                <span class="org-item-heading"><strong>{{ item.name }}</strong><span class="badge" :class="store.orgReasons[replyKey(item)]?.trim() ? 'approved' : 'review'">{{ store.orgReasons[replyKey(item)]?.trim() ? '작성 완료' : '미작성' }}</span></span>
                <span class="muted">손익제출!{{ item.cell || item.id }}</span>
                <span class="muted">{{ comparisonLabel(item) }}</span>
                <span class="muted">{{ amount(comparison(item).base) }} → {{ amount(comparison(item).current) }} ({{ rate(item) }})</span>
              </button>
            </section>
          </aside>
          <article v-if="selectedItem" class="org-comparison-card">
            <div class="org-selected-heading"><div><p class="muted">{{ selectedItem.group }} › {{ selectedItem.name }}</p><h3>{{ selectedItem.name }}</h3></div><span class="muted">금액 단위 원</span></div>
            <p class="muted">{{ comparisonLabel(selectedItem) }}</p>
            <div class="org-table-scroll" role="region" aria-label="선택 항목 금액 비교" tabindex="0"><table class="org-comparison"><thead><tr><th scope="col">원본 셀</th><th scope="col">기준 값<small>{{ version(selectedItem, 'base') }}</small></th><th scope="col">비교 값<small>{{ version(selectedItem, 'current') }}</small></th><th scope="col">기준 대비 차이</th></tr></thead><tbody><tr><td>손익제출!{{ selectedItem.cell || selectedItem.id }}</td><td>{{ amount(comparison(selectedItem).base) }}</td><td>{{ amount(comparison(selectedItem).current) }}</td><td class="org-value-change">{{ change(selectedItem) }}<small>{{ rate(selectedItem) }}</small></td></tr></tbody></table></div>
            <label class="org-reason-label" for="selected-reason">이번 소명</label>
            <textarea id="selected-reason" ref="reasonInput" v-model="store.orgReasons[replyKey(selectedItem)]" rows="4" placeholder="차이가 난 이유와 금액을 반영한 근거를 적어 주세요." />
          </article>
          <p v-else class="muted org-empty">소명할 항목이 없습니다.</p>
        </div>
        <div class="org-explanation-footer"><p v-if="missing" class="org-restriction">미작성 소명 {{ missing }}개를 작성하면 제출 내용을 확인할 수 있습니다.</p><span v-else class="muted">{{ items.length ? '모든 소명을 작성했습니다.' : '이 요청에는 소명할 항목이 없습니다.' }}</span><div><button class="btn" @click="emit('navigate', 'org-requests')">요청함으로</button><button class="btn primary" :disabled="missing > 0 || !items.length" @click="confirmSubmission">제출 내용 확인</button></div></div>
      </section>
      <section v-else class="card org-card">
        <h2 ref="confirmationTitle" tabindex="-1">제출 내용 확인</h2><p class="muted">소명 회신 예시입니다. 파일과 숫자는 바뀌지 않습니다.</p>
        <dl class="org-review-summary"><dt>대상 요청</dt><dd>{{ activeRequest?.title || '월간 시트 소명' }}</dd><dt>작성한 소명</dt><dd>{{ completed }}개 항목</dd><dt>비교 기준</dt><dd>각 항목의 요청 당시 비교 스냅샷</dd></dl>
        <div v-for="item in items" :key="replyKey(item)" class="org-review-reason"><strong>{{ item.name }}</strong><p class="muted">{{ comparisonLabel(item) }}</p><p>{{ store.orgReasons[replyKey(item)] }}</p></div>
        <div class="org-upload-actions"><button class="btn" @click="editReasons">소명 수정</button><button class="btn primary" @click="submitReasons">제출하기</button></div>
        <p v-if="submissionMessage" class="org-inline-message" role="alert">{{ submissionMessage }}</p>
      </section>
    </template>

    <template v-else-if="page === 'org-history'">
      <h1>제출 이력 관리</h1>
      <section class="card org-card">
        <div class="org-section-heading"><h2>제출본</h2><span class="muted">조회 {{ historyRows.length }}건</span></div>
        <div class="org-history-tabs" role="group" aria-label="제출 구분"><button :aria-pressed="historyType === 'monthly'" :class="{ active: historyType === 'monthly' }" @click="historyType = 'monthly'">월간 실적·추정</button><button :aria-pressed="historyType === 'annual'" :class="{ active: historyType === 'annual' }" @click="historyType = 'annual'">연간 계획</button></div>
        <div class="org-history-filters"><label>대상 연월<select v-model="historyPeriod"><option value="all">전체 연월</option><option v-for="period in historyPeriods" :key="period" :value="period">{{ period }}</option></select></label><label>처리 상태<select v-model="historyStatus"><option value="all">전체 상태</option><option v-for="status in [...new Set((store.orgHistory || []).map(item => item.status))]" :key="status" :value="status">{{ status }}</option></select></label><button class="org-link-button" @click="resetHistory">초기화</button></div>
        <div class="org-table-scroll" role="region" aria-label="제출 이력" tabindex="0"><table class="org-history-table"><thead><tr><th scope="col">제출 요청</th><th scope="col">제출일</th><th scope="col">상태</th><th scope="col">상세</th></tr></thead><tbody><template v-for="entry in historyRows" :key="entry.id"><tr><td>{{ entry.title }}</td><td>{{ date(entry.submittedAt) }}</td><td><span class="badge" :class="statusClass(entry.status)">{{ entry.status }}</span></td><td><button class="org-link-button" :aria-expanded="openHistoryId === entry.id" @click="openHistoryId = openHistoryId === entry.id ? null : entry.id">보기</button></td></tr><tr v-if="openHistoryId === entry.id"><td colspan="4" class="org-history-detail"><strong>{{ entry.title }}</strong><p>{{ entry.fileName ? `첨부 파일: ${entry.fileName}` : '소명 회신 · 기존 파일과 숫자 유지' }}</p><p class="muted">{{ date(entry.submittedAt) }} · {{ entry.status }} · 프로토타입 이력</p><div v-for="item in entry.items || []" :key="replyKey(item)" class="org-review-reason"><strong>{{ item.name }}</strong><p class="muted">{{ comparisonLabel(item) }} · {{ amount(comparison(item).base) }} → {{ amount(comparison(item).current) }}</p><p>{{ item.reason }}</p></div></td></tr></template></tbody></table></div>
        <p v-if="!historyRows.length" class="muted org-empty">선택한 조건의 제출 이력이 없습니다.</p>
      </section>
    </template>
  </section>
</template>
