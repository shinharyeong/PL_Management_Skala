import importlib.util
import unittest
from copy import deepcopy
from pathlib import Path

spec=importlib.util.spec_from_file_location('dataset_scope',Path(__file__).with_name('dataset-scope-python.py'))
scope=importlib.util.module_from_spec(spec)
spec.loader.exec_module(scope)


class DatasetScopeTest(unittest.TestCase):
    def setUp(self):
        self.values=[dict(year=y,month=m,scenario=s,amount='9007199254740993.123456',
                          sourceCell='AV34',valueStatus='AVAILABLE')
                     for y in (2025,2026,2027) for m in (10,11,12) for s in ('PLAN','ACTUAL','ESTIMATE')]
        self.original={'records':['full-original-records'], 'submissionData':{
            'sourceSha256':'original-hash','diagnostics':[{'severity':'ERROR','code':'DO_NOT_HIDE'}],
            'lines':[{'values':self.values}]}}

    def test_annual_selects_only_requested_year_plan(self):
        result=scope.scope_submission(self.original,2026,None)
        values=result['submissionData']['lines'][0]['values']
        self.assertEqual(len(values),3)
        self.assertTrue(all(v['year']==2026 and v['scenario']=='PLAN' for v in values))

    def test_monthly_selects_plan_past_actual_and_future_estimate(self):
        values=scope.scope_submission(self.original,2026,11)['submissionData']['lines'][0]['values']
        self.assertEqual(len(values),6)
        self.assertTrue(all(v['year']==2026 and (v['scenario']=='PLAN' or
            v['scenario']=='ACTUAL' and v['month']<=11 or v['scenario']=='ESTIMATE' and v['month']>11)
            for v in values))

    def test_scope_keeps_precise_amount_source_hash_diagnostics_and_original(self):
        before=deepcopy(self.original)
        result=scope.scope_submission(self.original,2026,11)
        self.assertEqual(self.original,before)
        self.assertEqual(result['records'],before['records'])
        self.assertEqual(result['submissionData']['sourceSha256'],'original-hash')
        self.assertEqual(result['submissionData']['diagnostics'],before['submissionData']['diagnostics'])
        self.assertTrue(all(v['amount']=='9007199254740993.123456' and v['sourceCell']=='AV34'
            for v in result['submissionData']['lines'][0]['values']))

    def test_missing_amount_stays_missing(self):
        missing=next(v for v in self.values if v['year']==2026 and v['month']==10 and v['scenario']=='PLAN')
        missing['amount']=None
        missing['valueStatus']='MISSING'
        result=scope.scope_submission(self.original,2026,None)
        self.assertIsNone(result['submissionData']['lines'][0]['values'][0]['amount'])


if __name__=='__main__':
    unittest.main()
