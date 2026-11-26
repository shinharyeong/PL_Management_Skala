"""xlsx(OOXML) 셀·시트 XML 조각. 엑셀 라이브러리 없이 표준 라이브러리만 쓴다."""
from io import BytesIO
from zipfile import ZipFile
from xml.sax.saxutils import escape

NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
REV, OPX, ANS = "매출_세부", "OpEx_세부", "답안지"

def col(n):
    s = ""
    while n:
        n, r = divmod(n - 1, 26); s = chr(65 + r) + s
    return s

def T(ref, text): return f'<c r="{ref}" t="inlineStr"><is><t>{escape(str(text))}</t></is></c>'
def N(ref, v): return f'<c r="{ref}"><v>{v}</v></c>'
def F(ref, formula, v): return f'<c r="{ref}"><f>{escape(formula)}</f><v>{v}</v></c>'

def sheet_xml(rows):
    body = "".join(f'<row r="{r}">{"".join(c)}</row>' for r, c in sorted(rows.items()))
    return f'<worksheet xmlns="{NS}"><sheetData>{body}</sheetData></worksheet>'
