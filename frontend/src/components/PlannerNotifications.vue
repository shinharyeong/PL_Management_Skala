<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useSession } from '../stores/session'
import { dateTime, errorText, query } from '../lib/display'

const session = useSession()
const router = useRouter()
const items = ref([])
const summary = ref(null)
const counts = ref({})
const type = ref('ALL')
const unreadOnly = ref(false)
const busy = ref(false)
const reading = ref(false)
const error = ref('')
const cursor = ref(null)
const controller = new AbortController()
const types = [{ key: 'ALL', label: '전체' }, { key: 'SUBMISSION', label: '조직 제출' }, { key: 'REPLY', label: '소명 회신' }, { key: 'OVERDUE', label: '기한 경과' }]
let generation = 0
const groups = computed(() => {
  const grouped = new Map()
  for (const item of items.value) { const label = dayLabel(item.occurredAt); if (!grouped.has(label)) grouped.set(label, []); grouped.get(label).push(item) }
  return [...grouped].map(([label, values]) => ({ label, values, unread: values.filter(value => !value.read).length }))
})

/** 한국 시간의 날짜 묶음 이름을 만든다. Args: value ISO 시각. Returns: 날짜 라벨. Raises: 없음. Side Effects: 없음. */
function dayLabel(value) {
  const fmt = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' })
  const date = new Date(value), key = fmt.format(date), today = fmt.format(new Date()), yesterday = fmt.format(new Date(Date.now() - 86400000))
  const label = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: '2-digit', day: '2-digit', weekday: 'short' }).format(date)
  return `${key === today ? '오늘 · ' : key === yesterday ? '어제 · ' : ''}${label}`
}
/** 유형에 맞는 표시 이름을 반환한다. Args: key API유형. Returns: 이름. Raises: 없음. Side Effects: 없음. */
function typeLabel(key) { return types.find(value => value.key === key)?.label || key }
/** 실제 수신 시각을 시·분으로 표시한다. Args: value ISO시각. Returns: 한국 시각. Raises: 없음. Side Effects: 없음. */
function timeLabel(value) { return new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value)) }

/** 필터에 맞는 알림 페이지와 요약을 조회한다.
 * Args: more 다음 페이지 여부. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: GET·UI·공통 배지 갱신.
 */
async function load(more = false) {
  const current = ++generation; busy.value = true; error.value = ''
  try {
    const data = await session.request(`/api/v1/notifications${query({ type: type.value, unreadOnly: String(unreadOnly.value), limit: 50, cursor: more ? cursor.value : null })}`, { signal: controller.signal })
    if (current !== generation) return
    items.value = more ? [...items.value, ...data.items] : data.items; summary.value = data.summary; counts.value = data.counts; cursor.value = data.nextCursor; session.unreadCount = data.summary.unreadCount
  } catch (cause) { if (current === generation && cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { if (current === generation) busy.value = false }
}

/** 한 건을 읽음 처리한 뒤 실제 검토 대상으로 이동한다.
 * Args: item 알림 DTO. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: 읽음 POST·라우트 이동.
 */
async function open(item) {
  if (reading.value) return
  reading.value = true; error.value = ''
  try { if (!item.read) { const result = await session.request('/api/v1/notifications/read', { method: 'POST', body: JSON.stringify({ eventIds: [item.id] }), signal: controller.signal }); session.unreadCount = result.summary.unreadCount } await router.push(item.href) }
  catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { reading.value = false }
}

/** 조회 시점에 존재하는 미읽음 알림만 모두 읽음으로 기록한다.
 * Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: 페이지 GET·1000건씩 읽음 POST·재조회.
 */
async function readAll() {
  if (reading.value) return
  reading.value = true; error.value = ''
  try {
    const ids = []; let next = null
    do { const data = await session.request(`/api/v1/notifications${query({ unreadOnly: 'true', limit: 200, cursor: next })}`, { signal: controller.signal }); ids.push(...data.items.map(item => item.id)); next = data.nextCursor } while (next)
    for (let i = 0; i < ids.length; i += 1000) await session.request('/api/v1/notifications/read', { method: 'POST', body: JSON.stringify({ eventIds: ids.slice(i, i + 1000) }), signal: controller.signal })
    await load()
  } catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) }
  finally { reading.value = false }
}
/** 유형을 변경하고 첫 페이지를 조회한다. Args: key 유형. Returns: 없음. Raises: 없음. Side Effects: 필터·조회. */
function selectType(key) { type.value = key; load() }
/** 진입 시 조회한다. Args: 없음. Returns: Promise<void>. Raises: 없음. Side Effects: GET. */
async function initialize() { await load() }
/** 화면 자원을 해제한다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 요청 취소. */
function dispose() { generation++; controller.abort() }
onMounted(initialize)
onBeforeUnmount(dispose)
</script>

