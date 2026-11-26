import test from 'node:test'
import assert from 'node:assert/strict'
import {layoutHomeWaterfall, waterfallStepDescription} from './home-waterfall-layout.js'
test('compact waterfall keeps positive and negative profit inside the plot, above labels',()=>{
 for(const cost of ['80','120']) {
  const input=[{key:'REVENUE',kind:'TOTAL',amount:'100'},{key:'COST',kind:'DEDUCTION',amount:cost},{key:'OP',kind:'TOTAL',amount:cost==='80'?'20':'-20'}]
  const result=layoutHomeWaterfall(input)
  for(const bar of result.bars){ assert.ok(bar.top>=24);assert.ok(bar.top+bar.height<=119);assert.ok(bar.end>=24&&bar.end<=118) }
  assert.ok(result.zero>=24&&result.zero<=118)
  assert.deepEqual(result.bars.map(b=>b.amount),input.map(s=>s.amount))
 }
})
test('empty and zero waterfall values do not create invalid coordinates',()=>{
 assert.deepEqual(layoutHomeWaterfall([]).bars,[])
 const result=layoutHomeWaterfall([{kind:'TOTAL',amount:'0'}])
 assert.ok(Number.isFinite(result.zero));assert.ok(Number.isFinite(result.bars[0].top))
})

test('HOME-06 keeps every cost category and child breakdown in server order',()=>{
 const input=[
  {key:'REVENUE',reportItemId:null,name:'매출',amount:'150000000000',kind:'TOTAL',breakdown:[]},
  {key:'COST',reportItemId:'11',name:'마케팅비용',amount:'20000000000',kind:'DEDUCTION',breakdown:[{reportItemId:'111',name:'광고비',amount:'20000000000'}]},
  {key:'COST',reportItemId:'12',name:'인건비',amount:'40000000000',kind:'DEDUCTION',breakdown:[]},
  {key:'OP',reportItemId:null,name:'영업이익',amount:'90000000000',kind:'TOTAL',breakdown:[]}
 ]
 const {bars}=layoutHomeWaterfall(input)
 assert.equal(bars.length,4)
 assert.deepEqual(bars.map(b=>b.name),['매출','마케팅비용','인건비','영업이익'])
 assert.deepEqual(bars.map(b=>b.reportItemId),input.map(s=>s.reportItemId))
 assert.deepEqual(bars[1].breakdown,input[1].breakdown)
 assert.equal(bars[2].start,130000000000)
 assert.equal(bars[2].end,bars[3].end)
 assert.match(waterfallStepDescription(bars[1]),/마케팅비용 · 차감 · 200억원 · 광고비 200억원/)
 assert.equal(input[1].amount,'20000000000')
})
test('HOME-06 does not cap the number of repeated COST steps',()=>{
 const costs=Array.from({length:8},(_,i)=>({key:'COST',reportItemId:String(i+1),name:'비용 '+i,kind:'DEDUCTION',amount:'100'}))
 const {bars}=layoutHomeWaterfall([{key:'REVENUE',kind:'TOTAL',amount:'1000'},...costs,{key:'OP',kind:'TOTAL',amount:'200'}])
 assert.equal(bars.length,10);assert.equal(bars.filter(b=>b.cost).length,8)
})
