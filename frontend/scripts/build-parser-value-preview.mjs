import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const outputDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../public/previews');
const elements = [];
const ink = '#171717';
const muted = '#717171';
const gray = '#D6D6D6';

/** 편집 가능한 장표 도형 하나를 공통 모델에 추가한다.
 * Args: kind는 rect, text, line 중 하나, 좌표와 크기는 CSS px, options는 표시 속성이다.
 * Returns: 없음. Raises: 없음. Side Effects: 메모리의 elements 배열에 도형을 추가한다.
 */
function shape(kind, x, y, w, h, options = {}) {
  elements.push({ kind, x, y, w, h, ...options });
}

/** 단색 사각형을 모델에 추가한다.
 * Args: 위치와 크기, 채움색, 선택 테두리색. Returns: 없음. Raises: 없음.
 * Side Effects: elements 배열에 사각형을 추가한다.
 */
function rect(x, y, w, h, fill = '#FFFFFF', stroke, options = {}) {
  shape('rect', x, y, w, h, { fill, stroke, ...options });
}

/** 독립적으로 편집할 수 있는 텍스트 상자를 추가한다.
 * Args: 문자열과 위치 및 크기, 글자 크기, 선택 스타일. Returns: 없음. Raises: 없음.
 * Side Effects: elements 배열에 텍스트를 추가한다.
 */
function text(value, x, y, w, h, fontSize = 20, options = {}) {
  shape('text', x, y, w, h, { text: value, fontSize, color: ink, fontWeight: 400, ...options });
}

/** 두 지점 사이의 선을 추가한다.
 * Args: 시작점, 끝점, 선택 색과 두께 및 점선 여부. Returns: 없음. Raises: 없음.
 * Side Effects: elements 배열에 선을 추가한다.
 */
function line(x1, y1, x2, y2, color = gray, strokeWidth = 1, dash = false) {
  shape('line', x1, y1, x2 - x1, y2 - y1, { stroke: color, strokeWidth, dash });
}

/** 도식의 읽는 방향을 작은 화살표로 표시한다.
 * Args: 시작 x와 중심 y, 화살표 길이 및 선택 색상. Returns: 없음. Raises: 없음.
 * Side Effects: 편집 가능한 선 세 개를 추가한다.
 */
function arrow(x, y, length = 24, color = '#888888') {
  line(x, y, x + length, y, color, 2);
  line(x + length - 6, y - 5, x + length, y, color, 2);
  line(x + length - 6, y + 5, x + length, y, color, 2);
}

/** 원본 파일을 설명하는 편집 가능한 엑셀 표 도식을 추가한다.
 * Args: x,y,w,h는 도식 영역, title은 파일 또는 시트 이름, rows는 표시 문자열 배열이다.
 * Returns: 없음. Raises: 없음. Side Effects: 표 배경, 구분선, 셀 텍스트를 모델에 추가한다.
 */
function sheet(x, y, w, h, title, rows, options = {}) {
  rect(x + 4, y + 5, w, h, '#E4E4E4');
  rect(x, y, w, h, '#FFFFFF', '#BFBFBF');
  rect(x, y, w, 27, options.dark ? '#2B2B2B' : '#E7E7E7');
  text(title, x + 10, y + 5, w - 20, 20, options.fontSize || 13, { color: options.dark ? '#FFFFFF' : '#454545', fontWeight: 700 });
  const rowHeight = (h - 27) / rows.length;
  rows.forEach((row, i) => {
    const ry = y + 27 + i * rowHeight;
    if (i) line(x, ry, x + w, ry);
    if (options.highlight === i) rect(x + 1, ry + 1, w - 2, rowHeight - 2, '#E4E4E4');
    if (Array.isArray(row)) {
      line(x + w * .4, ry, x + w * .4, ry + rowHeight);
      text(row[0], x + 8, ry + 8, w * .4 - 12, rowHeight - 12, options.fontSize || 13, { color: muted });
      text(row[1], x + w * .4 + 7, ry + 8, w * .6 - 13, rowHeight - 12, options.fontSize || 13, { fontWeight: options.highlight === i ? 700 : 400 });
    } else text(row, x + 9, ry + 8, w - 18, rowHeight - 12, options.fontSize || 13);
  });
}

/** 단계의 구현 결과를 작은 검정 체크 표식으로 표시한다.
 * Args: x,y는 표식 왼쪽 위 좌표다. Returns: 없음. Raises: 없음.
 * Side Effects: 모델에 정사각형과 체크 문자를 추가한다.
 */
