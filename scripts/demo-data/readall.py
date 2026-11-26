"""10월까지의 지난 알림은 읽음 처리(실제처럼). 11월·2027·소명 회신·기한 경과만 안 읽음으로 남긴다."""
from seed import call, login
P = login("kim.plan@sk.com")
ids, cursor = [], None
while True:
    page = call("GET", "/notifications?limit=100" + (f"&cursor={cursor}" if cursor else ""), P)
    for g in page["groups"]:
        for it in g["items"]:
            keep = it["type"] in ("REPLY", "OVERDUE") or "2026년 11월" in it["title"] or "2027년" in it["title"]
            if not it["read"] and not keep: ids.append(it["id"])
    cursor = page.get("nextCursor")
    if not cursor: break
for k in range(0, len(ids), 200): call("POST", "/notifications/read", P, body={"ids": ids[k:k+200]})
print("읽음 처리", len(ids), call("GET", "/notifications?limit=5", P)["summary"])
call("DELETE", "/auth/session", P)
