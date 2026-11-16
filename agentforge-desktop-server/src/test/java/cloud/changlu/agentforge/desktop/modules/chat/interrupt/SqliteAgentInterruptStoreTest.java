package cloud.changlu.agentforge.desktop.modules.chat.interrupt;

import cloud.changlu.agentforge.agent.governance.interrupt.domain.AgentInterruptSnapshot;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptBatchStatus;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptRequest;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptType;
import cloud.changlu.agentforge.agent.governance.interrupt.spi.AgentInterruptStore;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "agentforge.desktop.root-dir=target/test-interrupt-store")
class SqliteAgentInterruptStoreTest {

    @Autowired private AgentInterruptStore store;

    @Test
    void snapshotLifecycleUsesDatabaseCas() {
        String batchId = "batch_" + UUID.randomUUID();
        ToolExecutionRequest bash =
                ToolExecutionRequest.from(
                        "call_" + UUID.randomUUID(),
                        "bash",
                        "{\"command\":\"rm -rf build\"}");
        AgentInterruptSnapshot snapshot =
                AgentInterruptSnapshot.initial(
                        batchId,
                        "session-" + UUID.randomUUID(),
                        "exec-" + UUID.randomUUID(),
                        2,
                        Collections.singletonList(bash),
                        Collections.singletonList(
                                new InterruptRequest(
                                        "int_" + UUID.randomUUID(),
                                        InterruptType.PERMISSION_APPROVAL,
                                        bash,
                                        null)));

        store.save(snapshot);
        AgentInterruptSnapshot loaded = store.get(batchId);
        assertEquals(InterruptBatchStatus.PENDING, loaded.getStatus());
        assertEquals(bash.arguments(), loaded.getToolRequests().get(0).arguments());
        assertThrows(IllegalStateException.class, () -> store.save(snapshot));

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
}