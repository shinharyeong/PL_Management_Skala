<script setup>
import { reactive, ref, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSession } from '../stores/session'
import { query, errorText } from '../lib/display'
import AnnualPlanConfirmation from './AnnualPlanConfirmation.vue'

const route = useRoute()
const router = useRouter()
const session = useSession()
const organizations = ref([])
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const requestedYear = Number(route.query.year)
const form = reactive({
  year: Number.isInteger(requestedYear) && requestedYear >= 2000 && requestedYear <= 9999
    ? requestedYear : new Date().getFullYear(),
  dueAt: '',
  message: '',
})
const controller = new AbortController()
let commandKey = ''
let commandBody = ''

/** 현재 활성 조직을 모두 읽어 전 조직 요청 대상을 표시한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(API 오류를 화면에 표시).
 * Side Effects: HTTP 조회·대기/오류/조직 상태 갱신; 대상 조직 선택·제외는 허용하지 않는다.
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
    if (!controller.signal.aborted) organizations.value = all
  } catch (cause) {
    if (cause.name !== 'AbortError') error.value = errorText(cause)
  } finally { loading.value = false }
}

/** 연간 계획 요청을 생성하고 해당 요청의 진행 현황으로 이동한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(API 오류를 화면에 표시).
 * Side Effects: 요청 생성 POST·라우트 이동; 같은 본문 재시도는 멱등키를 재사용한다.
 */
async function createRequest() {
  if (saving.value || loading.value || !organizations.value.length || session.actor.role !== 'PLANNER') return
  saving.value = true
  error.value = ''
  try {
    const body = JSON.stringify({
      type: 'ANNUAL_PLAN',
      year: Number(form.year),
      message: form.message || null,
      dueAt: form.dueAt ? new Date(form.dueAt).toISOString() : null,
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
    <h1>연간 계획</h1>
    <label class="confirmation-year">확인 연도<input v-model="form.year" type="number" min="2000" max="9999" :disabled="saving" /></label>
    <AnnualPlanConfirmation :year="Number(form.year)" :organizations="organizations" />

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
              <caption class="visually-hidden">연간 계획 요청을 받는 전체 조직</caption>
              <colgroup><col class="organization-column" /><col class="code-column" /><col /></colgroup>
              <thead>
                <tr><th scope="col">조직</th><th scope="col"><span class="visually-hidden">조직 코드</span></th><th scope="col">수신 담당자 (자동 매핑)</th></tr>
              </thead>
              <tbody>
                <tr v-if="loading"><td colspan="3" class="recipient-empty" role="status">대상 조직을 불러오고 있습니다.</td></tr>
                <tr v-else-if="!organizations.length"><td colspan="3" class="recipient-empty">{{ error ? '조직 목록을 다시 확인해 주세요.' : '요청할 수 있는 조직이 없습니다.' }}</td></tr>
                <template v-else>
                  <tr v-for="organization in organizations" :key="organization.id">
                    <td>{{ organization.name }}</td>
                    <td>{{ organization.code }}</td>
                    <td class="recipient-name">—</td>
                  </tr>
                </template>
              </tbody>
            </table>
          </div>

          <label class="due-field" for="annual-due">
            회신 기한
            <input id="annual-due" v-model="form.dueAt" type="datetime-local" />
          </label>

          <label class="message-field" for="annual-message">
            요청 메시지 (템플릿)
            <textarea id="annual-message" v-model="form.message" maxlength="5000" rows="4"></textarea>
          </label>
        </fieldset>

        <div class="annual-actions">
          <RouterLink to="/planning/requests" class="return-link">요청 현황으로</RouterLink>
          <button class="create-submit" type="submit" :disabled="saving || loading || !organizations.length || session.actor.role !== 'PLANNER'">
            {{ saving ? '요청 발송 중…' : `${organizations.length}개 조직에 연간 계획 요청 발송 →` }}
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
