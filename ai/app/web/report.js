// ---- report.html 화면 로직. 숫자·문장은 JSON 값 그대로, 막대 길이 등 도형 좌표만 JS 에서 계산한다. ----
const CARDS = [
  {id: 'summary', group: 'table', label: '손익 요약표', sub: '작년 동월 · 지난달 · 이번달 · 전월/전년/계획 대비'},
  {id: 'bridge', group: 'chart', label: '연간 영업이익 브리지', sub: '계획 → 사업부별 증감 → 최신 추정'},
  {id: 'trend', group: 'chart', label: '월별 매출 · 영업이익 추이'},
  {id: 'org', group: 'table', label: '조직별 계획 대비 실적'},
  {id: 'contrib', group: 'chart', label: '조직별 기여도', sub: '영업이익 실적 기준'},
  {id: 'opcomp', group: 'table', label: '영업이익 구성', sub: '합산 / Contingency / 보고'},
  {id: 'causes', group: 'table', label: '주요 변동 원인', sub: '금액 · 원인 · 향후 영향 · 구분'},
  {id: 'cost', group: 'table', label: '비용 상세', sub: 'JSON 범위까지'},
  {id: 'changes', group: 'table', label: '이번 달 변경 · 소명 현황'},
  {id: 'limits', group: 'table', label: '데이터 한계 안내'},
];
const sel = {}; CARDS.forEach(c => sel[c.id] = true);
const opt = {inline: true, ref: true, cum: false};
loadHash();
let state = F.ver === 'provisional' ? 'provisional' : 'final', trendMetric = '영업이익', orgSort = 'order';
const R = () => D.report[state];
const NARR = () => D.narr[state];
const orgName = c => D.orgNames[c] || c;
const stateStatus = r => (stOf(r, state) || [])[0] || '소명 없음';

function arrowOf(won) { const n = Number(won); return n > 0 ? '▲' : n < 0 ? '▼' : '－'; }
function clsOf(dir) { return dir === '▲' ? 'up' : dir === '▼' ? 'down' : 'flat'; }
function dcell(o) {
  if (!o) return nd(); const dir = o.direction || '－'; const cls = clsOf(dir);
  const diff = String(o.diff_eok).replace('-', ''); const sg = dir === '▲' ? '+' : dir === '▼' ? '−' : '';
  const pct = (o.percent !== null && o.percent !== undefined) ? `${sg}${String(o.percent).replace('-', '')}%` : (o.percent_note || NA);
  return h('div', {class: cls}, h('div', {class: 'd1', text: `${dir}${diff}`}), h('div', {class: 'd2', text: pct}));
}
function vcell(o) { return o && o.value_eok !== undefined ? h('span', {text: o.value_eok}) : nd(); }
function acts() { return h('div', {class: 'acts'}, ['PNG 복사', 'Excel', 'PPT에 추가'].map(t => h('button', {class: 'btn', disabled: true, title: '준비 중'}, t))); }
function basisLine(txt) { return h('div', {class: 'basis', text: '보고 기준 · ' + txt}); }
function oneLine(key) {
  const n = NARR().cards[key]; if (!n) return null;
  return h('div', {class: 'oneline'}, n.text);
}
function cardHead(title, sub, key) { return [h('div', {class: 'chead'}, h('div', null, h('h2', {text: title}), sub ? h('div', {class: 'sub', text: sub}) : null), acts()), key ? oneLine(key) : null].filter(Boolean); }
function histLabelOf(hs) { return (hs || []).map(x => `${x.round}차 ${chipVerdict(x.verdict)[0]}`).join(' → '); }
function B() { return D.basis[state]; }

