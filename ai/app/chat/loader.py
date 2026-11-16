"""챗봇 조회 DB 적재 명령줄. (ai/ 에서)
  python3 -m app.chat.loader run [--run-id <실행 ID>]                              # 업로드 실행 결과 → 조회 DB
  python3 -m app.chat.loader excel --dir <폴더> --version close-<연도>-<월> --year <연도> --month <월> [--workers N]   # 조직별 월마감 엑셀 → 조회 DB
OpenAI 를 부르지 않는다."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from . import loader_excel, loader_run


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("--run-id")
    e = sub.add_parser("excel")
    e.add_argument("--dir", required=True)
    e.add_argument("--version", required=True)
    e.add_argument("--year", type=int, required=True)
    e.add_argument("--month", type=int, required=True)
    e.add_argument("--workers", type=int)
    a = ap.parse_args()
    if a.cmd == "run":
        out = loader_run.load_run(a.run_id)
    else:
        files = loader_excel.discover(Path(a.dir).expanduser())
        if not files:
            raise SystemExit("조직별 엑셀 파일(이름에 조직코드 Fxx 포함)을 찾지 못했습니다.")
        out = loader_excel.load_excel(files, a.version, a.year, a.month, workers=a.workers)
        out.pop("per_file_seconds", None)
    print(json.dumps(out, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
