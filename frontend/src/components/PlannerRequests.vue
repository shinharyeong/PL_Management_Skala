<script setup>
import { computed, ref, onMounted, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { useSession } from '../stores/session'
import { dateTime, query, errorText, saveBlob } from '../lib/display'
import { draftStatus } from '../lib/request-workflow'
import RequestEditor from './RequestEditor.vue'
import AnnualPlanConfirmation from './AnnualPlanConfirmation.vue'
import SubmissionReviews from './SubmissionReviews.vue'
import SubmissionRejectionItems from './SubmissionRejectionItems.vue'
import SubmissionExplanationPolicy from './SubmissionExplanationPolicy.vue'

const route = useRoute()
const session = useSession()
const items = ref([])
const nextCursor = ref(null)
const selected = ref(null)
const masterStatus = ref(null)
const masterError = ref('')
const selectedId = ref(typeof route.query.requestId === 'string' ? route.query.requestId : '')
const loading = ref(false)
const detailLoading = ref(false)
const error = ref('')
const controller = new AbortController()
let detailController = new AbortController()
let detailGeneration = 0
const expandedId = ref('')
const expandedSubmission = ref(null)
const expandedLoading = ref(false)
const actionBusy = ref(false)
const actionError = ref('')
const rejectNote = ref('')
const rejectionItems = ref([])
const decision = ref('')
const statusFilter = ref('ALL')
let expansionGeneration = 0
const actionKeys = new Map()

/** 발송된 조직만 진행 상태에 따라 표시한다. Args: 없음. Returns: 대상 목록. Raises: 없음. Side Effects: 없음. */
const visibleTargets = computed(function visibleRequestTargets() { const result = []; for (const item of selected.value?.targets || []) if (item.selected !== false && (statusFilter.value === 'ALL' || (item.latestStatus || 'NOT_SUBMITTED') === statusFilter.value)) result.push(item); return result })
/** 연간 확정 화면에 실제 조직 목록을 제공한다. Args: 없음. Returns: 조직 목록. Raises: 없음. Side Effects: 없음. */
const targetOrganizations = computed(function requestOrganizations() { const items = []; for (const item of selected.value?.targets || []) items.push(item.organization); return items })

/** 행을 펼쳐 실제 제출본과 검토 준비 상태를 읽는다. Args: target 조직 요청 대상. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: GET·행 선택. */
async function expand(target) {
  const id = String(target.organization.id)
  if (actionBusy.value) return
  const current = ++expansionGeneration
  expandedId.value = expandedId.value === id ? '' : id; expandedSubmission.value = null; expandedLoading.value = false; actionError.value = ''; decision.value = ''; rejectNote.value = ''; rejectionItems.value = []
  if (!expandedId.value || !target.latestSubmittedSubmissionId) return
  expandedLoading.value = true
  try { const result = await session.request(`/api/v1/submissions/${target.latestSubmittedSubmissionId}`, { signal: detailController.signal }); if (current === expansionGeneration) expandedSubmission.value = result }
  catch (cause) { if (current === expansionGeneration && cause.name !== 'AbortError') actionError.value = errorText(cause) }
  finally { if (current === expansionGeneration) expandedLoading.value = false }
}

/** 열린 제출본의 원본만 내려받는다. Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: GET·브라우저 다운로드. */
async function downloadSource() {
  if (!expandedSubmission.value || actionBusy.value) return
  actionBusy.value = true; actionError.value = ''
  try { const file = await session.request(`/api/v1/submissions/${expandedSubmission.value.id}/file`, { blob: true, signal: detailController.signal }); if (!detailController.signal.aborted) saveBlob(file, expandedSubmission.value.originalFilename) }
  catch (cause) { if (cause.name !== 'AbortError') actionError.value = errorText(cause) }
  finally { actionBusy.value = false }
}

/** 확인한 제출본을 토큰과 멱등키로 승인 또는 반려한다. Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: 명시적 POST·진행표 새로고침. */
async function decideSubmission() {
  if (!expandedSubmission.value || actionBusy.value || !['APPROVE', 'REJECT'].includes(decision.value)) return
  if (decision.value === 'APPROVE' && !expandedSubmission.value.readiness?.canApprove) return
  if (decision.value === 'REJECT' && !rejectNote.value.trim()) { actionError.value = '반려 사유를 입력해 주세요.'; return }
  const payload = { expectedStateToken: expandedSubmission.value.stateToken, action: decision.value }
  if (decision.value === 'REJECT') { payload.note = rejectNote.value.trim(); payload.rejectionItems = rejectionItems.value }
  const body = JSON.stringify(payload); if (!actionKeys.has(body)) actionKeys.set(body, crypto.randomUUID())
  actionBusy.value = true; actionError.value = ''
  try { await session.request(`/api/v1/submissions/${expandedSubmission.value.id}/decisions`, { method: 'POST', body, headers: { 'Idempotency-Key': actionKeys.get(body) }, signal: detailController.signal }); actionKeys.delete(body); await select(selectedId.value) }
  catch (cause) { if (cause.name !== 'AbortError') actionError.value = errorText(cause) }
  finally { actionBusy.value = false }
}

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
      const currentYear = Number(new Intl.DateTimeFormat('en', { timeZone: 'Asia/Seoul', year: 'numeric' }).format(new Date()))
      let currentRequest = null
      for (const item of items.value) if (item.year === currentYear) currentRequest = item
      const id = selectedId.value || currentRequest?.id || items.value[0]?.id
      if (id) await select(id)
    }
  } catch (cause) {
    if (cause.name !== 'AbortError') error.value = errorText(cause)
  } finally { loading.value = false }
}