// ---------- 손익 요약표 + 소명 인라인 ----------
function expBlock(r, isRef) {
  const st = stateStatus(r); const [lab, cls] = st === '수락' ? ['수락', 'ok'] : st === '반려' ? ['반려', 'bad'] : ['검토중', 'wait'];
  const nr = NARR().causes[r.id]; const hs = r.history || [];
  const head = h('div', {class: 'hd'}, `${r.org_name} · ${r.item} · ${r.month}월 ${SCN[r.scenario]} · 변동 ${r.diff_eok !== null ? r.diff_eok + '억원' : NA}`,
    lab === '수락' ? h('span', {class: 'faint', text: ' 수락'}) : h('span', {class: 'chip ' + cls, text: lab}), hs.length > 1 ? h('span', {class: 'chip', text: histLabelOf(hs)}) : null, isRef ? h('span', {class: 'faint', text: ' · Gap 아님(참고)'}) : null);
  const body = [];
  if (st === '수락') {
    if (nr) { body.push(h('div', {class: 'tx'}, h('b', {text: '원인 '}), nr.cause));
      if (nr.impact) body.push(h('div', {class: 'tx'}, h('b', {text: '대응·향후 '}), nr.impact)); }
    else body.push(h('div', {class: 'tx', text: r.latest_answer || NA}));
    body.push(h('details', null, h('summary', {class: 'd2', text: '원문 · 소명 ID · 판정 이력'}), h('div', {class: 'hist'},
      hs.map(x => { const [vl, vc] = chipVerdict(x.verdict); return h('div', null, `${x.round}차 `, h('span', {class: 'chip ' + vc, text: vl}), ` ${x.answer || '(문장 숨김)'} `, h('span', {class: 'd2 flat', text: `· 소명 ID ${x.explanation_id} · 판정 시점 ${NA}`}));}))));
  } else body.push(h('div', {class: 'tx'}, nd('검토중 · 문장 미표시')));
  return h('div', {class: 'itm'}, head, body);
}
function buildSummary() {
  const t = R().summary_table; const card = h('div', {class: 'card', 'data-card': 'summary'});
  card.append(...cardHead(t.title, t.subtitle, 'summary'));
  const seg = h('div', {class: 'seg', style: 'margin-bottom:8px'}, h('button', {class: 'on', type: 'button'}, '당월'), h('button', {type: 'button', disabled: true, title: '선택한 각 월의 마스터가 필요한데 현재 11월 마스터만 있음'}, '누적'));
  card.append(seg, h('span', {class: 'tl', style: 'margin-left:8px', text: '누적은 비활성: 선택한 각 월의 마스터가 필요한데 현재 11월 마스터만 있음'}));
  card.append(h('table', null, h('thead', null, h('tr', null, ['구분', '작년 동월', '지난달', '이번달', '계획', '전월 대비', '전년 대비', '계획 대비'].map(x => h('th', {text: x})))),
    h('tbody', null, t.rows.map(r => h('tr', null, h('td', null, h('b', {text: r.metric})), h('td', null, vcell(r.last_year_same_month)), h('td', null, vcell(r.last_month)),
      h('td', {class: 'num'}, h('b', {text: r.this_month.value_eok})), h('td', null, vcell(r.plan_this_month)), h('td', null, dcell(r.vs_last_month)), h('td', null, dcell(r.vs_last_year)), h('td', null, dcell(r.vs_plan)))))));
  card.append(basisLine(`비교 = ${B().compare} · 이 표의 비교는 소명 발생 규칙(Gap)과 별개`));
  if (opt.inline) {
    const rows = D.records.filter(r => matchF(r, state)); const main = rows.filter(r => r.gtype !== 'ref'), refs = rows.filter(r => r.gtype === 'ref');
    const acc = main.filter(r => stateStatus(r) === '수락').length, pend = main.filter(r => stateStatus(r) !== '수락').length;
    card.append(h('div', {class: 'inline'}, h('div', {class: 'hd'}, `▼ 영업이익 증감 소명 인라인 · 수락 ${acc}건 · 검토중 ${pend}건 (필터 적용)`),
      h('div', {class: 'd2', text: '수락된 소명은 원인/대응, 재소명 이력은 "1차 반려 → 2차 수락", 검토중·반려는 칩으로 구분합니다.'}), main.length ? main.map(r => expBlock(r, false)) : h('div', {class: 'tx'}, nd('조건에 맞는 소명 없음'))));
    if (opt.ref) card.append(h('div', {class: 'refbox'}, h('div', {class: 'hd'}, `Gap 아님 — 참고 소명 · ${refs.length}건`), refs.length ? refs.map(r => expBlock(r, true)) : nd('조건에 맞는 참고 소명 없음')));
  }
  return card;
}

