<script setup>
import { computed } from 'vue'
import { chartGeometry, formatMoney } from '../lib/decimal-display'
import { dashboardMoney, moneyUnits } from '../lib/dashboard-values'
const props = defineProps({ totals: { type: Object, default: null }, unit: { type: String, default: 'HUNDRED_MILLION' }, label: { type: String, default: '' } })

/** 확인된 매출·영업비용·영업이익만 정확히 대사하고 차트 좌표를 만든다.
 * Args: 없음. Returns: 차트 데이터 또는 미표시 이유. Raises: 없음. Side Effects: 없음; 확인되지 않은 회계 분류를 추정하지 않음.
 */
const flow = computed(function confirmedProfitFlow() {
  const revenue = moneyUnits(props.totals?.revenue), cost = moneyUnits(props.totals?.cost), profit = moneyUnits(props.totals?.operatingProfit)
  if (revenue === null || cost === null || profit === null) return { reason: '선택 범위의 승인 금액이 준비되면 손익 흐름을 표시합니다.' }
  if (revenue - cost !== profit) return { reason: '매출·영업비용·영업이익의 합계가 일치하지 않아 도표를 표시하지 않습니다.' }
  const geometry = chartGeometry(['0', props.totals.revenue, props.totals.operatingProfit])
  const [zero, revenueY, profitY] = geometry.positions.map(chartY)
  return { zero, rows: [bar('매출', props.totals.revenue, zero, revenueY, 60, 'revenue'), bar('영업비용', props.totals.cost, revenueY, profitY, 240, 'cost'), bar('영업이익', props.totals.operatingProfit, zero, profitY, 420, 'profit')], revenueY, profitY }
})

/** 백분율 좌표를 SVG 표시 좌표로 변환한다. Args: value 좌표. Returns: y좌표. Raises: 없음. Side Effects: 없음. */
function chartY(value) { return 30 + value * 1.5 }
/** 한 손익 막대의 위치와 원금액을 구성한다. Args: label 이름, amount 원금액, start/end y좌표, x 가로좌표, kind 종류. Returns: 막대 DTO. Raises: 없음. Side Effects: 없음. */
function bar(label, amount, start, end, x, kind) { return { label, amount, x, y: Math.min(start, end), height: Math.max(1, Math.abs(start - end)), kind } }
</script>

<template><section class="profit-flow"><div class="section-heading"><div><h2>손익 흐름</h2><p class="muted">{{ label }} · 단위 {{ unit === 'KRW' ? '원' : '억원' }}</p></div><span class="badge">확인된 보고 분류</span></div><p v-if="flow.reason" class="notice">{{ flow.reason }}</p><svg v-else viewBox="0 0 600 240" role="img" aria-label="매출에서 영업비용을 차감한 영업이익 흐름"><line x1="25" x2="575" :y1="flow.zero" :y2="flow.zero" class="zero" /><line x1="160" x2="240" :y1="flow.revenueY" :y2="flow.revenueY" class="connector" /><line x1="340" x2="420" :y1="flow.profitY" :y2="flow.profitY" class="connector" /><g v-for="item in flow.rows" :key="item.kind"><rect :x="item.x" :y="item.y" width="100" :height="item.height" :class="item.kind"><title>{{ item.label }} {{ formatMoney(item.amount) }}원</title></rect><text :x="item.x + 50" y="208" text-anchor="middle">{{ item.label }}</text><text :x="item.x + 50" y="227" text-anchor="middle" class="amount">{{ dashboardMoney(item.amount, unit) }}</text></g></svg><p class="classification-note">매출원가·매출총이익·영업외손익·순이익: <strong>분류 미확정</strong>. 현재 자료의 영업비용 전체를 매출원가로 간주하지 않습니다.</p></section></template>

<style scoped>.profit-flow{padding:22px 24px;border:1px solid #e7e4de;border-radius:12px;background:white}.profit-flow h2{font-size:16px}.profit-flow svg{display:block;width:100%;max-height:300px}.profit-flow .muted{font-size:11px;margin-top:6px}.zero{stroke:#d3d0ca;stroke-width:1}.connector{stroke:#a5a3a0;stroke-dasharray:4 3}.revenue{fill:#5e5e5c}.cost{fill:#c68c75}.profit{fill:#334b43}text{fill:#333;font-size:13px}.amount{font-size:12px;fill:#777}.classification-note{font-size:12px;color:#797670;line-height:1.8;border-top:1px solid var(--line);padding-top:12px}</style>
