"""규칙 파서의 원본 대조를 설치 없이 실행하는 진입점."""

import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "parser/src"))

from excel_parser.benchmark.cli import main

if __name__ == "__main__":
    raise SystemExit(main())