/** 선택 요청의 최신 조직 현황을 읽고 이전 선택의 뒤늦은 응답을 버린다.
 * Args: id 요청 ID 문자열. Returns: Promise<void>. Raises: 없음(API 오류를 화면에 표시).
 * Side Effects: 이전 HTTP 취소·요청 상세/마스터 상태 독립 조회·오류 갱신; 구 응답을 버린다.
 */
async function select(id) {
  detailController.abort()
  detailController = new AbortController()
  const current = ++detailGeneration
  selectedId.value = id
  expansionGeneration++; expandedId.value = ''; expandedSubmission.value = null; decision.value = ''
  selected.value = null
  masterStatus.value = null
  masterError.value = ''
  detailLoading.value = true
  error.value = ''
  try {
    const [detail, master] = await Promise.allSettled([
      session.request(`/api/v1/submission-requests/${id}`, { signal: detailController.signal }),
      session.request(`/api/v1/submission-requests/${id}/annual-master`, { signal: detailController.signal }),
    ])
    if (current !== detailGeneration) return
    if (detail.status === 'rejected') throw detail.reason
    const result = detail.value
    if (result.type !== 'ANNUAL_PLAN') {
      error.value = '연간 계획 요청을 선택해 주세요.'
      return
    }
    selected.value = result
    if (master.status === 'fulfilled') masterStatus.value = master.value
    else if (master.reason.name !== 'AbortError') masterError.value = errorText(master.reason)
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
    PENDING: { label: '검토 대기', tone: 'submitted' },
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

/** 최신 완료본과 별도로 현재 업로드 초안의 진단으로 연결한다.
 * Args: target 조직 대상 DTO. Returns: 초안 상세 라우트. Raises: 없음. Side Effects: 없음.
 */
function draftLink(target) { return { path: `/submissions/${target.latestDraftSubmissionId}`, query: { from: 'requests', requestId: selected.value.id, organizationId: target.organization.id } } }

/** 요청 수정 후 현재 선택의 현황과 상태 토큰을 다시 조회한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(조회 영역 표시). Side Effects: 요청 상세 GET.
 */
async function refreshSelected() { if (selectedId.value) await select(selectedId.value) }

/** 화면 이탈 시 목록과 상세 요청의 갱신을 중단한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소·상세 세대값 무효화.
 */
function dispose() {
  expansionGeneration++
  detailGeneration++
  controller.abort()
  detailController.abort()
}

onMounted(load)
onBeforeUnmount(dispose)
</script>

<template>
  <div class="annual-requests" data-figma-node="2:1108">
    <h1>연간 계획 관리</h1>
    <div class="request-types">
      <RouterLink to="/planning/annual-plan/new" class="request-type" data-figma-node="2:1160">연간 계획 요청 <span>→</span></RouterLink>
    </div>

    <section class="request-progress" aria-labelledby="request-progress-title" data-figma-node="2:1252">
      <div class="progress-heading">
        <h2 id="request-progress-title">{{ selected ? `${selected.year}년도 연간 계획` : '연간 계획 요청 현황' }}</h2>
        <div class="progress-controls">
          <label v-if="items.length" class="visually-hidden" for="annual-request-period">요청 기간</label>
          <select v-if="items.length" id="annual-request-period" :value="selectedId" :disabled="loading || detailLoading || actionBusy" @change="changeRequest">
            <option v-for="item in items" :key="item.id" :value="item.id">{{ item.year }}년도 연간 계획</option>
          </select>
          <button class="refresh-link" type="button" :disabled="loading || detailLoading || actionBusy" @click="load(false)">새로고침</button>
        </div>
      </div>
      <div v-if="error" class="alert error" role="alert">{{ error }}</div>
      <p v-if="loading || detailLoading" class="progress-empty" role="status">요청 현황을 불러오고 있습니다.</p>
      <template v-else-if="selected">
        <div v-if="masterError" class="alert error" role="alert">마스터 상태를 확인하지 못했습니다. {{ masterError }}</div>
        <details v-if="masterStatus" class="master-status" aria-labelledby="annual-master-status-title"><summary id="annual-master-status-title">연간 마스터 · {{ masterStatus.publicationState === 'PUBLISHED' ? masterStatus.latestVersionLabel : `승인 ${masterStatus.approvedOrganizationCount}/${masterStatus.requiredOrganizationCount}곳` }}</summary>
          <div>
            <p v-if="masterStatus.publicationState === 'PUBLISHED'"><strong>{{ masterStatus.latestVersionLabel }}</strong> 발행됨</p>
            <p v-else><strong>첫 발행 대기</strong> · 승인 {{ masterStatus.approvedOrganizationCount }}/{{ masterStatus.requiredOrganizationCount }}곳</p>
            <p class="muted">전 조직 승인 시 승인본을 통합해 자동 발행합니다. 이후에는 새로 승인한 조직만 교체하며, Contingency를 입력하는 연간 계획 확정은 별도입니다.</p>
          </div>
          <RouterLink v-if="masterStatus.latestMasterId" :to="`/planning/annual-masters/${masterStatus.latestMasterId}`" class="button">마스터 확인 →</RouterLink>
        </details>
        <div class="status-tabs" role="group" aria-label="연간 요청 상태"><button v-for="state in [{ value: 'ALL', name: '전체' }, { value: 'NOT_SUBMITTED', name: '회신 대기' }, { value: 'PENDING', name: '검토 대기' }, { value: 'APPROVED', name: '수락 완료' }, { value: 'REJECTED', name: '반려' }]" :key="state.value" :class="{ active: statusFilter === state.value }" @click="statusFilter = state.value">{{ state.name }}</button></div>
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
              <template v-for="target in visibleTargets" :key="target.organization.id"><tr :class="{ expanded: expandedId === String(target.organization.id) }">
                <td class="organization-name"><button class="row-toggle" :aria-expanded="expandedId === String(target.organization.id)" @click="expand(target)">{{ expandedId === String(target.organization.id) ? '▾' : '▸' }} {{ target.organization.name }}</button></td>
                <td>{{ target.organization.code }}</td>
                <td>{{ shortDate(selected.requestedAt) }}</td>
                <td>{{ shortDate(selected.dueAt, true) }}</td>
                <td class="recipient-name"><span v-if="!target.recipients?.length">등록된 담당자 없음</span><span v-for="recipient in target.recipients || []" :key="recipient.id" class="actual-recipient">{{ recipient.name }}<small v-if="recipient.email"> · {{ recipient.email }}</small></span></td>
                <td>
                  <span :class="['request-state', targetStatus(target).tone]">{{ targetStatus(target).label }}</span>
                  <small v-if="target.latestDraftSubmissionId">새 초안 · {{ draftStatus(target.latestDraftParsingStatus) }}</small><small v-else-if="target.draftCount">보존 초안 {{ target.draftCount }}건</small>
                </td>
                <td><button class="detail-link row-toggle" @click="expand(target)">{{ expandedId === String(target.organization.id) ? '접기' : '검토' }}</button></td>
              </tr>
              <tr v-if="expandedId === String(target.organization.id)" class="expanded-detail"><td colspan="7"><p v-if="expandedLoading" role="status">제출본을 불러오는 중…</p><p v-if="actionError" class="alert error" role="alert">{{ actionError }}</p><template v-if="expandedSubmission"><div class="reply-header"><div><strong>{{ target.organization.name }} · {{ expandedSubmission.versionLabel }}</strong><p>{{ dateTime(expandedSubmission.submittedAt || expandedSubmission.uploadedAt) }} 제출</p></div><button class="button" :disabled="actionBusy" @click="downloadSource">⤓ {{ expandedSubmission.originalFilename }}</button></div><SubmissionExplanationPolicy :submission="expandedSubmission" /><details class="reply-explanations"><summary>변경 항목 · 소명 회신 확인</summary><SubmissionReviews :submission="expandedSubmission" @changed="refreshSelected" /></details><div v-if="expandedSubmission.status === 'PENDING'" class="review-actions"><RouterLink :to="targetLink(target)" class="text-button">제출값 · 원본 근거 확인 →</RouterLink><div v-if="!decision" class="button-row"><button class="button" :disabled="actionBusy" @click="decision = 'REJECT'">반려 · 재제출 요청</button><button class="button primary" :disabled="actionBusy || !expandedSubmission.readiness?.canApprove" @click="decision = 'APPROVE'">제출 수락</button></div><form v-else @submit.prevent="decideSubmission"><template v-if="decision === 'REJECT'"><label>반려 사유<textarea v-model="rejectNote" required maxlength="5000" placeholder="수정이 필요한 항목과 사유를 입력해 주세요."></textarea></label><SubmissionRejectionItems v-model="rejectionItems" :submission="expandedSubmission" editable :disabled="actionBusy" /></template><p v-else>이 제출본을 수락하면 연간 마스터 발행 조건에 반영됩니다.</p><div class="button-row"><button type="button" class="button" :disabled="actionBusy" @click="decision = ''">취소</button><button class="button primary" :disabled="actionBusy">{{ actionBusy ? '처리 중…' : decision === 'APPROVE' ? '수락 확정' : '반려 확정' }}</button></div></form></div><p v-if="expandedSubmission.status === 'PENDING' && !expandedSubmission.readiness?.canApprove" class="progress-note">필수 소명 검토를 완료한 뒤 제출을 수락할 수 있습니다.</p></template><template v-else-if="!expandedLoading"><p>아직 최종 제출된 자료가 없습니다.</p><RouterLink v-if="target.latestDraftSubmissionId" :to="draftLink(target)" class="text-button">업로드 상태 · 오류 확인 →</RouterLink></template></td></tr></template>
              <tr v-if="!visibleTargets.length"><td colspan="7" class="progress-empty">이 상태의 조직이 없습니다.</td></tr>
            </tbody>
            <tfoot>
              <tr>
                <td>연간 계획 ({{ selected.year }}년)</td>
                <td>발송 {{ selected.selectedTargetCount ?? selected.targetCount }}곳</td>
                <td>{{ shortDate(selected.requestedAt) }}</td>
                <td>{{ shortDate(selected.dueAt, true) }}</td>
                <td><div class="submission-progress"><progress :value="selected.submittedCount" :max="(selected.selectedTargetCount ?? selected.targetCount) || 1" aria-label="발송 대상 중 최종 제출 조직 수"></progress><span>{{ selected.submittedCount }}/{{ selected.selectedTargetCount ?? selected.targetCount }}</span></div></td>
                <td><span class="summary-state">{{ selected.status === 'OPEN' ? '진행 중' : '마감' }}</span></td>
                <td></td>
              </tr>
            </tfoot>
          </table>
        </div>
        <p class="progress-note">발송 대상 {{ selected.selectedTargetCount ?? selected.targetCount }}곳 중 제출 {{ selected.submittedCount }}곳 · 수락 {{ selected.approvedCount }}곳. 연간 마스터 첫 발행은 전사 {{ selected.requiredOrganizationCount ?? selected.targetCount }}곳의 수락이 필요합니다.</p>
      </template>
      <div v-else-if="!error" class="progress-empty">
        <p>아직 발송한 연간 계획 요청이 없습니다.</p>
        <RouterLink to="/planning/annual-plan/new" class="empty-link">연간 계획 요청 작성 →</RouterLink>
      </div>
      <button v-if="nextCursor" class="more-link" type="button" :disabled="loading" @click="load(true)">이전 요청 더 불러오기</button>
    </section>
    <RequestEditor v-if="selected" :key="`${selected.id}:${selected.stateToken}`" :request="selected" @updated="refreshSelected" @refresh="refreshSelected" />
    <section v-if="selected" class="annual-confirmation"><h2>월간 요청을 위한 연간 계획 확정</h2><p class="notice">조직별 제출 수락으로 연간 마스터가 발행된 뒤, 아래에서 계획 원천과 Contingency를 확정해야 월간 요청을 시작할 수 있습니다. Contingency가 없으면 0을 입력해 확정하세요.</p><AnnualPlanConfirmation :key="`${selected.id}:${masterStatus?.latestMasterId || 'pending'}`" :year="selected.year" :organizations="targetOrganizations" /></section>
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
.draft-link { display:block; margin-top:8px; }
.request-type {
  display: flex;
  align-items: center;
  width: 166px;
  min-height: 63px;
  padding: 19px 21px;
  border: 1px solid #e7e4de;
  border-radius: 12px;
  background: #fff;
  color: #1a1a19;
  font-size: 15px;
  font-weight: 700;
  line-height: 1.45;
}
.request-type:hover { border-color: #a9875b; }
.request-progress {
  min-height: 361px;
  margin-top: 28px;
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
.progress-heading h2 { color: #1a1a19; font-size: 16px; font-weight: 700; line-height: 1.45; }
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
  color: #1a1a19;
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
.progress-table-wrap .recipient-name { color: #a3a5a2; }
.actual-recipient { display:block; color:#4a4844; }
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
.request-state.waiting { color: #7d7d7a; background: #f3f3f2; }
.request-state.submitted { color: #b7791f; background: #fbf2e0; }
.request-state.overdue { color: #b4524a; background: #fbeae8; }
.request-state.approved { color: #385238; background: #e8f4ec; }
.progress-table-wrap td small { display: block; margin-top: 4px; color: #7c808a; font-size: 10px; }
.detail-link { color: #75552f; font-size: 11px; }
.detail-link:hover { text-decoration: underline; }
.progress-table-wrap tfoot td { border-bottom: 0; background: #ffec99; font-size: 12px; }
.submission-progress { display: flex; align-items: center; gap: 8px; white-space: nowrap; }
.submission-progress progress { width: 110px; height: 6px; border: 0; border-radius: 999px; overflow: hidden; appearance: none; background: #efeeea; }
.submission-progress progress::-webkit-progress-bar { background: #efeeea; }
.submission-progress progress::-webkit-progress-value { background: #191919; border-radius: 999px; }
.submission-progress progress::-moz-progress-bar { background: #191919; border-radius: 999px; }
.summary-state { font-size: 11px; }
.progress-note { margin-top: 12px; color: #9a9690; font-size: 11px; }
.master-status { margin-bottom:20px; padding:14px 18px; border:1px solid #e7e4de; border-radius:10px; background:#faf9f6; }.master-status summary { font-size:12px; }.master-status[open]>div { margin-top:14px; }.status-tabs { display:flex; gap:6px; margin-bottom:18px; }.status-tabs button { padding:8px 13px; background:transparent; border-radius:7px; font-size:12px; color:#777; }.status-tabs .active { background:#191919; color:white; }.row-toggle { padding:0; text-align:left; background:transparent; font-weight:inherit; color:inherit; font-size:inherit; }.expanded { background:#faf7f1; }.expanded-detail>td { padding:24px!important; background:#fcfbf9; }.reply-header { display:flex; justify-content:space-between; align-items:center; gap:20px; }.reply-header p { font-size:11px; color:#a3a5a2; margin-top:6px; }.reply-explanations { margin:20px 0; }.reply-explanations summary { font-size:12px; }.review-actions { display:flex; align-items:center; justify-content:space-between; gap:18px; flex-wrap:wrap; }.review-actions form { width:100%; }.review-actions form>.button-row { justify-content:flex-end; margin-top:12px; }.annual-confirmation { padding:18px; border:1px solid var(--line); background:white; border-radius:10px; margin-top:18px; }.annual-confirmation summary { font-size:13px; }.request-type { gap:25px; }
.master-status h3 { margin-bottom:8px; }.master-status p { font-size:13px; line-height:1.7; }.master-status .muted { max-width:720px; margin-top:7px; font-size:12px; }.master-status .button { flex-shrink:0; }
.progress-empty { display: flex; min-height: 220px; flex-direction: column; align-items: center; justify-content: center; gap: 12px; color: #7c808a; font-size: 13px; text-align: center; }
.empty-link { color: #75552f; font-size: 12px; }
.more-link { display: block; margin: 16px auto 0; }
@media (max-width: 900px) {
  .request-progress { padding: 22px 18px; }
  .progress-heading { align-items: flex-start; flex-wrap: wrap; gap: 14px; }
}
@media (max-width: 760px) {
  .master-status { align-items:flex-start; flex-direction:column; }
  .request-progress { margin-top: 24px; }.reply-header { align-items:flex-start; flex-direction:column; }
  .request-types { flex-wrap: wrap; }
  .progress-heading { margin-bottom: 22px; }
  .progress-controls { width: 100%; justify-content: space-between; }
  .progress-controls select { flex: 1; max-width: 230px; }
}
</style>
