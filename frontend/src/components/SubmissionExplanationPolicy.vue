<script setup>
import { computed } from 'vue'
const props = defineProps({ submission: { type: Object, required: true } })

/** 제출본의 저장된 검증 정책만 업무 규칙으로 표시한다.
 * Args: 없음. Returns: 정책 설명. Raises: 없음. Side Effects: 없음.
 * 현재 전역 설정을 과거 제출에 대입하지 않으며 미검증·기존 정책 미기록 상태를 구분한다.
 */
const rule = computed(function savedExplanationRule() {
  const context = props.submission.comparisonContext
  if (!context?.reviewPolicyVersion) return { title: '검증 후 소명 범위 확인', text: '업무 검증을 실행하면 적용 정책과 필수 소명 항목이 정해집니다.' }
  if (context.requirementMode === 'ALL_CHANGES') return { title: '검증 당시 정책 · 변경 항목 전체', text: '비교 기준과 달라진 항목, Gap 초과 항목, 경영기획팀의 별도 요청 항목을 소명합니다. 비교본이 없는 최초 값은 변경으로 간주하지 않습니다.' }
  if (context.requirementMode === 'GAP_ONLY') return { title: '검증 당시 정책 · Gap 초과 항목 중심', text: 'Gap 기준을 초과한 항목과 경영기획팀의 별도 요청 항목을 소명합니다.' }
  return { title: '검증 당시 소명 기준 보존', text: '이전 정책으로 검증된 제출본입니다. 저장된 필수 소명 표시를 따릅니다.' }
})
</script>

<template><aside class="submission-policy"><strong>{{ rule.title }}</strong><p>{{ rule.text }}</p><p v-if="submission.readiness?.validationState === 'STALE'" class="stale-policy">검증 기준이 변경되었습니다. 업무 검증을 다시 실행해 현재 정책과 필수 항목을 확인해 주세요.</p><small v-else-if="submission.comparisonContext?.reviewPolicyVersion">제외된 항목은 필수 소명에서 빠집니다. 최종 제출 전 필수 답변을 모두 저장하고, 제출 승인 전 필수 소명을 모두 수용해야 합니다.</small></aside></template>

<style scoped>.submission-policy{padding:14px 16px;border:1px solid var(--line);border-radius:8px;background:#faf9f6;margin:12px 0;font-size:12px;line-height:1.7}.submission-policy p{margin-top:5px;color:var(--muted)}.submission-policy small{display:block;margin-top:6px;color:var(--muted)}.submission-policy .stale-policy{color:#9a562e}</style>
