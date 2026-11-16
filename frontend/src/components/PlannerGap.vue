<script setup>
import { computed, ref } from 'vue'

const activeTab = ref('review')
const organization = ref('미디어사업부')
const search = ref('')
const sort = ref('difference')
const rateEnabled = ref(true)
const amountEnabled = ref(true)
const rate = ref(5)
const amount = ref(5)
const selected = ref(['incentive', 'recruitment', 'management', 'support'])
const collapsed = ref([])
const dialog = ref(null)
const dialogTitle = ref('')
const dialogMessage = ref('')
const detail = ref(null)

const tabs = [
  { id: 'review', label: '검토 필요', count: 41 },
  { id: 'explanation', label: '소명 진행중', count: 27 },
  { id: 'recheck', label: '재검토 필요', count: 0 },
  { id: 'approved', label: '소명 승인 완료', count: 5 },
  { id: 'excluded', label: '제외됨', count: 9 },
]
const stages = [
  { title: '검토 필요', description: '차이 항목 확인', tab: 'review' },
  { title: '검토 완료', description: '검토 결과 확정', tab: null },
  { title: '소명 진행중', description: '조직 소명 수집', tab: 'explanation' },
  { title: '재검토 필요', description: '보완 내용 재검토', tab: 'recheck' },
  { title: '소명 승인 완료', description: '승인 및 확정', tab: 'approved' },
]
const groups = [
  { id: 'recruiting', name: '고객모집비용', plan: 860, actual: 812, difference: 48, rate: '5.6', rows: [
    { id: 'incentive', name: '위탁판매장려금', plan: 412, actual: 386, difference: 26, rate: '6.3' },
    { id: 'recruitment', name: '위탁모집수수료', plan: 310, actual: 298, difference: 12, rate: '3.9' },
    { id: 'customer', name: '고객모집수수료', plan: 138, actual: 128, difference: 10, rate: '7.2' },
  ] },
  { id: 'managing', name: '고객관리비용', plan: 144, actual: 136, difference: 8, rate: '5.6', rows: [
    { id: 'management', name: '위탁관리수수료', plan: 144, actual: 136, difference: 8, rate: '5.6' },
  ] },
  { id: 'retention', name: '고객유지비용', plan: 96, actual: 88, difference: 8, rate: '8.3', rows: [
    { id: 'support', name: '고객기변지원금', plan: 60, actual: 56, difference: 4, rate: '6.7' },
    { id: 'retention-fee', name: '고객유지수수료', plan: 36, actual: 32, difference: 4, rate: '11.1' },
  ] },
]

/** 예시 항목을 검색하고 같은 소분류 안에서 표시 순서를 바꾼다.
 * Args: 없음. Returns: 원본 합계와 검색된 상세를 가진 그룹 배열. Raises: 없음.
 * Side Effects: 없음; 실제 금융값을 합산하거나 원본 예시를 변경하지 않는다.
 */
function filteredGroups() {
  if (activeTab.value !== 'review' || organization.value === '네트워크인프라부문') return []
  const term = search.value.trim()
  const result = []
  for (const group of groups) {
    const matches = []
    for (const row of group.rows) {
      if (!term || `${group.name} ${row.name} 영업비용 마케팅비용`.includes(term)) matches.push(row)
    }
    if (matches.length) result.push({ ...group, rows: matches.sort(compareRows) })
  }
  return result
}

/** 선택한 정렬 기준으로 상세 두 개의 순서를 비교한다.
 * Args: left/right 예시 상세. Returns: 정렬 비교 정수. Raises: 없음. Side Effects: 없음.
 */
function compareRows(left, right) {
  if (sort.value === 'name') return left.name.localeCompare(right.name, 'ko')
  return sort.value === 'ascending' ? left.difference - right.difference : right.difference - left.difference
}

/** 현재 검색 결과의 상세 개수를 센다.
 * Args: 없음. Returns: 표시할 상세 수. Raises: 없음. Side Effects: 없음.
 */
function countDetails() {
  let count = 0
  for (const group of visibleGroups.value) count += group.rows.length
  return count
}

