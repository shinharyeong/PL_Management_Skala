// ---- AI 작업실(챗봇) 패널: 질문 → /v1/chat → 답변·처리 과정·미리보기·엑셀. 응답에 없는 내용은 만들어 쓰지 않는다(steps·sql·rows 는 응답 그대로). ----
const CHAT = {busy: false, status: null, lastQuestion: '', pending: null, cid: null, stamp: null,
  usage: {requests: 0, calls: 0, cache_hits: 0, input_tokens: 0, output_tokens: 0, failed: 0, reasons: []}};
window.CHAT_USAGE = CHAT.usage;
function newCid() { return (window.crypto && crypto.randomUUID) ? crypto.randomUUID() : 'c' + Math.random().toString(36).slice(2) + Date.now().toString(36); }
CHAT.cid = newCid();
function chatSrv() {
  const hq = new URLSearchParams(location.hash.slice(1)).get('srv'); if (hq) return hq.replace(/\/+$/, '');
  const own = document.getElementById('chatSrv'); if (own && own.value.trim()) return own.value.trim().replace(/\/+$/, '');
  try { const p = window.parent && window.parent !== window && window.parent.document.getElementById('srv'); if (p && p.value) return p.value.replace(/\/+$/, ''); } catch (e) {}
  try { const v = localStorage.getItem('flow_srv'); if (v) return v.replace(/\/+$/, ''); } catch (e) {}
  return location.origin + location.pathname.replace(/\/ui\/[^/]*$/, '');
}
const fmtCell = (v, col) => v === null || v === undefined ? '' : (typeof v === 'number' ? (col.endsWith('_won') ? v.toLocaleString('ko-KR') : String(v)) : String(v));
function el(id) { return document.getElementById(id); }
function dot(id, cls, title) { const d = el(id); if (d) { d.className = 'sdot ' + cls; d.title = title; } }

async function chatStatus() {
  const line = el('chatStatus'); if (!line) return;
  try {
    const r = await fetch(chatSrv() + '/v1/chat/status'); const j = await r.json(); CHAT.status = j;
    dot('dotSrv', 'ok', '서버 연결됨');
    if (!j.db_ready) { dot('dotDb', 'bad', '조회 DB 없음'); line.replaceChildren(h('span', {class: 'chip bad', text: '조회 DB 없음'}), ' 먼저 최신 업로드 실행 결과를 불러오세요 ', h('button', {class: 'btn', type: 'button', onclick: chatLoad}, '최신 실행 불러오기')); aiDot(j.ai); return; }
    dot('dotDb', 'ok', `DB 연결 · 버전 ${j.versions.length}개 · 조직 ${j.orgs}개`);
    aiDot(j.ai);
    const a = j.ai;
    line.replaceChildren(h('span', {title: 'AI_DAILY_CALL_LIMIT 로 바꿀 수 있고 같은 질문은 캐시되어 횟수가 늘지 않는다'}, `오늘 AI 호출 ${a.used_today}/${a.daily_limit}회 사용(하루 상한은 설정값)`),
      h('span', {class: 'd2 flat', text: ` · 기본 기준 ${j.default_basis || '-'} · 조직 ${j.orgs}개 · 적재 ${Number(j.fact_rows).toLocaleString('ko-KR')}행`}));
    if (CHAT.stamp && j.stamp && CHAT.stamp !== j.stamp) newConversation(true);        // 보고서 실행·버전이 바뀌면 대화 자동 초기화
    CHAT.stamp = j.stamp;
    drawQuick(j.quick || []); drawIntro();
  } catch (e) { dot('dotSrv', 'bad', '서버에 연결할 수 없음'); dot('dotDb', '', ''); dot('dotAi', '', ''); line.replaceChildren(h('span', {class: 'chip bad', text: '서버 연결 실패'}), ` ${chatSrv()} 에 연결할 수 없습니다. 서버를 켜고 주소를 확인하세요.`); }
}
function aiDot(a) { dot('dotAi', a.state === 'ready' ? 'ok' : a.state === 'limit' ? 'warn' : 'bad', a.state === 'ready' ? 'AI 사용 가능' : a.state === 'limit' ? '오늘 AI 호출 상한 도달(예시 질문은 AI 없이 동작)' : 'AI 키 없음(예시 질문은 AI 없이 동작)'); }
async function chatLoad() {
  try { const r = await fetch(chatSrv() + '/v1/chat/load', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: '{}'}); const j = await r.json(); if (!r.ok) throw new Error(j.detail || r.status); await chatStatus(); }
  catch (e) { el('chatStatus').append(h('div', {class: 'msg fail', text: '불러오지 못했습니다: ' + e.message})); }
}
function drawQuick(list) {
  const box = el('chatQuick'); box.replaceChildren(h('span', {class: 'd2 flat', text: '예시 질문'}), ...list.map(q => h('button', {class: 'btn', type: 'button', onclick: () => ask(q.label, {quick_id: q.id})}, q.label)));
}