// ---------- 3) 연간 영업이익 브리지 ----------
function buildBridge() {
  const t = R().op_bridge; const card = h('div', {class: 'card', 'data-card': 'bridge'}); const f = t.steps[0], l = t.steps[t.steps.length - 1];
  card.append(...cardHead(t.title, `연간 계획 ${f.value_eok} → 최신 추정 ${l.value_eok} (${t.total_delta_eok}, ${t.total_delta_percent}%) · ${t.unit} · ${t.scope}`, 'bridge'));
  let cur = 0; const bars = [];
  t.steps.forEach(s => { if (s.type === 'total') { const v = Number(s.value_won); bars.push({s, a: v, b: v, tot: true}); cur = v; } else { const d = Number(s.delta_won); bars.push({s, a: cur, b: cur + d, tot: false}); cur += d; } });
  const lv = bars.flatMap(b => [b.a, b.b]); const mx = Math.max(...lv), mn = Math.min(...lv); const span = (mx - mn) || 1; const lo = mn - span * 1.2, hi = mx + span * .35;
  const W = 760, H = 290, pl = 20, pr = 10, pt = 26, pb = 34; const n = bars.length, bw = (W - pl - pr) / n; const y = v => pt + (hi - v) / (hi - lo) * (H - pt - pb);
  const NS = 'http://www.w3.org/2000/svg'; const mk = (tag, at) => { const e = document.createElementNS(NS, tag); for (const k in at) e.setAttribute(k, at[k]); return e; };
  const svg = mk('svg', {viewBox: `0 0 ${W} ${H}`, width: '100%', style: 'max-height:320px'}); svg.append(mk('line', {x1: pl, x2: W - pr, y1: H - pb, y2: H - pb, stroke: '#e6e4de'}));
  bars.forEach((b, i) => { const x = pl + i * bw + bw * .2, w = bw * .6; const top = y(Math.max(b.a, b.b)), bot = b.tot ? y(lo) : y(Math.min(b.a, b.b)); const up = Number(b.s.delta_won) > 0;
    const r = mk('rect', {x, y: top, width: w, height: Math.max(bot - top, 2), fill: b.tot ? '#14161c' : (up ? '#d9625a' : '#4a6fdc'), rx: 2});
    r.addEventListener('mousemove', ev => showTip(ev, [b.s.label, b.tot ? `${b.s.value_eok}억원` : `${b.s.delta_eok}억원`, b.s.source && b.s.source.file ? `출처: ${b.s.source.file}` : '', b.s.source && b.s.source.path ? b.s.source.path : ''].filter(Boolean)));
    r.addEventListener('mouseleave', hideTip); svg.append(r);
    const lab = mk('text', {x: x + w / 2, y: top - 6, 'text-anchor': 'middle', 'font-size': 11, 'font-weight': 700, fill: b.tot ? '#14161c' : (up ? '#d9473f' : '#3b62d6')}); lab.textContent = b.tot ? b.s.value_eok : b.s.delta_eok; svg.append(lab);
    const nm = mk('text', {x: x + w / 2, y: H - pb + 16, 'text-anchor': 'middle', 'font-size': 10, fill: '#7b7f8a'}); nm.textContent = b.s.label.length > 12 ? b.s.label.slice(0, 12) + '…' : b.s.label; svg.append(nm); });
  card.append(svg);
  card.append(h('div', {class: 'd2 flat', text: `세로축은 막대가 보이도록 아래쪽을 생략해 확대한 도형입니다(값은 JSON 그대로). ${t.formula}`}));
  card.append(h('div', {class: 'd2 flat', text: '연간 최신 추정 = 1~11월 실적 + 12월 추정(최종 제출본 기준). 월별 금액을 12등분하거나 월에 반복 차감하지 않습니다.'}));
  const subs = D.submissions[state];
  card.append(h('details', {style: 'margin-top:6px'}, h('summary', {class: 'd2', text: `사용한 제출본 (조직별 제출본 ID ${subs.length ? subs.length + '건' : NA})`}),
    subs.length ? h('table', null, h('thead', null, h('tr', null, ['조직', '제출본 ID', '승인 상태'].map(x => h('th', {text: x})))), h('tbody', null, subs.map(s => h('tr', null, h('td', {text: s.org_name}), h('td', {text: s.submission_id || NA}), h('td', {text: s.approval_status || NA}))))) : nd()));
  card.append(basisLine(`${t.scope} · 단위 ${t.unit} · master ${B().master}`));
  return card;
}

// ---------- 4) 월별 매출·영업이익 추이 ----------
function buildTrend() {
  const card = h('div', {class: 'card', 'data-card': 'trend'}); card.append(...cardHead('월별 매출 · 영업이익 추이', '억원'));
  const seg = h('div', {class: 'seg', style: 'margin-bottom:8px'}, ['영업이익', '매출'].map(m => h('button', {type: 'button', class: m === trendMetric ? 'on' : '', onclick: () => { trendMetric = m; render(); }}, m)));
  card.append(seg);
  const row = R().summary_table.rows.find(r => r.metric === trendMetric);
  const ser = R().monthly_series; const pts = [];
  if (Array.isArray(ser) && ser.length) ser.forEach(p => pts.push({lab: p.ym, v: p[trendMetric === '매출' ? 'revenue_eok' : 'op_eok'], kind: p.scenario}));
  else if (row) [['last_year_same_month', 'A'], ['last_month', 'A'], ['this_month', 'C']].forEach(([k, kind]) => { const o = row[k]; if (o) pts.push({lab: (o.period || k).replace(' ACTUAL', ''), v: o.value_eok, kind, won: o.value_won}); });
  const plan = row && row.plan_this_month;
  if (pts.length) {
    const mx = Math.max(...pts.map(p => Math.abs(Number(p.v))), plan ? Math.abs(Number(plan.value_eok)) : 0) || 1; const W = 640, H = 220, bw = W / pts.length;
    const NS = 'http://www.w3.org/2000/svg'; const mk = (tag, at) => { const e = document.createElementNS(NS, tag); for (const k in at) e.setAttribute(k, at[k]); return e; };
    const svg = mk('svg', {viewBox: `0 0 ${W} ${H}`, width: '100%', style: 'max-height:240px'}); const yv = v => 170 - Math.abs(Number(v)) / mx * 120;
    pts.forEach((p, i) => { const x = i * bw + bw * .25, top = yv(p.v); const fill = p.kind === 'C' ? '#14161c' : '#c9ccd3';
      const r = mk('rect', {x, y: top, width: bw * .5, height: 170 - top, fill, rx: 2}); if (p.kind === 'E') { r.setAttribute('fill', '#fff'); r.setAttribute('stroke', '#7b7f8a'); r.setAttribute('stroke-dasharray', '4 3'); }
      r.addEventListener('mousemove', ev => showTip(ev, [p.lab, `${p.v}억원`])); r.addEventListener('mouseleave', hideTip); svg.append(r);
      const a = mk('text', {x: x + bw * .25, y: top - 6, 'text-anchor': 'middle', 'font-size': 11, 'font-weight': 700}); a.textContent = p.v; svg.append(a);
      const b = mk('text', {x: x + bw * .25, y: 190, 'text-anchor': 'middle', 'font-size': 11, fill: '#7b7f8a'}); b.textContent = p.lab; svg.append(b);
      if (p.kind === 'C' && plan) { const py = yv(plan.value_eok); svg.append(mk('line', {x1: x - 14, x2: x + bw * .5 + 14, y1: py, y2: py, stroke: '#b08d5b', 'stroke-width': 2.5}));
        const c = mk('text', {x: x + bw * .5 + 18, y: py + 4, 'font-size': 10, fill: '#9a7647'}); c.textContent = `계획 ${plan.value_eok}`; svg.append(c); } });
    card.append(svg);
  }
  card.append(h('div', {class: 'empty'}, h('b', {text: `${NA} (월별 시계열)`}), 'JSON 에 월별 시계열이 없어 작년 동월·지난달·이번달 3개 시점만 그렸습니다. 실적 회색, 이번달 검정, 계획 금색 선. 추정(점선 막대)은 월별 추정 값이 JSON 에 없어 그리지 않았습니다.'));
  card.append(basisLine('실적(회색) · 현재월(검정) · 계획(금색 선) · 추정(점선, 데이터 있을 때)'));
  return card;
}

