package cloud.changlu.agentforge.desktop.modules.resource.read;

import cloud.changlu.agentforge.agent.extend.middlewares.read.domain.ReadBatchResult;
import cloud.changlu.agentforge.agent.extend.middlewares.read.domain.ReadResourceKind;
import cloud.changlu.agentforge.agent.extend.middlewares.read.domain.ReadResult;
import cloud.changlu.agentforge.agent.extend.middlewares.read.tool.HttpReadResourceFetcher;
import cloud.changlu.agentforge.agent.extend.middlewares.read.tool.ReadResourceFetcher;
import cloud.changlu.agentforge.agent.extend.middlewares.read.tool.ReadToolOptions;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * 同时支持本机绝对路径与 http/https 的资源探测器。
 *
 * <p>本地路径只读取元数据；文本类文件额外附带有限长度的正文，图片字节仍由 Middleware 装配阶段读取。
 *
 * @author changlu
 * @since 2026-11-22
 */
public final class DesktopLocalReadFetcher implements ReadResourceFetcher {

    private static final int MAX_TEXT_CHARS = 12000;

    private final ReadToolOptions options;
    private final Logger log;
    private final HttpReadResourceFetcher httpFetcher;

    public DesktopLocalReadFetcher(ReadToolOptions options, Logger log) {
        this.options = options == null ? ReadToolOptions.defaults() : options;
        this.log = log == null ? Logger.getLogger(DesktopLocalReadFetcher.class.getName()) : log;
        this.httpFetcher = new HttpReadResourceFetcher(this.options, this.log);
    }

    @Override
    public ReadBatchResult fetch(List<String> paths, String hint) {
        ReadBatchResult batch = new ReadBatchResult();
        if (paths == null || paths.isEmpty()) {
            batch.setOk(false);
            batch.setErrorCode("EMPTY_PATHS");
            batch.setMessage("paths must not be empty");
            return batch;
        }
        if (paths.size() > options.getMaxPaths()) {
            batch.setOk(false);
            batch.setErrorCode("TOO_MANY_PATHS");
            batch.setMessage("at most " + options.getMaxPaths() + " paths per call");
            return batch;
        }

        List<String> remote = new ArrayList<String>();
        List<ReadResult> results = new ArrayList<ReadResult>();
        for (String raw : paths) {
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            String value = raw.trim();
            if (isRemote(value)) {
                remote.add(value);
            } else {
                results.add(readLocal(value));
            }
        }
        if (!remote.isEmpty()) {
            ReadBatchResult remoteBatch = httpFetcher.fetch(remote, hint);
            if (remoteBatch != null) {
                results.addAll(remoteBatch.getResults());
            }
        }
        batch.setResults(results);
        return batch;
    }

    private ReadResult readLocal(String raw) {
        Path path;
        try {
            path = raw.toLowerCase(Locale.ROOT).startsWith("file://")
                    ? Paths.get(URI.create(raw))
                    : Paths.get(raw).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return ReadResult.failure(raw, "INVALID_PATH", "invalid local path");
        }
        String url = path.toAbsolutePath().normalize().toString();
        if (!Files.exists(path)) {
            return ReadResult.failure(url, "NOT_FOUND", "local resource not found");
        }
        ReadResult result = new ReadResult();
        result.setUrl(url);
        result.setDedupeKey(url);
        result.setSource("local");
        String name = path.getFileName() == null ? url : path.getFileName().toString();
        result.setName(name);
        result.setExtra(new java.util.LinkedHashMap<String, Object>());
        result.getExtra().put("path", url);

        if (Files.isDirectory(path)) {
            result.setKind(ReadResourceKind.FILE);
            result.setFileType("folder");
            result.setSize(0L);
            result.getExtra().put("isDirectory", Boolean.TRUE);
            result.setText(listDirectory(path));
            return result;
        }

        String fileType = extension(path);
        result.setFileType(fileType);
        try {
            result.setSize(Files.size(path));
        } catch (IOException e) {
            result.setSize(0L);
        }
        if (isImage(fileType)) {
            result.setKind(ReadResourceKind.IMAGE);
            result.setMimeType(mimeOf(fileType));
            return result;
        }
        if (isText(fileType)) {
            result.setKind(ReadResourceKind.TEXT);
            result.setMimeType(mimeOf(fileType));
            result.setText(readTextHead(path));
            return result;
        }
        result.setKind(ReadResourceKind.FILE);
        result.setMimeType(mimeOf(fileType));
        return result;
    }

    private String listDirectory(Path path) {
        StringBuilder builder = new StringBuilder("目录内容：");
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
            return "目录内容读取失败：" + e.getMessage();
        }
        return builder.toString();
    }

    private String readTextHead(Path path) {
        try {
            long size = Files.size(path);
            int maxBytes = MAX_TEXT_CHARS * 4;
            byte[] bytes;
            if (size > maxBytes) {
                bytes = new byte[maxBytes];
                try (java.io.InputStream input = Files.newInputStream(path)) {
                    int read = input.read(bytes);
                    if (read <= 0) {
                        return null;
                    }
                    bytes = java.util.Arrays.copyOf(bytes, read);
                }
            } else {
                bytes = Files.readAllBytes(path);
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.length() <= MAX_TEXT_CHARS) {
                return text;
            }
            return text.substring(0, MAX_TEXT_CHARS) + "\n...[内容过长，已截断]";
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isRemote(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static String extension(Path path) {
        String name = path.getFileName() == null
                ? ""
                : path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1);
    }

    private static boolean isImage(String fileType) {
        return "png".equals(fileType)
                || "jpg".equals(fileType)
                || "jpeg".equals(fileType)
                || "gif".equals(fileType)
                || "webp".equals(fileType)
                || "bmp".equals(fileType)
                || "svg".equals(fileType);
    }

    private static boolean isText(String fileType) {
        if (fileType.isEmpty()) {
            return false;
        }
        return "txt".equals(fileType)
                || "md".equals(fileType)
                || "markdown".equals(fileType)
                || "json".equals(fileType)
                || "yaml".equals(fileType)
                || "yml".equals(fileType)
                || "xml".equals(fileType)
                || "csv".equals(fileType)
                || "log".equals(fileType)
                || "java".equals(fileType)
                || "js".equals(fileType)
                || "ts".equals(fileType)
                || "tsx".equals(fileType)
                || "jsx".equals(fileType)
                || "vue".equals(fileType)
                || "py".equals(fileType)
                || "go".equals(fileType)
                || "rs".equals(fileType)
                || "sh".equals(fileType)
                || "sql".equals(fileType)
                || "html".equals(fileType)
                || "htm".equals(fileType)
                || "css".equals(fileType)
                || "scss".equals(fileType);
    }

    private static String mimeOf(String fileType) {
        if ("png".equals(fileType)) {
            return "image/png";
        }
        if ("jpg".equals(fileType) || "jpeg".equals(fileType)) {
            return "image/jpeg";
        }
        if ("gif".equals(fileType)) {
            return "image/gif";
        }
        if ("webp".equals(fileType)) {
            return "image/webp";
        }
        if ("bmp".equals(fileType)) {
            return "image/bmp";
        }
        if ("svg".equals(fileType)) {
            return "image/svg+xml";
        }
        if ("html".equals(fileType) || "htm".equals(fileType)) {
            return "text/html";
        }
        if ("json".equals(fileType)) {
            return "application/json";
        }
        if ("pdf".equals(fileType)) {
            return "application/pdf";
        }
        return "text/plain";
    }
}
