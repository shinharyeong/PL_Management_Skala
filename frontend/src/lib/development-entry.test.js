import test from 'node:test'
import assert from 'node:assert/strict'
import {isDevelopmentEntry} from './development-entry.js'
test('dev path works independently of build mode; normal and lookalike URLs stay ordinary',()=>{
  assert.equal(isDevelopmentEntry('/dev'),true);assert.equal(isDevelopmentEntry('/dev/'),true)
  for(const path of ['/','/prototype.html','/development','/devil','/dev/accounts']) assert.equal(isDevelopmentEntry(path),false)
})