// ---------- 5) 조직별 표 + 기여도 ----------
function orgRows() {
  let rows = R().org_plan_vs_actual.rows.filter(r => !(F.org.length && !F.org.includes(r.org_code)) && !F.org.includes('__none__') && (!F.q || (r.org_name + r.org_code).toLowerCase().includes(F.q.toLowerCase())));
  const v = (r, m, k) => r.values && r.values[m] ? Number(r.values[m][k]) : -Infinity;
  if (orgSort === 'rev') rows = [...rows].sort((a, b) => v(b, '매출', 'actual_won') - v(a, '매출', 'actual_won'));
  if (orgSort === 'op') rows = [...rows].sort((a, b) => v(b, '영업이익', 'actual_won') - v(a, '영업이익', 'actual_won'));
  if (orgSort === 'opdiff') rows = [...rows].sort((a, b) => v(b, '영업이익', 'vs_plan_diff_won') - v(a, '영업이익', 'vs_plan_diff_won'));
  return rows;
}
function buildOrg() {
  const t = R().org_plan_vs_actual; const card = h('div', {class: 'card', 'data-card': 'org'}); card.append(...cardHead(t.title, `단위 ${t.unit}`, 'org'));
  const ss = h('select', null, [['order', '조직 순서'], ['rev', '매출 실적 큰 순'], ['op', '영업이익 실적 큰 순'], ['opdiff', '영업이익 계획 대비 차이 큰 순']].map(([v, tx]) => h('option', {value: v, text: tx})));
  ss.value = orgSort; ss.addEventListener('change', () => { orgSort = ss.value; render(); });
  card.append(h('div', {class: 'filters'}, h('div', {class: 'sel'}, h('span', {text: '정렬'}), ss), h('span', {class: 'tl', text: '필터(사업부·키워드)가 적용됩니다.'})));
  const mets = ['매출', '영업비용', '영업이익']; const rows = orgRows();
  const head1 = h('tr', null, h('th', {text: '조직'}), mets.map(m => h('th', {colspan: 4, text: m, style: 'text-align:center'})), h('th', {text: 'Gap'}));
  const head2 = h('tr', null, h('th'), mets.map(() => ['계획', '실적', '차이', '%'].map(x => h('th', {text: x}))), h('th'));
  const body = rows.map(r => {
    if (!r.values) return h('tr', null, h('td', null, h('b', {text: r.org_name}), h('span', {class: 'chip wait', text: '제외'})), h('td', {colspan: 13, style: 'text-align:left'}, nd(r.note || NA)));
    const cells = mets.flatMap(m => { const v = r.values[m]; if (!v) return [h('td', {colspan: 4}, nd())]; const dir = v.vs_plan_direction, cls = clsOf(dir); const sg = dir === '▲' ? '+' : dir === '▼' ? '−' : '';
      return [h('td', {text: v.plan_eok}), h('td', null, h('b', {text: v.actual_eok})), h('td', {class: cls, text: `${dir}${String(v.vs_plan_diff_eok).replace('-', '')}`}),
        h('td', {class: cls, text: v.vs_plan_percent !== null && v.vs_plan_percent !== undefined ? `${sg}${String(v.vs_plan_percent).replace('-', '')}%` : (v.vs_plan_percent_note || NA)})]; });
    return h('tr', null, h('td', null, h('b', {text: r.org_name}), h('span', {class: 'd2 flat', text: ` ${r.org_code}`})), cells, h('td', null, (r.gap_flags || []).map(g => h('span', {class: 'chip wait', text: g.gap_type === 'OP_GAP' ? '영업이익' : '항목 수정'}))));
  });
  card.append(h('div', {style: 'overflow-x:auto'}, h('table', null, h('thead', null, head1, head2), h('tbody', null, body))));
  card.append(basisLine(`계획 대비 = 이번달 실적 − 2026-11 계획 · 조직 ${rows.length}개 표시`));
  return card;
}
function buildContrib() {
  const rows = orgRows().filter(r => r.values && r.values['영업이익']); const card = h('div', {class: 'card', 'data-card': 'contrib'});
  card.append(...cardHead('조직별 기여도', '영업이익 실적 · 억원 (막대 길이는 도형, 표시 숫자는 JSON 값) · 영업이익 구성(Contingency)과 별개'));
  if (!rows.length) { card.append(h('div', {class: 'empty'}, h('b', {text: NA}))); return card; }
  const mx = Math.max(...rows.map(r => Math.abs(Number(r.values['영업이익'].actual_won)))) || 1;
  card.append(h('div', {class: 'bars'}, rows.map(r => { const v = r.values['영업이익']; const w = Math.max(Number(v.actual_won) / mx * 100, 0);
    return h('div', {class: 'row'}, h('div', {class: 'nm', text: r.org_name}), h('div', {class: 'track'}, h('div', {class: 'fill', style: `width:${w}%`})), h('div', {class: 'val', text: v.actual_eok})); })));
  return card;
}

