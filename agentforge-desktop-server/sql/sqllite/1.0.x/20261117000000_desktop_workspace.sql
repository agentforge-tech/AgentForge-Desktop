CREATE TABLE IF NOT EXISTS ai_workspace (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    name           TEXT    NOT NULL,
    folder_path    TEXT    NOT NULL,
    workspace_type INTEGER NOT NULL DEFAULT 1,
    status         INTEGER NOT NULL DEFAULT 1,
    remark         TEXT             DEFAULT NULL,
    sort_order     INTEGER NOT NULL DEFAULT 0,
    create_time    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_time    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE ai_agent_session ADD COLUMN workspace_id INTEGER DEFAULT NULL;
ALTER TABLE ai_agent_session ADD COLUMN workspace_path TEXT DEFAULT NULL;
