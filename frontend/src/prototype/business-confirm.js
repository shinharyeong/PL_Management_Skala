import { shallowRef } from 'vue'

export const businessConfirmation = shallowRef(null)
let pending
function cancelled() { return new DOMException('작업을 취소했습니다.', 'AbortError') }
export function confirmBusinessAction(options) {
  if (pending) return Promise.reject(cancelled())
  return new Promise((resolve,reject) => {
    pending = {resolve,reject}
    businessConfirmation.value = {confirmLabel:'확인', ...options}
  })
}
export function settleBusinessConfirmation(accepted) {
  const request = pending
  pending = null
  businessConfirmation.value = null
  if (accepted) request?.resolve(true)
  else request?.reject(cancelled())
}
