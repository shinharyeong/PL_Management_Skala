<script setup>
import { computed } from 'vue'
import { sum, subtract, percent, compare } from './exact-money.js'
import { formatMoney } from '../lib/decimal-display.js'
import { statuses, statusTones } from './domain.js'
import './monthly-live.css'

const props = defineProps({ rows: Array, selected: Array, unit: String, type: String, editable: Boolean })
const emit = defineEmits(['select', 'detail', 'all'])
const money = value => formatMoney(value, props.unit)
const tone = status => statusTones[statuses.indexOf(status)] || 'excluded'
const selectable = row => props.editable && ['검토 필요', '검토 완료'].includes(row.status)
const selectableRows = computed(() => props.rows.filter(selectable))
const allSelected = computed(() => selectableRows.value.length > 0 && selectableRows.value.every(row => props.selected.includes(row.id)))
const rate = row => {
  const value = percent(row.base, row.current)
  return value == null ? '산정 불가' : `${compare(value, '0') > 0 ? '+' : ''}${value}%`
}
const delta = row => {
  const value = subtract(row.current, row.base)
  return `${compare(value, '0') > 0 ? '+' : ''}${money(value)}`
}
// Each gutter spans its descendants and closing row. Every data row keeps the
// same five financial columns, regardless of its depth in the hierarchy.
const groupedRows = computed(() => {
  const fields = ['major', 'group', 'category']
  function branch(items, level, path) {
    const result = []
    for (const name of new Set(items.map(item => item[fields[level]] || '분류 없음'))) {
      const children = items.filter(item => (item[fields[level]] || '분류 없음') === name)
      const key = `${path}/${name}`
      const head = { key, name, level, rails: [], base: sum(children.map(item => item.baselineAmount)), current: sum(children.map(item => item.currentAmount)) }
      const descendants = level < 2
        ? branch(children, level + 1, key)
        : children.map(item => ({ ...item, key: item.id, level: 3, rails: [], base: item.baselineAmount, current: item.currentAmount }))
      descendants[0].rails.unshift({ level, span: descendants.length + 1 })
      result.push(head, ...descendants, { key: `${key}/close`, close: true, level })
    }
    return result
  }
  return branch(props.rows, 0, '')
})
</script>

<template>
  <div class="table-scroll hierarchy-table live-gap-table" tabindex="0" role="region" aria-label="Gap 계산 결과">
    <table>
      <colgroup><col v-for="level in 3" :key="level" class="live-hierarchy-gutter" /><col class="live-hierarchy-name" /><col span="3" class="live-hierarchy-amount" /><col class="live-hierarchy-rate" /><col class="live-hierarchy-status" /></colgroup>
      <thead><tr><th colspan="4"><input type="checkbox" aria-label="표시 항목 모두 선택" :disabled="!selectableRows.length" :checked="allSelected" @change="emit('all', $event.target.checked)" />항목</th><th class="number">{{ type === '추정 vs 추정' ? '직전 추정' : '계획값' }}</th><th class="number">{{ type === '실적 vs 계획' ? '실적' : '추정' }}</th><th class="number">차액</th><th class="number">차이율</th><th>진행 단계</th></tr></thead>
      <tbody>
        <tr v-for="row in groupedRows" :key="row.key" :class="row.close ? 'hierarchy-close' : [['major-row','middle-row','minor-row','detail-row'][row.level], {checked:row.id && selected.includes(row.id)}]">
          <template v-if="row.close"><td :colspan="8 - row.level" :class="['major-rail','middle-rail','minor-rail'][row.level]" aria-hidden="true"></td></template>
          <template v-else>
            <td v-for="rail in row.rails" :key="rail.level" :rowspan="rail.span" class="hierarchy-rail" :class="['major-rail','middle-rail','minor-rail'][rail.level]" aria-hidden="true"></td>
            <th v-if="row.level < 3" :colspan="4 - row.level" scope="row">{{ ['대','중','소'][row.level] }} · {{ row.name }}</th>
            <td v-else class="hierarchy-detail"><div class="live-hierarchy-item"><input type="checkbox" :aria-label="row.name + ' 선택'" :checked="selected.includes(row.id)" :disabled="!selectable(row)" @change="emit('select',row.id)" /><button class="text-button cell-link" @click="emit('detail',row)">{{ row.name }}</button></div></td>
            <td class="number">{{ money(row.base) }}</td><td class="number">{{ money(row.current) }}</td><td class="number" :class="compare(row.current,row.base) < 0 ? 'negative' : 'positive'">{{ delta(row) }}</td><td class="number">{{ rate(row) }}</td><td><span v-if="row.status" class="badge" :class="tone(row.status)">{{ row.status }}</span></td>
          </template>
        </tr>
      </tbody>
    </table>
  </div>
</template>
