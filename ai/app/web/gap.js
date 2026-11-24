// ---- gap_explain.html 화면 로직. 값은 JSON 그대로. ----
const KIND = {AMOUNT_REVISION: '항목 수정', OP_GAP: '영업이익'};
const BASIS = {PLAN: '계획', PREVIOUS: '직전 제출본', PREVIOUS_SAME_MONTH: '직전 제출본(같은 달)'};
const orgName = c => D.orgNames[c] || c;
function clsOf(dir) { return dir === '▲' ? 'up' : dir === '▼' ? 'down' : 'flat'; }
loadHash();
let st = 'final', tab = F.tab || 'list', selOrg = (F.sorg && D.orgNames[F.sorg]) ? F.sorg : Object.keys(D.orgNames)[0];
const stat1 = (r) => (stOf(r, st) || [])[0] || '소명 없음';
function chipSt(s) { return h('span', {class: 'chip ' + chipStatus(s), text: s}); }
function histLabel(hs) { return (hs || []).map(x => `${x.round}차 ${chipVerdict(x.verdict)[0]}`).join(' → '); }
function latestAnswer(e) { const hs = e.history || []; for (let i = hs.length - 1; i >= 0; i--) { if (hs[i].verdict === 'ACCEPTED' && hs[i].answer) return hs[i].answer; } return (hs.length && hs[hs.length - 1].answer) || null; }
function stateEx(e) { return st === 'final' ? e.status_final : e.status_provisional; }
function histBox(hs) {
  return h('div', {class: 'hist'}, (hs || []).map(x => { const [l, c] = chipVerdict(x.verdict);
    return h('div', null, `${x.round}차 `, h('span', {class: 'chip ' + c, text: l}), ` ${x.answer || NA}`, x.request_note ? h('div', {class: 'd2 down', text: `반려 사유: ${x.request_note}`}) : null, h('span', {class: 'd2 flat', text: ` · 소명 ID ${x.explanation_id || NA}`})); }));
}
function mockBox() {
  return h('div', {class: 'mock'}, h('b', {text: '담당자 입력 · 검토자 처리 '}), h('span', {class: 'badge-s', text: '시안'}), h('textarea', {disabled: true, placeholder: '담당자 소명 입력칸 (시안 · 저장 안 함)'}),
    h('div', {class: 'row'}, h('button', {class: 'btn', disabled: true}, '수락'), h('button', {class: 'btn', disabled: true}, '반려'), h('span', {class: 'd2 flat', text: '화면 모양만 · 저장하지 않습니다'})));
}

// ---------- ① 목록 ----------
function gapDetail(g) {
  const td = h('td', {colspan: 11});
  td.append(h('dl', {class: 'kv'}, h('dt', {text: '사유 코드'}), h('dd', {text: g.reason_code}), h('dt', {text: '공식'}), h('dd', {text: g.formula || NA}),
    h('dt', {text: '기준(임계)'}), h('dd', {text: g.threshold ? `% ${g.threshold.percent} · 금액 ${won(g.threshold.amount_won)} / 초과 여부 ${g.threshold_exceeded}` : NA}),
    h('dt', {text: '판정'}), h('dd', {text: g.judgement || NA}), h('dt', {text: 'Gap 키'}), h('dd', {text: g.gap_key}), h('dt', {text: '제출본 / 비교 제출본'}), h('dd', {text: `${g.current_submission_id} / ${g.compare_submission_id}`}),
    h('dt', {text: '변동 구분(코드 판정)'}), h('dd', {text: (g.dfr || []).join(' / ') || NA})));
  g.explanations.forEach(e => { const hs = e.history || [];
    td.append(h('div', {style: 'border:1px solid var(--line);border-radius:10px;background:#fff;padding:10px 12px;margin-top:8px'},
      h('div', null, h('b', {text: e.item}), chipSt(stateEx(e)), h('span', {class: 'chip', text: e.link_role}), hs.length > 1 ? h('span', {class: 'chip ok', text: histLabel(hs)}) : null), histBox(hs),
      e.do_not_invent_causal_amounts ? h('div', {class: 'd2 flat', text: '원인별 금액은 소명 문장에 없는 한 표시하지 않음(do_not_invent_causal_amounts)'}) : null)); });
  td.append(mockBox()); return h('tr', {class: 'det'}, td);
}
function viewList() {
  const rows = D.gaps.filter(g => matchF(g, st)); const c = D.gapCounts; const v = h('div', {class: 'card'});
  v.append(h('div', {class: 'chead'}, h('div', null, h('h2', {text: 'Gap 목록'}), h('div', {class: 'sub', text: `Gap ${c.gaps_total}건 (항목 수정 ${c.item_revision_gaps} · 영업이익 ${c.op_gaps}) · 소명 ${c.explanation_keys}건(행 ${c.explanation_rows}) · Gap 아님 ${c.explanations_without_gap}건 · 표시 ${rows.length}/${D.gaps.length}건 · 행 클릭 = 상세`}))));
  const tb = h('tbody');
  rows.forEach(g => {
    const ans = g.explanations.map(latestAnswer).filter(Boolean).join(' / '); const dir = Number(g.diff_won) > 0 ? 'up' : Number(g.diff_won) < 0 ? 'down' : 'flat'; const arrow = Number(g.diff_won) > 0 ? '▲' : Number(g.diff_won) < 0 ? '▼' : '－';
    const tr = h('tr', {class: 'gap'}, h('td', {text: g.org_name}), h('td', {text: g.item}), h('td', {text: g.ym}), h('td', {text: SCN[g.scenario]}), h('td', {text: BASIS[g.basis] || g.basis}),
      h('td', {text: `${won(g.previous_value_won)} → ${won(g.current_value_won)}`}), h('td', {class: dir, text: `${arrow}${won(g.diff_won).replace('-', '')}`}),
      h('td', {class: dir}, g.rate_percent !== null && g.rate_percent !== undefined ? h('span', {class: 'rate', title: g.rate_percent, text: g.rate_percent + '%'}) : nd(g.rate_note || NA)),
      h('td', null, h('span', {class: 'chip', text: KIND[g.gap_type] || g.gap_type})),
      h('td', null, g.explanations.map(e => [chipSt(stateEx(e)), (e.history || []).length > 1 ? h('span', {class: 'chip', text: histLabel(e.history)}) : null])),
      h('td', {style: 'text-align:left'}, ans ? h('span', {class: 'tx1', title: ans, text: ans}) : nd(NA)));
    let dr = null; tr.addEventListener('click', () => { if (dr) { dr.remove(); dr = null; } else { dr = gapDetail(g); tr.after(dr); } }); tb.append(tr); });
  v.append(h('div', {style: 'overflow-x:auto'}, h('table', null, h('thead', null, h('tr', null, ['조직', '항목', '연월', '실적/추정', '비교 기준', '이전값 → 변경값', '차이', '%', 'Gap 종류', '소명 상태', '소명 문장'].map(x => h('th', {text: x})))), tb)));
  return v;
}

