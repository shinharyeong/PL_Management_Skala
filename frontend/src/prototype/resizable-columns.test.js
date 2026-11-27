import test from 'node:test'
import assert from 'node:assert/strict'
import { resizeGapColumn, fitGapColumns, gapColumnMinimums } from './resizable-columns.js'
const initial=[408,168,168,168,144,168]
const sum=values=>values.reduce((a,b)=>a+b,0)
test('drag transfers width to the adjacent column and fixes the right table edge',()=>{
  assert.deepEqual(resizeGapColumn(initial,1,50),[408,208,128,168,144,168])
  assert.deepEqual(resizeGapColumn(initial,0,-60),[348,228,168,168,144,168])
  for(let i=0;i<5;i++)for(const delta of [-10000,-60,0,70,10000]){
    const next=resizeGapColumn(initial,i,delta)
    assert.equal(sum(next),sum(initial))
    next.forEach((width,index)=>assert.ok(width>=gapColumnMinimums[index]))
  }
  assert.equal(resizeGapColumn(initial,5,10),null)
})
test('viewport changes fill the entire width and preserve minimum readable widths',()=>{
  for(const size of [400,800,1200,1920,2560]){
    const widths=fitGapColumns(initial,size)
    assert.ok(Math.abs(sum(widths)-Math.max(size,sum(gapColumnMinimums)))<0.00001)
    widths.forEach((width,i)=>assert.ok(width>=gapColumnMinimums[i]))
  }
  assert.deepEqual(fitGapColumns(initial,400),gapColumnMinimums)
})
test('invalid measurements cannot corrupt table geometry',()=>{
  assert.equal(resizeGapColumn([],0,1),null)
  assert.equal(resizeGapColumn(initial,1,Infinity),null)
  assert.equal(fitGapColumns(initial,0),null)
})
