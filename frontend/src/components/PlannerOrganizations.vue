<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useSession } from '../stores/session'
import { dateTime, errorText, query, saveBlob, submissionStatus } from '../lib/display'

const session = useSession()
const route = useRoute()
const mode = ref(route.query.mode === 'actual' || typeof route.query.organizationId === 'string' ? 'actual' : 'preview')
const year = ref(2026)
const month = ref(10)
const search = ref('')
const selectedId = ref('media')
const organizations = ref([])
const submissions = ref([])
const loading = ref(false)
const error = ref('')
const notice = ref('')
const nextCursor = ref(null)
let controller = new AbortController()
let generation = 0
const previewOrganizations = [
  { id: 'mno', name: 'MNO사업부' }, { id: 'media', name: '미디어사업부' },
  { id: 'b2b', name: 'B2B사업부' }, { id: 'enterprise', name: '엔터프라이즈사업부' },
  { id: 'platform', name: '플랫폼사업부' }, { id: 'ai', name: 'AI사업부' },
]
const versions = ['v1', 'v2.01', 'v3', 'v4.02', 'v5', 'v6', 'v7.01', 'v8', 'v9', 'v10.01', '—', '—']
const changes = [
  ['2026.11.10', 'G1', '매출 › 제휴상품', '제휴 정산 지연분(10월)을 11월로 이연 반영함'],
  ['', 'G2', '매출 › 신사업/기타B2B', 'B2B 신규 계약 개시가 12월로 연기됨'],
  ['', 'A3', '영업비용 › 고객모집비용', '프로모션 단가 인하로 모집비가 계획보다 줄어듦'],
  ['2026.11.03', 'A7', '영업비용 › 고객유지비용', '약정 만료 고객 리텐션 캠페인을 앞당겨 집행함'],
]
const masters = [['master_ver10', '생성 대기'], ['master_ver9', '10.12 확정'], ['master_ver8', '09.11 확정'], ['master_ver7', '08.12 확정'], ['master_ver1', '2026.02 확정']]

/** 선택한 보기의 조직 목록을 검색어로 좁힌다.
 * Args: 없음. Returns: 조직 배열. Raises: 없음. Side Effects: 없음. filter 콜백은 이름만 비교한다.
 */
const visibleOrganizations = computed(function filterOrganizations() {
  return (mode.value === 'preview' ? previewOrganizations : organizations.value).filter(item => item.name.includes(search.value.trim()))
})

/** 현재 선택 조직을 찾는다.
 * Args: 없음. Returns: 조직 또는 undefined. Raises: 없음. Side Effects: 없음. find 콜백은 ID만 비교한다.
 */
const selectedOrganization = computed(function findOrganization() {
  return (mode.value === 'preview' ? previewOrganizations : organizations.value).find(item => item.id === selectedId.value)
})

/** 실제 조직을 읽고 첫 조직의 연간 업로드 이력을 조회한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: 읽기 HTTP·화면 상태 변경; some 콜백은 경로의 조직 ID가 목록에 있는지만 확인한다.
 */
