package cloud.changlu.agentforge.desktop.modules.chat.interrupt;

import cloud.changlu.agentforge.agent.governance.interrupt.domain.AgentInterruptSnapshot;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptRequest;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptType;
import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionDenial;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserAnswerType;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserRequest;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserSupport;
import cloud.changlu.agentforge.desktop.modules.chat.domain.AgentInterruptSnapshotPojo;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentInterruptSnapshotCodecTest {

    private final AgentInterruptSnapshotCodec codec =
            new AgentInterruptSnapshotCodec(new ObjectMapper());

    @Test
    void askUserSnapshotRoundTripKeepsQuestionsAndOptions() {
        ToolExecutionRequest ask =
                ToolExecutionRequest.from(
                        "call_ask",
                        "ask_user",
                        "{\"questions\":[{\"id\":\"env\",\"title\":\"环境\",\"type\":\"single_choice\",\"options\":[{\"id\":\"test\",\"label\":\"测试\"},{\"id\":\"prod\",\"label\":\"生产\"}]}],"
                                + "\"reason\":\"发布前确认\",\"submitLabel\":\"继续\"}");
        AskUserRequest payload = AskUserSupport.parse(ask);
        AgentInterruptSnapshot snapshot =
                snapshot(
                        ask,
                        Collections.singletonList(
                                new InterruptRequest(
                                        "int_1", InterruptType.ASK_USER, ask, payload)));

        AgentInterruptSnapshot decoded = codec.decode(pojo(snapshot));

        assertEquals("int_1", decoded.getInterrupts().get(0).getInterruptId());
        AskUserRequest actual = decoded.getInterrupts().get(0).getAskUserRequest();
        assertEquals("env", actual.getQuestions().get(0).getId());
        assertEquals(AskUserAnswerType.SINGLE_CHOICE, actual.getQuestions().get(0).getType());
        assertEquals(2, actual.getQuestions().get(0).getOptions().size());
        assertEquals("继续", actual.getSubmitLabel());
    }

    @Test
    void permissionSnapshotKeepsDenialsAndNullPayload() {
        ToolExecutionRequest bash =
                ToolExecutionRequest.from(
                        "call_bash", "bash", "{\"command\":\"rm -rf build\"}");
        AgentInterruptSnapshot snapshot =
                snapshot(
                        bash,
                        Collections.singletonList(
                                new InterruptRequest(
                                        "int_1", InterruptType.PERMISSION_APPROVAL, bash, null)),
                        Collections.singletonList(
                                new PermissionDenial(bash, "tool call denied by permission policy")));

        AgentInterruptSnapshot decoded = codec.decode(pojo(snapshot));

        assertNull(decoded.getInterrupts().get(0).getPayload());
        assertEquals(1, decoded.getDenials().size());
        assertTrue(decoded.getToolRequests().get(0).arguments().contains("rm -rf build"));
    }

    @Test
    void corruptedSchemaVersionFailsClosed() {
        ToolExecutionRequest bash =
                ToolExecutionRequest.from("call_bash", "bash", "{\"command\":\"ls\"}");
        AgentInterruptSnapshot snapshot = snapshot(bash, Collections.<InterruptRequest>emptyList());
        AgentInterruptSnapshotPojo pojo = pojo(snapshot);
        pojo.setSchemaVersion(99);

        assertThrows(IllegalStateException.class, () -> codec.decode(pojo));
    }

    @Test
    void nonStringMemoryIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> codec.memoryId(123L));
        assertTrue(codec.memoryId("session-1").length() > 0);
    }

    private static AgentInterruptSnapshot snapshot(
            ToolExecutionRequest request, List<InterruptRequest> interrupts) {
        return snapshot(request, interrupts, Collections.<PermissionDenial>emptyList());
    }

    private static AgentInterruptSnapshot snapshot(
            ToolExecutionRequest request,
            List<InterruptRequest> interrupts,
            List<PermissionDenial> denials) {
        return AgentInterruptSnapshot.initial(
                "batch_1",
                "session-1",
                "exec-1",
                3,
                Collections.singletonList(request),
                interrupts,
                denials);
    }

    private AgentInterruptSnapshotPojo pojo(AgentInterruptSnapshot snapshot) {
        AgentInterruptSnapshotPojo pojo = new AgentInterruptSnapshotPojo();
        pojo.setBatchId(snapshot.getBatchId());
        pojo.setMemoryId(codec.memoryId(snapshot.getMemoryId()));
        pojo.setSourceExecutionId(snapshot.getSourceExecutionId());
        pojo.setStep(snapshot.getStep());
        pojo.setStatus(snapshot.getStatus().name());
        pojo.setSchemaVersion(codec.schemaVersion());
        pojo.setSnapshotJson(codec.encode(snapshot));
        return pojo;
    }
}