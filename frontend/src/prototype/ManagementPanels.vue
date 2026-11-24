<script>
import { reactive } from 'vue'

// Prototype-only state. This never creates a backend request or an approved master.
const annualDemo = reactive({
  requested: false,
  deadline: '2026-11-25T18:00',
  rows: [
    { id: 'F1000', sourceCode: 'F20', name: '미디어사업부', person: '박미디 매니저', email: 'media@sktelecom.com', status: '제출 완료', reply: true, accepted: false },
    { id: 'D100', sourceCode: 'F10', name: '네트워크인프라부문', person: '최인프 매니저', email: 'infra@sktelecom.com', status: '제출 완료', reply: true, accepted: false },
    { id: 'F1300', sourceCode: 'F30', name: '플랫폼사업부', person: '이플랫 매니저', email: 'platform@sktelecom.com', status: '기한 경과', reply: false, accepted: false },
    { id: 'F1400', sourceCode: 'F40', name: 'B2B사업부', person: '정비투 매니저', email: 'b2b@sktelecom.com', status: '회신 대기', reply: false, accepted: false },
  ],
})
</script>

<script setup>
import { computed, ref, watch } from 'vue'
import { usePrototypeStore } from './store.js'
import { seoulDeadline } from './domain.js'
import './management.css'

const props = defineProps({ page: { type: String, required: true } })
const emit = defineEmits(['navigate'])
const store = usePrototypeStore()
const notificationType = ref('전체')
const unreadOnly = ref(false)
const expandedOrganization = ref(null)
watch([() => props.page, () => store.activeOrg], ([page, sourceCode]) => {
  if (page === 'annual') expandedOrganization.value = annualDemo.rows.find(row => row.sourceCode === sourceCode)?.id || null
}, { immediate: true })
const comments = ref({})
const formError = ref('')
const feedback = ref('')
const recipients = ref(annualDemo.rows.map(row => row.id))
const deadline = ref(annualDemo.deadline)
const requestMessage = ref('2027년 연간 계획을 작성해 제출해 주세요. 매출·비용의 주요 가정과 산출 근거를 함께 작성해 주세요.')
const orgSearch = ref('')
const selectedOrganization = ref('미디어사업부')
const selectedMonth = ref(10)
const selectedYear = ref('2026')
const selectedVersion = ref('ver10.01')
const reportFile = ref(null)
const reportError = ref('')
const hideNumbers = ref(true)
const templateSearch = ref('')
const selectedTemplate = ref('월 마감 실적 보고 (연결)')
const reportMode = ref('start')
const templateName = ref('월 마감 실적 보고')
const reportMonth = ref('2026-11')
const blocks = ref([])
const selectedBlock = ref(0)
const templates = ref([
  { name: '월 마감 실적 보고 (연결)', count: 6, hint: '기준 월 · 전년 대비 · 추정 대비 · 소명 분류 v3 · 지난달 사용' },
  { name: '사업부별 계획 대비 요약', count: 4, hint: '기준 월 · 계획 대비 · 최근 12개월 추이' },
  { name: '분기 경영회의 자료', count: 9, hint: '기준 월 · 블록별 기준 월 따로 지정 2개' },
])
const labels = { notifications: '알림 모아보기', annual: '연간 계획 관리', 'annual-request': '연간 계획 요청 작성', organizations: '조직별 현황', reports: '보고서 생성' }
const typeName = n => ({ submission: '조직 제출', submitted: '조직 제출', reply: '소명 회신', explanation: '소명 회신', overdue: '기한 경과' }[n.type] || n.type)
const tone = label => /기한 경과|반려/.test(label) ? 'red' : /회신|진행중/.test(label) ? 'purple' : /완료|수락|확정/.test(label) ? 'blue' : 'gray'
const notifications = computed(() => store.notifications || [])
const filteredNotifications = computed(() => notifications.value.filter(n => (notificationType.value === '전체' || typeName(n) === notificationType.value) && (!unreadOnly.value || !n.read)))
const typeCount = type => type === '전체' ? notifications.value.length : notifications.value.filter(n => typeName(n) === type).length
const notificationGroups = computed(() => {
  const groups = new Map()
  for (const [i, n] of filteredNotifications.value.entries()) {
    const date = n.day || n.date || (notifications.value.indexOf(n) < 4 ? '오늘 · 11.18 (수)' : '어제 · 11.17 (화)')
    if (!groups.has(date)) groups.set(date, [])
    groups.get(date).push({ ...n, itemIndex: i })
  }
  return [...groups].map(([date, items]) => ({ date, items, unread: items.filter(n => !n.read).length }))
})
const allRecipients = computed({ get: () => recipients.value.length === annualDemo.rows.length, set: value => { recipients.value = value ? annualDemo.rows.map(row => row.id) : [] } })
const acceptedCount = computed(() => annualDemo.rows.filter(row => row.accepted).length)
const allAccepted = computed(() => annualDemo.rows.every(row => row.accepted))
const archiveOrganizations = ['MNO사업부', '미디어사업부', 'B2B사업부', '엔터프라이즈사업부', '플랫폼사업부', 'AI사업부']
const visibleOrganizations = computed(() => archiveOrganizations.filter(name => name.includes(orgSearch.value)))
const visibleTemplates = computed(() => templates.value.filter(item => item.name.includes(templateSearch.value)))
const changes = [
  ['2026.11.10', '1', 'G1', '매출 › 제휴상품', '제휴 정산 지연분(10월)을 11월로 이연 반영함'],
  ['2026.11.10', '2', 'G2', '매출 › 신사업/기타B2B', 'B2B 신규 계약 개시가 12월로 연기됨'],
  ['2026.11.10', '3', 'A3', '영업비용 › 고객모집비용', '프로모션 단가 인하로 모집비가 계획보다 줄어듦'],
  ['2026.11.03', '1', 'A7', '영업비용 › 고객유지비용', '약정 만료 고객 리텐션 캠페인을 앞당겨 집행함'],
]

