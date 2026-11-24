import test from 'node:test'
import assert from 'node:assert/strict'
import {notificationDate,notificationTime,mergeNotificationGroups} from './presentation.js'

test('notification dates and received times follow Seoul calendar boundaries',()=>{
 assert.equal(notificationDate('2026-11-24',new Date('2026-11-23T15:30:00Z')),'오늘 · 11.24 (화)')
 assert.equal(notificationDate('2026-11-23',new Date('2026-11-23T15:30:00Z')),'어제 · 11.23 (월)')
 assert.equal(notificationTime({type:'REPLY',occurredAt:'2026-11-23T15:12:00Z'}),'수신 00:12')
 assert.equal(notificationTime({type:'OVERDUE',occurredAt:'2026-11-23T09:00:00Z'}),'기한 11.23 18:00 경과')
})
test('notification cursor pages join split dates and replace duplicate item IDs',()=>{
 const merged=mergeNotificationGroups([{date:'2026-11-24',unreadCount:2,items:[{id:'a',read:false}]}],[{date:'2026-11-24',unreadCount:1,items:[{id:'a',read:true},{id:'b',read:false}]},{date:'2026-11-23',unreadCount:0,items:[{id:'c',read:true}]}])
 assert.deepEqual(merged.map(g=>g.items.map(i=>i.id)),[['a','b'],['c']])
 assert.equal(merged[0].items[0].read,true)
 assert.equal(merged[0].unreadCount,1)
})
