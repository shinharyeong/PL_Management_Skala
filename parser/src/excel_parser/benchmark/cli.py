"""규칙 파서의 원본 대조를 실행하며 기본 결과는 콘솔에만 출력한다."""

from __future__ import annotations

import argparse
import csv
import json
import sys
from datetime import datetime
from decimal import Decimal
from pathlib import Path
from uuid import uuid4

from excel_parser.extraction import file_fingerprint

from .runner import load_dataset, load_truth, plan_comparison, run_comparison


def json_value(value: object) -> str:
    """정밀 Decimal을 JSON 문자열로 변환한다.

    Args:
        value: 기본 JSON 인코더가 처리하지 못한 값.
    Returns:
        유한 Decimal의 고정 소수점 문자열.
    Raises:
        TypeError: 지원하지 않는 값 또는 비유한 숫자인 경우.
    Side Effects:
        없음.
    """
    if isinstance(value, Decimal) and value.is_finite():
        return format(value, "f")
    raise TypeError("JSON 변환을 지원하지 않는 값입니다: " + type(value).__name__)


def validate_output_path(output: Path, repo: Path) -> Path:
    """실행 전에 출력 경로의 안전성과 덮어쓰기를 검사한다.

    Args:
        output: 요청한 새 실행 폴더.
        repo: 저장소 루트.
    Returns:
        검증된 절대 경로.
    Raises:
        ValueError: 출력 허용 범위를 벗어난 경우.
        FileExistsError: 경로가 이미 존재하는 경우.
    Side Effects:
        경로와 존재 여부만 읽는다. 폴더를 생성하지 않는다.
    """
    output = output.expanduser().resolve()
    allowed = (repo / "parser/output/comparisons").resolve()
    if output == allowed or not output.is_relative_to(allowed) or any(p.lower() == "data" for p in output.parts):
        raise ValueError("출력은 parser/output/comparisons 아래 새 폴더만 허용합니다")
    if output.exists():
        raise FileExistsError("기존 결과를 덮어쓰지 않습니다: " + str(output))
    return output


def save_report(report: dict, output: Path, repo: Path) -> Path:
    """허용 출력 경로의 새 폴더에 JSON과 요약 CSV를 저장한다.

    Args:
        report: 원본 객체가 제외된 비교 보고서.
        output: 새 실행 폴더 경로.
        repo: 현재 저장소 루트.
    Returns:
        생성한 실행 폴더 절대 경로.
    Raises:
        ValueError: 허용 출력 디렉터리 밖이거나 Data 내부인 경우.
        FileExistsError: 기존 경로를 덮어쓰려는 경우.
        OSError: 저장 실패.
    Side Effects:
        명시적으로 요청된 새 폴더와 comparison.json, summary.csv를 생성한다.
    """
    output = validate_output_path(output, repo)
    encoded = json.dumps(report, ensure_ascii=False, indent=2, default=json_value, allow_nan=False)
    output.mkdir(parents=True, exist_ok=False)
    (output / "comparison.json").write_text(encoded + "\n", encoding="utf-8")
    rows = report.get("summary", [])
    with (output / "summary.csv").open("w", encoding="utf-8-sig", newline="") as stream:
        columns = list(rows[0]) if rows else ["parser", "repetition", "input_role"]
        writer = csv.DictWriter(stream, fieldnames=columns)
        writer.writeheader()
        writer.writerows(rows)
    return output


def make_parser() -> argparse.ArgumentParser:
    """비교 계획과 실제 실행의 명령행 옵션을 정의한다.

    Args:
        없음.
    Returns:
        부작용 없이 생성한 명령행 파서.
    Raises:
        없음.
    Side Effects:
        없음.
    """
    parser = argparse.ArgumentParser(description="규칙 파서 원본 대조: 기본은 파일 저장 없음")
    sub = parser.add_subparsers(dest="command", required=True)
    master = sub.add_parser("master", help="조직 원본만으로 월별 마스터 생성 후 독립 대조")
    master.add_argument("stage", choices=("generate", "evaluate"))
    master.add_argument("--inventory", type=Path)
    master.add_argument("--types", default="")
    master.add_argument("--months", default="")
    master.add_argument("--output-dir", type=Path, required=True)
    master.add_argument("--workers", type=int, default=6)
    master.add_argument("--publication-policy", choices=("source-contract-v4",),
                        help="생성에서만 원본 생성 계약에 근거한 v4 발행 제외 정책 적용")
    master.add_argument("--write-output", action="store_true")
    monthly = sub.add_parser("monthly", help="월별 조직 원본의 규칙 파서 실행·독립 대조")
    monthly.add_argument("dataset", type=Path)
    monthly.add_argument("--types", default="01,02,03,04,05,06,07,08,09")
    monthly.add_argument("--output-dir", type=Path, required=True)
    monthly.add_argument("--workers", type=int, default=6)
    monthly.add_argument("--write-output", action="store_true")
    for name in ("plan", "run"):
        command = sub.add_parser(name)
        command.add_argument("dataset", type=Path)
        command.add_argument("--manifest", type=Path)
        command.add_argument("--include-master", action="store_true", help="마스터를 독립 입력·별도 집계로 포함")
        command.add_argument("--max-files", type=int)
        if name == "run":
            command.add_argument("--truth", type=Path, help="독립 셀 정답 JSON. 없으면 의미 정확도는 미측정")
            command.add_argument("--repeats", type=int, default=1)
            command.add_argument("--write-output", action="store_true")
            command.add_argument("--output-dir", type=Path)
    return parser


