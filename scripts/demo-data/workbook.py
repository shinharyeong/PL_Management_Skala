"""시연용 합성 제출 엑셀(xlsx). 실제 양식의 답안지(SUMIFS)·매출 세부·OpEx 세부 구조를 따르고, 답안지 항목은 실제 이름, 원천 행은 여러 개로 나눈다.
금액은 모두 합성값(천원)이며 실제 사업 수치가 아니다."""
from io import BytesIO
from zipfile import ZipFile
from xml.sax.saxutils import escape
from xlsx_xml import col, T, N, F, sheet_xml, NS, REL, REV, OPX, ANS
import model as M

def build(code, periods, vals, revision=1):
    """periods: [(year, '계획'|'실적'|'추정', month)], vals: {item: {period_index: 천원}}"""
    org = M.NAME[code]
    used = [i for i in M.ITEMS if any(vals[i].values())]
    # 매출 세부
    rev = {1: [T("A1", f"▣ 매출 세부 (천원, IFRS후) · {org}")]}
    head, months, groups = [T("A3", "조직명"), T("B3", "관리분류"), T("C3", "중분류"), T("D3", "소분류"), T("E3", "거래내역 및 산출근거")], [], []
    for i, (y, s, m) in enumerate(periods):
        c = col(6 + i)
        if not groups or groups[-1] != (y, s):
            head.append(T(f"{c}3", f"{y}년 {s}")); groups.append((y, s))
        months.append(T(f"{c}4", f"{m}월"))
    rev[3], rev[4] = head, months
    r = 5
    for gi, (grp, name, _, subs) in enumerate(M.REVENUE):
        if name not in used: continue
        parts = {i: M.split(code, name, vals[name].get(i, 0)) for i in range(len(periods))}
        for k, sub in enumerate(subs):
            basis = f"{sub}: 가입자 수 × ARPU 기준 산출" if grp in ("기본료", "통화료") else f"{sub}: 계약·수주 일정 기준 산출"
            rev[r] = [T(f"A{r}", org), T(f"B{r}", name), T(f"C{r}", grp), T(f"D{r}", sub), T(f"E{r}", f"{basis} [ID:{code}-R{gi+1:02d}{k+1}; 수정:{revision}]")] \
                     + [N(f"{col(6+i)}{r}", parts[i][k]) for i in range(len(periods))]
            r += 1
    # OpEx 세부
    opx = {1: [T("A1", f"▣ OpEx 세부 (천원, IFRS후) · {org}")]}
    labels = ["대분류", "중분류", "소분류", "조직명", "본부명", "팀Code", "팀명", "실행과제Code", "실행과제명", "예산Code", "예산명칭", "실적집계기준", "비용구분", "담당자", "거래내역 및 산출근거"]
    base = len(labels) + 1
    g4, h5, groups = [], [T(f"{col(k+1)}5", l) for k, l in enumerate(labels)], []
    for i, (y, s, m) in enumerate(periods):
        c = col(base + i)
        if not groups or groups[-1] != (y, s):
            g4.append(T(f"{c}4", f"{y}년 {s}")); groups.append((y, s))
        h5.append(T(f"{c}5", f"{m}월"))
    opx[4], opx[5] = g4, h5
    r = 6
    for ci, (big, name, bcode, _, subs) in enumerate(M.COST):
        if name not in used: continue
        parts = {i: M.split(code, name, vals[name].get(i, 0)) for i in range(len(periods))}
        for k, sub in enumerate(subs):
            team = f"{code}{ci+1:02d}{k+1}"
            row = [big, name, name, org, f"{org} 본부", team, f"{org} {sub}팀", f"P{code[1:]}{ci+1:02d}{k+1}", sub, bcode, name, name,
                   "인건비성" if big == "인건복리비" else "사업비성", M.NAME[code][:2] + " 담당", f"{sub}: 계약 단가 × 물량 기준 [ID:{code}-C{ci+1:02d}{k+1}; 수정:{revision}]"]
            opx[r] = [T(f"{col(j+1)}{r}", v) for j, v in enumerate(row)] + [N(f"{col(base+i)}{r}", parts[i][k]) for i in range(len(periods))]
            r += 1
    reg = {1: [T("A1", "■ 예산 코드")], 2: [T("A2", "예산코드"), T("B2", "예산명칭"), T("C2", "실적집계 기준"), T("D2", "구분")]}
    for k, (big, name, bcode, _, _) in enumerate(M.COST):
        reg[3 + k] = [T(f"A{3+k}", bcode), T(f"B{3+k}", name), T(f"C{3+k}", name), T(f"D{3+k}", "인건비성" if big == "인건복리비" else "사업비성")]
    orgs = {1: [T("A1", "사업부코드"), T("B1", "사업부명")]}
    for k, (c, n, _, _) in enumerate(M.ORGS):
        orgs[2 + k] = [T(f"A{2+k}", c), T(f"B{2+k}", n)]
    # 답안지
    ans = {2: [T("A2", "▣ 조직명")], 5: [T("A5", org)], 6: [], 7: [], 8: []}
    first = 10
    for i, (y, s, m) in enumerate(periods):
        c = col(first + i)
        ans[5].append(T(f"{c}5", f"{y}년")); ans[6].append(T(f"{c}6", s)); ans[7].append(T(f"{c}7", f"{(m-1)//3+1}Q")); ans[8].append(T(f"{c}8", f"{m}월"))
    rr = 10; rev_total = rr; ans[rr] = [T(f"B{rr}", "매출")]; rr += 1
    rgrp, rleaf = {}, {}
    for grp, name, _, _ in M.REVENUE:
        if grp not in rgrp: rgrp[grp] = rr; ans[rr] = [T(f"C{rr}", grp)]; rr += 1
        rleaf[name] = rr; ans[rr] = [T(f"D{rr}", name)]; rr += 1
    rr += 1; cost_total = rr; ans[rr] = [T(f"B{rr}", "영업비용")]; rr += 1
    cgrp, cleaf = {}, {}
    for big, name, _, _, _ in M.COST:
        if big not in cgrp: cgrp[big] = rr; ans[rr] = [T(f"C{rr}", big)]; rr += 1
        cleaf[name] = rr; ans[rr] = [T(f"E{rr}", name)]; rr += 1
    rr += 1; op_row = rr; ans[rr] = [T(f"B{rr}", "영업이익")]
    rkids = {g: [rleaf[n] for gg, n, _, _ in M.REVENUE if gg == g] for g in rgrp}
    ckids = {g: [cleaf[n] for gg, n, _, _, _ in M.COST if gg == g] for g in cgrp}
    for i, (y, s, m) in enumerate(periods):
        c, sr, so = col(first + i), col(6 + i), col(base + i)
        v = {}
        for name, row in rleaf.items():
            v[row] = round(vals[name].get(i, 0) * 0.00001, 6)
            ans[row].append(F(f"{c}{row}", f"(SUMIFS({REV}!{sr}:{sr},{REV}!$B:$B,$D{row},{REV}!$A:$A,$A$5))*0.00001", v[row]))
        for g, row in rgrp.items():
            v[row] = round(sum(v[x] for x in rkids[g]), 6); ans[row].append(F(f"{c}{row}", f"SUM({c}{min(rkids[g])}:{c}{max(rkids[g])})", v[row]))
        rt = round(sum(v[x] for x in rgrp.values()), 6); ans[rev_total].append(F(f"{c}{rev_total}", "+".join(f"{c}{x}" for x in rgrp.values()), rt))
        for name, row in cleaf.items():
            v[row] = round(vals[name].get(i, 0) / 100000, 6)
            ans[row].append(F(f"{c}{row}", f"SUMIFS({OPX}!{so}:{so},{OPX}!$D:$D,{ANS}!$A$5,{OPX}!$L:$L,{ANS}!$E:$E)/100000", v[row]))
        for g, row in cgrp.items():
            v[row] = round(sum(v[x] for x in ckids[g]), 6); ans[row].append(F(f"{c}{row}", f"SUM({c}{min(ckids[g])}:{c}{max(ckids[g])})", v[row]))
        ct = round(sum(v[x] for x in cgrp.values()), 6); ans[cost_total].append(F(f"{c}{cost_total}", "+".join(f"{c}{x}" for x in cgrp.values()), ct))
        ans[op_row].append(F(f"{c}{op_row}", f"{c}{rev_total}-{c}{cost_total}", round(rt - ct, 6)))
    sheets = {ANS: sheet_xml(ans), REV: sheet_xml(rev), OPX: sheet_xml(opx), "예산코드": sheet_xml(reg), "조직코드": sheet_xml(orgs)}
    ws_ct = "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"
    entries = {
        "[Content_Types].xml": '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>' + "".join(f'<Override PartName="/xl/worksheets/sheet{i}.xml" ContentType="{ws_ct}"/>' for i in range(1, len(sheets) + 1)) + "</Types>",
        "_rels/.rels": f'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="book" Type="{REL}/officeDocument" Target="xl/workbook.xml"/></Relationships>',
        "xl/workbook.xml": f'<workbook xmlns="{NS}" xmlns:r="{REL}"><sheets>' + "".join(f'<sheet name="{escape(nm)}" sheetId="{i}" r:id="r{i}"/>' for i, nm in enumerate(sheets, 1)) + "</sheets></workbook>",
        "xl/_rels/workbook.xml.rels": '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' + "".join(f'<Relationship Id="r{i}" Type="{REL}/worksheet" Target="worksheets/sheet{i}.xml"/>' for i in range(1, len(sheets) + 1)) + "</Relationships>",
    }
    for i, doc in enumerate(sheets.values(), 1):
        entries[f"xl/worksheets/sheet{i}.xml"] = doc
    out = BytesIO()
    with ZipFile(out, "w") as z:
        for k, v in entries.items(): z.writestr(k, v)
    return out.getvalue()
