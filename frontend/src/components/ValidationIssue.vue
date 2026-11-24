<script setup>
import { amount } from '../lib/display'
import { issueLocation, issueNextAction } from '../lib/submission-guidance'

defineProps({ issue: { type: Object, required: true }, parsingStatus: { type: String, default: '' } })
const emit = defineEmits(['open-cell'])

/** 안내에 포함된 실제 원본 셀을 상위 읽기 전용 조회 패널로 연결한다.
 * Args: source 서버가 반환한 sheet/cell. Returns: 없음. Raises: 없음.
 * Side Effects: 유효한 시트·셀이 있으면 open-cell 이벤트만 보낸다.
 */
function openCell(source) { if (source?.sheet && source?.cell) emit('open-cell', source) }
</script>

<template>
  <div class="diagnostic-row">
    <span :class="['badge', issue.severity === 'ERROR' ? 'red' : 'amber']">{{ parsingStatus === 'FAILED' ? '처리 실패' : issue.severity === 'ERROR' || parsingStatus === 'NEEDS_REVIEW' ? '확인 필요' : '안내' }}</span>
    <div class="issue-content">
      <strong class="issue-location">{{ issueLocation(issue) }}</strong>
      <p class="issue-reason">{{ issue.message || '제출 전 이 항목을 확인해 주세요.' }}</p>
      <p class="issue-action"><b>다음 행동</b> {{ issueNextAction(issue, parsingStatus) }}</p>
      <button v-if="issue.source?.sheet && issue.source?.cell" class="text-button" @click="openCell(issue.source)">저장된 원본 셀 보기</button>
      <details class="issue-details"><summary>진단 정보</summary><small>{{ issue.code || '코드 없음' }}<template v-if="issue.stage"> · {{ issue.stage }}</template></small><small v-if="issue.code === 'CONTROL_CACHE_PRECISION'">저장 캐시 {{ amount(issue.cachedAmount) }}원 · 상세 합계 {{ amount(issue.calculatedAmount) }}원 · 차이 {{ amount(issue.difference) }}원</small></details>
    </div>
  </div>
</template>

<style scoped>
.issue-content { min-width:0; flex:1; }
.issue-location { display:block; line-height:1.7; }
.issue-reason { margin:5px 0; font-size:13px; line-height:1.7; overflow-wrap:anywhere; }
.issue-action { margin:5px 0 8px; font-size:12px; line-height:1.7; color:var(--muted); }
.issue-action b { color:var(--ink); margin-right:5px; font-weight:500; }
.issue-details { margin-top:8px; font-size:11px; color:var(--muted); }
.issue-details summary { cursor:pointer; }
</style>