function passed(x, y) {
  rect(x, y, 25, 25, ink);
  text('✓', x + 3, y + 1, 21, 24, 20, { color: '#FFFFFF', fontWeight: 700 });
}

/** 회색 입력 그림에서 사라진 정보의 자리를 점선으로 표시한다.
 * Args: x,y,w,h는 자리 영역, label은 남길 짧은 식별 문구다.
 * Returns: 없음. Raises: 없음. Side Effects: 점선 테두리와 문구를 모델에 추가한다.
 */
function missingTile(x, y, w, h, label) {
  line(x, y, x + w, y, '#AAAAAA', 1, true);
  line(x + w, y, x + w, y + h, '#AAAAAA', 1, true);
  line(x + w, y + h, x, y + h, '#AAAAAA', 1, true);
  line(x, y + h, x, y, '#AAAAAA', 1, true);
  text(label, x + 4, y + 6, w - 8, h - 6, 16, { color: '#969696', fontWeight: 600, align: 'center' });
}

/** 단계 사이를 한 개의 가는 상승 화살표로 연결한다.
 * Args: x,y는 시작점이다. Returns: 없음. Raises: 없음.
 * Side Effects: 모델에 대각선과 화살촉 두 개를 추가한다.
 */
function risingArrow(x, y) {
  line(x, y, x + 34, y - 32, '#737373', 2);
  line(x + 22, y - 31, x + 34, y - 32, '#737373', 2);
  line(x + 34, y - 20, x + 34, y - 32, '#737373', 2);
}

/** 비교 그림의 출구를 짧은 단절 화살표와 차단벽으로 닫는다.
 * Args: x,y는 차단벽 시작 위치, h는 높이, entryY는 막히는 화살표의 중심 높이다.
 * Returns: 없음. Raises: 없음. Side Effects: 모델에 화살표·차단벽·양 끝선을 추가한다.
 */
function blockedExit(x, y, h, entryY) {
  line(x - 29, entryY, x - 21, entryY, '#777777', 2);
  line(x - 14, entryY, x - 5, entryY, '#777777', 2);
  line(x - 11, entryY - 5, x - 5, entryY, '#777777', 2);
  line(x - 11, entryY + 5, x - 5, entryY, '#777777', 2);
  rect(x, y, 5, h, '#777777');
  line(x - 4, y, x + 9, y, '#777777', 2);
  line(x - 4, y + h, x + 9, y + h, '#777777', 2);
}

text('기술 선정', 60, 38, 250, 25, 17, { fontWeight: 700 });
text('경영팀을 부탁해', 1240, 38, 300, 25, 17, { fontWeight: 700, align: 'right' });
line(60, 80, 1540, 80, '#474747');
rect(60, 114, 5, 42, ink);
text('기술 선정 · 자체 엑셀 파서', 80, 107, 1400, 64, 45, { fontWeight: 800 });
text('원본의 금액과 위치를 그대로 읽고, 확인된 업무 규칙으로 검증합니다.', 60, 177, 1480, 32, 21, { color: '#666666' });

rect(60, 235, 1480, 465, '#FFFFFF', '#BDBDBD');
rect(60, 235, 1480, 77, ink);
text('자체 파서 엔진', 88, 250, 650, 56, 43, { fontWeight: 800, color: '#FFFFFF' });
text('원본 추출부터 수정 안내까지, 전 단계 연결', 820, 269, 690, 27, 21, { color: '#E2E2E2', align: 'right' });

// 검토·실험의 한계는 해당 단계의 작은 입력 그림에만 표시한다.
const cols = [95, 455, 815, 1175];
const labels = ['원본 직접 추출', '코드·헤더 연결', '합계 대조', '수정 위치 안내'];
const stepTops = [491, 446, 401, 356];

// 이미지는 값을 보여주지만 원본 셀 주소와 수식의 연결을 그대로 전달하지 않는다.
text('OCR · VLM', 95, 335, 290, 27, 20, { fontWeight: 700, color: '#777777' });
rect(95, 373, 128, 73, '#EEEEEE', '#BBBBBB');
line(106, 390, 212, 390, '#CCCCCC');
line(106, 426, 212, 426, '#CCCCCC');
text('1,250,000', 101, 399, 116, 25, 20, { color: '#888888', fontWeight: 700, align: 'center' });
line(223, 407, 242, 407, '#B8B8B8', 2, true);
line(256, 398, 277, 385, '#B8B8B8', 2, true);
line(256, 418, 286, 432, '#B8B8B8', 2, true);
line(245, 402, 251, 396, '#8E8E8E', 2);
line(248, 415, 254, 409, '#8E8E8E', 2);
missingTile(279, 369, 105, 32, '셀 주소');
missingTile(289, 417, 95, 32, '수식');
blockedExit(420, 373, 76, 407);
text('구조 보존 단계에서 한계', 95, 460, 307, 23, 16, { color: '#777777', fontWeight: 600 });

