package cloud.changlu.agentforge.desktop.modules.agent.attachment;

import cloud.changlu.agentforge.agent.domain.AgentAttachmentType;
import cloud.changlu.agentforge.agent.domain.AttachmentPayloadType;
import cloud.changlu.agentforge.model.registry.enums.LlmEnum;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 桌面附件载荷路由。
 *
 * <p>桌面资源默认是本机绝对路径，因此图片基本都会走 INLINE；只有用户显式引用公网图片且模型不是 Anthropic 时才走 URL。
 *
 * @author changlu
 * @since 2026-11-22
 */
@Service
public class DesktopAttachmentRoutingService {

    private static final long MAX_INLINE_IMAGE_BYTES = 4L * 1024 * 1024;

    private static final Pattern PRIVATE_10 = Pattern.compile("^10\\..*");
    private static final Pattern PRIVATE_172 = Pattern.compile("^172\\.(1[6-9]|2\\d|3[0-1])\\..*");
    private static final Pattern PRIVATE_192 = Pattern.compile("^192\\.168\\..*");
    private static final Pattern LOOPBACK_127 = Pattern.compile("^127\\..*");

    public AttachmentPayloadType route(
            AgentAttachmentType attachmentType,
            String url,
            long fileSize,
            boolean supportsImage,
            int providerCode) {
        if (!supportsImage) {
            return AttachmentPayloadType.TEXT;
        }
        if (attachmentType != AgentAttachmentType.IMAGE) {
            return AttachmentPayloadType.TEXT;
        }
        boolean requiresInline = providerCode == LlmEnum.ANTHROPIC.getCode();
        if (!requiresInline && isPubliclyAccessible(url)) {
            return AttachmentPayloadType.URL;
        }
        if (fileSize > 0 && fileSize <= MAX_INLINE_IMAGE_BYTES) {
            return AttachmentPayloadType.INLINE;
        }
        return AttachmentPayloadType.TEXT;
    }

    private boolean isPubliclyAccessible(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        String lower = url.trim().toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false;
        }
        try {
            String host = new URI(url.trim()).getHost();
            if (host == null || host.trim().isEmpty()) {
                return false;
            }
            String value = host.trim().toLowerCase(Locale.ROOT);
            if ("localhost".equals(value) || value.endsWith(".localhost")) {
                return false;
            }
            return !(LOOPBACK_127.matcher(value).matches()
                    || PRIVATE_10.matcher(value).matches()
                    || PRIVATE_172.matcher(value).matches()
                    || PRIVATE_192.matcher(value).matches());
        } catch (Exception e) {
            return false;
        }
    }
}
