export const needsProcessing = item => ['검토 필요','검토 완료','소명 진행중','재검토 필요'].includes(item.status)
export const awaitingExplanationReview = item => ['소명 진행중','재검토 필요'].includes(item.status)

export function syncWorkspace(saved, latest, conditions) {
  const fresh = new Map(latest.map(item=>[item.id,item]))
  const rows = saved.flatMap(item=>{
    const current=fresh.get(item.id)
    return current ? needsProcessing(current) ? [{...current,conditions:item.conditions,restored:item.restored}] : [] : needsProcessing(item) ? [item] : []
  })
  const existing=new Set(rows.map(item=>item.id))
  // Sent requests already represent an explicitly created workspace. SELECTED
  // rows alone still do not create a new tab merely by calculating.
  for(const item of latest.filter(awaitingExplanationReview)){
    if(!existing.has(item.id))rows.push({...item,conditions:{...conditions,...item.conditions},restored:true})
  }
  return rows
}
