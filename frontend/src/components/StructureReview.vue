<script setup>
import { computed, nextTick, ref } from 'vue'
import PButton from 'primevue/button'
import PCheckbox from 'primevue/checkbox'
import PInputText from 'primevue/inputtext'
import PMessage from 'primevue/message'
import PSelect from 'primevue/select'
import PTag from 'primevue/tag'
import PTextarea from 'primevue/textarea'
import { TEXT_KINDS, boxStyle, changeKind, kindChoices, kindLabel, listRows, reviewCount, reviewReasons, tableView, tidyNotes } from '../lib/structure-review.js'

/** PDF 구성 확인: 쪽 그림 위에 블록 박스를 그리고(파랑 = 문제 표시 없음, 노랑 = 확인 필요), 같은 번호의 블록 목록에서 종류·본문을 고친다.
 * 데이터 조회·AI 호출은 하지 않는다. PDF 분석 결과(블록의 bbox·needs_review·tidy, 쪽 크기)만 쓴다.
 * Props: blocks 정의의 블록 목록, pages [{no, url}] 숫자를 가린 쪽 그림, sizes {쪽번호: {width, height}}(pt), busy 저장·요청 중.
 * Emits: edited(내용 변경), move({id, dir}), remove(id), acknowledge(block). */
const props = defineProps({
  blocks: { type: Array, required: true },
  pages: { type: Array, default: () => [] },
  sizes: { type: Object, default: () => ({}) },
  busy: { type: Boolean, default: false },
})
const emit = defineEmits(['edited', 'move', 'remove', 'acknowledge'])

const selected = ref(null)
const cards = {}
const indexed = computed(() => props.blocks.map((block, i) => ({ block, n: i + 1, reasons: reviewReasons(block), style: boxStyle(block, props.sizes) })))
const reviews = computed(() => reviewCount(props.blocks))
const positioned = computed(() => indexed.value.some(item => item.style))
const boxesOf = no => indexed.value.filter(item => item.style && Number(item.block.page) === no)

/** 블록을 고른다(박스·카드 어느 쪽을 눌러도 같다). 목록에서 그 카드가 보이게 스크롤한다. */
async function select(id) {
  selected.value = id
  await nextTick()
  cards[id]?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' })
}

/** 종류를 바꾼다. 허용되지 않는 종류(없는 표 내용을 만들 수 없음)는 무시한다. */
function setKind(block, kind) { if (changeKind(block, kind)) emit('edited') }

const isText = block => TEXT_KINDS.includes(block.kind) || block.kind === 'text'
const label = block => kindLabel(block.kind)
</script>

