import {subtract, percent} from './exact-money.js'
import {loadVersionExplanations} from './explanation-history.js'

export function cellChange(row, columnCount) {
  if(columnCount < 2) return {diff:null,rate:null}
  const previous=row.values?.[columnCount-2], current=row.values?.[columnCount-1]
  if(previous==null || current==null) return {diff:null,rate:null}
  return {diff:subtract(current,previous),rate:percent(previous,current)}
}

// Keep each source text tied to its submission and source cell. Never copy the
// last column's row.basis into older versions or substitute an explanation.
export async function loadCellVersionEvidence({detail,columns,currentSubmission,evidence,api}) {
  const historical=columns.filter(c=>String(c.submissionId)!==String(currentSubmission?.id))
  const blocks=historical.length ? await loadVersionExplanations({detail,columns:historical,currentSubmission,api}) : []
  return Object.fromEntries(await Promise.all(columns.map(async column=>{
    const id=String(column.submissionId)
    try {
      let source
      if(id===String(currentSubmission?.id)) source=evidence
      else {
        const block=blocks.find(b=>String(b.column.submissionId)===id)
        if(block?.error || !block?.detail) return [id,{state:'unavailable',texts:{}}]
        source=await api('/api/v1/review-items/'+block.detail.id+'/evidence')
      }
      if(!source || !Array.isArray(source.items)) return [id,{state:'unavailable',texts:{}}]
      const texts={}
      for(const item of source.items) {
        const cell=item.sourceSheet+'!'+item.sourceCell
        if(item.description!=null && item.description!=='') (texts[cell] ||= []).push(item.description)
      }
      return [id,{state:'ready',texts}]
    } catch { return [id,{state:'unavailable',texts:{}}] }
  })))
}
