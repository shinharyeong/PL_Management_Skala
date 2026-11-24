import {nextTick} from 'vue'

// Wait for reply content and route-heading focus before revealing the selected row.
export async function revealOrganization(rowId, isCurrent = () => true) {
  await nextTick()
  await new Promise(resolve => window.requestAnimationFrame(resolve))
  if (!isCurrent()) return
  const row = document.getElementById(rowId)
  if (!row) return
  row.querySelector('button[aria-expanded]')?.focus({preventScroll:true})
  row.scrollIntoView({block:'start',inline:'nearest',behavior:'instant'})
}
