import test from 'node:test'
import assert from 'node:assert/strict'
import {revenueShare} from './home-revenue-share.js'
const org=(id,revenue)=>({organization:{id,name:'조직'+id},revenue})
test('revenue pie ranks three organizations and sums every remaining organization exactly',()=>{
 const source=[org('a','10.01'),org('b','30'),org('c','20'),org('d','40'),org('e','0.02')]
 const rows=revenueShare(source)
 assert.deepEqual(rows.map(r=>r.key),['d','b','c','other'])
 assert.equal(rows[3].amount,'10.03')
 assert.equal(source[0].organization.id,'a')
 assert.ok(rows.every(r=>!r.path.includes('NaN')))
})
test('pie supports a single full circle, missing/zero data and does not hide negative revenues',()=>{
 assert.equal(revenueShare([org('a','100')])[0].percent,'100.0')
 assert.deepEqual(revenueShare([org('a','0')]),[])
 assert.deepEqual(revenueShare([org('a',null)]),[])
 assert.deepEqual(revenueShare([org('a','100'),org('b','-20')]),[])
})
