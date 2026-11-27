<script setup>
import { computed, ref } from 'vue'
import LiveGapTable from './LiveGapTable.vue'

const unit = ref('THOUSAND'), width = ref('100%'), selected = ref([]), query = ref('')
const rows = [
  ['매출', '기타 / 신사업·기타B2B', '신사업·기타B2B · 11월 실적', '200000000', '240000000', '검토 필요'],
  ['매출', '기타 / 신사업·기타B2B', '기업 솔루션 매출 · 11월 실적', '150000000', '180000000', '검토 필요'],
  ['매출', '통신 서비스', '모바일 서비스 매출 · 11월 실적', '350000000', '300000000', '소명 진행중'],
  ['매출', '통신 서비스', '기업 전용회선 매출 · 11월 실적', '100000000', '125000000', '재검토 필요'],
  ['영업비용', '인건비', '급여 · 11월 실적', '120000000', '135000000', '검토 필요'],
  ['영업비용', '인건비', '복리후생비 · 11월 실적', '30000000', '36000000', '소명 승인 완료'],
  ['영업비용', '지급수수료', '외주 용역비 · 11월 실적', '80000000', '70000000', '검토 필요'],
  ['영업비용', '지급수수료', '결제·판매 수수료 · 11월 실적', '50000000', '60000000', '제외됨'],
].map(([group, category, name, baselineAmount, currentAmount, status], index) => ({
  id: 'preview-' + index, major: group, group: category.split(' / ')[0], category: category.split(' / ')[1] || name.split(' · ')[0], name, baselineAmount, currentAmount, status,
}))
const visible = computed(() => rows.filter(row => (row.group + row.category + row.name).includes(query.value)))
function toggle(id) { selected.value = selected.value.includes(id) ? selected.value.filter(value => value !== id) : [...selected.value, id] }
function toggleAll(value,ids) { selected.value = value ? visible.value.filter(row => ['검토 필요', '검토 완료'].includes(row.status)&&(!ids||ids.includes(row.id))).map(row => row.id) : [] }
</script>

<template>
  <main class="app-layout gap-preview">
    <header class="gap-preview-heading"><div><p class="breadcrumb">개발용 미리보기</p><h1>다중 항목 표</h1></div><a href="/#/calculator" class="btn">서비스로 이동</a></header>
    <p class="notice">디자인 예시 · 사업부1의 실제 제출 데이터와 별개인 8개 항목입니다. 조직 합계 없이 매출·영업비용부터 대/중/소 분류를 표시합니다.</p>
    <div class="gap-preview-controls">
      <label>표 영역 너비 <select v-model="width"><option value="100%">화면에 맞게</option><option value="960px">960px · 좁은 화면</option></select></label>
      <label>표시 단위 <select v-model="unit"><option value="THOUSAND">천원</option><option value="HUNDRED_MILLION">억원</option></select></label>
      <label>항목 검색 <input v-model="query" type="search" placeholder="예: 급여, 매출"></label>
      <span>{{ visible.length }}개 항목 · 선택 {{ selected.length }}개</span>
    </div>
    <section class="monthly-panel gap-preview-table" :style="{ width }" aria-label="다중 항목 계층형 표 미리보기">
      <LiveGapTable v-if="visible.length" :rows="visible" :selected="selected" :unit="unit" type="실적 vs 계획" editable @select="toggle" @all="toggleAll" />
      <p v-else class="card">검색 결과가 없습니다.</p>
    </section>
    <p class="gap-preview-note">체크박스와 항목 클릭은 이 미리보기 안에서만 동작합니다. 숫자 증감은 +/− 기호와 함께 표시되며, 비용 증가는 손익 개선을 의미하지 않습니다.</p>
  </main>
</template>

<style>
.app-layout.gap-preview{display:block;min-height:100vh;padding:28px 32px;background:#f7f7f6}
.gap-preview-heading{display:flex;align-items:center;justify-content:space-between;gap:20px;margin-bottom:18px}
.gap-preview-heading h1{font-size:26px}.gap-preview-heading .breadcrumb{margin:0 0 6px}
.gap-preview-controls{display:flex;flex-wrap:wrap;align-items:center;gap:16px;margin:24px 0 18px;padding:16px;background:white;border:1px solid #e6e6e5;border-radius:12px}
.gap-preview-controls label{display:flex;align-items:center;gap:10px;font-size:13px}.gap-preview-controls select,.gap-preview-controls input{background:white;padding:9px 12px;border:1px solid #dededc;min-height:40px}
.gap-preview-controls>span{margin-left:auto;font-size:13px;color:#686865}.gap-preview-table{max-width:100%;min-width:0}.gap-preview-note{margin-top:20px;font-size:13px;color:#767672}
@media(max-width:700px){.app-layout.gap-preview{padding:20px 12px}.gap-preview-heading{align-items:flex-start}.gap-preview-controls{gap:12px}.gap-preview-controls>span{margin-left:0}}
</style>
