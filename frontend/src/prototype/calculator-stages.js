// 와이어프레임 4-2: 탭은 서버 상태를 그대로 보여 주고, '검토 필요' 탭에는 선택해 둔 항목(검토 완료)도 남긴다.
export const calculatorStages = [
  { label: '검토 필요', tone: 'review' },
  { label: '소명 진행중', tone: 'progress' },
  { label: '재검토 필요', tone: 'recheck' },
  { label: '소명 승인 완료', tone: 'approved' },
  { label: '제외됨', tone: 'excluded' },
  { label: '전체', tone: 'all' },
]
export const defaultCalculatorStage = '검토 필요'
export function calculatorStage(status) {
  return status === '검토 완료' ? '검토 필요' : status
}
export const matchesCalculatorStage = (item, stage) => stage === '전체' || calculatorStage(item.status) === stage
export function calculatorStageCounts(items) {
  return Object.fromEntries(calculatorStages.map(({ label }) => [label, items.filter(item => matchesCalculatorStage(item, label)).length]))
}
// Server SELECT/EXCLUDE actions are allowed before a request, but not on a received reply.
export const canSelectForReview = item => ['검토 필요', '검토 완료'].includes(item.status)
