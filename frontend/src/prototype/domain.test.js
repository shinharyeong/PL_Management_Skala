import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createPinia, setActivePinia } from 'pinia'
import { computed, isReactive } from 'vue'
import { usePrototypeStore } from './store.js'
import { matchesConditions, gapFixtures, differenceRate, valuesFor, replyKey, seoulDeadline } from './domain.js'

const conditions = { type:'실적 vs 계획', rateEnabled:true, amountEnabled:true, rate:5, minimum:10000000, baseVersion:'v1.02', compareVersion:'v1.03' }
const reasonsFor = (store, byCell) => Object.fromEntries(store.explanationItems.filter(item => Object.hasOwn(byCell, item.id)).map(item => [replyKey(item), byCell[item.id]]))
test('independent amount/rate filters use exact KRW, AND, and changed items only', () => {
  const match = c => gapFixtures.filter(i => matchesConditions(i,c)).map(i => i.id)
  assert.deepEqual(match(conditions),['A3'])
  assert.deepEqual(match({...conditions,amountEnabled:false}),['A3','A4'])
  assert.deepEqual(match({...conditions,rateEnabled:false}),['A3'])
  assert.deepEqual(match({...conditions,rateEnabled:false,amountEnabled:false}),['A3','A4','A5','A7'])
  assert.equal(matchesConditions({...gapFixtures[0],actual:gapFixtures[0].plan},{...conditions,rateEnabled:false,amountEnabled:false}),false)
  assert.deepEqual(valuesFor(gapFixtures[0],'추정 vs 추정'),{base:108000000,current:112000000})
  assert.equal(differenceRate(0,100),null)
})
test('cart creation is explicit, deduplicated by version snapshot, independent of filter changes', () => {
  setActivePinia(createPinia()); const s = usePrototypeStore()
  assert.equal(s.workspaces.length,0)
  s.addToCart('F10',[s.items.F10[0],s.items.F10[1]],conditions)
  assert.equal(s.workspaces[0].count,2)
  assert.equal(s.items.F10[0].status,'검토 완료')
  assert.equal(s.explanationRequests.length,0)
  s.addToCart('F10',[s.items.F10[0]],conditions)
  assert.equal(s.carts.F10.length,2)
  const changed = {...conditions,rateEnabled:false,minimum:50000000}
  assert.equal(s.carts.F10[0].conditions.rateEnabled,true)
  assert.equal(s.carts.F10[0].conditions.minimum,10000000)
  s.addToCart('F10',[s.items.F10[0]],{...changed,compareVersion:'v1.04'})
  assert.equal(s.carts.F10.length,3)
  assert.equal(s.carts.F10[0].conditions.compareVersion,'v1.03')
})
test('sending explanation changes selected items and removes workspace without approving numbers', () => {
  setActivePinia(createPinia()); const s = usePrototypeStore()
  const original = s.items.F10.map(i => [i.plan,i.actual])
  s.addToCart('F10',[s.items.F10[0],s.items.F10[1]],conditions)
  assert.equal(s.sendExplanation('F10','','2026-12-01T18:00'),false)
  assert.equal(s.workspaces.length,1)
  assert.equal(s.sendExplanation('F10','영향 기간을 확인해 주세요.','2026-12-01T18:00'),true)
  assert.equal(s.workspaces.length,0)
  assert.equal(s.items.F10[0].status,'소명 진행중')
  assert.equal(s.items.F10[2].status,'검토 필요')
  assert.deepEqual(s.items.F10.map(i => [i.plan,i.actual]),original)
  assert.equal(s.explanationRequests[0].items.length,2)
})
test('reply requires all reasons, preserves values, increments shared notification count', () => {
  setActivePinia(createPinia()); const s = usePrototypeStore()
  s.actor = {role:'org',orgId:'F10',name:'최인프 매니저'}
  assert.equal(s.activateOrgRequest('explanation-11'),true)
  assert.equal(s.unread,8)
  assert.equal(s.submitOrgExplanations(reasonsFor(s,{A3:'근거'})),false)
  assert.equal(s.submitOrgExplanations(reasonsFor(s,{A3:'계약 일정 변경',A4:'유지보수 단가 변경'})),true)
  assert.equal(s.unread,9)
  assert.equal(s.items.F10[0].status,'재검토 필요')
  assert.equal(s.items.F10[0].actual,110000000)
  assert.equal(s.comparisonItems('F10',conditions)[0].reason,'계약 일정 변경')
  assert.equal(s.orgRequests.find(r => r.type === 'explanation').status,'제출 완료')
  assert.equal(s.orgHistory.length,1)
  s.readNotification(s.notifications[0].id)
  assert.equal(s.unread,8)
  s.markAllRead(); assert.equal(s.unread,0)
})
test('organization requests bind to their organization and selected request, not the latest request', () => {
  setActivePinia(createPinia()); const s = usePrototypeStore()
  s.addToCart('F10',[s.items.F10[0]],conditions)
  s.sendExplanation('F10','사업부1 요청','2026-12-01T18:00')
  const ownRequest = s.explanationRequests.at(-1).id
  s.addToCart('F20',[s.items.F20[1]],conditions)
  s.sendExplanation('F20','미디어사업부 요청','2026-12-01T18:00')
  const otherRequest = s.explanationRequests.at(-1).id
  s.actor = {role:'org',orgId:'F10'}
  assert.equal(s.visibleOrgRequests.some(r => r.id === otherRequest),false)
  assert.equal(s.activateOrgRequest(otherRequest),false)
  assert.equal(s.activateOrgRequest(ownRequest),true)
  assert.deepEqual(s.explanationItems.map(i => i.id),['A3'])
  s.activeRequest = otherRequest
  assert.deepEqual(s.explanationItems,[])
  assert.equal(s.submitOrgExplanations({A4:'다른 조직 접근'}),false)
  assert.equal(s.orgHistory.length,0)
  s.actor = {role:'planner'}
  assert.equal(s.activateOrgRequest(ownRequest),false)
})
test('request drafts restore independently and subsequent requests never reuse old reasons', () => {
  setActivePinia(createPinia()); const s = usePrototypeStore()
  s.addToCart('F10',[s.items.F10[0]],conditions)
  s.sendExplanation('F10','첫 요청','2026-12-01T18:00')
  const first = s.explanationRequests.at(-1).id
  s.actor = {role:'org',orgId:'F10'}
  s.activateOrgRequest(first)
  const firstKey = replyKey(s.explanationItems[0])
  s.orgReasons[firstKey] = '첫 요청 작성 중'
  s.activateOrgRequest('explanation-11')
  assert.equal(s.orgReasons[firstKey],undefined)
  s.orgReasons[firstKey] = '기본 요청 작성 중'
  s.activateOrgRequest(first)
  assert.equal(s.orgReasons[firstKey],'첫 요청 작성 중')
  assert.equal(s.submitOrgExplanations(s.orgReasons),true)
  assert.equal(s.activateOrgRequest(first),false)
  assert.equal(s.submitOrgExplanations({A3:'중복 회신'}),false)
  s.addToCart('F10',[s.items.F10[0]],conditions)
  s.sendExplanation('F10','두 번째 요청','2026-12-02T18:00')
  const second = s.explanationRequests.at(-1).id
  assert.notEqual(first,second)
  assert.equal(s.visibleOrgRequests.find(r => r.id === second).status,'회신 대기')
  assert.equal(s.activateOrgRequest(second),true)
  assert.deepEqual(s.orgReasons,{})
  assert.equal(s.submitOrgExplanations({}),false)
})
test('historical replies keep reason and value snapshots after later replies and edits', () => {
  setActivePinia(createPinia()); const s = usePrototypeStore()
  s.addToCart('F10',[s.items.F10[0]],conditions)
  s.sendExplanation('F10','첫 요청','2026-12-01T18:00')
  const first = s.explanationRequests.at(-1).id
  s.actor = {role:'org',orgId:'F10'}
  s.activateOrgRequest(first)
  const key = replyKey(s.explanationItems[0])
  s.submitOrgExplanations({[key]:'첫 회신 원문'})
  const firstHistory = s.orgHistory[0]
  s.addToCart('F10',[s.items.F10[0]],conditions)
  s.sendExplanation('F10','다음 요청','2026-12-02T18:00')
  s.activateOrgRequest(s.explanationRequests.at(-1).id)
  s.submitOrgExplanations({[key]:'새 회신 원문'})
  s.orgReasons[key] = '그 뒤 편집'
  s.items.F10[0].actual = 120000000
  assert.equal(firstHistory.requestId,first)
  assert.equal(firstHistory.org,'F10')
  assert.equal(firstHistory.reasons[key],'첫 회신 원문')
  assert.equal(firstHistory.items[0].reason,'첫 회신 원문')
  assert.equal(firstHistory.items[0].actual,110000000)
  assert.equal(s.orgHistory[0].reasons[key],'새 회신 원문')
  assert.equal(new Set(s.notifications.map(n => n.id)).size,s.notifications.length)
})

