/** 보고서 화면의 공개 API 계약과 사용자 확인 후 적용하는 편집 규칙. */

/** 직접 작성할 보고서의 초기 틀을 만든다.
 * Args: name 자료 틀 이름.
 * Returns: 숫자 결과가 없는 새 자료 틀.
 * Raises: 없음. Side Effects: 없음.
 */
export function manualReport(name = '새 경영 보고서') {
  return { schema_version: 1, name, source: 'manual', blocks: [
    { id: 'title', kind: 'title', text: name },
    { id: 'summary', kind: 'metric_table', title: '주요 손익', metrics: ['매출', '영업비용', '영업이익'], compare: ['계획'] },
  ] }
}

/** 자료 틀 저장 봉투를 검증하고 JSON 복사본을 반환한다.
 * Args: definition 사용자가 편집하거나 AI가 제안한 자료 틀.
 * Returns: 원본과 분리된 검증된 정의.
 * Raises: Error 이름·블록 식별자가 잘못된 경우.
 * Side Effects: 없음.
 */
export function reportDefinition(definition) {
  if (!definition || typeof definition.name !== 'string' || !definition.name.trim() || definition.name.length > 120) throw new Error('자료 틀 이름을 1~120자로 입력해 주세요.')
  if (!Number.isInteger(definition.schema_version) || definition.schema_version < 1 || !Array.isArray(definition.blocks) || definition.blocks.length > 500) throw new Error('자료 틀 형식을 확인해 주세요.')
  const ids = new Set()
  for (const block of definition.blocks) {
    if (!block || typeof block.id !== 'string' || !block.id.trim() || block.id.length > 64 || ids.has(block.id) || typeof block.kind !== 'string' || !block.kind.trim() || block.kind.length > 40) throw new Error('자료 항목의 이름과 중복 여부를 확인해 주세요.')
    ids.add(block.id)
  }
  return JSON.parse(JSON.stringify(definition))
}

/** 현재 보고서 API의 요청·응답을 연결한다.
 * Args: api 인증된 요청 함수, action 작업 이름, input 해당 작업의 인자.
 * Returns: API JSON 또는 내보내기 Blob을 반환하는 Promise.
 * Raises: Error 지원하지 않는 작업·잘못된 입력, HTTP 오류.
 * Side Effects: 사용자 작업에 따라 서버 조회·자료 틀/분류/지표 저장 또는 PDF 업로드.
 */
export async function reportRequest(api, action, input = {}) {
  const root = '/api/v1/report'
  const id = encodeURIComponent(String(input.id ?? ''))
  const query = new URLSearchParams()
  switch (action) {
    case 'templates':
      query.set('limit', '50')
      if (input.cursor) query.set('cursor', input.cursor)
      return api(`${root}/templates?${query}`)
    case 'template':
      if (input.version) query.set('version', String(input.version))
      return api(`${root}/templates/${id}${query.size ? `?${query}` : ''}`)
    case 'save': {
      const definition = reportDefinition(input.definition)
      if (input.id) return api(`${root}/templates/${id}/versions`, { method: 'POST', body: { definition, expectedVersion: input.expectedVersion } })
      const body = { name: definition.name, definition }
      if (input.sourcePdfJobId) body.sourcePdfJobId = input.sourcePdfJobId
      return api(`${root}/templates`, { method: 'POST', body })
    }
    case 'periods':
      return api(`${root}/periods${input.year ? `?year=${encodeURIComponent(input.year)}` : ''}`)
    case 'metrics':
      for (const key of ['q', 'cursor', 'limit']) if (input[key] != null && input[key] !== '') query.set(key, String(input[key]))
      return api(`${root}/metrics${query.size ? `?${query}` : ''}`)
    case 'metric':
      return api(`${root}/metrics`, { method: 'POST', body: { code: input.code, name: input.name, description: input.description, sqlText: input.sqlText } })
    case 'categories':
      return api(`${root}/explanation-categories`)
    case 'explanations':
      for (const key of ['year', 'month', 'comparison']) query.set(key, String(input[key]))
      return api(`${root}/explanations?${query}`)
    case 'classify':
      if (input.comparison) query.set('comparison', input.comparison)
      return api(`${root}/explanations/${id}/classification${query.size ? `?${query}` : ''}`, { method: 'PUT', body: { categoryId: input.categoryId || null } })
    case 'pdf': {
      if (!input.file || !/\.pdf$/i.test(input.file.name) || input.file.size <= 0 || input.file.size > 30 * 1024 * 1024) throw new Error('비어 있지 않은 30MB 이하 PDF 파일을 선택해 주세요.')
      const body = new FormData()
      body.set('file', input.file)
      body.set('maskNumbers', 'true')
      return api(`${root}/pdf-jobs`, { method: 'POST', body })
    }
    case 'job':
      return api(`${root}/pdf-jobs/${id}`)
    case 'page':
      return api(`${root}/pdf-jobs/${id}/pages/${encodeURIComponent(input.no)}`, { blob: true })
    case 'data':
      return api(`${root}/data`, { method: 'POST', body: { base: input.base, blocks: input.blocks } })
    case 'query':
      return api(`${root}/nl-query`, { method: 'POST', body: { question: input.question, baseYear: input.base.year, baseMonth: input.base.month } })
    case 'edit':
      return api(`${root}/ai/edit`, { method: 'POST', body: { definition: reportDefinition(input.definition), instruction: input.instruction } })
    case 'export':
      return api(`${root}/export`, { method: 'POST', blob: true, body: { templateId: String(input.id), version: input.version, period: input.base, ...(input.blockId ? { blockId: input.blockId } : {}), format: 'XLSX' } })
    default:
      throw new Error('지원하지 않는 보고서 작업입니다.')
  }
}

