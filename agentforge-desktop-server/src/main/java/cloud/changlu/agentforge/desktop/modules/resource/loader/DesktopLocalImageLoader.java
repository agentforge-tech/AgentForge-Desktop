package cloud.changlu.agentforge.desktop.modules.resource.loader;

import cloud.changlu.agentforge.agent.domain.ImageLoader;
import cloud.changlu.agentforge.agent.support.AttachmentContentAssembler;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 桌面端图片加载器：本地绝对路径优先，公网地址才走 HTTP。
 *
 * <p>输入框选中的图片不会上传到服务端，因此框架层 INLINE 时传入的是本机路径。
 *
 * @author changlu
 * @since 2026-11-22
 */
@Component
public class DesktopLocalImageLoader implements ImageLoader {

    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;
    private static final Map<String, String> EXT_MIME;

    static {
        Map<String, String> mime = new HashMap<String, String>();
        mime.put("png", "image/png");
        mime.put("jpg", "image/jpeg");
        mime.put("jpeg", "image/jpeg");
        mime.put("gif", "image/gif");
        mime.put("webp", "image/webp");
        mime.put("bmp", "image/bmp");
        mime.put("svg", "image/svg+xml");
        EXT_MIME = Collections.unmodifiableMap(mime);
    }

    @PostConstruct
    public void registerAsDefaultLoader() {
        AttachmentContentAssembler.setDefaultImageLoader(this);
    }

    @Override
    public ImageBinary load(String url) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("image url must not be blank");
        }
        String value = url.trim();
        if (!isRemote(value)) {
            Path local = toLocalPath(value);
            if (local != null && Files.isRegularFile(local)) {
                return readLocal(local);
            }
            if (local != null) {
                throw new IllegalStateException("local image not found: " + local);
            }
        }
        return download(value);
    }

    private ImageBinary readLocal(Path path) {
        try {
            long size = Files.size(path);
            if (size > MAX_BYTES) {
                throw new IllegalStateException("image exceeds " + MAX_BYTES + " bytes");
            }
            return new ImageBinary(Files.readAllBytes(path), detectMime(path));
        } catch (IOException e) {
            throw new IllegalStateException("failed to read local image: " + path, e);
        }
    }

    private ImageBinary download(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "AgentForge-Desktop/0.1.0");
            connection.setInstanceFollowRedirects(true);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("download failed, status=" + status);
            }
            byte[] body = readBody(connection.getInputStream());
            if (body.length == 0) {
                throw new IllegalStateException("download failed, empty body");
            }
            return new ImageBinary(body, normalizeMime(connection.getContentType()));
        } catch (IOException e) {
            throw new IllegalStateException("download image failed: " + url, e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private byte[] readBody(InputStream input) throws IOException {
        try (InputStream in = input;
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0L;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BYTES) {
                    throw new IllegalStateException("image exceeds " + MAX_BYTES + " bytes");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static boolean isRemote(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static Path toLocalPath(String value) {
        try {
            if (value.toLowerCase(Locale.ROOT).startsWith("file://")) {
                return Paths.get(URI.create(value));
            }
            return Paths.get(value).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String detectMime(Path path) {
        String name = path.getFileName() == null
                ? ""
                : path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        String mime = EXT_MIME.get(ext);
        if (mime != null) {
            return mime;
        }
        try {
            String probed = Files.probeContentType(path);
            if (probed != null && probed.toLowerCase(Locale.ROOT).startsWith("image/")) {
                return probed.toLowerCase(Locale.ROOT);
            }
        } catch (IOException ignored) {
            // fall through
        }
        return "image/png";
    }

    private String normalizeMime(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "image/png";
        }
        int semicolon = raw.indexOf(';');
        String mime = semicolon >= 0 ? raw.substring(0, semicolon) : raw;
        mime = mime.trim().toLowerCase(Locale.ROOT);
        return mime.startsWith("image/") ? mime : "image/png";
    }
}
