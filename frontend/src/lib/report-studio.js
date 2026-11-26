/** 보고서 만들기 화면(데모 구성)의 순수 계산: 금액 표시, 기준 월 상태, 블록 번호·제목, 값 강조, 순서 바꾸기, 서식·배치.
 * DOM·네트워크를 만지지 않는다. 서버 값(숫자)을 새로 만들지 않고 표시 형식만 바꾼다. */

export const STEP_LABELS = ['시작', '구성 확인', '데이터', '소명 분류', '생성·편집']
const CIRCLED = '①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳'

/** 블록 번호 표시: 1~20은 ①~⑳, 그 이상은 숫자. */
export function circled(n) { return n >= 1 && n <= 20 ? CIRCLED[n - 1] : String(n) }

/** 원 단위 값(문자열·숫자)을 억원 한 자리로 표시한다. sign=true 면 +/−를 붙인다. 값이 없으면 '—'. 표시 전용(원 단위 정밀 값은 서버 문자열을 그대로 쓴다). */
export function eok(won, { sign = false, unit = '억원' } = {}) {
  if (won === null || won === undefined || won === '') return '—'
  const n = Number(won)
  if (!Number.isFinite(n)) return '—'
  let u = UNIT_SIZE[unit] ? unit : '억원'
  let r = roundTo(Math.abs(n) / UNIT_SIZE[u].div, UNIT_SIZE[u].digits)
  if (r === 0 && n !== 0 && u !== '원' && u !== '천원' && u !== '만원') { u = '만원'; r = roundTo(Math.abs(n) / UNIT_SIZE[u].div, 0) }   // 고른 단위로 0이 되는 작은 금액은 만원으로 보여 준다
  const body = r.toLocaleString('en-US', { minimumFractionDigits: UNIT_SIZE[u].digits, maximumFractionDigits: UNIT_SIZE[u].digits })
  const suffix = u === '억원' ? '억' : u
  const mark = r === 0 ? '' : n < 0 ? '−' : sign ? '+' : ''
  return `${mark}${body}${suffix}`
}
const UNIT_SIZE = { 원: { div: 1, digits: 0 }, 천원: { div: 1e3, digits: 0 }, 만원: { div: 1e4, digits: 0 }, 백만원: { div: 1e6, digits: 0 }, 억원: { div: 1e8, digits: 1 }, 조원: { div: 1e12, digits: 2 } }
const roundTo = (value, digits) => { const k = 10 ** digits; return Math.round(value * k) / k }

/** 기준 월 칸의 표시: { cls: 'none'|'def'|'prov'|'', label }. state 는 NONE·CONFIRMED·PROVISIONAL. */
export function monthCell(month) {
  const state = month?.state
  if (state === 'CONFIRMED') return { cls: 'def', label: '확정' }
  if (state === 'PROVISIONAL') return { cls: 'prov', label: '잠정' }
  return { cls: 'none', label: '데이터 없음' }
}

/** 블록의 기간 설명. period 가 없으면 기준 월을 따른다. */
export function periodText(block) {
  const p = block?.period
  if (!p || p.rule === 'base') return '기준 월 따름'
  if (p.rule === 'base_offset') { const o = Number(p.offset) || 0; return o === 0 ? '기준 월 따름' : `기준 월 ${o > 0 ? '+' : '−'}${Math.abs(o)}개월` }
  return String(p.rule || '기준 월 따름')
}

/** 블록이 글자만 있는 종류인가(제목·문장·각주). */
export function isTextual(block) { return ['title', 'paragraph', 'text', 'footnote'].includes(block?.kind) }

/** 문장 속 값(숫자·단위)과 못 채운 자리([값]·데이터 없음)를 나눈다. 강조 표시용.
 * Returns: [{text, kind: 'val'|'miss'|'plain'}] — 이어 붙이면 원문과 같다. */
export function highlightParts(text) {
  const s = String(text ?? '')
  const re = /(\[값\]|데이터 없음)|([+\-−△▲▼]?\d[\d,]*(?:\.\d+)?\s?(?:억원|백만원|억|%p|%|건|원|p)?)/g
  const out = []
  let last = 0
  for (const m of s.matchAll(re)) {
    if (m.index > last) out.push({ text: s.slice(last, m.index), kind: 'plain' })
    out.push({ text: m[0], kind: m[1] ? 'miss' : 'val' })
    last = m.index + m[0].length
  }
  if (last < s.length) out.push({ text: s.slice(last), kind: 'plain' })
  return out
}

/** id 블록을 target 블록 앞(after=false)·뒤(after=true)로 옮긴 새 배열. 대상이 없거나 같으면 그대로. */
export function reorder(blocks, id, targetId, after = false) {
  const list = Array.isArray(blocks) ? blocks.slice() : []
  const from = list.findIndex(b => b.id === id)
  if (from < 0 || id === targetId || !list.some(b => b.id === targetId)) return list
  const [item] = list.splice(from, 1)
  const to = list.findIndex(b => b.id === targetId)
  list.splice(after ? to + 1 : to, 0, item)
  return list
}

/** 블록 너비(%)를 15~100으로 맞춘다. 없으면 100. */
export function layoutWidth(block) {
  const w = Number(block?.layout?.w)
  return Number.isFinite(w) ? Math.max(15, Math.min(100, Math.round(w))) : 100
}

/** 서식 객체를 CSS 로. 알려진 값만 쓰고 범위를 벗어난 값은 버린다. */
export function styleCss(block) {
  const s = block?.style || {}
  const css = {}
  if (s.family === 'serif') css.fontFamily = "'Noto Serif KR', 'Noto Sans KR', serif"
  if (Number.isFinite(s.size) && s.size >= 8 && s.size <= 40) css.fontSize = `${s.size}pt`
  if (typeof s.color === 'string' && /^#[0-9a-fA-F]{6}$/.test(s.color)) css.color = s.color
  if (s.bold) css.fontWeight = '700'
  if (s.italic) css.fontStyle = 'italic'
  const deco = [s.underline ? 'underline' : '', s.strike ? 'line-through' : ''].filter(Boolean).join(' ')
  if (deco) css.textDecoration = deco
  if (['uppercase', 'lowercase', 'capitalize'].includes(s.case)) css.textTransform = s.case
  if (['left', 'center', 'right', 'justify'].includes(s.align)) css.textAlign = s.align
  if (Number.isFinite(Number(s.lh)) && Number(s.lh) >= 1 && Number(s.lh) <= 3) css.lineHeight = String(Number(s.lh))
  if (Number.isFinite(Number(s.ls)) && Number(s.ls) >= -2 && Number(s.ls) <= 5) css.letterSpacing = `${Number(s.ls)}px`
  return css
}

/** 서식 한 항목을 바꾼 새 style 객체. 값이 비면 그 항목을 지운다. */
export function withStyle(block, patch) {
  const next = { ...(block?.style || {}), ...patch }
  for (const k of Object.keys(next)) if (next[k] === '' || next[k] === null || next[k] === undefined || next[k] === false) delete next[k]
  return next
}
