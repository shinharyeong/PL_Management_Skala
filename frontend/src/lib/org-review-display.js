// Drafts stay in tab memory, isolated by actor, submission and server state token.
const drafts = new Map()
export function readReviewDraft(key, fallback = '') { return drafts.get(key) ?? fallback }
export function saveReviewDraft(key, value) {
  if (!key) return
  drafts.set(key, value)
  if (drafts.size > 500) drafts.delete(drafts.keys().next().value)
}
export function clearReviewDraft(key) { drafts.delete(key) }
export const scenarioLabel = value => ({PLAN:'계획', ACTUAL:'실적', ESTIMATE:'추정'}[value] || value)

// Exact decimal arithmetic: missing values stay missing; no floating-point money parsing.
export function resubmissionChange(current, previous) {
  const parse = value => value == null ? null : /^(-?)(\d+)(?:\.(\d+))?$/.exec(String(value))
  const a = parse(current), b = parse(previous)
  if (!a || !b) return {diff:null, rate:null}
  const scale = Math.max(a[3]?.length || 0, b[3]?.length || 0)
  const number = m => BigInt(m[2] + (m[3] || '').padEnd(scale,'0')) * (m[1] ? -1n : 1n)
  const av = number(a), bv = number(b), delta = av-bv
  const negative = delta < 0n
  const digits = (negative ? -delta : delta).toString().padStart(scale+1,'0')
  const diff = (negative ? '-' : '') + (scale ? digits.slice(0,-scale)+'.'+digits.slice(-scale) : digits)
  const base = bv < 0n ? -bv : bv
  if (!base) return {diff,rate:null}
  const scaled = (negative ? -delta : delta)*1000n
  const rounded = (scaled*2n+base)/(2n*base)
  return {diff,rate:(negative && rounded ? '-' : '')+(rounded/10n)+'.'+(rounded%10n)}
}
