<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useSession } from '../stores/session'
import { dateTime, errorText, query, saveBlob, submissionStatus } from '../lib/display'

const session = useSession()
const route = useRoute()
const requestedYear = Number(route.query.year)
const requestedMonth = Number(route.query.month)
const year = ref(Number.isInteger(requestedYear) && requestedYear >= 2000 && requestedYear <= 9999 ? requestedYear : Number(new Intl.DateTimeFormat('en', { timeZone: 'Asia/Seoul', year: 'numeric' }).format(new Date())))
const month = ref(Number.isInteger(requestedMonth) && requestedMonth >= 0 && requestedMonth <= 12 ? requestedMonth : 0)
const search = ref('')
const selectedId = ref('')
const selectedSubmissionId = ref('')
const organizations = ref([])
const allSubmissions = ref([])
const periods = ref([])
const annualMaster = ref(null)
const loading = ref(false)
const organizationsLoading = ref(false)
const error = ref('')
const masterError = ref('')
const downloadBusy = ref('')
const reviewItems = ref([])
const reviewsLoading = ref(false)
const reviewError = ref('')
const includeDrafts = ref(false)
const historySubmissionId = ref('')
const lifetime = new AbortController()
let controller = new AbortController()
let reviewController = new AbortController()
const reviewCache = new Map()
let generation = 0
let reviewGeneration = 0
let initialPeriodSelected = Number.isInteger(requestedMonth) && requestedMonth >= 0 && requestedMonth <= 12

/** 실제 조직 목록을 이름·코드 검색어로 좁힌다.
 * Args: 없음. Returns: 조직 배열. Raises: 없음. Side Effects: 없음.
 */
const visibleOrganizations = computed(function filterOrganizations() {
  const result = []
  for (const item of organizations.value) if (`${item.name} ${item.code}`.includes(search.value.trim())) result.push(item)
  return result
})

/** 현재 선택한 서버 조직을 찾는다.
 * Args: 없음. Returns: 조직 또는 null. Raises: 없음. Side Effects: 없음.
 */
const selectedOrganization = computed(function findOrganization() {
  for (const item of organizations.value) if (String(item.id) === selectedId.value) return item
  return null
})

/** 선택 기간의 실제 초안·완료 제출본만 반환한다.
 * Args: 없음. Returns: 제출 목록. Raises: 없음. Side Effects: 없음.
 */
const submissions = computed(function periodSubmissions() {
  const result = []
  for (const item of allSubmissions.value) if ((includeDrafts.value || item.status !== 'DRAFT') && (month.value === 0 ? item.type === 'ANNUAL_PLAN' : item.type === 'MONTHLY_ACTUAL' && item.month === month.value)) result.push(item)
  return result
})

/** 선택기간의 제출회차에 연결된 소명을 날짜별로 묶는다. Args: 없음. Returns: 날짜·소명행 묶음. Raises: 없음. Side Effects: 없음. */
const changeGroups = computed(function groupedChanges() {
  const versions = new Map(); for (const item of submissions.value) if (item.status !== 'DRAFT') versions.set(String(item.id), item)
  const grouped = new Map()
  for (const item of reviewItems.value) {
    const submission = versions.get(String(item.submissionId)); if (!submission || (historySubmissionId.value && String(item.submissionId) !== historySubmissionId.value)) continue
    for (const explanation of item.explanations || []) {
      if (!explanation.answer) continue
      const time = explanation.answeredAt || submission.submittedAt || submission.uploadedAt
      if (!time || Number.isNaN(new Date(time).getTime())) continue
      const day = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(time))
      if (!grouped.has(day)) grouped.set(day, { day, time, rows: [] })
      grouped.get(day).rows.push({ ...item, explanation, submission })
    }
  }
  for (const group of grouped.values()) group.rows.sort(newestExplanation)
  return [...grouped.values()].sort(newestChange)
})
/** 변경 날짜를 최신순으로 정렬한다. Args: left/right 날짜묶음. Returns: 정렬순서. Raises: 없음. Side Effects: 없음. */
function newestChange(left, right) { return new Date(right.time).getTime() - new Date(left.time).getTime() }
/** 같은 날짜의 소명을 답변 시각·회차의 최신순으로 정렬한다. Args: left/right 소명행. Returns: 정렬순서. Raises: 없음. Side Effects: 없음. */
function newestExplanation(left, right) { return new Date(right.explanation.answeredAt || right.submission.submittedAt || right.submission.uploadedAt).getTime() - new Date(left.explanation.answeredAt || left.submission.submittedAt || left.submission.uploadedAt).getTime() || right.explanation.round - left.explanation.round }

