const dateParts = value => {
  const date = new Date(value)
  if (!value || !Number.isFinite(date.getTime())) return null
  return Object.fromEntries(new Intl.DateTimeFormat('en-US', {timeZone:'Asia/Seoul',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23',weekday:'short'}).formatToParts(date).map(p=>[p.type,p.value]))
}
export function dateTime(value, {year=true} = {}) {
  const p=dateParts(value)
  return p ? `${year?p.year+'.':''}${p.month}.${p.day} ${p.hour}:${p.minute}` : '—'
}
export function notificationTime(item) {
  const p=dateParts(item.occurredAt)
  if (!p) return '—'
  return item.type==='OVERDUE' ? `기한 ${p.month}.${p.day} ${p.hour}:${p.minute} 경과` : `수신 ${p.hour}:${p.minute}`
}
export function notificationDate(date, now = new Date()) {
  const seoul = new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Seoul',year:'numeric',month:'2-digit',day:'2-digit'})
  const today=seoul.format(now), yesterday=seoul.format(new Date(now.getTime()-86400000))
  const prefix=date===today?'오늘 · ':date===yesterday?'어제 · ':''
  const d=new Date(date+'T12:00:00+09:00')
  const weekday=new Intl.DateTimeFormat('ko-KR',{timeZone:'Asia/Seoul',weekday:'short'}).format(d)
  return `${prefix}${date.slice(5).replace('-','.')} (${weekday})`
}
// Cursor pages can split the same date. Keep one date heading and one card per ID.
export function mergeNotificationGroups(current,incoming) {
  const groups=new Map()
  for(const group of [...current,...incoming]) {
    const old=groups.get(group.date)
    groups.set(group.date,{...group,items:[...new Map([...(old?.items||[]),...group.items].map(item=>[item.id,item])).values()]})
  }
  return [...groups.values()].sort((a,b)=>b.date.localeCompare(a.date))
}
export const statusTone = label => /기한 경과|반려/.test(label)?'red':/소명|회신 대기|검토 대기/.test(label)?'purple':/승인|제출 완료|확정|수락/.test(label)?'blue':'gray'
