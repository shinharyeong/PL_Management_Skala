// These timers model PRD interaction states; they do not authenticate a real user.
export const CODE_TTL_MS = 3 * 60 * 1000
export const RESEND_DELAY_MS = 60 * 1000
export function challengeState(sentAt, now) {
  const remaining = sentAt === null ? 0 : Math.max(0, Math.ceil((sentAt + CODE_TTL_MS - now) / 1000))
  const resendIn = sentAt === null ? 0 : Math.max(0, Math.ceil((sentAt + RESEND_DELAY_MS - now) / 1000))
  return { remaining, resendIn, expired: remaining === 0 }
}
export function clockText(seconds) {
  return `${Math.floor(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}`
}
