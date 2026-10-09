#!/bin/sh
# 通用 PostgreSQL 手动发布入口：与 upgrade.sql 放在同一目录，后续只替换 SQL。
# SQL 必须是可在一个事务执行的纯 SQL，不自行 COMMIT，也不使用 psql 元命令。
set +x
set -eu
umask 077

fail() {
    printf '\n错误：%s\n' "$*" >&2
    exit 1
}

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P)
[ "$#" -le 1 ] || fail '用法：sh run-sql.sh [同目录下的 SQL 文件名]'
SQL_FILE=${1:-upgrade.sql}
case "$SQL_FILE" in
    /*) ;;
    *) SQL_FILE="$SCRIPT_DIR/$SQL_FILE" ;;
esac
[ -f "$SQL_FILE" ] && [ -r "$SQL_FILE" ] && [ -s "$SQL_FILE" ] \
    || fail "SQL 不存在、不可读或为空：$SQL_FILE"

# 默认当前项目本地库；其他环境须显式覆盖连接环境变量。
: "${PGHOST:=127.0.0.1}"
: "${PGPORT:=5432}"
: "${PGDATABASE:=ucp-ng}"
: "${PGUSER:=postgres}"
: "${PGCONNECT_TIMEOUT:=10}"
PGAPPNAME=os-manual-sql-release
export PGHOST PGPORT PGDATABASE PGUSER PGCONNECT_TIMEOUT PGAPPNAME

# 口令不再内置（laneSD 同步时移除：原文件把线上库口令明文写在这里，已进 Git 历史，须轮换）。
# 执行前由调用方在环境变量 PGPASSWORD 里提供；未设置则停止。
: "${PGPASSWORD:?须先设置环境变量 PGPASSWORD（线上库口令），脚本不再内置}"
export PGPASSWORD

for required_command in psql pg_dump pg_restore mktemp cp mv mkdir rmdir date cat; do
    command -v "$required_command" >/dev/null 2>&1 \
        || fail "缺少命令 $required_command；请安装 PostgreSQL 客户端和系统基础工具，或补充 PATH。"
done

LOCK_DIR="$SCRIPT_DIR/.run-sql.lock"
LOCK_ACQUIRED=0
TTY_STATE=''
cleanup() {
    if [ -n "$TTY_STATE" ]; then
        stty "$TTY_STATE" 2>/dev/null || :
    fi
    unset PGPASSWORD
    if [ "$LOCK_ACQUIRED" -eq 1 ]; then
        rmdir "$LOCK_DIR" 2>/dev/null || :
    fi
}
trap cleanup 0
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP
mkdir "$LOCK_DIR" 2>/dev/null \
    || fail "无法取得发布锁：$LOCK_DIR。请检查目录权限或是否已有脚本运行；异常中断留下的空锁目录须确认无进程后再移除。"
LOCK_ACQUIRED=1


mkdir -p "$SCRIPT_DIR/backups"
RUN_DIR=$(mktemp -d "$SCRIPT_DIR/backups/$(date +%Y%m%d-%H%M%S).XXXXXX")
# 固定本次执行内容，避免备份期间替换 upgrade.sql 导致执行了另一份文件。
cp "$SQL_FILE" "$RUN_DIR/applied.sql"
printf '目标：%s:%s / %s；账号：%s\n' "$PGHOST" "$PGPORT" "$PGDATABASE" "$PGUSER"
printf 'SQL：%s\n备份与日志：%s\n' "$SQL_FILE" "$RUN_DIR"
printf '请在已暂停业务写入的维护窗口运行；本脚本不会自动停止或重启应用。\n'

printf '\n[1/3] 检查数据库连接……\n'
if psql -X -w -v ON_ERROR_STOP=1 \
    -c 'SELECT current_database() AS database_name, current_user AS executing_user, inet_server_addr() AS server_address, inet_server_port() AS server_port, current_setting('\''server_version'\'') AS server_version;' \
    > "$RUN_DIR/connection.log" 2>&1; then
    cat "$RUN_DIR/connection.log"
else
    cat "$RUN_DIR/connection.log" >&2
    fail "连接失败，未执行增量。日志：$RUN_DIR/connection.log"
fi

printf '\n[2/3] 备份整个数据库（数据量大时需要等待）……\n'
if ! pg_dump -w --format=custom --file="$RUN_DIR/database.dump.pending" \
    > "$RUN_DIR/backup.log" 2>&1; then
    cat "$RUN_DIR/backup.log" >&2
    fail "备份失败，未执行增量。保留现场：$RUN_DIR"
fi
[ -s "$RUN_DIR/database.dump.pending" ] || fail '备份为空，未执行增量。'
if ! pg_restore --list "$RUN_DIR/database.dump.pending" \
    > "$RUN_DIR/backup-contents.txt" 2> "$RUN_DIR/backup-check.log"; then
    cat "$RUN_DIR/backup-check.log" >&2
    fail "备份目录无法读取，未执行增量。保留现场：$RUN_DIR"
fi
mv "$RUN_DIR/database.dump.pending" "$RUN_DIR/database.dump"
printf '备份成功：%s/database.dump\n' "$RUN_DIR"

printf '\n[3/3] 在单个事务内执行增量……\n'
if psql -X -w -v ON_ERROR_STOP=1 --single-transaction \
    -f "$RUN_DIR/applied.sql" > "$RUN_DIR/upgrade.log" 2>&1; then
    cat "$RUN_DIR/upgrade.log"
    printf '\n执行成功，事务已提交。\n备份：%s/database.dump\n日志：%s/upgrade.log\n' "$RUN_DIR" "$RUN_DIR"
else
    upgrade_status=$?
    cat "$RUN_DIR/upgrade.log" >&2
    printf '\n执行失败（退出码 %s）。事务内的 SQL 错误会回滚；若提交时网络中断，请先核对数据库结果。\n' "$upgrade_status" >&2
    printf '备份：%s/database.dump\n日志：%s/upgrade.log\n脚本不会自动恢复备份，也不会删除业务数据来重试。\n' "$RUN_DIR" "$RUN_DIR" >&2
    exit "$upgrade_status"
fi