/** 검색 조건에 따른 유선재판매 예시 행의 표시 여부를 확인한다.
 * Args: 없음. Returns: 표시 여부. Raises: 없음. Side Effects: 없음.
 */
function showResale() {
  return activeTab.value === 'review' && organization.value !== '네트워크인프라부문'
    && (!search.value.trim() || '영업비용 유선재판매 마케팅비용'.includes(search.value.trim()))
}
const visibleGroups = computed(filteredGroups)
const detailCount = computed(countDetails)
const resaleVisible = computed(showResale)

/** 그룹 펼침 상태만 변경한다.
 * Args: id 예시 그룹 식별자. Returns: 없음. Raises: 없음. Side Effects: 화면 상태 변경.
 */
function toggleGroup(id) {
  const index = collapsed.value.indexOf(id)
  if (index === -1) collapsed.value.push(id)
  else collapsed.value.splice(index, 1)
}

/** 로컬 선택을 모두 해제한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 체크박스 상태 변경; 서버 호출 없음.
 */
function clearSelection() { selected.value = [] }

/** 실제 업무 처리가 없는 안내 대화상자를 연다.
 * Args: title 제목, message 설명, row 선택 상세 또는 null.
 * Returns: 없음. Raises: 없음. Side Effects: 대화상자와 초점 변경; 저장·발송 없음.
 */
function showDialog(title, message, row = null) {
  dialogTitle.value = title
  dialogMessage.value = message
  detail.value = row
  dialog.value.showModal()
}

/** 미연결 기능의 안내를 표시한다.
 * Args: action 기능 이름. Returns: 없음. Raises: 없음. Side Effects: 안내 표시만 수행.
 */
function explainUnavailable(action) {
  showDialog(action, '디자인 예시 화면입니다. 이 기능은 아직 서버에 연결되지 않아 저장·발송·승인이 처리되지 않습니다.')
}

/** 선택한 예시 상세와 원래 디자인 금액을 표시한다.
 * Args: row 상세 예시. Returns: 없음. Raises: 없음. Side Effects: 안내 대화상자 표시.
 */
function showDetail(row) {
  showDialog(row.name, 'Figma에 표시된 예시 값입니다. 원본 셀과 수정 사유 조회는 준비 중입니다.', row)
}

/** 파이프라인 단계의 예시 탭을 열거나 미연결 안내를 표시한다.
 * Args: stage 파이프라인 단계. Returns: 없음. Raises: 없음. Side Effects: 탭 또는 대화상자 변경.
 */
function selectStage(stage) {
  if (stage.tab) activeTab.value = stage.tab
  else explainUnavailable('검토 완료')
}

/** 안내 대화상자를 닫고 원래 버튼으로 초점을 복원한다.
 * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 대화상자 닫기.
 */
function closeDialog() { dialog.value.close() }
</script>