// LLM 실험은 코드가 읽은 값에 헤더 문맥을 연결하는 단계에서 입력 누락이 확인됐다.
text('LLM 구조 해석', 455, 335, 290, 27, 20, { fontWeight: 700, color: '#777777' });
text('입력 문맥 누락', 640, 338, 115, 23, 15, { color: '#888888', align: 'right' });
rect(455, 373, 78, 31, '#EEEEEE');
text('조직', 459, 379, 70, 23, 16, { color: '#8A8A8A', align: 'center' });
missingTile(549, 368, 112, 36, '헤더 ?');
rect(677, 373, 78, 31, '#EEEEEE');
text('기간', 681, 379, 70, 23, 16, { color: '#8A8A8A', align: 'center' });
line(494, 404, 494, 422, '#C2C2C2');
line(494, 422, 571, 422, '#C2C2C2');
line(716, 404, 716, 422, '#C2C2C2');
line(716, 422, 639, 422, '#C2C2C2');
rect(571, 412, 68, 25, '#EEEEEE');
text('금액', 574, 415, 62, 22, 15, { color: '#888888', align: 'center' });
blockedExit(782, 370, 67, 422);

labels.forEach((label, i) => {
  passed(cols[i], stepTops[i] + 4);
  text(label, cols[i] + 35, stepTops[i], 270, 39, 27, { fontWeight: 800 });
});
risingArrow(405, 581);
risingArrow(765, 536);
risingArrow(1125, 491);

sheet(95, 536, 300, 112, '원본 값 그대로', [['금액', '1,250,000'], ['원본 셀', '손익제출!N39'], ['수식', '=SUM(…)']], { highlight: 0, dark: true, fontSize: 15 });
sheet(455, 491, 300, 112, '확인된 근거로 연결', [['조직', 'D100'], ['항목', '설비 임차료'], ['기간·단위', '2026-11 / 원']], { dark: true, fontSize: 15 });
sheet(815, 446, 300, 112, '상세 ↔ 답안지', [['상세 A', '500,000'], ['상세 B', '750,000'], ['합계 일치', '1,250,000']], { highlight: 2, dark: true, fontSize: 15 });
sheet(1175, 401, 300, 112, '확인할 셀을 반환', [['원본 셀', '손익제출!N40'], ['금액', '빈칸'], ['수정 안내', '필수 금액 누락']], { highlight: 1, dark: true, fontSize: 15 });
text('값·수식·셀 위치 보존', 95, 657, 305, 27, 18, { fontWeight: 600 });
text('조직·항목·기간·단위 확인', 455, 612, 305, 27, 18, { fontWeight: 600 });
text('상세와 집계 근거 대조', 815, 567, 305, 27, 18, { fontWeight: 600 });
text('빈칸·코드 임의 보정 없음', 1175, 522, 305, 27, 18, { fontWeight: 600 });

text('한 셀도 추측하지 않고,\n끝까지 근거로 연결합니다.', 1000, 611, 477, 68, 28, { fontWeight: 800, align: 'right' });
text('OCR·VLM은 이미지 변환 방식의 검토, LLM은 본 프로젝트의 시트·헤더 입력 누락 실험 결과를 도식화. 금액·셀은 설명용 예시.', 60, 704, 1480, 16, 11, { color: muted, align: 'right' });

