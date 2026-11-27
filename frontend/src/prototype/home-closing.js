export function closingStep(step) {
  const total=Math.max(0,Number(step.total)||0),done=Math.max(0,Number(step.done)||0)
  const state=total>0&&done>=total?'complete':done>0?'progress':'waiting'
  return {state,label:{complete:'완료',progress:'진행 중',waiting:'대기'}[state],
    value:total===1?(done>=1?'완료':'미완료'):total>0?`${done} / ${total}`:'대기',
    percent:total>0?Math.min(100,done/total*100):0}
}
