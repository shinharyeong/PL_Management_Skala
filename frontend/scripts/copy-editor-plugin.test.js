import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { copyEditorPlugin, createCopyWriter } from './copy-editor-plugin.js'

async function fixture(fn) {
  const dir = await mkdtemp(join(tmpdir(), 'ui-copy-test-'))
  const file = join(dir, 'copy.json')
  await writeFile(file, JSON.stringify({ pages: { home: '전사 손익', monthly: '월간 관리' } }))
  try { await fn(file) } finally { await rm(dir, { recursive: true, force: true }) }
}
test('saves one approved key to disk; undo preserves all other phrases', () => fixture(async file => {
  const write = createCopyWriter(file)
  assert.equal((await write({ key: 'pages.home', expected: '전사 손익', value: '손익 현황' })).status, 200)
  assert.deepEqual(JSON.parse(await readFile(file, 'utf8')), { pages: { home: '손익 현황', monthly: '월간 관리' } })
  assert.equal((await write({ key: 'pages.home', expected: '손익 현황', value: '전사 손익' })).status, 200)
}))
test('concurrent stale writes conflict rather than overwrite the newer text', () => fixture(async file => {
  const write = createCopyWriter(file)
  const results = await Promise.all(['수정 A', '수정 B'].map(value => write({ key: 'pages.home', expected: '전사 손익', value })))
  assert.deepEqual(results.map(r => r.status), [200, 409])
  assert.equal(JSON.parse(await readFile(file, 'utf8')).pages.home, '수정 A')
}))
test('rejects unknown keys, prototype paths, non-text data, empty and oversized edits', () => fixture(async file => {
  const write = createCopyWriter(file), before = await readFile(file, 'utf8')
  for (const body of [null, {}, { key: '__proto__.x', expected: '', value: 'x' },
    { key: 'pages.missing', expected: '', value: 'x' },
    ...[3, {}, '', '  ', 'a'.repeat(10001)].map(value => ({ key: 'pages.home', expected: '전사 손익', value }))]) {
    assert.equal((await write(body)).status, 400)
  }
  assert.equal(await readFile(file, 'utf8'), before)
}))
test('local-only middleware refuses remote hosts and cross-origin mutation', async () => {
  let handler
  const plugin = copyEditorPlugin()
  assert.equal(plugin.apply, 'serve')
  plugin.configureServer({ middlewares: { use(path, fn) { assert.equal(path, '/__local/ui-copy'); handler = fn } } })
  for (const [address, host, origin] of [
    ['10.0.0.2', 'localhost:14175', 'http://localhost:14175'],
    ['127.0.0.1', 'evil.example', 'http://evil.example'],
    ['127.0.0.1', 'localhost:14175', 'http://evil.example'],
  ]) {
    const req = Readable.from(['{}'])
    req.method = 'POST'; req.socket = { remoteAddress: address }
    req.headers = { host, origin, 'content-type': 'application/json' }
    const res = { setHeader() {}, end() {} }
    await handler(req, res)
    assert.equal(res.statusCode, 403)
  }
})