line(60, 720, 1540, 720, '#555555');
text('9가지 유형 · 81개 조합, 465만 금액 셀 전부 일치', 60, 734, 1020, 38, 29, { fontWeight: 800 });
const verificationTypes = [
  '사업구조 차등', '결측·코드 오류', '숫자 문자열',
  '흑자·적자 혼재', '중복·수정본', '조직개편·코드 변경',
  '계절성·일회성', '계획·실적 괴리', '대용량 편중',
];
verificationTypes.forEach((label, i) => {
  const x = 60 + (i % 3) * 226;
  const y = 787 + Math.floor(i / 3) * 27;
  text('✓', x, y - 1, 25, 24, 19, { fontWeight: 800 });
  text(label, x + 27, y, 198, 24, 17, { fontWeight: 600, color: '#494949' });
});
line(748, 785, 748, 863, '#BBBBBB');
text('1,377개 파일 전수 대조', 774, 783, 300, 29, 21, { fontWeight: 600 });
text('4,657,338셀', 774, 816, 306, 39, 31, { fontWeight: 800 });
arrow(1062, 830, 30, ink);
rect(1110, 733, 430, 138, ink);
text('원본 금액 일치율', 1134, 747, 381, 28, 20, { color: '#FFFFFF', fontWeight: 600, align: 'center' });
text('100%', 1130, 772, 390, 101, 91, { color: '#FFFFFF', fontWeight: 800, align: 'center' });
text('11/17 검증 · 9개 유형 × 9개 마감월. 실제 금액 셀 불일치 0개, 원본 파일 변경 0건(SHA-256). 원본 오류·빈칸은 그대로 보존하며 제출 승인율을 뜻하지 않음.', 60, 882, 1480, 17, 11, { color: '#777777' });

const notes = '11/17 검증: parser/docs/development/parser-comparison.md. 조직 원본 1,377개, 실제 금액 셀 4,657,338개 원본 일치. 100%는 원본 금액 보존 결과이며 검증 통과율이나 전체 정확도가 아님. 0건은 전후 SHA-256 동일. 9개 마감월은 1~9월 검증 자료. OCR/VLM은 방식 검토, LLM은 11/12 비교 실험에서 시트·헤더 입력 누락 확인. 표의 화면·금액·셀은 설명용 예시. 합계 대조는 지원하는 집계 근거에 한정. Python 추출 및 검증, Java 업무 검증을 포함한 전체 흐름.';
const model = { width: 1600, height: 900, background: '#F4F4F4', elements, notes };
await fs.writeFile(path.join(outputDir, 'parser-value-slide.json'), JSON.stringify(model, null, 2));

