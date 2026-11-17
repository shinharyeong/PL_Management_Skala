window.addEventListener('error',function(e){var d=document.createElement('div');d.id='jserr';d.style.display='none';d.textContent=(e.message||'error')+' @'+(e.lineno||'');document.body&&document.body.appendChild(d);});

const D = window.DATA;
const NA = '데이터 없음';
const srcLabel = s => (s === 'ai' ? 'AI' : '기본문구');   // 문장 출처 표시: AI 가 쓴 문장 / 템플릿(기본문구)
function h(tag, props, ...kids){const e=document.createElement(tag);
  if(props) for(const k in props){const v=props[k]; if(v===null||v===undefined||v===false) continue;
    if(k==='class') e.className=v; else if(k==='text') e.textContent=v; else if(k.startsWith('on')) e.addEventListener(k.slice(2),v); else e.setAttribute(k,v===true?'':v);}
  for(const c of kids.flat(Infinity)){ if(c===null||c===undefined||c===false) continue; e.append(c.nodeType?c:document.createTextNode(String(c))); }
  return e;}
function nd(msg){return h('span',{class:'nd',text:msg||NA});}
function won(s){ if(s===null||s===undefined) return NA; const neg=String(s).startsWith('-'); const d=String(s).replace('-',''); return (neg?'-':'')+d.replace(/\B(?=(\d{3})+(?!\d))/g,',')+'원'; }
const tip=document.getElementById('tip');
function showTip(ev,lines){tip.replaceChildren(...lines.map(l=>h('div',{text:l})));tip.style.display='block';tip.style.left=(ev.clientX+14)+'px';tip.style.top=(ev.clientY+14)+'px';}
function hideTip(){tip.style.display='none';}
function chipVerdict(v){return v==='ACCEPTED'?['수락','ok']:v==='REJECTED'?['반려','bad']:[v||NA,'wait'];}
function chipStatus(s){return s==='수락'?'ok':s==='반려'?'bad':'wait';}
function copyText(t,btn){const done=()=>{const o=btn.textContent;btn.textContent='복사됨';setTimeout(()=>btn.textContent=o,1200)};
  const fb=()=>{const ta=document.createElement('textarea');ta.value=t;document.body.append(ta);ta.select();try{document.execCommand('copy');done()}catch(e){btn.textContent='복사 실패'}ta.remove();};
  if(navigator.clipboard&&window.isSecureContext){navigator.clipboard.writeText(t).then(done,fb);}else fb();}
const gapBadge = document.getElementById('gapBadge'); if (gapBadge) gapBadge.textContent = D.gapCounts.gaps_total;     // 사이드바는 팀 앱이 제공하므로 없을 수 있다
