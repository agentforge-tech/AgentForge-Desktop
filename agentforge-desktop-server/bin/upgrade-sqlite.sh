#!/usr/bin/env bash
#
# 执行 AgentForge Desktop SQLite 增量 SQL。
# 与 Java 内置迁移器共用 desktop_schema_migration 台账，双方可交替执行。
#
# 用法：./upgrade-sqlite.sh [--dry-run]

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE_HOME="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="${AGENTFORGE_DESKTOP_ROOT_DIR:-$HOME/.agentforge-desktop}"
DB_PATH="${AGENTFORGE_DESKTOP_SQLITE_PATH:-$ROOT_DIR/data/agentforge-desktop.db}"
SQL_DIR="${AGENTFORGE_DESKTOP_SQL_DIR:-$SERVICE_HOME/sql/sqllite}"
APPLIED_DIR="${AGENTFORGE_DESKTOP_SQL_APPLIED_DIR:-$SERVICE_HOME/sql/local/sqlite}"
DRY_RUN=false

info() { printf "[sqlite] %s\n" "$1"; }
warn() { printf "[sqlite][WARN] %s\n" "$1" >&2; }
fail() { printf "[sqlite][ERROR] %s\n" "$1" >&2; exit 1; }

while [ "$#" -gt 0 ]; do
    case "$1" in
        --dry-run) DRY_RUN=true ;;
        -h|--help)
            echo "用法：$0 [--dry-run]"
            echo "DB: $DB_PATH"
            echo "SQL: $SQL_DIR"
            exit 0
            ;;
        *) fail "未知参数：$1" ;;
    esac
    shift
done

command -v sqlite3 >/dev/null 2>&1 || fail "未找到 sqlite3 客户端；正常桌面启动会由 Java 迁移器自动执行"
[ -d "$SQL_DIR" ] || fail "增量 SQL 目录不存在：$SQL_DIR"

mkdir -p "$(dirname "$DB_PATH")" "$APPLIED_DIR"

sqlite3 "$DB_PATH" <<'SQL'
CREATE TABLE IF NOT EXISTS desktop_schema_migration (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    script_name TEXT    NOT NULL,
    version     TEXT    NOT NULL,
    checksum    TEXT    NOT NULL,
    applied_at  INTEGER NOT NULL
);
SQL

sha256_file() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    elif command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        openssl dgst -sha256 "$1" | awk '{print $NF}'
    fi
}

PENDING=()
while IFS= read -r sql_file; do
    [ -n "$sql_file" ] || continue
    relative="${sql_file#"$SQL_DIR"/}"
    exists="$(sqlite3 "$DB_PATH" "SELECT COUNT(1) FROM desktop_schema_migration WHERE script_name = '$relative';")"
    if [ "$exists" = "0" ]; then
        PENDING+=("$relative")
    fi
done < <(find "$SQL_DIR" -type f -name '*.sql' | sort)

if [ "${#PENDING[@]}" -eq 0 ]; then
    info "没有需要执行的增量 SQL"
    exit 0
fi

info "待执行 ${#PENDING[@]} 个脚本："
for relative in "${PENDING[@]}"; do echo "  - $relative"; done

if [ "$DRY_RUN" = true ]; then
    warn "dry-run 模式，未执行"
    exit 0
fi

for relative in "${PENDING[@]}"; do
    sql_file="$SQL_DIR/$relative"
    version="$(dirname "$relative")"
    checksum="$(sha256_file "$sql_file")"
    info "执行 $relative"
    if {
        printf 'BEGIN;\n'
        cat "$sql_file"
        printf '\n'
        printf "INSERT INTO desktop_schema_migration(script_name,version,checksum,applied_at) VALUES('%s','%s','%s',%s);\n" \
            "$relative" "$version" "$checksum" "$(($(date +%s) * 1000))"
        printf 'COMMIT;\n'
    } | sqlite3 -bail -cmd ".timeout 5000" "$DB_PATH"; then
        mkdir -p "$APPLIED_DIR/$version"
        cp -f "$sql_file" "$APPLIED_DIR/$relative"
        info "完成 $relative"
    else
        fail "执行失败：$relative"
    fi
done

info "增量 SQL 执行完成"