<template>
  <div class="sr">
    <PMessage v-if="pages.length && !positioned" severity="secondary" :closable="false">이 분석 결과에는 블록 위치 정보가 없어 박스를 그리지 못했습니다. 그림과 블록 목록을 나란히 보고 확인해 주세요.</PMessage>
    <p class="sr-summary">블록 {{ blocks.length }}개 · 확인 필요 <strong>{{ reviews }}개</strong>. 파란 박스는 문제 표시가 없는 블록, <span class="sr-key">노란 박스</span>는 확인이 필요한 블록입니다. 박스나 오른쪽 목록을 눌러 확인하고 고치세요.</p>
    <div class="sr-layout">
      <div class="sr-pages" role="group" aria-label="숫자를 가린 원본 페이지">
        <figure v-for="page in pages" :key="page.no" class="sr-page">
          <div class="sr-canvas">
            <img :src="page.url" :alt="`숫자를 가린 원본 ${page.no}쪽`" />
            <button v-for="item in boxesOf(page.no)" :key="item.block.id" type="button" class="sr-box"
              :class="{ 'sr-review': item.reasons.length, 'sr-selected': selected === item.block.id }" :style="item.style"
              :aria-label="`블록 ${item.n} ${label(item.block)}${item.reasons.length ? ', 확인 필요' : ''}`" :aria-pressed="selected === item.block.id" @click="select(item.block.id)">
              <span class="sr-num">{{ item.n }}</span>
            </button>
          </div>
          <figcaption>원본 {{ page.no }}쪽 · 숫자를 가림</figcaption>
        </figure>
      </div>

      <div class="sr-list">
        <article v-for="item in indexed" :key="item.block.id" :ref="el => { if (el) cards[item.block.id] = el }" class="sr-card"
          :class="{ 'sr-review': item.reasons.length, 'sr-selected': selected === item.block.id }" @click="select(item.block.id)">
          <header class="sr-head">
            <span class="sr-badge" :class="{ 'sr-review': item.reasons.length }">{{ item.n }}</span>
            <strong>{{ label(item.block) }}</strong>
            <small class="sr-id">{{ item.block.id }}</small>
            <PTag v-if="item.reasons.length" severity="warn" :value="`확인 필요 ${item.reasons.length}`" />
            <span class="sr-actions" @click.stop>
              <PButton icon="pi pi-arrow-up" severity="secondary" text size="small" :disabled="busy" :aria-label="`블록 ${item.n} 위로`" @click="emit('move', { id: item.block.id, dir: 'up' })" />
              <PButton icon="pi pi-arrow-down" severity="secondary" text size="small" :disabled="busy" :aria-label="`블록 ${item.n} 아래로`" @click="emit('move', { id: item.block.id, dir: 'down' })" />
              <PButton icon="pi pi-trash" severity="danger" text size="small" :disabled="busy" :aria-label="`블록 ${item.n} 삭제`" @click="emit('remove', item.block.id)" />
            </span>
          </header>

          <p v-if="item.reasons.length && selected !== item.block.id" class="sr-why">{{ item.reasons.map(r => r.label).join(' · ') }}</p>

          <!-- 내용 미리보기: 종류마다 PDF에서 읽은 그대로 보여 준다(숫자는 가려져 있어 ▒) -->
          <div class="sr-body">
            <p v-if="isText(item.block) && selected !== item.block.id" class="sr-text">{{ item.block.text }}</p>
            <div v-else-if="item.block.kind === 'table'" class="sr-scroll">
              <table class="sr-table">
                <thead><tr v-for="(line, li) in tableView(item.block).head" :key="li"><th v-for="(cell, ci) in line" :key="ci" :colspan="cell.colspan" :rowspan="cell.rowspan">{{ cell.text }}</th></tr></thead>
                <tbody><tr v-for="(row, ri) in tableView(item.block).body.slice(0, selected === item.block.id ? 50 : 4)" :key="ri"><td v-for="(cell, ci) in row" :key="ci">{{ cell }}</td></tr></tbody>
              </table>
              <small v-if="tableView(item.block).body.length > (selected === item.block.id ? 50 : 4)" class="sr-more">… 외 {{ tableView(item.block).body.length - (selected === item.block.id ? 50 : 4) }}행</small>
            </div>
            <ul v-else-if="item.block.kind === 'list'" class="sr-items">
              <li v-for="(row, ri) in listRows(item.block.items).slice(0, selected === item.block.id ? 80 : 5)" :key="ri" :style="{ paddingLeft: `${row.depth * 16}px` }"><span class="sr-marker">{{ row.marker }}</span>{{ row.text }}</li>
            </ul>
            <p v-else-if="item.block.kind === 'chart'" class="sr-text">차트({{ item.block.chart_type || '종류 미정' }}){{ item.block.title ? ` · ${item.block.title}` : '' }}{{ item.block.categories?.length ? ` · 항목 ${item.block.categories.length}개: ${item.block.categories.slice(0, 6).join(', ')}` : '' }}</p>
            <p v-else-if="item.block.kind === 'numbercard'" class="sr-text">숫자 카드 {{ item.block.cards?.length || 0 }}개: {{ (item.block.cards || []).map(c => c.label).join(', ') }}</p>
          </div>

          <!-- 고르면 펼쳐지는 편집 영역 -->
          <div v-if="selected === item.block.id" class="sr-detail" @click.stop>
            <div v-if="item.reasons.length" class="sr-reasons">
              <div v-for="(r, ri) in item.reasons" :key="ri" class="sr-reason" role="alert">
                <strong>{{ r.label }}</strong><span v-if="r.reason"> — {{ r.reason }}</span>
                <details v-if="r.help"><summary>이 표시는 무슨 뜻인가요?</summary>{{ r.help }}</details>
              </div>
            </div>
            <label :for="`sr-kind-${item.block.id}`">종류
              <PSelect :inputId="`sr-kind-${item.block.id}`" :modelValue="item.block.kind" :options="kindChoices(item.block)" optionLabel="label" optionValue="value" :disabled="busy" @update:modelValue="setKind(item.block, $event)" />
            </label>
            <small v-if="!isText(item.block)" class="sr-hint">표·목록·차트·숫자 카드는 문장·제목·각주로만 바꿀 수 있습니다(PDF에서 읽은 내용이 없는 표를 새로 만들 수는 없습니다).</small>
            <label v-if="isText(item.block)" :for="`sr-text-${item.block.id}`">본문
              <PTextarea :id="`sr-text-${item.block.id}`" v-model="item.block.text" rows="3" autoResize :disabled="busy" @update:modelValue="emit('edited')" />
            </label>
            <label v-else :for="`sr-title-${item.block.id}`">제목(다음 단계에서 이 이름으로 보입니다)
              <PInputText :id="`sr-title-${item.block.id}`" v-model="item.block.title" :disabled="busy" @update:modelValue="emit('edited')" />
            </label>
            <p v-if="tidyNotes(item.block).length" class="sr-tidy">코드 정리: {{ tidyNotes(item.block).join(' · ') }}</p>
            <label v-if="item.reasons.length" :for="`sr-ok-${item.block.id}`" class="sr-check">
              <PCheckbox :inputId="`sr-ok-${item.block.id}`" :modelValue="false" binary :disabled="busy" @update:modelValue="emit('acknowledge', item.block)" /><span>이 항목을 확인했습니다</span>
            </label>
          </div>
        </article>
      </div>
    </div>
  </div>
