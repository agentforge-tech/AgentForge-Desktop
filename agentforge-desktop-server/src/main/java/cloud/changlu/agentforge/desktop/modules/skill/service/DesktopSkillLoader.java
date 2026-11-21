package cloud.changlu.agentforge.desktop.modules.skill.service;

import cloud.changlu.agentforge.desktop.config.DesktopProperties;
import cloud.changlu.agentforge.desktop.modules.skill.api.SkillLoader;
import cloud.changlu.agentforge.desktop.modules.skill.api.SkillMetadata;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class DesktopSkillLoader implements SkillLoader {
    // Skill 正文与导入包限制分开：避免单文档过大污染 Prompt，同时允许 Skill 携带必要资源。
    private static final String SKILL_FILE = "SKILL.md";
    private static final long MAX_SKILL_BYTES = 512L * 1024L;
    private static final long MAX_IMPORT_BYTES = 100L * 1024L * 1024L;
    private static final int MAX_IMPORT_FILES = 2000;
    private final DesktopProperties properties;

    public DesktopSkillLoader(DesktopProperties properties) { this.properties = properties; }

    @Override
    public Path globalRoot() { return properties.globalSkillRoot(); }

    @Override
    public Path ensureGlobalRoot() {
        try {
            return Files.createDirectories(globalRoot());
        } catch (IOException e) {
            throw new IllegalStateException("创建全局 Skill 目录失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<SkillMetadata> listGlobalSkills() { return discover(globalRoot(), "global"); }

    @Override
    public List<SkillMetadata> listEffectiveSkills(Path workspaceRoot) {
        // 先放入全局 Skill，再用工作空间同名 Skill 覆盖，保证项目级规则优先。
        Map<String, SkillMetadata> effective = new LinkedHashMap<String, SkillMetadata>();
        for (SkillMetadata item : discover(globalRoot(), "global")) effective.put(item.getName(), item);
        if (workspaceRoot != null) {
            for (SkillMetadata item : discover(workspaceRoot.resolve(".agentforge").resolve("skills"), "workspace")) {
                effective.put(item.getName(), item);
            }
        }
        List<SkillMetadata> result = new ArrayList<SkillMetadata>(effective.values());
        sort(result);
        return result;
    }

    @Override
    public String loadSkillIndex(Path workspaceRoot) {
        // Prompt 只注入元数据索引，正文由 skill(path) 按需加载，控制上下文体积。
        List<SkillMetadata> skills = listEffectiveSkills(workspaceRoot);
        if (skills.isEmpty()) return "";
        StringBuilder out = new StringBuilder("<desktop-skills>\nAvailable skills:");
        for (SkillMetadata skill : skills) {
            out.append("\n- name: ").append(skill.getName())
                    .append("\n  description: ").append(skill.getDescription())
                    .append("\n  scope: ").append(skill.getScope())
                    .append("\n  path: ").append(skill.getPath());
        }
        return out.append("\n</desktop-skills>").toString();
    }

    @Override
    public String skill(Path workspaceRoot, String requestedPath) {
        if (requestedPath == null || requestedPath.trim().isEmpty() || requestedPath.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Skill path 不能为空");
        }
        try {
            // 先归一化输入，再转换为真实路径，阻断 .. 和符号链接越界。
            Path input = Paths.get(requestedPath.trim()).toAbsolutePath().normalize();
            Path file = Files.isDirectory(input) ? input.resolve(SKILL_FILE) : input;
            if (!SKILL_FILE.equals(file.getFileName().toString()) || !Files.isRegularFile(file)) {
                throw new IllegalArgumentException("Skill path 必须指向包含 SKILL.md 的目录或文件");
            }
            Path realFile = file.toRealPath();
            // Tool 不是通用文件读取器：只允许全局根和当前工作空间 Skill 根。
            boolean allowed = insideExistingRoot(realFile, globalRoot());
            if (!allowed && workspaceRoot != null) {
                allowed = insideExistingRoot(realFile, workspaceRoot.resolve(".agentforge").resolve("skills"));
            }
            if (!allowed) throw new SecurityException("Skill path 不在允许的目录中");
            if (Files.size(realFile) > MAX_SKILL_BYTES) throw new IllegalArgumentException("SKILL.md 超过 512 KiB 限制");
            return new String(Files.readAllBytes(realFile), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 Skill 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public SkillMetadata importGlobalSkill(Path sourcePath) {
        if (sourcePath == null) throw new IllegalArgumentException("请选择 Skill 文件夹或 ZIP");
        final Path original = sourcePath.toAbsolutePath().normalize();
        // ZIP 先解压到全局根下的临时目录，通过校验后再进入统一导入流程。
        final Path extraction = globalRoot().resolve(".extract-" + UUID.randomUUID().toString());
        Path source = original;
        boolean zip = Files.isRegularFile(original) && original.getFileName().toString().toLowerCase().endsWith(".zip");
        try {
            Files.createDirectories(globalRoot());
            if (zip) {
                extractZip(original, extraction);
                source = locateExtractedSkill(extraction);
            }
            if (!Files.isDirectory(source) || !Files.isRegularFile(source.resolve(SKILL_FILE))) {
                throw new IllegalArgumentException("所选内容不是有效 Skill，缺少 SKILL.md");
            }
        } catch (IOException e) {
            deleteQuietly(extraction);
            throw new IllegalStateException("解压 Skill ZIP 失败: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            deleteQuietly(extraction);
            throw e;
        }
        String folderName = source.getFileName() == null ? "" : source.getFileName().toString();
        if (zip && source.equals(extraction)) folderName = stripZipSuffix(original.getFileName().toString());
        if (folderName.isEmpty() || ".".equals(folderName) || "..".equals(folderName)) throw new IllegalArgumentException("Skill 目录名无效");
        Path root = globalRoot();
        Path target = root.resolve(folderName).normalize();
        if (!target.getParent().equals(root.normalize())) throw new IllegalArgumentException("Skill 目录名无效");
        // 先完整复制到临时目录，再原子移动，避免扫描到半导入状态。
        Path temporary = root.resolve(".import-" + UUID.randomUUID().toString());
        try {
            Files.createDirectories(root);
            if (Files.exists(target)) throw new IllegalArgumentException("已存在同名 Skill: " + folderName);
            copyValidated(source, temporary);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, target); }
            return metadata(target.resolve(SKILL_FILE), "global");
        } catch (IOException e) {
            throw new IllegalStateException("导入 Skill 失败: " + e.getMessage(), e);
        } finally {
            deleteQuietly(temporary);
            deleteQuietly(extraction);
        }
    }

    private static void extractZip(Path zipFile, Path destination) throws IOException {
        Files.createDirectories(destination);
        long totalBytes = 0L;
        int fileCount = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String entryName = entry.getName().replace('\\', '/');
                if (entryName.startsWith("/") || entryName.indexOf('\0') >= 0) throw new IOException("ZIP 包含非法路径");
                // normalize + startsWith 防止 ZIP Slip 将文件写到临时目录之外。
                Path target = destination.resolve(entryName).normalize();
                if (!target.startsWith(destination)) throw new IOException("ZIP 包含路径穿越内容");
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    fileCount++;
                    if (fileCount > MAX_IMPORT_FILES) throw new IOException("Skill ZIP 文件数超过限制");
                    Path parent = target.getParent();
                    if (parent != null) Files.createDirectories(parent);
                    try (java.io.OutputStream output = Files.newOutputStream(target)) {
                        int read;
                        while ((read = input.read(buffer)) >= 0) {
                            if (read == 0) continue;
                            totalBytes += read;
                            if (totalBytes > MAX_IMPORT_BYTES) throw new IOException("Skill ZIP 解压后超过大小限制");
                            output.write(buffer, 0, read);
                        }
                    }
                }
                input.closeEntry();
            }
        }
    }

    private static Path locateExtractedSkill(Path extraction) throws IOException {
        // 同时兼容 SKILL.md 位于 ZIP 根目录或唯一顶层目录的常见打包方式。
        if (Files.isRegularFile(extraction.resolve(SKILL_FILE))) return extraction;
        List<Path> candidates = new ArrayList<Path>();
        try (java.nio.file.DirectoryStream<Path> children = Files.newDirectoryStream(extraction)) {
            for (Path child : children) {
                if (Files.isDirectory(child) && Files.isRegularFile(child.resolve(SKILL_FILE))) candidates.add(child);
            }
        }
        if (candidates.size() != 1) throw new IllegalArgumentException("Skill ZIP 必须在根目录或唯一顶层目录中包含 SKILL.md");
        return candidates.get(0);
    }

    private static String stripZipSuffix(String fileName) {
        return fileName.toLowerCase().endsWith(".zip") ? fileName.substring(0, fileName.length() - 4) : fileName;
    }

    private List<SkillMetadata> discover(Path root, String scope) {
        // 只扫描根目录和一层子目录，避免资源目录中的 SKILL.md 被误识别。
        if (root == null || !Files.isDirectory(root)) return Collections.emptyList();
        List<SkillMetadata> result = new ArrayList<SkillMetadata>();
        addIfSkill(root.resolve(SKILL_FILE), scope, result);
        try (java.nio.file.DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
            for (Path child : children) if (Files.isDirectory(child)) addIfSkill(child.resolve(SKILL_FILE), scope, result);
        } catch (IOException ignored) { }
        sort(result);
        return result;
    }

    private void addIfSkill(Path file, String scope, List<SkillMetadata> result) {
        if (!Files.isRegularFile(file)) return;
        try { if (Files.size(file) <= MAX_SKILL_BYTES) result.add(metadata(file, scope)); }
        catch (IOException ignored) { }
    }

    private SkillMetadata metadata(Path file, String scope) throws IOException {
        String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        Map<String, String> frontmatter = parseFrontmatter(content);
        String fallback = file.getParent().getFileName().toString();
        String name = clean(frontmatter.get("name"));
        String description = clean(frontmatter.get("description"));
        if (name.isEmpty()) name = fallback;
        if (description.isEmpty()) description = "未提供技能描述";
        return new SkillMetadata(name, description, scope, file.getParent().toAbsolutePath().normalize().toString(), file.toAbsolutePath().normalize().toString(), Files.getLastModifiedTime(file).toMillis());
    }

    private static Map<String, String> parseFrontmatter(String content) {
        // 第一阶段只解析 Agent 路由必需的 name/description，并兼容 YAML 块标量指示符。
        Map<String, String> result = new LinkedHashMap<String, String>();
        String normalized = content.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) return result;
        int end = normalized.indexOf("\n---", 4);
        if (end < 0) return result;
        String[] lines = normalized.substring(4, end).split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim();
            if (!"name".equals(key) && !"description".equals(key)) continue;
            String value = line.substring(colon + 1).trim();
            if (value.matches("[>|][+-]?")) {
                StringBuilder block = new StringBuilder();
                while (i + 1 < lines.length && (lines[i + 1].startsWith(" ") || lines[i + 1].startsWith("\t"))) {
                    if (block.length() > 0) block.append(' ');
                    block.append(lines[++i].trim());
                }
                value = block.toString();
            }
            result.put(key, value);
        }
        return result;
    }

    private static String clean(String value) {
        if (value == null) return "";
        String result = value.trim();
        if (result.length() >= 2 && ((result.startsWith("\"") && result.endsWith("\"")) || (result.startsWith("'") && result.endsWith("'")))) result = result.substring(1, result.length() - 1);
        return result.replace('\n', ' ').trim();
    }

    private static boolean insideExistingRoot(Path file, Path root) throws IOException {
        return Files.isDirectory(root) && file.startsWith(root.toRealPath());
    }

    private static void copyValidated(final Path source, final Path target) throws IOException {
        // 导入不跟随符号链接，并同时限制文件数量与累计大小。
        final long[] bytes = {0L};
        final int[] files = {0};
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(dir)) throw new IOException("导入目录不允许包含符号链接");
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file)) throw new IOException("导入目录不允许包含符号链接");
                files[0]++; bytes[0] += attrs.size();
                if (files[0] > MAX_IMPORT_FILES || bytes[0] > MAX_IMPORT_BYTES) throw new IOException("Skill 目录超过导入限制");
                Files.copy(file, target.resolve(source.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteQuietly(Path root) {
        if (root == null || !Files.exists(root)) return;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { Files.deleteIfExists(file); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException { Files.deleteIfExists(dir); return FileVisitResult.CONTINUE; }
            });
        } catch (IOException ignored) { }
    }

    private static void sort(List<SkillMetadata> items) {
        Collections.sort(items, new Comparator<SkillMetadata>() {
            @Override public int compare(SkillMetadata a, SkillMetadata b) { return a.getName().compareToIgnoreCase(b.getName()); }
        });
    }
}
