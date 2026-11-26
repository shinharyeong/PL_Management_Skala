import { defineStore } from 'pinia'
import { createMonthlySession, seoulPeriod } from './domain.js'
import { createApiClient, comparisons, flattenGapTree, status, saveFile } from './live-api.js'
import { useSession } from '../stores/session.js'
import { annualProgressRows } from './review-status.js'
import { monthlyProgress } from './monthly-progress.js'
import {syncWorkspace,needsProcessing} from './workspace-items.js'
import {canSelectForReview} from './calculator-stages.js'
import {reviewActionBody} from '../lib/review-api.js'

let client
export const usePrototypeStore = defineStore('connected-frontend', {
  state: () => ({ live:true, actor:null, token:'', error:'', busy:false, loading:false, unreadCount:0, toast:null, toastSequence:0,
    organizations:[], notifications:[], orgRequests:[], orgHistory:[], items:{}, carts:{}, monthlySessions:{},
    monthlyLoaded:false, activeOrg:'', activeRequest:'', monthlyRequest:null, annualRequest:null, requestOptions:[], orgReasons:{},
    notificationPage:null, notificationTarget:null, annualRows:[], activeReviewId:null, reviewDetail:null, evidence:null, versions:null, currentSubmission:null }),
  getters:{ unread:s=>s.unreadCount, currentOrg:s=>s.organizations.find(o=>o.id===s.activeOrg)||{ name:'조직을 선택해 주세요',code:'—' },
    workspaces:s=>Object.entries(s.carts).map(([org,rows])=>({org,name:s.organizations.find(o=>o.id===org)?.name||org,count:rows.filter(needsProcessing).length})).filter(work=>work.count>0),
    visibleOrgRequests:s=>s.orgRequests, activeOrgRequest:s=>s.orgRequests.find(r=>r.id===s.activeRequest)||null,
    explanationItems:s=>s.items[s.actor?.orgId]||[] },
  actions:{
    notify(message,tone='info'){if(message)this.toast={id:++this.toastSequence,message:String(message),tone}},
    dismissToast(id){if(this.toast?.id===id)this.toast=null},
    api(path,options) { client ||= createApiClient({token:()=>this.token,clear:()=>this.clear()}); return client(path,options) },
    clear(){ useSession().clear(); this.$reset(); client=null },
    async run(fn){ if(this.busy)return false; this.busy=true;this.error='';try{await fn();return true}catch(e){this.error=[e.message,e.code,e.requestId&&`요청 ID: ${e.requestId}`].filter(Boolean).join(' · ');this.notify(this.error,'error');return false}finally{this.busy=false} },
    async pages(path){const rows=[];let cursor;do{const p=await this.api(path+(path.includes('?')?'&':'?')+'limit=100'+(cursor?'&cursor='+encodeURIComponent(cursor):''));rows.push(...(p.items||[]));cursor=p.nextCursor}while(cursor);return rows},
    requestCode(email){return this.api('/api/v1/auth/codes',{method:'POST',body:{email}})},
    syncSession(session){const m=session.actor;this.token=session.token;this.actor={id:m.id,name:m.name,email:m.email,role:m.role==='PLANNER'?'planner':'org',orgId:m.organization?.id,orgCode:m.organization?.code,department:m.organization?.name||'경영기획팀'}},
    async restoreLogin(){const session=useSession();if(!await session.restore())return false;this.syncSession(session);return true},
    developmentAccounts(accessKey){return this.api('/api/v1/dev/accounts',{headers:{'X-Dev-Access-Key':accessKey}})},
    async developmentLogin(email,accessKey){const result=await this.api('/api/v1/dev/sessions',{method:'POST',body:{email},headers:{'X-Dev-Access-Key':accessKey}});const session=useSession();const saved=session.accept(result);this.syncSession(session);if(!saved)this.notify('이 브라우저에서는 새로고침 후 다시 계정을 선택해야 합니다.','error')},
    async login(email,code){const r=await this.api('/api/v1/auth/sessions',{method:'POST',body:{email,code}});const session=useSession();const saved=session.accept(r);this.syncSession(session);if(!saved)this.notify('이 브라우저에서는 로그인 유지 정보를 저장할 수 없습니다. 새로고침하면 다시 로그인해야 합니다.','error')},
    async logout(){await this.api('/api/v1/auth/session',{method:'DELETE'});this.clear()},
    async refreshBadge(){if(this.actor?.role==='planner'){const r=await this.api('/api/v1/me/badges');this.unreadCount=r.unreadNotifications}},
    async loadOrganizations(){const rows=await this.pages('/api/v1/organizations?active=true');this.organizations=rows.map(o=>({...o,author:o.managers?.map(m=>m.name).join(', ')||'—',department:o.name,submissionStatus:'요청 필요',explanationStatus:'—'}));this.activeOrg ||=rows[0]?.id||''},
    async loadMonthly(){this.monthlyLoaded=false;const p=seoulPeriod(); const rows=await this.pages('/api/v1/submission-requests?type=MONTHLY_ACTUAL');this.requestOptions=rows;const r=rows.find(r=>r.year===p.year&&r.month===p.month);this.monthlyRequest=r?await this.api('/api/v1/submission-requests/'+r.id):null;await this.loadOrganizations();const targets=this.monthlyRequest?.targets||[];this.organizations=this.organizations.map(o=>({...o,...monthlyProgress(targets.find(t=>t.organization.id===o.id))}));this.monthlyLoaded=true;if(this.actor?.role==='planner')await Promise.all(this.organizations.filter(o=>o.latestSubmission && (o.latestSubmission.status==='PENDING'||this.carts[o.id]?.length)).map(o=>this.loadGap(o.id,this.monthlySession(o.id).applied?.type||'실적 vs 계획',{background:true})))},
    async reply(org){const o=this.organizations.find(o=>o.id===org);if(!o?.latestSubmission)return;const s=await this.api('/api/v1/submissions/'+o.latestSubmission.id);Object.assign(o,{author:s.submittedBy?.name||'—',message:s.replyMessage||'',fileName:s.file?.name,submittedAt:s.submittedAt,submission:s})},
    monthlySession(org){if(!this.monthlySessions[org]){const s=createMonthlySession();s.draft.baseVersion='fixed';s.draft.compareVersion='latest';this.monthlySessions[org]=s}return this.monthlySessions[org]},
    comparisonItems(org){return this.items[org]||[]},
    async loadGap(org,type='실적 vs 계획',options={}) {
      const id=this.monthlyRequest?.request.id
      if(!id){this.items[org]=[];return}
      const p=await this.api(`/api/v1/review-items?view=PLANNER&requestId=${id}&organizationId=${org}&comparison=${comparisons[type]}&tab=ALL`)
      this.items[org]=flattenGapTree(p.tree,type,this.organizations.find(item=>item.id===org)?.name)
      const session=this.monthlySession(org)
      this.carts[org]=syncWorkspace(this.carts[org]||[],this.items[org],{...(session.applied||session.draft),type})
      if(p.submission && !options.background)this.currentSubmission=await this.api('/api/v1/submissions/'+p.submission.id)
    },
    async reviewAction(org,rows,action,extra={}){await this.api('/api/v1/review-items/actions',{method:'POST',body:reviewActionBody(action,rows,extra)});await this.loadGap(org,this.monthlySession(org).applied?.type)},
    async addToCart(org,rows,conditions){const unselected=rows.filter(r=>r.status==='검토 필요');if(unselected.length)await this.reviewAction(org,unselected,'SELECT');const latest=this.items[org]||[];this.carts[org]=[...(this.carts[org]||[]).filter(r=>!rows.some(n=>n.id===r.id)),...latest.filter(r=>rows.some(n=>n.id===r.id)).map(r=>({...r,conditions:{...conditions}}))]},
    async excludeItems(org,ids,note){await this.reviewAction(org,(this.items[org]||[]).filter(r=>ids.includes(r.id)),'EXCLUDE',{note});this.carts[org]=(this.carts[org]||[]).filter(r=>!ids.includes(r.id))},
    removeCartItem(org,id,note){return this.excludeItems(org,[id],note)},
    async sendExplanation(org,message,due){const rows=(this.carts[org]||[]).filter(canSelectForReview);if(!rows.length)throw new Error('새로 소명을 요청할 항목이 없습니다.');await this.reviewAction(org,rows,'REQUEST_EXPLANATION',{message,dueAt:new Date(due+':00+09:00').toISOString()});await this.loadMonthly();await this.refreshBadge();return true},
    async createRequest(type,organizationIds,due,message,year){const p=seoulPeriod();const dueAt=new Date(due+':00+09:00').toISOString();if(type==='MONTHLY_ACTUAL'&&this.monthlyRequest){await this.api('/api/v1/submission-requests/'+this.monthlyRequest.request.id+'/targets',{method:'POST',body:{organizationIds,dueAt,message}})}else{await this.api('/api/v1/submission-requests',{method:'POST',body:{type,year:year||p.year,month:type==='MONTHLY_ACTUAL'?p.month:null,organizationIds,dueAt,message}})};if(type==='MONTHLY_ACTUAL')await this.loadMonthly();else await this.loadAnnual(year)},
    async openDetail(id){this.activeReviewId=id;const r=await Promise.all([this.api('/api/v1/review-items/'+id),this.api('/api/v1/review-items/'+id+'/evidence'),this.api('/api/v1/review-items/'+id+'/versions')]);if(this.activeReviewId===id)[this.reviewDetail,this.evidence,this.versions]=r},
    async decideExplanation(id,verdict,note){await this.api(`/api/v1/review-items/${id}/decisions`,{method:'POST',body:{verdict,note,expectedStateToken:this.reviewDetail.stateToken}});await this.openDetail(id);await this.loadGap(this.activeOrg,this.monthlySession(this.activeOrg).applied?.type)},
    async downloadSubmission(id,name){saveFile(await this.api(`/api/v1/submissions/${id}/file`,{blob:true}),name||'제출본.xlsx')},
    async loadNotifications(){this.notificationPage=await this.api('/api/v1/notifications?limit=100');this.notifications=this.notificationPage.groups.flatMap(g=>g.items.map(n=>({...n,day:g.date,org:n.organization?.name,code:n.organization?.code,author:n.actor?.name,time:n.occurredAt})));this.unreadCount=this.notificationPage.summary.unreadCount},
    async markAllRead(){await this.api('/api/v1/notifications/read',{method:'POST',body:{all:true}});await this.loadNotifications()},
    async loadAnnual(year=seoulPeriod().year){await this.loadOrganizations();const rows=await this.pages('/api/v1/submission-requests?type=ANNUAL_PLAN');const r=rows.find(r=>r.year===Number(year));this.annualRequest=r?await this.api('/api/v1/submission-requests/'+r.id):null;this.annualRows=annualProgressRows(this.organizations,this.annualRequest?.targets)},
    async markSubmissionNotificationsRead(submissionId){
      const ids=[];let cursor
      do{const page=await this.api('/api/v1/notifications?limit=100'+(cursor?'&cursor='+encodeURIComponent(cursor):''));
        for(const group of page.groups)for(const item of group.items)if(!item.read&&item.type==='SUBMISSION'&&item.link?.query?.submissionId===String(submissionId))ids.push(item.id)
        cursor=page.nextCursor
      }while(cursor)
      if(ids.length)await this.api('/api/v1/notifications/read',{method:'POST',body:{ids}})
      await this.refreshBadge()
    },
    async decideSubmission(s,decision,comment,rejectedReviewItemIds=[]){const result=await this.api(`/api/v1/submissions/${s.id}/decisions`,{method:'POST',body:{decision,comment:comment||null,rejectedReviewItemIds,expectedStateToken:s.stateToken}});try{await this.markSubmissionNotificationsRead(s.id)}catch{this.notify('제출본 처리는 완료됐지만 알림 읽음 갱신에 실패했습니다. 알림 화면에서 다시 확인해 주세요.','error')};return result},
    async loadInbox(){const r=await this.api('/api/v1/inbox');this.orgRequests=r.items.map((i,index)=>({...i,id:i.kind+':'+i.requestId+':'+(i.submissionId||index),org:r.organization.id,type:i.kind==='EXPLANATION'?'explanation':'monthly',due:i.dueAt,status:'회신 대기'}));this.orgHistory=await this.pages('/api/v1/submissions');this.orgRequests=this.orgRequests.map(item=>{const previous=this.orgHistory.find(s=>s.id===item.submissionId);return {...item,isResubmit:item.kind==='SUBMISSION'&&previous?.status==='REJECTED',reviewComment:previous?.reviewComment||''}})},
    activateOrgRequest(id){this.activeRequest=id;return !!this.activeOrgRequest},
  }
})
