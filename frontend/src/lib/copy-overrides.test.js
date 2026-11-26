import test from 'node:test'
import assert from 'node:assert/strict'
import {mergeSharedCopy,createSharedCopyClient} from './copy-overrides.js'
const base={pages:{home:'전사 손익'},login:{title:'로그인'}}
test('shared text merges known string keys only and never mutates base data',()=>{
  const result=mergeSharedCopy(base,[{key:'pages.home',value:'공통 변경'},{key:'pages.unknown',value:'ignored'},{key:'pages.__proto__',value:'ignored'},{key:'login.title',value:123}])
  assert.equal(result.pages.home,'공통 변경');assert.equal(base.pages.home,'전사 손익');assert.equal(result.login.title,'로그인');assert.equal(Object.hasOwn(result.pages,'unknown'),false)
})
test('two clients share server values and stale revision cannot overwrite another edit',async()=>{
  let row=null
  const request=async(path,options)=>{
    if(!options) return row?[{...row}]:[]
    assert.equal(path,'/api/v1/dev/ui-copy');assert.equal(options.method,'PUT')
    const body=JSON.parse(options.body)
    if(body.expectedRevision!==(row?.revision||0)) throw new Error('RESOURCE_CHANGED')
    row={key:body.key,value:body.value,revision:(row?.revision||0)+1};return {...row}
  }
  const a=createSharedCopyClient(base,request),b=createSharedCopyClient(base,request)
  await a.read();await b.read()
  await a.change({key:'pages.home',expected:'전사 손익',value:'함께 반영'})
  await assert.rejects(b.change({key:'pages.home',expected:'전사 손익',value:'오래된 편집'}),/RESOURCE_CHANGED/)
  assert.equal((await b.read()).pages.home,'함께 반영')
  await a.change({key:'pages.home',expected:'함께 반영',value:'전사 손익'})
  assert.equal((await b.read()).pages.home,'전사 손익')
})
