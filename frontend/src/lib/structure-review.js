/** PDF 구성 확인 화면의 순수 계산: 박스 위치, 노란 표시 이유, 종류 변경, 표·목록 표시용 변환.
 * 데이터 조회·AI 호출 없이 PDF 분석 결과(definition.blocks, definition.extraction.pages)만 쓴다. DOM·네트워크를 만지지 않는다. */

/** 노란 표시(needs_review) 코드의 이름. 설명은 REASON_HELP. */
export const REASON_LABELS = {
  "kind_ambiguous": "종류 애매",
  "table_alignment": "표 열 정렬 불확실",
  "footnote_link": "각주 연결 불명",
  "ai_rule_mismatch": "AI·규칙 판단 다름",
  "vlm_uncertain": "모델이 애매하다고 함",
  "text_mismatch": "PDF 글자와 대조 실패",
  "metric_unknown": "지표 사전에 없음",
  "scope_unknown": "범위가 조직에 없음",
  "date_semantics": "날짜 해석",
  "vlm_missed": "VLM 이 놓쳐 규칙으로 채움",
  "table_shape": "표 모양이 PDF 글자와 다름",
  "box_unresolved": "박스 위치 확인 필요",
  "header_ambiguous": "표 머리 병합 확인",
  "split_invalid": "줄 쪼개기 요청 무시",
  "review_unresolved": "박스 점검 후에도 문제 남음",
  "tidy_uncertain": "정리 경계라 그대로 둠"
}

export const REASON_HELP = {
  "kind_ambiguous": "블록 종류(표·문장·목록 등)를 규칙과 모델이 확신하지 못했습니다. 종류를 골라 주세요.",
  "table_alignment": "표의 열이 어느 머리칸 아래인지 위치만으로 확정하기 어렵습니다(합쳐진 칸 등).",
  "footnote_link": "각주(※)가 가리키는 표·문장을 모델과 위치 추측이 서로 다르게 짚었거나 찾지 못했습니다.",
  "ai_rule_mismatch": "그림을 읽은 모델과 PDF 글자 규칙이 서로 다른 결과를 냈습니다. 둘 중 맞는 쪽을 확인하세요.",
  "vlm_uncertain": "모델이 이 블록을 읽으며 스스로 “애매하다”고 표시했습니다(글씨가 작거나 겹침 등).",
  "text_mismatch": "모델이 그림에서 읽은 글자가 PDF 안에 들어 있는 글자(글자 PDF 일 때만 있음)에서 찾아지지 않습니다. 오독이거나 PDF 글자와 표기가 달라서 생깁니다. 그림만 있는 PDF 는 PDF 글자가 없어 이 대조 자체를 하지 않습니다.",
  "metric_unknown": "행·항목 이름이 조회 DB 지표 사전(매출·영업비용·영업이익 등)에 없습니다. 3·5단계에서 데이터로 채울 수 없습니다.",
  "scope_unknown": "조직·범위 이름이 DB 조직 목록에 없습니다.",
  "date_semantics": "‘전월’·‘26.11월’ 같은 날짜·비교 표현이 기준 월 기준으로 무엇을 뜻하는지 확정하지 못했습니다.",
  "header_ambiguous": "표 머리가 여러 줄(병합 칸)인데 어느 칸이 병합됐는지 알 수 없어 열 이름을 확정하지 못했습니다. 이 열들은 값을 추측해 채우지 않고 ‘데이터 없음’으로 남습니다.",
  "box_unresolved": "모델이 그린 박스를 PDF 글자 위치와 맞추지 못했습니다(글자 내용·읽는 순서로 줄을 찾지 못함, 또는 글자가 없는 PDF). 박스가 실제 글자와 어긋날 수 있으니 원본에서 확인하세요.",
  "tidy_uncertain": "코드가 블록을 합치거나 쪼갤지 정리하려다 기준값 근처라 확실하지 않아 그대로 두었습니다(위 이유). 직접 확인해 주세요.",
  "split_invalid": "AI 가 줄을 쪼개 달라고 했지만 없는 줄이거나 없는 후보 번호였습니다. 그 요청은 무시했고 줄은 그대로 두었습니다. 이 줄이 두 구역으로 나뉘어야 하는지 확인하세요.",
  "review_unresolved": "박스를 만든 뒤 AI 가 그림으로 다시 점검했는데(최대 2회 재질문) 문제가 남았습니다. 위 이유(예: 나란한 칸이 한 박스에 합쳐짐·박스에 안 든 글자)를 보고 종류·묶음을 직접 확인하세요.",
  "vlm_missed": "모델 결과에는 없는데 PDF 글자 규칙으로는 뽑힌 제목·문장·각주를 코드가 채워 넣었습니다. 모델이 건너뛴 글자일 수 있어 실제로 필요한 블록인지 확인하세요.",
  "table_shape": "같은 페이지에서 모델이 읽은 표와 PDF 글자로 규칙이 뽑은 표를 칸 글자가 겹치는 정도로 짝지은 뒤 행·열 개수를 비교했습니다. 개수가 다르면 줄이 빠진 쪽을 의심합니다(모델이 줄을 빠뜨렸으면 규칙 표를 쓰고, 반대면 모델 표를 두고 확인을 요청)."
}

