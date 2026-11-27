// Action-oriented calculator filters. Approval includes direct approval and accepted explanations.
export const calculatorStages = [
  { label: '전체', tone: 'all' },
  { label: '검토 필요', tone: 'review' },
  { label: '회신 대기', tone: 'progress' },
  { label: '승인됨', tone: 'approved' },
]
export const defaultCalculatorStage = '전체'
export function calculatorStage(status) {
  if (['검토 필요', '검토 완료', '재검토 필요'].includes(status)) return '검토 필요'
  if (status === '소명 진행중') return '회신 대기'
  if (['소명 승인 완료', '제외됨', '승인됨'].includes(status)) return '승인됨'
  return status
}
export const matchesCalculatorStage = (item, stage) => stage === '전체' || calculatorStage(item.status) === stage
export function calculatorStageCounts(items) {
  return Object.fromEntries(calculatorStages.map(({ label }) => [label, items.filter(item => matchesCalculatorStage(item, label)).length]))
}
// Received replies are reviewed in detail; direct approval applies before requesting an explanation.
export const canSelectForReview = item => ['검토 필요', '검토 완료'].includes(item.status)
