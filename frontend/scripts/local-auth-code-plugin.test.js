import test from 'node:test'
import assert from 'node:assert/strict'
import { findLocalCode, localAuthCodePlugin } from './local-auth-code-plugin.js'
const line = (email,code,time='2026-11-24T12:00:00.100+09:00') => `${time} WARN AuthMailSenderConfig : [개발용] 인증코드 email=${email} code=${code}`
test('shows only the code matching the requested email and issuance window',()=>{
 const expires='2026-11-24T03:03:00.100Z'
 const log=[line('other@sk.com','999999'),line('kim.plan@sk.com','012345')].join('\n')
 assert.equal(findLocalCode(log,'kim.plan@sk.com',expires),'012345')
 assert.equal(findLocalCode(log,'missing@sk.com',expires),null)
 assert.equal(findLocalCode(log,'kim.plan@sk.com','2026-11-24T03:04:00.100Z'),null)
 assert.equal(findLocalCode(log+'\n'+line('kim.plan@sk.com','654321','2026-11-24T12:01:00.100+09:00'),'kim.plan@sk.com',expires),null)
})
test('only registers for explicit local development configuration',()=>{
 let registrations=0
 const server={middlewares:{use(){registrations++}}}
 localAuthCodePlugin({apiTarget:'http://127.0.0.1:18183'}).configureServer(server)
 localAuthCodePlugin({logPath:'/tmp/test.log',apiTarget:'https://example.com'}).configureServer(server)
 assert.equal(registrations,0)
 const plugin=localAuthCodePlugin({logPath:'/tmp/test.log',apiTarget:'http://127.0.0.1:18183'})
 assert.equal(plugin.apply,'serve');plugin.configureServer(server);assert.equal(registrations,1)
})
