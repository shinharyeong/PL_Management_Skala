// ---- 공통 필터바(두 화면 공통). 상태는 URL hash 에 저장한다. 값은 JSON 에서 온 항목 분류일 뿐이며 숫자를 계산하지 않는다. ----
const F = {org: [], kind: '', item: '', ym: '', scn: '', gtype: '', st: '', dfr: '', resub: false, q: '', tab: '', sorg: '', ver: '', only: ''};
const FKEYS = ['kind', 'item', 'ym', 'scn', 'gtype', 'st', 'dfr', 'q', 'tab', 'sorg', 'ver', 'only'];
const GTYPE = {item: '항목 수정', op: '영업이익', ref: 'Gap 아님·참고'};
const SCN = {ACTUAL: '실적', ESTIMATE: '추정', PLAN: '계획'};
const _EXTRA = {};      // 필터가 아닌 주소 값(srv, ask, quick 등)은 그대로 보존한다
function loadHash() {
  const p = new URLSearchParams(location.hash.slice(1));
  if (!Object.keys(_EXTRA).length) p.forEach((v, k) => { if (!['org', 'kind', 'item', 'ym', 'scn', 'gtype', 'st', 'dfr', 'q', 'tab', 'sorg', 'ver', 'only', 'resub'].includes(k)) _EXTRA[k] = v; });
  F.org = (p.get('org') || '').split(',').filter(Boolean);
  FKEYS.forEach(k => F[k] = p.get(k) || '');
  F.resub = p.get('resub') === '1';
}
function hashString() {
  const p = new URLSearchParams();
  if (F.org.length) p.set('org', F.org.join(','));
  FKEYS.forEach(k => { if (F[k]) p.set(k, F[k]); });
  if (F.resub) p.set('resub', '1');
  Object.entries(_EXTRA).forEach(([k, v]) => p.set(k, v));
  return p.toString();
}
function saveHash() {
  const s = hashString();
  try { history.replaceState(null, '', location.pathname + (s ? '#' + s : '')); } catch (e) { location.hash = s; }
  document.querySelectorAll('a[data-nav]').forEach(a => { a.href = a.dataset.nav + location.search + (s ? '#' + s : ''); });
}
function stOf(r, state) { return state === 'final' ? r.st_final : r.st_prov; }
function matchF(r, state, skip) {
  skip = skip || {};
  if (!skip.org && F.org.length && !F.org.includes(r.org_code)) return false;
  if (F.kind && r.kind !== F.kind) return false;
  if (F.item && r.item !== F.item) return false;
  if (F.ym && r.ym !== F.ym) return false;
  if (F.scn && r.scenario !== F.scn) return false;
  if (F.gtype && r.gtype !== F.gtype) return false;
  if (F.st) { const s = stOf(r, state) || []; if (F.st === '소명 없음' ? s.length > 0 : !s.includes(F.st)) return false; }
  if (F.dfr && !(r.dfr || []).includes(F.dfr)) return false;
  if (F.resub && !r.resub) return false;
  if (F.q && !(r.txt || '').toLowerCase().includes(F.q.toLowerCase())) return false;
  return true;
}
function buildFilterBar(host, getState, onChange) {
  loadHash();
  const recs = [...D.records, ...D.gaps];
  const uniq = (f) => [...new Set(recs.map(f).filter(Boolean))];
  const sel = (key, label, opts) => {
    const s = h('select', null, h('option', {value: '', text: '전체'}), opts.map(([v, t]) => h('option', {value: v, text: t})));
    s.value = F[key]; s.addEventListener('change', () => { F[key] = s.value; changed(); });
    return h('label', null, label, s);
  };
  const grid = h('div', {class: 'grid'});
  // 사업부 다중 선택
  const orgBtn = h('button', {class: 'btn', type: 'button'}); const pop = h('div', {class: 'orgs'});
  const codes = Object.keys(D.orgNames);
  const boxes = {};
  const orgText = () => { orgBtn.textContent = F.org.includes('__none__') ? '사업부 선택 없음' : F.org.length ? `사업부 ${F.org.length}개 선택` : '사업부 전체'; };
  pop.append(h('div', {style: 'display:flex;gap:6px;margin-bottom:6px'},
    h('button', {class: 'btn', type: 'button', onclick: () => { F.org = []; sync(); changed(); }}, '전체'),
    h('button', {class: 'btn', type: 'button', onclick: () => { F.org = ['__none__']; sync(); changed(); }}, '해제')));
  codes.forEach(c => { const cb = h('input', {type: 'checkbox'}); boxes[c] = cb;
    cb.addEventListener('change', () => { const cur = new Set(F.org.filter(x => x !== '__none__')); cb.checked ? cur.add(c) : cur.delete(c); F.org = [...cur]; orgText(); changed(); });
    pop.append(h('label', null, cb, D.orgNames[c])); });
  function sync() { codes.forEach(c => boxes[c].checked = F.org.length ? F.org.includes(c) : false); orgText(); }
  orgBtn.addEventListener('click', () => pop.classList.toggle('open'));
  grid.append(h('label', null, '사업부(다중)', h('div', {class: 'orgwrap'}, orgBtn, pop)));
  grid.append(sel('kind', '항목', [['매출', '매출'], ['영업비용', '영업비용'], ['영업이익', '영업이익']]));
  grid.append(sel('item', '세부 항목', uniq(r => r.item).sort().map(x => [x, x])));
  grid.append(sel('ym', '연월', uniq(r => r.ym).sort().map(x => [x, x])));
  grid.append(sel('scn', '실적/추정', [['ACTUAL', '실적'], ['ESTIMATE', '추정']]));
  grid.append(sel('gtype', 'Gap 종류', Object.entries(GTYPE)));
  grid.append(sel('st', '소명 상태', ['수락', '반려', '검토중', '소명 없음'].map(x => [x, x])));
  grid.append(sel('dfr', '변동 구분', ['이연', '실제 총액 변화', '판정 불가'].map(x => [x, x])));
  const rs = h('input', {type: 'checkbox'}); rs.checked = F.resub; rs.addEventListener('change', () => { F.resub = rs.checked; changed(); });
  grid.append(h('label', null, '재소명 이력', h('span', {style: 'display:flex;gap:4px;align-items:center;font-size:12px;color:var(--ink)'}, rs, '만 보기')));
  const q = h('input', {type: 'text', placeholder: '키워드(항목·소명 문장)'}); q.value = F.q; q.addEventListener('input', () => { F.q = q.value.trim(); changed(); });
  grid.append(h('label', null, '키워드 검색', q));
  grid.append(h('button', {class: 'btn', type: 'button', onclick: () => { Object.assign(F, {org: [], kind: '', item: '', ym: '', scn: '', gtype: '', st: '', dfr: '', resub: false, q: ''}); host.replaceChildren(); buildFilterBar(host, getState, onChange); changed(); }}, '초기화'));
  grid.append(h('span', {class: 'fcount', id: 'fcount'}));
  host.replaceChildren(h('div', {class: 'fbar'}, grid));
  host.firstChild.append(h('div', {class: 'tl', text: '필터 상태는 주소(#)에 저장됩니다. 비교(전월·전년·계획 대비) 표시와 소명 발생 규칙(Gap)은 별개 기능입니다.'}));
  sync(); saveHash();
  function changed() { if (F.org.includes('__none__') && F.org.length > 1) F.org = F.org.filter(x => x !== '__none__'); saveHash(); onChange(); }
  window.addEventListener('click', e => { if (!pop.contains(e.target) && e.target !== orgBtn) pop.classList.remove('open'); });
}
// 선택한 사업부가 '__none__'(해제)이면 아무 것도 보이지 않는다.
const _matchF = matchF;
matchF = function (r, state, skip) { if (F.org.includes('__none__')) return false; return _matchF(r, state, skip); };
