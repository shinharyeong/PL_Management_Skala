import assert from 'node:assert/strict'
import test from 'node:test'
import { evidenceFileSelection, submissionFileSelection } from './org-upload.js'

/** 제출 파일의 이진 형식과 크기 경계를 검증한다.
 * Args: 없음. Returns: 없음. Raises: 단언 실패. Side Effects: 없음.
 */
test('제출 Excel은 PrimeVue files 배열과 20MiB XLSB 경계를 보존한다', () => {
  const file = { name: 'F10_2026-11.XLSB', size: 20 * 1024 * 1024 }
  assert.deepEqual(submissionFileSelection({ files: [file] }), { file, error: '' })
  assert.notEqual(submissionFileSelection({ files: [{ ...file, size: file.size + 1 }] }).error, '')
})

/** 잘못된 선택과 빈 선택의 결과를 검증한다.
 * Args: 없음. Returns: 없음. Raises: 단언 실패. Side Effects: 없음.
 */
test('제출 선택은 빈 파일·다중 파일·다른 확장자를 거절한다', () => {
  const file = { name: 'report.xlsx', size: 100 }
  for (const files of [[{ ...file, size: 0 }], [file, file], [{ ...file, name: 'report.pdf' }]]) {
    const result = submissionFileSelection({ files })
    assert.equal(result.file, null)
    assert.notEqual(result.error, '')
  }
  assert.deepEqual(submissionFileSelection({ files: [] }), { file: null, error: '' })
  assert.notEqual(submissionFileSelection({ files: [file], originalEvent: { dataTransfer: { files: [file, file] } } }).error, '')
})

/** 소명 근거의 확장자와 별도 크기 한도를 검증한다.
 * Args: 없음. Returns: 없음. Raises: 단언 실패. Side Effects: 없음.
 */
test('근거첨부는 16MiB를 허용하고 XLSB와 초과파일을 거절한다', () => {
  const file = { name: 'evidence.pdf', size: 16 * 1024 * 1024 }
  assert.deepEqual(evidenceFileSelection({ files: [file] }), { file, error: '' })
  assert.notEqual(evidenceFileSelection({ files: [{ ...file, size: file.size + 1 }] }).error, '')
  assert.notEqual(evidenceFileSelection({ files: [{ ...file, name: 'evidence.xlsb' }] }).error, '')
  assert.notEqual(evidenceFileSelection({ files: [file, file] }).error, '')
})
