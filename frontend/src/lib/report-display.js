import { reportCell, reportRows } from '../prototype/report-api.js'

/** 서버 표를 PrimeVue 헤더 그룹과 본문 행으로 나눈다.
 * Args: data PDF 셀 또는 SQL 표를 담은 결과 객체.
 * Returns: headers, rows, width. 원본 십진 문자열과 병합 좌표를 유지한다.
 * Raises: 없음. Side Effects: 없음.
 */
export function reportTableModel(data) {
  const matrix = reportRows(data)
  if (!matrix.length) return { headers: [], rows: [], width: 0 }
  const explicit = data?.header_rows
  let headerCount = Number.isInteger(explicit) ? Math.max(0, explicit) : 1
  if (explicit == null && !data?.table && !data?.rows?.headers) {
    const firstBody = matrix.findIndex(row => !row.some(cell => cell?.hdr))
    if (firstBody > 0) headerCount = firstBody
    if (firstBody < 0) headerCount = matrix.length
  }
  const width = Math.max(0, ...matrix.map(row => row.length))
  return {
    width,
    headers: matrix.slice(0, headerCount).map(row => row.flatMap((cell, index) => cell?.hide ? [] : [{
      key: index, text: reportCell(cell), colspan: cell?.colspan || 1, rowspan: cell?.rowspan || 1,
    }])),
    rows: matrix.slice(headerCount).map((cells, id) => ({ id, cells })),
  }
}

/** 병합 본문 셀의 HTML 속성을 계산한다.
 * Args: row 원본 cells를 가진 표시 행, column 열 좌표.
 * Returns: colspan/rowSpan/style 속성. Raises: 없음. Side Effects: 없음.
 * PrimeVue BodyRow의 rowspan=null 전달이 병합값을 덮지 않도록 DOM 속성명 rowSpan을 사용한다.
 */
export function reportBodyCell(row, column) {
  const cell = row?.cells?.[column]
  return { colspan: cell?.colspan || 1, rowSpan: cell?.rowspan || 1, ...(cell?.hide ? { style: { display: 'none' } } : {}) }
}

/** 차트의 기하 좌표와 원문 표시값을 분리한다.
 * Args: block chart_type과 name/won/text 행을 가진 결과 블록.
 * Returns: type, labels, values, rows. values만 근삿값이며 업무 계산에 쓰지 않는다.
 * Raises: 없음. Side Effects: 없음.
 */
export function reportChartModel(block) {
  const rows = (block?.rows || []).map((row, id) => ({ id, name: String(row.name ?? ''), text: row.text ?? (row.won == null ? '데이터 없음' : String(row.won)), won: row.won }))
  const raw = rows.map(row => row.won == null || row.won === '' || !Number.isFinite(Number(row.won)) ? null : Number(row.won))
  let total = 0
  const values = block?.chart_type === 'waterfall' ? raw.map(value => {
    if (value == null) return null
    const start = total; total += value
    return [start, total]
  }) : raw
  return { rows, labels: rows.map(row => row.name), values, type: block?.chart_type === 'donut' ? 'doughnut' : block?.chart_type === 'line' ? 'line' : 'bar' }
}
