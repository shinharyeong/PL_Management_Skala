import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import { definePreset } from '@primeuix/themes'
import Aura from '@primeuix/themes/aura'
import 'primeicons/primeicons.css'

const preset = definePreset(Aura, {
  semantic: { primary: Object.fromEntries([50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 950].map(shade => [shade, `{blue.${shade}}`])) },
})

/** 연결 화면에 공통 테마와 한국어 위젯 안내를 설치한다.
 * Args: app 마운트 전 Vue 애플리케이션.
 * Returns: 같은 app 인스턴스. Raises: 플러그인 설치 오류.
 * Side Effects: PrimeVue와 ToastService를 앱에 등록한다.
 */
export function installPrimeVue(app) {
  return app.use(PrimeVue, {
    ripple: true,
    theme: { preset, options: { darkModeSelector: false, cssLayer: { name: 'primevue', order: 'legacy, primevue, app' } } },
    locale: {
      accept: '확인', reject: '취소', choose: '파일 선택', upload: '업로드', cancel: '취소',
      emptyMessage: '조회된 데이터가 없습니다.', emptyFilterMessage: '일치하는 항목이 없습니다.',
      searchMessage: '{0}개 결과가 있습니다.', selectionMessage: '{0}개 항목이 선택되었습니다.',
      emptySelectionMessage: '선택된 항목이 없습니다.', emptySearchMessage: '검색 결과가 없습니다.',
      fileSizeTypes: ['B', 'KB', 'MB', 'GB', 'TB'],
      aria: {
        close: '닫기', previous: '이전', next: '다음', navigation: '탐색',
        selectAll: '전체 선택', unselectAll: '전체 선택 해제', selectRow: '행 선택', unselectRow: '행 선택 해제',
        expandRow: '행 펼치기', collapseRow: '행 접기', showFilterMenu: '필터 열기', hideFilterMenu: '필터 닫기',
        firstPageLabel: '첫 페이지', lastPageLabel: '마지막 페이지', nextPageLabel: '다음 페이지', prevPageLabel: '이전 페이지',
        pageLabel: '{page} 페이지', rowsPerPageLabel: '페이지당 행 수',
      },
    },
  }).use(ToastService)
}
