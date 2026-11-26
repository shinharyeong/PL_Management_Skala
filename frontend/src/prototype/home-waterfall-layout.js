import {formatMoney} from '../lib/decimal-display.js'

// Approximate numbers are used only for chart coordinates. Financial display strings stay intact.
export function layoutHomeWaterfall(steps) {
  let previous=0
  const segments=steps.map(step=>{
    const parsed=Number(step.amount), value=Number.isFinite(parsed)?parsed:0, cost=step.kind==='DEDUCTION'
    const start=cost?previous:0, end=cost?previous-Math.abs(value):value
    previous=end
    return {...step,start,end,cost}
  })
  const maximum=Math.max(0,...segments.flatMap(s=>[s.start,s.end]))
  const minimum=Math.min(0,...segments.flatMap(s=>[s.start,s.end]))
  const range=maximum-minimum||1
  const y=value=>24+(maximum-value)/range*94
  return {zero:y(0),bars:segments.map(s=>({...s,top:y(Math.max(s.start,s.end)),height:Math.max(1,Math.abs(y(s.start)-y(s.end))),end:y(s.end)}))}
}

// API names and child amounts are preserved for pointer and keyboard readers.
export function waterfallStepDescription(step) {
  return [step.name,step.kind==='DEDUCTION'?'차감':'합계',formatMoney(step.amount,'HUNDRED_MILLION')+'억원',
    ...(step.breakdown||[]).map(part=>part.name+' '+formatMoney(part.amount,'HUNDRED_MILLION')+'억원')].join(' · ')
}