function openNotification(item) {
  store.readNotification(item.id)
  store.activeOrg = item.code || 'F10'
  emit('navigate', String(item.target || '').includes('annual') ? 'annual' : 'monthly')
}
function acceptAnnual(row) {
  row.accepted = true
  row.status = '수락 완료'
  feedback.value = `${row.name}의 예시 제출본을 수락했습니다. 실제 제출본 승인은 실행되지 않습니다.`
}
function rerequestAnnual(row) {
  if (!comments.value[row.id]?.trim()) { formError.value = '재요청하려면 경영기획 코멘트를 입력해 주세요.'; return }
  formError.value = ''
  row.status = '회신 대기'
  row.reply = false
  row.accepted = false
  feedback.value = `${row.name} 재요청 상태를 시연했습니다. 실제 메시지는 발송되지 않습니다.`
}
function sendAnnual() {
  formError.value = ''
  if (!recipients.value.length || !deadline.value || !requestMessage.value.trim()) { formError.value = '요청 대상, 회신 기한과 요청 메시지를 확인해 주세요.'; return }
  const dueAt = seoulDeadline(deadline.value)
  if (!Number.isFinite(dueAt) || dueAt <= Date.now()) { formError.value = '현재 시각 이후의 회신 기한을 한국 시간(KST)으로 선택해 주세요.'; return }
  annualDemo.requested = true
  annualDemo.deadline = deadline.value
  feedback.value = `${recipients.value.length}개 조직의 연간 계획 요청을 시연합니다. 실제 발송은 하지 않습니다.`
  emit('navigate', 'annual')
}
function attachmentNotice() { feedback.value = '디자인 예시 첨부파일입니다. 원본 파일을 연결하면 내려받을 수 있습니다.' }
function choosePdf(event) {
  const files = Array.from(event.target?.files || event.dataTransfer?.files || [])
  const file = files?.[0]
  if (event.target?.type === 'file') event.target.value = ''
  reportError.value = ''
  if (!file) return
  reportFile.value = null
  if (files.length !== 1) { reportError.value = 'PDF 파일 1개를 선택해 주세요.'; return }
  if (file.type !== 'application/pdf' && !file.name.toLowerCase().endsWith('.pdf')) { reportError.value = 'PDF 파일을 선택해 주세요.'; return }
  if (!file.size || file.size > 50 * 1024 * 1024) { reportError.value = '비어 있지 않은 50MB 이하의 PDF 파일을 선택해 주세요.'; return }
  reportFile.value = file
}
function analyzePdf() { reportError.value = 'PDF 분석 서비스가 연결되지 않았습니다. 파일은 외부로 전송되지 않았으며 숫자 가림도 아직 실행되지 않았습니다. 자료 틀 직접 만들기를 이용할 수 있습니다.' }
function startReport(manual = false) {
  reportError.value = ''
  reportMode.value = 'edit'
  templateName.value = manual ? '새 자료 틀' : selectedTemplate.value
  blocks.value = manual ? [] : [
    { type: '표', title: '전사 손익 요약', period: '공통 따라감', metric: '매출 · 영업비용 · 영업이익' },
    { type: '계층 목록', title: '사업부별 계획 대비', period: '공통 따라감', metric: '사업부별 손익' },
    { type: '문장', title: '손익 변동 설명', period: '공통 따라감', metric: '수락된 소명' },
  ]
}
function addBlock() { blocks.value.push({ type: '표', title: `새 블록 ${blocks.value.length + 1}`, period: '공통 따라감', metric: '' }); selectedBlock.value = blocks.value.length - 1 }
function moveBlock(index, direction) { const to = index + direction; if (to < 0 || to >= blocks.value.length) return; [blocks.value[index], blocks.value[to]] = [blocks.value[to], blocks.value[index]]; selectedBlock.value = to }
function saveTemplate() {
  if (!templateName.value.trim() || !blocks.value.length) { reportError.value = '틀 이름과 블록을 한 개 이상 입력해 주세요.'; return }
  const old = templates.value.find(item => item.name === templateName.value)
  if (old) old.count = blocks.value.length
  else templates.value.push({ name: templateName.value, count: blocks.value.length, hint: '이번 세션에 저장한 자료 틀 · 서버 저장 미연결' })
  reportError.value = ''
  feedback.value = '이 브라우저 세션에 자료 틀을 저장했습니다. 서버 버전 저장은 연결되지 않았습니다.'
}
</script>