test('the same cell in different comparisons requires independent reasons and preserves both histories', () => {
  setActivePinia(createPinia()); const s = usePrototypeStore()
  const estimateConditions = { ...conditions, type: '추정 vs 추정' }
  s.addToCart('F10', [s.items.F10[0]], conditions)
  s.addToCart('F10', [s.items.F10[0]], estimateConditions)
  assert.equal(s.carts.F10.length, 2)
  s.sendExplanation('F10', '두 비교의 원인을 각각 설명해 주세요.', '2027-01-01T18:00')
  s.actor = { role: 'org', orgId: 'F10' }
  s.activateOrgRequest(s.explanationRequests.at(-1).id)
  const [actualKey, estimateKey] = s.explanationItems.map(replyKey)
  assert.notEqual(actualKey, estimateKey)
  assert.equal(s.submitOrgExplanations({ A3: '같은 셀이니 하나만 작성' }), false)
  assert.equal(s.submitOrgExplanations({ [actualKey]: '실적 변동' }), false)
  assert.equal(s.submitOrgExplanations({ [actualKey]: '실적 변동', [estimateKey]: '추정 조정' }), true)
  const history = s.orgHistory[0]
  assert.equal(history.items[0].reason, '실적 변동')
  assert.equal(history.items[1].reason, '추정 조정')
  assert.deepEqual(valuesFor(history.items[1], history.items[1].conditions.type), { base: 108000000, current: 112000000 })
  assert.equal(s.comparisonItems('F10', conditions)[0].reason, '실적 변동')
  assert.equal(s.comparisonItems('F10', estimateConditions)[0].reason, '추정 조정')
})