<template>
  <div class="gap-page">
    <header class="gap-heading">
      <div><h1>월간 시트 관리</h1><p class="design-note">디자인 예시 · 실제 집계가 아닙니다</p></div>
      <div class="version-controls">
        <label class="version-select"><span>기준 대상</span><select aria-label="기준 대상"><option>조직 제출본 ver1.10</option></select></label>
        <label class="version-select"><span>비교 대상</span><select aria-label="비교 대상"><option>조직 제출본 ver10</option></select></label>
        <button class="gap-button" @click="explainUnavailable('엑셀 다운로드')">엑셀 다운로드</button>
      </div>
    </header>

    <section class="gap-criteria" aria-labelledby="criteria-title">
      <div class="criteria-heading"><h2 id="criteria-title">Gap 기준</h2><p>중복 선택 가능</p></div>
      <div class="criteria-fields">
        <div class="criterion">
          <label :class="['criterion-toggle', { checked: rateEnabled }]"><input v-model="rateEnabled" type="checkbox" /><span>차이율</span></label>
          <label class="criterion-value"><span>±</span><input v-model="rate" type="number" min="0" max="100" aria-label="차이율 기준 예시" /><span class="suffix">%</span></label>
        </div>
        <div class="criterion">
          <label :class="['criterion-toggle', { checked: amountEnabled }]"><input v-model="amountEnabled" type="checkbox" /><span>최소 금액</span></label>
          <label class="criterion-value"><input v-model="amount" type="number" min="0" aria-label="최소 금액 기준 예시" /><span class="suffix">천만원</span></label>
        </div>
        <label class="comparison-select"><span>필터</span><select aria-label="비교 필터"><option>실적 vs 계획</option><option>추정 vs 계획</option></select></label>
        <button class="apply-button" @click="explainUnavailable('선택한 기준 적용')">기준 적용</button>
        <p class="criteria-help">버튼을 눌러 각 기준 선택·해제 · 두 기준 선택 시 모두 충족하는 항목 표시</p>
      </div>
    </section>

    <ol class="gap-pipeline" aria-label="검토부터 승인까지 5단계 예시">
      <li v-for="(stage, index) in stages" :key="stage.title" :class="`stage-${index + 1}`">
        <button @click="selectStage(stage)"><span class="stage-number"><img :src="`/assets/gap-step-${index + 1}.svg`" alt="" /><b>{{ index + 1 }}</b></span><span><strong>{{ stage.title }}</strong><small>{{ stage.description }}</small></span></button>
        <span v-if="index < stages.length - 1" class="stage-arrow" aria-hidden="true">›</span>
      </li>
    </ol>

    <section class="gap-list" aria-label="월간 시트 항목 예시">
      <div class="gap-tabs" role="tablist" aria-label="예시 검토 상태">
        <button v-for="tab in tabs" :id="`gap-tab-${tab.id}`" :key="tab.id" role="tab" :aria-selected="activeTab === tab.id" aria-controls="gap-results" :class="{ active: activeTab === tab.id }" @click="activeTab = tab.id">{{ tab.label }} <span>{{ tab.count }}</span><span v-if="tab.id === 'recheck'" class="tab-design-line" aria-hidden="true"><img src="/assets/gap-tab-line.svg" alt="" /></span></button>
      </div>
      <div class="gap-filters">
        <label class="filter-control"><span>조직</span><select v-model="organization" aria-label="조직 예시"><option>미디어사업부</option><option>전체 조직</option><option>네트워크인프라부문</option></select></label>
        <label class="filter-control unit-control"><span>단위</span><select aria-label="금액 표시 단위"><option>천원</option></select></label>
        <input v-model="search" class="item-search" type="search" placeholder="항목 검색" aria-label="항목 검색" />
        <label class="sort-control"><span>정렬</span><select v-model="sort" aria-label="항목 정렬"><option value="difference">내림차순(차액)</option><option value="ascending">오름차순(차액)</option><option value="name">항목명순</option></select></label>
      </div>

      <div id="gap-results" role="tabpanel" :aria-labelledby="`gap-tab-${activeTab}`">
        <div v-if="visibleGroups.length || resaleVisible" class="gap-table-scroll">
          <div class="gap-tree" role="table" aria-label="영업비용 계층형 예시, 단위 천원">
            <div class="tree-row table-header" role="row"><span role="columnheader">항목 · 대 / 중 / 소 / 상세</span><span role="columnheader">계획값</span><span role="columnheader">실적</span><span role="columnheader">차액</span><span role="columnheader">차이율</span><span role="columnheader">진행 단계</span><span role="columnheader"><span class="visually-hidden">세부사항</span></span></div>
            <div class="tree-group operating-group" role="rowgroup">
              <div class="tree-row operating-row" role="row"><button role="cell" class="tree-label" :aria-expanded="!collapsed.includes('operating')" @click="toggleGroup('operating')"><span>{{ collapsed.includes('operating') ? '+' : '−' }}</span>영업비용</button><span role="cell">1320</span><span role="cell">1236</span><span role="cell" class="difference">−84</span><span role="cell" class="difference">−6.4%</span><span role="cell"></span><span role="cell"></span></div>
              <div v-if="!collapsed.includes('operating')" class="operating-children">
                <div v-if="visibleGroups.length" class="tree-group marketing-group">
                  <div class="tree-row marketing-row" role="row"><button role="cell" class="tree-label" :aria-expanded="!collapsed.includes('marketing')" @click="toggleGroup('marketing')"><span>{{ collapsed.includes('marketing') ? '+' : '−' }}</span>마케팅비용</button><span role="cell">1100</span><span role="cell">1036</span><span role="cell" class="difference">−64</span><span role="cell" class="difference">−5.8%</span><span role="cell"></span><span role="cell"></span></div>
                  <div v-if="!collapsed.includes('marketing')" class="marketing-children">
                    <div v-for="group in visibleGroups" :key="group.id" class="tree-group category-group">
                      <div class="tree-row category-row" role="row"><button role="cell" class="tree-label" :aria-expanded="!collapsed.includes(group.id)" @click="toggleGroup(group.id)"><span>{{ collapsed.includes(group.id) ? '+' : '−' }}</span>{{ group.name }}</button><span role="cell">{{ group.plan }}</span><span role="cell">{{ group.actual }}</span><span role="cell" class="difference">−{{ group.difference }}</span><span role="cell" class="difference">−{{ group.rate }}%</span><span role="cell"></span><span role="cell"></span></div>
                      <div v-if="!collapsed.includes(group.id)" class="detail-children">
                        <div v-for="row in group.rows" :key="row.id" class="tree-row detail-row" role="row"><label role="cell" class="detail-label"><input v-model="selected" :value="row.id" type="checkbox" :aria-label="`${row.name} 선택`" /><span>{{ row.name }}</span></label><span role="cell">{{ row.plan }}</span><span role="cell">{{ row.actual }}</span><span role="cell" class="difference">−{{ row.difference }}</span><span role="cell" class="difference">−{{ row.rate }}%</span><span role="cell" class="row-stage">1단계</span><span role="cell"><button class="detail-button" @click="showDetail(row)">세부사항</button></span></div>
                      </div>
                    </div>
                  </div>
                </div>
                <div v-if="resaleVisible" class="tree-row marketing-row resale-row" role="row"><button role="cell" class="tree-label" @click="explainUnavailable('유선재판매 마케팅비용 상세')"><span>−</span>유선재판매 마케팅비용</button><span role="cell">220</span><span role="cell">200</span><span role="cell" class="difference">−20</span><span role="cell" class="difference">−9.1%</span><span role="cell"></span><span role="cell"></span></div>
              </div>
            </div>
          </div>
        </div>
        <div v-else class="gap-empty" role="status"><strong>표시할 예시 항목이 없습니다.</strong><p>{{ activeTab === 'review' ? '조직 또는 검색 조건을 변경해 주세요.' : '이 상태의 상세 예시는 제공되지 않습니다. 실제 검토 상태는 연결되지 않았습니다.' }}</p></div>
        <p class="table-caption">{{ !search && activeTab === 'review' && organization !== '네트워크인프라부문' ? '12개 항목 · 상세 6개' : `검색된 상세 ${detailCount}개` }}</p>
      </div>
    </section>

    <div class="gap-bulk-bar" aria-label="예시 선택 항목 작업"><strong aria-live="polite">{{ selected.length }}건 선택</strong><div><button class="clear-button" :disabled="!selected.length" @click="clearSelection">선택 해제</button><button class="exclude-button" :disabled="!selected.length" @click="explainUnavailable('선택 항목 제외')">제외</button><button class="review-button" :disabled="!selected.length" @click="explainUnavailable('선택 항목 검토하기')">선택 항목 검토하기 <span aria-hidden="true">→</span></button></div></div>

    <dialog ref="dialog" class="gap-dialog" aria-labelledby="gap-dialog-title"><div class="dialog-heading"><h2 id="gap-dialog-title">{{ dialogTitle }}</h2><button aria-label="대화상자 닫기" @click="closeDialog">×</button></div><p>{{ dialogMessage }}</p><dl v-if="detail"><div><dt>계획값</dt><dd>{{ detail.plan }} 천원</dd></div><div><dt>실적</dt><dd>{{ detail.actual }} 천원</dd></div><div><dt>차액</dt><dd>−{{ detail.difference }} 천원</dd></div><div><dt>차이율</dt><dd>−{{ detail.rate }}%</dd></div></dl><p class="dialog-note">예시 항목을 확인해도 실제 제출·승인 상태는 변경되지 않습니다.</p><button class="apply-button" @click="closeDialog">확인</button></dialog>
  </div>
