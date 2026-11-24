<script setup>
import { computed, reactive, ref, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSession } from '../stores/session'
import { query, errorText } from '../lib/display'
import MessageTemplateSelect from './MessageTemplateSelect.vue'

const route = useRoute()
const router = useRouter()
const session = useSession()
const organizations = ref([])
const selectedOrganizations = ref([])
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const requestedYear = Number(route.query.year)
const form = reactive({
  year: Number.isInteger(requestedYear) && requestedYear >= 2000 && requestedYear <= 9999
    ? requestedYear : new Date().getFullYear(),
  dueAt: '',
  message: '연간 계획 양식을 작성하여 회신 기한까지 제출해 주세요. 원본에서 확인이 필요한 항목은 수정한 후 다시 업로드해 주세요.',
})
const controller = new AbortController()
let commandKey = ''
let commandBody = ''

/** 현재 활성 조직을 읽고 발송 대상을 선택할 수 있게 준비한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(API 오류를 화면에 표시).
 * Side Effects: HTTP 조회·대기/오류/조직 상태 갱신; 최초에는 전체 조직을 선택한다.
 */
async function loadOrganizations() {
  loading.value = true
  error.value = ''
  try {
    let cursor = null
    const all = []
    do {
      const page = await session.request(`/api/v1/organizations${query({ active: true, limit: 100, cursor })}`, {
        signal: controller.signal,
      })
      all.push(...page.items)
      cursor = page.nextCursor
    } while (cursor && !controller.signal.aborted)
    if (!controller.signal.aborted) {
      organizations.value = all
      selectedOrganizations.value = all.map(organizationId)
    }
  } catch (cause) {
    if (cause.name !== 'AbortError') error.value = errorText(cause)
  } finally { loading.value = false }
}

/** 수신 대상을 문자열 식별자로 정규화한다.
 * Args: item 조직 DTO. Returns: 조직 ID. Raises: 없음. Side Effects: 없음.
 */
function organizationId(item) { return String(item.id) }

/** 사용자가 선택한 조직만 요청 수신 표에 표시한다.
 * Args: 없음. Returns: 선택 조직 목록. Raises: 없음. Side Effects: 없음.
 */
const recipients = computed(function selectedRecipients() { return organizations.value.filter(isSelected) })

/** 조직이 현재 발송 선택에 포함되는지 판정한다.
 * Args: item 조직 DTO. Returns: 선택 여부. Raises: 없음. Side Effects: 없음.
 */
function isSelected(item) { return selectedOrganizations.value.includes(String(item.id)) }

/** 특정 수신 조직을 제외하거나 전체 선택을 복원한다.
 * Args: id 제외 조직 ID, 없으면 전체 복원. Returns: 없음. Raises: 없음. Side Effects: 발송 선택만 변경한다.
 */
function changeRecipients(id) {
  if (!id) { selectedOrganizations.value = organizations.value.map(organizationId); return }
  const retained = []
  for (const value of selectedOrganizations.value) if (value !== String(id)) retained.push(value)
  selectedOrganizations.value = retained
}

/** 연간 계획 요청을 생성하고 해당 요청의 진행 현황으로 이동한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(API 오류를 화면에 표시).
 * Side Effects: 요청 생성 POST·라우트 이동; 같은 본문 재시도는 멱등키를 재사용한다.
 */
async function createRequest() {
  if (saving.value || loading.value || !selectedOrganizations.value.length || session.actor.role !== 'PLANNER') return
  saving.value = true
  error.value = ''
  try {
    const body = JSON.stringify({
      type: 'ANNUAL_PLAN',
      year: Number(form.year),
      message: form.message || null,
      dueAt: form.dueAt ? new Date(form.dueAt).toISOString() : null,
      organizationIds: selectedOrganizations.value,
    })
    if (body !== commandBody) {
      commandBody = body
      commandKey = crypto.randomUUID()
    }
    const result = await session.request('/api/v1/submission-requests', {
      method: 'POST',
      body,
      headers: { 'Idempotency-Key': commandKey },
      signal: controller.signal,
    })
    await router.push({ path: '/planning/requests', query: { requestId: result.id } })
  } catch (cause) {
    if (cause.name !== 'AbortError') error.value = errorText(cause)
  } finally { saving.value = false }
}

