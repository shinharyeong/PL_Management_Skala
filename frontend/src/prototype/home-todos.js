export function homeTodoItems(annual, monthly) {
  return [['ANNUAL',annual],['MONTHLY',monthly]].flatMap(([scope,items]) =>
    (items || []).filter(item=>Number(item.count)>0).map(item=>({...item,scope})))
}
export function homeTodoDestination(todo) {
  return todo.key==='REQUEST_NEEDED' ? (todo.scope==='ANNUAL'?'annual-request':'monthly-request')
    : todo.scope==='ANNUAL'?'annual':'monthly'
}
