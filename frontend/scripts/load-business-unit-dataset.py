"""Load the supplied BusinessUnits v2 files through real APIs into the dedicated local test DB.

No original workbook or fixture is changed. Auth codes and tokens stay in memory.
Requires the separately bootstrapped pl_business_units_v2 DB and --apply.
"""
import argparse
import hashlib
import importlib.util
import json
import re
import sys
import time
import unicodedata
import uuid
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path


def module(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    sys.modules[name] = value
    spec.loader.exec_module(value)
    return value


def main(args):
    assert (args.apply or args.verify_only) and args.base_url == 'http://127.0.0.1:18183', 'dedicated BusinessUnits local server required'
    helpers = module(Path(__file__).with_name('verify-monthly-api.py'), 'business_unit_http')
    files = {unicodedata.normalize('NFC', str(p.relative_to(args.source))): p for p in args.source.rglob('*') if p.is_file()}
    manifest = json.loads((args.source/'manifest.json').read_text())
    assert manifest['organization_count'] == 17
    digests = {entry['file']: entry['sha256'] for entry in manifest['files']}
    def source(name):
        p = files[unicodedata.normalize('NFC', name)]
        assert hashlib.sha256(p.read_bytes()).hexdigest() == digests[name], 'source hash mismatch'
        return p
    for name in digests:
        source(name)
    organizations = json.loads((args.source/'02_test_support/organizations.json').read_text())

    class Api(helpers.Api):
        def session(self, role, code=None):
            if code in self.tokens:
                return self.tokens[code]
            email = 'kim.plan@sk.com' if code is None else code.lower()+'.test@sk.com'
            offset = self.local_auth_log.stat().st_size
            result = self.http.call(self.base, '/api/v1/auth/codes', method='POST',
                content_type='application/json', body=json.dumps({'email':email}).encode())
            assert result.status == 202, 'test login code request failed'
            match = None
            for _ in range(30):
                with self.local_auth_log.open('rb') as log:
                    log.seek(offset)
                    match = re.search(r'email='+re.escape(email)+r' code=(\d{6})', log.read().decode(errors='replace'))
                if match: break
                time.sleep(.1)
            assert match, 'local code not observed'
            result = self.http.call(self.base, '/api/v1/auth/sessions', method='POST',
                content_type='application/json', body=json.dumps({'email':email,'code':match.group(1)}).encode())
            assert result.status == 201, 'test login failed'
            data = json.loads(result.body)
            assert data['me']['role'] == role
            self.tokens[code] = data['token']
            return data['token']

        def upload_file(self, request_id, name, token):
            p = source(name)
            payload = p.read_bytes()
            boundary = 'businessunits-'+uuid.uuid4().hex
            response = self.http.call(self.base, f'/api/v1/submission-requests/{request_id}/submissions',
                method='POST', token=token, body=self.http.multipart(p.name, payload, boundary),
                content_type='multipart/form-data; boundary='+boundary, key='bu2-'+uuid.uuid4().hex)
            assert response.status == 202, f'upload failed {p.name}: HTTP {response.status}'
            sid = json.loads(response.body)['id']
            for _ in range(600):
                detail = self.submission(sid,token)
                job = detail['parsingJob']
                if job['status'] not in ('QUEUED','RUNNING'):
                    if job['status'] != 'SUCCEEDED' or job.get('errorCode'):
                        diagnostics = self.request(f'/submissions/{sid}/diagnostics',token)
                        print('DIAGNOSTICS',p.name,json.dumps(diagnostics,ensure_ascii=False)[:1800],flush=True)
                        raise AssertionError(f'parser/validation rejected {p.name}: {job["status"]} {job.get("errorCode")}')
                    break
                time.sleep(.3)
            else:
                raise AssertionError('parsing timeout '+p.name)
            response = self.http.call(self.base,f'/api/v1/submissions/{sid}/file',token=token)
            assert response.status == 200 and hashlib.sha256(response.body).hexdigest() == digests[name], 'stored original differs'
            return sid

    api = Api(args.base_url,args.repository,args.local_auth_log)
    server_orgs = api.request('/organizations?active=true&limit=100')['items']
    assert {o['organization_code'] for o in organizations}.issubset({o['code'] for o in server_orgs}), 'organization mismatch'
    server_by_code = {o['code']:o for o in server_orgs}
    org_ids = [o['id'] for o in server_orgs]
    marker = 'BusinessUnits v2 화면 검증 데이터'
    existing = api.request('/submission-requests?limit=100')['items']
    if not args.annual_pending_only:
        assert all(r.get('message') == marker for r in existing), 'unrelated requests present; refusing changes'
    if existing and not (args.resume or args.verify_only):
        raise AssertionError('use --resume for this dataset only')
    due = (datetime.now(timezone.utc)+timedelta(days=7)).isoformat()
    def request(kind,month=None):
        r=next((r for r in existing if r['type']==kind and r['year']==2026 and r.get('month')==month),None)
        if r:return r
        r=api.request('/submission-requests',method='POST',status=201,body={
            'type':kind,'year':2026,'month':month,'dueAt':due,'message':marker,'organizationIds':org_ids})
        existing.append(r)
        return r

    def process(req, o, name, approve=True, revision=False):
        code=o['organization_code'];token=api.session('ORG_MANAGER',code)
        found=api.request(f'/submissions?requestId={req["id"]}&organizationId={server_by_code[code]["id"]}&limit=100')['items']
        found=sorted(found,key=lambda s:int(s['id']))
        if revision:
            sid=found[-1]['id'] if len(found)>=2 else api.upload_file(req['id'],name,token)
        else:
            sid=found[-1]['id'] if found else api.upload_file(req['id'],name,token)
            if found and api.submission(sid,token)['parsingJob'].get('errorCode'):
                sid=api.upload_file(req['id'],name,token)
        s=api.submission(sid,token)
        if s['status']=='DRAFT':
            api.request(f'/submissions/{sid}/validation',token,method='POST',body={'expectedStateToken':s['stateToken']})
            page=api.request(f'/review-items?view=ORG&submissionId={sid}',token)
            for item in helpers.org_review_items(page):
                if item['answerState']!='EMPTY':continue
                rid=item['reviewItemId'];detail=api.request(f'/review-items/{rid}',token)
                reason='화면 검증용 기준 데이터입니다.' if req['month']!=11 else '화면 검증용 더미 소명입니다. '+o['reason']
                api.request(f'/review-items/{rid}/answer',token,method='PUT',body={'expectedStateToken':detail['stateToken'],'answer':reason})
            preview=api.request(f'/submissions/{sid}/submit-preview',token)
            assert preview['canSubmit'], 'submit prerequisites incomplete'
            api.request(f'/submissions/{sid}/submit',token,method='POST',body={'expectedStateToken':preview['stateToken'],'replyMessage':marker})
        s=api.submission(sid)
        if approve and s['status']=='PENDING':
            helpers.accept_and_approve(api,sid)
        final=api.submission(sid)
        assert final['status']==('APPROVED' if approve else 'PENDING')
        print(f'PASS {req["type"]} {req.get("month") or "PLAN"} {code} {final["status"]} {final["label"]}',flush=True)
        return sid

    if args.annual_pending_only:
        annuals=[r for r in existing if r['type']=='ANNUAL_PLAN' and r['year']==2026]
        assert len(annuals)==1, 'one existing 2026 annual request required'
        annual=annuals[0]
        targets=api.request('/submission-requests/'+annual['id'])['targets']
        selected={t['organization']['code'] for t in targets if t['selected'] and not t.get('excluded',False)}
        assert selected.issubset({o['organization_code'] for o in organizations}), 'selected organization missing from dataset'
        for o in organizations:
            code=o['organization_code']
            if code not in selected:continue
            found=api.request(f'/submissions?requestId={annual["id"]}&organizationId={server_by_code[code]["id"]}&limit=100')['items']
            if any(s['status'] in ('APPROVED','PENDING') for s in found):
                print('KEEP existing submitted or approved '+code,flush=True);continue
            process(annual,o,o['plan_file'],approve=False)
        for name in digests:source(name)
        print('PASS annual targets submitted; existing approvals and original files preserved',flush=True)
        return

    def verify_display():
        expected=json.loads((args.source/'02_test_support/expected_financial_values.json').read_text())
        # The first approved files of 15/17 intentionally precede their final fixture.
        # Replace only their expected ACTUAL cells using their original first-file data.
        sys.path.insert(0,str(args.repository/'parser/src'))
        from excel_parser.service_bridge import parse_bytes
        first_actual={}
        for o in organizations:
            if o['organization'] not in (15,17):continue
            p=source(o['first_submission_file'])
            data=parse_bytes(p.read_bytes(),p.name)['submissionData']
            for line in data['lines']:
                for v in line['values']:
                    if v['year']==2026 and v['scenario']=='ACTUAL' and v['valueStatus']=='AVAILABLE':
                        key=(o['organization_code'],v['month'],line['domain'])
                        first_actual[key]=first_actual.get(key,Decimal(0))+Decimal(v['amount'])
        for period in ('MONTH','YEAR'):
            q=f'year=2026&month=11&period={period}'
            totals={'REVENUE':Decimal(0),'COST':Decimal(0)}
            for row in expected:
                if row['scenario']!='ACTUAL' or period=='MONTH' and row['month']!=11 or period=='YEAR' and row['month']>11:continue
                for kind,field in [('REVENUE','revenue_won'),('COST','cost_won')]:
                    totals[kind]+=first_actual.get((row['organization_code'],row['month'],kind),Decimal(row[field]))
            totals['OP']=totals['REVENUE']-totals['COST']
            for path in ('pnl','divisions','waterfall','op-drivers'):
                data=api.request('/home/'+path+'?'+q)
                assert data['dataStatus']=='CONFIRMED', 'approved home snapshot missing'
                if path=='pnl':
                    for kpi in data['kpis']:
                        assert Decimal(kpi['actual'])==totals[kpi['key']],f'home {period} {kpi["key"]} differs from source expectation'
                if path=='divisions':assert len(data['columns'])==17, 'all divisions shown'
            print('PASS '+period+' home amounts equal dataset expectations and approved source files',flush=True)

    if args.verify_only:
        verify_display()
        print('PASS read-only dataset view verification',flush=True)
        return

    annual=request('ANNUAL_PLAN')
    for o in organizations:
        process(annual,o,o['plan_file'])
    print('PASS annual plan confirmed through actual approvals',flush=True)
    # Explicit test settings; >= is implemented by the current backend and is the user's selected rule.
    settings=json.loads((args.source/'02_test_support/test_settings.json').read_text())
    for comparison,scenario in [('ACTUAL_VS_PLAN','ACTUAL'),('ESTIMATE_VS_PREVIOUS','ESTIMATE'),('ESTIMATE_VS_PLAN','ESTIMATE')]:
        current=api.request('/gap-thresholds/current')
        value=settings['thresholds'][scenario]
        api.request('/gap-thresholds',method='POST',status=201,body={'comparison':comparison,'percentEnabled':True,
            'percent':value['percent'],'amountEnabled':True,'amount':value['amount_won'],'expectedStateToken':current['stateToken']})
    october=request('MONTHLY_ACTUAL',10)
    for o in organizations:
        process(october,o,o['previous_month_file'])
    print('PASS October baseline master through actual approvals',flush=True)
    november=request('MONTHLY_ACTUAL',11)
    for o in organizations:
        name=o['first_submission_file'] if o['organization'] in (15,17) else o['upload_file']
        process(november,o,name)
    print('PASS November approved master, all 17 organizations',flush=True)
    # Leave the real second F66 file pending so the calculator, cart and review screens have work to do.
    o=next(o for o in organizations if o['organization']==15)
    process(november,o,o['upload_file'],approve=False,revision=True)
    print('PASS organization 15 latest resubmission pending; approved master remains a separate baseline',flush=True)
    verify_display()
    for name in digests:source(name)
    print('PASS all input workbook hashes preserved and home endpoints populated',flush=True)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source',type=Path,required=True)
    p.add_argument('--repository',type=Path,required=True)
    p.add_argument('--local-auth-log',type=Path,required=True)
    p.add_argument('--base-url',default='http://127.0.0.1:18183')
    p.add_argument('--apply',action='store_true')
    p.add_argument('--annual-pending-only',action='store_true')
    p.add_argument('--resume',action='store_true')
    p.add_argument('--verify-only',action='store_true')
    args=p.parse_args()
    try:main(args)
    except Exception as error:
        print('FAIL',str(error) if isinstance(error,AssertionError) else type(error).__name__,flush=True)
        sys.exit(1)
