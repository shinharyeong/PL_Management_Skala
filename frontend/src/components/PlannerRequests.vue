<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { useSession } from '../stores/session'
import { query, errorText } from '../lib/display'

const route = useRoute()
const session = useSession()
const items = ref([])
const nextCursor = ref(null)
const selected = ref(null)
const selectedId = ref(typeof route.query.requestId === 'string' ? route.query.requestId : '')
const loading = ref(false)
const detailLoading = ref(false)
const error = ref('')
const controller = new AbortController()
let detailController = new AbortController()
let detailGeneration = 0

/** 연간 요청 목록을 읽고 선택한 요청의 조직별 진행 현황을 연결한다.
 * Args: more 다음 페이지 여부. Returns: Promise<void>. Raises: 없음(API 오류를 화면에 표시).
 * Side Effects: HTTP 조회·목록/선택 상태 갱신; 새 요청 작성 후 query의 requestId를 유지한다.
 */
async function load(more = false) {
  loading.value = true
  error.value = ''
  try {
    const page = await session.request(`/api/v1/submission-requests${query({
      type: 'ANNUAL_PLAN', limit: 50, cursor: more ? nextCursor.value : null,
    })}`, { signal: controller.signal })
    if (controller.signal.aborted) return
    items.value = more ? [...items.value, ...page.items] : page.items
    nextCursor.value = page.nextCursor
    if (!more) {
      const id = selectedId.value || items.value[0]?.id
      if (id) await select(id)
    }
  } catch (cause) {
    if (cause.name !== 'AbortError') error.value = errorText(cause)
  } finally { loading.value = false }
}

/** 선택 요청의 최신 조직 현황을 읽고 이전 선택의 뒤늦은 응답을 버린다.
 * Args: id 요청 ID 문자열. Returns: Promise<void>. Raises: 없음(API 오류를 화면에 표시).
 * Side Effects: 이전 HTTP 취소·요청 상세/오류 갱신; 세대값으로 동일 요청 재조회도 구분한다.
 */
async function select(id) {
  detailController.abort()
  detailController = new AbortController()
  const current = ++detailGeneration
  selectedId.value = id
  selected.value = null
  detailLoading.value = true
  error.value = ''
  try {
    const result = await session.request(`/api/v1/submission-requests/${id}`, { signal: detailController.signal })
    if (current !== detailGeneration) return
    if (result.type !== 'ANNUAL_PLAN') {
      error.value = '연간 계획 요청을 선택해 주세요.'
      return
    }
    selected.value = result
  } catch (cause) {
    if (current === detailGeneration && cause.name !== 'AbortError') error.value = errorText(cause)
  } finally {
    if (current === detailGeneration) detailLoading.value = false
  }
}

/** 기간 선택 이벤트를 요청 상세 조회에 연결한다.
 * Args: event select 변경 이벤트. Returns: 없음. Raises: 없음. Side Effects: 요청 상세 조회 시작.
 */
function changeRequest(event) { select(event.target.value) }

/** 원본 표의 월·일 형식으로 서버 시각을 한국 시간대에 표시한다.
 * Args: value ISO 시각/null, withTime 시·분 포함 여부. Returns: 표시 문자열.
 * Raises: 없음. Side Effects: 없음; 시각이 없거나 잘못되면 —를 반환한다.
 */
function shortDate(value, withTime = false) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Asia/Seoul', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).formatToParts(date)
  const values = {}
  for (const part of parts) values[part.type] = part.value
  return `${values.month}.${values.day}${withTime ? ` ${values.hour}:${values.minute}` : ''}`
}

/** 최종 제출 상태와 실제 기한으로 요청 표의 상태 표시를 만든다.
 * Args: target 조직 대상 DTO. Returns: label/tone 표시 객체. Raises: 없음.
 * Side Effects: 없음; 업로드 초안을 제출 완료로 처리하지 않는다.
 */
function targetStatus(target) {
  const states = {
    PENDING: { label: '제출 완료', tone: 'submitted' },
    APPROVED: { label: '승인 완료', tone: 'approved' },
    REJECTED: { label: '반려', tone: 'overdue' },
  }
  if (states[target.latestStatus]) return states[target.latestStatus]
  if (selected.value?.dueAt && new Date(selected.value.dueAt).getTime() < Date.now()) {
    return { label: '기한 경과', tone: 'overdue' }
  }
  return { label: '회신 대기', tone: 'waiting' }
}

/** 최종 제출본이 있으면 직접 열고 없으면 요청·조직의 업로드 이력을 연다.
 * Args: target 요청 대상 조직 DTO. Returns: Vue Router 대상 객체. Raises: 없음.
 * Side Effects: 없음; 초안을 최신 최종 제출본으로 간주하지 않는다.
 */
