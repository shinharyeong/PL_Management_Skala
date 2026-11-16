<script setup>
import { computed, ref } from 'vue'

const selected = ref(['summary', 'organizations', 'weekly', 'bridge'])
const inlineReasons = ref(true)
const notice = ref('')
const editing = ref(false)
const message = ref('• 10월 매출 15,203억으로 계획 대비 −2.1%(−326억), 전년 동월 대비 +3.4%.\n• 영업이익 1,482억(계획 대비 −5.8%)은 미디어사업부 광고 캠페인 11월 이연(−84억)이 주요 원인이며, 11월 추정에 반영.\n• 연간 영업이익은 최신 17,620억으로 계획 대비 −380억(−2.1%), 전월 추정 대비 −90억.')
const options = [
  { group: '표', items: [
    { id: 'summary', label: '손익 요약표', note: '작년 동월 · 지난달 · 이번달 · 전월/전년 대비' },
    { id: 'organizations', label: '조직별 계획 대비 실적' },
    { id: 'cost', label: '비용 상세 (인건비·마케팅비…)' },
  ] },
  { group: '차트', items: [
    { id: 'weekly', label: '주별 매출 증감률', note: '전년 동주 대비 %' },
    { id: 'bridge', label: '연간 영업이익 브리지', note: '계획 → 조직별 증감 → 최신 추정' },
    { id: 'trend', label: '월별 매출 · 영업이익 추이' },
    { id: 'contribution', label: '조직별 기여도' },
  ] },
]
const summary = [
  ['매출', '14,702', '15,410', '15,203', '▼207', '−1.3%', '▲501', '+3.4%'],
  ['영업비용', '13,390', '13,670', '13,721', '▲51', '+0.4%', '▲331', '+2.5%'],
  ['영업이익', '1,312', '1,740', '1,482', '▼258', '−14.8%', '▲170', '+13.0%'],
]
const weekly = [22, 14, 6, 27, 21]
const bridge = [
  { label: '연간 계획', value: '18,000', bottom: 0, height: 126, tone: 'total' },
  { label: 'MNO', value: '−210', bottom: 88, height: 38, tone: 'negative' },
  { label: '미디어', value: '−150', bottom: 61, height: 27, tone: 'negative' },
  { label: '인프라', value: '+80', bottom: 61, height: 15, tone: 'positive' },
  { label: 'B2B', value: '−60', bottom: 65, height: 11, tone: 'negative' },
  { label: '기타', value: '−40', bottom: 57, height: 8, tone: 'negative' },
  { label: '최신 추정', value: '17,620', bottom: 0, height: 57, tone: 'total' },
]

/** 선택한 보고 자료 수를 계산한다.
 * Args: 없음. Returns: 자료 수. Raises: 없음. Side Effects: 없음.
 */
const selectedCount = computed(function countSelected() { return selected.value.length })

/** 예시 화면에서 실제 보고서 생성이 수행되지 않음을 알린다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 안내 문구 표시; 파일·DB 변경 없음.
 */
function explainPreview() { notice.value = '현재는 Figma 보고서 구성 예시입니다. 공식 마스터 연결 후 보고서를 생성할 수 있습니다.' }
</script>

