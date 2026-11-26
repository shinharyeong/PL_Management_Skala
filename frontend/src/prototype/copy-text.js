import { reactive } from 'vue'
import initial from './ui-copy.json' with { type: 'json' }
import {createSharedCopyClient} from '../lib/copy-overrides.js'

const copy=reactive(structuredClone(initial))
export default copy
const client=createSharedCopyClient(initial,async path=>{
  const response=await fetch(path,{cache:'no-store',signal:AbortSignal.timeout(10000)})
  if(!response.ok) throw new Error('공통 문구를 불러오지 못했습니다.')
  return response.json()
})
export async function refreshSharedCopy() {
  const latest=await client.read()
  for(const [group,values] of Object.entries(latest)) Object.assign(copy[group],values)
}
let syncing=false
export function startSharedCopySync() {
  const timer=setInterval(async()=>{
    if(syncing || document.hidden) return
    syncing=true
    try {await refreshSharedCopy()} catch { /* Keep last successfully loaded values during temporary outages. */ }
    finally {syncing=false}
  },5000)
  return ()=>clearInterval(timer)
}
// Keep the reactive object stable while editing source JSON in Vite.
if(import.meta.hot) import.meta.hot.accept('./ui-copy.json',async module=>{
  if(!module) return
  for(const [group,values] of Object.entries(module.default)) Object.assign(copy[group],values)
  try {await refreshSharedCopy()} catch {}
})