/** 블록 정리(코드)가 적용한 규칙 이름. */
export const TIDY_LABELS = {
  "tidy:chart": "막대·막대 이름·축 숫자를 한 차트로 묶음",
  "tidy:cards": "나란한 카드를 카드별로 쪼갬",
  "tidy:table_header": "표 머리 줄을 표에 포함",
  "tidy:headings": "나란한 제목을 제목별로 쪼갬",
  "tidy:hierarchy": "소제목과 그 아래 항목을 한 목록으로 묶음",
  "tidy:footnote": "쪽 하단 각주를 따로 뗌",
  "tidy:labels": "표·차트 옆 잔 라벨을 흡수"
}

export const KIND_LABELS = { title: '제목', paragraph: '문장', text: '문장', footnote: '각주', table: '표', list: '목록', chart: '차트', numbercard: '숫자 카드', metric_table: '손익 표' }
export const TEXT_KINDS = ['title', 'paragraph', 'footnote']
const TEXT_LIKE = ['title', 'paragraph', 'text', 'footnote']

/** 블록 종류의 화면 이름. 모르는 종류는 코드 그대로. */
export function kindLabel(kind) { return KIND_LABELS[kind] || String(kind || '') }

/** 쪽 번호 → 쪽 크기(pt). AI 가 definition.extraction.pages 로 보내며, 없으면(예전 작업) 빈 객체.
 * Args: definition 분석 결과 틀. Returns: { [no]: {width, height} }. */
export function pageSizes(definition) {
  const out = {}
  for (const page of definition?.extraction?.pages || []) {
    const no = Number(page?.no), width = Number(page?.width), height = Number(page?.height)
    if (Number.isInteger(no) && no > 0 && width > 0 && height > 0) out[no] = { width, height }
  }
  return out
}

/** 블록 박스를 쪽 그림 위에 올릴 CSS 위치(퍼센트). bbox 는 쪽 크기(pt) 기준 [x0, y0, x1, y1].
 * Returns: { left, top, width, height } 또는 위치를 알 수 없으면 null(박스를 그리지 않는다). */
export function boxStyle(block, sizes) {
  const size = sizes?.[Number(block?.page)]
  const b = block?.bbox
  if (!size || !Array.isArray(b) || b.length !== 4 || !b.every(Number.isFinite)) return null
  const [x0, y0, x1, y1] = b
  if (x1 <= x0 || y1 <= y0) return null
  const pct = (v, total) => Math.max(0, Math.min(100, (v / total) * 100))
  const left = pct(x0, size.width), top = pct(y0, size.height)
  return { left: `${left.toFixed(3)}%`, top: `${top.toFixed(3)}%`, width: `${(pct(x1, size.width) - left).toFixed(3)}%`, height: `${(pct(y1, size.height) - top).toFixed(3)}%` }
}

/** 노란 표시를 화면용으로: [{code, label, reason, help}]. 모르는 코드는 코드 이름 그대로. */
export function reviewReasons(block) {
  return (Array.isArray(block?.needs_review) ? block.needs_review : []).map(flag => ({
    code: String(flag?.code || ''), label: REASON_LABELS[flag?.code] || String(flag?.code || '확인 필요'), reason: String(flag?.reason || ''), help: REASON_HELP[flag?.code] || '',
  }))
}