async function changeMode() {
  controller.abort()
  controller = new AbortController()
  const current = ++generation
  error.value = ''; notice.value = ''; search.value = ''; submissions.value = []; nextCursor.value = null
  if (mode.value === 'preview') { selectedId.value = 'media'; year.value = 2026; month.value = 10; loading.value = false; return }
  selectedId.value = ''
  loading.value = true
  const requestedYear = Number(route.query.year)
  const requestedMonth = Number(route.query.month)
  year.value = Number.isInteger(requestedYear) && requestedYear >= 2000 && requestedYear <= 9999 ? requestedYear : 2026
  month.value = Number.isInteger(requestedMonth) && requestedMonth >= 0 && requestedMonth <= 12 ? requestedMonth : 0
  try {
    const all = []
    let cursor = null
    do {
      const page = await session.request(`/api/v1/organizations${query({ limit: 100, cursor })}`, { signal: controller.signal })
      all.push(...page.items); cursor = page.nextCursor
    } while (cursor && !controller.signal.aborted)
    if (generation !== current) return
    organizations.value = all
    selectedId.value = all.some(item => item.id === route.query.organizationId) ? route.query.organizationId : all[0]?.id || ''
    await loadSubmissions()
  } catch (cause) { if (current === generation && cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { if (current === generation) loading.value = false }
}

/** 조직·연월 필터로 실제 저장본을 조회하고 구 응답을 무시한다.
 * Args: more 다음 페이지 여부. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: 이전 HTTP 취소·GET·목록 변경. 승인되지 않은 초안에 버전 번호를 만들지 않는다.
 */
async function loadSubmissions(more = false) {
  if (mode.value !== 'actual' || !selectedId.value) return
  controller.abort(); controller = new AbortController()
  const current = ++generation
  loading.value = true; error.value = ''
  if (!more) { submissions.value = []; nextCursor.value = null }
  try {
    const page = await session.request(`/api/v1/submissions${query({ organizationId: selectedId.value, year: year.value, month: month.value || '', type: month.value ? 'MONTHLY_ACTUAL' : 'ANNUAL_PLAN', limit: 50, cursor: more ? nextCursor.value : null })}`, { signal: controller.signal })
    if (generation !== current) return
    submissions.value = more ? [...submissions.value, ...page.items] : page.items
    nextCursor.value = page.nextCursor
  } catch (cause) { if (current === generation && cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { if (current === generation) loading.value = false }
}

/** 조직 또는 기간 선택을 반영하고 실제 보기에서 다시 조회한다.
 * Args: field organization, year 또는 month, value 선택값. Returns: 없음. Raises: 없음.
 * Side Effects: 화면 선택 변경·실제 보기의 읽기 HTTP.
 */
function select(field, value) {
  if (field === 'organization') selectedId.value = value
  else if (field === 'year') year.value = value
  else month.value = value
  notice.value = ''; loadSubmissions()
}

/** 실제 선택 조직·기간을 제출본 상세와 돌아갈 경로에 전달한다.
 * Args: item 제출 요약. Returns: Vue Router 대상 객체. Raises: 없음. Side Effects: 없음.
 */
function detailLink(item) {
  return { path: `/submissions/${item.id}`, query: { from: 'organizations', organizationId: selectedId.value, year: year.value, month: month.value } }
}

/** 경로에 실제 조회 문맥이 있으면 같은 조직·기간을 복원한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 실제 보기에서 조직·제출 조회 시작.
 */
function initialize() { if (mode.value === 'actual') changeMode() }

/** 실제 업로드 원본만 권한 확인 후 내려받는다.
 * Args: item 서버의 제출 DTO. Returns: Promise<void>. Raises: 없음(화면 표시).
 * Side Effects: GET 및 사용자 원본 다운로드. 예시 자료 다운로드는 수행하지 않는다.
 */
async function download(item) {
  try { saveBlob(await session.request(`/api/v1/submissions/${item.id}/file`, { blob: true, signal: controller.signal }), item.originalFilename) }
  catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
}

/** 예시 파일·미구현 마스터의 상태를 안내한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 안내만 표시한다.
 */
function explainPreview() { notice.value = '디자인 예시에는 내려받을 파일이 없습니다. 실제 업로드 보기에서 저장한 원본을 확인하세요.' }

/** 화면 이탈 시 요청·응답을 무효화한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소.
 */
function dispose() { generation++; controller.abort() }
onMounted(initialize)
onBeforeUnmount(dispose)
</script>

<template>
  <div class="archive-page">
    <div class="archive-background" aria-hidden="true"><img src="/assets/planner-archive-background.svg" alt="" /></div>
    <header class="archive-heading"><h1>조직별 현황 · 아카이브</h1><div class="archive-tools"><select v-model="mode" aria-label="아카이브 보기" @change="changeMode"><option value="preview">디자인 예시</option><option value="actual">실제 업로드</option></select><div class="year-tabs"><button v-for="value in (mode === 'preview' ? [2025, 2026, 2027] : [2026, 2027, 2028])" :key="value" :class="{ active: year === value }" @click="select('year', value)">{{ value }}</button></div></div></header>
    <div class="archive-note"><p>{{ mode === 'preview' ? '디자인 예시 · 실제 집계가 아닙니다' : '실제 업로드 자료 · 초안과 최종 제출본을 구분해 표시합니다' }}</p><label v-if="mode === 'actual'">대상 연도 <input v-model.number="year" aria-label="아카이브 대상 연도" type="number" min="2000" max="9999" @change="loadSubmissions()" /></label></div>
    <p v-if="notice" class="notice" role="status">{{ notice }}</p><p v-if="error" class="alert error" role="alert">{{ error }}</p>
    <div class="archive-layout">
      <aside class="organization-tree"><input v-model="search" type="search" placeholder="조직 검색" aria-label="조직 검색" /><p>사업부 · {{ mode === 'preview' ? previewOrganizations.length : organizations.length }}</p><button v-for="org in visibleOrganizations" :key="org.id" :class="{ selected: org.id === selectedId }" @click="select('organization', org.id)">{{ org.name }}</button><p v-if="!visibleOrganizations.length">{{ loading ? '불러오는 중…' : '검색 결과가 없습니다' }}</p></aside>
      <div class="archive-detail-stack">
        <section class="organization-detail">
          <h2>{{ selectedOrganization?.name || '조직을 선택하세요' }} · {{ year }}</h2><p class="contact">{{ mode === 'preview' && selectedId === 'media' ? '담당 박미디 매니저' : selectedOrganization?.code || '조직별 제출 자료' }}</p>
          <h3>월별 최종 버전</h3>
          <div class="month-rail"><button v-if="mode === 'actual'" :class="{ active: month === 0 }" @click="select('month', 0)"><span>연간</span>계획</button><button v-for="value in 12" :key="value" :class="{ active: month === value }" @click="select('month', value)"><span>{{ value }}월</span>{{ mode === 'preview' && year === 2026 && selectedId === 'media' ? versions[value - 1] : '—' }}</button></div>
          <h3>{{ month ? `${month}월` : '연간 계획' }} {{ mode === 'actual' ? '업로드 이력' : '버전 이력' }}</h3>
          <div class="table-scroll"><table><caption class="visually-hidden">조직의 선택 기간 저장본</caption><thead><tr><th>버전</th><th>최종 수정 일시</th><th v-if="mode === 'actual'">상태</th><th class="file-column">파일</th></tr></thead><tbody>
            <template v-if="mode === 'preview' && year === 2026 && month === 10 && selectedId === 'media'"><tr><td><strong>ver10</strong></td><td>11.05 16:40</td><td class="file-column"><button aria-label="ver10 예시 파일" @click="explainPreview">⤓</button></td></tr><tr class="latest"><td><strong>ver10.01</strong></td><td>11.12 14:20</td><td class="file-column"><button aria-label="ver10.01 예시 파일" @click="explainPreview">⤓</button></td></tr></template>
            <template v-else-if="mode === 'actual'"><tr v-for="item in submissions" :key="item.id"><td><RouterLink :to="detailLink(item)" class="file-link">{{ item.versionLabel || '초안' }}<small>{{ item.originalFilename }}</small></RouterLink></td><td>{{ dateTime(item.uploadedAt) }}</td><td>{{ submissionStatus(item) }}</td><td class="file-column"><button :aria-label="`${item.originalFilename} 다운로드`" @click="download(item)">⤓</button></td></tr><tr v-if="!submissions.length"><td colspan="4" class="empty-cell">{{ loading ? '업로드 자료를 불러오는 중…' : '이 기간에 업로드한 파일이 없습니다.' }}</td></tr></template>
            <tr v-else><td colspan="3" class="empty-cell">선택한 조직·기간의 예시 이력이 없습니다.</td></tr>
          </tbody></table></div>
          <button v-if="mode === 'actual' && nextCursor" class="button load-more" :disabled="loading" @click="loadSubmissions(true)">이력 더 보기</button>
          <h3>변경 이력</h3>
          <div class="table-scroll"><table class="changes"><caption class="visually-hidden">변경 항목과 소명 사유</caption><thead><tr><th>변경 일자</th><th>변경 항목</th><th>소명 사유</th></tr></thead><tbody><template v-if="mode === 'preview' && year === 2026 && month === 10 && selectedId === 'media'"><tr v-for="row in changes" :key="row[1]"><td>{{ row[0] }}</td><td><b>{{ row[1] }}</b> {{ row[2] }}</td><td>{{ row[3] }}</td></tr></template><tr v-else><td colspan="3" class="empty-cell">{{ mode === 'actual' ? '변경·소명 이력은 연동 준비 중입니다.' : '선택한 조직·기간의 예시 변경 이력이 없습니다.' }}</td></tr></tbody></table></div>
        </section>
        <section class="master-card"><h2>월별 마스터</h2><div v-if="mode === 'preview'" class="master-rail"><button v-for="(master, index) in masters" :key="master[0]" :class="{ waiting: index === 0 }" @click="explainPreview"><strong>{{ master[0] }}</strong><span>{{ master[1] }}</span><b>⤓</b></button></div><p v-else class="master-empty">실제 월간 마스터는 <RouterLink to="/planning/gap" class="text-button">월간 시트 관리</RouterLink>, 연간 계획 마스터는 <RouterLink to="/planning/requests" class="text-button">요청·검토</RouterLink>에서 확인하세요.</p></section>
      </div>
    </div>
  </div>
</template>

<style scoped>
.archive-page { position:relative; isolation:isolate; min-height:1000px; }.archive-background { position:absolute; top:-30px; left:-36px; right:-36px; height:1076px; overflow:hidden; z-index:-1; pointer-events:none; }.archive-background img { display:block; }
.archive-tools>select { width:auto; margin:0; min-height:0; }
.archive-note { display:flex; align-items:center; justify-content:space-between; gap:12px; }.archive-note label { display:flex; gap:8px; align-items:center; }.archive-note input { width:85px; min-height:30px; margin:0; padding:4px 8px; }
.archive-heading,.archive-tools { display:flex; align-items:center; justify-content:space-between; gap:14px; }.archive-tools>select { background:white; border:1px solid var(--line); border-radius:7px; padding:9px; font-size:12px; }.year-tabs { display:flex; gap:3px; padding:4px; background:#ebe9e4; border-radius:10px; }.year-tabs button { padding:9px 18px; background:transparent; color:#5f636b; font-size:12px; border-radius:7px; }.year-tabs button.active { background:white; color:var(--ink); font-weight:700; }
.archive-note { margin:12px 0 18px; font-size:11px; color:#8b7657; }.archive-layout { display:grid; grid-template-columns:300px minmax(0,1fr); gap:20px; align-items:start; }
.organization-tree,.organization-detail,.master-card { background:white; border:1px solid var(--line); border-radius:12px; }.organization-tree { padding:16px; min-height:342px; }.organization-tree input { width:100%; padding:11px 12px; border:1px solid var(--line); border-radius:8px; font-size:13px; }.organization-tree>p { color:#9ea2a9; font-size:11px; margin:10px 0 4px; }.organization-tree>button { display:block; width:100%; padding:13px 10px; text-align:left; background:transparent; font-size:13px; border-radius:6px; }.organization-tree>button.selected { font-weight:700; background:#faf9f6; }.organization-tree>button:hover { background:#f5f4f0; }
.organization-detail { min-height:757px; padding:26px 24px; }.organization-detail h2 { font-size:20px; }.contact { color:#9ea2a9; font-size:12px; margin-top:6px; }.organization-detail h3 { font-size:14px; margin:28px 0 12px; }.month-rail { display:flex; gap:6px; overflow-x:auto; padding-bottom:4px; }.month-rail button { flex:1 0 48px; border:1px solid var(--line); background:#fff; border-radius:7px; min-height:62px; font-size:11px; padding:8px 4px; }.month-rail button span { display:block; color:#9ea2a9; font-size:11px; margin-bottom:4px; }.month-rail button.active { background:#14171e; color:#fff; border-color:#14171e; }
table { font-size:12px; min-width:500px; border:1px solid var(--line); border-collapse:separate; border-spacing:0; border-radius:8px; overflow:hidden; }.file-column { text-align:center; width:58px; }.file-column button { background:transparent; font-size:18px; color:#5f636b; padding:3px 8px; }.latest { background:#faf7f1; }.latest strong,.changes b { color:var(--gold); }.changes { margin-top:0; }.changes td { font-size:12px; border-right:1px solid var(--line); line-height:1.7; }.changes td:last-child { color:#6f7480; border-right:0; }.changes td:first-child { white-space:nowrap; }.changes b { margin-right:8px; font-size:11px; }.empty-cell { height:95px; text-align:center; color:#9ea2a9; }.file-link { font-weight:600; }.file-link small { display:block; color:#9ea2a9; margin-top:4px; font-weight:400; }
.master-card { margin-top:18px; padding:24px; }.master-rail { display:flex; gap:10px; margin-top:18px; overflow-x:auto; }.master-rail button { position:relative; flex:1 0 125px; background:#f5f4f0; border-radius:8px; padding:14px 10px; text-align:left; }.master-rail strong { display:block; font-size:12px; }.master-rail span { display:block; color:#9ea2a9; font-size:11px; margin-top:6px; }.master-rail button.waiting { background:#fbf2e0; }.master-rail button.waiting span { color:#b7791f; }.master-rail b { position:absolute; right:10px; top:12px; color:#9ea2a9; }.master-empty { color:#9ea2a9; font-size:12px; margin-top:18px; }
@media(max-width:1200px) { .archive-layout { grid-template-columns:220px minmax(0,1fr); }.archive-heading { align-items:flex-start; }.archive-tools { flex-wrap:wrap; justify-content:flex-end; } }
@media(max-width:800px) { .archive-layout { grid-template-columns:1fr; }.organization-tree { min-height:0; }.archive-heading { flex-wrap:wrap; }.archive-tools { justify-content:flex-start; }.organization-detail { padding:20px 16px; } }
</style>
