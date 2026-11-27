export function uploadGuidance(actor,request){
 const code=actor?.orgCode
 const title=request?.title||''
 const annual=title.match(/(\d{4})년 연간 계획/)
 if(!code)return '파일명은 조직코드_YYYY-PLAN.xlsx(연간) 또는 조직코드_YYYY-MM.xlsx(월간) 형식이어야 합니다. 확장자는 .xlsx 또는 .xlsb입니다.'
 const month=title.match(/(\d{1,2})월 실적/)
 const year=request?.year||title.match(/(\d{4})년/)?.[1]||'YYYY'
 const name=annual?`${code}_${annual[1]}-PLAN`:month?`${code}_${year}-${month[1].padStart(2,'0')}`:`${code}_${year}-PLAN`
 return `파일명: ${name}.xlsx 또는 ${name}.xlsb · 요청한 조직과 기간의 원본 파일을 사용해 주세요.`
}
