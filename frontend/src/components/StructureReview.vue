<script setup>
import { computed, nextTick, ref } from 'vue'
import { TEXT_KINDS, boxStyle, changeKind, kindChoices, kindLabel, listRows, reviewCount, reviewReasons, tableView, tidyNotes } from '../lib/structure-review.js'

/** PDF 구성 확인(데모 3열): 쪽 그림 위 번호 박스 | 블록 목록 | 고른 블록 고치기.
 * 데이터 조회·AI 호출은 하지 않는다. PDF 분석 결과(블록의 bbox·needs_review·tidy, 쪽 크기)만 쓴다.
 * Props: blocks 정의의 블록 목록, pages [{no, url}] 숫자를 가린 쪽 그림, sizes {쪽번호: {width, height}}(pt), busy 처리 중.
 * Emits: edited(내용 변경), move({id, dir}), remove(id), acknowledge(block). */
const props = defineProps({
  blocks: { type: Array, required: true },
  pages: { type: Array, default: () => [] },
  sizes: { type: Object, default: () => ({}) },
  busy: { type: Boolean, default: false },
})
const emit = defineEmits(['edited', 'move', 'remove', 'acknowledge'])

const selected = ref(null)
const checked = ref({})          // 확인한 블록의 원래 확인 필요 표시(확인 취소용)
const cards = {}
const indexed = computed(() => props.blocks.map((block, i) => ({ block, n: i + 1, reasons: reviewReasons(block), style: boxStyle(block, props.sizes), done: !!checked.value[block.id] })))
const reviews = computed(() => reviewCount(props.blocks))
const positioned = computed(() => indexed.value.some(item => item.style))
const current = computed(() => indexed.value.find(item => item.block.id === selected.value) || null)
const boxesOf = no => indexed.value.filter(item => item.style && Number(item.block.page) === no)
const isText = block => TEXT_KINDS.includes(block.kind) || block.kind === 'text'
const label = block => kindLabel(block.kind)

/** 블록을 고른다(박스·카드 어느 쪽을 눌러도 같다). 목록에서 그 카드가 보이게 스크롤한다. */
async function select(id) {
  selected.value = id
  await nextTick()
  cards[id]?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' })
}
function setKind(block, kind) { if (changeKind(block, kind)) emit('edited') }

/** 확인했음을 표시한다. 원래 표시를 기억해 두어 "확인 취소"로 되살릴 수 있다. */
function confirm(item) { checked.value = { ...checked.value, [item.block.id]: JSON.parse(JSON.stringify(item.block.needs_review || [])) }; emit('acknowledge', item.block) }
/** 확인 취소: 기억해 둔 확인 필요 표시를 되돌린다. */
function unconfirm(item) {
  item.block.needs_review = checked.value[item.block.id] || []
  const next = { ...checked.value }; delete next[item.block.id]; checked.value = next
  emit('edited')
}
function remove(id) { if (selected.value === id) selected.value = null; emit('remove', id) }
</script>