// ---------- 6) 영업이익 구성 ----------
function buildOpComp() {
  const card = h('div', {class: 'card', 'data-card': 'opcomp'}); card.append(...cardHead('영업이익 구성', '조직 합산 영업이익 / Contingency / 보고 영업이익 · 억원'));
  const op = R().summary_table.rows.find(r => r.metric === '영업이익').this_month; const c = R().op_bridge.contingency || {};
  card.append(h('div', {class: 'big3'},
    h('div', null, h('div', {class: 'l', text: '조직 합산 영업이익'}), h('div', {class: 'v', text: op.value_eok}), h('div', {class: 'd2 flat', text: `출처: ${op.source.file} · ${op.source.path}`})),
    h('div', null, h('div', {class: 'l', text: 'Contingency'}), h('div', {class: 'v'}, c.value_won === null || c.value_won === undefined ? nd() : h('span', {text: won(c.value_won)})), h('div', {class: 'd2 flat', text: c.note || ''})),
    h('div', null, h('div', {class: 'l', text: '보고 영업이익'}), h('div', {class: 'v', text: op.value_eok}), h('div', {class: 'd2 flat', text: c.value_won === null || c.value_won === undefined ? '= 조직 합산(Contingency 데이터 없음 → 미반영)' : '합산 + Contingency'}))));
  card.append(h('div', {class: 'tl', text: 'Contingency 는 연간 금액을 월에 반복 차감하거나 12등분하지 않습니다. 조직별 기여도(아래 별도 카드)와 구분해 보세요.'}));
  card.append(basisLine(`master ${B().master}`));
  return card;
}

// ---------- 7) 주요 변동 원인 ----------
const DFR_DEF = {'이연': '이연: 11월과 12월 변동의 합이 0', '실제 총액 변화': '실제 총액 변화: 11월과 12월 변동의 합이 0이 아님', '판정 불가': '판정 불가: 한쪽 변동만 있어 비교할 수 없음'};
function dfrChip(r) {   // 눈에 띄는 배지는 이연 / 실제 총액 변화만. 판정 불가는 연한 글자. 판정 이름 옆 물음표에 뜻을 넣는다
  const label = (r.dfr && r.dfr[0]) || '판정 불가'; const q = h('span', {class: 'qmark', title: DFR_DEF[label] || '', text: '?'});
  if (label === '판정 불가') return h('span', {class: 'faint'}, label, q);
  return h('span', null, h('span', {class: 'chip ' + (label === '이연' ? 'dfr' : 'real'), text: label}), q);
}
function buildCauses() {
  const card = h('div', {class: 'card cause', 'data-card': 'causes'}); card.append(...cardHead('주요 변동 원인', '큰 변동의 금액과 이유 · 이연인지 실제 총액 변화인지는 코드가 숫자로 판정', 'causes'));
  const rows = D.records.filter(r => matchF(r, state));
  const body = []; rows.forEach(r => {
    const st = stateStatus(r); const nr = NARR().causes[r.id]; const dir = arrowOf(r.diff_won);
    const cause = st === '수락' ? (nr ? nr.cause : r.latest_answer) : (st === '소명 없음' ? '확인된 사유 없음' : '검토중');
    const impact = st === '수락' && nr ? nr.impact : '';
    const tr = h('tr', {style: 'cursor:pointer'}, h('td', null, h('b', {text: r.org_name}), h('div', {class: 'd2 flat', text: `${r.item} · ${r.month}월 ${SCN[r.scenario]}`}), r.gtype === 'ref' ? h('span', {class: 'faint', text: 'Gap 아님(참고)'}) : null),
      h('td', {style: 'text-align:right'}, h('div', {class: clsOf(dir) + ' d1', text: `${dir}${String(r.diff_eok).replace('-', '')}억원`}), h('div', {class: 'd2 flat', text: r.diff_basis || ''})),
      h('td', null, st === '수락' ? cause : h('span', {class: 'chip wait', text: cause})),
      h('td', {text: impact || ''}), h('td', null, dfrChip(r)));
    let det = null; tr.addEventListener('click', () => { if (det) { det.remove(); det = null; return; }
      det = h('tr', {class: 'sub'}, h('td', {colspan: 5}, h('div', {class: 'd2'}, `소명 원문: ${st === '수락' ? (r.latest_answer || NA) : '(수락 전이라 원문 숨김)'}`),
        h('div', {class: 'd2'}, `소명 ID: ${(r.history || []).map(x => x.explanation_id).join(', ') || NA} · 판정 이력: ${histLabelOf(r.history) || NA} · 판정 시점: ${NA}`),
        h('div', {class: 'd2'}, `구분 판정 근거: ${r.dfr_basis || NA}`), h('div', {class: 'd2 flat'}, `엑셀 출처: ${r.sheet || NA}!${r.cell || ''}`)));
      tr.after(det); });
    body.push(tr); });
  card.append(h('div', {class: 'tl', style: 'margin:0 0 6px', text: '구분은 코드 판정이며 팀 확인이 필요합니다.'}));
  card.append(rows.length ? h('div', {style: 'overflow-x:auto'}, h('table', null, h('thead', null, h('tr', null, ['조직 · 항목', '변동(코드 계산값)', '확인된 원인', '향후 영향', '구분'].map(x => h('th', {text: x})))), h('tbody', null, body)))
    : h('div', {class: 'empty'}, h('b', {text: '조건에 맞는 변동 없음'})));
  card.append(h('div', {class: 'tl', text: '향후 영향은 수락된 소명 문장에 근거가 있을 때만 채우고 없으면 비워 둡니다. 구분 판정: 같은 조직·항목의 당월 변동(계획 대비)과 12월 추정 수정(직전 추정 대비)의 합이 0이면 이연, 아니면 실제 총액 변화, 근거 데이터가 없으면 판정 불가.'}));
  card.append(basisLine(`${rows.length}건 표시 · 소명 기준 시점 ${B().snapshot}`));
  return card;
}

