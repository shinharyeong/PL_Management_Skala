import test from 'node:test'
import assert from 'node:assert/strict'
import {businessConfirmation,confirmBusinessAction,settleBusinessConfirmation} from './business-confirm.js'

test('Execute only after explicit acceptance, and clear the popup',async()=>{
 let executed=false
 const work=confirmBusinessAction({title:'승인',details:[{label:'대상',value:'조직1'}]}).then(()=>{executed=true})
 assert.equal(executed,false)
 assert.equal(businessConfirmation.value.title,'승인')
 settleBusinessConfirmation(true)
 await work
 assert.equal(executed,true)
 assert.equal(businessConfirmation.value,null)
})
test('Cancel or navigation aborts the action without running it',async()=>{
 let executed=false
 const work=confirmBusinessAction({title:'발송'}).then(()=>{executed=true})
 const rejection=assert.rejects(work,{name:'AbortError'})
 settleBusinessConfirmation(false)
 await rejection
 assert.equal(executed,false)
 assert.equal(businessConfirmation.value,null)
})
test('A repeated click cannot replace the pending decision',async()=>{
 const first=confirmBusinessAction({title:'첫 승인'})
 await assert.rejects(confirmBusinessAction({title:'중복 승인'}),{name:'AbortError'})
 assert.equal(businessConfirmation.value.title,'첫 승인')
 settleBusinessConfirmation(true)
 await first
 settleBusinessConfirmation(false)
})