/** 코드 정리 적용 내역: ['나란한 카드를 카드별로 쪼갬', ...] */
export function tidyNotes(block) {
  return (Array.isArray(block?.tidy) ? block.tidy : []).map(key => TIDY_LABELS[key] || String(key))
}

/** 이 블록을 바꿀 수 있는 종류. 글자 종류끼리는 자유롭게, 표·목록·차트·숫자 카드는 글자 종류로만(없는 표 내용을 만들 수 없다). */
export function kindChoices(block) {
  const keep = [block?.kind].filter(Boolean)
  return [...new Set([...keep, ...TEXT_KINDS])].map(value => ({ value, label: kindLabel(value) }))
}

/** 표·목록·차트·숫자 카드를 한 덩어리 글자로(종류를 문장으로 바꿀 때 쓴다). */
export function blockPlainText(block) {
  if (TEXT_LIKE.includes(block?.kind)) return String(block.text || '')
  if (block?.kind === 'table') return (block.table?.rows || []).map(row => row.map(cellText).filter(Boolean).join(' ')).filter(Boolean).join(' ')
  if (block?.kind === 'list') return listRows(block.items).map(row => row.text).join(' ')
  if (block?.kind === 'chart') return [block.title, ...(block.categories || []), ...(block.series || [])].filter(Boolean).join(' ')
  if (block?.kind === 'numbercard') return (block.cards || []).map(card => card.label).filter(Boolean).join(' ')
  return String(block?.title || '')
}

/** 종류를 바꾼다(제자리 수정). 허용되지 않는 변경이면 false. 글자 종류로 바꿀 때 본문이 비어 있으면 내용에서 만든다. */
export function changeKind(block, kind) {
  if (!block || block.kind === kind) return false
  if (!TEXT_KINDS.includes(kind)) return false
  if (!TEXT_LIKE.includes(block.kind)) block.text = blockPlainText(block)
  else block.text = String(block.text || '')
  if (kind === 'title' && !block.level) block.level = 2
  if (kind === 'footnote' && !block.marker) block.marker = '※'
  block.kind = kind
  return true
}

function cellText(cell) { return cell?.type === 'text' ? String(cell.text || '') : '' }
const shown = text => text.replace(/\{수치\}/g, '▒')                       // AI 가 숫자를 가린 자리({수치})는 표에서 ▒ 로 보여 준다

/** 표를 화면용으로: { head: [[{text, colspan, rowspan}]], body: [[text]], numberCells }. 병합 머리(header_cells)를 그대로 쓰고, 없으면 첫 header_rows 줄. 숫자 칸은 '▒'. */
export function tableView(block) {
  const table = block?.table || {}
  const rows = Array.isArray(table.rows) ? table.rows : []
  const hr = Math.max(0, Math.min(Number(table.header_rows) || 0, rows.length))
  const head = Array.isArray(table.header_cells) && table.header_cells.length
    ? table.header_cells.map(line => line.filter(cell => !cell.hide).map(cell => ({ text: shown(String(cell.text || '')), colspan: cell.colspan || 1, rowspan: cell.rowspan || 1 })))
    : rows.slice(0, hr).map(row => row.map(cell => ({ text: shown(cellText(cell)), colspan: 1, rowspan: 1 })))
  const body = rows.slice(hr).map(row => row.map(cell => (cell?.type === 'number' ? '▒' : shown(cellText(cell)))))
  return { head, body, numberCells: Number(table.number_cells) || 0 }
}

/** 계층 목록을 줄로 펼친다: [{depth, marker, text}] */
export function listRows(items, depth = 0) {
  const out = []
  for (const item of Array.isArray(items) ? items : []) {
    out.push({ depth, marker: String(item?.marker || ''), text: String(item?.text || '') })
    out.push(...listRows(item?.children, depth + 1))
  }
  return out
}

/** 확인 필요 표시가 있는 블록 수 */
export function reviewCount(blocks) {
  return (Array.isArray(blocks) ? blocks : []).filter(block => block?.needs_review?.length).length
}