</template>

<style scoped>
.gap-page {
  color:#16181d;
  font-size:13px;
  max-width:1280px;
  margin:0 auto;
}

.gap-heading {
  display:flex;
  align-items:center;
  justify-content:space-between;
  gap:18px;
  margin:0 0 20px;
}

.gap-heading h1 {
  font-size:26px;
  letter-spacing:-.8px;
}

.design-note {
  font-size:10px;
  color:#85858a;
  margin-top:4px;
}

.version-controls {
  display:flex;
  gap:8px;
  align-items:center;
}

.version-select,.comparison-select,.filter-control {
  display:flex;
  align-items:center;
  background:#fff;
  border:1px solid #e7e4de;
  border-radius:7px;
  gap:12px;
  white-space:nowrap;
}

.version-select {
  padding:0 12px;
  height:37px;
}

.version-select>span {
  color:#9ea2a9;
  font-size:10px;
}

.gap-page select {
  width:auto;
  margin:0;
  padding:0;
  min-height:0;
  border:0;
  background:transparent;
  color:#16181d;
  font-size:12px;
  max-width:100%;
  cursor:pointer;
}

.gap-page input {
  min-height:0;
  margin-top:0;
}

.gap-button {
  background:#fff;
  border:1px solid #e7e4de;
  border-radius:7px;
  padding:10px 14px;
  font-size:12px;
  white-space:nowrap;
}