// ---------- 비용 상세 ----------
function costRows(rows, depth, out) { rows.forEach(r => { const ch = r.children && r.children.length;
  out.push(h('tr', null, h('td', {style: `padding-left:${8 + depth * 18}px`}, h('span', {text: (ch ? '▾ ' : '') + r.name, style: ch ? 'font-weight:600' : ''})), h('td', null, h('b', {text: r.this_month_eok})), h('td', {text: r.last_month_eok}), h('td', {text: r.plan_eok}), h('td', null, dcell(r.vs_last_month)), h('td', null, dcell(r.vs_plan))));
  if (ch) costRows(r.children, depth + 1, out); }); return out; }
function buildCost() {
  const t = R().cost_detail; const card = h('div', {class: 'card', 'data-card': 'cost'}); card.append(...cardHead(t.title, `단위 ${t.unit} · 도달 범위: ${t.level_reached}`));
  if (!t.rows || !t.rows.length) { card.append(h('div', {class: 'empty'}, h('b', {text: NA}))); return card; }
  card.append(h('table', null, h('thead', null, h('tr', null, ['항목(중 → 소)', '이번달', '지난달', '계획', '전월 대비', '계획 대비'].map(x => h('th', {text: x})))), h('tbody', null, costRows(t.rows, 0, []))));
  card.append(h('div', {class: 'd2 flat', style: 'margin-top:8px', text: `한계: ${t.limits}`})); return card;
}

// ---------- 9) 이번 달 변경·소명 현황 ----------
function buildChanges() {
  const card = h('div', {class: 'card', 'data-card': 'changes'}); card.append(...cardHead('이번 달 변경 · 소명 현황', `${D.master[state].approval_label}`, 'changes'));
  const m = D.master[state], c = D.gapCounts; const cnt = s => D.records.filter(r => stateStatus(r) === s).length;
  const resub = D.records.filter(r => (r.history || []).length > 1);
  card.append(h('div', {class: 'big3'},
    h('div', null, h('div', {class: 'l', text: '승인'}), h('div', {class: 'v', text: `${m.approved_orgs}/${m.requested_orgs}`}), h('div', {class: 'd2 flat', text: m.approval_label})),
    h('div', null, h('div', {class: 'l', text: 'Gap'}), h('div', {class: 'v', text: `${c.gaps_total}건`}), h('div', {class: 'd2 flat', text: `항목 수정 ${c.item_revision_gaps} · 영업이익 ${c.op_gaps}`})),
    h('div', null, h('div', {class: 'l', text: '소명(키 기준)'}), h('div', {class: 'v', text: `${D.records.length}건`}), h('div', {class: 'd2 flat', text: `수락 ${cnt('수락')} · 반려 ${cnt('반려')} · 검토중 ${cnt('검토중')}`}))));
  card.append(h('div', {class: 'tl'}, h('b', {text: '재소명 이력 '}), resub.length ? resub.map(r => h('span', {class: 'chip ok', text: `${r.org_name} ${r.item} ${r.month}월: ${histLabelOf(r.history)}`})) : nd()));
  card.append(h('div', {class: 'tl'}, h('b', {text: '직전 제출본 대비 수정 '})));
  card.append(D.changes.prevRevisions.length ? h('table', null, h('thead', null, h('tr', null, ['조직', '항목', '연월', '이전값 → 변경값(원)', '차이(원)'].map(x => h('th', {text: x})))), h('tbody', null, D.changes.prevRevisions.map(g =>
    h('tr', null, h('td', {text: g.org_name}), h('td', {text: g.item}), h('td', {text: g.ym}), h('td', {text: `${won(g.previous_value_won)} → ${won(g.current_value_won)}`}), h('td', {text: won(g.diff_won)}))))) : nd());
  card.append(h('div', {class: 'tl'}, h('b', {text: 'master 버전 변화 '}), D.changes.pastUpdates.map(u => h('div', {text: `${u.month}: ${u.previous_version} → ${u.new_version} (${u.replaced_sources.map(s => `${orgName(s.org_code.replace(/^F/, 'F'))} 제출본 ${s.from} → ${s.to}`).join(', ')})`})),
    h('div', {text: `${D.master.final.month || ''} 신규: ${D.master.final.version || NA} (이전 버전 없음)`})));
  card.append(basisLine(`master ${B().master} · 소명 기준 시점 ${B().snapshot}`)); return card;
}
function buildLimits() {
  const card = h('div', {class: 'card', 'data-card': 'limits'}); card.append(h('div', {class: 'chead'}, h('h2', {text: '데이터 한계 안내'})));
  card.append(h('ul', {class: 'lim'}, ['주별 매출은 입력 엑셀에 주 단위 값이 없어 보고서에서 제외했습니다.', '비용 상세는 중분류 → 소분류까지만 있고 대분류는 없습니다.', '누적 보기와 Contingency 는 해당 데이터(월별 master, 입력값)가 있을 때만 표시합니다.',
    '월별 시계열(추이)은 JSON 에 3개 시점만 있어 일부만 그립니다.', 'Gap 규칙·임계값(예: 5%, 10백만원)은 팀 확정 전 예시 값입니다.', '이연/실제 총액 변화 구분은 코드 판정이며 팀 확인이 필요합니다.'].map(t => h('li', {text: t}))));
  return card;
}

