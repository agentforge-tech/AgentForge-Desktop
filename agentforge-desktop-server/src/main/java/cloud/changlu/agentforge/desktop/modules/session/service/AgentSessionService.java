package cloud.changlu.agentforge.desktop.modules.session.service;

import cloud.changlu.agentforge.desktop.modules.session.dao.AgentSessionMapper;
import cloud.changlu.agentforge.desktop.modules.session.dao.AgentSessionMessageMapper;
import cloud.changlu.agentforge.desktop.modules.session.domain.AgentSessionMessagePojo;
import cloud.changlu.agentforge.desktop.modules.session.domain.AgentSessionPojo;
import cloud.changlu.agentforge.desktop.modules.session.domain.SessionMessageVo;
import cloud.changlu.agentforge.desktop.modules.session.domain.SessionVo;
import cloud.changlu.agentforge.desktop.modules.session.enums.SessionMessageRoleEnum;
import cloud.changlu.agentforge.desktop.modules.session.enums.SessionMessageStatusEnum;
import cloud.changlu.agentforge.desktop.modules.session.enums.SessionStatusEnum;
import cloud.changlu.agentforge.desktop.modules.session.util.SessionTitleUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class AgentSessionService {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;
    private static final long DEFAULT_USER_ID = 1L;

    private final AgentSessionMapper sessionMapper;
    private final AgentSessionMessageMapper messageMapper;

    public AgentSessionService(
            AgentSessionMapper sessionMapper, AgentSessionMessageMapper messageMapper) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public SessionInitResult initChat(
            String aid, String sessionId, String message, Long modelId, Long userId,
            Long workspaceId, String workspacePath) {
        if (aid == null || aid.trim().isEmpty()) {
            throw new IllegalArgumentException("aid 不能为空");
        }
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("对话内容不能为空");
        }
        String trimmedMessage = message.trim();
        String resolvedSessionId =
                sessionId == null || sessionId.trim().isEmpty()
                        ? UUID.randomUUID().toString()
                        : sessionId.trim();
        String now = now();
        long user = userId == null ? DEFAULT_USER_ID : userId;

        AgentSessionPojo session =
                sessionMapper.selectBySessionIdAndAid(resolvedSessionId, aid);
        if (session == null) {
            session = new AgentSessionPojo();
            session.setSessionId(resolvedSessionId);
            session.setAid(aid);
            session.setTitle(SessionTitleUtil.resolve(trimmedMessage));
            session.setStatus(SessionStatusEnum.IDLE.getCode());
            session.setModelId(modelId);
            session.setWorkspaceId(workspaceId);
            session.setWorkspacePath(workspacePath);
            session.setPinOrder(0);
            session.setCreateUserId(user);
            session.setIsDeleted(0);
            session.setCreateTime(now);
            session.setModifyTime(now);
            sessionMapper.insert(session);
        } else {
            sessionMapper.touch(session.getId(), now);
            if ((session.getWorkspacePath() == null || session.getWorkspacePath().trim().isEmpty())
                    && workspacePath != null && !workspacePath.trim().isEmpty()) {
                sessionMapper.updateWorkspace(session.getId(), workspaceId, workspacePath, now);
                session.setWorkspaceId(workspaceId);
                session.setWorkspacePath(workspacePath);
            }
            if (modelId != null) {
                sessionMapper.updateModelId(session.getId(), modelId, now);
                session.setModelId(modelId);
            }
        }

        Integer maxSeq = messageMapper.selectMaxSeqNum(session.getId());
        int userSeq = (maxSeq == null ? 0 : maxSeq) + 1;
        int aiSeq = userSeq + 1;

        AgentSessionMessagePojo userMessage = new AgentSessionMessagePojo();
        userMessage.setSessionId(session.getId());
        userMessage.setSeqNum(userSeq);
        userMessage.setRoleType(SessionMessageRoleEnum.USER.getCode());
        userMessage.setStatus(SessionMessageStatusEnum.SUCCEEDED.getCode());
        userMessage.setContent(trimmedMessage);
        userMessage.setCreateUserId(user);
        userMessage.setIsDeleted(0);
        userMessage.setCreateTime(now);
        userMessage.setModifyTime(now);
        messageMapper.insert(userMessage);

        AgentSessionMessagePojo aiMessage = new AgentSessionMessagePojo();
        aiMessage.setSessionId(session.getId());
        aiMessage.setSeqNum(aiSeq);
        aiMessage.setRoleType(SessionMessageRoleEnum.AI.getCode());
        aiMessage.setStatus(SessionMessageStatusEnum.CREATED.getCode());
        aiMessage.setModelId(modelId);
        aiMessage.setCreateUserId(user);
        aiMessage.setIsDeleted(0);
        aiMessage.setCreateTime(now);
        aiMessage.setModifyTime(now);
        messageMapper.insert(aiMessage);

        return new SessionInitResult(
                resolvedSessionId, session.getId(), aiMessage.getId(), aiSeq, session.getTitle());
    }

    public List<SessionVo> listNormal(String aid, Integer limit) {
        if (aid == null || aid.trim().isEmpty()) {
            throw new IllegalArgumentException("aid 不能为空");
        }
        int size = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        return toVoList(sessionMapper.selectNormalList(aid.trim(), size));
    }

    public List<SessionVo> listTop(String aid) {
        if (aid == null || aid.trim().isEmpty()) {
            throw new IllegalArgumentException("aid 不能为空");
        }
        return toVoList(sessionMapper.selectTopList(aid.trim()));
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean setTop(Long sessionId, boolean top) {
        AgentSessionPojo session = requireOwned(sessionId);
        String now = now();
        if (!top) {
            sessionMapper.updatePinOrder(session.getId(), 0, now);
            return true;
        }
        Integer max = sessionMapper.selectMaxPinOrder();
        sessionMapper.updatePinOrder(session.getId(), (max == null ? 0 : max) + 1, now);
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public SessionVo changeTitle(Long sessionId, String title) {
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("会话标题不能为空");
        }
        AgentSessionPojo session = requireOwned(sessionId);
        String trimmed = title.trim();
        String now = now();
        sessionMapper.updateTitle(session.getId(), trimmed, now);
        session.setTitle(trimmed);
        session.setModifyTime(now);
        return toVo(session);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean delete(Long sessionId) {
        AgentSessionPojo session = requireOwned(sessionId);
        String now = now();
        sessionMapper.logicDelete(session.getId(), now);
        messageMapper.logicDeleteBySessionId(session.getId(), now);
        return true;
    }

    public List<SessionMessageVo> listMessages(Long sessionId) {
        AgentSessionPojo session = requireOwned(sessionId);
        List<AgentSessionMessagePojo> messages = messageMapper.selectBySessionId(session.getId());
        List<SessionMessageVo> result = new ArrayList<SessionMessageVo>(messages.size());
        for (AgentSessionMessagePojo message : messages) {
            result.add(toMessageVo(session, message));
        }
        return result;
    }

    public AgentSessionPojo requireOwned(Long sessionId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        AgentSessionPojo session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在或已被删除");
        }
        return session;
    }

    private List<SessionVo> toVoList(List<AgentSessionPojo> sessions) {
        List<SessionVo> result = new ArrayList<SessionVo>(sessions.size());
        for (AgentSessionPojo session : sessions) {
            result.add(toVo(session));
        }
        return result;
    }

    private SessionVo toVo(AgentSessionPojo session) {
        SessionVo vo = new SessionVo();
        vo.setId(session.getId());
        vo.setSessionId(session.getSessionId());
        vo.setAid(session.getAid());
        vo.setTitle(session.getTitle());
        vo.setStatus(session.getStatus());
        vo.setModelId(session.getModelId());
        vo.setWorkspaceId(session.getWorkspaceId());
        vo.setWorkspacePath(session.getWorkspacePath());
        vo.setTopOrder(session.getPinOrder());
        vo.setCreateTime(session.getCreateTime());
        vo.setModifyTime(session.getModifyTime());
        vo.setRunning(SessionStatusEnum.RUNNING.getCode().equals(session.getStatus()));
        vo.setMessageCount(messageMapper.countBySessionId(session.getId()));
        vo.setLastMessageAt(messageMapper.selectLastMessageTime(session.getId()));
        return vo;
    }

    private SessionMessageVo toMessageVo(AgentSessionPojo session, AgentSessionMessagePojo message) {
        SessionMessageVo vo = new SessionMessageVo();
        vo.setId(message.getId());
        vo.setSessionRefId(message.getSessionId());
        vo.setSessionId(session.getSessionId());
        vo.setSeqNum(message.getSeqNum());
        vo.setRoleType(message.getRoleType());
        vo.setStatus(message.getStatus());
        vo.setStartedAt(message.getStartedAt());
        vo.setFinishedAt(message.getFinishedAt());
        vo.setElapsedMs(message.getElapsedMs());
        vo.setModelId(message.getModelId());
        vo.setContentText(message.getContent());
        vo.setEvents(message.getEvents());
        vo.setCreateTime(message.getCreateTime());
        vo.setModifyTime(message.getModifyTime());
        return vo;
    }

    private static String now() {
        return LocalDateTime.now().format(FORMATTER);
    }

    public static class SessionInitResult {
        private final String sessionId;
        private final Long sessionPk;
        private final Long aiMessageId;
        private final Integer aiSeqNum;
        private final String title;

        public SessionInitResult(
                String sessionId, Long sessionPk, Long aiMessageId, Integer aiSeqNum, String title) {
            this.sessionId = sessionId;
            this.sessionPk = sessionPk;
            this.aiMessageId = aiMessageId;
            this.aiSeqNum = aiSeqNum;
            this.title = title;
        }

        public String getSessionId() { return sessionId; }
        public Long getSessionPk() { return sessionPk; }
        public Long getAiMessageId() { return aiMessageId; }
        public Integer getAiSeqNum() { return aiSeqNum; }
        public String getTitle() { return title; }
    }
}
