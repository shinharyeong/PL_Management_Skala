"""페이지 그림 위에 블록 박스(파랑 / 노랑=확인 필요)와 블록 번호를 그린다. 박스 좌표는 PDF 좌표(pt) × (그림 가로 / 쪽 가로).
tools/render_boxes.py(개발용 PNG)와 vlm.review_loop(AI 점검용 그림)가 같이 쓴다. 번호는 넘겨 준 블록 목록의 순서(1부터)."""
from __future__ import annotations

import io


def draw_boxes(png: bytes, page_width: float, blocks: list[dict]) -> bytes:
    from PIL import Image, ImageDraw
    im = Image.open(io.BytesIO(png)).convert("RGB")
    k = im.width / page_width
    d = ImageDraw.Draw(im, "RGBA")
    for n, b in enumerate(blocks, 1):
        x0, y0, x1, y1 = [v * k for v in b["bbox"]]
        warn = bool(b["needs_review"])
        col = (214, 150, 0, 255) if warn else (47, 91, 216, 255)
        d.rectangle([x0, y0, x1, y1], outline=col, width=2, fill=(255, 214, 0, 40) if warn else (47, 91, 216, 14))
        d.rectangle([x0, y0 - 13, x0 + 8 + 7 * len(str(n)), y0], fill=col)
        d.text((x0 + 3, y0 - 12), str(n), fill=(255, 255, 255, 255))
    buf = io.BytesIO()
    im.save(buf, format="PNG")
    return buf.getvalue()