<template>
  <div class="report-page">
    <header class="report-heading">
      <div><p class="report-crumb">경영기획팀 / 보고서</p><h1>보고서 자료 생성</h1></div>
      <div class="report-tools"><label>기준월 <select aria-label="보고서 기준월"><option>2026년 10월</option></select></label><label>버전 <select aria-label="보고서 버전"><option>master_ver10 (잠정)</option></select></label><button class="button primary" @click="explainPreview">전체 다운로드</button></div>
    </header>
    <p class="design-note">디자인 예시 · 실제 집계가 아닙니다</p>
    <p v-if="notice" class="notice" role="status">{{ notice }}</p>
    <div class="report-layout">
      <div class="report-preview">
        <p class="provisional"><strong>잠정</strong> master_ver10 생성 전 · 추출 시 “잠정” 워터마크 자동 표시 · 미수락 소명 2건은 “검토중”으로 표기</p>
        <div class="preview-top">
          <section v-if="selected.includes('weekly')" class="report-card weekly-card">
            <h2>주별 매출 증감률</h2><p class="caption">전년 동주 대비 · 단위 %</p>
            <div class="weekly-chart" role="img" aria-label="예시 주별 매출 증감률: 22, 14, 6, 27, 21퍼센트">
              <div v-for="(value, index) in weekly" :key="index" class="week"><div :class="['week-bar', { peak: index === 3 }]" :style="{ height: `${value * 4.4}px` }"><b>+{{ value }}{{ index === 3 ? ' 최고' : '' }}</b></div><span>{{ index + 1 }}주차</span></div>
            </div>
          </section>
          <section v-if="selected.includes('summary')" class="report-card summary-card">
            <div class="card-heading"><div><h2>손익 요약표</h2><p class="caption">2026년 10월 · 전사 · 억원</p></div><div class="card-actions"><button @click="explainPreview">PNG 복사</button><button @click="explainPreview">Excel</button><button @click="explainPreview">PPT에 추가</button></div></div>
            <div class="table-scroll"><table><caption class="visually-hidden">Figma 예시 손익 요약</caption><thead><tr><th>구분</th><th>작년 동월</th><th>지난달</th><th>이번달</th><th>전월 대비</th><th>전년 대비</th></tr></thead><tbody><tr v-for="row in summary" :key="row[0]"><th>{{ row[0] }}</th><td>{{ row[1] }}</td><td>{{ row[2] }}</td><td><strong>{{ row[3] }}</strong></td><td :class="row[4].startsWith('▼') ? 'blue' : 'red'">{{ row[4] }}<small>{{ row[5] }}</small></td><td class="red">{{ row[6] }}<small>{{ row[7] }}</small></td></tr></tbody></table></div>
            <div v-if="inlineReasons" class="reason"><strong>▼ 영업이익 감소 소명 · [미디어사업부]</strong><p>원인: IPTV 광고 캠페인(약 70억) 11월 이연</p><p>대응: 11월 추정 +70억 반영, 광고주 일정 확정</p></div>
          </section>
        </div>
        <section v-if="selected.includes('bridge')" class="report-card bridge-card">
          <div class="card-heading"><div><h2>연간 영업이익 브리지</h2><p class="caption">연간 계획 18,000 → 최신 추정 17,620 (−380, −2.1%) · 억원</p></div><div class="card-actions"><button @click="explainPreview">PNG 복사</button><button @click="explainPreview">Excel</button><button @click="explainPreview">PPT에 추가</button></div></div>
          <div class="bridge-chart" role="img" aria-label="예시 연간 영업이익: 계획 18000억원에서 MNO -210, 미디어 -150, 인프라 +80, B2B -60, 기타 -40억원으로 최신 추정 17620억원">
            <div v-for="bar in bridge" :key="bar.label" class="bridge-column"><div :class="['bridge-bar', bar.tone]" :style="{ bottom: `${bar.bottom}px`, height: `${bar.height}px` }"><b>{{ bar.value }}</b></div><span>{{ bar.label }}</span></div>
          </div>
        </section>
        <section class="report-card message-card">
          <div class="card-heading"><div class="message-title"><h2>보고 핵심 메시지 초안</h2><span class="badge">자동 생성</span></div><div class="card-actions"><button @click="editing = !editing">{{ editing ? '편집 완료' : '편집' }}</button><button @click="explainPreview">복사</button></div></div>
          <textarea v-if="editing" v-model="message" aria-label="보고 메시지 예시 편집" rows="5"></textarea><p v-else class="message-text">{{ message }}</p>
        </section>
      </div>
      <aside class="report-card configuration" aria-label="보고 자료 구성">
        <h2>자료 구성</h2>
        <div v-for="group in options" :key="group.group" class="option-group"><h3>{{ group.group }}</h3><label v-for="item in group.items" :key="item.id"><input v-model="selected" type="checkbox" :value="item.id" /><span>{{ item.label }}<small v-if="item.note">{{ item.note }}</small><small v-if="!['summary', 'weekly', 'bridge'].includes(item.id)">미리보기 준비 중</small></span></label></div>
        <div class="option-group"><h3>옵션</h3><label><input v-model="inlineReasons" type="checkbox" /><span>소명 사유 인라인 표시<small>Gap 셀 아래에 수락된 소명 자동 삽입</small></span></label></div>
        <div class="configuration-filters"><label>단위 <select aria-label="보고서 단위"><option>억원</option></select></label><label>범위 <select aria-label="보고서 범위"><option>전사</option></select></label></div>
        <button class="button dark" @click="explainPreview">선택 {{ selectedCount }}개 자료 한 번에 다운로드</button><p class="caption export-format">PPT(장표 1개 = 자료 1개) · Excel · PNG</p>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.report-heading,.report-tools,.card-heading,.card-actions,.message-title { display:flex; align-items:center; gap:10px; }
