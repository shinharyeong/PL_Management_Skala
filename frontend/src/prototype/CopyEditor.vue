<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import copy from './copy-text.js'
import initial from './ui-copy.json' with {type:'json'}
import {createSharedCopyClient} from '../lib/copy-overrides.js'
import {useSession} from '../stores/session.js'
import {refreshSharedCopy} from './copy-text.js'
import {saveBlob} from '../lib/display.js'
const session=useSession()
const sharedCopy=createSharedCopyClient(initial,(path,options)=>session.request(path,options))
async function downloadCopy(){try {saveBlob(new Blob([JSON.stringify(await sharedCopy.read(),null,2)+'\n'],{type:'application/json'}),'ui-copy.json')}catch(e){notice.value=e.message}}

const enabled = ref(true), dialog = ref(null), input = ref(null)
const key = ref(''), original = ref(''), draft = ref(''), busy = ref(false), error = ref(''), notice = ref(''), last = ref(null)
let trigger

// 편집 상자 위치·접힘: 화면 아래 버튼(로그아웃 등)을 가리지 않게 끌어서 옮긴다. 이 브라우저에만 기억한다.
const POSITION_KEY = 'copy-editor-bar'
const bar = ref(null), pos = ref(null), collapsed = ref(false)
const barStyle = computed(() => pos.value ? { left: pos.value.x + 'px', top: pos.value.y + 'px', bottom: 'auto' } : {})
let drag = null
function clamp(x, y) {
  const rect = bar.value?.getBoundingClientRect()
  const w = rect?.width || 0, h = rect?.height || 0
  return { x: Math.round(Math.min(Math.max(0, x), Math.max(0, window.innerWidth - w))), y: Math.round(Math.min(Math.max(0, y), Math.max(0, window.innerHeight - h))) }
}
function remember() {
  try { localStorage.setItem(POSITION_KEY, JSON.stringify({ pos: pos.value, collapsed: collapsed.value })) } catch { /* 저장 못 해도 이번 화면에서는 옮겨진 채로 쓴다 */ }
}
function startDrag(event) {
  if (event.button !== 0) return
  const rect = bar.value.getBoundingClientRect()
  drag = { dx: event.clientX - rect.left, dy: event.clientY - rect.top }
  event.currentTarget.setPointerCapture(event.pointerId)
  event.preventDefault()
}
function moveDrag(event) {
  if (!drag) return
  pos.value = clamp(event.clientX - drag.dx, event.clientY - drag.dy)
}
function endDrag() {
  if (!drag) return
  drag = null; remember()
}
function nudge(event) {
  const step = event.shiftKey ? 40 : 10
  const delta = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] }[event.key]
  if (!delta) return
  event.preventDefault()
  const rect = bar.value.getBoundingClientRect()
  pos.value = clamp(rect.left + delta[0], rect.top + delta[1]); remember()
}
function resetPosition() { pos.value = null; remember() }
async function toggleCollapsed() {
  collapsed.value = !collapsed.value
  await nextTick()
  if (pos.value) pos.value = clamp(pos.value.x, pos.value.y)
  remember()
}
function keepInside() { if (pos.value) pos.value = clamp(pos.value.x, pos.value.y) }
async function request(body) {
  if(body && !session.actor) throw new Error('개발 계정을 선택해 로그인한 뒤 문구를 저장해 주세요.')
  const result=body ? await sharedCopy.change(body) : await sharedCopy.read()
  if(body) await refreshSharedCopy().catch(()=>{})
  return result
}

