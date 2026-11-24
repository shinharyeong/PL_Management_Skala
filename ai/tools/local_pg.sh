#!/bin/sh
# 개발용 로컬 PostgreSQL(Docker 없이): ai/.cache/pgdata 에 데이터, 127.0.0.1:15433 에서 실행. 사용: tools/local_pg.sh start|stop|status
# 접속 주소(ai/.env 또는 셸): AI_DATABASE_URL=postgresql://aidev@127.0.0.1:15433/aidev   (로컬 전용·비밀번호 없음)
BIN=$(ls -d /opt/homebrew/opt/postgresql@*/bin 2>/dev/null | sort -r | head -1); [ -z "$BIN" ] && BIN=$(dirname "$(command -v initdb)")
D="$(cd "$(dirname "$0")/.." && pwd)/.cache/pgdata"
case "$1" in
  start) [ -d "$D" ] || { "$BIN/initdb" -D "$D" -U aidev --auth=trust -E UTF8 >/dev/null && echo "새 데이터 폴더를 만들었습니다"; }
         "$BIN/pg_ctl" -D "$D" -o "-p 15433 -c listen_addresses=127.0.0.1" -l "$D/server.log" -w start >/dev/null && "$BIN/createdb" -h 127.0.0.1 -p 15433 -U aidev aidev 2>/dev/null; echo "실행 중: postgresql://aidev@127.0.0.1:15433/aidev";;
  stop)  "$BIN/pg_ctl" -D "$D" -m fast stop;;
  status) "$BIN/pg_ctl" -D "$D" status;;
  *) echo "사용: $0 start|stop|status";;
esac