// ---------- 대화 기억(서버 메모리) ----------
function drawChips(chips) {
  const box = el('chatChips'); if (!box) return; box.replaceChildren();
  if (!chips || !chips.length) { box.style.display = 'none'; return; }
  box.style.display = 'flex'; box.append(h('span', {class: 'd2 flat', text: '이어 쓰는 조건'}),
    ...chips.map(c => h('button', {class: 'chipx', type: 'button', title: '누르면 이 조건을 해제합니다', onclick: () => clearChip(c.key)}, c.label + ' ×')));
}
async function clearChip(key) {
  try { const r = await fetch(chatSrv() + '/v1/chat/memory', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({conversation_id: CHAT.cid, clear: [key]})}); drawChips((await r.json()).chips); } catch (e) {}
}
async function newConversation(silent) {
  const old = CHAT.cid; CHAT.cid = newCid(); CHAT.pending = null; drawChips([]);
  try { await fetch(chatSrv() + '/v1/chat/memory', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({conversation_id: old, reset: true})}); } catch (e) {}
  if (!silent) { el('chatConv').replaceChildren(); bubbleIntro(); } else el('chatConv').append(h('div', {class: 'cb ai intro'}, '보고서 실행이나 버전이 바뀌어 대화를 새로 시작했습니다.'));
}