/** 새 블록 제안에서 조회 조건만 허용하고 결과 값이나 SQL 삽입을 막는다.
 * Args: spec 서버가 제안한 블록 조회 명세.
 * Returns: 검증된 블록 종류.
 * Raises: Error 알 수 없는 종류·조회 조건·숫자 결과가 포함된 경우.
 * Side Effects: 없음.
 */
function proposalBlockKind(spec) {
  const fields = {
    breakdown: ['type', 'metric'],
    metric_table: ['type', 'metrics', 'compare'],
    chart: ['type', 'metric', 'chart_type', 'limit', 'scope', 'months_n'],
    numbercard: ['type', 'cards'],
  }
  if (!spec || !Object.hasOwn(fields, spec.type) || Object.keys(spec).some(key => !fields[spec.type].includes(key))) throw new Error('추가할 자료 항목에 허용되지 않은 값이 있습니다.')
  if (spec.type === 'numbercard' && (!Array.isArray(spec.cards) || spec.cards.some(card => !card || Object.keys(card).some(key => !['label', 'unit', 'delta_label'].includes(key)) || typeof card.label !== 'string' || (card.unit != null && typeof card.unit !== 'string') || (card.delta_label != null && typeof card.delta_label !== 'string')))) throw new Error('숫자 카드에는 조회할 지표와 표시 단위만 지정할 수 있습니다.')
  if (spec.metric != null && typeof spec.metric !== 'string') throw new Error('조회할 지표 이름을 확인해 주세요.')
  if (spec.metrics != null && (!Array.isArray(spec.metrics) || spec.metrics.some(metric => typeof metric !== 'string'))) throw new Error('조회할 지표 목록을 확인해 주세요.')
  if (spec.compare != null && (!Array.isArray(spec.compare) || spec.compare.some(value => !['계획', '직전 월', '전년 동월', '직전 추정'].includes(value)))) throw new Error('비교 기준을 확인해 주세요.')
  if (spec.scope != null && (!Array.isArray(spec.scope) || spec.scope.some(value => typeof value !== 'string'))) throw new Error('조회할 조직 목록을 확인해 주세요.')
  if (spec.chart_type != null && !['bar', 'line', 'donut', 'waterfall'].includes(spec.chart_type)) throw new Error('차트 종류를 확인해 주세요.')
  if ((spec.limit != null && (!Number.isInteger(spec.limit) || spec.limit < 3 || spec.limit > 30)) || (spec.months_n != null && (!Number.isInteger(spec.months_n) || spec.months_n < 2 || spec.months_n > 36))) throw new Error('차트 조회 범위를 확인해 주세요.')
  return spec.type
}

/** 사용자가 확인한 AI 변경안만 원본 복사본에 적용한다.
 * Args: definition 현재 틀, proposals RPT-15가 반환한 변경안 목록.
 * Returns: 적용된 새 틀. 원본과 계산된 숫자는 수정하지 않는다.
 * Raises: Error 알 수 없는 명령·대상·잘못된 서식 또는 비교 열.
 * Side Effects: 없음. 저장은 별도 사용자 명령이다.
 */
