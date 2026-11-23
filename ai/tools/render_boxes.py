"""개발용: PDF 한 쪽씩 그림 위에 블록 박스(파랑 / 노랑=확인 필요)와 번호를 그려 PNG 로 저장한다. 서비스 코드에서 import 하지 않는다.

사용: python tools/render_boxes.py 파일.pdf [--ai] [--out 폴더]
  --ai  : VLM 결과(키 필요, 호출 1회/쪽). 없으면 코드 규칙 결과.
박스 좌표는 PDF 좌표(pt) × (그림 가로 / 쪽 가로) 로 그린다.
"""
from __future__ import annotations

import argparse
import io
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from PIL import Image, ImageDraw  # noqa: E402

from app.pdf_structure import extract_structure  # noqa: E402


def render(pdf_path: Path, out_dir: Path, use_ai: bool = False) -> list[Path]:
    data = pdf_path.read_bytes()
    r = extract_structure(data, with_images=True, resolution=110, use_ai=use_ai)
    out_dir.mkdir(parents=True, exist_ok=True)
    paths = []
    for p, png in zip(r["pages"], r["_images"]):
        im = Image.open(io.BytesIO(png)).convert("RGB")
        k = im.width / p["width"]
        d = ImageDraw.Draw(im, "RGBA")
        for n, b in enumerate([b for b in r["blocks"] if b["page"] == p["page"]], 1):
            x0, y0, x1, y1 = [v * k for v in b["bbox"]]
            warn = bool(b["needs_review"])
            col = (214, 150, 0, 255) if warn else (47, 91, 216, 255)
            d.rectangle([x0, y0, x1, y1], outline=col, width=2, fill=(255, 214, 0, 40) if warn else (47, 91, 216, 14))
            d.rectangle([x0, y0 - 13, x0 + 8 + 7 * len(str(n)), y0], fill=col)
            d.text((x0 + 3, y0 - 12), str(n), fill=(255, 255, 255, 255))
        path = out_dir / f"{pdf_path.stem}_p{p['page']}{'_vlm' if use_ai else '_rules'}.png"
        im.save(path)
        paths.append(path)
    kinds = {}
    for b in r["blocks"]:
        kinds[b["kind"]] = kinds.get(b["kind"], 0) + 1
    print(f"{pdf_path.name}: 블록 {len(r['blocks'])}개 {kinds} · 확인 필요 {sum(1 for b in r['blocks'] if b['needs_review'])}개 · 모드 {r['vlm'].get('mode')}")
    return paths


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("pdf", type=Path)
    ap.add_argument("--ai", action="store_true", help="VLM 결과 사용(키 필요)")
    ap.add_argument("--out", type=Path, default=Path("tools/out"))
    a = ap.parse_args()
    for pth in render(a.pdf, a.out, a.ai):
        print(pth)
