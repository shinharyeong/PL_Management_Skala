"""조직 원본 파서 출력을 상세 업무키의 다중집합으로 대조한다.

평가기의 원본 조회는 상세 ID와 비교 대상 범위를 찾는 데만 사용한다.
조직·계정·기간·금액을 정답이나 원본으로 보완하여 점수를 높이지 않는다.
"""

from collections import Counter, defaultdict
from decimal import Decimal
import re

from ..ooxml import cell_position
from .monthly_truth import decimal_amount, native_detail_index, snapshot_label


KEY_FIELDS = (
    "type", "closing_month", "domain", "organization_code", "account_code",
    "year", "month", "scenario", "detail_id",
)


def _code(value: object) -> str | None:
    """파서가 출력한 계정 코드 표기의 선행 숫자만 표준화한다.

    Args:
        value: 파서가 명시적으로 출력한 계정 코드 또는 None.
    Returns:
        선행 숫자 코드, 원래 비숫자 코드, 또는 None. 새 코드를 추측하지 않는다.
    Raises:
        없음.
    Side Effects:
        없음.
    """
    if value in (None, ""):
        return None
    text = str(value).strip()
    match = re.fullmatch(r"(\d+)(?:\s+.*|\.0)?", text)
    return match.group(1) if match else text


def _account(record: dict, domain: str) -> str | None:
    """표준 레코드가 명시한 코드 또는 코드 차원에서 계정을 찾는다.

    Args:
        record: 기존 파서의 표준 레코드.
        domain: revenue 또는 expense.
    Returns:
        item.code 우선의 계정 코드. 값이 없으면 파서 출력의 예산Code 또는
        매출 중분류 선행 숫자를 사용한다. 원본·정답으로 보충하지 않는다.
    Raises:
        없음.
    Side Effects:
        없음.
    """
    item = record.get("item") or {}
    if item.get("code") not in (None, ""):
        return _code(item["code"])
    dimensions = record.get("dimensions") or {}
    field = "중분류" if domain == "revenue" else "예산Code"
    value = dimensions.get(field, {})
    value = value.get("value") if isinstance(value, dict) else value
    if value not in (None, ""):
        return _code(value)
    if domain == "revenue":
        for label in item.get("header_path", []):
            if isinstance(label, str) and re.match(r"^\d+\s", label):
                return _code(label)
    return None


def project_predictions(
    records: list[dict], workbook: dict, *, type_name: str,
    closing_month: str | int,
) -> list[dict]:
    """생산 파서 출력을 월별 비교용 평탄 레코드로 투영한다.

    Args:
        records: 기존 파서가 생성한 중첩 표준 레코드. 수정하지 않는다.
        workbook: 같은 조직 원본의 추출 결과. ID와 월별 대상 셀 판정 전용.
        type_name: 실행 유형 식별자.
        closing_month: 2026년 실행 마감월.
    Returns:
        모든 대상 출력의 새 목록. 금액 미확정도 보존하며, 원본에 있어도
        파서가 생략한 셀은 만들어 넣지 않는다. 금액 확정은 amount, confirmed,
        유한 normalized_value를 모두 요구한다. 의미 미확정은 별도 표시한다.
    Raises:
        ValueError: 마감월 인자가 유효하지 않은 경우.
    Side Effects:
        메모리 원본 조회만 수행하며 파일·API 접근은 없다.
    """
    indices = native_detail_index(workbook)
    snapshot = snapshot_label(closing_month)
    output = []
    for record in records:
        source = record.get("source") or {}
        index = indices.get(source.get("sheet"))
        if index is None:
            continue
        try:
            row, column, _ = cell_position(source.get("cell", ""))
        except (ValueError, TypeError):
            continue
        detail = index["details"].get(row)
        native_period = index["monthly_columns"].get(column)
        if detail is None or native_period is None:
            continue
        period = record.get("period") or {}
        value = record.get("value") or {}
        organization = record.get("organization") or {}
        normalized = decimal_amount(value.get("normalized_value"))
        confirmed = (value.get("status") == "confirmed"
                     and value.get("value_kind") == "amount"
                     and normalized is not None)
        predicted = {
            "type": type_name, "closing_month": snapshot,
            "domain": index["layout"]["domain"],
            "organization_code": organization.get("code"),
            "account_code": _account(record, index["layout"]["domain"]),
            "year": period.get("year"), "month": period.get("month"),
            "scenario": period.get("scenario"),
            "aggregation": period.get("aggregation"),
            "period_group": native_period["period_group"],
            "detail_id": detail["detail_id"], "revision": detail["revision"],
            "source_file": source.get("path", workbook.get("file", {}).get("path")),
            "sheet": source.get("sheet"), "cell": source.get("cell"),
            "identity_cell": detail["description_cell"],
            "normalized_value": str(normalized) if normalized is not None else None,
            "raw_value": record.get("raw_value"), "source_unit": value.get("source_unit"),
            "value_kind": value.get("value_kind"), "value_status": value.get("status"),
            "mapping_status": (record.get("mapping") or {}).get("status"),
            "mapping_reasons": (record.get("mapping") or {}).get("reasons", []),
            "confirmed": confirmed,
        }
        predicted["semantic_complete"] = (
            all(predicted.get(field) not in (None, "", "unknown") for field in KEY_FIELDS)
            and period.get("aggregation") == "month"
            and period.get("scenario") in {"actual", "plan", "estimate"}
        )
        output.append(predicted)
    return output