.gap-criteria {
  display:flex;
  gap:24px;
  border:1px solid #e5e2db;
  border-radius:7px;
  background:#fff;
  padding:18px 22px;
  margin-bottom:24px;
}

.criteria-heading {
  min-width:110px;
  padding-top:5px;
}

.criteria-heading h2 {
  font-size:15px;
}

.criteria-heading p {
  font-size:11px;
  color:#68717d;
  margin-top:12px;
}

.criteria-fields {
  display:flex;
  flex:1;
  flex-wrap:wrap;
  align-items:flex-start;
  gap:8px;
}

.criterion {
  display:flex;
  gap:8px;
  flex:1;
  min-width:230px;
}

.criterion-toggle,.criterion-value {
  height:44px;
  display:flex;
  align-items:center;
  border:1px solid #d9dee4;
  border-radius:7px;
  padding:0 11px;
  gap:9px;
  flex:1;
  min-width:0;
}

.criterion-toggle {
  font-weight:700;
  cursor:pointer;
  white-space:nowrap;
}

.criterion-toggle.checked {
  background:#f6f0e4;
  border-color:#aa8958;
  color:#604923;
}

.criterion-toggle input {
  padding:0;
  flex-shrink:0;
  margin:0;
  width:17px;
  height:17px;
  accent-color:#9b7b48;
}

.criterion-value input {
  width:48px;
  border:0;
  font-weight:700;
  min-width:0;
  background:transparent;
  -moz-appearance:textfield;
}

.criterion-value input::-webkit-inner-spin-button {
  appearance:none;
}

.criterion-value .suffix {
  font-size:11px;
  color:#68717d;
  margin-left:auto;
  white-space:nowrap;
}

.comparison-select {
  height:44px;
  padding:0 12px;
  gap:10px;
}

.comparison-select>span {
  font-size:11px;
  color:#9ea2a9;
}

.comparison-select select {
  font-size:12px;
  appearance:none;
}

.apply-button {
  background:#293541;
  color:#fff;
  border-radius:6px;
  font-size:13px;
  font-weight:700;
  min-height:44px;
  padding:0 28px;
  white-space:nowrap;
}

.criteria-help {
  flex-basis:100%;
  color:#68717d;
  font-size:11px;
  margin-top:3px;
}

