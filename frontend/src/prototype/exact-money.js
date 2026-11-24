function read(value) {
  const m=/^(-?)(\d+)(?:\.(\d+))?$/.exec(String(value??''))
  return m?{ n:BigInt(m[2]+(m[3]||''))*(m[1]?-1n:1n), s:(m[3]||'').length }:null
}
function text(n,s){const sign=n<0n?'-':'';const digits=(n<0n?-n:n).toString().padStart(s+1,'0');return sign+(s?digits.slice(0,-s)+'.'+digits.slice(-s):digits)}
export function sum(values){const rows=values.map(read);if(rows.some(r=>!r))return null;const scale=Math.max(0,...rows.map(r=>r.s));return text(rows.reduce((n,r)=>n+r.n*10n**BigInt(scale-r.s),0n),scale)}
export function subtract(a,b){if(a==null||b==null)return null;return sum([a,String(b).startsWith('-')?String(b).slice(1):'-'+b])}
export function compare(a,b){const d=read(subtract(a,b));return !d?0:d.n<0n?-1:d.n>0n?1:0}
export function percent(base,current){const a=read(base),d=read(subtract(current,base));if(!a||!d||a.n===0n)return null;const n=d.n*10000n*10n**BigInt(a.s)/( (a.n<0n?-a.n:a.n)*10n**BigInt(d.s));return text(n,2)}
