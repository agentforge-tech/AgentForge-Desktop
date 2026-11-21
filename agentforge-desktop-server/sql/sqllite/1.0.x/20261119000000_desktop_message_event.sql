-- 2026.11.19：二段式对话的可重放事件流
CREATE TABLE IF NOT EXISTS ai_agent_session_message_event (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    message_id  INTEGER NOT NULL,
    event_seq   INTEGER NOT NULL,
    event_type  TEXT    NOT NULL,
    payload     TEXT    NOT NULL,
    create_time TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (message_id, event_seq)
);

CREATE INDEX IF NOT EXISTS idx_message_event_message_seq
    ON ai_agent_session_message_event (message_id, event_seq);