function bubble(cls, ...kids) { const b = h('div', {class: 'cb ' + cls}, ...kids); el('chatConv').append(b); b.scrollIntoView({block: 'end'}); return b; }
function sheetTabs(res) {
  const tabs = {결과: () => resultTable(res), 요약: () => h('dl', {class: 'kv'}, h('dt', {text: '질문'}), h('dd', {text: CHAT.lastQuestion}), h('dt', {text: '기준'}), h('dd', {text: res.basis || res.version}), h('dt', {text: '조회 행수'}), h('dd', {text: String(res.row_count) + (res.truncated ? ' (상한으로 잘림)' : '')}),
    h('dt', {text: '설명 출처'}), h('dd', {text: res.answer_source === 'ai' ? 'AI' : '기본문구'})),
    근거: () => h('div', null, h('pre', {class: 'sqlbox', text: res.sql}), h('div', {class: 'd2', text: '인자: ' + JSON.stringify(res.params)}), h('ul', {class: 'ev'}, res.evidence.map(e => h('li', {text: `${e.type}: ${[e.version, e.ref, e.src].filter(Boolean).join(' · ')}`}))))};
  const body = h('div', {class: 'sheetbody'}); const bar = h('div', {class: 'seg'});
  const show = k => { [...bar.children].forEach(b => b.classList.toggle('on', b.dataset.k === k)); body.replaceChildren(tabs[k]()); };
  Object.keys(tabs).forEach(k => bar.append(h('button', {type: 'button', 'data-k': k, onclick: () => show(k)}, k)));
  show('결과'); return h('div', {class: 'sheets'}, bar, body);
}
function resultTable(res) {
  if (!res.columns.length) return nd();
  return h('div', null, h('div', {class: 'scroll sm'}, h('table', {class: 't'}, h('thead', null, h('tr', null, res.columns.map(c => h('th', {text: c})))),
    h('tbody', null, res.rows_preview.map(r => h('tr', null, r.map((v, i) => h('td', {class: typeof v === 'number' ? 'r' : '', text: fmtCell(v, res.columns[i])}))))))),
    h('div', {class: 'd2 flat', text: `미리보기 ${res.rows_preview.length}행 / 전체 ${res.row_count}행${res.truncated ? '(행 상한으로 잘림)' : ''} · 금액(_won)은 원 단위 정확값`}));
}
function stepsBox(res) {
  const m = res.metrics || {}, a = res.ai;
  return h('details', {class: 'steps'}, h('summary', {text: '처리 과정 보기'}), h('ol', null, res.steps.map(s => h('li', {class: 'st-' + s.status}, h('b', {text: s.step}), h('span', {class: 'chip sm ' + ({ok: 'ok', fail: 'bad', clarify: 'wait', unknown: 'nj', skip: 'nj', blocked: 'nj'}[s.status] || ''), text: s.status}),
    ` ${s.detail}`, s.ms !== null && s.ms !== undefined ? h('span', {class: 'd2 flat', text: ` · ${s.ms}ms`}) : null))),
    h('div', {class: 'd2 flat', text: `처리: ${({rule: '규칙(AI 없음)', ai: 'AI', quick: '예시 질문 고정 SQL', clarify: '되묻기', unknown: '모름', blocked: '차단', error: '오류'}[m.handled_by] || m.handled_by || '-')} · AI 호출 ${a.calls}회 · 토큰 ${a.input_tokens}+${a.output_tokens} · 자기 수정 ${m.fix_count || 0}회 · 저장 SQL 재사용 ${a.cache_hits}회 · ${m.seconds ?? '-'}초${a.failed ? ' · 실패 ' + a.failed : ''}${a.reasons.length ? ' · 이유: ' + a.reasons.join(' / ') : ''}`}));
}
function addUsage(a) {
  const u = CHAT.usage; u.requests++; u.calls += a.calls; u.cache_hits += a.cache_hits; u.input_tokens += a.input_tokens; u.output_tokens += a.output_tokens; u.failed += a.failed;
  (a.reasons || []).forEach(r => { if (!u.reasons.includes(r)) u.reasons.push(r); });
  if (typeof drawAiBar === 'function') drawAiBar();
}
function optionList(cl, onPick) {
  const wrap = h('div', {class: 'optwrap'}); const row = h('div', {class: 'opts'});
  const draw = (f) => { row.replaceChildren(...(cl.options || []).filter(o => !f || o.includes(f)).map(o => h('button', {class: 'btn', type: 'button', onclick: () => onPick(o, (cl.options || []).indexOf(o))}, o))); };
  if ((cl.options || []).length >= 20) { const q = h('input', {type: 'text', class: 'optsearch', placeholder: `이름으로 찾기 (${cl.options.length}개)`}); q.addEventListener('input', () => draw(q.value.trim())); wrap.append(q); }
  draw(''); wrap.append(row); return wrap;
}
function answerClarify(opt, idx) {
  const cl = CHAT.pending; if (!cl) return ask(opt, {});
  CHAT.pending = null;
  if (cl.slot === 'quick') return ask(opt, {quick_id: (cl.quick_ids || [])[idx]});
  if (cl.slot === 'org' && cl.replace) return ask(CHAT.lastQuestion.replace(cl.replace, opt), {});
  if (cl.slot === 'dup' && cl.replace) return ask(CHAT.lastQuestion.replace(cl.replace, opt), {});
  if (cl.slot === 'compare') return ask(`${opt} 지난달 대비 변화`, {});
  return ask(CHAT.lastQuestion + ` (${opt})`, {});
}
function renderAnswer(res) {
  addUsage(res.ai); drawChips(res.memory && res.memory.chips);
  if (res.status === 'clarify') {
    const cl = res.clarify; CHAT.pending = cl; const b = bubble('ai', h('div', {class: 'answer-label', text: '되묻기'}), h('p', {text: cl.question}));
    b.append(optionList(cl, (o, i) => answerClarify(o, i)), stepsBox(res)); return;
  }
  CHAT.pending = null;
  if (res.status === 'unknown') { bubble('ai unknown', h('div', {class: 'answer-label', text: '모릅니다'}), h('p', {text: res.answer_text}), stepsBox(res)); return; }
  if (res.status === 'blocked') { bubble('ai unknown', h('div', {class: 'answer-label', text: '조회만 가능합니다'}), h('p', {text: res.answer_text}), stepsBox(res)); return; }
  if (res.status === 'error') { bubble('ai err', h('div', {class: 'answer-label', text: '오류'}), h('p', {text: res.answer_text}), res.notice ? h('div', {class: 'd2', text: res.notice}) : null, stepsBox(res)); return; }
  const b = bubble('ai', h('div', {class: 'answer-label'}, h('span', {class: 'chip sm', title: '사용한 버전과 뜻', text: `기준: ${res.basis || res.version}`}), res.scope ? h('span', {class: 'chip sm ok', text: res.scope}) : null,
    res.followup && res.handled_by === 'rule' ? h('span', {class: 'chip sm', text: '이전 조건 이어서'}) : null,
    res.sql_source ? h('span', {class: 'chip sm', text: 'SQL: ' + ({ai: 'AI 생성', cache: '저장된 SQL', quick: '고정 SQL', rule: '규칙'}[res.sql_source] || res.sql_source)}) : null,
    res.answer_source === 'ai' ? h('span', {class: 'chip sm ai', text: 'AI 문장'}) : null, res.notice ? h('span', {class: 'chip sm wait', text: res.notice}) : null), h('p', {class: 'ans', text: res.answer_text}));
  b.append(stepsBox(res), res.row_count ? sheetTabs(res) : null);
  const acts = h('div', {class: 'answer-actions'}); b.append(acts);
  if (res.file) acts.append(h('a', {class: 'btn dark', href: chatSrv() + res.file.url, download: res.file.name}, '엑셀 다운로드 ↓'));
  acts.append(h('button', {class: 'btn', type: 'button', onclick: () => addToReport(res)}, '보고서에 넣기'));
}
var ADDED = [];      // "보고서에 넣기"로 담은 조회 결과(화면을 다시 그려도 유지). 첫 화면 그릴 때는 아직 정의 전(undefined)일 수 있다
function chatCard(item) {
  const res = item.res;
  const card = h('div', {class: 'card', 'data-card': 'chat'}, h('div', {class: 'chead'}, h('div', null, h('h2', {text: 'AI 질의: ' + item.question}), h('div', {class: 'sub', text: `기준 ${res.basis || res.version} · ${res.row_count}행 · ${res.scope || ''}`})),
    h('div', {class: 'acts'}, h('button', {class: 'btn', type: 'button', onclick: () => { ADDED.splice(ADDED.indexOf(item), 1); card.remove(); }}, '빼기'))), h('div', {class: 'oneline'}, res.answer_text),
    resultTable({...res, rows_preview: res.rows_preview.slice(0, 10)}), h('div', {class: 'basis', text: '보고 기준 · 조회 결과(AI 작업실). 숫자는 DB 조회 값이며 처리 과정은 작업실에서 확인'}));
  return card;
}
function renderAdded() {
  const wrap = document.getElementById('cards'); if (!wrap) return; const km = wrap.querySelector('[data-card="km"]');
  (ADDED || []).forEach(it => { const c = chatCard(it); km ? wrap.insertBefore(c, km) : wrap.append(c); });
}
function addToReport(res) {
  const wrap = document.getElementById('cards'); if (!wrap) return; const it = {question: CHAT.lastQuestion, res}; ADDED.push(it);
  const km = wrap.querySelector('[data-card="km"]'), c = chatCard(it); km ? wrap.insertBefore(c, km) : wrap.append(c); c.scrollIntoView({behavior: 'smooth', block: 'center'});
}
async function ask(question, opts) {
  opts = opts || {}; if (CHAT.busy) return; question = (question || '').trim(); if (!question) return;
  CHAT.busy = true; CHAT.lastQuestion = question; bubble('user', question);
  const wait = bubble('ai wait', '조회 중…'); el('chatSend').disabled = true;
  try {
    const r = await fetch(chatSrv() + '/v1/chat', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({question, quick_id: opts.quick_id || null, conversation_id: CHAT.cid})});
    const j = await r.json(); wait.remove();
    if (!r.ok) throw new Error(j.detail ? JSON.stringify(j.detail).slice(0, 200) : r.status);
    renderAnswer(j);
  } catch (e) { wait.remove(); bubble('ai err', h('div', {class: 'answer-label', text: '요청 실패'}), h('p', {text: '서버 응답을 받지 못했습니다: ' + e.message})); }
  CHAT.busy = false; el('chatSend').disabled = false; chatStatus();
}
function submitText(raw) {
  const q = (raw || '').trim(); if (!q) return;
  const names = (CHAT.status && CHAT.status.org_names) || [];
  if (CHAT.pending && ['org', 'ai_org', 'dup'].includes(CHAT.pending.slot)) {     // 되묻기 대기 중 이름을 직접 입력해도 인식
    const hit = names.filter(n => q === n || q.includes(n)).sort((a, b) => b.length - a.length)[0];
    if (hit) { bubble('user', q); const cl = CHAT.pending; CHAT.pending = null; return ask(cl.replace ? CHAT.lastQuestion.replace(cl.replace, hit) : CHAT.lastQuestion + ` (${hit})`, {}); }
  }
  ask(q);
}
// 카드의 "AI로 보기": 해당 카드 범위를 질문 칸에 채운다(바로 보내지 않음)
const CARD_Q = {summary: m => `${m}월 조직별 매출, 영업비용, 영업이익을 보여줘`, bridge: () => '조직별 월별 영업이익 추이를 보여줘', trend: () => '월별 매출, 영업비용, 영업이익 추이를 보여줘', org: m => `${m}월 조직별 영업이익을 큰 순서로 보여줘`,
  contrib: m => `${m}월 조직별 영업이익 기여도를 보여줘`, opcomp: m => `${m}월 전사 매출, 영업비용, 영업이익 합계를 보여줘`, causes: () => '수락된 소명을 Gap과 함께 보여줘', cost: m => `${m}월 비용 상위 항목 20개를 보여줘`,
  changes: () => 'Gap 목록과 소명 현황을 보여줘', km: m => `${m}월 영업이익 요약을 보여줘`};