<template>
  <div class="notification-page">
    <header class="page-heading"><h1>알림 모아보기</h1><button class="button" :disabled="reading || !summary?.unreadCount" @click="readAll">모두 읽음 처리</button></header>
    <div v-if="error" class="alert error" role="alert">{{ error }} <button class="text-button" @click="load()">다시 시도</button></div>
    <section class="notification-summary" aria-label="알림 요약"><article><p>안 읽은 알림</p><strong>{{ summary?.unreadCount ?? '—' }}</strong><span>건</span></article><article><p>회신 검토 대기</p><strong>{{ summary?.replyPendingCount ?? '—' }}</strong><span>건</span></article><article><p>기한 경과 조직</p><strong>{{ summary?.overdueOrganizationCount ?? '—' }}</strong><span>곳</span></article></section>
    <div class="notification-filters"><div class="notification-tabs" role="tablist" aria-label="알림 유형"><button v-for="tab in types" :key="tab.key" role="tab" :aria-selected="type === tab.key" :class="{ active: type === tab.key }" @click="selectType(tab.key)">{{ tab.label }} <span>{{ counts[tab.key] ?? '—' }}</span></button></div><label class="unread-check"><input v-model="unreadOnly" type="checkbox" @change="load()" />안 읽은 알림만</label></div>
    <p v-if="busy && !items.length" class="empty" role="status">알림을 불러오는 중…</p>
    <section v-for="group in groups" :key="group.label" class="notification-group"><h2>{{ group.label }} <span>{{ group.values.length }}건<span v-if="group.unread"> · 안 읽음 {{ group.unread }}</span></span></h2><article v-for="item in group.values" :key="item.id" :class="['notification-card', { unread: !item.read }]"><div class="notification-copy"><div class="notification-title"><i v-if="!item.read" class="unread-dot" aria-label="읽지 않음"></i><span :class="['notification-type', item.type]">{{ typeLabel(item.type) }}</span><strong>{{ item.title }}</strong><small>{{ item.organizationName }} · {{ item.organizationCode }}</small></div><p>{{ item.actorName ? `${item.actorName.replace(' (데모)', '')} 회신` : '제출 현황을 확인해 주세요.' }}</p></div><div :class="['notification-time', { overdue: item.type === 'OVERDUE' }]"><small>{{ item.type === 'OVERDUE' ? '기한' : '수신' }}</small><strong>{{ item.type === 'OVERDUE' ? dateTime(item.occurredAt) + ' 경과' : timeLabel(item.occurredAt) }}</strong></div><button :class="['button', { dark: !item.read && item.type !== 'OVERDUE' }]" :disabled="reading" @click="open(item)">{{ item.type === 'OVERDUE' ? '현황 보기' : '검토하기' }}</button></article></section>
    <div v-if="!busy && !items.length && !error" class="panel empty"><strong>표시할 알림이 없습니다.</strong><p>조직 제출과 소명 회신, 기한 경과 내역이 여기에 표시됩니다.</p></div>
    <button v-if="cursor" class="button load-more" :disabled="busy" @click="load(true)">{{ busy ? '불러오는 중…' : '이전 알림 더 보기' }}</button>
  </div>
</template>

<style scoped>
.notification-summary{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;margin:20px 0}.notification-summary article{padding:18px 20px;background:white;border:1px solid var(--line);border-radius:10px}.notification-summary p{font-size:12px;color:var(--muted);margin-bottom:5px}.notification-summary strong{font-size:26px;color:#b4524a}.notification-summary article:nth-child(2) strong{color:#b7791f}.notification-summary article>span{font-size:11px;color:#a3a5a2;margin-left:7px}.notification-filters{display:flex;justify-content:space-between;align-items:center;gap:14px;margin:20px 0}.notification-tabs{display:flex;flex-wrap:wrap;gap:8px}.notification-tabs button{border:1px solid var(--line);background:white;border-radius:20px;padding:7px 14px;font-size:12px;color:#353734}.notification-tabs button span{color:#9f9f9d;margin-left:3px}.notification-tabs button.active{background:#191919;border-color:#191919;color:white}.notification-tabs button.active span{color:#c3a475}.unread-check{display:flex;align-items:center;gap:6px;white-space:nowrap;font-size:11px;color:#7e827d;font-weight:400}.unread-check input{width:15px;min-height:15px;margin:0;accent-color:#a9875b}.notification-group{margin-bottom:22px}.notification-group h2{font-size:13px;margin:18px 0 10px}.notification-group h2>span{font-size:11px;color:#a3a5a2;font-weight:400;margin-left:8px}.notification-card{border:1px solid var(--line);border-radius:11px;background:white;padding:18px 22px;display:flex;align-items:center;gap:20px;margin-bottom:10px;min-height:80px}.notification-card.unread:first-of-type{border-color:#ba955f}.notification-copy{flex:1;min-width:0}.notification-title{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.notification-title strong{font-size:13px}.notification-title small{color:#a0a39f;font-size:11px}.notification-copy p{color:#7e827d;font-size:11px;margin-top:6px}.notification-time{text-align:right;white-space:nowrap;min-width:70px;font-size:12px}.notification-time small{display:block;font-size:10px;color:#a5a9a4;margin-bottom:4px}.notification-time.overdue strong{color:#b4524a;font-size:11px}.notification-type{border-radius:20px;font-size:10px;padding:3px 8px;color:#6c6c68;background:#f3f3f3}.notification-type.REPLY{background:#fcf2df;color:#b7791f}.notification-type.OVERDUE{background:#fcece9;color:#b4524a}.unread-dot{width:6px;height:6px;border-radius:50%;background:#bd625d;flex-shrink:0}.notification-card .button{font-size:12px;min-height:34px}@media(max-width:1000px){.notification-card{flex-wrap:wrap;gap:12px}.notification-copy{flex-basis:70%}.notification-time{margin-left:auto}}@media(max-width:650px){.notification-summary{gap:8px}.notification-summary article{padding:12px}.notification-filters{align-items:flex-start;flex-direction:column}.notification-card{padding:16px}.notification-copy{flex-basis:100%}.notification-time{margin-left:0;margin-right:auto;text-align:left}}
</style>