.gap-pipeline {
  list-style:none;
  padding:0 20px;
  margin:0 0 36px;
  display:grid;
  grid-template-columns:repeat(5,minmax(0,1fr));
  gap:21px;
}

.gap-pipeline li {
  position:relative;
  border:1px solid;
  border-radius:8px;
  min-height:80px;
}

.gap-pipeline button {
  display:flex;
  align-items:flex-start;
  gap:10px;
  padding:15px 12px;
  width:100%;
  height:100%;
  text-align:left;
  background:transparent;
  color:inherit;
}

.stage-number {
  position:relative;
  display:block;
  flex:0 0 23.6528px;
  height:23.6528px;
}

.stage-number img {
  display:block;
  max-width:none;
}

.stage-number b {
  position:absolute;
  inset:0;
  display:grid;
  place-items:center;
  font-size:12px;
  color:white;
}

.gap-pipeline strong {
  display:block;
  font-size:14px;
  line-height:24px;
  white-space:nowrap;
}

.gap-pipeline small {
  display:block;
  font-size:11px;
  color:#596675;
  margin-top:5px;
  white-space:nowrap;
}

.stage-1 {
  background:#fff6e3;
  border-color:#dec18c!important;
  color:#80550d;
}

.stage-2 {
  background:#edf4ff;
  border-color:#afc8ec!important;
  color:#265aa7;
}

.stage-3 {
  background:#f5eefc;
  border-color:#ceb5e5!important;
  color:#7444a6;
}

.stage-4 {
  background:#fff0e9;
  border-color:#e9baa9!important;
  color:#ac4930;
}

.stage-5 {
  background:#eaf7f1;
  border-color:#9fcdbd!important;
  color:#19705b;
}

.stage-arrow {
  position:absolute;
  right:-15px;
  top:24px;
  font-size:22px;
  color:#9aa6b3;
}

.gap-list {
  background:#fff;
  border:1px solid #e7e4de;
  border-radius:12px;
  padding:0 23px 20px;
}

.gap-tabs {
  display:flex;
  align-items:stretch;
  gap:23px;
  min-height:51px;
}

.gap-tabs>button {
  position:relative;
  padding:17px 0 13px;
  background:transparent;
  font-size:13px;
  color:#5f636b;
  border-bottom:2px solid #f5f4f0;
  white-space:nowrap;
}

.gap-tabs>button.active {
  color:#16181d;
  font-weight:700;
  border-color:#16181d;
}

.gap-tabs>button span {
  font-size:11px;
  color:#9ea2a9;
  margin-left:5px;
}

.gap-tabs>button.active span {
  color:#a9875b;
}

.tab-design-line {
  position:absolute;
  bottom:0;
  left:0;
  height:0;
  overflow:hidden;
  margin:0!important;
}

.tab-design-line img {
  display:block;
  max-width:none;
}

.gap-filters {
  display:flex;
  align-items:center;
  gap:10px;
  margin:15px 0;
}

.filter-control {
  height:37px;
  padding:0 10px;
  gap:10px;
}

.filter-control>span,.sort-control>span {
  font-size:11px;
  color:#9ea2a9;
}

.filter-control select {
  font-size:13px;
  appearance:none;
}

.unit-control select {
  appearance:auto;
}

.item-search {
  height:37px;
  width:200px;
  max-width:35%;
  border:1px solid #e7e4de;
  border-radius:7px;
  padding:0 13px;
  font-size:13px;
  background:#fff;
}

.item-search::placeholder {
  color:#9ea2a9;
}

.sort-control {
  display:flex;
  align-items:center;
  gap:12px;
  margin-left:auto;
}

.sort-control select {
  height:37px;
  border:1px solid #e7e4de;
  border-radius:7px;
  padding:0 8px;
  font-size:12px;
}

.gap-table-scroll {
  overflow-x:auto;
  border:1px solid #e7e4de;
  border-radius:8px 8px 0 0;
}

.gap-tree {
  min-width:850px;
  container-type:inline-size;
}

