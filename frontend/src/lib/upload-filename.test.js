import test from 'node:test'
import assert from 'node:assert/strict'
import { inspectUploadFilename, preserveWorkbookExtension } from './upload-filename.js'

/** XLSB를 XLSX로 잘못 이름 붙이지 않고 요청 기간·조직을 검증한다.
 * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음.
 */
test('binary workbooks preserve their format during filename consent', function binaryFilename() {
  assert.equal(preserveWorkbookExtension('F10_2026-11.xlsb', 'F10_2026-11.xlsx'), 'F10_2026-11.xlsb')
  assert.equal(inspectUploadFilename('F10_2026-11.xlsb', 'F10_2026-11.xlsx', 'F10').requiresConsent, false)
  assert.equal(inspectUploadFilename('F10_2026-11.XLSB', 'F10_2026-11.xlsx', 'F10').allowed, true)
  assert.equal(inspectUploadFilename('01_F10_사업부1_2026_11월마감.xlsb', 'F10_2026-11.xlsx', 'F10').allowed, true)
  assert.equal(inspectUploadFilename('01_F10_사업부1_2026_10월마감.xlsb', 'F10_2026-11.xlsx', 'F10').allowed, false)
  assert.equal(inspectUploadFilename('F12_2026-11.xlsb', 'F10_2026-11.xlsx', 'F10').allowed, false)
})