// ---------- 8) 핵심 메시지 ----------
function copyBtn(getText) { return h('button', {class: 'btn', onclick: e => copyText(getText(), e.target)}, '복사'); }
function buildKM() {
  const k = D.keyMessages[state]; const fk = {}; k.facts.forEach(f => fk[f.id] = f.kind);
  const card = h('div', {class: 'card km', 'data-card': 'km'});
  card.append(h('div', {class: 'chead'}, h('div', null, h('h2', {text: '보고 핵심 메시지 초안'}), h('div', {class: 'sub', text: `주목할 점 3개 + 확인할 일 · AI 작성 문장 ${k.counts.ai}개`})), h('div', {class: 'acts'}, h('button', {class: 'btn', onclick: e => copyText(k.sentences.map(s => '• ' + s.text).join('\n'), e.target)}, '전체 복사'))));
  card.append(h('ul', null, k.sentences.map(s => { const t = h('span', {class: 't', text: s.text}); let editing = false;
    const eb = h('button', {class: 'btn', onclick: e => { editing = !editing; t.contentEditable = editing; e.target.textContent = editing ? '편집 끝(저장 안 함)' : '편집'; if (editing) t.focus(); }}, '편집');
    return h('li', null, h('div', {style: 'flex:1'}, t, h('div', null, (s.fact_ids || []).map(id => h('span', {class: 'chip sm', title: id, text: `${fk[id] || '?'} ${id}`})))), copyBtn(() => t.textContent), eb); })));
  card.append(h('div', {class: 'meta', text: '편집은 저장되지 않습니다 · 숫자는 코드가 계산하고 AI 는 문장만 씁니다'}));
  return card;
}

