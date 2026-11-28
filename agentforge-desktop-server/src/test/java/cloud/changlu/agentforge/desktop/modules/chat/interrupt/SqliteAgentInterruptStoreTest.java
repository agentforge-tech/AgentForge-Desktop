package cloud.changlu.agentforge.desktop.modules.chat.interrupt;

import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.domain.AgentRequest;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.AgentInterruptSnapshot;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptBatchStatus;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptRequest;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptType;
import cloud.changlu.agentforge.agent.governance.interrupt.spi.AgentInterruptStore;
import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionMode;
import cloud.changlu.agentforge.desktop.modules.chat.context.DesktopAgentContextAccessor;
import cloud.changlu.agentforge.desktop.modules.chat.context.DesktopExecutionContext;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "agentforge.desktop.root-dir=target/test-interrupt-store")
class SqliteAgentInterruptStoreTest {

    @Autowired private AgentInterruptStore store;
    @Autowired private DesktopAgentContextAccessor contextAccessor;

    @Test
    void snapshotLifecycleUsesDatabaseCas() {
        String batchId = "batch_" + UUID.randomUUID();
        String sessionId = "session-" + UUID.randomUUID();
        ToolExecutionRequest bash =
                ToolExecutionRequest.from(
                        "call_" + UUID.randomUUID(),
                        "bash",
                        "{\"command\":\"rm -rf build\"}");
        AgentInterruptSnapshot snapshot =
                AgentInterruptSnapshot.initial(
                        batchId,
                        sessionId,
                        "exec-" + UUID.randomUUID(),
                        2,
                        Collections.singletonList(bash),
                        Collections.singletonList(
                                new InterruptRequest(
                                        "int_" + UUID.randomUUID(),
                                        InterruptType.PERMISSION_APPROVAL,
                                        bash,
                                        null)));

        store.save(snapshot, chatContext(sessionId, 9012L));
        AgentInterruptSnapshot loaded = store.get(batchId);
        assertEquals(InterruptBatchStatus.PENDING, loaded.getStatus());
        assertEquals(bash.arguments(), loaded.getToolRequests().get(0).arguments());
        assertEquals(
                "9012", loaded.getContextAttributes().get(DesktopAgentContextAccessor.AI_MESSAGE_ID));
        assertEquals(
                "agt_test", loaded.getContextAttributes().get(DesktopAgentContextAccessor.AID));
        assertThrows(IllegalStateException.class, () -> store.save(snapshot, chatContext(sessionId, 9012L)));

        store.claim(batchId);
        assertEquals(InterruptBatchStatus.RESUMING, store.get(batchId).getStatus());
        IllegalStateException resumed =
                assertThrows(IllegalStateException.class, () -> store.claim(batchId));
        assertTrue(resumed.getMessage().contains("being resumed"));

        store.complete(batchId);
        assertEquals(InterruptBatchStatus.COMPLETED, store.get(batchId).getStatus());
        IllegalStateException completed =
                assertThrows(IllegalStateException.class, () -> store.claim(batchId));
        assertTrue(completed.getMessage().contains("already completed"));

        store.complete("missing_" + UUID.randomUUID());
    }

    @Test
    void cancelPendingByMemoryIdOnlyCancelsPending() {
        String sessionId = "session-" + UUID.randomUUID();
        ToolExecutionRequest bash =
                ToolExecutionRequest.from("call_" + UUID.randomUUID(), "bash", "{\"command\":\"ls\"}");
        AgentInterruptSnapshot pending =
                AgentInterruptSnapshot.initial(
                        "batch_" + UUID.randomUUID(),
                        sessionId,
                        "exec-" + UUID.randomUUID(),
                        1,
                        Collections.singletonList(bash),
                        Collections.singletonList(
                                new InterruptRequest(
                                        "int_" + UUID.randomUUID(),
                                        InterruptType.PERMISSION_APPROVAL,
                                        bash,
                                        null)));
        store.save(pending, chatContext(sessionId, 1L));

        AgentInterruptSnapshot cancelled = store.cancelPendingByMemoryId(sessionId);
        assertNotNull(cancelled);
        assertEquals(InterruptBatchStatus.CANCELLED, store.get(pending.getBatchId()).getStatus());
        assertNull(store.cancelPendingByMemoryId(sessionId));
    }

    @Test
    void saveWithoutAttributionFailsClosed() {
        String sessionId = "session-" + UUID.randomUUID();
        ToolExecutionRequest bash =
                ToolExecutionRequest.from("call_" + UUID.randomUUID(), "bash", "{\"command\":\"ls\"}");
        AgentInterruptSnapshot snapshot =
                AgentInterruptSnapshot.initial(
                        "batch_" + UUID.randomUUID(),
                        sessionId,
                        "exec-" + UUID.randomUUID(),
                        1,
                        Collections.singletonList(bash),
                        Collections.singletonList(
                                new InterruptRequest(
                                        "int_" + UUID.randomUUID(),
                                        InterruptType.PERMISSION_APPROVAL,
                                        bash,
                                        null)));

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> store.save(snapshot, AgentChatContext.builder().build()));
        assertTrue(error.getMessage().contains("HITL_CONTEXT_ATTRIBUTE_MISSING"));
    }

    private AgentChatContext chatContext(String sessionId, Long aiMessageId) {
        Map<String, Object> extensions = new HashMap<String, Object>();
        contextAccessor.write(
                extensions,
                new DesktopExecutionContext(
                        aiMessageId,
                        sessionId,
                        1L,
                        "agt_test",
                        2L,
                        "/tmp/workspace",
                        PermissionMode.DEFAULT));
        return AgentChatContext.builder()
                .request(
                        AgentRequest.builder()
                                .memoryId(sessionId)
                                .question("test")
                                .build())
                .extensions(extensions)
                .build();
    }
}
