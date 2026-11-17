package cloud.changlu.agentforge.desktop.modules.chat;

import cloud.changlu.agentforge.desktop.modules.session.dao.AgentSessionMessageMapper;
import cloud.changlu.agentforge.desktop.modules.session.domain.AgentSessionMessagePojo;
import cloud.changlu.agentforge.desktop.modules.session.enums.SessionMessageStatusEnum;
import cloud.changlu.agentforge.desktop.modules.session.service.AgentSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "agentforge.desktop.root-dir=target/test-stop-store")
class ChatStopMapperTest {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired private AgentSessionService sessionService;
    @Autowired private AgentSessionMessageMapper messageMapper;

    @Test
    void cancelJourneyPersistsExecutionIdAndUsesConditionalTerminalUpdates() {
        AgentSessionService.SessionInitResult init = initChat();
        messageMapper.updateStarted(
                init.getAiMessageId(),
                SessionMessageStatusEnum.RUNNING.getCode(),
                now(),
                "exec-1",
                now());

        AgentSessionMessagePojo running = messageMapper.selectById(init.getAiMessageId());
        assertEquals("exec-1", running.getExecutionId());
        assertEquals(init.getAiMessageId(), messageMapper.selectActiveAiMessage(init.getSessionPk()).getId());

        assertEquals(1, messageMapper.markCancelling(init.getAiMessageId(), now()));
        assertEquals(
                SessionMessageStatusEnum.CANCELLING.getCode(),
                messageMapper.selectById(init.getAiMessageId()).getStatus());

        assertEquals(
                1,
                messageMapper.timeoutCancel(
                        init.getAiMessageId(),
                        SessionMessageStatusEnum.TIMEOUT_CANCELLED.getCode(),
                        100L,
                        now(),
                        now()));
        AgentSessionMessagePojo timedOut = messageMapper.selectById(init.getAiMessageId());
        assertEquals(SessionMessageStatusEnum.TIMEOUT_CANCELLED.getCode(), timedOut.getStatus());
        assertEquals(Long.valueOf(100L), timedOut.getElapsedMs());

        assertEquals(
                0,
                messageMapper.finish(
                        init.getAiMessageId(),
                        SessionMessageStatusEnum.CANCELLED.getCode(),
                        "late",
                        null,
                        200L,
                        now(),
                        now()));
        assertEquals(
                SessionMessageStatusEnum.TIMEOUT_CANCELLED.getCode(),
                messageMapper.selectById(init.getAiMessageId()).getStatus());
    }

    @Test
    void normalCancelFinishesWhileRunning() {
        AgentSessionService.SessionInitResult init = initChat();
        messageMapper.updateStarted(
                init.getAiMessageId(),
                SessionMessageStatusEnum.RUNNING.getCode(),
                now(),
                "exec-2",
                now());
        messageMapper.markCancelling(init.getAiMessageId(), now());

        assertEquals(
                1,
                messageMapper.finish(
                        init.getAiMessageId(),
                        SessionMessageStatusEnum.CANCELLED.getCode(),
                        "用户已停止",
                        null,
                        50L,
                        now(),
                        now()));
        assertEquals(
                SessionMessageStatusEnum.CANCELLED.getCode(),
                messageMapper.selectById(init.getAiMessageId()).getStatus());
    }

    private AgentSessionService.SessionInitResult initChat() {
        AgentSessionService.SessionInitResult init =
                sessionService.initChat(
                        "agt_default", "session-" + UUID.randomUUID(), "hello", 1L, 1L, null, null);
        assertNotNull(init.getAiMessageId());
        return init;
    }

    private static String now() {
        return LocalDateTime.now().format(FORMATTER);
    }
}