.tree-row {
  display:grid;
  grid-template-columns:minmax(220px,1fr) 7cqw 7cqw 10cqw 10cqw 15cqw 12cqw;
  align-items:center;
  min-height:40px;
  font-size:13px;
  line-height:20px;
}

.tree-row>span {
  text-align:right;
  padding-right:16px;
  font-variant-numeric:tabular-nums;
  white-space:nowrap;
}

.tree-row>span:nth-last-child(2),.tree-row>span:last-child {
  text-align:center;
  padding:0 8px;
}

.table-header {
  min-height:34px;
  background:#faf9f6;
  color:#5f636b;
  font-size:11px;
}

.table-header>span:first-child {
  text-align:left;
  padding-left:50px;
}

.tree-label {
  display:flex;
  align-items:center;
  gap:14px;
  background:transparent;
  color:inherit;
  font-weight:700;
  text-align:left;
  padding:0 12px;
  font-size:13px;
  min-height:40px;
  white-space:nowrap;
}

.tree-label>span {
  font-size:16px;
  font-weight:700;
  width:14px;
  text-align:center;
  flex-shrink:0;
}

.operating-group {
  border-left:4px solid #102e59;
  border-bottom:3px solid #102e59;
}

.operating-row {
  background:#102e59;
  color:#fff;
  font-weight:700;
  margin-left:-4px;
}

.operating-row .difference {
  color:#c6deff;
}

.operating-children {
  padding:8px 0 7px 15px;
}

.marketing-group {
  border-left:4px solid #73b7c5;
  border-bottom:3px solid #73b7c5;
}

.marketing-row {
  background:#d8eef2;
  color:#214f5c;
  font-weight:700;
}

.marketing-group>.marketing-row {
  margin-left:-4px;
}

.marketing-children {
  padding:8px 0 7px 15px;
}

.category-group {
  border-left:3px solid #a8bf8d;
  border-bottom:3px solid #a8bf8d;
  margin-bottom:9px;
}

.category-group:last-child {
  margin-bottom:0;
}

.category-row {
  background:#edf3e5;
  color:#435c34;
  font-weight:700;
  margin-left:-3px;
}

.detail-children {
  padding:7px 0 6px 14px;
}

.detail-row {
  background:#f5f7f9;
  color:#263240;
  border-bottom:1px solid #e1e6e9;
  margin-bottom:10px;
  font-weight:400;
}

.detail-row:last-child {
  margin-bottom:0;
}

.detail-label {
  display:flex;
  align-items:center;
  font:inherit;
  color:inherit;
  gap:11px;
  padding:0 10px;
  white-space:nowrap;
  cursor:pointer;
}

.detail-label input {
  padding:0;
  flex-shrink:0;
  width:15px;
  height:15px;
  margin:0;
  accent-color:#293541;
}

.difference {
  color:#2457ad;
}

.row-stage {
  color:#80550d;
  font-size:11px;
}

.detail-button {
  background:#fff;
  border:1px solid #96a3b0;
  border-radius:5px;
  color:#30404f;
  font-size:11px;
  padding:3px 12px;
  min-height:28px;
  white-space:nowrap;
}

.resale-row {
  margin-top:10px;
}

.table-caption {
  text-align:center;
  color:#5f636b;
  font-size:11px;
  margin:20px 0 13px;
}

.gap-bulk-bar {
  background:#14171e;
  color:#fff;
  min-height:65px;
  border-radius:12px;
  display:flex;
  align-items:center;
  justify-content:space-between;
  padding:12px 22px;
  margin-top:18px;
}

.gap-bulk-bar strong {
  font-size:14px;
}

.gap-bulk-bar>div {
  display:flex;
  gap:4px;
}

.gap-bulk-bar button {
  color:white;
  min-height:37px;
  border-radius:7px;
  font-size:12px;
  padding:0 15px;
}

.clear-button {
  background:#2a2f3a;
}

.exclude-button {
  background:#b4c1d3;
  min-width:83px;
}

.review-button {
  background:#a9875b;
  min-width:220px;
  text-align:left;
}

.review-button span {
  margin-left:8px;
}

.gap-bulk-bar button:disabled {
  opacity:.5;
}

