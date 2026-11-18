<script setup>
import { computed, ref, watch } from 'vue'
import { usePrototypeStore } from './store.js'
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
const historyType = ref('monthly')
const historyPeriod = ref('all')
const historyStatus = ref('all')
const openHistoryId = ref(null)
const activeRequest = computed(() => store.activeOrgRequest)
const requests = computed(() => store.visibleOrgRequests.filter(item => item.status !== '제출 완료'))
const items = computed(() => store.explanationItems || [])
const selectedItem = computed(() => items.value.find(item => item.id === selectedId.value) || items.value[0])
const groups = computed(() => [...new Set(items.value.map(item => item.group))])
const missing = computed(() => items.value.filter(item => !String(store.orgReasons[item.id] || '').trim()).length)
const completed = computed(() => items.value.length - missing.value)
const historyRows = computed(() => (store.orgHistory || []).filter(item => item.org === store.actor?.orgId &&
  (item.type === historyType.value || (historyType.value === 'monthly' && item.type === 'explanation')) &&
  (historyStatus.value === 'all' || item.status === historyStatus.value) &&
  (historyPeriod.value === 'all' || String(item.submittedAt || '').startsWith(historyPeriod.value))))
const historyPeriods = computed(() => [...new Set((store.orgHistory || []).filter(item => item.org === store.actor?.orgId).map(item => String(item.submittedAt || '').slice(0, 7)).filter(Boolean))])

watch(() => props.page, () => { confirming.value = false; uploadMessage.value = ''; submissionMessage.value = '' })

function openRequest(request) {
  if (!store.activateOrgRequest(request.id)) return
  selectedId.value = ''
  submissionMessage.value = ''
  file.value = null
  emit('navigate', request.type === 'explanation' ? 'org-explanation' : 'org-upload')
}

function chooseFile(event) { selectFiles(event.target.files) }
function selectFiles(files) {
  uploadMessage.value = ''
  if (!files?.length) return
  if (files.length !== 1) { uploadMessage.value = '요청당 엑셀 파일 1개를 선택해 주세요.'; return }
  if (!/\.xlsx$/i.test(files[0].name)) { uploadMessage.value = '.xlsx 파일을 선택해 주세요.'; return }
  file.value = files[0]
}
function dropFile(event) { dragOver.value = false; selectFiles(event.dataTransfer.files) }
function validateFile() {
  uploadMessage.value = '파일 선택까지만 지원하는 프로토타입입니다. 파서 연결 전이므로 검증·제출은 진행되지 않습니다.'
}
function amount(value) { return formatMoney(String(value), 'KRW') }
function change(item) {
  const delta = item.actual - item.plan
  return `${delta > 0 ? '+' : delta < 0 ? '−' : ''}${amount(Math.abs(delta))}`
}
function rate(item) { return item.plan === 0 ? '산정 불가' : `${item.actual - item.plan >= 0 ? '+' : ''}${((item.actual - item.plan) / Math.abs(item.plan) * 100).toFixed(2)}%` }
function confirmSubmission() { if (!missing.value && items.value.length) confirming.value = true }
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
function date(value) {
  if (!value) return '—'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return String(value)
  return new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(parsed)
}
</script>

