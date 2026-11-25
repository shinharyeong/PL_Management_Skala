import { status } from './live-api.js'

// 요청 상세에는 미선택 조직도 포함된다. 행의 존재가 발송을 뜻하지 않는다.
export function monthlyProgress(target) {
  const requested = target?.selected === true
  return {
    monthlyRequested: requested,
    submissionStatus: !requested ? '요청 필요' : status(target.displayStatus),
    requestedAt: requested ? target.requestedAt : null,
    requestDue: requested ? target.dueAt : null,
    latestSubmission: requested ? target.latestSubmission : null,
    fileName: requested ? target.latestSubmission?.label || null : null,
  }
}
