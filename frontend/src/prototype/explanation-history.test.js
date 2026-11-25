import test from 'node:test'
import assert from 'node:assert/strict'
import { explanationEvents, loadVersionExplanations } from './explanation-history.js'
import { hierarchyTableRows, expandedGapItems } from './hierarchy-table-rows.js'

test('a collapsed major retains exact totals and removes its descendants and rails only',()=>{
  const rows=[{id:'1',major:'매출',group:'기본료',category:'음성',baselineAmount:'9007199254740993.1',currentAmount:'9007199254740993.2'},{id:'2',major:'매출',group:'기본료',category:'데이터',baselineAmount:'0.2',currentAmount:'0.3'},{id:'3',major:'비용',group:'관리비',category:'인건비',baselineAmount:'100',currentAmount:'150'}]
  const collapsed=new Set(['매출']),result=hierarchyTableRows(rows,collapsed)
  assert.equal(result[0].name,'매출');assert.equal(result[0].collapsed,true)
  assert.equal(result[0].base,'9007199254740993.3')
  assert.deepEqual(result.filter(row=>row.id).map(row=>row.id),['3'])
  assert.deepEqual(expandedGapItems(rows,collapsed).map(row=>row.id),['3'])
  assert.deepEqual(result[0].rails,[])
  assert.deepEqual(hierarchyTableRows(rows).filter(row=>row.id).map(row=>row.id),['1','2','3'])
})

test('timeline distinguishes explanation approval and submission approval without inventing decision times',()=>{
  const events=explanationEvents({label:'2026-11-01',approvedLabel:'ver11.01',submittedAt:'2026-11-01T00:00:00Z',submittedBy:{name:'조직 담당자'},history:[{event:'APPROVED',label:'ver11.01',at:'2026-11-05T00:00:00Z',actor:{name:'기획자'}}]},[{id:'1',round:2,requestedAt:'2026-11-02T00:00:00Z',requestedBy:{name:'기획자'},answeredAt:'2026-11-03T00:00:00Z',answer:'원문',answeredBy:{name:'조직 담당자'},verdict:'ACCEPTED',approvedLabel:'ver11.01'}])
  assert.deepEqual(events.map(e=>e.label),['최초 제출','소명 요청','소명 회신','소명 승인 · ver11.01','제출본 승인'])
  assert.equal(events[3].at,null);assert.equal(events[3].actor,null)
  assert.equal(events[4].at,'2026-11-05T00:00:00Z')
})

test('a new submission does not inherit a previous version rejection in its own timeline',()=>{
  const history=[{event:'RESUBMIT_REQUESTED',label:'2026-11-01',at:'2026-11-10T00:00:00Z'},{event:'APPROVED',label:'ver11.01',at:'2026-11-12T00:00:00Z'}]
  assert.deepEqual(explanationEvents({label:'2026-11-02',approvedLabel:null,history},[]),[])
  const own=explanationEvents({label:'2026-11-01',approvedLabel:null,history},[])
  assert.deepEqual(own.map(event=>event.label),['파일 재제출 요청'])
})

test('version history resolves the full path instead of joining same-name accounts',async()=>{
  const detail={id:'30',path:'매출 › 기타 › 사용료 › 11월 실적',month:11,scenario:'ACTUAL',explanations:[]}
  const currentSubmission={id:'3',label:'2026-11-03'}
  const columns=[{submissionId:'1',label:'ver11.01',state:'CONFIRMED'},{submissionId:'3',label:'2026-11-03',state:'NEW'}]
  const api=async path=>{
    if(path==='/api/v1/submissions/1')return {id:'1'}
    if(path.includes('view=ORG'))return {groups:[{months:[{month:11,scenario:'ACTUAL',items:[{reviewItemId:'10',name:'사용료'},{reviewItemId:'11',name:'사용료'}]}]}]}
    return path.endsWith('/10')?{...detail,path:'비용 › 기타 › 사용료 › 11월 실적'}:{...detail,id:'11',explanations:[{id:'old',answer:'과거 소명 원문'}]}
  }
  const result=await loadVersionExplanations({detail,columns,currentSubmission,api})
  assert.equal(result[0].detail.id,'11')
  assert.equal(result[0].detail.explanations[0].answer,'과거 소명 원문')
  assert.equal(result[1].detail,detail)
  assert.equal(result[1].previousLabel,'ver11.01')
})

test('a historical fetch failure leaves current-version history usable and exposes an error',async()=>{
  const detail={id:'current',path:'매출 › 사용료 › 11월 실적',explanations:[]}
  const result=await loadVersionExplanations({detail,currentSubmission:{id:'2'},columns:[{submissionId:'1'},{submissionId:'2'}],api:async()=>{throw Error('offline')}})
  assert.ok(result[0].error);assert.equal(result[1].detail,detail)
})
