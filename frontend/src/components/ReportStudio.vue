<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import ReportDataTable from './ReportDataTable.vue'
import ReportChart from './ReportChart.vue'
import { reportCell, reportRows } from '../prototype/report-api.js'
import { highlightParts, isTextual, layoutWidth, reorder, styleCss, withStyle } from '../lib/report-studio.js'

/** 보고서 만들기 5단계(생성·편집): 서식 바 · 문서 캔버스(끌어서 순서 바꾸기, 모서리로 크기, 나란히 배치, 더블클릭으로 글자 고치기) · AI 대화.
 * 서버 호출은 하지 않는다. 데이터 채우기·AI 편집·저장·내보내기는 이벤트로 부모(LiveReportPanel)에 맡긴다.
 * Props: definition, results(서버가 채운 값, 없으면 null), base, busy, canUndo, editResult(AI 편집안), saved, dirty.
 * Emits: back, fill, undo, png, print, save, xlsx, reorder(blocks), remove(id), add({kind, metric}), edit(문장), accept, dismiss. */
const props = defineProps({
  definition: { type: Object, required: true },
  results: { type: Object, default: null },
  base: { type: Object, required: true },
  busy: { type: Boolean, default: false },
  canUndo: { type: Boolean, default: false },
  editResult: { type: Object, default: null },
  preview: { type: Array, default: () => [] },
  saved: { type: Object, default: null },
  dirty: { type: Boolean, default: false },
})
const emit = defineEmits(['back', 'fill', 'undo', 'png', 'print', 'save', 'xlsx', 'reorder', 'remove', 'add', 'edit', 'accept', 'dismiss', 'edited'])

const selected = ref(null), editing = ref(null), highlight = ref(true)
const dragId = ref(null), dropMark = ref(null), docEl = ref(null), message = ref('')
const addKind = ref('metric_table'), addMetric = ref('영업이익')
const kinds = [{ label: '손익 표', value: 'metric_table' }, { label: '조직별 차트', value: 'chart' }, { label: '숫자 카드', value: 'numbercard' }, { label: '문장', value: 'paragraph' }]
const hints = ['영업이익 차트를 추가해 줘', '맨 앞에 제목 문장을 넣어 줘']
const current = computed(() => props.definition.blocks.find(block => block.id === selected.value) || null)
const style = computed(() => current.value?.style || {})
const statusText = computed(() => ({ CONFIRMED: '확정 자료', PROVISIONAL: '잠정 자료', NONE: '조회할 데이터 없음' }[props.results?.dataStatus] || props.results?.dataStatus || ''))

const dataOf = block => props.results?.blocks?.find(item => item.id === block.id) || null
const titleOf = block => (block.kind === 'title' ? (dataOf(block)?.text ?? block.text ?? '') : (block.title || ''))
const textOf = block => { const data = dataOf(block); return data?.text ?? block.text ?? '' }
const widthCss = block => (layoutWidth(block) >= 100 ? { width: 'calc(100% - 8px)' } : { width: `calc(${layoutWidth(block)}% - 8px)` })
const boxCss = block => ({ ...widthCss(block), ...styleCss(block) })

/** 서식 한 항목을 고른 블록에 반영한다(서식은 블록 정의에 저장되어 틀과 함께 저장된다). */
function setStyle(patch) { if (current.value) current.value.style = withStyle(current.value, patch) }
const toggle = key => setStyle({ [key]: !style.value[key] })

function pick(block) { selected.value = block.id }
async function startEdit(block) {
  if (!isTextual(block) || props.busy) return
  editing.value = block.id; selected.value = block.id
  await nextTick(); docEl.value?.querySelector('.rs-ed')?.focus()
}

