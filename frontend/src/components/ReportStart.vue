<script setup>
import { computed, ref } from 'vue'

/** 보고서 만들기 1단계(시작): PDF 올리기·직접 만들기·저장한 틀로 시작. 서버 호출은 하지 않고 이벤트로 알린다.
 * Props: templates 저장한 틀 목록, hasMore 다음 쪽 유무, job PDF 분석 작업, busy 처리 중, name 직접 만들기 이름(v-model).
 * Emits: pick(File), refresh, manual, load(id), more, update:name. */
const props = defineProps({
  templates: { type: Array, default: () => [] },
  hasMore: { type: Boolean, default: false },
  job: { type: Object, default: null },
  busy: { type: Boolean, default: false },
  name: { type: String, default: '' },
})
const emit = defineEmits(['pick', 'refresh', 'manual', 'load', 'more', 'update:name'])
const over = ref(false), query = ref(''), chosen = ref(null), fileInput = ref(null)
const shown = computed(() => props.templates.filter(item => !query.value.trim() || String(item.name).includes(query.value.trim())))
const jobLabel = computed(() => ({ RUNNING: '분석 중', SUCCEEDED: '완료', FAILED: '실패' }[props.job?.status] || props.job?.status || ''))
const jobClass = computed(() => ({ RUNNING: 'rs-tagrun', SUCCEEDED: 'rs-tagok', FAILED: 'rs-tagbad' }[props.job?.status] || 'rs-tagrun'))

/** 고른 파일을 PDF 여부만 확인하고 올린다(30MB 한도는 요청 단계에서 한 번 더 검사한다). */
function take(file) { if (file && !props.busy) emit('pick', file) }
function onDrop(event) { over.value = false; take(event.dataTransfer?.files?.[0]) }
function onFile(event) { take(event.target.files?.[0]); event.target.value = '' }
</script>

<template>
  <div class="rs-start">
    <section class="rs-card">
      <div><div class="rs-eyebrow">방법 ①</div><h2>PDF에서 틀 만들기</h2><p>숫자를 가린 PDF를 올리면 제목·표·차트·문장을 찾아 블록으로 나눕니다. 최대 30MB입니다.</p></div>
      <div class="rs-drop" :class="{ over }" role="button" tabindex="0" aria-label="분석할 PDF 올리기"
        @click="fileInput?.click()" @keydown.enter.prevent="fileInput?.click()" @keydown.space.prevent="fileInput?.click()"
        @dragover.prevent="over = true" @dragleave="over = false" @drop.prevent="onDrop">
        <b>PDF를 여기에 놓거나 눌러서 고르기</b><small>.pdf · 30MB 이하</small>
        <input ref="fileInput" type="file" accept=".pdf,application/pdf" hidden :disabled="busy" @change="onFile" />
      </div>
      <div v-if="job" class="rs-row"><span>분석 상태</span><span :class="jobClass">{{ jobLabel }}</span>
        <button type="button" class="rs-btn ghost" :disabled="busy" @click="emit('refresh')">상태 새로고침</button></div>
    </section>

    <section class="rs-card">
      <div><div class="rs-eyebrow">방법 ②</div><h2>내 틀로 시작</h2><p>저장해 둔 틀을 불러와 이어서 만듭니다.</p></div>
      <input v-model="query" class="rs-input" type="search" placeholder="틀 이름 검색" aria-label="틀 이름 검색" />
      <button v-for="item in shown" :key="item.id" type="button" class="rs-saved" :class="{ on: chosen === item.id }" @click="chosen = item.id">
        <b>{{ item.name }}</b><div class="m">{{ item.latestVersion }}버전</div>
      </button>
      <p v-if="!shown.length" class="rs-cap">{{ templates.length ? '검색 결과가 없습니다.' : '저장된 자료 틀이 없습니다.' }}</p>
      <button v-if="hasMore" type="button" class="rs-btn ghost" :disabled="busy" @click="emit('more')">더 보기</button>
      <button type="button" class="rs-btn" :disabled="busy || !chosen" @click="emit('load', chosen)">이 틀로 시작</button>
      <hr style="border:0;border-top:1px solid var(--rs-line2);width:100%;margin:2px 0" />
      <div><div class="rs-eyebrow">방법 ③</div><p>빈 보고서에서 직접 만듭니다.</p></div>
      <div class="rs-row">
        <input :value="name" class="rs-input" style="flex:1;min-width:140px" maxlength="120" aria-label="자료 틀 이름" @input="emit('update:name', $event.target.value)" />
        <button type="button" class="rs-btn ghost" :disabled="busy || !name.trim()" @click="emit('manual')">직접 만들기</button>
      </div>
    </section>
  </div>
</template>
