export function explanationEvents(submission, explanations = []) {
  const events = []
  if (submission?.submittedAt) events.push({ key:'submitted', at:submission.submittedAt, label:submission.previousSubmission ? '재제출' : '최초 제출', actor:submission.submittedBy?.name })
  for (const item of [...explanations].sort((a,b)=>a.round-b.round)) {
    if (item.requestedAt) events.push({ key:item.id+'-request', at:item.requestedAt, label:item.round>2?'재소명 요청':'소명 요청', actor:item.requestedBy?.name })
    if (item.answer) events.push({ key:item.id+'-answer', at:item.answeredAt, label:item.requestedAt?'소명 회신':'업로드 시 소명', actor:item.answeredBy?.name })
    if (item.verdict==='ACCEPTED' || item.verdict==='REJECTED') events.push({ key:item.id+'-decision', at:null, label:(item.verdict==='ACCEPTED'?'소명 승인':'소명 보완 요청')+(item.approvedLabel?' · '+item.approvedLabel:''), actor:null })
  }
  for (const event of submission?.history || []) {
    // HIST-02 contains the organization's entire submission chain. Only show
    // decisions owned by this version, not an earlier version's rejection.
    const ownLabel = event.event==='APPROVED' ? submission.approvedLabel : submission.label
    if (!ownLabel || event.label!==ownLabel) continue
    if (event.event==='APPROVED' || event.event==='RESUBMIT_REQUESTED') events.push({ key:event.event+'-'+event.at, at:event.at, label:event.event==='APPROVED'?'제출본 승인':'파일 재제출 요청', actor:event.actor?.name })
  }
  return events
}

// Resolve historical items by actual server IDs, and check the full path before
// attaching a round to a version. Identical account names alone are insufficient.
export async function loadVersionExplanations({ detail, columns, currentSubmission, api }) {
  const versions = columns.length ? columns : currentSubmission ? [{submissionId:currentSubmission.id,label:currentSubmission.label,state:'NEW'}] : []
  return Promise.all(versions.map(async (column, index) => {
    const block = { column, previousLabel:versions[index-1]?.label, submission:null, detail:null, error:'' }
    try {
      const current = String(column.submissionId)===String(currentSubmission?.id)
      block.submission = current ? currentSubmission : await api('/api/v1/submissions/'+column.submissionId)
      if (current) { block.detail = detail; return block }
      const list = await api('/api/v1/review-items?view=ORG&submissionId='+column.submissionId)
      const candidates = (list.groups || []).flatMap(group=>(group.months || []).filter(month=>month.month===detail.month && month.scenario===detail.scenario).flatMap(month=>month.items || []))
      const account = detail.path.split(' › ').at(-2)
      for (const candidate of candidates.filter(item=>item.name===account)) {
        const result = await api('/api/v1/review-items/'+candidate.reviewItemId)
        if (result.path===detail.path && result.month===detail.month && result.scenario===detail.scenario) { block.detail=result; break }
      }
    } catch { block.error='이 버전의 소명 이력을 불러오지 못했습니다.' }
    return block
  }))
}