/* 끌어서 순서 바꾸기: 앞/뒤 표시는 마우스 위치(나란히 배치된 블록은 가로, 아니면 세로)로 정한다. */
function dragStart(event, block) { dragId.value = block.id; event.dataTransfer?.setData('text/plain', block.id); if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move' }
function dragOver(event, block) {
  if (!dragId.value || dragId.value === block.id) return
  const rect = event.currentTarget.getBoundingClientRect()
  const side = layoutWidth(block) < 100
  const after = side ? event.clientX > rect.left + rect.width / 2 : event.clientY > rect.top + rect.height / 2
  dropMark.value = { id: block.id, after }
}
function drop() {
  const mark = dropMark.value, id = dragId.value
  dragId.value = null; dropMark.value = null
  if (id && mark) emit('reorder', reorder(props.definition.blocks, id, mark.id, mark.after))
}
const markClass = block => (dropMark.value?.id === block.id ? (dropMark.value.after ? 'drop-below' : 'drop-above') : '')

/* 모서리를 끌어 너비(%)를 바꾼다. 5% 단위로 맞추어 나란히 놓기 쉽게 한다. */
function resizeStart(event, block) {
  const doc = docEl.value; if (!doc) return
  const rect = doc.getBoundingClientRect(), full = Math.max(1, rect.width - 56), startX = event.clientX, startW = layoutWidth(block)
  const target = event.currentTarget
  target.setPointerCapture?.(event.pointerId)
  const move = e => { block.layout = { ...(block.layout || {}), w: Math.max(15, Math.min(100, Math.round((startW + ((e.clientX - startX) / full) * 100) / 5) * 5)) } }
  const up = () => { target.removeEventListener('pointermove', move); target.removeEventListener('pointerup', up); target.removeEventListener('pointercancel', up) }
  target.addEventListener('pointermove', move); target.addEventListener('pointerup', up); target.addEventListener('pointercancel', up)
}

/* 대화 기록: 내가 보낸 말과 AI 답(글·표·차트 미리보기·적용 버튼)을 순서대로 쌓는다. */
const thread = ref([]), threadBox = ref(null)
const lastPending = computed(() => { const last = [...thread.value].reverse().find(entry => entry.role === 'ai'); return last?.pending ? last : null })
watch(() => props.editResult, value => {
  if (!value) return
  thread.value.push({ role: 'ai', status: value.status, message: value.message, table: value.table || null, preview: props.preview.map(p => ({ block: p.block, result: p.result })), pending: value.status === 'EDIT' && !!value.proposals?.length, proposals: value.proposals || [] })
})
watch(() => thread.value.length, async () => { await nextTick(); if (threadBox.value) threadBox.value.scrollTop = threadBox.value.scrollHeight })
function applyPending() { const entry = lastPending.value; if (entry) { entry.pending = false; entry.applied = true } emit('accept') }
function cancelPending() { const entry = lastPending.value; if (entry) { entry.pending = false; entry.cancelled = true } emit('dismiss') }
/* 블록이 새로 생기면(AI 편집안 적용) 그 블록을 고르고 화면에 보이게 한다. */
watch(() => props.definition.blocks.map(block => block.id), async (now, before) => {
  const added = now.find(id => !before.includes(id))
  if (!added || now.length <= before.length) return
  selected.value = added
  await nextTick()
  docEl.value?.querySelector(`[data-bid="${added}"]`)?.scrollIntoView({ block: 'center', behavior: 'smooth' })
})
function send(text) {
  const value = String(text ?? message.value).trim()
  if (!value || props.busy) return
  thread.value.push({ role: 'me', text: value }); message.value = ''
  emit('edit', value)
}
</script>

<template>
  <div>
    <div class="rs-bar">
      <div class="rs-brand"><b>{{ definition.name }}</b><span>{{ base.year }}년 {{ base.month }}월<template v-if="statusText"> · {{ statusText }}</template></span></div>
      <div class="rs-bar-acts">
        <button type="button" class="rs-btn ghost" :disabled="busy" @click="emit('back')">← 소명 분류</button>
        <button v-if="canUndo" type="button" class="rs-btn ghost" :disabled="busy" @click="emit('undo')">되돌리기</button>
        <button type="button" class="rs-btn" :disabled="busy || !definition.blocks.length" @click="emit('fill')">현재 데이터로 채우기</button>
        <button type="button" class="rs-btn ghost" :disabled="busy || !results" @click="emit('png')">전체 PNG</button>
        <button type="button" class="rs-btn ghost" :disabled="busy || !results" @click="emit('print')">인쇄 · PDF</button>
        <button type="button" class="rs-btn ghost" :disabled="busy || !dirty" @click="emit('save')">{{ saved ? '새 버전 저장' : '틀 저장' }}</button>
        <button type="button" class="rs-btn ghost" :disabled="busy || !saved || dirty" @click="emit('xlsx')">XLSX</button>
      </div>
    </div>

    <div class="rs-fbar" role="toolbar" aria-label="서식">
      <span class="rs-cap">{{ current ? `선택: ${current.title || current.kind}` : '블록을 눌러 서식을 바꾸세요' }}</span>
      <select aria-label="글꼴" :disabled="!current || busy" :value="style.family || 'gothic'" @change="setStyle({ family: $event.target.value === 'serif' ? 'serif' : '' })"><option value="gothic">고딕</option><option value="serif">명조</option></select>
      <input type="number" aria-label="글자 크기(pt)" min="8" max="40" step="1" style="width:64px;height:34px;border:1px solid var(--rs-line);border-radius:8px;padding:0 6px" :disabled="!current || busy" :value="style.size || ''" placeholder="크기" @change="setStyle({ size: $event.target.value === '' ? '' : Math.max(8, Math.min(40, Math.round(Number($event.target.value)))) })" />
      <input type="color" aria-label="글자 색" :disabled="!current || busy" :value="style.color || '#191918'" @input="setStyle({ color: $event.target.value })" />
      <button type="button" class="tb" :class="{ on: style.bold }" :disabled="!current || busy" aria-label="굵게" @click="toggle('bold')">B</button>
      <button type="button" class="tb" :class="{ on: style.italic }" :disabled="!current || busy" aria-label="기울임" style="font-style:italic" @click="toggle('italic')">I</button>
      <button type="button" class="tb" :class="{ on: style.underline }" :disabled="!current || busy" aria-label="밑줄" style="text-decoration:underline" @click="toggle('underline')">U</button>
      <button type="button" class="tb" :class="{ on: style.strike }" :disabled="!current || busy" aria-label="취소선" style="text-decoration:line-through" @click="toggle('strike')">S</button>
      <button v-for="a in [['left', '≡'], ['center', '☰'], ['right', '≣']]" :key="a[0]" type="button" class="tb" :class="{ on: style.align === a[0] }" :disabled="!current || busy" :aria-label="`${{ left: '왼쪽', center: '가운데', right: '오른쪽' }[a[0]]} 정렬`" @click="setStyle({ align: style.align === a[0] ? '' : a[0] })">{{ a[1] }}</button>
      <button type="button" class="rs-btn ghost" style="height:34px" :disabled="!current || busy" @click="current.style = undefined; delete current.style">서식 지우기</button>
    </div>

    <div class="rs-rep">
      <div>
        <div class="rs-hl-legend"><span><span class="sw" style="background:rgba(86,116,79,.25)"></span>파랑 = 데이터에서 가져온 값</span><span><span class="sw" style="background:var(--rs-warn-chip)"></span>노랑 = 아직 채우지 못한 값</span>
          <label><input v-model="highlight" type="checkbox" /> 값 표시</label>
          <span>· 블록을 끌어 순서 바꾸기, 모서리를 끌어 크기 조절, 글자 블록은 더블클릭으로 직접 고치기</span></div>
        <div ref="docEl" class="rs-doc report-preview" :class="{ 'hl-off': !highlight }">
          <div v-if="!definition.blocks.length" class="rs-empty5" style="width:100%">블록이 없습니다. 아래에서 항목을 추가하세요.</div>
          <article v-for="block in definition.blocks" :key="block.id" :data-bid="block.id" class="rs-rb" :class="[{ sel: selected === block.id, dragging: dragId === block.id, side: layoutWidth(block) < 100 }, markClass(block)]" :style="boxCss(block)"
            @click.stop="pick(block)" @dblclick.stop="startEdit(block)" @dragover.prevent="dragOver($event, block)" @drop.prevent="drop" @dragend="dragId = null; dropMark = null">
            <span class="hd" draggable="true" title="끌어서 순서 바꾸기" aria-hidden="true" @dragstart="dragStart($event, block)">⋮⋮</span>
            <span class="bt"><button type="button" :disabled="busy" :aria-label="`${block.id} 빼기`" title="빼기" @click.stop="emit('remove', block.id)">✕</button></span>
            <span class="rz" role="separator" title="끌어서 너비 조절" @pointerdown.stop.prevent="resizeStart($event, block)"></span>

            <textarea v-if="editing === block.id" v-model="block.text" class="rs-ed" rows="3" @input="emit('edited')" @blur="editing = null" @keydown.esc="editing = null"></textarea>
            <template v-else>
              <h3 v-if="block.kind === 'title'"><template v-for="(part, i) in highlightParts(titleOf(block))" :key="i"><span v-if="part.kind !== 'plain' && results" :class="part.kind === 'val' ? 'rs-dv' : 'rs-mv'">{{ part.text }}</span><template v-else>{{ part.text }}</template></template></h3>
              <p v-else-if="isTextual(block)"><template v-for="(part, i) in highlightParts(textOf(block))" :key="i"><span v-if="part.kind !== 'plain' && results" :class="part.kind === 'val' ? 'rs-dv' : 'rs-mv'">{{ part.text }}</span><template v-else>{{ part.text }}</template></template><span v-if="!textOf(block)" class="rs-cap">(빈 문장 — 더블클릭해서 입력)</span></p>
              <template v-else>
                <h3 v-if="titleOf(block)">{{ titleOf(block) }}</h3>
                <template v-if="dataOf(block)">
                  <div v-if="dataOf(block).missing" class="rs-mv" style="display:inline-block;margin-bottom:4px">일부 데이터가 없습니다. {{ (dataOf(block).why || dataOf(block).reasons || []).join(' · ') }}</div>
                  <ReportDataTable v-if="reportRows(dataOf(block)).length" :data="dataOf(block)" />
                  <div v-if="dataOf(block).cards" class="rs-ncards"><div v-for="(card, i) in dataOf(block).cards" :key="i" class="rs-ncard"><div class="rs-cap">{{ card.label }}</div><div class="nv" :class="{ miss: card.value == null }">{{ card.value ?? '데이터 없음' }}<small v-if="card.value != null"> {{ card.unit }}</small></div><small class="rs-cap">{{ card.delta }}</small></div></div>
                  <ReportChart v-if="block.kind === 'chart' && Array.isArray(dataOf(block).rows)" :block="{ ...block, ...dataOf(block) }" />
                  <ul v-if="dataOf(block).items"><li v-for="(item, i) in dataOf(block).items" :key="i">{{ reportCell(item) }}</li></ul>
                </template>
                <div v-else class="rs-empty5">{{ block.kind === 'chart' ? '차트' : block.kind === 'numbercard' ? '숫자 카드' : '표' }} · “현재 데이터로 채우기”를 누르면 값이 들어옵니다.</div>
              </template>
            </template>
          </article>
        </div>
        <div class="rs-addmenu"><label for="rs-add-kind">항목 추가</label>
          <select id="rs-add-kind" v-model="addKind" :disabled="busy"><option v-for="k in kinds" :key="k.value" :value="k.value">{{ k.label }}</option></select>
          <select v-if="addKind !== 'paragraph'" v-model="addMetric" aria-label="지표" :disabled="busy"><option>매출</option><option>영업비용</option><option>영업이익</option></select>
          <button type="button" class="rs-btn ghost" style="height:32px" :disabled="busy" @click="emit('add', { kind: addKind, metric: addMetric })">+ 추가</button></div>
      </div>

      <aside class="rs-panel rs-ai" aria-label="AI 편집 대화">
        <b>AI에게 요청</b>
        <div class="rs-chips"><button v-for="h in hints" :key="h" type="button" :disabled="busy" @click="send(h)">{{ h }}</button></div>
        <div ref="threadBox" class="rs-aimsgs" aria-live="polite">
          <div v-if="!thread.length" class="rs-cap">데이터를 묻거나(예: “10월 영업비용이 가장 큰 항목 5개”) 차트를 추가해 달라고(예: “조직별 영업이익 막대그래프 추가”) 해 보세요.</div>
          <template v-for="(entry, i) in thread" :key="i">
            <div v-if="entry.role === 'me'" class="rs-aibox me">{{ entry.text }}</div>
            <div v-else class="rs-aibox">
              <div>{{ entry.message }}</div>
              <div v-if="entry.table?.rows?.length" class="rs-scroll" style="margin-top:8px"><table class="rs-mini">
                <thead><tr><th v-for="(h, hi) in entry.table.headers" :key="hi">{{ h?.text ?? h }}</th></tr></thead>
                <tbody><tr v-for="(row, ri) in entry.table.rows" :key="ri"><td v-for="(cell, ci) in row" :key="ci">{{ cell?.text ?? cell }}</td></tr></tbody></table></div>
              <div v-for="p in entry.preview" :key="p.block.id" style="margin-top:8px">
                <ReportChart v-if="p.block.kind === 'chart' && Array.isArray(p.result?.rows)" :block="{ ...p.block, ...p.result }" />
                <ReportDataTable v-else-if="p.result && reportRows(p.result).length" :data="p.result" />
              </div>
              <ul v-if="entry.proposals?.length" style="margin:6px 0 0;padding-left:18px"><li v-for="(p, pi) in entry.proposals" :key="pi">{{ p.description }}</li></ul>
              <div v-if="entry.applied" class="rs-cap" style="margin-top:6px">보고서에 적용했습니다. 왼쪽 문서에서 확인하세요(“되돌리기”로 취소).</div>
              <div v-else-if="entry.cancelled" class="rs-cap" style="margin-top:6px">적용하지 않았습니다.</div>
              <div v-if="entry.pending && entry === lastPending" class="rs-acts" style="margin-top:8px">
                <button type="button" class="rs-btn" style="height:32px" :disabled="busy" @click="applyPending">보고서에 적용</button>
                <button type="button" class="rs-btn ghost" style="height:32px" :disabled="busy" @click="cancelPending">취소</button>
              </div>
            </div>
          </template>
        </div>
        <form class="rs-aiin" @submit.prevent="send()"><input v-model="message" maxlength="1000" placeholder="구성을 바꿀 요청을 입력" aria-label="AI 편집 요청" :disabled="busy" /><button type="submit" aria-label="보내기" :disabled="busy || !message.trim()">↑</button></form>
      </aside>
    </div>
  </div>
</template>
