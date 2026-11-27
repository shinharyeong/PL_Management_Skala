export function annualProgress(detail, master) {
  const targets = (detail?.targets || []).filter(target => target.selected)
  const submitted = targets.filter(target => ['PENDING', 'APPROVED'].includes(target.latestSubmission?.status)).length
  const approved = targets.filter(target => target.latestSubmission?.status === 'APPROVED').length
  return [
    {key:'REQUEST', label:'제출 요청', done:detail ? 1 : 0, total:1},
    {key:'SUBMIT', label:'조직 제출', done:submitted, total:targets.length},
    {key:'APPROVE', label:'제출본 승인', done:approved, total:targets.length},
    {key:'MASTER', label:'master 생성', done:master?.state === 'CONFIRMED' && master.items?.length ? 1 : 0, total:1},
  ]
}
