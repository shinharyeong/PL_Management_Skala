const workflowStates = {
 REQUEST_NEEDED:{label:'요청 필요',tone:'gray',done:false},
 WAITING:{label:'회신 대기',tone:'blue',done:false},
 REVIEW_NEEDED:{label:'검토 필요',tone:'amber',done:false},
 APPROVED:{label:'승인 완료',tone:'green',done:true},
}
export function organizationWorkflowStatus(target) {
 if(workflowStates[target?.workflowStatus])return workflowStates[target.workflowStatus]
 if(!target?.selected)return workflowStates.REQUEST_NEEDED
 const submission=target.latestSubmission
 if(submission?.status==='APPROVED')return workflowStates.APPROVED
 if(!submission||submission.status==='REJECTED')return workflowStates.WAITING
 if(target.explanationStatus==='소명 진행중')return workflowStates.WAITING
 return workflowStates.REVIEW_NEEDED
}
export function submissionReviewStatus(submission) {
 return organizationWorkflowStatus({selected:true,latestSubmission:submission})
}
export const annualTargetStatus=organizationWorkflowStatus
export function notificationWorkflowStatus(item) {
 return workflowStates[item.workflowStatus] || (item.type==='OVERDUE'?workflowStates.WAITING:workflowStates.REVIEW_NEEDED)
}

export function annualProgressRows(organizations, targets) {
 return (targets ?? organizations.map(organization=>({organization,selected:false}))).map(target=>
  target.selected ? target : {...target,requestedAt:null,dueAt:null,latestSubmission:null})
}