// ---------- ② 사업부별 ----------
function viewOrg() {
  const v = h('div'); const codes = Object.keys(D.orgNames);
  const s = h('select', null, codes.map(c => h('option', {value: c, text: D.orgNames[c]}))); s.value = selOrg; s.addEventListener('change', () => { selOrg = s.value; F.sorg = selOrg; saveHash(); draw(); });
  v.append(h('div', {class: 'filters'}, h('div', {class: 'sel'}, h('span', {text: '사업부'}), s), h('span', {class: 'tl', text: '여기서는 사업부 선택이 우선하고, 나머지 필터는 소명 목록에 적용됩니다.'})));
  const r = D.report[st].org_plan_vs_actual.rows.find(x => x.org_code === selOrg); const card = h('div', {class: 'card'});
  card.append(h('div', {class: 'chead'}, h('h2', {text: `${orgName(selOrg)} · 계획 대비 요약`}), h('span', {class: 'badge-s', text: '시안'})));
  if (!r || !r.values) card.append(h('div', {class: 'empty'}, h('b', {text: NA}), r && r.note ? r.note : '이 버전의 집계에 포함되지 않았습니다.'));
  else card.append(h('table', null, h('thead', null, h('tr', null, ['구분', '계획', '실적', '차이', '%'].map(x => h('th', {text: x})))),
    h('tbody', null, ['매출', '영업비용', '영업이익'].map(m => { const x = r.values[m]; const cls = clsOf(x.vs_plan_direction); const sg = x.vs_plan_direction === '▲' ? '+' : x.vs_plan_direction === '▼' ? '−' : '';
      return h('tr', null, h('td', {text: m}), h('td', {text: x.plan_eok}), h('td', null, h('b', {text: x.actual_eok})), h('td', {class: cls, text: `${x.vs_plan_direction}${String(x.vs_plan_diff_eok).replace('-', '')}`}), h('td', {class: cls, text: x.vs_plan_percent !== null && x.vs_plan_percent !== undefined ? `${sg}${String(x.vs_plan_percent).replace('-', '')}%` : (x.vs_plan_percent_note || NA)})); }))));
  const nar = D.narr[st].divisions[selOrg];
  card.append(h('div', {class: 'oneline', style: 'margin-top:8px'}, '한 줄 요약 ', nar ? nar.text : nd()));
  v.append(card);
  const gs = D.gaps.filter(g => g.org_code === selOrg); const g2 = h('div', {class: 'card', style: 'margin-top:12px'});
  g2.append(h('div', {class: 'chead'}, h('h2', {text: `Gap ${gs.length}건`})));
  g2.append(gs.length ? h('table', null, h('thead', null, h('tr', null, ['항목', '연월', '실적/추정', '차이(원)', 'Gap 종류', '소명 상태'].map(x => h('th', {text: x})))), h('tbody', null, gs.map(g => h('tr', null, h('td', {text: g.item}), h('td', {text: g.ym}), h('td', {text: SCN[g.scenario]}), h('td', {text: won(g.diff_won)}), h('td', {text: KIND[g.gap_type] || g.gap_type}), h('td', null, g.explanations.map(e => chipSt(stateEx(e)))))))) : nd('Gap 없음'));
  v.append(g2);
  const rs = D.records.filter(x => x.org_code === selOrg && matchF(x, st, {org: true})); const c3 = h('div', {class: 'card', style: 'margin-top:12px'});
  c3.append(h('div', {class: 'chead'}, h('h2', {text: `소명 ${rs.length}건`})));
  rs.forEach(x => c3.append(h('div', {style: 'border-top:1px solid var(--line);padding:8px 0'}, h('b', {text: `${x.item} · ${x.month}월 ${SCN[x.scenario]}`}), chipSt(stat1(x)), h('span', {class: 'chip', text: x.gclass}), (x.history || []).length > 1 ? h('span', {class: 'chip ok', text: histLabel(x.history)}) : null, histBox(x.history))));
  if (!rs.length) c3.append(nd('조건에 맞는 소명 없음'));
  c3.append(mockBox()); v.append(c3); return v;
  function draw() { render(); }
}

