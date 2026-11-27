// 같은 추정 항목이 두 비교 기준에 나타나도 업무 건수는 reviewItemId별로 센다.
export function monthlyReviewItems(byComparison, saved = [], preferred = '실적 vs 계획') {
  const rows = new Map(Object.values(byComparison).flat().map(item => [item.id, item]))
  for (const item of byComparison[preferred] || []) rows.set(item.id, item)
  for (const savedItem of saved) {
    const item = byComparison[savedItem.conditions?.type]?.find(row => row.id === savedItem.id)
    if (item) rows.set(item.id, item)
  }
  return [...rows.values()]
}

export function monthlyExplanationStatus(items) {
  const states = new Set(items.map(item => item.status))
  if (states.has('재검토 필요')) return '소명 회신'
  if (states.has('소명 진행중')) return '소명 진행중'
  if (states.has('검토 필요') || states.has('검토 완료')) return '검토 중'
  if (states.has('소명 승인 완료')) return '소명 승인 완료'
  return states.has('제외됨') ? '제외됨' : '대상 없음'
}
