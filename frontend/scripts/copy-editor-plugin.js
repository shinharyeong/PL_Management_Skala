import { readFile, writeFile, rename, unlink } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

const defaultFile = fileURLToPath(new URL('../src/prototype/ui-copy.json', import.meta.url))
const loopback = host => ['localhost', '127.0.0.1', '[::1]'].includes(host)
export function allowedCopyRequest(req) {
  if (!['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(req.socket?.remoteAddress)) return false
  try {
    const local = new URL('http://' + req.headers.host)
    if (!loopback(local.hostname)) return false
    if (req.headers.origin && new URL(req.headers.origin).origin !== local.origin) return false
    if (req.headers['sec-fetch-site'] === 'cross-site') return false
    return req.method === 'GET' || req.headers.origin === local.origin
  } catch { return false }
}

export function validateCopyChange(copy, body) {
  if (!body || typeof body.key !== 'string' || typeof body.value !== 'string' || typeof body.expected !== 'string') return 'INVALID_CHANGE'
  const parts = body.key.split('.')
  if (parts.length !== 2 || parts.some(p => ['__proto__', 'constructor', 'prototype'].includes(p))) return 'INVALID_KEY'
  const [group, key] = parts
  if (!Object.hasOwn(copy, group) || !Object.hasOwn(copy[group], key) || typeof copy[group][key] !== 'string') return 'INVALID_KEY'
  if (!body.value.trim() || body.value.length > 10000) return 'INVALID_TEXT'
  return copy[group][key] === body.expected ? null : 'STALE_COPY'
}

// Only this fixed JSON file can be written; request data can never choose a file path.
export function createCopyWriter(file = defaultFile) {
  let queue = Promise.resolve()
  return body => {
    const task = queue.then(async () => {
      const copy = JSON.parse(await readFile(file, 'utf8'))
      const error = validateCopyChange(copy, body)
      if (error) return { error, status: error === 'STALE_COPY' ? 409 : 400 }
      const [group, key] = body.key.split('.')
      copy[group][key] = body.value
      const temp = file + '.copy-edit.tmp'
      try {
        await writeFile(temp, JSON.stringify(copy, null, 2) + '\n', { mode: 0o600 })
        await rename(temp, file)
      } finally { await unlink(temp).catch(() => {}) }
      return { status: 200, key: body.key, value: body.value }
    })
    queue = task.catch(() => {})
    return task
  }
}

export function copyEditorPlugin() {
  return { name: 'local-copy-editor', apply: 'serve', configureServer(server) {
    const write = createCopyWriter()
    server.middlewares.use('/__local/ui-copy', async (req, res) => {
      res.setHeader('Content-Type', 'application/json; charset=utf-8')
      res.setHeader('Cache-Control', 'no-store')
      const reply = (status, body) => { res.statusCode = status; res.end(JSON.stringify(body)) }
      if (!allowedCopyRequest(req)) return reply(403, { error: 'LOCAL_ONLY' })
      try {
        if (req.method === 'GET') return reply(200, JSON.parse(await readFile(defaultFile, 'utf8')))
        if (req.method !== 'POST') return reply(405, { error: 'METHOD_NOT_ALLOWED' })
        if (!req.headers['content-type']?.startsWith('application/json')) return reply(415, { error: 'JSON_REQUIRED' })
        const chunks = []; let size = 0
        for await (const chunk of req) {
          size += Buffer.byteLength(chunk)
          if (size > 65536) return reply(413, { error: 'TOO_LARGE' })
          chunks.push(chunk)
        }
        let body
        try { body = JSON.parse(Buffer.concat(chunks.map(c => Buffer.from(c))).toString()) }
        catch { return reply(400, { error: 'INVALID_JSON' }) }
        const result = await write(body)
        return reply(result.status, result)
      } catch { return reply(500, { error: 'SAVE_FAILED' }) }
    })
  } }
}
