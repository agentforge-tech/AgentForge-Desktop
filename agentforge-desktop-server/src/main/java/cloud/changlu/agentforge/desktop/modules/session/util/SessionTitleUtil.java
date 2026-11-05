package cloud.changlu.agentforge.desktop.modules.session.util;

public final class SessionTitleUtil {

    public static final int TITLE_MAX_LENGTH = 20;
    public static final String DEFAULT_TITLE = "新会话";

    private SessionTitleUtil() {}

    public static String resolve(String message) {
        if (message == null) {
            return DEFAULT_TITLE;
        }
        String normalized = message.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            return DEFAULT_TITLE;
        }
        if (normalized.codePointCount(0, normalized.length()) <= TITLE_MAX_LENGTH) {
            return normalized;
        }
        int end = normalized.offsetByCodePoints(0, TITLE_MAX_LENGTH);
        return normalized.substring(0, end);
    }
}