</template>

<style scoped>
.sr { display:grid; gap:12px; }
.sr-summary { margin:0; font-size:14px; }
.sr-key { background:rgba(255,214,0,.35); border-bottom:2px solid var(--p-yellow-500, #d69600); padding:0 4px; }
.sr-layout { display:grid; grid-template-columns:minmax(0, 1fr) minmax(0, 1fr); gap:16px; align-items:start; }
.sr-pages { display:grid; gap:12px; position:sticky; top:8px; max-height:calc(100vh - 24px); overflow:auto; }
.sr-page { margin:0; }
.sr-page figcaption { font-size:12px; margin-top:4px; opacity:.75; }
.sr-canvas { position:relative; line-height:0; border:1px solid var(--p-content-border-color); }
.sr-canvas img { width:100%; display:block; }
.sr-box { position:absolute; padding:0; cursor:pointer; border:2px solid var(--p-primary-color, #2f5bd8); background:rgba(47, 91, 216, .07); border-radius:2px; }
.sr-box.sr-review { border-color:var(--p-yellow-500, #d69600); background:rgba(255, 214, 0, .2); }
.sr-box.sr-selected { outline:3px solid var(--p-text-color, #16181d); outline-offset:1px; z-index:2; }
.sr-num { position:absolute; left:-2px; top:-2px; transform:translateY(-100%); background:var(--p-primary-color, #2f5bd8); color:#fff; font-size:11px; line-height:14px; padding:0 4px; border-radius:2px 2px 0 0; }
.sr-box.sr-review .sr-num { background:var(--p-yellow-600, #b87f00); }
.sr-list { display:grid; gap:10px; min-width:0; }
.sr-card { border:1px solid var(--p-content-border-color); border-left:4px solid var(--p-primary-color, #2f5bd8); border-radius:6px; padding:10px 12px; background:var(--p-content-background); cursor:pointer; min-width:0; }
.sr-card.sr-review { border-left-color:var(--p-yellow-500, #d69600); background:rgba(255, 214, 0, .07); }
.sr-card.sr-selected { box-shadow:0 0 0 2px var(--p-text-color, #16181d); }
.sr-head { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }
.sr-badge { display:inline-grid; place-items:center; min-width:22px; height:22px; border-radius:11px; padding:0 6px; background:var(--p-primary-color, #2f5bd8); color:#fff; font-size:12px; }
.sr-badge.sr-review { background:var(--p-yellow-600, #b87f00); }
.sr-id { opacity:.6; }
.sr-actions { margin-left:auto; display:flex; }
.sr-why { margin:6px 0 0; font-size:12px; color:var(--p-yellow-700, #8a5f00); }
.sr-body { margin-top:6px; min-width:0; }
.sr-text { margin:0; font-size:14px; white-space:pre-wrap; overflow-wrap:anywhere; display:-webkit-box; -webkit-line-clamp:3; line-clamp:3; -webkit-box-orient:vertical; overflow:hidden; }
.sr-card.sr-selected .sr-text { -webkit-line-clamp:unset; line-clamp:unset; }
.sr-scroll { overflow:auto; }
.sr-table { border-collapse:collapse; font-size:12px; }
.sr-table th, .sr-table td { border:1px solid var(--p-content-border-color); padding:2px 8px; text-align:center; white-space:nowrap; }
.sr-table th { background:rgba(128, 128, 128, .12); font-weight:600; }
.sr-more { opacity:.7; }
.sr-items { margin:0; padding:0; list-style:none; font-size:13px; }
.sr-marker { opacity:.6; margin-right:6px; }
.sr-detail { margin-top:10px; padding-top:10px; border-top:1px dashed var(--p-content-border-color); display:grid; gap:8px; cursor:default; }
.sr-detail label { display:grid; gap:6px; font-size:14px; }
.sr-detail .sr-check { display:flex; align-items:center; gap:8px; }
.sr-reasons { display:grid; gap:6px; }
.sr-reason { background:rgba(255, 214, 0, .16); border:1px solid var(--p-yellow-400, #e6c200); border-radius:6px; padding:8px 10px; font-size:13px; overflow-wrap:anywhere; }
.sr-reasons summary { cursor:pointer; font-size:12px; opacity:.85; margin-top:4px; }
.sr-hint, .sr-tidy { font-size:12px; opacity:.8; margin:0; }
@media (max-width:900px) { .sr-layout { grid-template-columns:1fr; } .sr-pages { position:static; max-height:none; } }
</style>