function apply(path, value) {
  const [group, field] = path.split('.')
  if (copy[group]) copy[group][field] = value
}
function toggle() {
  enabled.value = !enabled.value
  document.documentElement.classList.toggle('copy-editing', enabled.value)
  notice.value = enabled.value ? '제목·버튼·안내문을 우클릭해 수정하세요.' : '문구 편집을 껐습니다.'
}
async function open(event) {
  if (!enabled.value || busy.value || dialog.value?.open) return
  const target = event.target instanceof Element ? event.target.closest('[data-copy-key]') : null
  const path = target?.dataset.copyKey
  if (!path || !Object.hasOwn(copy, path.split('.')[0]) || typeof copy[path.split('.')[0]]?.[path.split('.')[1]] !== 'string') return
  event.preventDefault(); event.stopPropagation()
  trigger = target; error.value = ''; notice.value = ''; key.value = path; busy.value = true
  try {
    const latest = await request()
    const [group, field] = path.split('.')
    if (typeof latest[group]?.[field] !== 'string') throw new Error('편집할 수 없는 문구입니다.')
    original.value = latest[group][field]; draft.value = original.value
    dialog.value.showModal()
    await nextTick(); input.value.focus(); input.value.select()
  } catch (e) { notice.value = e.message } finally { busy.value = false }
}
function close() {
  if (busy.value) return
  dialog.value.close(); trigger?.focus?.({ preventScroll: true })
}
async function save() {
  if (busy.value || !draft.value.trim() || draft.value === original.value) return
  busy.value = true; error.value = ''
  const change = { key: key.value, expected: original.value, value: draft.value }
  try {
    await request(change); apply(change.key, change.value)
    last.value = change; notice.value = '공통 문구를 저장했습니다. 모든 사용자 화면에 적용됩니다.'
    dialog.value.close()
  } catch (e) { error.value = e.message } finally { busy.value = false }
}
async function undo() {
  if (!last.value || busy.value) return
  busy.value = true
  try {
    const change = last.value
    await request({ key: change.key, expected: change.value, value: change.expected })
    apply(change.key, change.expected); last.value = null; notice.value = '직전 문구 수정을 되돌렸습니다.'
  } catch (e) { notice.value = e.message } finally { busy.value = false }
}
onMounted(async () => {
  document.addEventListener('contextmenu', open, true); document.documentElement.classList.add('copy-editing')
  window.addEventListener('resize', keepInside)
  try {
    const saved = JSON.parse(localStorage.getItem(POSITION_KEY) || 'null')
    collapsed.value = saved?.collapsed === true
    if (Number.isFinite(saved?.pos?.x) && Number.isFinite(saved?.pos?.y)) { await nextTick(); pos.value = clamp(saved.pos.x, saved.pos.y) }
  } catch { /* 저장된 위치가 없거나 읽을 수 없으면 기본 위치 */ }
})
onBeforeUnmount(() => { document.removeEventListener('contextmenu', open, true); document.documentElement.classList.remove('copy-editing'); window.removeEventListener('resize', keepInside) })
</script>

<template>
  <Teleport to="body">
    <aside ref="bar" class="copy-editor-bar" :class="{ collapsed }" :style="barStyle" aria-label="개발용 문구 편집">
      <div><button type="button" class="copy-editor-grip" title="끌어서 옮기기 · 방향키로 이동 · 두 번 눌러 처음 위치" aria-label="문구 편집 상자 옮기기(방향키로 이동, 두 번 눌러 처음 위치로)" @pointerdown="startDrag" @pointermove="moveDrag" @pointerup="endDrag" @pointercancel="endDrag" @keydown="nudge" @dblclick="resetPosition">⠿</button><button type="button" :aria-expanded="!collapsed" @click="toggleCollapsed">{{ collapsed ? '문구 편집 펼치기' : '접기' }}</button><template v-if="!collapsed"><button type="button" @click="downloadCopy">문구 JSON 내려받기</button><button type="button" :aria-pressed="enabled" @click="toggle">문구 우클릭 편집 · {{ enabled ? '켜짐' : '꺼짐' }}</button><button v-if="last" type="button" :disabled="busy" @click="undo">직전 수정 되돌리기</button></template></div>
      <p v-show="!collapsed" role="status">{{ notice || '제목·버튼·안내문을 우클릭하세요. 숫자와 업무 데이터는 편집되지 않습니다.' }}</p>
    </aside>
    <dialog ref="dialog" class="copy-editor-dialog" aria-labelledby="copy-editor-title" @cancel.prevent="close">
      <form @submit.prevent="save">
        <h2 id="copy-editor-title">문구 수정</h2>
        <p class="copy-editor-caption">같은 문구 항목을 사용하는 위치에 함께 반영됩니다.</p>
        <p class="copy-editor-key">{{ key }}</p>
        <label for="screen-copy-text">화면에 표시할 문구</label>
        <textarea id="screen-copy-text" ref="input" v-model="draft" rows="4" maxlength="10000" :disabled="busy" @keydown.ctrl.enter.prevent="save" @keydown.meta.enter.prevent="save"></textarea>
        <p class="copy-editor-error" role="alert">{{ error }}</p>
        <p class="copy-editor-caption">저장하면 서버 DB에 보관되어 일반 화면을 포함한 모든 사용자 화면에 적용됩니다. 열려 있는 화면은 약 5초 안에 갱신됩니다.</p>
        <div class="copy-editor-actions"><button type="button" :disabled="busy" @click="close"><span data-copy-key="login.cancel">{{ copy.login.cancel }}</span></button><button type="submit" class="copy-editor-save" :disabled="busy || !draft.trim() || draft===original">{{ busy ? '저장 중…' : '저장' }}</button></div>
      </form>
    </dialog>
  </Teleport>