def main(argv: list[str] | None = None) -> int:
    """계획 또는 비교를 실행하고 결과를 콘솔에 출력한다.

    Args:
        argv: 선택적 명령행 인자 목록.
    Returns:
        처리 완료 0, 입력·구성·검증·처리 실패 2, 사용자 중단 130.
    Raises:
        SystemExit: argparse의 도움말 또는 잘못된 명령행 처리.
    Side Effects:
        원본·정답 읽기와 콘솔 출력. write-output 때만 저장하며 네트워크 호출은 없다.
    """
    parser = make_parser()
    args = parser.parse_args(argv)
    repo = Path(__file__).resolve().parents[4]
    output = None
    try:
        if args.command == "master":
            from .reconstruction import evaluate, generate

            if not args.write_output or args.workers < 1:
                raise ValueError("마스터 실행에는 --write-output과 양수 --workers가 필요합니다")
            if args.stage == "generate":
                if args.inventory is None:
                    raise ValueError("생성에는 최초 --inventory가 필요합니다")
                report = generate(args.inventory, args.output_dir, types=[x for x in args.types.split(",") if x],
                                  months=[x for x in args.months.split(",") if x], workers=args.workers,
                                  publication_policy=args.publication_policy)
            else:
                if args.publication_policy is not None:
                    raise ValueError("발행 정책은 generate에만 적용하며 평가기는 변경하지 않습니다")
                report = evaluate(args.output_dir.resolve())
            print(json.dumps(report, ensure_ascii=False, default=json_value))
            return 0
        if args.command == "monthly":
            from .monthly import run_monthly

            if not args.write_output or args.workers < 1:
                raise ValueError("월별 실행에는 --write-output과 양수 --workers가 필요합니다")
            report = run_monthly(args.dataset, args.types.split(","), args.output_dir, repo, args.workers)
            print(json.dumps(report, ensure_ascii=False, default=json_value))
            return 0
        if args.command == "run":
            if args.output_dir and not args.write_output:
                raise ValueError("--output-dir에는 --write-output이 필요합니다")
            if args.repeats < 1:
                raise ValueError("반복 횟수는 양수여야 합니다")
            if args.write_output:
                name = datetime.now().strftime("%Y%m%d-%H%M%S") + "-" + uuid4().hex[:8]
                output = validate_output_path(args.output_dir or repo / "parser/output/comparisons" / name, repo)
        files, manifest, manifest_path = load_dataset(args.dataset, args.manifest,
                                                     include_master=args.include_master, max_files=args.max_files)
        if args.command == "plan":
            report = plan_comparison(files)
        else:
            truth = load_truth(args.truth, files)
            evaluation_sources = {"manifest": str(manifest_path),
                                  "manifest_sha256": file_fingerprint(manifest_path),
                                  "truth": str(args.truth.resolve()) if args.truth else None,
                                  "truth_sha256": file_fingerprint(args.truth) if args.truth else None,
                                  "hash_capture": "before_engine_execution",
                                  "provided_to_engines": False}
            report = run_comparison(files, manifest, repeats=args.repeats, truth=truth,
                                    progress=lambda message: print(message, file=sys.stderr, flush=True))
            report["evaluation_sources"] = evaluation_sources
            if args.write_output:
                saved = save_report(report, output, repo)
                print("비교 결과 저장: " + str(saved), file=sys.stderr)
        print(json.dumps(report, ensure_ascii=False, indent=2, default=json_value, allow_nan=False))
        if args.command == "run":
            valid = all(r["completed"] and r["source_integrity"]["unchanged"]
                        and r["validation"]["extraction"]["passed"] is True
                        and r["validation"]["provenance"]["passed"] is True for r in report["trials"])
            return 0 if valid else 2
        return 0
    except (ValueError, OSError, KeyError, TypeError, ImportError) as error:
        print("비교 실행 오류: " + str(error), file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print("비교 중단: 메모리 실행이며 완료 보고서는 생성되지 않았습니다", file=sys.stderr)
        return 130