.report-heading,.card-heading { justify-content:space-between; }
.report-crumb { color:#9ea2a9; font-size:12px; margin-bottom:5px; }
.report-tools label,.configuration-filters label { background:#fff; border:1px solid var(--line); border-radius:8px; padding:8px 10px; font-size:11px; color:#9ea2a9; display:flex; align-items:center; gap:7px; }
select { border:0; background:transparent; color:var(--ink); font-size:12px; max-width:150px; width:auto; min-height:0; margin:0; padding:0; }
.report-page .button.primary { color:white; font-weight:500; }
.report-page input[type=checkbox] { min-height:0; padding:0; }
.option-group>label { font-weight:400; }
.design-note { margin:12px 0 16px; color:#8b7657; font-size:11px; }
.report-layout { display:grid; grid-template-columns:minmax(0, 800fr) minmax(265px, 300fr); gap:20px; align-items:start; }
.report-preview { min-width:0; }
.report-card { background:#fff; border:1px solid var(--line); border-radius:12px; padding:20px; }
.report-card h2 { font-size:15px; }
.caption { font-size:10px; color:#9ea2a9; margin-top:5px; line-height:1.6; }
.provisional { background:#fbf2e0; border-radius:10px; padding:13px 16px; font-size:11px; color:#b7791f; margin-bottom:16px; line-height:1.7; }
.provisional strong { margin-right:12px; }
.preview-top { display:grid; grid-template-columns:minmax(220px, 3fr) minmax(0,5fr); gap:16px; align-items:start; }
.card-actions { gap:5px; flex-shrink:0; }
.card-actions button { padding:6px 8px; background:#fff; border:1px solid var(--line); border-radius:6px; font-size:10px; }
.summary-card { padding:17px 14px; }
.summary-card .card-heading { flex-wrap:wrap; }
.summary-card table { font-size:11px; margin-top:14px; min-width:420px; }
.summary-card th,.summary-card td { padding:11px 6px; text-align:right; white-space:nowrap; }
.summary-card th:first-child { text-align:left; }
.summary-card thead th { font-size:10px; background:#faf9f6; }
.summary-card small { display:block; font-size:9px; }
.blue { color:#4a70e5; }.red { color:#df5d52; }
.reason { background:#eaf0fb; color:#8691a8; padding:9px; border-radius:4px; font-size:10px; line-height:1.8; }
.reason strong { color:#4a70e5; font-weight:500; }
.weekly-chart { display:flex; align-items:flex-end; justify-content:space-around; height:200px; padding-bottom:24px; margin-top:18px; border-bottom:1px solid #d2d5db; }
.week { position:relative; width:30px; }.week-bar { background:#c9ccd3; border-radius:3px 3px 0 0; position:relative; }.week-bar.peak { background:#14171e; }.week-bar b { position:absolute; top:-20px; left:50%; transform:translateX(-50%); font-size:10px; white-space:nowrap; }.week>span { position:absolute; top:calc(100% + 9px); width:100%; text-align:center; font-size:9px; color:#9ea2a9; }
.bridge-card,.message-card { margin-top:16px; }.bridge-chart { display:flex; gap:20px; height:188px; margin:26px 5px 28px; border-bottom:1px solid #e7e4de; }.bridge-column { flex:1; position:relative; }.bridge-bar { position:absolute; width:100%; max-width:52px; left:50%; transform:translateX(-50%); border-radius:3px 3px 0 0; }.bridge-bar b { position:absolute; top:-20px; left:50%; transform:translateX(-50%); font-size:11px; white-space:nowrap; }.bridge-bar.total { background:#14171e; }.bridge-bar.negative { background:#4a70e5; color:#4a70e5; }.bridge-bar.positive { background:#df5d52; color:#df5d52; }.bridge-column>span { position:absolute; top:calc(100% + 12px); left:50%; transform:translateX(-50%); font-size:10px; color:#5f636b; white-space:nowrap; }
.message-text { white-space:pre-line; font-size:12px; line-height:1.9; margin-top:12px; }.message-card textarea { width:100%; border:1px solid var(--line); border-radius:6px; padding:10px; margin-top:12px; font-size:12px; }
.configuration { padding:22px; }.option-group { margin-top:19px; }.option-group h3 { font-size:11px; color:#9ea2a9; margin-bottom:10px; }.option-group>label { display:flex; align-items:flex-start; gap:9px; margin:15px 0; font-size:12px; }.option-group input { width:16px; height:16px; accent-color:#14171e; margin:0; flex-shrink:0; }.option-group small { display:block; color:#9ea2a9; font-size:10px; margin-top:4px; line-height:1.6; }.configuration-filters { display:flex; gap:8px; margin:20px 0 12px; }.configuration-filters label { flex:1; padding:8px; }.configuration .dark { background:#14171e; color:white; width:100%; font-size:11px; }.export-format { text-align:center; font-size:9px; }
@media(max-width:1250px) { .report-layout { grid-template-columns:minmax(0,1fr) 265px; }.preview-top { grid-template-columns:1fr; }.weekly-chart { height:160px; }.report-tools { flex-wrap:wrap; justify-content:flex-end; }.report-heading { align-items:flex-start; }.card-heading { flex-wrap:wrap; } }
@media(max-width:900px) { .report-layout { grid-template-columns:1fr; }.configuration { grid-row:1; }.report-heading { flex-wrap:wrap; }.report-tools { justify-content:flex-start; }.bridge-chart { gap:8px; } }
</style>
