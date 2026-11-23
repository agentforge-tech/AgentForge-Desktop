package cloud.changlu.agentforge.desktop.modules.resource.service;

import cloud.changlu.agentforge.desktop.modules.resource.domain.ResolvedResource;
import cloud.changlu.agentforge.desktop.modules.resource.domain.ResourceRef;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 桌面端本地资源解析与 prompt 替换。
 *
 * <p>输入框内的资源以 {@code [[agentforge-resource:type:encodedPath]]} 标记锚定位置。请求进入后端后，
 * 先把标记替换为模型可理解的路径引用，再把图片/文本文件转换为框架层附件。
 *
 * @author changlu
 * @since 2026-11-22
 */
@Service
public class DesktopResourceService {

    /** 单条消息最多携带的资源数量，与 AgentForge service 保持一致。 */
    private static final int MAX_RESOURCES_PER_MESSAGE = 5;

    private static final Pattern RESOURCE_PATTERN =
            Pattern.compile("\\[\\[agentforge-resource:([^:\\]]+):([^\\]]+)\\]\\]");

    private static final Set<String> IMAGE_EXTENSIONS = new LinkedHashSet<String>();
    private static final Set<String> TEXT_EXTENSIONS = new LinkedHashSet<String>();
    private static final Set<String> DOCUMENT_EXTENSIONS = new LinkedHashSet<String>();

    static {
        IMAGE_EXTENSIONS.add("png");
        IMAGE_EXTENSIONS.add("jpg");
        IMAGE_EXTENSIONS.add("jpeg");
        IMAGE_EXTENSIONS.add("gif");
        IMAGE_EXTENSIONS.add("webp");
        IMAGE_EXTENSIONS.add("bmp");
        IMAGE_EXTENSIONS.add("svg");

        TEXT_EXTENSIONS.add("txt");
        TEXT_EXTENSIONS.add("md");
        TEXT_EXTENSIONS.add("markdown");
        TEXT_EXTENSIONS.add("json");
        TEXT_EXTENSIONS.add("yaml");
        TEXT_EXTENSIONS.add("yml");
        TEXT_EXTENSIONS.add("xml");
        TEXT_EXTENSIONS.add("csv");
        TEXT_EXTENSIONS.add("log");
        TEXT_EXTENSIONS.add("java");
        TEXT_EXTENSIONS.add("js");
        TEXT_EXTENSIONS.add("ts");
        TEXT_EXTENSIONS.add("tsx");
        TEXT_EXTENSIONS.add("jsx");
        TEXT_EXTENSIONS.add("vue");
        TEXT_EXTENSIONS.add("py");
        TEXT_EXTENSIONS.add("go");
        TEXT_EXTENSIONS.add("rs");
        TEXT_EXTENSIONS.add("sh");
        TEXT_EXTENSIONS.add("sql");
        TEXT_EXTENSIONS.add("html");
        TEXT_EXTENSIONS.add("htm");
        TEXT_EXTENSIONS.add("css");
        TEXT_EXTENSIONS.add("scss");

        DOCUMENT_EXTENSIONS.add("pdf");
        DOCUMENT_EXTENSIONS.add("doc");
        DOCUMENT_EXTENSIONS.add("docx");
        DOCUMENT_EXTENSIONS.add("xls");
        DOCUMENT_EXTENSIONS.add("xlsx");
        DOCUMENT_EXTENSIONS.add("ppt");
        DOCUMENT_EXTENSIONS.add("pptx");
    }

    /**
     * 校验并解析本次请求引用的本地资源。
     *
     * @throws IllegalArgumentException 资源不存在或数量超限
     */
    public List<ResolvedResource> resolve(List<ResourceRef> references) {
        if (references == null || references.isEmpty()) {
            return new ArrayList<ResolvedResource>();
        }
        Map<String, ResolvedResource> unique =
                new LinkedHashMap<String, ResolvedResource>();
        for (ResourceRef reference : references) {
            if (reference == null || !StringUtils.hasText(reference.getPath())) {
                continue;
            }
            Path path = toPath(reference.getPath());
            if (path == null) {
                continue;
            }
            String identity = path.toAbsolutePath().normalize().toString();
            if (unique.containsKey(identity)) {
                continue;
            }
            if (!Files.exists(path)) {
                throw new IllegalArgumentException("本地资源不存在或已被移动: " + identity);
            }
            unique.put(identity, toResolved(reference, path));
        }
        if (unique.size() > MAX_RESOURCES_PER_MESSAGE) {
            throw new IllegalArgumentException(
                    "单条消息最多携带 " + MAX_RESOURCES_PER_MESSAGE + " 个资源");
        }
        return new ArrayList<ResolvedResource>(unique.values());
    }

    /** 去掉内联资源标记后的可见文本。 */
    public String displayText(String rawMessage) {
        if (rawMessage == null) {
            return "";
        }
        return RESOURCE_PATTERN.matcher(rawMessage).replaceAll("").trim();
    }

