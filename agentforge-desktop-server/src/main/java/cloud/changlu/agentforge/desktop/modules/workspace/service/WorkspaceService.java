package cloud.changlu.agentforge.desktop.modules.workspace.service;

import cloud.changlu.agentforge.desktop.config.DesktopProperties;
import cloud.changlu.agentforge.desktop.modules.session.dao.AgentSessionMapper;
import cloud.changlu.agentforge.desktop.modules.workspace.dao.WorkspaceMapper;
import cloud.changlu.agentforge.desktop.modules.workspace.domain.WorkspacePojo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class WorkspaceService {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final WorkspaceMapper mapper;
    private final AgentSessionMapper sessionMapper;
    private final DesktopProperties properties;

    public WorkspaceService(WorkspaceMapper mapper, AgentSessionMapper sessionMapper, DesktopProperties properties) {
        this.mapper = mapper;
        this.sessionMapper = sessionMapper;
        this.properties = properties;
    }

    public List<WorkspacePojo> list() { return mapper.selectList(); }

    public WorkspacePojo create(String rawName, String remark) {
        String name = normalizeName(rawName);
        Path root = properties.rootPath().resolve("workspace").toAbsolutePath().normalize();
        Path folder = root.resolve(name).normalize();
        if (!folder.startsWith(root)) throw new IllegalArgumentException("工作空间名称包含非法路径");
        WorkspacePojo existing = mapper.selectByFolderPath(folder.toString());
        if (existing != null) throw new IllegalArgumentException("同名工作空间已存在");
        WorkspacePojo pojo = base(name, folder.toString(), 1, 0);
        pojo.setRemark(trimToNull(remark));
        mapper.insert(pojo);
        return pojo;
    }

    public WorkspacePojo importFolder(String rawName, String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) throw new IllegalArgumentException("文件夹路径不能为空");
        Path folder = Paths.get(rawPath.trim()).toAbsolutePath().normalize();
        if (!Files.isDirectory(folder)) throw new IllegalArgumentException("所选路径不是可用文件夹");
        WorkspacePojo existing = mapper.selectByFolderPath(folder.toString());
        if (existing != null) return existing;
        Path fileName = folder.getFileName();
        String name = rawName == null || rawName.trim().isEmpty()
                ? (fileName == null ? folder.toString() : fileName.toString())
                : normalizeName(rawName);
        WorkspacePojo pojo = base(name, folder.toString(), 2, 1);
        mapper.insert(pojo);
        return pojo;
    }

    public WorkspacePojo require(Long id) {
        if (id == null) throw new IllegalArgumentException("workspaceId 不能为空");
        WorkspacePojo pojo = mapper.selectById(id);
        if (pojo == null) throw new IllegalArgumentException("工作空间不存在或已移除");
        return pojo;
    }

    public WorkspacePojo rename(Long id, String rawName) {
        WorkspacePojo pojo = require(id);
        String name = normalizeName(rawName);
        String now = LocalDateTime.now().format(FORMATTER);
        if (mapper.updateName(id, name, now) == 0) throw new IllegalArgumentException("工作空间不存在或已移除");
        pojo.setName(name);
        pojo.setModifyTime(now);
        return pojo;
    }

    @Transactional
    public void delete(Long id) {
        require(id);
        String now = LocalDateTime.now().format(FORMATTER);
        sessionMapper.clearWorkspace(id, now);
        if (mapper.updateStatus(id, 3, now) == 0) throw new IllegalArgumentException("工作空间不存在或已移除");
    }

    private WorkspacePojo base(String name, String folderPath, int type, int status) {
        String now = LocalDateTime.now().format(FORMATTER);
        WorkspacePojo pojo = new WorkspacePojo();
        pojo.setName(name);
        pojo.setFolderPath(folderPath);
        pojo.setWorkspaceType(type);
        pojo.setStatus(status);
        pojo.setSortOrder(0);
        pojo.setCreateTime(now);
        pojo.setModifyTime(now);
        return pojo;
    }

    private static String normalizeName(String raw) {
        if (raw == null || raw.trim().isEmpty()) throw new IllegalArgumentException("工作空间名称不能为空");
        String name = raw.trim();
        if (name.length() > 60) throw new IllegalArgumentException("工作空间名称不能超过 60 个字符");
        if (name.contains("/") || name.contains("\\") || name.contains("..") || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("工作空间名称包含非法字符");
        }
        return name;
    }

    private static String trimToNull(String text) {
        return text == null || text.trim().isEmpty() ? null : text.trim();
    }
}