def record_key(record: dict) -> tuple:
    """상세 ID가 확장된 월별 업무키를 만든다.

    Args:
        record: 평탄한 정답 또는 예측 레코드.
    Returns:
        KEY_FIELDS 순서의 튜플. 미확정 값도 그대로 남긴다.
    Raises:
        없음.
    Side Effects:
        없음.
    """
    return tuple(record.get(field) for field in KEY_FIELDS)


def _detail(status: str, truth: dict | None, prediction: dict | None, *, duplicate: bool = False) -> dict:
    """오류 한 건의 두 원본 출처와 값·키를 보존한다.

    Args:
        status: missing, extra, amount_mismatch, unresolved 등의 오류 종류.
        truth: 연결된 정답 또는 None.
        prediction: 연결된 파서 출력 또는 None.
        duplicate: 같은 업무키의 출력이 여러 개인지 여부.
    Returns:
        JSON 직렬화 가능한 상세 오류 사전.
    Raises:
        없음.
    Side Effects:
        없음.
    """
    base = prediction or truth or {}
    return {
        "status": status, "type": base.get("type"),
        "closing_month": base.get("closing_month"), "domain": base.get("domain"),
        "source_file": base.get("source_file"),
        "organization_file": (truth or {}).get("organization_file"),
        "sheet": base.get("sheet"), "cell": base.get("cell"),
        "truth_file": (truth or {}).get("source_file"),
        "truth_sheet": (truth or {}).get("sheet"), "truth_cell": (truth or {}).get("cell"),
        "record_key": dict(zip(KEY_FIELDS, record_key(base))),
        "truth_value": (truth or {}).get("normalized_value"),
        "output_value": (prediction or {}).get("normalized_value"),
        "duplicate": duplicate,
        "truth_revision": (truth or {}).get("revision"),
        "output_revision": (prediction or {}).get("revision"),
        "mapping_status": (prediction or {}).get("mapping_status"),
        "value_status": (prediction or {}).get("value_status"),
        "mapping_reasons": (prediction or {}).get("mapping_reasons", []),
    }


def _metrics(expected: int, confirmed: int, matched: int) -> dict:
    """정답·확정 출력·일치 건수로 마이크로 지표를 계산한다.

    Args:
        expected: 전체 정답 수. 누락·생략된 정답도 포함한다.
        confirmed: 금액이 확정된 출력 수. 중복도 각각 센다.
        matched: 업무키와 금액이 일치한 수.
    Returns:
        Precision, Recall, F1 및 TP/FP/FN. 정답이 없으면 미측정이다.
    Raises:
        없음.
    Side Effects:
        없음.
    """
    precision = matched / confirmed if confirmed else (0.0 if expected else None)
    recall = matched / expected if expected else None
    return {
        "truth_count": expected, "confirmed_output_count": confirmed,
        "match_count": matched, "true_positive": matched,
        "false_positive": confirmed - matched, "false_negative": expected - matched,
        "precision": precision, "recall": recall,
        "recognition_rate": recall,
        "f1": 2 * matched / (confirmed + expected) if expected else None,
        "status": "scored" if expected else "not_scored",
    }


def _aggregate_checks(predictions: list[dict], truth: list[dict]) -> list[dict]:
    """상세 대조 이후 월별 금액 합계 검증을 별도로 계산한다.

    Args:
        predictions: 모든 평탄 예측. 확정 금액만 합산한다.
        truth: 마스터 상세 정답.
    Returns:
        영역·기간·구분별 두 합계, 건수, 합계 일치 및 완전성.
    Raises:
        없음.
    Side Effects:
        없음. 개별 레코드를 합산 결과로 대체하지 않는다.
    """
    groups = {}
    fields = ("domain", "period_group", "year", "month", "scenario")
    for label, rows in (("truth", truth), ("output", predictions)):
        for row in rows:
            if label == "output" and not row.get("confirmed"):
                continue
            amount = decimal_amount(row.get("normalized_value"))
            if amount is None:
                continue
            group = groups.setdefault(tuple(row.get(field) for field in fields), {
                "truth_count": 0, "output_count": 0,
                "truth_total": Decimal(0), "output_total": Decimal(0),
            })
            group[label + "_count"] += 1
            group[label + "_total"] += amount
    return [{
        **dict(zip(fields, key)),
        "truth_count": group["truth_count"], "output_count": group["output_count"],
        "truth_total": str(group["truth_total"]), "output_total": str(group["output_total"]),
        "matching": group["truth_total"] == group["output_total"],
        "count_complete": group["truth_count"] == group["output_count"],
    } for key, group in groups.items()]


