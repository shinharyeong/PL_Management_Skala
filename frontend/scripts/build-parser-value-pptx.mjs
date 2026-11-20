import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateRawSync } from 'node:zlib';
import { execFileSync } from 'node:child_process';

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const previewDirectory = resolve(scriptDirectory, '../public/previews');
const modelPath = resolve(previewDirectory, 'parser-value-slide.json');
const outputPath = resolve(previewDirectory, 'parser-value.pptx');
const fontFamily = 'Apple SD Gothic Neo';
const xmlHeader = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>';
const drawingNamespace = 'http://schemas.openxmlformats.org/drawingml/2006/main';
const presentationNamespace = 'http://schemas.openxmlformats.org/presentationml/2006/main';
const relationNamespace = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships';
const packageRelationNamespace = 'http://schemas.openxmlformats.org/package/2006/relationships';

/**
 * 목적: XML 본문과 속성에 사용할 문자열을 이스케이프한다.
 * Args: value — 문자열로 변환할 값.
 * Returns: XML 예약 문자를 치환한 문자열.
 * Raises: 없음.
 * Side Effects: 없음.
 */
function escapeXml(value) {
  return String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;').replaceAll('"', '&quot;').replaceAll("'", '&apos;');
}

/**
 * 목적: CSS 픽셀을 OOXML 좌표 단위로 변환한다.
 * Args: value — 유한한 픽셀 수치.
 * Returns: 정수 EMU 좌표.
 * Raises: TypeError — 입력이 유한한 수치가 아닌 경우.
 * Side Effects: 없음.
 */
function emu(value) {
  if (!Number.isFinite(value)) throw new TypeError(`유효하지 않은 좌표: ${value}`);
  return Math.round(value * 9525);
}

/**
 * 목적: 모델의 색상과 불투명도를 OOXML 채우기로 변환한다.
 * Args: color — #RGB 또는 #RRGGBB, opacity — 0~1 불투명도.
 * Returns: solidFill 또는 noFill XML.
 * Raises: TypeError — 지원하지 않는 색상 표기인 경우.
 * Side Effects: 없음.
 */
