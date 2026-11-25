export function canAnswerExplanation(role, submissionStatus, explanation) {
  if(role!=='ORG_MANAGER' || !explanation || explanation.verdict!=='PENDING' || explanation.inheritedFrom)return false
  return submissionStatus==='DRAFT' ? explanation.round===1 : submissionStatus==='PENDING' && !!explanation.requestedAt
}
