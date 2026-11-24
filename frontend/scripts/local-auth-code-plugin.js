import { open } from 'node:fs/promises'

export function findLocalCode(log, email, expiresAt) {
  const expiry = Date.parse(expiresAt)
  for (const line of log.split('\n').reverse()) {
    const match = line.match(/^(\S+).*\[개발용\] 인증코드 email=(\S+) code=(\d{6})(?:\s|$)/)
    if (!match || match[2] !== email) continue
    const issued = Date.parse(match[1])
    return issued >= expiry - 182000 && issued <= expiry - 178000 ? match[3] : null
  }
  return null
}

const loopback = address => ['127.0.0.1','::1','::ffff:127.0.0.1'].includes(address)
export function localAuthCodePlugin({ logPath, apiTarget }) {
  return { name: 'local-auth-code', apply: 'serve', configureServer(server) {
    if (!logPath || !apiTarget || !['127.0.0.1','localhost','[::1]'].includes(new URL(apiTarget).hostname)) return
    server.middlewares.use('/__local/auth-code', async (req, res) => {
      res.setHeader('Cache-Control', 'no-store')
      res.setHeader('Content-Type', 'application/json')
      const reply = (status, data) => { res.statusCode = status; res.end(JSON.stringify(data)) }
      const host = req.headers.host
      if (req.method !== 'POST' || !loopback(req.socket.remoteAddress) ||
          !host || !['127.0.0.1','localhost','[::1]'].includes(new URL('http://' + host).hostname) ||
          req.headers.origin !== 'http://' + host || !req.headers['content-type']?.startsWith('application/json')) {
        reply(403, { message: '로컬 개발 화면에서만 확인할 수 있습니다.' }); return
      }
      let handle
      try {
        let body = ''
        for await (const chunk of req) { body += chunk; if (body.length > 2048) { reply(413, {}); return } }
        const { email, expiresAt } = JSON.parse(body)
        if (typeof email !== 'string' || typeof expiresAt !== 'string') { reply(400, {}); return }
        handle = await open(logPath, 'r')
        const { size } = await handle.stat(), length = Math.min(size, 262144), buffer = Buffer.alloc(length)
        await handle.read(buffer, 0, length, size - length)
        const code = findLocalCode(buffer.toString('utf8'), email, expiresAt)
        reply(code ? 200 : 404, code ? { code } : { message: '발급된 인증코드를 확인하지 못했습니다.' })
      } catch { reply(503, { message: '로컬 인증코드 표시 설정을 확인해 주세요.' }) }
      finally { await handle?.close() }
    })
  } }
}