function fillXml(color, opacity = 1) {
  if (!color || color === 'none' || color === 'transparent') return '<a:noFill/>';
  let hex = color.replace(/^#/, '');
  if (/^[\da-f]{3}$/i.test(hex)) hex = [...hex].map(character => character + character).join('');
  if (!/^[\da-f]{6}$/i.test(hex)) throw new TypeError(`지원하지 않는 색상: ${color}`);
  const alpha = Math.round(Math.max(0, Math.min(1, opacity)) * 100000);
  return `<a:solidFill><a:srgbClr val="${hex.toUpperCase()}"><a:alpha val="${alpha}"/></a:srgbClr></a:solidFill>`;
}

/**
 * 목적: 모델 요소의 외곽선과 점선을 OOXML 선 속성으로 변환한다.
 * Args: element — stroke, strokeWidth, dash, opacity를 가진 요소.
 * Returns: 선 속성 XML.
 * Raises: TypeError — 좌표 또는 색상 값이 유효하지 않은 경우.
 * Side Effects: 없음.
 */
function lineXml(element) {
  const dash = element.dash ? '<a:prstDash val="dash"/>' : '<a:prstDash val="solid"/>';
  return `<a:ln w="${emu(element.strokeWidth ?? 1)}">${fillXml(element.stroke, element.opacity)}${dash}</a:ln>`;
}

/**
 * 목적: 요소 위치와 크기를 PowerPoint 변환 속성으로 변환한다.
 * Args: element — x, y, w, h 좌표를 가진 요소.
 * Returns: 음수 방향도 보존하는 xfrm XML.
 * Raises: TypeError — 좌표가 유효하지 않은 경우.
 * Side Effects: 없음.
 */
function transformXml(element) {
  const flip = `${element.w < 0 ? ' flipH="1"' : ''}${element.h < 0 ? ' flipV="1"' : ''}`;
  return `<a:xfrm${flip}><a:off x="${emu(Math.min(element.x, element.x + element.w))}" y="${emu(Math.min(element.y, element.y + element.h))}"/><a:ext cx="${emu(Math.abs(element.w))}" cy="${emu(Math.abs(element.h))}"/></a:xfrm>`;
}

/**
 * 목적: 단색 문자열을 편집 가능한 PowerPoint 문단들로 구성한다.
 * Args: text — 줄바꿈을 포함한 문자열, element — 글꼴·색상·정렬 스타일.
 * Returns: 문단 XML 문자열.
 * Raises: TypeError — 글자 크기 또는 색상이 유효하지 않은 경우.
 * Side Effects: 없음.
 */
function paragraphsXml(text, element = {}) {
  const size = Math.round((element.fontSize ?? 20) * 75);
  if (!Number.isFinite(size) || size <= 0) throw new TypeError('글자 크기는 양수여야 합니다.');
  const align = { left: 'l', center: 'ctr', right: 'r' }[element.align] ?? 'l';
  const bold = element.fontWeight === 'bold' || Number(element.fontWeight ?? 400) >= 600;
  const runProperties = `<a:rPr lang="ko-KR" sz="${size}" b="${bold ? 1 : 0}" dirty="0">${fillXml(element.color ?? '#151515', element.opacity)}<a:latin typeface="${fontFamily}"/><a:ea typeface="${fontFamily}"/><a:cs typeface="${fontFamily}"/></a:rPr>`;
  return String(text ?? '').split('\n').map(line => `<a:p><a:pPr algn="${align}"><a:lnSpc><a:spcPct val="115000"/></a:lnSpc><a:spcBef><a:spcPts val="0"/></a:spcBef><a:spcAft><a:spcPts val="0"/></a:spcAft></a:pPr><a:r>${runProperties}<a:t xml:space="preserve">${escapeXml(line)}</a:t></a:r><a:endParaRPr lang="ko-KR" sz="${size}"/></a:p>`).join('');
}

/**
 * 목적: 단일 모델 요소를 이미지가 아닌 편집 가능한 슬라이드 도형으로 변환한다.
 * Args: element — rect, text 또는 line 모델, index — 요소 순서.
 * Returns: PowerPoint 도형 XML.
 * Raises: TypeError — 미지원 요소 또는 유효하지 않은 도형 속성.
 * Side Effects: 없음.
 */
function shapeXml(element, index) {
  if (!['rect', 'text', 'line'].includes(element.kind)) throw new TypeError(`지원하지 않는 요소: ${element.kind}`);
  const id = index + 2;
  const name = escapeXml(element.name ?? `${element.kind} ${id}`);
  const geometry = element.kind === 'line' ? 'line' : element.radius ? 'roundRect' : 'rect';
  const bodyAnchor = { top: 't', middle: 'ctr', center: 'ctr', bottom: 'b' }[element.verticalAlign] ?? 't';
  const properties = `${transformXml(element)}<a:prstGeom prst="${geometry}"><a:avLst/></a:prstGeom>${fillXml(element.fill, element.opacity)}${lineXml(element)}`;
  const body = element.kind === 'text' ? `<p:txBody><a:bodyPr wrap="square" lIns="0" tIns="0" rIns="0" bIns="0" anchor="${bodyAnchor}"><a:noAutofit/></a:bodyPr><a:lstStyle/>${paragraphsXml(element.text, element)}</p:txBody>` : '';
  return `<p:sp><p:nvSpPr><p:cNvPr id="${id}" name="${name}"/><p:cNvSpPr${element.kind === 'text' ? ' txBox="1"' : ''}/><p:nvPr/></p:nvSpPr><p:spPr>${properties}</p:spPr>${body}</p:sp>`;
}

/**
 * 목적: OOXML 관계 항목들을 단일 관계 파일로 구성한다.
 * Args: items — {id, type, target} 관계 목록.
 * Returns: Relationships XML.
 * Raises: 없음.
 * Side Effects: 없음.
 */
function relationshipsXml(items) {
  return `${xmlHeader}<Relationships xmlns="${packageRelationNamespace}">${items.map(item => `<Relationship Id="${escapeXml(item.id)}" Type="${relationNamespace}/${escapeXml(item.type)}" Target="${escapeXml(item.target)}"/>`).join('')}</Relationships>`;
}

/**
 * 목적: 빈 슬라이드·마스터의 필수 그룹 도형 속성을 제공한다.
 * Args: 없음.
 * Returns: 그룹 도형 속성 XML.
 * Raises: 없음.
 * Side Effects: 없음.
 */
function groupPropertiesXml() {
  return '<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>';
}

/**
 * 목적: 한국어 글꼴과 기본 색상으로 최소 테마를 구성한다.
 * Args: 없음.
 * Returns: 테마 XML.
 * Raises: 없음.
 * Side Effects: 없음.
 */
function themeXml() {
  const colors = [['dk1', '151515'], ['lt1', 'FFFFFF'], ['dk2', '444444'], ['lt2', 'F5F5F5'], ['accent1', '333333'], ['accent2', '555555'], ['accent3', '777777'], ['accent4', '999999'], ['accent5', 'BBBBBB'], ['accent6', 'DDDDDD'], ['hlink', '333333'], ['folHlink', '666666']];
  const fonts = `<a:latin typeface="${fontFamily}"/><a:ea typeface="${fontFamily}"/><a:cs typeface="${fontFamily}"/><a:font script="Hang" typeface="${fontFamily}"/>`;
  const fills = '<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>'.repeat(3);
  const lines = '<a:ln w="9525" cap="flat" cmpd="sng" algn="ctr"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/><a:miter lim="800000"/></a:ln>'.repeat(3);
  return `${xmlHeader}<a:theme xmlns:a="${drawingNamespace}" name="Parser Value"><a:themeElements><a:clrScheme name="Monochrome">${colors.map(([name, value]) => `<a:${name}><a:srgbClr val="${value}"/></a:${name}>`).join('')}</a:clrScheme><a:fontScheme name="Korean"><a:majorFont>${fonts}</a:majorFont><a:minorFont>${fonts}</a:minorFont></a:fontScheme><a:fmtScheme name="Simple"><a:fillStyleLst>${fills}</a:fillStyleLst><a:lnStyleLst>${lines}</a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst>${fills}</a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>`;
}

/**
 * 목적: 슬라이드 모델과 출처 메모를 완결된 OOXML 패키지 항목으로 구성한다.
 * Args: model — width, height, background, elements, notes 슬라이드 모델.
 * Returns: 경로와 XML 문자열을 담은 Map.
 * Raises: TypeError — 모델 크기나 요소가 유효하지 않은 경우.
 * Side Effects: 없음.
 */
function createPackageEntries(model) {
  if (!Array.isArray(model.elements) || model.width <= 0 || model.height <= 0) throw new TypeError('유효한 슬라이드 모델이 필요합니다.');
  const namespaces = `xmlns:a="${drawingNamespace}" xmlns:r="${relationNamespace}" xmlns:p="${presentationNamespace}"`;
  const colorMap = '<p:clrMap accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" bg1="lt1" bg2="lt2" folHlink="folHlink" hlink="hlink" tx1="dk1" tx2="dk2"/>';
  const colorOverride = '<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>';
  const timestamp = new Date().toISOString().replace(/\.\d+Z$/, 'Z');
  const entries = new Map();
  const contentTypes = [
    ['ppt/presentation.xml', 'application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml'],
    ['ppt/slides/slide1.xml', 'application/vnd.openxmlformats-officedocument.presentationml.slide+xml'],
    ['ppt/slideMasters/slideMaster1.xml', 'application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml'],
    ['ppt/slideLayouts/slideLayout1.xml', 'application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml'],
    ['ppt/theme/theme1.xml', 'application/vnd.openxmlformats-officedocument.theme+xml'],
    ['ppt/notesSlides/notesSlide1.xml', 'application/vnd.openxmlformats-officedocument.presentationml.notesSlide+xml'],
    ['ppt/notesMasters/notesMaster1.xml', 'application/vnd.openxmlformats-officedocument.presentationml.notesMaster+xml'],
    ['docProps/core.xml', 'application/vnd.openxmlformats-package.core-properties+xml'],
    ['docProps/app.xml', 'application/vnd.openxmlformats-officedocument.extended-properties+xml'],
  ];
  entries.set('[Content_Types].xml', `${xmlHeader}<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>${contentTypes.map(([path, type]) => `<Override PartName="/${path}" ContentType="${type}"/>`).join('')}</Types>`);
  entries.set('_rels/.rels', `${xmlHeader}<Relationships xmlns="${packageRelationNamespace}"><Relationship Id="rId1" Type="${relationNamespace}/officeDocument" Target="ppt/presentation.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/><Relationship Id="rId3" Type="${relationNamespace}/extended-properties" Target="docProps/app.xml"/></Relationships>`);
  entries.set('docProps/core.xml', `${xmlHeader}<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><dc:title>자체 엑셀 파서</dc:title><dc:subject>규칙 기반 파서의 가치와 원본 검증</dc:subject><dc:creator>경영팀을 부탁해</dc:creator><cp:lastModifiedBy>경영팀을 부탁해</cp:lastModifiedBy><dcterms:created xsi:type="dcterms:W3CDTF">${timestamp}</dcterms:created><dcterms:modified xsi:type="dcterms:W3CDTF">${timestamp}</dcterms:modified></cp:coreProperties>`);
  entries.set('docProps/app.xml', `${xmlHeader}<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Application>Parser slide builder</Application><PresentationFormat>On-screen Show (16:9)</PresentationFormat><Slides>1</Slides><Notes>1</Notes><Company>경영팀을 부탁해</Company><AppVersion>1.0</AppVersion></Properties>`);
  entries.set('ppt/presentation.xml', `${xmlHeader}<p:presentation ${namespaces}><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId2"/></p:sldMasterIdLst><p:notesMasterIdLst><p:notesMasterId r:id="rId3"/></p:notesMasterIdLst><p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst><p:sldSz cx="${emu(model.width)}" cy="${emu(model.height)}" type="screen16x9"/><p:notesSz cx="6858000" cy="9144000"/><p:defaultTextStyle/></p:presentation>`);
  entries.set('ppt/_rels/presentation.xml.rels', relationshipsXml([{ id: 'rId1', type: 'slide', target: 'slides/slide1.xml' }, { id: 'rId2', type: 'slideMaster', target: 'slideMasters/slideMaster1.xml' }, { id: 'rId3', type: 'notesMaster', target: 'notesMasters/notesMaster1.xml' }]));
  entries.set('ppt/slides/slide1.xml', `${xmlHeader}<p:sld ${namespaces}><p:cSld name="자체 엑셀 파서"><p:bg><p:bgPr>${fillXml(model.background ?? '#F5F5F5')}<a:effectLst/></p:bgPr></p:bg><p:spTree>${groupPropertiesXml()}${model.elements.map(shapeXml).join('')}</p:spTree></p:cSld>${colorOverride}</p:sld>`);
  entries.set('ppt/slides/_rels/slide1.xml.rels', relationshipsXml([{ id: 'rId1', type: 'slideLayout', target: '../slideLayouts/slideLayout1.xml' }, { id: 'rId2', type: 'notesSlide', target: '../notesSlides/notesSlide1.xml' }]));
  entries.set('ppt/slideMasters/slideMaster1.xml', `${xmlHeader}<p:sldMaster ${namespaces}><p:cSld><p:spTree>${groupPropertiesXml()}</p:spTree></p:cSld>${colorMap}<p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst><p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles></p:sldMaster>`);
  entries.set('ppt/slideMasters/_rels/slideMaster1.xml.rels', relationshipsXml([{ id: 'rId1', type: 'slideLayout', target: '../slideLayouts/slideLayout1.xml' }, { id: 'rId2', type: 'theme', target: '../theme/theme1.xml' }]));
  entries.set('ppt/slideLayouts/slideLayout1.xml', `${xmlHeader}<p:sldLayout ${namespaces} type="blank" preserve="1"><p:cSld name="Blank"><p:spTree>${groupPropertiesXml()}</p:spTree></p:cSld>${colorOverride}</p:sldLayout>`);
  entries.set('ppt/slideLayouts/_rels/slideLayout1.xml.rels', relationshipsXml([{ id: 'rId1', type: 'slideMaster', target: '../slideMasters/slideMaster1.xml' }]));
  entries.set('ppt/theme/theme1.xml', themeXml());
  entries.set('ppt/notesMasters/notesMaster1.xml', `${xmlHeader}<p:notesMaster ${namespaces}><p:cSld><p:spTree>${groupPropertiesXml()}</p:spTree></p:cSld>${colorMap}<p:notesStyle/></p:notesMaster>`);
  entries.set('ppt/notesMasters/_rels/notesMaster1.xml.rels', relationshipsXml([{ id: 'rId1', type: 'theme', target: '../theme/theme1.xml' }]));
  entries.set('ppt/notesSlides/notesSlide1.xml', `${xmlHeader}<p:notes ${namespaces}><p:cSld><p:spTree>${groupPropertiesXml()}<p:sp><p:nvSpPr><p:cNvPr id="2" name="발표 메모 및 근거"/><p:cNvSpPr txBox="1"/><p:nvPr><p:ph type="body" idx="1"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x="685800" y="3429000"/><a:ext cx="5486400" cy="4572000"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/>${paragraphsXml(model.notes ?? '', { fontSize: 14 })}</p:txBody></p:sp></p:spTree></p:cSld>${colorOverride}</p:notes>`);
  entries.set('ppt/notesSlides/_rels/notesSlide1.xml.rels', relationshipsXml([{ id: 'rId1', type: 'notesMaster', target: '../notesMasters/notesMaster1.xml' }, { id: 'rId2', type: 'slide', target: '../slides/slide1.xml' }]));
  return entries;
}

/**
 * 목적: ZIP 항목의 무결성 검사용 CRC-32를 계산한다.
 * Args: data — 계산할 Buffer.
 * Returns: 부호 없는 32비트 CRC.
 * Raises: 없음.
 * Side Effects: 없음.
 */
function crc32(data) {
  let crc = 0xffffffff;
  for (const byte of data) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

/**
 * 목적: 메모리의 XML 항목들을 외부 패키지 없이 ZIP으로 묶는다.
 * Args: entries — 경로와 문자열을 담은 Map.
 * Returns: 완결된 PPTX ZIP Buffer.
 * Raises: RangeError — ZIP32 용량 한계를 넘는 경우.
 * Side Effects: 없음. 압축과 헤더 조립은 메모리에서 수행한다.
 */
function zipEntries(entries) {
  const localParts = [];
  const centralParts = [];
  let offset = 0;
  for (const [path, content] of entries) {
    const name = Buffer.from(path, 'utf8');
    const data = Buffer.from(content, 'utf8');
    const compressed = deflateRawSync(data);
    const crc = crc32(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x0800, 6);
    local.writeUInt16LE(8, 8);
    local.writeUInt16LE(0x21, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(compressed.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(name.length, 26);
    localParts.push(local, name, compressed);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0x0800, 8);
    central.writeUInt16LE(8, 10);
    central.writeUInt16LE(0x21, 14);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(compressed.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(name.length, 28);
    central.writeUInt32LE(offset, 42);
    centralParts.push(central, name);
    offset += local.length + name.length + compressed.length;
  }
  const central = Buffer.concat(centralParts);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(entries.size, 8);
  end.writeUInt16LE(entries.size, 10);
  end.writeUInt32LE(central.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...localParts, central, end]);
}

/**
 * 목적: 생성된 ZIP의 CRC, 모든 XML, 관계 대상, 편집 가능한 요소 수를 저장 전에 검사한다.
 * Args: archive — 메모리 PPTX Buffer, expectedShapes — 기대하는 모델 요소 수.
 * Returns: 검증 요약 객체.
 * Raises: Error — Python 실행 실패 또는 ZIP/XML/관계/요소 검사 실패.
 * Side Effects: python3 -B 자식 프로세스를 실행한다. 파일·캐시·로그는 생성하지 않는다.
 */
function validatePackage(archive, expectedShapes) {
  const validator = `import io,json,posixpath,sys,zipfile,xml.etree.ElementTree as ET
z=zipfile.ZipFile(io.BytesIO(sys.stdin.buffer.read()))
assert z.testzip() is None, 'ZIP CRC mismatch'
names=set(z.namelist())
for name in names:
    if name.endswith(('.xml','.rels')):
        root=ET.fromstring(z.read(name))
        if name.endswith('.rels'):
            base=posixpath.dirname(posixpath.dirname(name)) if name!='_rels/.rels' else ''
            for rel in root:
                if rel.get('TargetMode')!='External':
                    target=posixpath.normpath(posixpath.join(base,rel.get('Target')))
                    assert target in names, (name,target)
ns={'p':'${presentationNamespace}','a':'${drawingNamespace}'}
slide=ET.fromstring(z.read('ppt/slides/slide1.xml'))
shapes=slide.findall('.//p:sp',ns)
assert len(shapes)==int(sys.argv[1]), 'Shape count mismatch'
assert not slide.findall('.//p:pic',ns), 'Slide must remain editable'
assert len(ET.fromstring(z.read('ppt/presentation.xml')).findall('.//p:sldId',ns))==1
print(json.dumps({'xmlParts':len(names),'editableShapes':len(shapes),'textBoxes':len(slide.findall('.//p:txBody',ns)),'slides':1}))`;
  return JSON.parse(execFileSync('python3', ['-B', '-c', validator, String(expectedShapes)], { input: archive, encoding: 'utf8', maxBuffer: 1024 * 1024 }));
}

/**
 * 목적: 공통 슬라이드 모델을 읽고 검증된 편집용 PPTX 한 파일을 작성한다.
 * Args: 없음. 고정된 parser-value-slide.json을 입력으로 사용한다.
 * Returns: Promise<void>.
 * Raises: 파일 읽기·JSON 해석·모델 변환·검증·출력 쓰기 오류를 전파한다.
 * Side Effects: parser-value.pptx만 생성하거나 덮어쓰고 검증 요약을 콘솔에 출력한다.
 */
async function buildPresentation() {
  const model = JSON.parse(await readFile(modelPath, 'utf8'));
  const archive = zipEntries(createPackageEntries(model));
  const validation = validatePackage(archive, model.elements.length);
  await writeFile(outputPath, archive);
  console.log(JSON.stringify({ outputPath, bytes: archive.length, ...validation }, null, 2));
}

await buildPresentation();
