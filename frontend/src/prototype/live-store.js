import {confirmBusinessAction,settleBusinessConfirmation} from './business-confirm.js'
import { defineStore } from 'pinia'
import { createMonthlySession, seoulPeriod } from './domain.js'
import { createApiClient, comparisons, flattenGapTree, status, saveFile } from './live-api.js'
import { useSession } from '../stores/session.js'
import { annualProgressRows } from './review-status.js'
import { monthlyProgress } from './monthly-progress.js'
import {syncWorkspace,needsProcessing} from './workspace-items.js'
import {canSelectForReview} from './calculator-stages.js'
import {reviewActionBody} from '../lib/review-api.js'
import {monthlyReviewItems,monthlyExplanationStatus} from './monthly-review-progress.js'
import {calculatorRows} from './calculator-values.js'

// 요청 세대와 API 클라이언트는 로그인·스토어별로 격리한다. 늦게 도착한 응답은 최신 화면을 덮지 않는다.
const runtimes = new WeakMap()
function runtime(store) {
  if (!runtimes.has(store)) runtimes.set(store, { requests: new Map(), client: null })
  return runtimes.get(store)
}
function latestRequest(store, key) {
  const context = runtime(store), token = store.token
  const sequence = (context.requests.get(key) || 0) + 1
  context.requests.set(key, sequence)
  return () => runtimes.get(store) === context && token === store.token && context.requests.get(key) === sequence
}
function organizationRows(store, rows) {
  const previous = new Map(store.organizations.map(org => [org.id, org]))
  return rows.map(org => ({ submissionStatus: '요청 필요', explanationStatus: '—', ...previous.get(org.id), ...org,
    author: previous.get(org.id)?.submission?.submittedBy?.name || org.managers?.map(manager => manager.name).join(', ') || '—', department: org.name }))
}
export const usePrototypeStore = defineStore('connected-frontend', {
  state: () => ({ live:true, actor:null, token:'', error:'', busy:false, loading:false, unreadCount:0, toast:null, toastSequence:0,
    organizations:[], notifications:[], orgRequests:[], orgHistory:[], items:{}, itemsByComparison:{}, carts:{}, monthlySessions:{},
    recentCells:[], recentCellTarget:null, monthlyLoaded:false, activeOrg:'', activeRequest:'', monthlyRequest:null, annualRequest:null, requestOptions:[], orgReasons:{},
    notificationPage:null, notificationTarget:null, annualRows:[], activeReviewId:null, reviewDetail:null, evidence:null, versions:null, currentSubmission:null }),
  getters:{ unread:s=>s.unreadCount, currentOrg:s=>s.organizations.find(o=>o.id===s.activeOrg)||{ name:'조직을 선택해 주세요',code:'—' },
    workspaces:s=>Object.entries(s.carts).map(([org,rows])=>({org,name:s.organizations.find(o=>o.id===org)?.name||org,count:rows.filter(needsProcessing).length})).filter(work=>work.count>0).sort((a,b)=>s.organizations.findIndex(org=>org.id===a.org)-s.organizations.findIndex(org=>org.id===b.org)),
    visibleOrgRequests:s=>s.orgRequests, activeOrgRequest:s=>s.orgRequests.find(r=>r.id===s.activeRequest)||null,
    explanationItems:s=>s.items[s.actor?.orgId]||[] },
  actions:{
    notify(message,tone='info'){if(message)this.toast={id:++this.toastSequence,message:String(message),tone}},
    dismissToast(id){if(this.toast?.id===id)this.toast=null},
    api(path,options) { const context=runtime(this); context.client ||= createApiClient({token:()=>this.token,clear:()=>this.clear()}); return context.client(path,options) },
    clear(){ settleBusinessConfirmation(false); runtimes.delete(this); useSession().clear(); this.$reset() },
    async run(fn){
      if(this.busy)return false
      const context=runtime(this),isCurrent=()=>runtimes.get(this)===context
      this.busy=true;this.error=''
      try{await fn();return true}catch(e){
        if(isCurrent()&&e.name!=='AbortError'){this.error=[e.message,e.code,e.requestId&&`요청 ID: ${e.requestId}`].filter(Boolean).join(' · ');this.notify(this.error,'error')}
        return false
      }finally{if(isCurrent())this.busy=false}
    },
    async pages(path){const rows=[];let cursor;do{const p=await this.api(path+(path.includes('?')?'&':'?')+'limit=100'+(cursor?'&cursor='+encodeURIComponent(cursor):''));rows.push(...(p.items||[]));cursor=p.nextCursor}while(cursor);return rows},
    requestCode(email){return this.api('/api/v1/auth/codes',{method:'POST',body:{email}})},
    syncSession(session){const m=session.actor;this.token=session.token;this.actor={id:m.id,name:m.name,email:m.email,role:m.role==='PLANNER'?'planner':'org',orgId:m.organization?.id,orgCode:m.organization?.code,department:m.organization?.name||'경영기획팀'}},
    async restoreLogin(){const session=useSession();if(!await session.restore())return false;this.syncSession(session);return true},
    developmentAccounts(accessKey){return this.api('/api/v1/dev/accounts',{headers:{'X-Dev-Access-Key':accessKey}})},
    async developmentLogin(email,accessKey){const result=await this.api('/api/v1/dev/sessions',{method:'POST',body:{email},headers:{'X-Dev-Access-Key':accessKey}});const session=useSession();const saved=session.accept(result);this.syncSession(session);if(!saved)this.notify('이 브라우저에서는 새로고침 후 다시 계정을 선택해야 합니다.','error')},
    async login(email,code){const r=await this.api('/api/v1/auth/sessions',{method:'POST',body:{email,code}});const session=useSession();const saved=session.accept(r);this.syncSession(session);if(!saved)this.notify('이 브라우저에서는 로그인 유지 정보를 저장할 수 없습니다. 새로고침하면 다시 로그인해야 합니다.','error')},
    async logout(){await this.api('/api/v1/auth/session',{method:'DELETE'});this.clear()},
    async refreshBadge(){if(this.actor?.role==='planner'){const r=await this.api('/api/v1/me/badges');this.unreadCount=r.unreadNotifications}},
    async loadOrganizations(){
      const current=latestRequest(this,'organizations'),rows=await this.pages('/api/v1/organizations?active=true')
      if(!current())return
      this.organizations=organizationRows(this,rows);this.activeOrg ||=rows[0]?.id||''
    },
    async loadMonthly(){
      const current=latestRequest(this,'monthly'),period=seoulPeriod();this.monthlyLoaded=false
      const [rows,orgs]=await Promise.all([this.pages('/api/v1/submission-requests?type=MONTHLY_ACTUAL'),this.pages('/api/v1/organizations?active=true')])
      if(!current())return
      const request=rows.find(row=>row.year===period.year&&row.month===period.month)
      const monthly=request?await this.api('/api/v1/submission-requests/'+request.id):null
      if(!current())return
      if(this.monthlyRequest?.request.id!==monthly?.request.id){this.items={};this.itemsByComparison={};this.carts={};this.monthlySessions={};this.currentSubmission=null}
      this.requestOptions=rows;this.monthlyRequest=monthly
      const targets=monthly?.targets||[]
      this.organizations=organizationRows(this,orgs).map(org=>{
        const progress=monthlyProgress(targets.find(target=>target.organization.id===org.id))
        const changed=org.latestSubmission?.id!==progress.latestSubmission?.id
        if(changed){delete this.items[org.id];delete this.itemsByComparison[org.id];delete this.carts[org.id];if(this.activeOrg===org.id)this.currentSubmission=null}
        return {...org,...progress,...(changed?{submission:null,message:'',submittedAt:null}:{}),
          explanationStatus:progress.latestSubmission?.status==='APPROVED'?'소명 승인 완료':progress.latestSubmission?.status==='REJECTED'?'재제출 요청':org.explanationStatus}
      })
      this.activeOrg ||=orgs[0]?.id||'';this.monthlyLoaded=true
      if(this.actor?.role==='planner'){
        const results=await Promise.allSettled(this.organizations.filter(org=>org.latestSubmission&&(org.latestSubmission.status==='PENDING'||this.carts[org.id]?.length))
          .map(async org=>{for(const type of new Set(['실적 vs 계획','추정 vs 계획',this.monthlySession(org.id).applied?.type||'실적 vs 계획']))await this.loadGap(org.id,type,{background:true})}))
        const failed=results.filter(result=>result.status==='rejected'&&result.reason?.name!=='AbortError').length
        if(current()&&failed)this.notify(`${failed}개 조직의 검토 항목을 불러오지 못했습니다. 해당 조직에서 다시 조회해 주세요.`,'error')
      }
    },
    async reply(org){const current=latestRequest(this,'reply:'+org),id=this.organizations.find(item=>item.id===org)?.latestSubmission?.id;if(!id)return;const s=await this.api('/api/v1/submissions/'+id);const o=this.organizations.find(item=>item.id===org);if(current()&&o?.latestSubmission?.id===id)Object.assign(o,{author:s.submittedBy?.name||'—',message:s.replyMessage||'',fileName:s.file?.name,submittedAt:s.submittedAt,submission:s})},
    monthlySession(org){if(!this.monthlySessions[org]){const s=createMonthlySession();s.draft.baseVersion='fixed';s.draft.compareVersion='latest';this.monthlySessions[org]=s}return this.monthlySessions[org]},
    comparisonItems(org){return this.items[org]||[]},
    async loadCalculator(org) {
      const id=this.monthlyRequest?.request.id,current=latestRequest(this,'gap-view:'+org)
      if(this.activeOrg===org)this.currentSubmission=null
      if(!id){this.items[org]=[];return}
      const results=await Promise.all(['실적 vs 계획','추정 vs 계획'].map(type=>this.loadGap(org,type,{background:true})))
      if(!current()||this.monthlyRequest?.request.id!==id)return
      if(results.some(result=>!result)||results[0].submission?.id!==results[1].submission?.id)throw new Error('제출본이 변경되었습니다. 기준을 다시 적용해 주세요.')
      const ref=results[0].submission,submission=ref?await this.api('/api/v1/submissions/'+ref.id):null
      if(!current()||this.monthlyRequest?.request.id!==id)return
      this.items[org]=calculatorRows(results[0].rows,results[1].rows,this.monthlyRequest.request.month)
      this.monthlySession(org).calculatorUnified=true
      if(this.activeOrg===org)this.currentSubmission=submission
    },
    async loadGap(org,type='실적 vs 계획',options={}) {
      if(!options.background&&this.monthlySession(org).calculatorUnified)return this.loadCalculator(org)
      const id=this.monthlyRequest?.request.id
      if(!id){this.items[org]=[];return}
      const current=latestRequest(this,'gap:'+org+':'+type),viewCurrent=options.background?()=>false:latestRequest(this,'gap-view:'+org)
      if(!options.background&&this.activeOrg===org)this.currentSubmission=null
      const p=await this.api(`/api/v1/review-items?view=PLANNER&requestId=${id}&organizationId=${org}&comparison=${comparisons[type]}&tab=ALL`)
      if(!current()||this.monthlyRequest?.request.id!==id)return
      const submission=p.submission&&!options.background?await this.api('/api/v1/submissions/'+p.submission.id):null
      if(!current()||this.monthlyRequest?.request.id!==id)return
      const rows=flattenGapTree(p.tree,type,this.organizations.find(item=>item.id===org)?.name)
      this.itemsByComparison[org]={...this.itemsByComparison[org],[type]:rows}
      if(viewCurrent())this.items[org]=rows
      const session=this.monthlySession(org)
      const all=monthlyReviewItems(this.itemsByComparison[org],this.carts[org],session.applied?.type||type)
      const locked=['APPROVED','REJECTED'].includes(p.submission?.status)
      this.carts[org]=locked?[]:syncWorkspace(this.carts[org]||[],all,{...(session.applied||session.draft),type})
      const organization=this.organizations.find(item=>item.id===org)
      if(organization){organization.workflowStatus=null;organization.explanationStatus=p.submission?.status==='APPROVED'?'소명 승인 완료':p.submission?.status==='REJECTED'?'재제출 요청':monthlyExplanationStatus(all)}
      if(viewCurrent()&&this.activeOrg===org)this.currentSubmission=submission
      return {rows,submission:p.submission}
    },
    async reviewAction(org,rows,action,extra={}){
      if(['APPROVE','EXCLUDE','REQUEST_EXPLANATION'].includes(action)) {
        const request=action==='REQUEST_EXPLANATION'
        await confirmBusinessAction({title:request?'소명 요청을 보낼까요?':'선택 항목을 승인할까요?', confirmLabel:request?'소명 요청 보내기':'항목 승인',
          message:request?'선택한 항목의 소명을 조직 담당자에게 요청합니다.':'선택한 항목을 소명 요청 없이 승인합니다. 제출본 승인은 별도로 처리됩니다.',
          details:[{label:'조직',value:this.organizations.find(o=>o.id===org)?.name||org},{label:'대상',value:rows.length+'개 항목'},...(request?[{label:'회신 기한',value:extra.dueAt?new Date(extra.dueAt).toLocaleString('ko-KR',{timeZone:'Asia/Seoul'}):'—'},{label:'요청 메시지',value:extra.message}]:[])]})
      }
      await this.api('/api/v1/review-items/actions',{method:'POST',body:reviewActionBody(action,rows,extra)});await this.loadGap(org,this.monthlySession(org).applied?.type)},
    async addToCart(org,rows,conditions){const unselected=rows.filter(r=>r.status==='검토 필요');if(unselected.length)await this.reviewAction(org,unselected,'SELECT');const latest=this.items[org]||[];this.carts[org]=[...(this.carts[org]||[]).filter(r=>!rows.some(n=>n.id===r.id)),...latest.filter(r=>rows.some(n=>n.id===r.id)).map(r=>({...r,conditions:{...conditions,type:r.conditions?.type||conditions.type}}))]},
    async excludeItems(org,ids,note){await this.reviewAction(org,(this.items[org]||[]).filter(r=>ids.includes(r.id)),'APPROVE',{note});this.carts[org]=(this.carts[org]||[]).filter(r=>!ids.includes(r.id));this.notify(ids.length+'개 항목을 승인했습니다.')},
    removeCartItem(org,id,note){return this.excludeItems(org,[id],note)},
    async sendExplanation(org,message,due){const rows=(this.carts[org]||[]).filter(canSelectForReview);if(!rows.length)throw new Error('새로 소명을 요청할 항목이 없습니다.');await this.reviewAction(org,rows,'REQUEST_EXPLANATION',{message,dueAt:new Date(due+':00+09:00').toISOString()});await this.loadMonthly();await this.refreshBadge();return true},
    async createRequest(type,organizationIds,due,message,year){
      const annual=type==='ANNUAL_PLAN'
      await confirmBusinessAction({title:annual?'연간 계획 요청을 보낼까요?':'월간 시트 요청을 보낼까요?',confirmLabel:'요청 발송',message:organizationIds.length+'개 조직의 담당자에게 제출 요청을 보냅니다.',details:[{label:'대상 기간',value:annual?(year||seoulPeriod().year)+'년':seoulPeriod().year+'년 '+seoulPeriod().month+'월'},{label:'대상 조직',value:organizationIds.map(id=>this.organizations.find(o=>o.id===id)?.name||id).join(', ')},{label:'회신 기한',value:due.replace('T',' ')},{label:'요청 메시지',value:message}]})
      const p=seoulPeriod();const dueAt=new Date(due+':00+09:00').toISOString();if(type==='MONTHLY_ACTUAL'&&this.monthlyRequest){await this.api('/api/v1/submission-requests/'+this.monthlyRequest.request.id+'/targets',{method:'POST',body:{organizationIds,dueAt,message}})}else{await this.api('/api/v1/submission-requests',{method:'POST',body:{type,year:year||p.year,month:type==='MONTHLY_ACTUAL'?p.month:null,organizationIds,dueAt,message}})};if(type==='MONTHLY_ACTUAL')await this.loadMonthly();else await this.loadAnnual(year)},
    async openDetail(id){const current=latestRequest(this,'detail');this.activeReviewId=id;const r=await Promise.all([this.api('/api/v1/review-items/'+id),this.api('/api/v1/review-items/'+id+'/evidence'),this.api('/api/v1/review-items/'+id+'/versions')]);if(current()&&this.activeReviewId===id){
      [this.reviewDetail,this.evidence,this.versions]=r
      const org=this.activeOrg, source=(this.carts[org]||[]).find(row=>String(row.id)===String(id)) || (this.items[org]||[]).find(row=>String(row.id)===String(id)) || (this.recentCellTarget?.id===id?this.recentCellTarget.item:null)
      const entry={id,organizationId:org,organizationName:this.organizations.find(row=>row.id===org)?.name||'—',name:source?.name||r[0].path,sourceCell:r[0].rows?.[0]?.sourceCell||'',submissionId:this.currentSubmission?.id,item:source?{...source}:{id,name:r[0].path},viewedAt:Date.now()}
      this.recentCells=[entry,...this.recentCells.filter(row=>String(row.id)!==String(id))].slice(0,20)
    }},
    async decideExplanation(id,verdict,note){
      const org=this.activeOrg,type=this.monthlySession(org).applied?.type
      if(this.activeReviewId!==id||!this.reviewDetail?.stateToken)throw new Error('검토 항목을 다시 선택해 주세요.')
      await confirmBusinessAction({title:verdict==='ACCEPT'?'소명을 수락할까요?':'소명 보완을 요청할까요?',confirmLabel:verdict==='ACCEPT'?'소명 수락':'보완 요청',message:verdict==='ACCEPT'?'해당 항목의 소명을 수락합니다. 제출본 승인은 별도로 처리됩니다.':'조직 담당자에게 소명 보완을 요청합니다.',details:note?[{label:'검토 메모',value:note}]:[]})
      await this.api(`/api/v1/review-items/${id}/decisions`,{method:'POST',body:{verdict,note,expectedStateToken:this.reviewDetail.stateToken}})
      if(this.activeReviewId===id)await this.openDetail(id)
      await this.loadGap(org,type,{background:this.activeOrg!==org})
    },
    async downloadSubmission(id,name){saveFile(await this.api(`/api/v1/submissions/${id}/file`,{blob:true}),name||'제출본.xlsx')},
    async loadNotifications(){this.notificationPage=await this.api('/api/v1/notifications?limit=100');this.notifications=this.notificationPage.groups.flatMap(g=>g.items.map(n=>({...n,day:g.date,org:n.organization?.name,code:n.organization?.code,author:n.actor?.name,time:n.occurredAt})));this.unreadCount=this.notificationPage.summary.unreadCount},
    async markAllRead(){await this.api('/api/v1/notifications/read',{method:'POST',body:{all:true}});await this.loadNotifications()},
    async loadAnnual(year=seoulPeriod().year){
      const current=latestRequest(this,'annual')
      const [orgs,rows]=await Promise.all([this.pages('/api/v1/organizations?active=true'),this.pages('/api/v1/submission-requests?type=ANNUAL_PLAN')])
      if(!current())return
      const request=rows.find(row=>row.year===Number(year)),annual=request?await this.api('/api/v1/submission-requests/'+request.id):null
      if(!current())return
      this.organizations=organizationRows(this,orgs);this.activeOrg ||=orgs[0]?.id||''
      this.annualRequest=annual;this.annualRows=annualProgressRows(this.organizations,annual?.targets)
    },
    async markSubmissionNotificationsRead(submissionId){
      const ids=[];let cursor
      do{const page=await this.api('/api/v1/notifications?limit=100'+(cursor?'&cursor='+encodeURIComponent(cursor):''));
        for(const group of page.groups)for(const item of group.items)if(!item.read&&item.type==='SUBMISSION'&&item.link?.query?.submissionId===String(submissionId))ids.push(item.id)
        cursor=page.nextCursor
      }while(cursor)
      if(ids.length)await this.api('/api/v1/notifications/read',{method:'POST',body:{ids}})
      await this.refreshBadge()
    },
    async decideSubmission(s,decision,comment,rejectedReviewItemIds=[],options={}){
      if(!options.confirmed)await confirmBusinessAction({title:decision==='APPROVE'?'제출본을 승인할까요?':'재제출을 요청할까요?',confirmLabel:decision==='APPROVE'?'제출본 승인':'재제출 요청',message:decision==='APPROVE'?'이 제출본을 승인합니다. 모든 대상 조직의 승인이 완료되면 서버 기준에 따라 master가 생성됩니다.':'조직 담당자에게 파일 재제출을 요청합니다.',details:[{label:'조직',value:s.organization?.name||this.organizations.find(o=>o.latestSubmission?.id===s.id)?.name||'—'},{label:'버전',value:s.label||'—'},...(comment?[{label:'코멘트',value:comment}]:[])]})
      const result=await this.api(`/api/v1/submissions/${s.id}/decisions`,{method:'POST',body:{decision,comment:comment||null,rejectedReviewItemIds,expectedStateToken:s.stateToken}});try{await this.markSubmissionNotificationsRead(s.id)}catch{this.notify('제출본 처리는 완료됐지만 알림 읽음 갱신에 실패했습니다. 알림 화면에서 다시 확인해 주세요.','error')};return result},
    async loadInbox(){
      const current=latestRequest(this,'inbox'),[inbox,history]=await Promise.all([this.api('/api/v1/inbox'),this.pages('/api/v1/submissions')])
      if(!current())return
      this.orgHistory=history;this.orgRequests=inbox.items.map((item,index)=>{
        const previous=history.find(submission=>submission.id===item.submissionId)
        return {...item,id:item.kind+':'+item.requestId+':'+(item.submissionId||index),org:inbox.organization.id,
          type:item.kind==='EXPLANATION'?'explanation':'monthly',due:item.dueAt,status:'회신 대기',
          isResubmit:item.kind==='SUBMISSION'&&previous?.status==='REJECTED',reviewComment:previous?.reviewComment||''}
      })
    },
    activateOrgRequest(id){this.activeRequest=id;return !!this.activeOrgRequest},
  }
})