const BUILD = {summary: buildSummary, bridge: buildBridge, trend: buildTrend, org: buildOrg, contrib: buildContrib, opcomp: buildOpComp, causes: buildCauses, cost: buildCost, changes: buildChanges, limits: buildLimits};
function drawAiBar() {
  const el = document.getElementById('aibar'); if (!el) return; const a = D.ai; el.replaceChildren();
  if (!a) { el.style.display = 'none'; return; }
  const cu = window.CHAT_USAGE && window.CHAT_USAGE.requests ? window.CHAT_USAGE : null;   // AI 작업실(챗봇) 누적 사용량(chat.js 가 채움)
  const calls = a.api_calls + (cu ? cu.calls : 0), failed = a.failed + (cu ? cu.failed : 0);
  el.style.display = 'block'; el.className = 'aibar' + (failed ? ' bad' : calls ? ' ok' : '');
  const line = `AI 호출 ${calls}회 · 성공 ${calls - failed} · 실패 ${failed}`;
  const det = h('div', {class: 'aidet'});
  det.append(h('div', {class: 'd2'}, h('b', {text: '보고서 문장 '}), a.requested ? `호출 ${a.api_calls}회 · 성공 ${a.succeeded} · 실패 ${a.failed}${a.cache_hits ? ' · 저장된 결과 재사용 ' + a.cache_hits : ''} · AI 작성 문장 ${a.ai_sentences}개${a.input_tokens ? ` · 토큰 ${a.input_tokens}+${a.output_tokens}` : ''}`
    : `이번에는 AI 를 켜지 않았습니다${a.ai_sentences ? ` · 저장된 AI 결과 재사용(AI 작성 문장 ${a.ai_sentences}개)` : ' · 모든 문장이 기본문구'} · 숫자는 항상 코드가 계산`));
  if (a.reasons && a.reasons.length) det.append(h('div', {class: 'd2'}, '기본문구가 쓰인 이유: ' + a.reasons.join(' · ')));
  if (cu) det.append(h('div', {class: 'd2'}, h('b', {text: 'AI 작업실 '}), `질문 ${cu.requests}건 · AI 호출 ${cu.calls}회 · 저장 SQL 재사용 ${cu.cache_hits}회 · 토큰 ${cu.input_tokens}+${cu.output_tokens} · 실패 ${cu.failed}${cu.reasons.length ? ' · 이유: ' + cu.reasons.join(' / ') : ''}`));
  det.append(h('table', null, h('thead', null, h('tr', null, ['작업', '호출', 'AI 문장', '기본문구', '이유'].map(x => h('th', {text: x})))),
    h('tbody', null, a.tasks.map(t => h('tr', null, h('td', {text: {key_messages: '핵심 메시지(주목할 점·확인할 일)'}[t.task] || t.task}), h('td', {text: t.attempted ? '호출함' : (t.cached ? '저장 결과 사용' : '안 함')}),
      h('td', {text: t.ai}), h('td', {text: t.template}), h('td', {text: t.reason_ko || ''}))))));
  el.append(h('details', null, h('summary', {text: line}), det));
}
function render() {
  const r = R(), m = D.master[state], b = B();
  document.getElementById('baseMonth').textContent = r.base_month.replace(/^(\d{4})-(\d{2})$/, (a, y, mo) => `${y}년 ${Number(mo)}월`);
  const bn = document.getElementById('banner');
  if (state === 'provisional') { bn.className = 'banner prov'; const ex = r.scope.excluded_orgs.map(orgName).join(', ');
    bn.replaceChildren(h('b', {text: '잠정 · 공식 master 아님 · '}), `${m.approval_label.replace('조직', '').trim()} · 참고용 · 검토 대기 조직 제외(${ex}) · 미수락 소명 ${r.explanation_inline.pending_count}건 검토중`);
  } else { bn.className = 'banner final'; bn.replaceChildren(h('b', {text: '확정'}), `${m.version} · ${m.approval_label}`); }
  document.getElementById('topbasis').replaceChildren(...[['대상 기간', b.period], ['조직 범위', b.scope], ['단위', b.unit], ['비교 기준', b.compare], ['master 버전', b.master], ['소명 기준 시점', b.snapshot]].map(([k, v]) => h('span', null, h('b', {text: k}), v)));
  drawAiBar();
  const wrap = document.getElementById('cards'); wrap.replaceChildren();
  const addAi = (e, id) => { const acts = e.querySelector('.acts'); if (acts && typeof aiViewButton === 'function') acts.prepend(aiViewButton(id)); };   // 카드마다 "AI로 보기"
  CARDS.forEach(c => { const e = BUILD[c.id](); addAi(e, c.id); if (!sel[c.id] || (F.only && F.only !== c.id)) e.classList.add('hide'); wrap.append(e); });
  const km = buildKM(); addAi(km, 'km'); if (F.only && F.only !== 'km') km.classList.add('hide'); wrap.append(km);
  if (typeof renderAdded === 'function' && !F.only) renderAdded();          // AI 작업실에서 "보고서에 넣기"로 담은 조회 결과
  const filtered = D.records.filter(x => matchF(x, state)).length;
  const fc = document.getElementById('fcount'); if (fc) fc.textContent = `필터 결과: 소명 ${filtered}/${D.records.length}건`;
  updateDl();
}
function updateDl() { const n = Object.values(sel).filter(Boolean).length; document.getElementById('dl').textContent = `선택 ${n}개 자료 한 번에 다운로드`; }
function chk(label, sub, val, on) { const i = h('input', {type: 'checkbox'}); i.checked = val; i.addEventListener('change', () => on(i.checked)); return h('label', null, i, h('span', null, label, sub ? h('small', {text: sub}) : null)); }
CARDS.forEach(c => document.getElementById(c.group === 'table' ? 'opt-table' : 'opt-chart').append(chk(c.label, c.sub, true, v => { sel[c.id] = v; const e = document.querySelector(`[data-card=${c.id}]`); if (e) e.classList.toggle('hide', !v); updateDl(); })));
const o = document.getElementById('opt-opt');
o.append(chk('소명 사유 인라인 표시', '손익 요약표 아래 소명 삽입', true, v => { opt.inline = v; render(); }));
o.append(chk('Gap 아님(참고) 소명 표시', '소명은 있으나 Gap 으로 분류되지 않은 건', true, v => { opt.ref = v; render(); }));
const vs = document.getElementById('ver');
[['final', `${D.master.final.version} 확정`], ['provisional', '잠정 (공식 master 아님)']].forEach(([v, t]) => vs.append(h('option', {value: v, text: t}))); vs.value = state;
vs.addEventListener('change', () => { state = vs.value; F.ver = state === 'provisional' ? 'provisional' : ''; saveHash(); render(); });
buildFilterBar(document.getElementById('filters'), () => state, render);
render();