export function applyReportProposals(definition, proposals) {
  const next = reportDefinition(definition)
  const reservedIds = new Set(next.blocks.map(block => block.id))
  if (!Array.isArray(proposals)) throw new Error('편집안을 확인할 수 없습니다.')
  for (const proposal of proposals) {
    if (!proposal || typeof proposal !== 'object' || (proposal.patch != null && (typeof proposal.patch !== 'object' || Array.isArray(proposal.patch)))) throw new Error('편집안을 확인할 수 없습니다.')
    const patch = proposal.patch || {}
    const ids = String(proposal.target || '').split(',').filter(Boolean)
    if (proposal.op === 'add_block') {
      const spec = patch.spec
      proposalBlockKind(spec)
      let count = next.blocks.length + 1
      while (reservedIds.has(`added-${count}`)) count++
      const { type, ...settings } = spec
      const block = { ...settings, id: `added-${count}`, kind: type }
      reservedIds.add(block.id)
      const after = patch.after || proposal.target
      const index = after ? next.blocks.findIndex(item => item.id === after) : next.blocks.length - 1
      if (after && index < 0) throw new Error('삽입 위치가 변경됐습니다. 편집안을 다시 요청해 주세요.')
      next.blocks.splice(index + 1, 0, block)
      continue
    }
    if (!ids.length || ids.some(id => !next.blocks.some(block => block.id === id))) throw new Error('편집 대상이 변경됐습니다. 편집안을 다시 요청해 주세요.')
    if (proposal.op === 'remove_block') {
      next.blocks = next.blocks.filter(block => !ids.includes(block.id))
    } else if (proposal.op === 'move_block') {
      if (!['up', 'down'].includes(patch.direction)) throw new Error('이동 방향을 확인해 주세요.')
      const index = next.blocks.findIndex(block => block.id === ids[0])
      const target = index + (patch.direction === 'up' ? -1 : 1)
      if (target >= 0 && target < next.blocks.length) [next.blocks[index], next.blocks[target]] = [next.blocks[target], next.blocks[index]]
    } else if (proposal.op === 'set_style') {
      const style = patch.patch || {}
      if (Object.keys(style).some(key => !['family', 'size', 'bold'].includes(key)) || (style.family != null && !['gothic', 'serif'].includes(style.family)) || (style.size != null && (!Number.isInteger(style.size) || style.size < 8 || style.size > 40)) || (style.bold != null && typeof style.bold !== 'boolean')) throw new Error('허용되지 않은 서식 변경입니다.')
      for (const block of next.blocks) if (ids.includes(block.id)) block.style = { ...block.style, ...style }
    } else if (proposal.op === 'add_column') {
      if (!['계획', '직전 월', '전년 동월', '직전 추정'].includes(patch.compare)) throw new Error('비교 기준을 확인해 주세요.')
      const block = next.blocks.find(item => item.id === ids[0])
      if (!['table', 'spec_table', 'metric_table'].includes(block.kind)) throw new Error('비교 열은 표에만 추가할 수 있습니다.')
      if (block.kind === 'metric_table') block.compare = [...new Set([...(block.compare || []), patch.compare])]
      else if (block.spec) {
        const columns = block.spec.cols || []
        if (!columns.some(column => column.t === 'cmp' && column.name === patch.compare)) block.spec.cols = [...columns, { t: 'cmp', name: patch.compare }]
      }
      else throw new Error('이 표의 열 구성을 먼저 확인해 주세요.')
    } else throw new Error('지원하지 않는 편집안입니다.')
  }
  return reportDefinition(next)
}

/** 데이터 채우기와 SQL 미리보기의 표를 화면용 행 배열로 변환한다.
 * Args: block 서버가 반환한 블록 데이터.
 * Returns: 제목 행을 포함하는 셀 배열. 원 단위 숫자 문자열은 그대로 둔다.
 * Raises: 없음. Side Effects: 없음.
 */
export function reportRows(block) {
  if (block?.table) {
    const columns = block.table.columns || block.table.headers || []
    const headers = columns.map(column => typeof column === 'object' ? column.label || column.key : column)
    const rows = (block.table.rows || []).map(row => Array.isArray(row) ? row : columns.map(column => row?.[typeof column === 'object' ? column.key : column] ?? null))
    return [headers, ...rows]
  }
  if (block?.rows?.headers) return [block.rows.headers, ...(block.rows.rows || [])]
  if (Array.isArray(block?.rows) && block.rows.every(Array.isArray)) return block.rows
  return []
}

/** 서버 셀 값을 HTML 해석 없이 텍스트로 표시한다.
 * Args: cell 원시 값 또는 text/missing을 가진 셀.
 * Returns: 화면에 표시할 문자열.
 * Raises: 없음. Side Effects: 없음.
 */
export function reportCell(cell) {
  if (cell == null) return '데이터 없음'
  if (typeof cell === 'object') return cell.missing ? '데이터 없음' : String(cell.text ?? cell.value ?? '')
  return String(cell)
}
