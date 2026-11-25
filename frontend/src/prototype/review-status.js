export function submissionReviewStatus(submission) {
 if(!submission)return {label:'미제출',tone:'gray',done:false}
 if(submission.status==='APPROVED')return {label:'검토 완료 · 승인 완료',tone:'blue',done:true}
 if(submission.status==='REJECTED')return {label:submission.displayStatus==='SUPERSEDED'?'이전 제출본 · 재제출됨':'재제출 요청',tone:'red',done:true}
 if(submission.status==='PENDING')return {label:'검토 대기',tone:'amber',done:false}
 return {label:'작성 중',tone:'gray',done:false}
}

// Request dispatch and submission review are separate states.
export function annualTargetStatus(target) {
 if(!target?.selected)return {label:'요청 필요',tone:'gray',done:false}
 if(target.latestSubmission)return submissionReviewStatus(target.latestSubmission)
 if(target.displayStatus==='OVERDUE')return {label:'기한 경과',tone:'red',done:false}
 return {label:'회신 대기',tone:'amber',done:false}
}

export function annualProgressRows(organizations, targets) {
 return (targets ?? organizations.map(organization=>({organization,selected:false}))).map(target=>
  target.selected ? target : {...target,requestedAt:null,dueAt:null,latestSubmission:null})
}
