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

const STRIP = '.hd, .bt, .rz'   // 편집용 손잡이·빼기·크기 조절 표시는 그림에 넣지 않는다.

/** 5단계 미리보기 요소를 화면 모양(서식·너비·나란히 배치) 그대로 PNG로 만든다. 의존 라이브러리 없이 SVG foreignObject 를 쓴다.
 * Args: element 미리보기 요소(.report-preview), scale 배율(기본 2).
 * Returns: PNG Blob Promise. Raises: Error 렌더링 실패(호출자가 글자판 PNG로 대신한다).
 * Side Effects: 메모리의 복제본만 만든다. 화면은 바꾸지 않는다. */
export async function elementPng(element, scale = 2) {
  const { width, height } = element.getBoundingClientRect()
  if (!width || !height) throw new Error('미리보기 크기를 알 수 없습니다.')
  if (height * scale > 28000) throw new Error('PNG 한 장으로 만들기에는 내용이 많습니다. XLSX 또는 인쇄·PDF 저장을 사용해 주세요.')
  const clone = element.cloneNode(true)
  inlineStyles(element, clone)
  clone.querySelectorAll(STRIP).forEach(node => node.remove())
  clone.querySelectorAll('.rs-rb').forEach(node => { node.style.borderColor = 'transparent'; node.style.background = 'none'; node.style.opacity = '1' })
  clone.style.margin = '0'; clone.style.width = `${width}px`; clone.style.height = `${height}px`; clone.style.border = '0'; clone.style.borderRadius = '0'
  const xhtml = new XMLSerializer().serializeToString(clone)
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}"><foreignObject x="0" y="0" width="100%" height="100%"><div xmlns="http://www.w3.org/1999/xhtml">${xhtml}</div></foreignObject></svg>`
  const image = new Image()
  await new Promise((resolve, reject) => { image.onload = resolve; image.onerror = () => reject(new Error('미리보기를 그림으로 바꾸지 못했습니다.')); image.src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}` })
  const canvas = document.createElement('canvas'); canvas.width = Math.ceil(width * scale); canvas.height = Math.ceil(height * scale)
  const context = canvas.getContext('2d')
  if (!context) throw new Error('이 브라우저에서 PNG를 만들 수 없습니다.')
  context.fillStyle = '#fff'; context.fillRect(0, 0, canvas.width, canvas.height); context.scale(scale, scale); context.drawImage(image, 0, 0, width, height)
  return new Promise((resolve, reject) => canvas.toBlob(blob => blob ? resolve(blob) : reject(new Error('PNG를 만들지 못했습니다.')), 'image/png'))
}

/** 원본과 복제본의 같은 위치 요소에 계산된 스타일을 그대로 복사한다. */
function inlineStyles(source, target) {
  const computed = getComputedStyle(source)
  let text = ''
  for (const name of computed) text += `${name}:${computed.getPropertyValue(name)};`
  target.setAttribute('style', text)
  for (let i = 0; i < source.children.length; i++) inlineStyles(source.children[i], target.children[i])
}

/** 화면의 미리보기가 있으면 그 모양대로, 없거나 실패하면 글자판으로 PNG를 만든다. */
export async function reportPngOf(definition, result, base) {
  const element = typeof document !== 'undefined' ? document.querySelector('.report-preview') : null
  if (element) {
    element.classList.add('exporting')
    try { return await elementPng(element) } catch (cause) { if (/한 장으로/.test(cause.message)) throw cause } finally { element.classList.remove('exporting') }
  }
  return reportPng(definition, result, base)
}
