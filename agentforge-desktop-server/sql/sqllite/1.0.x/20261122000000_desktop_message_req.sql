-- 2026.11.22：用户消息请求快照，保存本次消息引用的本地资源，供历史回放渲染内联资源卡片
ALTER TABLE ai_agent_session_message ADD COLUMN req TEXT DEFAULT NULL;
