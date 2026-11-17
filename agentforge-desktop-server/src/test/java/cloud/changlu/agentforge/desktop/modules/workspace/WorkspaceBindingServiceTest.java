package cloud.changlu.agentforge.desktop.modules.workspace;

import cloud.changlu.agentforge.desktop.modules.session.dao.AgentSessionMapper;
import cloud.changlu.agentforge.desktop.modules.session.domain.AgentSessionPojo;
import cloud.changlu.agentforge.desktop.modules.session.service.AgentSessionService;
import cloud.changlu.agentforge.desktop.modules.workspace.domain.WorkspacePojo;
import cloud.changlu.agentforge.desktop.modules.workspace.service.WorkspaceBindingService;
import cloud.changlu.agentforge.desktop.modules.workspace.service.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        properties = {
            "agentforge.desktop.root-dir=target/test-workspace-store",
            "spring.main.web-application-type=none"
        })
class WorkspaceBindingServiceTest {
    @Autowired private WorkspaceService workspaceService;
    @Autowired private WorkspaceBindingService bindingService;
    @Autowired private AgentSessionService sessionService;
    @Autowired private AgentSessionMapper sessionMapper;

    @Test
    void defaultTaskCreatesAnIndependentFolderAndPersistsPath() {
        String sessionId = "workspace-default-" + UUID.randomUUID();
        WorkspaceBindingService.Binding binding = bindingService.prepare("agt_default", sessionId, null, null);

        assertNull(binding.getWorkspaceId());
        assertTrue(Files.isDirectory(Paths.get(binding.getWorkspacePath())));

        sessionService.initChat("agt_default", sessionId, "hello", 1L, 1L,
                binding.getWorkspaceId(), binding.getWorkspacePath());
        AgentSessionPojo session = sessionMapper.selectBySessionIdAndAid(sessionId, "agt_default");
        assertNotNull(session);
        assertNull(session.getWorkspaceId());
        assertEquals(binding.getWorkspacePath(), session.getWorkspacePath());
    }

    @Test
    void managedWorkspaceIsCreatedOnFirstBindingAndReused() {
        WorkspacePojo workspace = workspaceService.create("workspace-" + UUID.randomUUID(), null);
        assertEquals(Integer.valueOf(0), workspace.getStatus());

        WorkspaceBindingService.Binding first = bindingService.prepare(
                "agt_default", "workspace-managed-" + UUID.randomUUID(), workspace.getId(), null);
        assertEquals(workspace.getId(), first.getWorkspaceId());
        assertTrue(Files.isDirectory(Paths.get(first.getWorkspacePath())));

        WorkspaceBindingService.Binding second = bindingService.prepare(
                "agt_default", "workspace-managed-" + UUID.randomUUID(), workspace.getId(), null);
        assertEquals(first.getWorkspacePath(), second.getWorkspacePath());
    }
}