def score_monthly_records(predictions: list[dict], truth_records: list[dict]) -> dict:
    """상세 업무키별 다중집합을 일대일 연결하고 금액 불일치를 FP와 FN으로 센다.

    Args:
        predictions: project_predictions가 만든 파서 출력 전체.
        truth_records: build_master_truth의 records. 생산 파서 생략과 무관하게 유지.
    Returns:
        summary, details, by_domain, aggregates. 금액 불일치는 상세 1건과
        FP 1건·FN 1건이다. 중복 출력은 정확 일치부터 일대일 연결하고 남은
        수량은 FP이며 삭제·합산하지 않는다. 미확정 출력은 확정 분모에서
        제외하되 대응 정답은 FN에 남긴다. 오류 상세는 상한 없이 반환한다.
    Raises:
        없음. 입력 자료의 필드가 빠지면 미확정 업무키로 평가한다.
    Side Effects:
        메모리 연산만 수행한다. 입력 레코드와 파일을 변경하지 않는다.
    """
    truth_groups = defaultdict(list)
    prediction_groups = defaultdict(list)
    for row in truth_records:
        truth_groups[record_key(row)].append(row)
    for row in predictions:
        prediction_groups[record_key(row)].append(row)
    details = []
    matched = Counter()
    for key in sorted(truth_groups.keys() | prediction_groups.keys(), key=repr):
        expected = list(truth_groups.get(key, []))
        outputs = list(prediction_groups.get(key, []))
        duplicate = len(outputs) > 1
        remaining = []
        for prediction in outputs:
            amount = decimal_amount(prediction.get("normalized_value"))
            exact = next((i for i, row in enumerate(expected)
                          if prediction.get("confirmed") and prediction.get("semantic_complete")
                          and amount is not None
                          and amount == decimal_amount(row.get("normalized_value"))), None)
            if exact is not None:
                truth = expected.pop(exact)
                matched[prediction.get("domain")] += 1
                if duplicate:
                    details.append(_detail("duplicate_matched", truth, prediction, duplicate=True))
            else:
                remaining.append(prediction)
        for prediction in remaining:
            truth = expected.pop(0) if expected else None
            if not prediction.get("confirmed"):
                status = "unresolved"
            elif truth is not None and prediction.get("semantic_complete"):
                status = "amount_mismatch"
            else:
                status = "extra"
            details.append(_detail(status, truth, prediction, duplicate=duplicate))
            if truth is not None and status == "extra":
                details.append(_detail("missing", truth, None))
        details.extend(_detail("missing", truth, None) for truth in expected)
    by_domain = []
    for domain in sorted({r.get("domain") for r in truth_records + predictions if r.get("domain")}):
        scoped = [r for r in predictions if r.get("domain") == domain]
        counts = Counter(d["status"] for d in details if d["domain"] == domain)
        by_domain.append({
            "domain": domain,
            **_metrics(sum(r.get("domain") == domain for r in truth_records), sum(bool(r.get("confirmed")) for r in scoped), matched[domain]),
            "unresolved_output_count": sum(not r.get("confirmed") or not r.get("semantic_complete") for r in scoped),
            "error_counts": dict(counts),
        })
    summary = _metrics(len(truth_records), sum(bool(r.get("confirmed")) for r in predictions), sum(matched.values()))
    summary.update({
        "observed_output_count": len(predictions),
        "unresolved_output_count": sum(not r.get("confirmed") or not r.get("semantic_complete") for r in predictions),
        "duplicate_output_groups": sum(len(rows) > 1 for rows in prediction_groups.values()),
        "duplicate_output_excess": sum(max(0, len(rows) - 1) for rows in prediction_groups.values()),
        "duplicate_truth_groups": sum(len(rows) > 1 for rows in truth_groups.values()),
        "error_counts": dict(Counter(d["status"] for d in details)),
    })
    summary.update({
        "matched": summary["match_count"],
        "amount_mismatch": summary["error_counts"].get("amount_mismatch", 0),
        "missing": summary["error_counts"].get("missing", 0),
        "extra": summary["error_counts"].get("extra", 0),
        "unresolved": summary["unresolved_output_count"],
        "duplicate_prediction": summary["duplicate_output_excess"],
        "duplicate_truth": summary["duplicate_truth_groups"],
    })
    return {"summary": summary, "details": details, "by_domain": by_domain,
            "aggregates": _aggregate_checks(predictions, truth_records)}