/** 선택 제출본들의 검토를 페이지 조회하고 각 항목의 전체 소명 회차를 읽는다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: 이전 조회 취소·GET·소명목록 교체. 제출 요청월에 포함된 미래 추정월을 누락하지 않으며 상세 동시 조회는 4개로 제한한다.
 */
async function loadReviews() {
  reviewController.abort(); reviewController = new AbortController()
  const current = ++reviewGeneration
  reviewItems.value = []; reviewError.value = ''; reviewsLoading.value = true
  try {
    const all = []
    for (const submission of submissions.value) {
      if (submission.status === 'DRAFT' || (historySubmissionId.value && String(submission.id) !== historySubmissionId.value)) continue
      let cursor = null
      do {
        const page = await session.request(`/api/v1/review-items${query({ submissionId: submission.id, limit: 100, cursor })}`, { signal: reviewController.signal })
        if (current !== reviewGeneration) return
        all.push(...page.items); cursor = page.nextCursor
      } while (cursor)
    }
    const details = []
    for (let offset = 0; offset < all.length; offset += 4) {
      const calls = []
      for (const item of all.slice(offset, offset + 4)) calls.push(reviewDetail(item, reviewController.signal))
      const results = await Promise.allSettled(calls)
      if (current !== reviewGeneration) return
      for (const result of results) {
        if (result.status === 'fulfilled') details.push(result.value)
        else if (result.reason.name !== 'AbortError') reviewError.value = `일부 소명 회차를 불러오지 못했습니다. ${errorText(result.reason)}`
      }
      reviewItems.value = [...details]
    }
  } catch (cause) { if (current === reviewGeneration && cause.name !== 'AbortError') reviewError.value = errorText(cause) }
  finally { if (current === reviewGeneration) reviewsLoading.value = false }
}
/** 상태가 같은 상세는 메모리에서 재사용하고 바뀐 검토의 전체 소명회차만 읽는다.
 * Args: item 검토 요약, signal 취소신호. Returns: 검토 상세. Raises: HTTP 오류.
 * Side Effects: GET·화면 수명 내 캐시 갱신. 파일이나 브라우저 저장소를 생성하지 않는다.
 */
async function reviewDetail(item, signal) {
  const key = `${item.id}:${item.stateToken}`
  if (reviewCache.has(key)) return reviewCache.get(key)
  const result = await session.request(`/api/v1/review-items/${item.id}`, { signal })
  if (!signal.aborted) reviewCache.set(key, result)
  return result
}

/** 기간별 가장 높은 최종 제출 회차를 찾고 초안은 제외한다.
 * Args: period 0이면 연간 계획, 1~12이면 해당 월. Returns: 최종 제출본 또는 null.
 * Raises: 없음. Side Effects: 없음; 승인 회차와 제출 회차를 혼동하지 않는다.
 */
function latestVersion(period) {
  let result = null
  for (const item of allSubmissions.value) {
    const matches = period === 0 ? item.type === 'ANNUAL_PLAN' : item.type === 'MONTHLY_ACTUAL' && item.month === period
    if (matches && item.status !== 'DRAFT' && item.seq != null && (!result || item.seq > result.seq)) result = item
  }
  return result
}

/** 활성 사업조직 전체 페이지를 읽고 경로의 실제 조직 또는 첫 조직을 선택한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: 조직 GET·선택 연도 자료 조회; 예시 조직을 추가하지 않는다.
 */
