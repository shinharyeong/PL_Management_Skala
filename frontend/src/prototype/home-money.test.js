import test from 'node:test'
import assert from 'node:assert/strict'
import {homeMoney} from './home-money.js'
test('home amounts truncate from the third decimal place in hundred-million won without rounding or losing precision',()=>{
 assert.equal(homeMoney('888334607000'),'8,883.34')
 assert.equal(homeMoney('-5550806000'),'-55.50')
 assert.equal(homeMoney('702626718000'),'7,026.26')
 assert.equal(homeMoney('0'),'0.00')
 assert.equal(homeMoney('-1'),'0.00')
 assert.equal(homeMoney('9007199254740993120000'),'90,071,992,547,409.93')
 assert.equal(homeMoney(null),'—')
})
