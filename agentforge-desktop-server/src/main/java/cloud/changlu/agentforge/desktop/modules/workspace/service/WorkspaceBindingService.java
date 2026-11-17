package cloud.changlu.agentforge.desktop.modules.workspace.service;

import cloud.changlu.agentforge.desktop.config.DesktopProperties;
import cloud.changlu.agentforge.desktop.modules.session.dao.AgentSessionMapper;
import cloud.changlu.agentforge.desktop.modules.session.domain.AgentSessionPojo;
import cloud.changlu.agentforge.desktop.modules.workspace.dao.WorkspaceMapper;
import cloud.changlu.agentforge.desktop.modules.workspace.domain.WorkspacePojo;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class WorkspaceBindingService {
    private static final DateTimeFormatter FOLDER_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
    private static final DateTimeFormatter DB_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final AgentSessionMapper sessionMapper;
    private final WorkspaceMapper workspaceMapper;
    private final DesktopProperties properties;

    public WorkspaceBindingService(AgentSessionMapper sessionMapper, WorkspaceMapper workspaceMapper, DesktopProperties properties) {
        this.sessionMapper = sessionMapper;
        this.workspaceMapper = workspaceMapper;
        this.properties = properties;
    }

    public Binding prepare(String aid, String sessionId, Long workspaceId, String legacyProjectPath) {
        AgentSessionPojo existing = sessionMapper.selectBySessionIdAndAid(sessionId, aid);
        if (existing != null) {
            if (existing.getWorkspacePath() == null || existing.getWorkspacePath().trim().isEmpty()) {
                return createDefault();
            }
            Path path = Paths.get(existing.getWorkspacePath()).toAbsolutePath().normalize();
            if (!Files.isDirectory(path)) throw new IllegalStateException("会话工作目录不存在: " + path);
            return new Binding(existing.getWorkspaceId(), path.toString());
        }
        if (workspaceId == null && legacyProjectPath != null && !legacyProjectPath.trim().isEmpty()) {
            Path legacy = Paths.get(legacyProjectPath.trim()).toAbsolutePath().normalize();
            if (!Files.isDirectory(legacy)) throw new IllegalArgumentException("所选工作目录不可用: " + legacy);
            WorkspacePojo registered = workspaceMapper.selectByFolderPath(legacy.toString());
            return new Binding(registered == null ? null : registered.getId(), legacy.toString());
        }
        if (workspaceId == null) return createDefault();
        WorkspacePojo workspace = workspaceMapper.selectById(workspaceId);
        if (workspace == null) throw new IllegalArgumentException("工作空间不存在或已移除");
        Path path = Paths.get(workspace.getFolderPath()).toAbsolutePath().normalize();
        try {
            if (Integer.valueOf(1).equals(workspace.getWorkspaceType())) Files.createDirectories(path);
            if (!Files.isDirectory(path)) throw new IllegalStateException("工作空间目录不可用: " + path);
            if (!Integer.valueOf(1).equals(workspace.getStatus())) {
                workspaceMapper.updateStatus(workspace.getId(), 1, LocalDateTime.now().format(DB_FORMATTER));
            }
            return new Binding(workspace.getId(), path.toString());
        } catch (IOException e) {
            throw new IllegalStateException("创建工作空间失败: " + path, e);
        }
    }

    private Binding createDefault() {
        Path root = properties.rootPath().resolve("workspace").toAbsolutePath().normalize();
        String base = LocalDateTime.now().format(FOLDER_FORMATTER);
        try {
            Files.createDirectories(root);
            for (int index = 1; index < 100; index++) {
                String name = index == 1 ? base : base + "-" + String.format("%02d", index);
                Path candidate = root.resolve(name);
                try {
                    Files.createDirectory(candidate);
                    return new Binding(null, candidate.toString());
                } catch (java.nio.file.FileAlreadyExistsException ignored) {
                    // try the next suffix
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("创建默认任务目录失败: " + root, e);
        }
        throw new IllegalStateException("同一时间创建的任务过多，请稍后重试");
    }

    public static class Binding {
        private final Long workspaceId;
        private final String workspacePath;
        public Binding(Long workspaceId, String workspacePath) {
            this.workspaceId = workspaceId;
            this.workspacePath = workspacePath;
        }
        public Long getWorkspaceId() { return workspaceId; }
        public String getWorkspacePath() { return workspacePath; }
    }
}