test('review/exclusion status is scoped to organization, versions and comparison type', () => {
  setActivePinia(createPinia()); const s = usePrototypeStore()
  s.addToCart('F10', [s.items.F10[0]], conditions)
  assert.equal(s.comparisonItems('F10', conditions)[0].status, '검토 완료')
  assert.equal(s.comparisonItems('F10', { ...conditions, type: '추정 vs 계획' })[0].status, '검토 필요')
  assert.equal(s.comparisonItems('F10', { ...conditions, compareVersion: 'v1.04' })[0].status, '검토 필요')
  assert.equal(s.comparisonItems('F20', conditions)[0].status, '검토 필요')
  s.removeCartItem('F10', s.carts.F10[0].key)
  assert.equal(s.comparisonItems('F10', conditions)[0].status, '제외됨')
  assert.equal(s.comparisonItems('F10', { ...conditions, type: '추정 vs 계획' })[0].status, '검토 필요')
})

test('monthly comparison context survives panel navigation independently for each organization', () => {
  setActivePinia(createPinia()); const s = usePrototypeStore()
  const first = s.monthlySession('F10')
  first.applied = { ...conditions }
  first.search = '광고'
  first.detailSource = 'results'
  first.activeCell = 'A3'
  const second = s.monthlySession('F20')
  assert.equal(second.applied, null)
  second.draft.type = '추정 vs 추정'
  assert.equal(s.monthlySession('F10').search, '광고')
  assert.equal(s.monthlySession('F10').applied.compareVersion, 'v1.03')
  assert.equal(s.monthlySession('F10').detailSource, 'results')
  assert.equal(s.monthlySession('F10').draft.type, '실적 vs 계획')
})

test('the first monthly session is reactive and applying conditions invalidates computed results', () => {
  setActivePinia(createPinia()); const s = usePrototypeStore()
  const session = computed(() => s.monthlySession(s.activeOrg))
  assert.equal(isReactive(session.value), true)
  const appliedType = computed(() => session.value.applied?.type || null)
  assert.equal(appliedType.value, null)
  session.value.applied = { ...conditions }
  assert.equal(appliedType.value, conditions.type)
  session.value.applied.type = '추정 vs 추정'
  assert.equal(appliedType.value, '추정 vs 추정')
})

test('excluding a reviewed result removes only that comparison from the cart', () => {
  setActivePinia(createPinia()); const s = usePrototypeStore()
  const next = { ...conditions, type: '추정 vs 계획' }
  s.addToCart('F10', [s.items.F10[0]], conditions)
  s.addToCart('F10', [s.items.F10[0]], next)
  s.excludeItems('F10', ['A3'], conditions)
  assert.equal(s.carts.F10.length, 1)
  assert.equal(s.carts.F10[0].conditions.type, next.type)
  assert.equal(s.comparisonItems('F10', conditions)[0].status, '제외됨')
  assert.equal(s.comparisonItems('F10', next)[0].status, '검토 완료')
})

test('deadline parsing uses Seoul time and rejects invalid calendar values', () => {
  assert.equal(seoulDeadline('2027-01-01T18:00'), Date.parse('2027-01-01T09:00:00Z'))
  for (const invalid of ['', '2027-02-30T18:00', '2027-13-01T18:00', '2027-01-01T24:00', '2027-01-01T18:60', null]) {
    assert.equal(Number.isNaN(seoulDeadline(invalid)), true, String(invalid))
  }
})