/** 화면 이탈 시 대상 조회와 저장 요청을 취소한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소.
 */
function dispose() { controller.abort() }

onMounted(loadOrganizations)
onBeforeUnmount(dispose)
</script>

<template>
  <div class="annual-create" data-figma-node="2:2261">
    <RouterLink to="/planning/requests" class="back-link">← 연간 계획 관리</RouterLink>
    <h1>연간 계획 요청</h1>

    <section class="annual-card" aria-labelledby="annual-create-title" data-figma-node="2:2300">
      <form @submit.prevent="createRequest">
        <div class="annual-card-heading">
          <h2 id="annual-create-title">연간 계획 요청 작성</h2>
          <label class="year-field" for="annual-year">
            대상 연도
            <input id="annual-year" v-model="form.year" type="number" min="2000" max="9999" required :disabled="saving" />
          </label>
        </div>

        <div v-if="error" class="alert error" role="alert">
          {{ error }}
          <button v-if="!organizations.length" class="text-button" type="button" :disabled="loading" @click="loadOrganizations">다시 조회</button>
        </div>

        <fieldset :disabled="saving">
          <div class="recipient-table-wrap">
            <table>
              <caption class="visually-hidden">연간 계획 요청을 받는 선택 조직</caption>
              <colgroup><col class="organization-column" /><col class="code-column" /><col /></colgroup>
              <thead>
                <tr><th scope="col">조직</th><th scope="col"><span class="visually-hidden">조직 코드</span></th><th scope="col">수신 담당자 (자동 매핑)</th></tr>
              </thead>
              <tbody>
                <tr v-if="loading"><td colspan="3" class="recipient-empty" role="status">대상 조직을 불러오고 있습니다.</td></tr>
                <tr v-else-if="!organizations.length"><td colspan="3" class="recipient-empty">{{ error ? '조직 목록을 다시 확인해 주세요.' : '요청할 수 있는 조직이 없습니다.' }}</td></tr>
                <template v-else>
                  <tr v-for="organization in recipients" :key="organization.id">
                    <td>{{ organization.name }}</td>
                    <td>{{ organization.code }}</td>
                    <td class="recipient-name"><span v-if="!organization.recipients?.length">등록된 담당자 없음</span><span v-for="recipient in organization.recipients || []" :key="recipient.id" class="actual-recipient">{{ recipient.name }}<small v-if="recipient.email"> · {{ recipient.email }}</small></span><button type="button" class="remove-recipient" :aria-label="`${organization.name} 발송 대상에서 제외`" @click="changeRecipients(organization.id)">×</button></td>
                  </tr>
                </template>
              </tbody>
            </table>
          </div>
          <div class="recipient-controls"><span>{{ recipients.length }}개 조직 선택</span><button type="button" class="text-button" @click="changeRecipients()">전체 조직 선택</button></div>

          <label class="due-field" for="annual-due">
            회신 기한
            <input id="annual-due" v-model="form.dueAt" type="datetime-local" />
          </label>

          <MessageTemplateSelect type="ANNUAL_PLAN" :disabled="saving" @select="form.message = $event" />
          <label class="message-field" for="annual-message">
            요청 메시지 (템플릿)
            <textarea id="annual-message" v-model="form.message" maxlength="5000" rows="4"></textarea>
          </label>
        </fieldset>

        <div class="annual-actions">
          <RouterLink to="/planning/requests" class="return-link">요청 현황으로</RouterLink>
          <button class="create-submit" type="submit" :disabled="saving || loading || !selectedOrganizations.length || session.actor.role !== 'PLANNER'">
            {{ saving ? '요청 발송 중…' : `${recipients.length}개 조직에 연간 계획 요청 발송 →` }}
          </button>
        </div>
      </form>
    </section>
  </div>
