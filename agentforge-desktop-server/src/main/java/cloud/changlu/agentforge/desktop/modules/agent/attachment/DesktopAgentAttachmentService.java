package cloud.changlu.agentforge.desktop.modules.agent.attachment;

import cloud.changlu.agentforge.agent.domain.AgentAttachment;
import cloud.changlu.agentforge.agent.domain.AgentAttachmentType;
import cloud.changlu.agentforge.agent.domain.AttachmentPayloadType;
import cloud.changlu.agentforge.desktop.modules.chat.domain.ChatStreamRequest;
import cloud.changlu.agentforge.desktop.modules.resource.domain.ResolvedResource;
import cloud.changlu.agentforge.desktop.modules.resource.service.DesktopResourceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把本地资源转换为框架层附件，并生成用户消息的请求快照。
 *
 * @author changlu
 * @since 2026-11-22
 */
@Service
public class DesktopAgentAttachmentService {

    private static final int MAX_TEXT_ATTACHMENT_LENGTH = 12000;

    private final DesktopResourceService resourceService;
    private final DesktopAttachmentRoutingService routingService;
    private final ObjectMapper objectMapper;

    public DesktopAgentAttachmentService(
            DesktopResourceService resourceService,
            DesktopAttachmentRoutingService routingService,
            ObjectMapper objectMapper) {
        this.resourceService = resourceService;
        this.routingService = routingService;
        this.objectMapper = objectMapper;
    }

    public List<AgentAttachment> toAgentAttachments(
            List<ResolvedResource> resources, boolean supportsImage, int providerCode) {
        if (resources == null || resources.isEmpty()) {
            return new ArrayList<AgentAttachment>();
        }
        List<AgentAttachment> attachments = new ArrayList<AgentAttachment>(resources.size());
        for (ResolvedResource resource : resources) {
            AgentAttachmentType type = toAttachmentType(resource);
            AttachmentPayloadType payload =
                    routingService.route(
                            type,
                            resource.getUrl(),
                            resource.getSize(),
                            supportsImage,
                            providerCode);
            AgentAttachment.Builder builder =
                    AgentAttachment.builder()
                            .name(resource.getName())
                            .type(type)
                            .fileType(resource.getFileType())
                            .size(resource.getSize())
                            .url(resource.getUrl())
                            .payloadType(payload);
            if (payload == AttachmentPayloadType.TEXT) {
                builder.text(buildTextPayload(resource));
            }
            attachments.add(builder.build());
        }
        return attachments;
    }

    public String buildUserReqJson(
            ChatStreamRequest body, List<ResolvedResource> resources, String displayMessage) {
        Map<String, Object> req = new LinkedHashMap<String, Object>();
        if (body != null) {
            req.put("aid", body.getAid());
            req.put("sessionId", body.getSessionId());
            req.put("message", displayMessage);
            // 保留带内联资源标记的原始消息，历史回放时可在气泡内还原输入框中的资源位置。
            req.put("rawMessage", body.getMessage());
            req.put("modelId", body.getModelId());
            req.put("mode", body.getMode());
            req.put("workspaceId", body.getWorkspaceId());
            req.put("projectPath", body.getProjectPath());
        }
        List<Map<String, Object>> files = new ArrayList<Map<String, Object>>();
        if (resources != null) {
            for (ResolvedResource resource : resources) {
                Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
                snapshot.put("path", resource.getUrl());
                snapshot.put("originFileName", resource.getName());
                snapshot.put("fileType", resource.getFileType());
                snapshot.put("fileSize", resource.getSize());
                snapshot.put("type", resource.getType());
                files.add(snapshot);
            }
        }
        req.put("files", files);
        try {
            return objectMapper.writeValueAsString(req);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private AgentAttachmentType toAttachmentType(ResolvedResource resource) {
        if (!resource.isDirectory() && resourceService.isImage(resource.getFileType())) {
            return AgentAttachmentType.IMAGE;
        }
        if (!resource.isDirectory() && resourceService.isText(resource.getFileType())) {
            return AgentAttachmentType.TEXT;
        }
        return AgentAttachmentType.UNKNOWN;
    }

    private String buildTextPayload(ResolvedResource resource) {
        StringBuilder builder = new StringBuilder();
        builder.append(resource.isDirectory() ? "文件夹：" : "文件：").append(resource.getName());
        if (resource.getFileType() != null && !resource.getFileType().trim().isEmpty()) {
            builder.append("（类型 ").append(resource.getFileType());
            if (!resource.isDirectory()) {
                builder.append("，大小 ").append(resource.getSize()).append(" bytes");
            }
            builder.append("）");
        }
        builder.append("，本机路径 ").append(resource.getUrl());
        if (resource.isDirectory()) {
            builder.append("\n这是一个目录，以下是目录内容概览；如需深入查看可继续调用 read_resource：\n")
                    .append(listDirectory(resource.getPath()));
            return builder.toString();
        }
        if (resourceService.isText(resource.getFileType())) {
            String text = readTextHead(resource.getPath());
            if (text != null && !text.trim().isEmpty()) {
                builder.append("\n以下是文件正文内容：\n").append(text);
            }
        }
        return builder.toString();
    }

    private String listDirectory(Path path) {
        StringBuilder builder = new StringBuilder();
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
            for (Path child : stream) {
                if (count >= 100) {
                    builder.append("\n...");
                    break;
                }
                if (count > 0) {
                    builder.append('\n');
                }
                builder.append(Files.isDirectory(child) ? "[目录] " : "[文件] ");
                builder.append(child.getFileName() == null ? child.toString() : child.getFileName().toString());
                count++;
            }
        } catch (IOException e) {
            return "目录读取失败：" + e.getMessage();
        }
        return builder.length() == 0 ? "（空目录）" : builder.toString();
    }

    private String readTextHead(Path path) {
        try {
            long size = Files.size(path);
            if (size > MAX_TEXT_ATTACHMENT_LENGTH * 4L) {
                byte[] head = new byte[MAX_TEXT_ATTACHMENT_LENGTH * 4];
                try (java.io.InputStream input = Files.newInputStream(path)) {
                    int read = input.read(head);
                    if (read <= 0) {
                        return null;
                    }
                    String text = new String(head, 0, read, StandardCharsets.UTF_8);
                    return truncate(text);
                }
            }
            byte[] bytes = Files.readAllBytes(path);
            return truncate(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        if (text.length() <= MAX_TEXT_ATTACHMENT_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_TEXT_ATTACHMENT_LENGTH) + "\n...[内容过长，已截断]";
    }
}