</template>

<style>
.copy-editing [data-copy-key]:hover{outline:1px dashed #8272c7;outline-offset:3px;background:#f0ecff66;cursor:context-menu}
.copy-editor-bar{position:fixed;bottom:10px;left:10px;z-index:1100;max-width:min(480px,calc(100vw - 20px));padding:9px 12px;border:1px solid #b8b0d6;border-radius:12px;background:#faf8ff;color:#40345c;box-shadow:0 3px 18px #26203726;font:12px/1.5 system-ui}
.copy-editor-bar>div{display:flex;gap:8px;flex-wrap:wrap}.copy-editor-bar p{margin:5px 0 0}.copy-editor-bar .copy-editor-grip{cursor:grab;touch-action:none;padding:5px 7px;font-size:14px;line-height:1}.copy-editor-bar .copy-editor-grip:active{cursor:grabbing}.copy-editor-bar.collapsed{padding:6px 8px}.copy-editor-bar button{font:inherit;border:1px solid #c8bedf;padding:5px 9px;background:white;color:#493763;border-radius:7px;cursor:pointer}
.copy-editor-dialog{width:min(530px,calc(100vw - 32px));max-height:85dvh;overflow:auto;padding:24px;border:1px solid #cbc4dd;border-radius:18px;background:#fff;color:#27354b;box-shadow:0 16px 60px #1a173b40;font:14px/1.6 system-ui}
.copy-editor-dialog::backdrop{background:#1d233d55}.copy-editor-dialog h2{font-size:21px;margin:0 0 8px}.copy-editor-caption{font-size:13px;color:#657080;margin:8px 0}.copy-editor-key{font-size:12px;color:#766b8b;overflow-wrap:anywhere}.copy-editor-dialog label{display:block;margin:15px 0 6px;font-weight:600}.copy-editor-dialog textarea{display:block;box-sizing:border-box;width:100%;font:inherit;line-height:1.7;padding:12px;border:1px solid #9b8ec0;border-radius:9px;resize:vertical;min-height:110px}.copy-editor-dialog textarea:focus{outline:2px solid #8c7bc7;outline-offset:2px}.copy-editor-error{color:#aa302b;min-height:20px;margin:8px 0}.copy-editor-actions{display:flex;justify-content:flex-end;gap:9px;margin-top:18px}.copy-editor-actions button{padding:9px 20px;border:1px solid #bfc6d0;border-radius:9px;background:#fff;color:#304056;font:inherit;cursor:pointer}.copy-editor-actions .copy-editor-save{background:#64529a;border-color:#64529a;color:white}.copy-editor-dialog button:disabled{opacity:.5;cursor:default}
</style>