// ---------- ③ 시나리오 대조 ----------
function viewScn() {
  const c = D.gapCounts; const rows = D.records.filter(r => matchF(r, st)); const v = h('div', {class: 'card'});
  v.append(h('div', {class: 'chead'}, h('div', null, h('h2', {text: '시나리오 대조 (소명 25건)'}), h('div', {class: 'sub', text: `항목 수정 ${c.explanations_linked_direct} · 영업이익 기여 ${c.explanations_linked_op_gap_only} · Gap 아님 ${c.explanations_without_gap} · 합 ${c.explanation_keys} (필터 적용 후 ${rows.length}건 표시)`}))));
  v.append(h('div', {class: 'tl', text: `항목 수정 ${c.explanations_linked_direct}건 중 ${c.explanations_also_contributing_to_op_gap}건은 영업이익 Gap 에도 기여합니다. Gap 아님 사유 코드는 JSON 에 link_type=NO_GAP 만 있고 세부 사유는 없습니다.`}));
  v.append(h('div', {style: 'overflow-x:auto'}, h('table', null, h('thead', null, h('tr', null, ['사업부', 'gap_key', '엑셀 시트!셀', '엑셀에서 실제 바뀐 값(원)', 'Gap 분류', '소명 문장', '판정'].map(x => h('th', {text: x})))),
    h('tbody', null, rows.map(r => h('tr', null, h('td', {text: r.org_name}), h('td', {text: r.id}), h('td', {text: `${r.sheet || NA}!${r.cell || ''}`}),
      h('td', {text: `현재 ${won(r.current_won)} · ${r.diff_basis_full || NA}`}),
      h('td', null, h('span', {class: 'chip ' + (r.gtype === 'ref' ? 'ref' : r.gtype === 'op' ? 'wait' : 'ok'), text: r.gclass}), r.gtype === 'ref' ? h('div', {class: 'd2 flat', text: `사유 코드 ${r.link_type}`}) : null),
      h('td', {style: 'text-align:left'}, r.latest_answer ? h('span', {class: 'tx1', title: r.latest_answer, text: r.latest_answer}) : nd()),
      h('td', null, chipSt(stat1(r)), h('span', {class: 'd2 flat', text: ` ${r.latest_verdict || ''}`}), (r.history || []).length > 1 ? h('span', {class: 'chip ok', text: histLabel(r.history)}) : null)))))));
  return v;
}

function render() {
  document.querySelectorAll('#subtabs a').forEach(a => a.classList.toggle('on', a.dataset.t === tab));
  const view = document.getElementById('view'); view.replaceChildren(tab === 'list' ? viewList() : tab === 'org' ? viewOrg() : viewScn());
  const fc = document.getElementById('fcount'); if (fc) fc.textContent = `필터 결과: Gap ${D.gaps.filter(g => matchF(g, st)).length}/${D.gaps.length} · 소명 ${D.records.filter(r => matchF(r, st)).length}/${D.records.length}`;
}
document.getElementById('st').addEventListener('change', e => { st = e.target.value; render(); });
document.querySelectorAll('#subtabs a').forEach(a => a.addEventListener('click', e => { e.preventDefault(); tab = a.dataset.t; F.tab = tab; saveHash(); render(); }));
buildFilterBar(document.getElementById('filters'), () => st, render);
render();
