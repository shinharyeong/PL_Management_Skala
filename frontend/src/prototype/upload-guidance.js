export function uploadGuidance(actor,request){
 const code=actor?.orgCode
 const title=request?.title||''
 const annual=title.match(/(\d{4})년 연간 계획/)
 if(!code)return '파일명은 조직코드_YYYY-PLAN.xlsx(연간) 또는 조직코드_YYYY-MM.xlsx(월간) 형식이어야 합니다. 확장자는 .xlsx 또는 .xlsb입니다.'
 const month=title.match(/(\d{1,2})월 실적/)
 const name=annual?`${code}_${annual[1]}-PLAN`:month?`${code}_YYYY-${month[1].padStart(2,'0')}`:`${code}_YYYY-PLAN`
 return `현재 요청의 파일명은 ${name}.xlsx 또는 ${name}.xlsb 형식입니다. 선택한 조직·기간의 원본 자료를 사용해 주세요. 다른 조직이나 월간 자료는 파일명만 바꿔 연간 계획으로 제출하면 안 됩니다.`
}
