import { reportCell, reportRows } from './report-api.js'

/** 확인된 미리보기 값을 PNG용 문장으로 펼친다.
 * Args: definition 자료 틀, result RPT-10 응답, base 기준 연월.
 * Returns: 계산 없이 서버 표시값을 유지한 문자열 목록.
 * Raises: Error 미리보기 데이터가 없는 경우.
 * Side Effects: 없음.
 */
export function reportLines(definition, result, base) {
  if (!result || !Array.isArray(result.blocks)) throw new Error('먼저 현재 데이터로 보고서를 채워 주세요.')
  const state = { CONFIRMED: '확정', PROVISIONAL: '잠정', NONE: '데이터 없음' }[result.dataStatus] || result.dataStatus
  const lines = [definition.name, `${base.year}년 ${base.month}월 · ${state}`, '']
  for (const block of definition.blocks) {
    const data = result.blocks.find(item => item.id === block.id)
    if (block.title) lines.push(block.title)
    if (!data) { lines.push('데이터 없음', ''); continue }
    if (data.text) lines.push(data.text)
    if (data.missing) lines.push('일부 데이터 없음')
    for (const row of reportRows(data)) lines.push(row.filter(cell => !cell?.hide).map(reportCell).join('  |  '))
    for (const card of data.cards || []) lines.push(`${card.label}: ${card.value ?? '데이터 없음'}${card.value == null ? '' : ` ${card.unit || ''}`}${card.delta ? ` (${card.delta})` : ''}`)
    if (block.kind === 'chart') for (const row of data.rows || []) lines.push(`${row.name}: ${row.text ?? '데이터 없음'}`)
    for (const item of data.items || []) lines.push(reportCell(item))
    lines.push('')
  }
  return lines
}

/** 보고서 내용을 의존 라이브러리 없이 PNG로 렌더링한다.
 * Args: definition 자료 틀, result 조회 결과, base 기준 연월.
 * Returns: PNG Blob Promise. 큰 표는 28,000px 높이 한도에서 중단한다.
 * Raises: Error 캔버스 미지원·너무 큰 결과·인코딩 실패.
 * Side Effects: 브라우저 메모리 캔버스만 생성; 다운로드는 호출자가 수행한다.
 */
export async function reportPng(definition, result, base) {
  const canvas = document.createElement('canvas'), context = canvas.getContext('2d')
  if (!context) throw new Error('이 브라우저에서 PNG를 만들 수 없습니다.')
  const width = 1400, padding = 60, lineHeight = 34, lines = []
  context.font = '22px sans-serif'
  for (const source of reportLines(definition, result, base)) {
    for (const paragraph of String(source).split('\n')) {
      let line = ''
      for (const character of paragraph) {
        if (line && context.measureText(line + character).width > width - padding * 2) { lines.push(line); line = '' }
        line += character
      }
      lines.push(line)
    }
  }
  const height = padding * 2 + lines.length * lineHeight
  if (height > 28000) throw new Error('PNG 한 장으로 만들기에는 내용이 많습니다. XLSX 또는 인쇄·PDF 저장을 사용해 주세요.')
  canvas.width = width; canvas.height = Math.max(300, height)
  context.fillStyle = '#fff'; context.fillRect(0, 0, width, canvas.height)
  context.font = '22px sans-serif'; context.fillStyle = '#172033'; context.textBaseline = 'top'
  lines.forEach((line, index) => context.fillText(line, padding, padding + index * lineHeight))
  return new Promise((resolve, reject) => canvas.toBlob(blob => blob ? resolve(blob) : reject(new Error('PNG를 만들지 못했습니다.')), 'image/png'))
}
