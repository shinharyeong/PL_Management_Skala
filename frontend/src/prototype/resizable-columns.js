// The first width includes the three 10px hierarchy gutters.
export const gapColumnMinimums = [180, 96, 96, 96, 80, 110]
const valid = widths => Array.isArray(widths) && widths.length === gapColumnMinimums.length && widths.every(width=>Number.isFinite(width)&&width>0)
export function fitGapColumns(widths, available) {
  if (!valid(widths) || !Number.isFinite(available) || available<=0) return null
  const minimum = gapColumnMinimums.reduce((a,b)=>a+b,0)
  const extra = Math.max(0,available-minimum)
  const weights = widths.map((width,i)=>Math.max(0,width-gapColumnMinimums[i]))
  const total = weights.reduce((a,b)=>a+b,0)
  return gapColumnMinimums.map((min,i)=>min+extra*(total?weights[i]/total:1/widths.length))
}
export function resizeGapColumn(widths, index, delta) {
  if (!valid(widths) || !Number.isInteger(index) || index<0 || index>=widths.length-1 || !Number.isFinite(delta)) return null
  const change = Math.max(gapColumnMinimums[index]-widths[index], Math.min(delta,widths[index+1]-gapColumnMinimums[index+1]))
  return widths.map((width,i)=>i===index?width+change:i===index+1?width-change:width)
}