.gap-empty {
  min-height:340px;
  display:grid;
  align-content:center;
  justify-items:center;
  gap:12px;
  color:#68717d;
  padding:35px;
  text-align:center;
}

.gap-empty strong {
  font-size:15px;
}

.gap-empty p {
  font-size:12px;
}

.gap-dialog {
  border:1px solid #e7e4de;
  border-radius:14px;
  padding:26px;
  max-width:500px;
  width:calc(100% - 32px);
  color:#263240;
}

.gap-dialog::backdrop {
  background:#14171e80;
}

.dialog-heading {
  display:flex;
  align-items:center;
  justify-content:space-between;
  gap:20px;
  margin-bottom:18px;
}

.dialog-heading h2 {
  font-size:18px;
}

.dialog-heading button {
  background:transparent;
  font-size:25px;
  line-height:1;
  color:#68717d;
}

.gap-dialog>p {
  font-size:13px;
  line-height:1.8;
}

.gap-dialog dl {
  display:grid;
  grid-template-columns:1fr 1fr;
  gap:15px;
  background:#f5f7f9;
  padding:18px;
  border-radius:8px;
}

.gap-dialog dt {
  font-size:11px;
  color:#68717d;
}

.gap-dialog dd {
  font-size:15px;
  margin:6px 0 0;
  font-weight:700;
}

.gap-dialog .dialog-note {
  font-size:11px;
  color:#68717d;
  margin:16px 0;
}

.gap-dialog>.apply-button {
  float:right;
  min-height:38px;
}

.gap-page button:focus-visible,.gap-page select:focus-visible {
  outline:3px solid #a9875b;
  outline-offset:3px;
}

@media(min-width:1500px) {
  .criterion {
    min-width:270px;
  }
}

@media(max-width:1250px) {
  .gap-heading {
    align-items:flex-start;
  }
  .version-controls {
    flex-wrap:wrap;
    justify-content:flex-end;
  }
  .version-select {
    gap:7px;
  }
  .gap-criteria {
    gap:16px;
    padding:16px;
  }
  .criteria-heading {
    min-width:90px;
  }
  .criteria-fields {
    gap:8px;
  }
  .criterion {
    min-width:225px;
  }
  .gap-pipeline {
    padding:0;
    gap:15px;
  }
  .gap-pipeline button {
    gap:7px;
    padding:13px 8px;
  }
  .gap-pipeline strong {
    font-size:12px;
  }
  .gap-pipeline small {
    font-size:10px;
  }
  .stage-arrow {
    right:-12px;
  }
  .gap-tabs {
    gap:18px;
  }
  .gap-tabs>button {
    font-size:12px;
  }
  .gap-list {
    padding:0 18px 15px;
  }
  .criteria-help {
    font-size:10px;
  }
}

@media(max-width:900px) {
  .gap-heading {
    display:block;
  }
  .version-controls {
    justify-content:flex-start;
    margin-top:15px;
  }
  .gap-criteria {
    display:block;
  }
  .criteria-heading {
    display:flex;
    align-items:center;
    gap:15px;
    margin-bottom:15px;
  }
  .criteria-heading p {
    margin:0;
  }
  .gap-pipeline {
    overflow-x:auto;
    grid-template-columns:repeat(5,165px);
    padding-bottom:3px;
    margin-bottom:24px;
  }
  .gap-tabs {
    overflow-x:auto;
  }
  .gap-filters {
    flex-wrap:wrap;
  }
  .sort-control {
    margin-left:0;
  }
  .item-search {
    max-width:none;
    flex:1;
    min-width:130px;
  }
  .gap-list {
    padding:0 14px 10px;
  }
  .gap-bulk-bar {
    gap:15px;
    flex-wrap:wrap;
    padding:14px;
  }
  .gap-bulk-bar>div {
    flex-wrap:wrap;
  }
  .review-button {
    min-width:180px;
  }
  .gap-heading h1 {
    font-size:23px;
  }
}

@media(prefers-reduced-motion:reduce) {
  * {
    scroll-behavior:auto;
  }
}
</style>