<template>
  <section class="org-panel">
    <template v-if="page === 'org-requests'">
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
          <span class="muted">.xlsx · 요청당 파일 1개</span>
          <input ref="fileInput" class="org-file-input" type="file" accept=".xlsx" aria-label="제출할 엑셀 파일" @change="chooseFile" />
          <button class="btn" @click="fileInput?.click()">{{ file ? '파일 다시 선택' : '파일 선택' }}</button>
        </div>
        <p class="org-upload-notice muted">선택한 파일은 서버에 업로드되지 않습니다. 실제 검증에는 파서 연결이 필요합니다.</p>
        <div class="org-upload-actions"><button class="btn primary" :disabled="!file" @click="validateFile">검증 시작</button></div>
        <p v-if="uploadMessage" class="org-inline-message" role="status">{{ uploadMessage }}</p>
      </section>
    </template>

    <template v-else-if="page === 'org-explanation'">
      <h1>{{ activeRequest?.title || '월간 시트 소명' }}</h1>
      <section v-if="!confirming" class="card org-card org-explanation-card">
        <h2>변경 확인·소명</h2>
        <p class="org-baseline muted">기준 제출본 <strong>v1.02 · 현재 기준</strong></p>
        <div class="org-explanation-grid">
          <aside class="org-item-list" aria-label="소명 대상 항목">
            <h3>소명 대상</h3><p class="muted">소명 {{ completed }}/{{ items.length }} 작성</p>
            <section v-for="group in groups" :key="group" class="org-item-group">
              <div class="org-group-title"><span>− {{ group }}</span><span>{{ items.filter(item => item.group === group).length }}개 셀</span></div>
              <button v-for="item in items.filter(item => item.group === group)" :key="item.id" class="org-explanation-item" :class="{ selected: selectedItem?.id === item.id }" :aria-pressed="selectedItem?.id === item.id" @click="selectedId = item.id">
                <span class="org-item-heading"><strong>{{ item.name }}</strong><span class="badge" :class="store.orgReasons[item.id]?.trim() ? 'approved' : 'review'">{{ store.orgReasons[item.id]?.trim() ? '작성 완료' : '미작성' }}</span></span>
                <span class="muted">손익제출!{{ item.cell || item.id }}</span>
                <span class="muted">{{ amount(item.plan) }} → {{ amount(item.actual) }} ({{ rate(item) }})</span>
              </button>
            </section>
          </aside>
          <article v-if="selectedItem" class="org-comparison-card">
            <div class="org-selected-heading"><div><p class="muted">{{ selectedItem.group }} › {{ selectedItem.name }}</p><h3>{{ selectedItem.name }}</h3></div><span class="muted">금액 단위 원</span></div>
            <div class="org-table-scroll"><table class="org-comparison"><thead><tr><th scope="col">원본 셀</th><th scope="col">기준 제출값<small>v1.02</small></th><th scope="col">이번 제출값<small>v1.03 · 미확정</small></th><th scope="col">기준 대비 차이</th></tr></thead><tbody><tr><td>손익제출!{{ selectedItem.cell || selectedItem.id }}</td><td>{{ amount(selectedItem.plan) }}</td><td>{{ amount(selectedItem.actual) }}</td><td class="org-value-change">{{ change(selectedItem) }}<small>{{ rate(selectedItem) }}</small></td></tr></tbody></table></div>
            <label class="org-reason-label" :for="`reason-${selectedItem.id}`">이번 소명</label>
            <textarea :id="`reason-${selectedItem.id}`" v-model="store.orgReasons[selectedItem.id]" rows="4" placeholder="차이가 난 이유와 금액을 반영한 근거를 적어 주세요." />
          </article>
          <p v-else class="muted org-empty">소명할 항목이 없습니다.</p>
        </div>
        <div class="org-explanation-footer"><p v-if="missing" class="org-restriction">미작성 소명 {{ missing }}개를 작성하면 제출 내용을 확인할 수 있습니다.</p><span v-else class="muted">모든 소명을 작성했습니다.</span><div><button class="btn" @click="emit('navigate', 'org-upload')">파일 다시 선택</button><button class="btn primary" :disabled="missing > 0 || !items.length" @click="confirmSubmission">제출 내용 확인</button></div></div>
      </section>
      <section v-else class="card org-card">
        <h2>제출 내용 확인</h2><p class="muted">소명 회신 예시입니다. 파일과 숫자는 바뀌지 않습니다.</p>
        <dl class="org-review-summary"><dt>대상 요청</dt><dd>{{ activeRequest?.title || '월간 시트 소명' }}</dd><dt>작성한 소명</dt><dd>{{ completed }}개 항목</dd><dt>기준 버전</dt><dd>v1.02 · 현재 기준</dd></dl>
        <div v-for="item in items" :key="item.id" class="org-review-reason"><strong>{{ item.name }}</strong><p>{{ store.orgReasons[item.id] }}</p></div>
        <div class="org-upload-actions"><button class="btn primary" @click="submitReasons">제출하기</button></div>
        <p v-if="submissionMessage" class="org-inline-message" role="alert">{{ submissionMessage }}</p>
      </section>
    </template>

    <template v-else-if="page === 'org-history'">
      <h1>제출 이력 관리</h1>
      <section class="card org-card">
        <div class="org-section-heading"><h2>제출본</h2><span class="muted">조회 {{ historyRows.length }}건</span></div>
        <div class="org-history-tabs" role="tablist" aria-label="제출 구분"><button role="tab" :aria-selected="historyType === 'monthly'" :class="{ active: historyType === 'monthly' }" @click="historyType = 'monthly'">월간 실적·추정</button><button role="tab" :aria-selected="historyType === 'annual'" :class="{ active: historyType === 'annual' }" @click="historyType = 'annual'">연간 계획</button></div>
        <div class="org-history-filters"><label>대상 연월<select v-model="historyPeriod"><option value="all">전체 연월</option><option v-for="period in historyPeriods" :key="period" :value="period">{{ period }}</option></select></label><label>처리 상태<select v-model="historyStatus"><option value="all">전체 상태</option><option v-for="status in [...new Set((store.orgHistory || []).map(item => item.status))]" :key="status" :value="status">{{ status }}</option></select></label><button class="org-link-button" @click="resetHistory">초기화</button></div>
        <div class="org-table-scroll"><table class="org-history-table"><thead><tr><th scope="col">제출 요청</th><th scope="col">제출일</th><th scope="col">상태</th><th scope="col">상세</th></tr></thead><tbody><template v-for="entry in historyRows" :key="entry.id"><tr><td>{{ entry.title }}</td><td>{{ date(entry.submittedAt) }}</td><td><span class="badge" :class="statusClass(entry.status)">{{ entry.status }}</span></td><td><button class="org-link-button" :aria-expanded="openHistoryId === entry.id" @click="openHistoryId = openHistoryId === entry.id ? null : entry.id">보기</button></td></tr><tr v-if="openHistoryId === entry.id"><td colspan="4" class="org-history-detail"><strong>{{ entry.title }}</strong><p>{{ entry.fileName ? `첨부 파일: ${entry.fileName}` : '소명 회신 · 기존 파일과 숫자 유지' }}</p><p class="muted">{{ date(entry.submittedAt) }} · {{ entry.status }} · 프로토타입 이력</p><div v-for="item in entry.items || []" :key="item.id" class="org-review-reason"><strong>{{ item.name }}</strong><p>{{ item.reason }}</p></div></td></tr></template></tbody></table></div>
        <p v-if="!historyRows.length" class="muted org-empty">선택한 조건의 제출 이력이 없습니다.</p>
      </section>
    </template>
  </section>
</template>
