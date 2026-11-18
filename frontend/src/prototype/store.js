import { defineStore } from 'pinia'
import { gapFixtures, seoulPeriod, snapshotKey } from './domain.js'

const period = seoulPeriod()
const periodText = `${period.year}년 ${period.month}월`
export const usePrototypeStore = defineStore('figma-prototype', {
  state: () => ({
    actor: null,
    activeOrg: 'F10',
    activeRequest: 'monthly-11',
    orgReasons: {},
    orgDrafts: {},
    requestSequence: 0,
    orgHistory: [],
    orgRequests: [
      { id: 'monthly-11', org: 'F10', type: 'monthly', title: `${period.month}월 실적·추정 제출`, period: periodText, due: `${period.year}-11-20T18:00`, status: '회신 대기', message: '월간 시트 양식에 실적과 추정을 작성해 제출해 주세요.', fileName: null },
      { id: 'annual-2027', org: 'F10', type: 'annual', title: `${period.year + 1}년 연간 계획 제출`, period: `${period.year + 1}년`, due: `${period.year}-11-27T18:00`, status: '회신 대기', message: '연간 계획 양식에 계획 금액을 작성해 제출해 주세요.', fileName: null },
      { id: 'explanation-11', org: 'F10', type: 'explanation', title: `${period.month}월 소명 요청`, period: periodText, due: `${period.year}-11-20T18:00`, status: '회신 대기', message: '선택 항목의 차이 원인과 영향 기간을 확인해 주세요.', fileName: null, items: gapFixtures.slice(0, 2).map(item => ({ ...item, status: '소명 진행중' })) },
    ],
    organizations: [
      { id: 'F10', name: '사업부1', code: 'F10', author: '최인프 매니저', department: '네트워크인프라부문', submissionStatus: '제출 완료', explanationStatus: '요청 없음', fileName: '월간시트_사업부1.xlsx', message: '11월 실적 및 추정을 제출합니다. 세부 변경 내역은 파일을 확인해 주세요.' },
      { id: 'F20', name: '미디어사업부', code: 'F20', author: '박미디 매니저', department: '미디어사업부', submissionStatus: '제출 완료', explanationStatus: '소명 회신', fileName: '월간시트_미디어.xlsx', message: '11월 실적·추정 제출합니다. 계약 일정 변경분을 반영했습니다.' },
      { id: 'F30', name: '플랫폼사업부', code: 'F30', author: '이플랫 매니저', department: '플랫폼사업부', submissionStatus: '기한 경과', explanationStatus: '요청 없음', fileName: null, message: '' },
      { id: 'F40', name: 'B2B사업부', code: 'F40', author: '정비투 매니저', department: 'B2B사업부', submissionStatus: '회신 대기', explanationStatus: '요청 없음', fileName: null, message: '' },
    ],
    items: Object.fromEntries(['F10', 'F20'].map(org => [org, gapFixtures.map(i => ({ ...i }))])),
    carts: {},
    explanationRequests: [],
    notifications: [
      { id: 'n1', type: '소명 회신', title: `${period.month}월 매출 Gap 소명 회신 도착`, org: '미디어사업부', code: 'F20', author: '박미디 매니저 회신', time: '09:12', read: false, target: 'monthly' },
      { id: 'n2', type: '조직 제출', title: `${period.month}월 실적·추정 제출 완료`, org: '사업부1', code: 'F10', author: '최인프 매니저 회신', time: '09:05', read: false, target: 'monthly' },
      { id: 'n3', type: '기한 경과', title: '월간 시트 회신 기한 경과', org: '플랫폼사업부', code: 'F30', author: '', time: '기한 11.17 18:00 경과', read: false, target: 'monthly' },
      { id: 'n4', type: '조직 제출', title: '연간 계획 제출 완료', org: '미디어사업부', code: 'F20', author: '박미디 매니저 회신', time: '08:40', read: false, target: 'annual' },
      { id: 'n5', type: '조직 제출', title: '연간 계획 제출 완료', org: '사업부1', code: 'F10', author: '최인프 매니저 회신', time: '어제 16:10', read: false, target: 'annual' },
      { id: 'n6', type: '소명 회신', title: '인건비 Gap 소명 회신 도착', org: '사업부1', code: 'F10', author: '최인프 매니저 회신', time: '어제 15:20', read: false, target: 'monthly' },
      { id: 'n7', type: '기한 경과', title: '연간 계획 회신 기한 경과', org: 'B2B사업부', code: 'F40', author: '', time: '기한 11.17 18:00 경과', read: false, target: 'annual' },
      { id: 'n8', type: '조직 제출', title: '월간 시트 제출 완료', org: '미디어사업부', code: 'F20', author: '박미디 매니저 회신', time: '어제 14:10', read: false, target: 'monthly' },
    ],
  }),
  getters: {
    unread: state => state.notifications.filter(n => !n.read).length,
    currentOrg: state => state.organizations.find(o => o.id === state.activeOrg) || state.organizations[0],
    workspaces: state => Object.entries(state.carts).filter(([, rows]) => rows.length).map(([org, rows]) => ({ org, name: state.organizations.find(o => o.id === org)?.name || org, count: rows.length })),
    visibleOrgRequests: state => state.actor?.role === 'org' && state.actor.orgId
      ? state.orgRequests.filter(request => request.org === state.actor.orgId)
      : [],
    activeOrgRequest() {
      return this.visibleOrgRequests.find(request => request.id === this.activeRequest) || null
    },
    explanationItems() {
      const request = this.activeOrgRequest
      return request?.type === 'explanation' && request.status !== '제출 완료' ? request.items || [] : []
    },
  },
  actions: {
    markAllRead() { this.notifications.forEach(n => { n.read = true }) },
    readNotification(id) { const n = this.notifications.find(n => n.id === id); if (n) { n.read = true; this.activeOrg = n.code } },
    addToCart(org, items, conditions) {
      const rows = this.carts[org] || []
      for (const item of items) {
        const key = snapshotKey(org, periodText, conditions.baseVersion, conditions.compareVersion, conditions.type, item.id)
        if (!rows.some(r => r.key === key)) rows.push({ ...item, key, conditions: { ...conditions }, period: periodText })
      }
      this.carts[org] = rows
      const addedIds = items.map(i => i.id)
      this.items[org]?.filter(i => addedIds.includes(i.id)).forEach(i => { i.status = '검토 완료' })
    },
    excludeItems(org, ids) { (this.items[org] || []).filter(i => ids.includes(i.id)).forEach(i => { i.status = '제외됨' }) },
    removeCartItem(org, key) {
      const item = (this.carts[org] || []).find(i => i.key === key)
      this.carts[org] = (this.carts[org] || []).filter(i => i.key !== key)
      if (item && !this.carts[org].some(i => i.id === item.id)) this.items[org]?.filter(i => i.id === item.id).forEach(i => { i.status = '제외됨' })
    },
    sendExplanation(org, message, due) {
      const items = this.carts[org] || []
      if (!items.length || !message.trim() || !due) return false
      const id = `request-${Date.now()}-${++this.requestSequence}`
      const request = {
        id, org, type: 'explanation', title: `${period.month}월 소명 요청`, period: periodText,
        message: message.trim(), due, status: '회신 대기', fileName: null,
        items: items.map(i => ({ ...i, conditions: i.conditions ? { ...i.conditions } : undefined, status: '소명 진행중' })),
      }
      this.explanationRequests.push(request)
      this.orgRequests.unshift({ ...request })
      this.orgDrafts[id] = {}
      const ids = items.map(i => i.id)
      this.items[org]?.filter(i => ids.includes(i.id)).forEach(i => { i.status = '소명 진행중' })
      const organization = this.organizations.find(o => o.id === org)
      if (organization) organization.explanationStatus = '소명 진행중'
      this.carts[org] = []
      return true
    },
    activateOrgRequest(id) {
      const next = this.visibleOrgRequests.find(request => request.id === id && request.status !== '제출 완료')
      if (!next) return false
      const current = this.activeOrgRequest
      if (current?.type === 'explanation' && current.status !== '제출 완료') {
        this.orgDrafts[current.id] = { ...this.orgReasons }
      }
      this.activeRequest = id
      this.orgReasons = next.type === 'explanation' ? { ...(this.orgDrafts[id] || {}) } : {}
      return true
    },
    submitOrgExplanations(reasons) {
      const request = this.activeOrgRequest
      const items = this.explanationItems
      if (!request || request.type !== 'explanation' || !items.length || items.some(i => typeof reasons[i.id] !== 'string' || !reasons[i.id].trim())) return false
      const submittedReasons = Object.fromEntries(items.map(i => [i.id, reasons[i.id].trim()]))
      this.orgReasons = { ...submittedReasons }
      this.orgDrafts[request.id] = { ...submittedReasons }
      request.status = '제출 완료'
      const linked = this.explanationRequests.find(item => item.id === request.id)
      if (linked) linked.status = '제출 완료'
      const org = request.org
      this.items[org]?.filter(i => items.some(e => e.id === i.id)).forEach(i => { i.status = '재검토 필요'; i.reason = submittedReasons[i.id] })
      const organization = this.organizations.find(o => o.id === org)
      if (organization) organization.explanationStatus = '소명 회신'
      this.orgHistory.unshift({
        id: `reply-${request.id}`, requestId: request.id, org,
        title: `${period.month}월 소명 회신`, fileName: null, fileDisposition: '기존 월간 제출본 유지',
        submittedAt: new Date().toISOString(), status: '검토 대기', type: 'explanation',
        reasons: { ...submittedReasons },
        items: items.map(item => ({ id: item.id, name: item.name, cell: item.cell, plan: item.plan, actual: item.actual, reason: submittedReasons[item.id] })),
      })
      this.notifications.unshift({ id: `noti-${request.id}`, type: '소명 회신', title: `${period.month}월 선택 항목 소명 회신 도착`, org: organization?.name || '사업부1', code: org, author: this.actor?.name || '조직 담당자 회신', time: '방금', read: false, target: 'monthly' })
      return true
    },
  },
})