async function initialize() {
  organizationsLoading.value = true; error.value = ''
  try {
    const all = []; let cursor = null
    do {
      const page = await session.request(`/api/v1/organizations${query({ active: true, limit: 100, cursor })}`, { signal: lifetime.signal })
      if (lifetime.signal.aborted) return
      all.push(...page.items); cursor = page.nextCursor
    } while (cursor)
    organizations.value = all
    const wanted = typeof route.query.organizationId === 'string' ? route.query.organizationId : ''
    selectedId.value = String(all[0]?.id || '')
    for (const item of all) if (String(item.id) === wanted) selectedId.value = wanted
    await loadYear()
  } catch (cause) { if (!lifetime.signal.aborted && cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { if (!lifetime.signal.aborted) organizationsLoading.value = false }
}

/** 조직·연도에 속한 모든 업로드 페이지를 읽고 월별 완료 회차와 소명 선택을 준비한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: 이전 조회 취소·GET·현재 선택 상태 변경. 과거 선택의 응답은 폐기한다.
 */
async function loadYear() {
  controller.abort(); controller = new AbortController(); reviewController.abort(); reviewCache.clear()
  const current = ++generation
  allSubmissions.value = []; selectedSubmissionId.value = ''; periods.value = []; annualMaster.value = null; reviewItems.value = []; reviewGeneration++
  loading.value = false; error.value = ''; masterError.value = ''
  if (!selectedId.value) return
  if (!Number.isInteger(year.value) || year.value < 2000 || year.value > 9999) { error.value = '대상 연도를 확인해 주세요.'; return }
  loading.value = true
  try {
    const all = []; let cursor = null
    do {
      const page = await session.request(`/api/v1/submissions${query({ organizationId: selectedId.value, year: year.value, limit: 100, cursor })}`, { signal: controller.signal })
      if (current !== generation) return
      all.push(...page.items); cursor = page.nextCursor
    } while (cursor)
    allSubmissions.value = all
    if (!initialPeriodSelected) {
      let latest = 0
      for (const item of all) if (item.type === 'MONTHLY_ACTUAL' && item.status !== 'DRAFT') latest = Math.max(latest, item.month)
      month.value = latest || 0; initialPeriodSelected = true
    }
    choosePeriod(month.value)
    await loadMasters(current)
  } catch (cause) { if (current === generation && cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { if (current === generation) loading.value = false }
}

/** 해당 연도의 실제 월간·연간 마스터 링크를 독립적으로 조회한다.
 * Args: current 현재 자료 조회 세대. Returns: Promise<void>. Raises: 없음(마스터 영역 표시).
 * Side Effects: GET·마스터 상태 변경; 마스터 오류가 업로드·소명 자료를 숨기지 않는다.
 */
async function loadMasters(current) {
  try {
    const result = await session.request(`/api/v1/master-periods?year=${year.value}`, { signal: controller.signal })
    if (current !== generation) return
    periods.value = result.items
  } catch (cause) { if (current === generation && cause.name !== 'AbortError') masterError.value = errorText(cause) }
  let annualRequestId = null
  for (const item of allSubmissions.value) if (item.type === 'ANNUAL_PLAN') { annualRequestId = item.requestId; break }
  if (current !== generation || !annualRequestId) return
  try {
    const result = await session.request(`/api/v1/submission-requests/${annualRequestId}/annual-master`, { signal: controller.signal })
    if (current === generation) annualMaster.value = result
  } catch (cause) { if (current === generation && cause.name !== 'AbortError') masterError.value = errorText(cause) }
}

/** 기간을 바꾸고 가장 최근 최종 제출본 또는 아직 제출 전인 첫 초안을 선택한다.
 * Args: value 연간 0 또는 월. Returns: 없음. Raises: 없음.
 * Side Effects: 기간·소명 대상 변경; 기존 결과를 재파싱하거나 저장하지 않는다.
 */
function choosePeriod(value) {
  month.value = value
  historySubmissionId.value = ''
  selectedSubmissionId.value = String(latestVersion(value)?.id || submissions.value[0]?.id || '')
  loadReviews()
}

/** 실제 조직을 선택하고 해당 연도의 자료를 읽는다.
 * Args: id 조직 ID. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: 선택 변경·GET.
 */
async function selectOrganization(id) { selectedId.value = String(id); await loadYear() }

/** 업로드 이력에서 전체 소명 회차와 원본 근거를 볼 대상을 선택한다.
 * Args: id 현재 기간의 제출 ID 또는 전체 조회용 빈 문자열. Returns: 없음. Raises: 없음. Side Effects: 선택 상태·GET.
 */
function selectSubmission(id) { if (id) selectedSubmissionId.value = String(id); historySubmissionId.value = String(id); loadReviews() }

/** 연도탭을 선택하고 그 연도의 이력으로 전환한다. Args: value 연도. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: 선택과 GET. */
async function selectYear(value) { year.value = value; await loadYear() }

/** 발행한 월마스터만 명시적인 클릭으로 Excel 내보낸다. Args: period 발행월. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: GET·브라우저다운로드. */
async function downloadMaster(period) {
  if (!period.latestMasterId || downloadBusy.value) return
  downloadBusy.value = `master:${period.latestMasterId}`
  try { const result = await session.request(`/api/v1/masters/${period.latestMasterId}/file`, { blob: true, signal: lifetime.signal }); if (!lifetime.signal.aborted) saveBlob(result, `${period.latestVersionLabel}.xlsx`) }
  catch (cause) { if (cause.name !== 'AbortError') masterError.value = errorText(cause) }
  finally { if (!lifetime.signal.aborted) downloadBusy.value = '' }
}

/** 선택 조직·기간을 제출 상세의 돌아가기 문맥으로 제공한다.
 * Args: item 제출 요약. Returns: 라우트 객체. Raises: 없음. Side Effects: 없음.
 */
function detailLink(item) { return { path: `/submissions/${item.id}`, query: { from: 'organizations', organizationId: selectedId.value, year: year.value, month: month.value } } }

/** 권한 있는 원본 파일만 명시적인 클릭으로 내려받는다.
 * Args: item 서버 제출 DTO. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: 원본 GET·브라우저 다운로드; 저장된 파일을 변경하지 않는다.
 */
async function download(item) {
  if (downloadBusy.value) return
  downloadBusy.value = String(item.id)
  try {
    const result = await session.request(`/api/v1/submissions/${item.id}/file`, { blob: true, signal: lifetime.signal })
    if (!lifetime.signal.aborted) saveBlob(result, item.originalFilename)
  } catch (cause) { if (!lifetime.signal.aborted && cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { if (!lifetime.signal.aborted) downloadBusy.value = '' }
}

/** 화면 이탈 이후 자료와 다운로드 응답을 폐기한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소·세대 무효화.
 */
function dispose() { generation++; reviewGeneration++; controller.abort(); reviewController.abort(); reviewCache.clear(); lifetime.abort() }
onMounted(initialize)
onBeforeUnmount(dispose)
</script>

<template>
  <div class="archive-page">
    <div class="archive-background" aria-hidden="true"><img src="/assets/planner-archive-background.svg" alt="" /></div>
    <header class="archive-heading"><h1>조직별 현황 · 아카이브</h1><div class="archive-tools"><div class="year-tabs" aria-label="조회 연도"><button v-for="value in [year - 1, year, year + 1]" :key="value" :class="{ active: value === year }" :disabled="loading" @click="selectYear(value)">{{ value }}</button></div><button class="button" :disabled="loading || organizationsLoading" @click="loadYear">새로고침</button></div></header>
    <p v-if="error" class="alert error" role="alert">{{ error }}</p>
    <div class="archive-layout">
      <div class="archive-side"><aside class="organization-tree"><input v-model="search" type="search" placeholder="조직명·코드 검색" aria-label="조직 검색" /><p>사업조직 · {{ organizations.length }}</p><button v-for="org in visibleOrganizations" :key="org.id" :class="{ selected: String(org.id) === selectedId }" @click="selectOrganization(org.id)">{{ org.name }} <small>{{ org.code }}</small></button><p v-if="!visibleOrganizations.length">{{ organizationsLoading ? '불러오는 중…' : '검색 결과가 없습니다' }}</p></aside><section class="master-card"><h2>월별 마스터</h2><p v-if="masterError" class="alert error" role="alert">{{ masterError }}</p><div class="master-rail"><template v-for="period in periods" :key="period.month"><div v-if="period.requestId || period.latestMasterId" class="master-item"><RouterLink v-if="period.latestMasterId" :to="`/planning/masters/${period.latestMasterId}`"><strong>{{ period.month }}월 · {{ period.latestVersionLabel }}</strong><span>발행 완료 · 버전 확인 →</span></RouterLink><RouterLink v-else :to="{ path: '/planning/gap', query: { requestId: period.requestId } }"><strong>{{ period.month }}월 · 수락 진행 중</strong><span>{{ period.approvedOrganizationCount }}/{{ period.requiredOrganizationCount }}개 조직 수락</span></RouterLink><button v-if="period.latestMasterId" class="download-master" :disabled="!!downloadBusy" :aria-label="`${period.month}월 마스터 Excel 다운로드`" @click="downloadMaster(period)">⤓</button></div></template><p v-if="!periods.some(item => item.requestId || item.latestMasterId)" class="master-empty">등록된 월간 요청이 없습니다.</p></div><RouterLink v-if="annualMaster?.latestMasterId" :to="`/planning/annual-masters/${annualMaster.latestMasterId}`" class="text-button">연간 계획 · {{ annualMaster.latestVersionLabel }} →</RouterLink></section></div>
      <div class="archive-detail-stack">
        <section class="organization-detail">
          <h2>{{ selectedOrganization?.name || '조직을 선택하세요' }} · {{ year }}</h2><p class="contact">{{ selectedOrganization?.code || '조직별 제출 자료' }}</p>
          <h3>월별 최종 버전</h3>
          <div class="month-rail"><button :class="{ active: month === 0 }" @click="choosePeriod(0)"><span>연간 계획</span>{{ latestVersion(0)?.seq ? `${latestVersion(0).seq}차` : '미제출' }}</button><button v-for="value in 12" :key="value" :class="{ active: month === value }" @click="choosePeriod(value)"><span>{{ value }}월</span>{{ latestVersion(value)?.seq ? `${latestVersion(value).seq}차` : '미제출' }}</button></div>
          <div class="versions-heading"><h3>{{ month ? `${month}월` : '연간 계획' }} 버전 이력</h3><label class="draft-toggle"><input v-model="includeDrafts" type="checkbox" />작성 중 포함</label></div>
          <p v-if="loading" class="notice" role="status">선택 연도의 업로드 이력을 불러오는 중…</p>
          <div class="table-scroll"><table><caption class="visually-hidden">조직의 선택 기간 저장본과 소명 확인</caption><thead><tr><th>버전</th><th>업로드 일시</th><th>상태</th><th>변경·소명</th><th class="file-column">원본</th></tr></thead><tbody><tr v-for="item in submissions" :key="item.id" :class="{ latest: String(item.id) === selectedSubmissionId }"><td><RouterLink :to="detailLink(item)" class="file-link">{{ item.versionLabel || '초안' }}<small>{{ item.originalFilename }}</small></RouterLink></td><td>{{ dateTime(item.uploadedAt) }}</td><td>{{ submissionStatus(item) }}</td><td><button class="text-button" :aria-pressed="String(item.id) === selectedSubmissionId" @click="selectSubmission(item.id)">이력 보기</button></td><td class="file-column"><button :disabled="!!downloadBusy" :aria-label="`${item.originalFilename} 원본 다운로드`" @click="download(item)">⤓</button></td></tr><tr v-if="!submissions.length"><td colspan="5" class="empty-cell">{{ loading ? '불러오는 중…' : '이 기간에 업로드한 파일이 없습니다.' }}</td></tr></tbody></table></div>
          <div class="versions-heading"><h3>변경·소명 전체 회차 {{ historySubmissionId ? '· 선택 버전' : '' }}</h3><button v-if="historySubmissionId" class="text-button" @click="selectSubmission('')">전체 버전 보기</button></div><p v-if="reviewError" class="alert error" role="alert">{{ reviewError }}</p><div class="table-scroll"><table class="changes"><thead><tr><th>변경 일자</th><th>번호</th><th>코드</th><th>항목명</th><th>소명 사유</th></tr></thead><tbody><template v-for="group in changeGroups" :key="group.day"><tr v-for="(item, index) in group.rows" :key="`${item.id}:${item.explanation.id}`"><td v-if="index === 0" :rowspan="group.rows.length">{{ group.day }}</td><td>{{ index + 1 }}</td><td><b>{{ item.reportItem.code }}</b></td><td>{{ item.reportItem.name }}<small>{{ item.month }}월 {{ item.scenario === 'PLAN' ? '계획' : item.scenario === 'ACTUAL' ? '실적' : '추정' }} · {{ item.submission.versionLabel }}</small></td><td>{{ item.explanation.answer }}<small>{{ item.explanation.round > 1 ? '재소명' : '소명' }} {{ item.explanation.round }}차 · {{ item.explanation.verdict === 'ACCEPTED' ? '수락' : item.explanation.verdict === 'REJECTED' ? '보완 요청' : '검토 대기' }} <RouterLink :to="detailLink(item.submission)" class="text-button">전체 소명 이력 →</RouterLink></small></td></tr></template><tr v-if="!changeGroups.length"><td colspan="5" class="empty-cell">{{ reviewsLoading ? '변경 소명을 불러오는 중…' : '이 기간의 제출본에 작성된 변경 소명이 없습니다.' }}</td></tr></tbody></table></div>
        </section>
      </div>
    </div>
  </div>
</template>

<style scoped>

.archive-page { position:relative; isolation:isolate; min-height:1000px; }.archive-background { position:absolute; top:-30px; left:-36px; right:-36px; height:1076px; overflow:hidden; z-index:-1; pointer-events:none; }.archive-background img { display:block; }
.archive-tools>select { width:auto; margin:0; min-height:0; }
.archive-note { display:flex; align-items:center; justify-content:space-between; gap:12px; }.archive-note label { display:flex; gap:8px; align-items:center; }.archive-note input { width:85px; min-height:30px; margin:0; padding:4px 8px; }
.archive-heading,.archive-tools { display:flex; align-items:center; justify-content:space-between; gap:14px; }.archive-tools>select { background:white; border:1px solid var(--line); border-radius:7px; padding:9px; font-size:12px; }.year-tabs { display:flex; gap:3px; padding:4px; background:#ebe9e4; border-radius:10px; }.year-tabs button { padding:9px 18px; background:transparent; color:#5f636b; font-size:12px; border-radius:7px; }.year-tabs button.active { background:white; color:var(--ink); font-weight:700; }
.archive-note { margin:12px 0 18px; font-size:11px; color:#8b7657; }.archive-layout { display:grid; grid-template-columns:250px minmax(0,1fr); gap:20px; align-items:start; margin-top:24px; }
.organization-tree,.organization-detail,.master-card { background:white; border:1px solid var(--line); border-radius:12px; }.organization-tree { padding:16px; min-height:342px; }.organization-tree input { width:100%; padding:11px 12px; border:1px solid var(--line); border-radius:8px; font-size:13px; }.organization-tree>p { color:#9ea2a9; font-size:11px; margin:10px 0 4px; }.organization-tree>button { display:block; width:100%; padding:13px 10px; text-align:left; background:transparent; font-size:13px; border-radius:6px; }.organization-tree>button.selected { font-weight:700; background:#faf9f6; }.organization-tree>button:hover { background:#f5f4f0; }
.organization-detail { min-height:757px; padding:26px 24px; }.organization-detail h2 { font-size:20px; }.contact { color:#9ea2a9; font-size:12px; margin-top:6px; }.organization-detail h3 { font-size:14px; margin:28px 0 12px; }.month-rail { display:flex; gap:6px; overflow-x:auto; padding-bottom:4px; }.month-rail button { flex:1 0 48px; border:1px solid var(--line); background:#fff; border-radius:7px; min-height:62px; font-size:11px; padding:8px 4px; }.month-rail button span { display:block; color:#9ea2a9; font-size:11px; margin-bottom:4px; }.month-rail button.active { background:#14171e; color:#fff; border-color:#14171e; }
table { font-size:12px; min-width:500px; border:1px solid var(--line); border-collapse:separate; border-spacing:0; border-radius:8px; overflow:hidden; }.file-column { text-align:center; width:58px; }.file-column button { background:transparent; font-size:18px; color:#5f636b; padding:3px 8px; }.latest { background:#faf7f1; }.latest strong,.changes b { color:var(--gold); }.changes { margin-top:0; }.changes td { font-size:12px; border-right:1px solid var(--line); line-height:1.7; }.changes td:last-child { color:#6f7480; border-right:0; }.changes td:first-child { white-space:nowrap; }.changes b { margin-right:8px; font-size:11px; }.empty-cell { height:95px; text-align:center; color:#9ea2a9; }.file-link { font-weight:600; }.file-link small { display:block; color:#9ea2a9; margin-top:4px; font-weight:400; }
.master-card { margin-top:18px; padding:18px; }.master-card h2 { font-size:14px; }.master-card>.text-button { display:block; margin-top:15px; }.master-rail { display:flex; flex-direction:column; gap:10px; margin-top:14px; }.master-item { position:relative; background:#f5f4f0; border-radius:8px; padding:14px 28px 14px 10px; text-align:left; }.master-rail strong { display:block; font-size:11px; overflow-wrap:anywhere; }.master-rail span { display:block; color:#9ea2a9; font-size:10px; margin-top:6px; }.download-master { position:absolute; right:6px; top:15px; background:transparent; padding:3px; color:#777; font-size:17px; }.master-empty { color:#9ea2a9; font-size:12px; line-height:1.7; }.versions-heading { display:flex; align-items:center; justify-content:space-between; gap:15px; }.draft-toggle { display:flex; align-items:center; gap:6px; font-size:11px; color:#8c9098; font-weight:400; margin-top:16px; }.draft-toggle input { width:14px; min-height:14px; margin:0; }
@media(max-width:1200px) { .archive-layout { grid-template-columns:220px minmax(0,1fr); }.archive-heading { align-items:flex-start; }.archive-tools { flex-wrap:wrap; justify-content:flex-end; } }
@media(max-width:800px) { .archive-layout { grid-template-columns:1fr; }.organization-tree { min-height:0; }.archive-heading { flex-wrap:wrap; }.archive-tools { justify-content:flex-start; }.organization-detail { padding:20px 16px; } }
</style>