    /** 把内联资源标记替换为按原位置排序的本地路径引用。 */
    public String buildAgentQuestion(String rawMessage, List<ResolvedResource> resources) {
        String text = rawMessage == null ? "" : rawMessage;
        final Set<String> seen = new LinkedHashSet<String>();
        Matcher matcher = RESOURCE_PATTERN.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String type = matcher.group(1);
            String path = decode(matcher.group(2));
            seen.add(normalizeIdentity(path));
            String replacement = "\n[本地资源(" + type + ")： " + path + "]\n";
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        StringBuilder ordered = new StringBuilder(buffer.toString());

        if (resources != null) {
            for (ResolvedResource resource : resources) {
                String identity = normalizeIdentity(resource.getUrl());
                if (seen.contains(identity)) {
                    continue;
                }
                ordered.append("\n[本地资源(")
                        .append(resource.getType())
                        .append(")： ")
                        .append(resource.getUrl())
                        .append("]\n");
            }
        }

        String result = ordered.toString().trim();
        if (resources != null && !resources.isEmpty()) {
            if (result.isEmpty()) {
                result = "请处理我提供的本地资源。";
            }
            result = result
                    + "\n\n以上资源来自用户本机。图片会作为附件提供；其他文件或文件夹可使用 "
                    + "read_resource 工具按路径读取，或使用文件工具在工作空间内检索。";
        }
        return result.isEmpty() ? "我上传了附件，请结合附件内容回答。" : result;
    }

    /** 运行期追加消息只做标记替换，不重复装配附件。 */
    public String buildAppendContent(String rawMessage) {
        return buildAppendContent(rawMessage, null);
    }

    /** 运行期追加消息：替换标记，并补充未出现在正文中的资源路径。 */
    public String buildAppendContent(String rawMessage, List<ResourceRef> references) {
        String text = rawMessage == null ? "" : rawMessage;
        final Set<String> seen = new LinkedHashSet<String>();
        Matcher matcher = RESOURCE_PATTERN.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String type = matcher.group(1);
            String path = decode(matcher.group(2));
            seen.add(normalizeIdentity(path));
            String replacement = "\n[本地资源(" + type + ")： " + path + "]\n";
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        StringBuilder result = new StringBuilder(buffer.toString().trim());
        if (references != null) {
            for (ResourceRef reference : references) {
                if (reference == null || !StringUtils.hasText(reference.getPath())) {
                    continue;
                }
                String identity = normalizeIdentity(reference.getPath());
                if (seen.contains(identity)) {
                    continue;
                }
                String type = StringUtils.hasText(reference.getType())
                        ? reference.getType().trim()
                        : "file";
                result.append("\n[本地资源(").append(type).append(")： ")
                        .append(reference.getPath()).append("]");
            }
        }
        return result.toString().trim();
    }

    public boolean isImage(String fileType) {
        return fileType != null && IMAGE_EXTENSIONS.contains(fileType.toLowerCase(Locale.ROOT));
    }

    public boolean isText(String fileType) {
        return fileType != null && TEXT_EXTENSIONS.contains(fileType.toLowerCase(Locale.ROOT));
    }

    public boolean isDocument(String fileType) {
        return fileType != null && DOCUMENT_EXTENSIONS.contains(fileType.toLowerCase(Locale.ROOT));
    }

    private ResolvedResource toResolved(ResourceRef reference, Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        String fileType = resolveFileType(reference.getFileType(), absolute);
        boolean directory = Files.isDirectory(absolute);
        long size = 0L;
        if (!directory) {
            try {
                size = Files.size(absolute);
            } catch (IOException ignored) {
                size = reference.getSize() == null ? 0L : reference.getSize();
            }
        }
        String type = resolveType(reference.getType(), fileType, directory);
        String name = StringUtils.hasText(reference.getName())
                ? reference.getName().trim()
                : absolute.getFileName() == null
                        ? absolute.toString()
                        : absolute.getFileName().toString();
        return new ResolvedResource(absolute, name, fileType, size, type, directory);
    }

    private static String resolveFileType(String provided, Path path) {
        if (StringUtils.hasText(provided)) {
            return provided.trim().toLowerCase(Locale.ROOT);
        }
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1
                ? ""
                : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String resolveType(String provided, String fileType, boolean directory) {
        if (directory) {
            return "folder";
        }
        if (StringUtils.hasText(provided)) {
            return provided.trim().toLowerCase(Locale.ROOT);
        }
        if (isImage(fileType)) {
            return "image";
        }
        if ("pdf".equals(fileType)) {
            return "pdf";
        }
        if ("html".equals(fileType) || "htm".equals(fileType)) {
            return "html";
        }
        if (isDocument(fileType)) {
            return "document";
        }
        return "file";
    }

    private static Path toPath(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            if (value.startsWith("file://")) {
                return Paths.get(URI.create(value));
            }
            return Paths.get(value).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    private static String normalizeIdentity(String path) {
        if (path == null) {
            return "";
        }
        try {
            return Paths.get(path).toAbsolutePath().normalize().toString();
        } catch (RuntimeException e) {
            return path.trim();
        }
    }
}
