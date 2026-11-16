-- 2026.11.16：新增 Agent 运行中追加消息队列表与 HITL 中断冻结快照表

CREATE TABLE IF NOT EXISTS ai_agent_session_message_append (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id  TEXT    NOT NULL,
    content     TEXT    NOT NULL,
    status      INTEGER NOT NULL DEFAULT 1,
    queued_at   DATETIME NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Framework AgentInterruptStore 持久化与批次 CAS
CREATE TABLE IF NOT EXISTS ai_agent_interrupt_snapshot (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    batch_id            TEXT    NOT NULL,
    memory_id           TEXT    NOT NULL,
    source_execution_id TEXT    NOT NULL,
    step                INTEGER NOT NULL,
    status              TEXT    NOT NULL DEFAULT 'PENDING',
    schema_version      INTEGER NOT NULL DEFAULT 1,
    snapshot_json       TEXT    NOT NULL,
    version             INTEGER NOT NULL DEFAULT 0,
    claimed_at          DATETIME DEFAULT NULL,
    resolved_at         DATETIME DEFAULT NULL,
    create_time         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_time         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (batch_id)
);

CREATE INDEX IF NOT EXISTS idx_ai_agent_interrupt_snapshot_memory_status
    ON ai_agent_interrupt_snapshot (memory_id, status);

CREATE INDEX IF NOT EXISTS idx_ai_agent_interrupt_snapshot_status_modify
    ON ai_agent_interrupt_snapshot (status, modify_time);
