// The first width includes the three 10px hierarchy gutters.
// 좁은 화면에서는 표 내부를 가로로 스크롤한다. 자동 맞춤 때문에 금액을 잘라 보이지 않게 압축하지 않는다.
export const gapColumnMinimums = [240, 128, 128, 128, 128, 112]
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
