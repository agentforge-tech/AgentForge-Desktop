package cloud.changlu.agentforge.desktop.modules.chat.governance;

import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.domain.AgentRequest;
import cloud.changlu.agentforge.agent.governance.PermissionInterruptModule;
import cloud.changlu.agentforge.agent.governance.interrupt.support.InMemoryAgentInterruptStore;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptType;
import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionPreflightResult;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopPermissionModuleTest {

    @Test
    void rmCommandRequiresApproval() {
        PermissionPreflightResult result =
                module().preflight(
                                1,
                                Collections.singletonList(bash("rm -rf build")),
                                context());

        assertTrue(result.isSuspended());
        assertTrue(
                result.getSuspendInfo().getInterrupts().get(0).getType()
                        == InterruptType.PERMISSION_APPROVAL);
    }

    @Test
    void safeCommandPassesWithoutInterrupt() {
        PermissionPreflightResult result =
                module().preflight(
                                1,
                                Collections.singletonList(bash("ls -la && git status")),
                                context());

        assertFalse(result.isSuspended());
        assertFalse(result.hasDenials());
    }

    @Test
    void askUserIsBuiltIn() {
        PermissionPreflightResult result =
                module().preflight(
                                1,
                                Collections.singletonList(
                                        ToolExecutionRequest.from(
                                                "call_1",
                                                "ask_user",
                                                "{\"questions\":[{\"id\":\"q1\",\"title\":\"继续吗\",\"type\":\"single_choice\",\"options\":[{\"id\":\"yes\",\"label\":\"继续\"},{\"id\":\"no\",\"label\":\"停止\"}]}]}")),
                                context());

        assertTrue(result.isSuspended());
        assertTrue(
                result.getSuspendInfo().getInterrupts().get(0).getType() == InterruptType.ASK_USER);
    }

    @Test
    void shellRuleDetectsCompoundAndWrappedCommands() {
        ShellCommandPermissionRule rule =
                new ShellCommandPermissionRule("test", cloud.changlu.agentforge.agent.governance.permission.domain.PermissionDecision.ASK);

        assertTrue(rule.matches("cd project && rm -rf target"));
        assertTrue(rule.matches("sudo /bin/rm notes.txt"));
        assertTrue(rule.matches("git reset --hard HEAD"));
        assertFalse(rule.matches("rg -n 'rm ' src"));
    }

    private static PermissionInterruptModule module() {
        return DesktopPermissionModule.build(new InMemoryAgentInterruptStore());
    }

    private static ToolExecutionRequest bash(String command) {
        return ToolExecutionRequest.from(
                "call_bash", "bash", "{\"command\":\"" + command.replace("\"", "\\\"") + "\"}");
    }

    private static AgentChatContext context() {
        AgentRequest request = new AgentRequest();
        request.setMemoryId("session-1");
        request.setExecutionId("exec-1");
        return AgentChatContext.builder().executionId("exec-1").request(request).build();
    }
}