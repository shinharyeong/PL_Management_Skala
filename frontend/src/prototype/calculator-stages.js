// Calculator filters group workflow states without changing their server meaning.
export const calculatorStages = [
  { label: '전체', tone: 'all' },
  { label: '검토중', tone: 'review' },
  { label: '회신 검토', tone: 'recheck' },
  { label: '소명 중', tone: 'progress' },
  { label: '제외됨', tone: 'excluded' },
  { label: '승인됨', tone: 'approved' },
]
export const defaultCalculatorStage = '전체'
export function calculatorStage(status) {
  if (['검토 필요', '검토 완료'].includes(status)) return '검토중'
  if (status === '재검토 필요') return '회신 검토'
  if (status === '소명 진행중') return '소명 중'
  if (status === '소명 승인 완료') return '승인됨'
  return status
}
export const matchesCalculatorStage = (item, stage) => stage === '전체' || calculatorStage(item.status) === stage
export function calculatorStageCounts(items) {
  return Object.fromEntries(calculatorStages.map(({ label }) => [label, items.filter(item => matchesCalculatorStage(item, label)).length]))
}
// Server SELECT/EXCLUDE actions are allowed before a request, but not on a received reply.
export const canSelectForReview = item => ['검토 필요', '검토 완료'].includes(item.status)
