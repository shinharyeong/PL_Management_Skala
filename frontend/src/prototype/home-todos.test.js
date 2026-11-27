import test from 'node:test'
import assert from 'node:assert/strict'
import {homeTodoItems,homeTodoDestination} from './home-todos.js'
test('todos hide zero counts and prioritize annual work while keeping correct destinations',()=>{
 const rows=homeTodoItems([{key:'REQUEST_NEEDED',count:0},{key:'REVIEW_NEEDED',count:2}], [{key:'REQUEST_NEEDED',count:1},{key:'NOT_SUBMITTED',count:0}])
 assert.deepEqual(rows.map(r=>[r.scope,r.key]),[['ANNUAL','REVIEW_NEEDED'],['MONTHLY','REQUEST_NEEDED']])
 assert.deepEqual(rows.map(homeTodoDestination),['annual','monthly-request'])
 assert.equal(homeTodoDestination({scope:'ANNUAL',key:'REQUEST_NEEDED'}),'annual-request')
 assert.deepEqual(homeTodoItems(undefined,[]),[])
})
