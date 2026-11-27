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

    private static final Pattern RESOURCE_PATTERN =
            Pattern.compile("\\[\\[agentforge-resource:([^:\\]]+):([^\\]]+)\\]\\]");

    /**
     * 输入框内联 Skill 标记：{@code [[agentforge-skill:encodedName:encodedPath]]}。
     *
     * <p>与资源标记同构，位置由用户决定。这里只做文本替换，不查询 Skill 列表、不拼
     * description，也不校验 Skill 是否仍然存在——失效 Skill 会在模型调用
     * {@code skill(path)} 时暴露，且 path 越界由该工具自身的根目录校验兜住。
     */
    private static final Pattern SKILL_PATTERN =
            Pattern.compile("\\[\\[agentforge-skill:([^:\\]]+):([^\\]]+)\\]\\]");

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
     * @throws IllegalArgumentException 资源不存在
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
        return new ArrayList<ResolvedResource>(unique.values());
    }

    /** 去掉内联资源与 Skill 标记后的可见文本。 */
    public String displayText(String rawMessage) {
        if (rawMessage == null) {
            return "";
        }
        return SKILL_PATTERN.matcher(RESOURCE_PATTERN.matcher(rawMessage).replaceAll(""))
                .replaceAll("")
                .trim();
    }

    /** 把内联资源标记替换为按原位置排序的本地路径引用。 */
    public String buildAgentQuestion(String rawMessage, List<ResolvedResource> resources) {
        String text = replaceSkillMarkers(rawMessage == null ? "" : rawMessage);
        final Set<String> seen = new LinkedHashSet<String>();
        final Map<String, String> resolvedTypes = new LinkedHashMap<String, String>();
        if (resources != null) {
            for (ResolvedResource resource : resources) {
                resolvedTypes.put(normalizeIdentity(resource.getUrl()), resource.getType());
            }
        }
        Matcher matcher = RESOURCE_PATTERN.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String path = decode(matcher.group(2));
            String identity = normalizeIdentity(path);
            seen.add(identity);
            // 类型以解析结果为准：标记里可能是旧版前端写入的过期类型
            String type = resolvedTypes.containsKey(identity)
                    ? resolvedTypes.get(identity)
                    : matcher.group(1);
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
        String text = replaceSkillMarkers(rawMessage == null ? "" : rawMessage);
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
                // 与主链路同口径：目录或扩展名能确证时以确证结果为准，避免旧标记类型影响 prompt
                String type = resolveReferenceType(reference);
                result.append("\n[本地资源(").append(type).append(")： ")
                        .append(reference.getPath()).append("]");
            }
        }
        return result.toString().trim();
    }

    /** 追加消息里的资源类型：优先文件系统与扩展名，其次客户端声明。 */
    private String resolveReferenceType(ResourceRef reference) {
        Path path = toPath(reference.getPath());
        String fileType = resolveFileType(reference.getFileType(), path);
        boolean directory = path != null && Files.isDirectory(path);
        return resolveType(reference.getType(), fileType, directory);
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
        if (path == null) {
            return "";
        }
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1
                ? ""
                : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 判定资源类型。
     *
     * <p>顺序：目录 → 扩展名（强证据）→ 客户端声明 → file。
     * 与 Electron {@code describeResource} 及前端 {@code inferResourceType} 保持同一口径：
     * 历史标记里可能留有旧版写入的过期类型（例如把文件夹写成 file），
     * 只要能被文件系统或扩展名确证，就以确证结果为准，否则才采信客户端声明。
     */
    private String resolveType(String provided, String fileType, boolean directory) {
        if (directory) {
            return "folder";
        }
        String detected = typeOfFileType(fileType);
        if (detected != null) {
            return detected;
        }
        if (StringUtils.hasText(provided)) {
            return provided.trim().toLowerCase(Locale.ROOT);
        }
        return "file";
    }

    /** 仅按扩展名判定类型；无法判定时返回 {@code null}。 */
    private String typeOfFileType(String fileType) {
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
        // 文本/代码类在 UI 上按 document 展示，与 Electron describeResource、
        // 前端 inferResourceType 保持同一口径，避免同一资源三处类型不一致。
        if (isText(fileType)) {
            return "document";
        }
        return null;
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

    /** 判断原始消息里是否含内联 Skill 标记（用于可见文本兜底判定）。 */
    public boolean hasSkillMarker(String rawMessage) {
        return rawMessage != null && SKILL_PATTERN.matcher(rawMessage).find();
    }

    /**
     * 把内联 Skill 标记替换成模型可读的指令文本，并保留用户在正文中的位置。
     *
     * <p>不校验 Skill 是否仍存在：列表只由前端筛选，后端注入的 path 也会被
     * {@code DesktopSkillTool} 的根目录校验兜住，因此失效 Skill 自然在工具调用时暴露。
     */
    private String replaceSkillMarkers(String text) {
        Matcher matcher = SKILL_PATTERN.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(
                    "[使用 Skill：" + decode(matcher.group(1)) + "（" + decode(matcher.group(2)) + "）]"));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
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
