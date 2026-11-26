// Shared text is stored by the backend, never in browser-only storage.
export function mergeSharedCopy(initial, rows) {
  const result=structuredClone(initial)
  for(const row of rows || []) {
    const parts=typeof row.key==='string'?row.key.split('.'):[]
    const [group,key]=parts
    if(parts.length===2 && Object.hasOwn(initial,group) && Object.hasOwn(initial[group],key)
        && typeof row.value==='string' && row.value.trim() && row.value.length<=10000) result[group][key]=row.value
  }
  return result
}
export function createSharedCopyClient(initial, request) {
  let revisions=new Map(), snapshot=structuredClone(initial)
  async function read() {
    const rows=await request('/api/v1/ui-copy')
    if(!Array.isArray(rows)) throw new Error('공통 문구 응답을 확인해 주세요.')
    revisions=new Map(rows.map(row=>[row.key,row.revision]));snapshot=mergeSharedCopy(initial,rows)
    return snapshot
  }
  async function change(body) {
    const [group,key,...extra]=body.key.split('.')
    if(extra.length || !Object.hasOwn(initial,group) || !Object.hasOwn(initial[group],key)) throw new Error('편집할 수 없는 문구입니다.')
    if(snapshot[group][key]!==body.expected) throw new Error('문구가 변경됐습니다. 다시 열어 주세요.')
    const saved=await request('/api/v1/dev/ui-copy',{method:'PUT',body:JSON.stringify({key:body.key,value:body.value,expectedRevision:revisions.get(body.key)||0})})
    revisions.set(body.key,saved.revision);snapshot[group][key]=saved.value
    return saved
  }
  return {read,change}
}