<template>
  <section class="management-panels">
    <p class="mg-breadcrumb">경영기획팀 / {{ labels[page] }}</p>
    <div class="mg-heading"><h1>{{ labels[page] }}</h1><button v-if="page === 'notifications'" class="btn" :disabled="!store.unread" @click="store.markAllRead()">모두 읽음 처리</button></div>
    <p v-if="feedback" class="mg-feedback" role="status">{{ feedback }}<button aria-label="안내 닫기" @click="feedback = ''">×</button></p>

    <template v-if="page === 'notifications'">
      <p class="mg-description">조직 제출, 소명 회신, 기한 경과 알림을 확인합니다.</p>
      <div class="mg-stat-grid">
        <article class="card mg-stat"><span>안 읽은 알림</span><strong>{{ store.unread }}건</strong><small class="muted">받은 알림 중 안 읽은 알림</small></article>
        <article class="card mg-stat"><span class="mg-amber">회신 검토 대기</span><strong>{{ typeCount('소명 회신') }}건</strong><small class="muted">읽음 여부와 별도로 검토할 회신</small></article>
        <article class="card mg-stat"><span class="mg-red">기한 경과 조직</span><strong class="mg-red">{{ typeCount('기한 경과') }}건</strong><small class="muted">회신 기한이 지난 조직</small></article>
      </div>
      <div class="mg-filter-row"><div class="mg-tabs"><button v-for="type in ['전체', '조직 제출', '소명 회신', '기한 경과']" :key="type" :class="['btn', notificationType === type ? 'primary' : '', 'mg-type-' + tone(type)]" @click="notificationType = type">{{ type }} {{ typeCount(type) }}</button></div><label class="mg-checkbox"><input v-model="unreadOnly" type="checkbox">안 읽은 알림만</label></div>
      <div v-for="group in notificationGroups" :key="group.date" class="mg-notification-group">
        <h2>{{ group.date }} {{ group.items.length }}건 · 안 읽음 {{ group.unread }}</h2>
        <article v-for="item in group.items" :key="item.id" class="card mg-notification" :class="{ 'mg-read': item.read }">
          <div class="mg-notification-body"><div class="mg-notification-meta"><img src="/assets/prototype-unread-dot.svg" alt="" width="8" height="8" class="mg-unread-dot" :class="{ hidden: item.read }"><span :class="'mg-' + tone(typeName(item))">{{ typeName(item) }}</span><span class="muted">{{ item.read ? '읽음' : '안 읽음' }}</span></div><h3 :class="{ 'mg-red': typeName(item) === '기한 경과' }">{{ item.title }}</h3><p>{{ item.org }} · {{ item.code }}</p><small class="muted">{{ item.author || '회신 대기' }}</small></div>
          <div class="mg-notification-action"><small :class="typeName(item) === '기한 경과' ? 'mg-red' : 'muted'">{{ typeName(item) === '기한 경과' ? '' : '수신 ' }}{{ item.time }}</small><button class="btn" :class="{ primary: typeName(item) !== '기한 경과' }" @click="openNotification(item)">{{ typeName(item) === '기한 경과' ? '현황 보기' : '검토하기' }}</button></div>
        </article>
      </div>
      <p v-if="!filteredNotifications.length" class="card mg-empty">조건에 맞는 알림이 없습니다.</p>
    </template>

    <template v-else-if="page === 'annual'">
      <p class="mg-description">진행 중인 요청과 조직별 제출·회신·수락 상태를 확인합니다.</p>
      <div class="mg-section-heading"><h2>진행 중인 요청</h2><button class="btn primary" @click="emit('navigate', 'annual-request')">연간 계획 요청</button></div>
      <div class="card mg-annual-progress"><h2>조직별 진행 현황</h2><p class="mg-small-note">2027년 연간 계획 · 디자인 예시 · 수락 {{ acceptedCount }}/{{ annualDemo.rows.length }}</p>
        <div class="mg-table-scroll"><table class="mg-table"><thead><tr><th>조직명</th><th>조직코드</th><th>발송일</th><th>회신 기한</th><th>제출 상태</th></tr></thead><tbody><template v-for="row in annualDemo.rows" :key="row.id"><tr :class="{ 'mg-selected-row': expandedOrganization === row.id }"><td><button class="mg-row-link" :aria-expanded="expandedOrganization === row.id" @click="expandedOrganization = expandedOrganization === row.id ? null : row.id">{{ row.name }}</button></td><td>{{ row.id }}</td><td>2026.11.17 09:00</td><td>{{ annualDemo.deadline.replace('T', ' ') }}</td><td><span class="badge" :class="'mg-status-' + tone(row.status)">{{ row.status }}</span></td></tr><tr v-if="expandedOrganization === row.id" class="mg-expanded"><td colspan="5"><article v-if="row.reply" class="mg-reply"><h3>{{ row.person }} · {{ row.name }}</h3><small class="muted">2026.11.17 14:20 회신</small><p>2027년 연간 계획안을 제출합니다. 주요 매출 가정과 비용 산출 근거를 첨부파일에 정리했습니다.</p><button class="btn mg-file-btn" @click="attachmentNotice">연간계획.xlsx ↓</button><div class="mg-history-note"><strong>이력</strong><p>11.17 09:00 · 연간 계획 제출 요청 발송</p><p>11.17 14:20 · 최초 회신 · 연간계획.xlsx</p></div><label class="mg-field">경영기획 코멘트 (재요청 시 필수)<textarea v-model="comments[row.id]" rows="3" placeholder="회신에 대한 경영기획 코멘트를 입력하세요." :disabled="row.accepted"></textarea></label><p v-if="formError" class="mg-red" role="alert">{{ formError }}</p><div class="mg-actions"><button class="btn" :disabled="row.accepted" @click="rerequestAnnual(row)">재요청</button><button class="btn primary" :disabled="row.accepted" @click="acceptAnnual(row)">{{ row.accepted ? '수락 완료' : '수락' }}</button></div></article><p v-else class="mg-empty">아직 회신한 내용이 없습니다.</p></td></tr></template></tbody></table></div>
      </div>
      <p v-if="allAccepted" class="mg-feedback">모든 조직이 수락된 예시 상태입니다. 연간 계획 마스터 v1.00 · 실제 마스터 발행은 실행되지 않았습니다.</p>
    </template>

    <template v-else-if="page === 'annual-request'">
      <p class="mg-description">전체 조직을 기본 선택하고 회신 기한과 요청 메시지를 설정합니다.</p>
      <form class="card mg-request-form" @submit.prevent="sendAnnual"><h2>2027년 연간 계획</h2><div class="mg-table-scroll"><table class="mg-table"><thead><tr><th><input v-model="allRecipients" type="checkbox" aria-label="모든 조직 선택"></th><th>조직명</th><th>조직코드</th><th>수신 담당자</th></tr></thead><tbody><tr v-for="row in annualDemo.rows" :key="row.id"><td><input v-model="recipients" type="checkbox" :value="row.id" :aria-label="row.name + ' 선택'"></td><td>{{ row.name }}</td><td>{{ row.id }}</td><td>{{ row.person }} · {{ row.email }}</td></tr></tbody></table></div><label class="mg-field">회신 기한<input v-model="deadline" type="datetime-local" required></label><label class="mg-field">요청 메시지 (템플릿)<textarea v-model="requestMessage" rows="4" required></textarea></label><p v-if="formError" class="mg-red" role="alert">{{ formError }}</p><p class="mg-small-note">프로토타입에서는 선택·요청 흐름만 시연하며 실제 메일을 발송하지 않습니다.</p><button class="btn primary mg-request-submit" type="submit" :disabled="!recipients.length">{{ recipients.length }}개 조직에 연간 계획 요청 발송</button></form><button class="btn" @click="emit('navigate', 'annual')">연간 계획 관리로</button>
    </template>

    <template v-else-if="page === 'organizations'">
      <p class="mg-description">조직과 월을 선택해 제출 버전·변경 사유·소명 이력을 확인합니다.</p>
      <div class="mg-archive"><aside class="card mg-org-picker"><h2>조직 선택</h2><input v-model="orgSearch" type="search" placeholder="조직명·코드 검색" aria-label="조직 검색"><button v-for="org in visibleOrganizations" :key="org" :class="{ selected: selectedOrganization === org }" @click="selectedOrganization = org">{{ org }}</button></aside><div class="mg-archive-main"><div class="mg-section-heading"><h2>{{ selectedOrganization }} · {{ selectedYear }}</h2><label>연도 <select v-model="selectedYear"><option>2026</option><option>2025</option></select></label></div><p class="mg-small-note">담당 박미디 매니저 · 원본 와이어프레임 디자인 예시</p><div class="mg-month-grid"><button v-for="month in 12" :key="month" class="card" :class="{ selected: month === selectedMonth }" @click="selectedMonth = month"><strong>{{ month }}월</strong><small class="muted">최종 버전 {{ month > 10 ? '—' : 'v' + month + ([2, 4, 7, 10].includes(month) ? '.01' : '') }}</small></button></div><h2>{{ selectedMonth }}월 버전 이력</h2><div class="mg-table-scroll"><table class="mg-table"><thead><tr><th>버전명</th><th>최종 수정 일시</th><th>파일</th><th>자료 구분</th></tr></thead><tbody v-if="selectedMonth <= 10"><tr v-for="version in ['ver' + selectedMonth, 'ver' + selectedMonth + '.01']" :key="version" :class="{ 'mg-blue-row': selectedVersion === version }"><td><button class="mg-row-link" @click="selectedVersion = version">{{ version }}</button></td><td>{{ selectedMonth === 10 ? '11.12 14:20' : (selectedMonth + 1) + '.12 14:20' }}</td><td><button class="mg-icon-button" aria-label="예시 파일 안내" @click="attachmentNotice">↓</button></td><td>원본 디자인 예시</td></tr></tbody><tbody v-else><tr><td colspan="4" class="mg-empty">제출된 버전이 없습니다.</td></tr></tbody></table></div><template v-if="selectedMonth <= 10"><h3>변경 이력</h3><div class="mg-table-scroll"><table class="mg-table mg-changes-table"><thead><tr><th>변경 일자</th><th>순서</th><th>코드</th><th>변경 항목</th><th>소명 사유</th></tr></thead><tbody><tr v-for="(change, index) in changes" :key="index"><td v-for="(cell, i) in change" :key="i">{{ cell }}</td></tr></tbody></table></div></template><h2>월별 master</h2><div class="mg-table-scroll"><table class="mg-table"><thead><tr><th>대상 월</th><th>master 버전</th><th>확정일</th><th>상태</th><th>파일</th></tr></thead><tbody><tr v-for="month in [10, 9, 8, 7, 1]" :key="month"><td>{{ month }}월</td><td>master_ver{{ month }}</td><td>{{ month === 10 ? '—' : (month + 1) + '.12' }}</td><td><span :class="month === 10 ? 'muted' : 'mg-green'">{{ month === 10 ? '생성 대기' : '확정' }}</span></td><td>—</td></tr></tbody></table></div></div></div>
    </template>

    <template v-else-if="page === 'reports'">
      <div class="mg-report-stepper"><strong>AI 보고서 만들기</strong><span v-for="(step, i) in ['시작', '구성 확인', '데이터', '소명 분류', '생성·편집']" :key="step" :class="{ active: reportMode === 'start' ? i === 0 : i === 1 }">{{ i + 1 }} {{ step }}</span></div>
      <div v-if="reportMode === 'start'" class="mg-report-grid"><article class="card mg-report-card"><span class="mg-blue mg-small-note">1-1 새 틀 만들기</span><h2>보고서 PDF 업로드</h2><p class="muted">쓰고 싶은 보고서 양식을 올리면 AI가 표·문장·구조 구성을 뽑습니다. 숫자는 가져오지 않습니다.</p><label class="mg-dropzone" @dragover.prevent @drop.prevent="choosePdf"><img src="/assets/prototype-report-upload.svg" alt="" width="24" height="24"><strong>{{ reportFile ? reportFile.name : 'PDF를 끌어오거나 눌러서 선택' }}</strong><small class="muted">여러 장 가능 · 글자 PDF 권장 (스캔본은 확인할 곳이 늘어남)</small><input type="file" accept="application/pdf,.pdf" @change="choosePdf"></label><label class="mg-checkbox"><input v-model="hideNumbers" type="checkbox">AI에 보내기 전에 PDF의 숫자 가리기</label><button v-if="reportFile" class="btn primary" @click="analyzePdf">자료 틀 분석</button><button class="mg-text-button" @click="startReport(true)">자료 틀 직접 만들기 →</button></article><article class="card mg-report-card"><span class="mg-blue mg-small-note">1-2 저장한 틀 불러오기</span><h2>내 틀</h2><p class="muted">불러오면 구성 확인을 건너뛰고 데이터 선택으로 갑니다.</p><input v-model="templateSearch" type="search" placeholder="틀 이름으로 찾기" aria-label="자료 틀 검색"><button v-for="item in visibleTemplates" :key="item.name" class="mg-template" :class="{ selected: selectedTemplate === item.name }" @click="selectedTemplate = item.name"><span><strong>{{ item.name }}</strong><small class="muted">{{ item.hint }}</small></span><small class="muted">블록 {{ item.count }}개</small></button><p v-if="!visibleTemplates.length" class="muted">일치하는 틀이 없습니다.</p><div class="mg-right"><button class="btn primary" @click="startReport()">이 틀로 시작</button></div></article></div>
      <div v-else class="mg-report-editor"><div class="mg-section-heading"><input v-model="templateName" aria-label="자료 틀 이름"><div class="mg-actions"><button class="btn" @click="reportMode = 'start'">시작으로</button><button class="btn primary" @click="saveTemplate">틀 저장</button></div></div><p class="mg-small-note">자료 틀 편집 예시입니다. AI 분석·SQL 조회·보고서 내보내기는 아직 연결되지 않았습니다.</p><div class="mg-report-editor-grid"><aside class="card"><h2>블록 구성</h2><button v-for="(block, i) in blocks" :key="i" class="mg-block-selector" :class="{ selected: selectedBlock === i }" @click="selectedBlock = i"><span>{{ i + 1 }}. {{ block.title }}</span><small class="muted">{{ block.type }}</small></button><button class="btn" @click="addBlock">+ 블록 추가</button></aside><article class="card mg-block-editor"><label class="mg-field">공통 기준 월<input v-model="reportMonth" type="month"></label><template v-if="blocks[selectedBlock]"><label class="mg-field">블록 제목<input v-model="blocks[selectedBlock].title"></label><label class="mg-field">블록 종류<select v-model="blocks[selectedBlock].type"><option>표</option><option>계층 목록</option><option>문장</option><option>Gap 설명</option></select></label><label class="mg-field">날짜 관계<select v-model="blocks[selectedBlock].period"><option>공통 따라감</option><option>기준 월 +1</option><option>기준 월 -1</option><option>최근 12개월</option></select></label><label class="mg-field">지표 연결<input v-model="blocks[selectedBlock].metric" placeholder="연결할 지표 입력"></label><div class="mg-actions"><button class="btn" :disabled="selectedBlock === 0" @click="moveBlock(selectedBlock, -1)">위로</button><button class="btn" :disabled="selectedBlock === blocks.length - 1" @click="moveBlock(selectedBlock, 1)">아래로</button><button class="btn" @click="blocks.splice(selectedBlock, 1); selectedBlock = Math.max(0, selectedBlock - 1)">블록 삭제</button></div></template><p v-else class="mg-empty">블록을 추가해 자료 틀을 구성하세요.</p></article></div></div>
      <p v-if="reportError" class="mg-report-error" role="alert">{{ reportError }}</p>
    </template>
  </section>
</template>