</template>

<style scoped>
.annual-create h1 {
  margin: 0;
  font-size: 26px;
  line-height: 1.45;
  letter-spacing: -.6px;
  font-weight: 700;
}

.annual-card {
  width: min(740px, 100%);
  min-height: 611px;
  margin-top: 28px;
  padding: 24px 25px 26px;
  border: 1px solid #e7e4de;
  border-radius: 12px;
  background: #fff;
}

.annual-card-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 37px;
  gap: 20px;
  margin-bottom: 24px;
}
.confirmation-year { display:flex; align-items:center; gap:12px; margin:20px 0; max-width:250px; font-size:13px; }.confirmation-year input { width:110px; margin:0; }

.annual-card h2 {
  font-size: 16px;
  font-weight: 700;
  line-height: 1.45;
}

.year-field {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  gap: 10px;
  color: #5f636b;
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
}

.year-field input {
  width: 98px;
  margin: 0;
}

.annual-card fieldset {
  display: flex;
  flex-direction: column;
  gap: 18px;
  min-width: 0;
}

.recipient-table-wrap {
  min-height: 213px;
  max-height: 390px;
  overflow: auto;
  border: 1px solid #e7e4de;
  border-radius: 10px;
}

.recipient-table-wrap table {
  min-width: 500px;
  table-layout: fixed;
  border-collapse: collapse;
  font-size: 13px;
  line-height: 1.45;
}

.organization-column { width: 31%; }
.code-column { width: 30%; }

.recipient-table-wrap th {
  height: 35px;
  padding: 8px 12px;
  color: #5f636b;
  background: #faf9f6;
  font-size: 11.5px;
  font-weight: 500;
  text-align: left;
}

.recipient-table-wrap td {
  height: 44px;
  padding: 11px 12px;
  border: 0;
  vertical-align: middle;
  overflow-wrap: anywhere;
}

.recipient-table-wrap td:first-child { font-weight: 500; }
.recipient-table-wrap .recipient-name { color: #9ea2a9; }
.recipient-name span { color:#4a4844; }.remove-recipient { float:right; background:transparent; color:#9ea2a9; font-size:18px; }.recipient-controls { display:flex; justify-content:space-between; font-size:12px; color:#777; }
.actual-recipient { display:block; }

.recipient-table-wrap .recipient-empty {
  height: 176px;
  text-align: center;
  color: #7c808a;
  font-size: 12px;
  font-weight: 400;
}

.due-field,
.message-field {
  color: #5f636b;
  font-size: 12px;
  font-weight: 500;
  line-height: 1.45;
}

.due-field { width: min(338px, 100%); }

.annual-card input,
.annual-card textarea {
  min-height: 37px;
  padding: 9px 12px;
  border-color: #e7e4de;
  border-radius: 8px;
  color: #16181d;
  background: #fff;
  font-size: 13px;
  line-height: 1.45;
}

.due-field input,
.message-field textarea { margin-top: 6px; }

.message-field textarea {
  height: 112px;
  min-height: 112px;
  resize: vertical;
}

.annual-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  margin-top: 18px;
}

.return-link {
  color: #7c808a;
  font-size: 12px;
}

.return-link:hover { color: #75552f; }

.create-submit {
  min-height: 37px;
  padding: 9px 16px;
  border-radius: 8px;
  background: #a9875b;
  color: #fff;
  font-size: 13px;
  font-weight: 500;
  line-height: 1.45;
}

.create-submit:hover:not(:disabled) { background: #957348; }
.create-submit:disabled { color: #8a8885; background: #efeeea; }

@media (max-width: 760px) {
  .annual-card { margin-top: 48px; padding: 20px; }
  .annual-card-heading { flex-wrap: wrap; gap: 14px; }
  .annual-card h2 { font-size: 15px; }
  .annual-actions { flex-wrap: wrap; }
  .create-submit { margin-left: auto; }
}
</style>
