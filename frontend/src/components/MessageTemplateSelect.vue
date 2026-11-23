<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useSession } from '../stores/session'
import { errorText } from '../lib/display'
const props = defineProps({ type: { type: String, required: true }, disabled: Boolean })
const emit = defineEmits(['select'])
const session = useSession()
const items = ref([])
const selected = ref('')
const error = ref('')
const controller = new AbortController()
/** 현재 요청에 사용할 활성 메시지만 반환한다. Args: 없음. Returns: 템플릿 목록. Raises: 없음. Side Effects: 없음. */
const choices = computed(function applicableTemplates() { return items.value.filter(item => item.active && item.type === props.type) })
/** 저장된 요청 메시지 목록을 조회한다. Args: 없음. Returns: Promise<void>. Raises: 없음(화면 표시). Side Effects: GET. */
async function load() { try { const result = await session.request('/api/v1/settings/message-templates', { signal: controller.signal }); items.value = result.items } catch (cause) { if (cause.name !== 'AbortError') error.value = errorText(cause) } }
/** 선택한 템플릿을 현재 메시지 입력에 복사한다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: select 이벤트; 저장은 부모의 명시 동작에서 수행. */
function apply() { const item = choices.value.find(value => value.id === selected.value); if (item) emit('select', item.message) }
/** 화면 이탈 시 조회를 취소한다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: HTTP 취소. */
function dispose() { controller.abort() }
onMounted(load)
onBeforeUnmount(dispose)
</script>
<template><div class="message-templates"><label>저장된 메시지<select v-model="selected" :disabled="disabled || !choices.length"><option value="">{{ choices.length ? '템플릿 선택' : '등록된 템플릿 없음' }}</option><option v-for="item in choices" :key="item.id" :value="item.id">{{ item.name }}</option></select></label><button type="button" class="button" :disabled="disabled || !selected" @click="apply">메시지에 적용</button><RouterLink to="/planning/settings" class="text-button">관리</RouterLink><p v-if="error" class="alert error" role="alert">{{ error }}</p></div></template>
<style scoped>.message-templates{display:flex;align-items:end;gap:10px;flex-wrap:wrap}.message-templates label{min-width:190px}.message-templates label,.message-templates select{margin:0}.message-templates .text-button{padding-bottom:12px}.message-templates .alert{width:100%}</style>