function targetLink(target) {
  const query = { from: 'requests', requestId: selected.value.id, organizationId: target.organization.id }
  return { path: target.latestSubmittedSubmissionId ? `/submissions/${target.latestSubmittedSubmissionId}` : '/history', query }
}

/** 화면 이탈 시 목록과 상세 요청의 갱신을 중단한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소·상세 세대값 무효화.
 */
function dispose() {
  detailGeneration++
  controller.abort()
  detailController.abort()
}

onMounted(load)
onBeforeUnmount(dispose)
</script>

<template>
  <div class="annual-requests" data-figma-node="2:1108">
    <h1>연간 계획</h1>
    <div class="request-types">
      <RouterLink to="/planning/annual-plan" class="request-type" data-figma-node="2:1160">연간 계획 요청</RouterLink>
    </div>

    <section class="request-progress" aria-labelledby="request-progress-title" data-figma-node="2:1252">
      <div class="progress-heading">
        <h2 id="request-progress-title">{{ selected ? `${selected.year}년도 연간 계획` : '연간 계획 요청 현황' }}</h2>
        <div class="progress-controls">
          <label v-if="items.length" class="visually-hidden" for="annual-request-period">요청 기간</label>
          <select v-if="items.length" id="annual-request-period" :value="selectedId" :disabled="loading" @change="changeRequest">
            <option v-for="item in items" :key="item.id" :value="item.id">{{ item.year }}년도 연간 계획</option>
          </select>
          <button class="refresh-link" type="button" :disabled="loading || detailLoading" @click="load(false)">새로고침</button>
        </div>
      </div>
      <div v-if="error" class="alert error" role="alert">{{ error }}</div>
      <p v-if="loading || detailLoading" class="progress-empty" role="status">요청 현황을 불러오고 있습니다.</p>
      <template v-else-if="selected">
        <div class="progress-table-wrap">
          <table>
            <caption class="visually-hidden">{{ selected.year }}년도 조직별 연간 계획 요청 진행 현황</caption>
            <colgroup>
              <col class="organization-column" /><col class="code-column" /><col class="sent-column" />
              <col class="due-column" /><col class="recipient-column" /><col class="state-column" /><col class="detail-column" />
            </colgroup>
            <thead>
              <tr><th scope="col">조직명</th><th scope="col">조직코드</th><th scope="col">발송일</th><th scope="col">기한</th><th scope="col">수신 담당자</th><th scope="col">상태</th><th scope="col"><span class="visually-hidden">상세</span></th></tr>
            </thead>
            <tbody>
              <tr v-for="target in selected.targets" :key="target.organization.id">
                <td class="organization-name">{{ target.organization.name }}</td>
                <td>{{ target.organization.code }}</td>
                <td>{{ shortDate(selected.requestedAt) }}</td>
                <td>{{ shortDate(selected.dueAt, true) }}</td>
                <td class="recipient-name">—</td>
                <td>
                  <span :class="['request-state', targetStatus(target).tone]">{{ targetStatus(target).label }}</span>
                  <small v-if="target.draftCount">업로드 초안 {{ target.draftCount }}건</small>
                </td>
                <td><RouterLink :to="targetLink(target)" class="detail-link">보기</RouterLink></td>
              </tr>
            </tbody>
            <tfoot>
              <tr>
                <td>연간 계획 ({{ selected.year }}년)</td>
                <td>전 조직 {{ selected.targetCount }}곳</td>
                <td>{{ shortDate(selected.requestedAt) }}</td>
                <td>{{ shortDate(selected.dueAt, true) }}</td>
                <td><div class="submission-progress"><progress :value="selected.submittedCount" :max="selected.targetCount || 1" aria-label="최종 제출 조직 수"></progress><span>{{ selected.submittedCount }}/{{ selected.targetCount }}</span></div></td>
                <td><span class="summary-state">{{ selected.status === 'OPEN' ? '진행 중' : '마감' }}</span></td>
                <td></td>
              </tr>
            </tfoot>
          </table>
        </div>
        <p class="progress-note">업로드 초안은 최종 제출 완료에 포함하지 않습니다.</p>
      </template>
      <div v-else-if="!error" class="progress-empty">
        <p>아직 발송한 연간 계획 요청이 없습니다.</p>
        <RouterLink to="/planning/annual-plan" class="empty-link">연간 계획 요청 작성 →</RouterLink>
      </div>
      <button v-if="nextCursor" class="more-link" type="button" :disabled="loading" @click="load(true)">이전 요청 더 불러오기</button>
    </section>
  </div>
</template>

