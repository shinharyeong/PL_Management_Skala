import {formatMoney} from '../lib/decimal-display.js'
// Display only: truncate from the third decimal place, without modifying source amounts.
export function homeMoney(value) {
  const formatted=formatMoney(value,'HUNDRED_MILLION')
  if(formatted==='—')return formatted
  const [whole,fraction='']=formatted.split('.')
  const decimals=fraction.slice(0,2).padEnd(2,'0')
  const integer=whole.startsWith('-')&&!/[1-9]/.test(whole+decimals)?whole.slice(1):whole
  return integer+'.'+decimals
}