<template>
  <div class="rs-work">
    <div class="rs-panel">
      <div class="rs-cap">원본 쪽 그림(숫자는 가려져 있습니다). 번호 박스를 누르면 오른쪽에서 고칠 수 있어요.</div>
      <div v-if="pages.length && !positioned" class="rs-hint">이 분석 결과에는 블록 위치 정보가 없어 박스를 그리지 못했습니다. 그림과 블록 목록을 나란히 보고 확인해 주세요.</div>
      <div class="rs-pages" role="group" aria-label="숫자를 가린 원본 페이지">
        <figure v-for="page in pages" :key="page.no" class="rs-page">
          <img :src="page.url" :alt="`숫자를 가린 원본 ${page.no}쪽`" />
          <button v-for="item in boxesOf(page.no)" :key="item.block.id" type="button" class="rs-box"
            :class="{ rev: item.reasons.length, res: item.done, sel: selected === item.block.id }" :style="item.style"
            :aria-label="`블록 ${item.n} ${label(item.block)}${item.reasons.length ? ', 확인 필요' : ''}`" :aria-pressed="selected === item.block.id" @click="select(item.block.id)">
            <i>{{ item.n }}</i>
          </button>
        </figure>
        <div v-if="!pages.length" class="rs-hint">쪽 그림이 없는 틀입니다. 블록 목록에서 확인해 주세요.</div>
      </div>
    </div>

    <div class="rs-mid">
      <div class="rs-legend"><span style="background:var(--rs-blue-bg);color:var(--rs-blue-d)">파랑 = 문제 표시 없음</span><span style="background:var(--rs-warn-chip);color:var(--rs-warn-ink)">노랑 = 확인 필요 {{ reviews }}개</span><span style="background:var(--rs-ok-bg);color:var(--rs-ok)">초록 = 확인함</span></div>
      <div v-for="item in indexed" :key="item.block.id" :ref="el => { if (el) cards[item.block.id] = el }" role="button" tabindex="0" class="rs-blk"
        :class="{ rev: item.reasons.length, sel: selected === item.block.id }" @click="select(item.block.id)" @keydown.enter.self.prevent="select(item.block.id)">
        <div class="h"><b>{{ item.n }}. {{ label(item.block) }}<small style="font-weight:400;color:var(--rs-sub)"> · {{ item.block.id }}</small></b>
          <span v-if="item.reasons.length" class="rs-st rev">확인 필요</span><span v-else-if="item.done" class="rs-st ok">확인함</span><span v-else class="rs-st ok">확인됨</span></div>
        <div v-if="item.reasons.length" class="s">{{ item.reasons.map(r => r.label).join(' · ') }}</div>
        <div v-if="isText(item.block)" class="s" style="color:var(--rs-ink)">{{ (item.block.text || '').slice(0, 120) }}</div>
        <div v-else-if="item.block.kind === 'table'" class="rs-scroll">
          <table class="rs-mini"><thead><tr v-for="(line, li) in tableView(item.block).head" :key="li"><th v-for="(cell, ci) in line" :key="ci" :colspan="cell.colspan" :rowspan="cell.rowspan">{{ cell.text }}</th></tr></thead>
            <tbody><tr v-for="(row, ri) in tableView(item.block).body.slice(0, 4)" :key="ri"><td v-for="(cell, ci) in row" :key="ci">{{ cell }}</td></tr></tbody></table>
          <small v-if="tableView(item.block).body.length > 4" class="s">… 외 {{ tableView(item.block).body.length - 4 }}행</small>
        </div>
        <ul v-else-if="item.block.kind === 'list'" class="rs-items"><li v-for="(row, ri) in listRows(item.block.items).slice(0, 5)" :key="ri" :style="{ paddingLeft: `${row.depth * 16}px` }"><span class="mk">{{ row.marker }}</span>{{ row.text }}</li></ul>
        <div v-else-if="item.block.kind === 'chart'" class="s">차트({{ item.block.chart_type || '종류 미정' }}){{ item.block.title ? ` · ${item.block.title}` : '' }}{{ item.block.categories?.length ? ` · 항목 ${item.block.categories.length}개: ${item.block.categories.slice(0, 6).join(', ')}` : '' }}</div>
        <div v-else-if="item.block.kind === 'numbercard'" class="s">숫자 카드 {{ item.block.cards?.length || 0 }}개: {{ (item.block.cards || []).map(c => c.label).join(', ') }}</div>
      </div>
    </div>

    <div class="rs-panel rs-edit">
      <template v-if="current">
        <div class="t"><b>이 블록 고치기</b><span>{{ current.n }}번 · {{ current.block.id }}</span></div>
        <div v-if="current.reasons.length">
          <div v-for="(r, ri) in current.reasons" :key="ri" class="rs-reason" role="alert" style="margin-bottom:6px">
            <b>{{ r.label }}</b><span v-if="r.reason"> — {{ r.reason }}</span>
            <details v-if="r.help"><summary>이 표시는 무슨 뜻인가요?</summary>{{ r.help }}</details>
          </div>
        </div>
        <div v-else-if="current.done" class="rs-okbox">확인한 블록입니다.</div>
        <div v-else class="rs-okbox">문제 표시가 없는 블록입니다.</div>
        <div><label class="l" :for="`sr-kind-${current.block.id}`">종류</label>
          <select :id="`sr-kind-${current.block.id}`" :value="current.block.kind" :disabled="busy" @change="setKind(current.block, $event.target.value)">
            <option v-for="c in kindChoices(current.block)" :key="c.value" :value="c.value">{{ c.label }}</option></select></div>
        <div v-if="!isText(current.block)" class="rs-hint">표·목록·차트·숫자 카드는 문장·제목·각주로만 바꿀 수 있습니다(PDF에서 읽은 내용이 없는 표를 새로 만들 수는 없습니다).</div>
        <div v-if="isText(current.block)"><label class="l" :for="`sr-text-${current.block.id}`">본문</label>
          <textarea :id="`sr-text-${current.block.id}`" v-model="current.block.text" rows="5" :disabled="busy" @input="emit('edited')"></textarea></div>
        <div v-else><label class="l" :for="`sr-title-${current.block.id}`">제목(다음 단계에서 이 이름으로 보입니다)</label>
          <input :id="`sr-title-${current.block.id}`" v-model="current.block.title" type="text" :disabled="busy" @input="emit('edited')" /></div>
        <div v-if="tidyNotes(current.block).length" class="rs-hint">코드 정리: {{ tidyNotes(current.block).join(' · ') }}</div>
        <div class="rs-acts">
          <button v-if="current.reasons.length" type="button" class="rs-btn" :disabled="busy" @click="confirm(current)">확인했습니다</button>
          <button v-else-if="current.done" type="button" class="rs-btn ghost" :disabled="busy" @click="unconfirm(current)">확인 취소</button>
          <button type="button" class="rs-btn ghost" :disabled="busy" :aria-label="`블록 ${current.n} 위로`" @click="emit('move', { id: current.block.id, dir: 'up' })">↑ 위로</button>
          <button type="button" class="rs-btn ghost" :disabled="busy" :aria-label="`블록 ${current.n} 아래로`" @click="emit('move', { id: current.block.id, dir: 'down' })">↓ 아래로</button>
          <button type="button" class="rs-btn ghost" :disabled="busy" @click="remove(current.block.id)">빼기</button>
        </div>
      </template>
      <div v-else class="rs-hint">왼쪽 그림의 번호 박스나 가운데 목록에서 블록을 고르면 여기서 종류·내용을 고칠 수 있습니다.</div>
    </div>
  </div>
</template>
