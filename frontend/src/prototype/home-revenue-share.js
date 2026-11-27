import {compare,sum} from './exact-money.js'

export function revenueShare(columns=[]) {
  if(!columns.length||columns.some(row=>row.revenue==null||compare(row.revenue,'0')<0))return []
  const sorted=[...columns].sort((a,b)=>compare(b.revenue,a.revenue)||String(a.organization.id).localeCompare(String(b.organization.id)))
  const total=sum(sorted.map(row=>row.revenue))
  if(compare(total,'0')<=0)return []
  const slices=sorted.slice(0,3).map((row,index)=>({key:row.organization.id,name:row.organization.name,amount:row.revenue,rank:index+1}))
  if(sorted.length>3)slices.push({key:'other',name:'기타',amount:sum(sorted.slice(3).map(row=>row.revenue))})
  let angle=-Math.PI/2
  const colors=['#3868bd','#68a39c','#bd9b68','#dbe1ea']
  return slices.map((slice,index)=>{
    const ratio=Number(slice.amount)/Number(total),end=angle+ratio*Math.PI*2
    const point=a=>`${100+78*Math.cos(a)} ${100+78*Math.sin(a)}`
    const path=ratio>=1?'M 100 22 A 78 78 0 1 1 100 178 A 78 78 0 1 1 100 22 Z':ratio>0?`M 100 100 L ${point(angle)} A 78 78 0 ${ratio>0.5?1:0} 1 ${point(end)} Z`:''
    angle=end
    return {...slice,percent:(ratio*100).toFixed(1),path,color:colors[index]}
  })
}