const html = `<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>기술 선정 · 자체 엑셀 파서</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#dedede;color:#171717;font-family:"Apple SD Gothic Neo","Malgun Gothic",sans-serif;-webkit-font-smoothing:antialiased}.toolbar{max-width:1600px;width:calc(100% - 48px);margin:0 auto;min-height:66px;display:flex;align-items:center;justify-content:space-between;gap:16px;font-size:13px}.toolbar strong{font-weight:600}.actions{display:flex;gap:8px}button,.actions a{border:1px solid #bbb;border-radius:3px;padding:8px 13px;font:inherit;text-decoration:none;color:#222;background:#f5f5f5;cursor:pointer}.actions a{background:#202020;color:white;border-color:#202020}.shell{width:min(1600px,calc(100% - 48px));height:calc(900px * var(--scale,.75));margin:auto;box-shadow:0 8px 30px #00000012}.slide{position:relative;width:1600px;height:900px;transform:scale(var(--scale,.75));transform-origin:top left;overflow:hidden;background:#f4f4f4}.element{position:absolute}.text{white-space:pre-wrap;word-break:keep-all;line-height:1.15;letter-spacing:-.35px;padding:0;margin:0}.editing .text{outline:1px dashed #aaa;cursor:text}.text:focus{outline:2px solid #555;background:#fff}.footer{max-width:1600px;width:calc(100% - 48px);margin:15px auto;font-size:12px;color:#777;display:flex;justify-content:space-between}details{max-width:1600px;width:calc(100% - 48px);margin:24px auto;font-size:13px;line-height:1.8;color:#5e5e5e}summary{cursor:pointer}body:fullscreen{display:flex;align-items:center;background:#222}body:fullscreen .toolbar,body:fullscreen .footer,body:fullscreen details{display:none}body:fullscreen .shell{width:min(100vw,calc(100vh * 16 / 9));box-shadow:none}button:focus-visible,a:focus-visible{outline:2px solid #222;outline-offset:3px}@media(max-width:750px){.toolbar{align-items:flex-start;padding:15px 0;flex-direction:column}.actions{flex-wrap:wrap}.toolbar,.shell,.footer,details{width:calc(100% - 24px)}}@media print{@page{size:16in 9in;margin:0}.toolbar,.footer,details{display:none}body{background:#fff}.shell{width:100vw;box-shadow:none}.slide{print-color-adjust:exact;-webkit-print-color-adjust:exact}.editing .text{outline:0}}
</style></head><body>
<header class="toolbar"><strong>기술 선정 · 엑셀 파서</strong><div class="actions"><button id="edit" type="button" aria-pressed="false">문구 편집</button><button id="save" type="button">수정한 HTML 저장</button><button id="full" type="button">전체화면</button></div></header>
<main class="shell" aria-label="자체 엑셀 파서 소개 발표 장표"><article class="slide" id="slide"></article></main>
<div class="footer"><span>문구 편집을 켜면 장표에서 텍스트를 바로 수정할 수 있습니다.</span><span>16:9 · 텍스트와 도형 편집 가능</span></div>
<details><summary>발표 메모와 검증 범위</summary><p>${notes}</p><p>HTML에서 수정한 문구는 ‘수정한 HTML 저장’으로 보관합니다.</p></details>
<script id="slide-data" type="application/json">${JSON.stringify(model)}</script>
<script>
const model=JSON.parse(document.getElementById('slide-data').textContent);const slide=document.getElementById('slide');const shell=document.querySelector('.shell');
/** 공통 도형 모델을 HTML 요소로 표시한다. Args: 없음. Returns: 없음. Raises: DOM 오류. Side Effects: 장표 DOM을 생성한다. */
function renderSlide(){slide.replaceChildren();model.elements.forEach((item,index)=>{const el=document.createElement('div');el.className='element '+item.kind;el.dataset.index=index;Object.assign(el.style,{left:item.x+'px',top:item.y+'px',width:item.w+'px',height:item.h+'px'});if(item.kind==='text'){el.textContent=item.text;Object.assign(el.style,{fontSize:item.fontSize+'px',fontWeight:item.fontWeight,color:item.color,textAlign:item.align||'left'});el.setAttribute('spellcheck','false')}else if(item.kind==='rect'){el.style.background=item.fill||'transparent';if(item.stroke)el.style.border='1px solid '+item.stroke}else{const length=Math.hypot(item.w,item.h);Object.assign(el.style,{width:length+'px',height:(item.strokeWidth||1)+'px',background:item.dash?'repeating-linear-gradient(to right,'+item.stroke+' 0 5px,transparent 5px 10px)':item.stroke,transformOrigin:'0 0',transform:'rotate('+Math.atan2(item.h,item.w)+'rad)'})}slide.append(el)})}
/** 창 너비에 맞춰 장표 배율을 갱신한다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: CSS 배율 갱신. */
function fitSlide(){shell.style.setProperty('--scale',shell.clientWidth/1600)}
/** 편집 모드를 전환한다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 텍스트 편집 속성과 버튼 상태 갱신. */
function toggleEdit(){const editing=!slide.classList.contains('editing');slide.classList.toggle('editing',editing);document.querySelectorAll('.text').forEach(el=>el.contentEditable=String(editing));document.getElementById('edit').setAttribute('aria-pressed',String(editing));document.getElementById('edit').textContent=editing?'편집 마치기':'문구 편집'}
/** 수정 문구를 담은 독립 HTML 파일을 다운로드한다. Args: 없음. Returns: 없음. Raises: 브라우저 다운로드 오류. Side Effects: 사용자 요청으로 HTML 다운로드. */
function saveHtml(){document.querySelectorAll('.text').forEach(el=>model.elements[Number(el.dataset.index)].text=el.innerText);document.getElementById('slide-data').textContent=JSON.stringify(model);const copy=document.documentElement.cloneNode(true);copy.querySelector('#slide').replaceChildren();copy.querySelector('#slide').classList.remove('editing');copy.querySelector('#edit').textContent='문구 편집';copy.querySelector('#edit').setAttribute('aria-pressed','false');const url=URL.createObjectURL(new Blob(['<!doctype html>'+copy.outerHTML],{type:'text/html;charset=utf-8'}));const a=document.createElement('a');a.href=url;a.download='parser-value-edited.html';a.click();URL.revokeObjectURL(url)}
/** 발표용 전체화면을 전환한다. Args: 없음. Returns: Promise<void>. Raises: 없음. Side Effects: 전체화면 요청 또는 버튼 안내 갱신. */
async function toggleFullscreen(){try{if(document.fullscreenElement)await document.exitFullscreen();else await document.body.requestFullscreen()}catch{document.getElementById('full').textContent='브라우저 전체화면을 이용해 주세요'}}
renderSlide();fitSlide();new ResizeObserver(fitSlide).observe(shell);document.getElementById('edit').addEventListener('click',toggleEdit);document.getElementById('save').addEventListener('click',saveHtml);document.getElementById('full').addEventListener('click',toggleFullscreen);
</script></body></html>`;
await fs.writeFile(path.join(outputDir, 'parser-value.html'), html);
console.log(`HTML과 공통 장표 모델 갱신: ${elements.length}개 편집 요소`);
