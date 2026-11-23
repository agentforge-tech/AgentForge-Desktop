package cloud.changlu.agentforge.desktop.modules.session;

import cloud.changlu.agentforge.desktop.modules.session.domain.SessionVo;
import cloud.changlu.agentforge.desktop.modules.session.service.AgentSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        properties = {
            "agentforge.desktop.root-dir=target/test-session-context",
            "spring.main.web-application-type=none"
        })
class SessionContextTest {

    @Autowired private AgentSessionService sessionService;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void updateAndLoadLatestSessionContext() {
        AgentSessionService.SessionInitResult init = sessionService.initChat(
                "agt_default", "context-" + UUID.randomUUID(), "hello", 1L, 1L, null, null);

        ObjectNode context = objectMapper.createObjectNode();
        context.put("modelId", 2);
        context.put("message", "[[agentforge-resource:file:%2Ftmp%2Fa.txt]]继续");
        context.put("projectPath", "/tmp/workspace");
        context.putArray("files")
                .addObject()
                .put("path", "/tmp/a.txt")
                .put("name", "a.txt")
                .put("type", "file");

        assertTrue(sessionService.updateContext(init.getSessionPk(), context));

        SessionVo detail = sessionService.detail(init.getSessionPk());
        assertEquals(2, detail.getContext().get("modelId").asInt());
        assertEquals("/tmp/workspace", detail.getContext().get("projectPath").asText());
        assertEquals("a.txt", detail.getContext().get("files").get(0).get("name").asText());

        assertTrue(sessionService.updateContext(init.getSessionPk(), null));
        assertNull(sessionService.detail(init.getSessionPk()).getContext());
    }
}
