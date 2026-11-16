-- 2026.11.16：AI 消息持久化本轮 executionId，支持按消息精确停止
ALTER TABLE ai_agent_session_message ADD COLUMN execution_id TEXT;
