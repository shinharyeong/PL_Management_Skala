<script setup>
import { computed, ref } from 'vue'
import { eok } from '../lib/report-studio.js'

/** 보고서 만들기 4단계(소명 분류): 승인된 소명을 사유 분류함으로 끌어 놓는다. 서버 호출은 하지 않고 이벤트로 알린다.
 * Props: explanations 서버의 소명 목록·합계, categories 분류 후보(assignable), comparison 비교 기준, busy.
 * Emits: classify(item, categoryId 또는 ''), load. 비교 기준은 부모가 기준 월 상태로 정해 준다. */
const props = defineProps({
  explanations: { type: Object, default: null },
  categories: { type: Array, default: () => [] },
  comparison: { type: String, default: 'ACTUAL_VS_PLAN' },
  busy: { type: Boolean, default: false },
})
const emit = defineEmits(['classify', 'load'])
const dragId = ref(null), overBin = ref(null)
const LABELS = { ACTUAL_VS_PLAN: '실적 vs 계획', ESTIMATE_VS_PLAN: '추정 vs 계획', ESTIMATE_VS_PREVIOUS: '추정 vs 직전 추정' }
const label = computed(() => LABELS[props.comparison] || props.comparison)
const items = computed(() => props.explanations?.items || [])
const loose = computed(() => items.value.filter(item => !item.categoryId))
const inBin = id => items.value.filter(item => item.categoryId === id)
const nameOf = id => props.categories.find(category => category.id === id)?.name || ''
const money = value => eok(value, { sign: true })
const tone = value => (Number(value) >= 0 ? 'rs-pos' : 'rs-neg')

function start(event, item) { dragId.value = item.explanationId; event.dataTransfer?.setData('text/plain', String(item.explanationId)); if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move' }
function drop(categoryId) {
  const item = items.value.find(row => row.explanationId === dragId.value)
  dragId.value = null; overBin.value = null
  if (item && item.categoryId !== categoryId && !props.busy) emit('classify', item, categoryId)
}
</script>

<template>
  <div>
    <div class="rs-cbanner">승인된 소명을 오른쪽 분류함에 끌어 놓으세요. 분류 합계가 Gap 합계와 같아지면 보고서에 그대로 쓸 수 있습니다.</div>
    <div class="rs-row" style="padding:0 32px 12px">
      <span class="rs-cap">비교 기준(데이터 단계의 기준 월에 따라 자동)</span><span class="rs-tagok">{{ label }}</span>
      <button type="button" class="rs-btn ghost" :disabled="busy" @click="emit('load')">새로고침</button>
    </div>
    <div v-if="!explanations" class="rs-msg info">비교 기준을 고르고 “소명 불러오기”를 누르면 승인된 소명이 나타납니다.</div>
    <div v-else class="rs-cls">
      <section class="rs-panel">
        <b>분류할 소명 <small class="rs-cap">{{ loose.length }}건</small></b>
        <div v-for="item in loose" :key="item.explanationId" class="rs-gcard" :class="{ dragging: dragId === item.explanationId }" draggable="true"
          @dragstart="start($event, item)" @dragend="dragId = null; overBin = null">
          <div class="r"><b>{{ item.organization?.name }} · {{ item.item }}</b><span :class="tone(item.gapAmount)" :title="`${item.gapAmount}원`">{{ money(item.gapAmount) }}</span></div>
          <div class="s">{{ item.answer }}</div>
          <button v-if="item.suggestedCategoryId" type="button" class="dj" :disabled="busy" @click="emit('classify', item, item.suggestedCategoryId)">제안 분류 · {{ nameOf(item.suggestedCategoryId) }} 적용</button>
          <div class="rs-row" style="margin-top:6px"><label :for="`rs-cat-${item.explanationId}`" class="rs-cap">사유 유형</label>
            <select :id="`rs-cat-${item.explanationId}`" class="rs-input" style="width:auto;height:30px" :value="''" :disabled="busy" @change="$event.target.value && emit('classify', item, $event.target.value)">
              <option value="">선택</option><option v-for="c in categories" :key="c.id" :value="c.id">{{ c.name }}</option></select></div>
        </div>
        <div v-if="!loose.length" class="rs-hint">{{ items.length ? '모든 소명을 분류했습니다.' : '분류할 승인 소명이 없습니다.' }}</div>
        <template v-if="explanations.reviewing?.length">
          <div class="rs-cap">검토 중(아직 분류할 수 없음)</div>
          <div v-for="(item, i) in explanations.reviewing" :key="i" class="rs-gcard nodrag"><div class="r"><b>{{ item.organization?.name }} · {{ item.item }}</b><span class="rs-cap">검토 중</span></div></div>
        </template>
      </section>

      <section class="rs-panel">
        <b>분류함</b>
        <div v-for="c in categories" :key="c.id" class="rs-bin" :class="{ over: overBin === c.id }" @dragover.prevent="overBin = c.id" @dragleave="overBin = null" @drop.prevent="drop(c.id)">
          <div class="bh"><b>{{ c.name }}</b><span class="rs-cap">{{ inBin(c.id).length }}건</span></div>
          <div v-for="item in inBin(c.id)" :key="item.explanationId" class="rs-chip"><span>{{ item.organization?.name }} · {{ item.item }} <span :class="tone(item.gapAmount)">{{ money(item.gapAmount) }}</span></span>
            <button type="button" :disabled="busy" :aria-label="`${item.item} 분류 해제`" @click="emit('classify', item, '')">✕</button></div>
          <div v-if="!inBin(c.id).length" class="ph">여기로 끌어 놓기</div>
        </div>
        <div class="rs-sumrow"><span>Gap 합계 <b :title="`${explanations.gapTotal}원`">{{ money(explanations.gapTotal) }}</b></span><span>분류 합계 <b :title="`${explanations.classifiedTotal}원`">{{ money(explanations.classifiedTotal) }}</b></span>
          <span :class="explanations.matchesGapTotal ? 'rs-vok' : 'rs-vbad'">{{ explanations.matchesGapTotal ? '일치' : '아직 일치하지 않음(분류 전·검토 중 소명이 있습니다)' }}</span></div>
      </section>
    </div>
  </div>
</template>
