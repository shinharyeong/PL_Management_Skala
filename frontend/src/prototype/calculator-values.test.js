import test from 'node:test'
import assert from 'node:assert/strict'
import { calculatorRows, calculatorComparisons, matchesCalculatorConditions } from './calculator-values.js'
import { flattenGapTree } from './live-api.js'
import { hierarchyTableRows } from './hierarchy-table-rows.js'

function row(id, month, scenario, current, plan, previousEstimate) {
  return flattenGapTree([{ reviewItemId:id,reportItemId:'account',month,scenario,name:`${month}월`,
    actual:current,plan,previousEstimate,tab:'NEEDS_REVIEW' }],scenario==='ACTUAL'?'실적 vs 계획':'추정 vs 계획')[0]
}
const conditions={rateEnabled:true,rate:'5',amountEnabled:true,minimum:'10'}

test('June actual and July forecast keep four separate columns and May forecast, preserving zero and missing sources',()=>{
  const june=row('june',6,'ACTUAL','120','100','110'),july=row('july',7,'ESTIMATE','0','100',null)
  assert.deepEqual([june.planAmount,june.previousEstimate,june.actualAmount,june.estimateAmount],['100','110','120',null])
  assert.deepEqual([july.planAmount,july.previousEstimate,july.actualAmount,july.estimateAmount],['100',null,null,'0'])
  const comparisons=calculatorComparisons(june)
  assert.deepEqual(comparisons.map(c=>[c.label,c.diff,c.rate]),[['계획 대비 실적','20','20.00'],['이전 추정 대비 실적','10','9.09']])
  assert.equal(calculatorComparisons(july)[1].diff,null)
  assert.equal(calculatorComparisons(july)[0].label,'계획 대비 추정')
  const [head]=hierarchyTableRows([june,july])
  assert.deepEqual([head.planAmount,head.previousEstimate,head.actualAmount,head.estimateAmount],['200',null,'120','0'])
})

test('a real actual replaces the same-month forecast without duplicating review IDs or summing both',()=>{
  const june=row('actual',6,'ACTUAL','0','100','110'),old=row('forecast',6,'ESTIMATE','120','100','110'),future=row('future',7,'ESTIMATE','130','100','110')
  assert.deepEqual(calculatorRows([june],[old,future]).map(r=>r.id),['actual','future'])
})

test('either comparison can meet the inclusive thresholds but both enabled rules must hold within that comparison',()=>{
  assert.equal(matchesCalculatorConditions(row('a',6,'ACTUAL','110','110','100'),conditions),true)
  assert.equal(matchesCalculatorConditions(row('a',6,'ACTUAL','110','110',null),conditions),false)
  assert.equal(matchesCalculatorConditions(row('a',6,'ACTUAL','110','1000','105'),{...conditions,minimum:'800',rate:'100'}),false)
  assert.equal(matchesCalculatorConditions(row('a',6,'ACTUAL','10','0',null),conditions),true)
  assert.equal(calculatorComparisons(row('a',6,'ACTUAL','10','0',null))[0].rate,null)
})

test('large fractional amounts compare exactly and prior forecasts never fall back to annual plan',()=>{
  const item=row('a',6,'ACTUAL','9007199254740993.12','9007199254740993.10',null)
  assert.equal(calculatorComparisons(item)[0].diff,'0.02')
  assert.equal(item.previousEstimate,null)
})

test('item toggles preserve totals and actuals precede forecasts in calendar order with approvals last in the same month',()=>{
  const items=[row('sep',9,'ACTUAL','120','100','110'),row('nov-approved',11,'ACTUAL','120','100','110'),row('dec',12,'ESTIMATE','120','100','110'),row('nov-open',11,'ACTUAL','125','100','110')]
  items[1].status='소명 승인 완료'
  const expanded=hierarchyTableRows(items)
  assert.deepEqual(expanded.filter(r=>r.id).map(r=>r.id),['sep','nov-open','nov-approved','dec'])
  const category=expanded.find(r=>r.level===2&&!r.close)
  const folded=hierarchyTableRows(items,new Set([category.key]))
  assert.equal(folded.filter(r=>r.id).length,0)
  assert.equal(folded.find(r=>r.key===category.key).planAmount,'400')
  assert.equal(folded.find(r=>r.key===category.key).collapsed,true)
})


test('November calculator excludes past actuals and expired estimates without deleting original financial values',()=>{
  const past=row('sep',9,'ACTUAL','120','100','110'),current=row('nov',11,'ACTUAL','125','100','110')
  const expired=row('old-estimate',11,'ESTIMATE','110','100','105'),future=row('dec',12,'ESTIMATE','130','100','110')
  assert.deepEqual(calculatorRows([past,current],[expired,future],11).map(item=>item.id),['nov','dec'])
  assert.equal(past.actualAmount,'120')
})


test('server comparison sources control qualification: resubmission ignores annual-plan and last-month gaps',()=>{
  const item=row('a',3,'ACTUAL','1250','1000','1100')
  item.comparisons=[{label:'직전 실적 대비 실적',baselineAmount:'1200',currentAmount:'1250'}]
  assert.equal(calculatorComparisons(item).length,1)
  assert.equal(calculatorComparisons(item)[0].diff,'50')
  assert.equal(matchesCalculatorConditions(item,{rateEnabled:false,amountEnabled:true,minimum:'100'}),false)
  item.comparisons=[]
  assert.equal(matchesCalculatorConditions(item,conditions),false)
})


test('January actual is followed by February through December forecasts regardless of input order or status',()=>{
  const items=[row('jan',1,'ACTUAL','120','100','110'),...Array.from({length:11},(_,i)=>row('m'+(i+2),i+2,'ESTIMATE','130','100','110'))]
  items[0].status='소명 승인 완료'
  const displayed=hierarchyTableRows([...items].reverse()).filter(item=>item.id)
  assert.deepEqual(displayed.map(item=>item.month),Array.from({length:12},(_,i)=>i+1))
  assert.equal(displayed[0].scenario,'ACTUAL')
})