function chatFill(cardId) {
  const m = Number(String(D.report[state].base_month).split('-')[1]); const q = (CARD_Q[cardId] || (() => '이 카드의 데이터를 보여줘'))(m);
  document.body.classList.add('chat-open'); const ta = el('chatText'); ta.value = q; ta.focus();
}
function aiViewButton(cardId) { return h('button', {class: 'btn', type: 'button', title: '이 카드의 범위를 AI 작업실 질문에 채웁니다', onclick: () => chatFill(cardId)}, 'AI로 보기'); }

(function initChat() {
  const sv = el('chatSrv'); if (!sv) return; sv.value = chatSrv();
  el('chatForm').addEventListener('submit', ev => { ev.preventDefault(); const t = el('chatText'); const q = t.value; t.value = ''; submitText(q); });
  el('chatText').addEventListener('keydown', ev => { if (ev.key === 'Enter' && !ev.shiftKey) { ev.preventDefault(); el('chatForm').requestSubmit(); } });
  sv.addEventListener('change', chatStatus);
  el('chatNew').addEventListener('click', () => newConversation(false));
  el('chatClose').addEventListener('click', () => document.body.classList.remove('chat-open'));
  el('chatOpen').addEventListener('click', () => document.body.classList.add('chat-open'));
  if (window.innerWidth >= 1300) document.body.classList.add('chat-open');
  bubbleIntro(); chatStatus().then(() => {
    const p = new URLSearchParams(location.hash.slice(1));       // 확인용: #ask=질문&quick=아이디 로 열면 자동으로 질문을 보낸다
    if (p.get('ask') || p.get('quick')) ask(p.get('ask') || '조회', {quick_id: p.get('quick') || undefined});
  });
})();
function exampleText() {   // 예시 질문은 DB 에서 읽은 값(기본 월, 조직 이름 등)으로 만든다. 값이 없으면 형식만 보인다
  const st = CHAT.status || {}; const m = st.latest_month, o = (st.org_samples || [])[0];
  return {a: o && m ? `${m}월 ${o} 비용이 큰 항목 5개` : '<월> <조직> 비용이 큰 항목 5개', b: m ? `${m}월 전사 영업비용이 가장 큰 항목 10개` : '<월> 전사 영업비용이 가장 큰 항목 10개', c: '잠정 Gap 목록'};
}
function bubbleIntro() { const box = h('div', {class: 'cb ai intro', id: 'chatIntro'}); el('chatConv').append(box); drawIntro(); }
function drawIntro() {
  const box = el('chatIntro'); if (!box) return; const e = exampleText();
  box.replaceChildren(h('b', {text: '무엇이 궁금하세요?'}), h('div', {class: 'd2', text: `한국어로 물어보면 DB 에서 조회해 엑셀과 설명 문장을 만들어 드립니다. 조직을 말하지 않으면 전사 기준으로 답하고, 기준 버전은 질문에서 읽습니다(기본은 확정 최신). 예) "${e.a}", "${e.b}", "${e.c}"`}));
  const ta = el('chatText'); if (ta) ta.placeholder = `예: ${e.a} (Enter 전송)`;
}
