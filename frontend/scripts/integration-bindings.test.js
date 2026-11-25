import test from 'node:test'
import assert from 'node:assert/strict'
import { readdirSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc'

test('merged active screens have no template references lost during the PrimeVue merge', () => {
  const directory = new URL('../src/prototype/', import.meta.url)
  for (const name of readdirSync(directory).filter(name => name.endsWith('.vue') && /Live|Monthly|PrototypeApp|FeedbackToast|CopyEditor/.test(name))) {
    const filename = fileURLToPath(new URL(name, directory))
    const { descriptor } = parse(readFileSync(filename, 'utf8'))
    const script = compileScript(descriptor, { id: name })
    const template = compileTemplate({ source: descriptor.template.content, filename, id: name, compilerOptions: { bindingMetadata: script.bindings } })
    assert.deepEqual(template.errors, [], name)
    const missing = [...new Set([...template.code.matchAll(/_ctx\.([A-Za-z_$][\w$]*)/g)].map(match => match[1]))]
    assert.deepEqual(missing, [], `${name}: missing setup bindings`)
  }
})
