-- AgentForge Desktop SQLite baseline
-- 业务表只保留 id 主键，不创建二级索引 / 唯一索引。

CREATE TABLE IF NOT EXISTS desktop_schema_migration (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    script_name TEXT    NOT NULL,
    version     TEXT    NOT NULL,
    checksum    TEXT    NOT NULL,
    applied_at  INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS ai_agent_session (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id     TEXT    NOT NULL,
    aid            TEXT    NOT NULL,
    title          TEXT    NOT NULL DEFAULT '新会话',
    status         INTEGER NOT NULL DEFAULT 0,
    model_id       INTEGER          DEFAULT NULL,
    pin_order      INTEGER NOT NULL DEFAULT 0,
    create_user_id INTEGER NOT NULL DEFAULT 1,
    is_deleted     INTEGER NOT NULL DEFAULT 0,
    create_time    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_time    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ai_agent_session_message (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id     INTEGER NOT NULL,
    seq_num        INTEGER NOT NULL,
    role_type      INTEGER NOT NULL,
    status         INTEGER NOT NULL DEFAULT 1,
    started_at     TEXT             DEFAULT NULL,
    finished_at    TEXT             DEFAULT NULL,
    elapsed_ms     INTEGER          DEFAULT NULL,
    model_id       INTEGER          DEFAULT NULL,
    content        TEXT             DEFAULT NULL,
    events         TEXT             DEFAULT NULL,
    create_user_id INTEGER NOT NULL DEFAULT 1,
    is_deleted     INTEGER NOT NULL DEFAULT 0,
    create_time    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_time    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ai_agent_memory (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    memory_id   TEXT NOT NULL,
    content     TEXT NOT NULL,
    create_time TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_time TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