<style scoped>
.annual-requests h1 {
  margin: 0;
  font-size: 26px;
  font-weight: 700;
  line-height: 1.45;
  letter-spacing: -.6px;
}
.request-types { display: flex; gap: 12px; margin-top: 20px; }
.request-type {
  display: flex;
  align-items: center;
  width: 166px;
  min-height: 63px;
  padding: 19px 21px;
  border: 1px solid #e7e4de;
  border-radius: 12px;
  background: #fff;
  color: #16181d;
  font-size: 15px;
  font-weight: 700;
  line-height: 1.45;
}
.request-type:hover { border-color: #a9875b; }
.request-progress {
  min-height: 361px;
  margin-top: 118px;
  padding: 24px 25px 30px;
  border: 1px solid #e7e4de;
  border-radius: 12px;
  background: #fff;
}
.progress-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 27px;
  gap: 20px;
  margin-bottom: 27px;
}
.progress-heading h2 { color: #16181d; font-size: 16px; font-weight: 700; line-height: 1.45; }
.progress-controls { display: flex; align-items: center; gap: 14px; }
.progress-controls select {
  width: 178px;
  min-height: 30px;
  margin: 0;
  padding: 5px 9px;
  border: 1px solid #e7e4de;
  border-radius: 6px;
  background: #fff;
  color: #5f636b;
  font-size: 11px;
}
.refresh-link, .more-link { padding: 0; background: transparent; color: #7c808a; font-size: 11px; white-space: nowrap; }
.refresh-link:hover:not(:disabled), .more-link:hover:not(:disabled) { color: #75552f; }
.refresh-link:disabled { color: #aaa; }
.progress-table-wrap { overflow: auto; border: 1px solid #e7e4de; border-radius: 10px; }
.progress-table-wrap table {
  width: 100%;
  min-width: 850px;
  table-layout: fixed;
  border-collapse: collapse;
  color: #16181d;
  font-size: 13px;
  line-height: 1.45;
}
.organization-column { width: 19%; }
.code-column { width: 17%; }
.sent-column { width: 9%; }
.due-column { width: 12%; }
.recipient-column { width: 21%; }
.state-column { width: 16%; }
.detail-column { width: 6%; }
.progress-table-wrap th {
  height: 35px;
  padding: 8px 7px;
  border-bottom: 1px solid #e7e4de;
  background: #faf9f6;
  color: #5f636b;
  font-size: 11.5px;
  font-weight: 500;
  text-align: left;
}
.progress-table-wrap td { height: 54px; padding: 12px 7px; border-bottom: 1px solid #e7e4de; vertical-align: middle; overflow-wrap: anywhere; }
.progress-table-wrap .organization-name { font-weight: 500; }
.progress-table-wrap .recipient-name { color: #9ea2a9; }
.progress-table-wrap tbody tr:last-child td { border-bottom: 1px solid #e7e4de; }
.request-state {
  display: inline-flex;
  align-items: center;
  min-height: 22px;
  padding: 3px 9px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 500;
  line-height: 1.45;
  white-space: nowrap;
}
.request-state.waiting { color: #2e5bc9; background: #eaf0fb; }
.request-state.submitted { color: #b7791f; background: #fbf2e0; }
.request-state.overdue { color: #c4453a; background: #fbeae8; }
.request-state.approved { color: #216943; background: #e8f4ec; }
.progress-table-wrap td small { display: block; margin-top: 4px; color: #7c808a; font-size: 10px; }
.detail-link { color: #75552f; font-size: 11px; }
.detail-link:hover { text-decoration: underline; }
.progress-table-wrap tfoot td { border-bottom: 0; background: #ffec99; font-size: 12px; }
.submission-progress { display: flex; align-items: center; gap: 8px; white-space: nowrap; }
.submission-progress progress { width: 110px; height: 6px; border: 0; border-radius: 999px; overflow: hidden; appearance: none; background: #efeeea; }
.submission-progress progress::-webkit-progress-bar { background: #efeeea; }
.submission-progress progress::-webkit-progress-value { background: #14171e; border-radius: 999px; }
.submission-progress progress::-moz-progress-bar { background: #14171e; border-radius: 999px; }
.summary-state { font-size: 11px; }
.progress-note { margin-top: 12px; color: #9a9690; font-size: 11px; }
.progress-empty { display: flex; min-height: 220px; flex-direction: column; align-items: center; justify-content: center; gap: 12px; color: #7c808a; font-size: 13px; text-align: center; }
.empty-link { color: #75552f; font-size: 12px; }
.more-link { display: block; margin: 16px auto 0; }
@media (max-width: 900px) {
  .request-progress { padding: 22px 18px; }
  .progress-heading { align-items: flex-start; flex-wrap: wrap; gap: 14px; }
}
@media (max-width: 760px) {
  .request-progress { margin-top: 54px; }
  .request-types { flex-wrap: wrap; }
  .progress-heading { margin-bottom: 22px; }
  .progress-controls { width: 100%; justify-content: space-between; }
  .progress-controls select { flex: 1; max-width: 230px; }
}
</style>
