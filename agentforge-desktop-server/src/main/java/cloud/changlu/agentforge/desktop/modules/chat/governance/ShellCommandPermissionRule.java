package cloud.changlu.agentforge.desktop.modules.chat.governance;

import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionDecision;
import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionRule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 本地工作台内置的 bash 命令规则：命中高风险命令时请求用户审批。 */
public final class ShellCommandPermissionRule implements PermissionRule {

    public static final String TYPE = "shell_command";

    private static final Set<String> HIGH_RISK_COMMANDS =
            new HashSet<String>(
                    Arrays.asList(
                            "rm",
                            "rmdir",
                            "sudo",
                            "su",
                            "shutdown",
                            "reboot",
                            "halt",
                            "poweroff",
                            "mkfs",
                            "dd",
                            "fdisk",
                            "diskutil",
                            "format"));

    private static final Pattern RM_TOKEN =
            Pattern.compile("(?:^|[\\s;&|(])rm(?:\\s|$|[;&|)])", Pattern.CASE_INSENSITIVE);

    private static final Set<String> SHELLS =
            new HashSet<String>(Arrays.asList("sh", "bash", "zsh", "dash", "fish"));

    private static final Set<String> WRAPPERS =
            new HashSet<String>(
                    Arrays.asList(
                            "env", "nohup", "nice", "time", "command", "exec", "timeout", "xargs"));

    private final String id;
    private final PermissionDecision decision;

    public ShellCommandPermissionRule(String id, PermissionDecision decision) {
        if (decision == null) {
            throw new IllegalArgumentException("decision must not be null");
        }
        this.id = id;
        this.decision = decision;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public PermissionDecision getDecision() {
        return decision;
    }

    /**
     * 分析整条 shell 命令是否包含高风险动作。
     *
     * <p>按常见命令分隔符拆成简单命令，跳过环境变量与包装命令后判断真实命令名；同时补充
     * {@code git reset --hard}、{@code find -delete}、递归 chmod/chown 等破坏性用法。
     */
    public boolean matches(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }
        String normalized = command.replace("\\\r\n", " ").replace("\\\n", " ");
        String[] segments = normalized.split("(?:\\r?\\n|;|&&|\\|\\||\\||&|\\(|\\)|`)");
        for (String segment : segments) {
            if (isRiskySegment(segment)) {
                return true;
            }
        }
        return false;
    }

    private boolean isRiskySegment(String segment) {
        List<String> tokens = tokenize(segment);
        if (tokens.isEmpty()) {
            return false;
        }
        if (SHELLS.contains(commandName(tokens.get(0))) && tokens.contains("-c")) {
            return containsRiskyWord(segment.substring(segment.indexOf("-c") + 2));
        }
        String command = commandName(firstCommandToken(tokens));
        if (command == null) {
            return containsRiskyWord(segment);
        }
        if (HIGH_RISK_COMMANDS.contains(command)) {
            return true;
        }
        if ("git".equals(command) && isRiskyGit(tokens)) {
            return true;
        }
        if ("find".equals(command) && isRiskyFind(tokens)) {
            return true;
        }
        if (("chmod".equals(command) || "chown".equals(command)) && hasRecursiveFlag(tokens)) {
            return true;
        }
        if ("truncate".equals(command) && tokens.contains("-s") && containsRootPath(tokens)) {
            return true;
        }
        return containsRiskyWord(segment);
    }

    private static String firstCommandToken(List<String> tokens) {
        int index = 0;
        while (index < tokens.size()) {
            String token = tokens.get(index);
            if (isAssignment(token)) {
                index++;
                continue;
            }
            String name = commandName(token);
            if (WRAPPERS.contains(name)) {
                index = skipWrapper(tokens, index);
                continue;
            }
            return token;
        }
        return null;
    }

    private static int skipWrapper(List<String> tokens, int index) {
        String wrapper = commandName(tokens.get(index));
        index++;
        if ("timeout".equals(wrapper) && index < tokens.size() && isDuration(tokens.get(index))) {
            index++;
        }
        while (index < tokens.size()) {
            String token = tokens.get(index);
            if (isAssignment(token) || token.startsWith("-")) {
                index++;
                continue;
            }
            break;
        }
        return index;
    }

    private static boolean isRiskyGit(List<String> tokens) {
        int git = indexOfCommand(tokens, "git");
        if (git < 0) {
            return false;
        }
        List<String> args = tokens.subList(git + 1, tokens.size());
        if (args.contains("reset") && args.contains("--hard")) {
            return true;
        }
        if (args.contains("clean")
                && (args.contains("-f") || args.contains("--force") || args.contains("-fd"))) {
            return true;
        }
        if (args.contains("checkout") && args.contains("--") && args.contains(".")) {
            return true;
        }
        return args.contains("push")
                && (args.contains("--force") || args.contains("-f") || args.contains("--force-with-lease"));
    }

    private static boolean isRiskyFind(List<String> tokens) {
        return tokens.contains("-delete") || (tokens.contains("-exec") && tokens.contains("rm"));
    }

    private static boolean hasRecursiveFlag(List<String> tokens) {
        for (String token : tokens) {
            if ("-R".equals(token) || "--recursive".equals(token) || token.startsWith("-R")) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsRootPath(List<String> tokens) {
        for (String token : tokens) {
            if ("/".equals(token) || token.startsWith("/dev/")) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsRiskyWord(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return lower.contains("rm -rf")
                || lower.contains("rm -r ")
                || lower.contains("sudo ")
                || RM_TOKEN.matcher(lower).find();
    }

    private static int indexOfCommand(List<String> tokens, String name) {
        for (int i = 0; i < tokens.size(); i++) {
            if (name.equals(commandName(tokens.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    private static String commandName(String token) {
        if (token == null || token.trim().isEmpty()) {
            return null;
        }
        String value = token.trim();
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static boolean isAssignment(String token) {
        return token != null && token.matches("[A-Za-z_][A-Za-z0-9_]*=.*");
    }

    private static boolean isDuration(String token) {
        return token != null && token.matches("\\d+(?:\\.\\d+)?[smhd]?");
    }

    private static List<String> tokenize(String segment) {
        List<String> tokens = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean escaped = false;
        for (int i = 0; i < segment.length(); i++) {
            char ch = segment.charAt(i);
            if (escaped) {
                current.append(ch);
                escaped = false;
                continue;
            }
            if (ch == '\\' && !singleQuoted) {
                escaped = true;
                continue;
            }
            if (ch == '\'' && !doubleQuoted) {
                singleQuoted = !singleQuoted;
                continue;
            }
            if (ch == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
                continue;
            }
            if (Character.isWhitespace(ch) && !singleQuoted && !doubleQuoted) {
                flush(tokens, current);
                continue;
            }
            current.append(ch);
        }
        flush(tokens, current);
        return tokens;
    }

    private static void flush(List<String> tokens, StringBuilder current) {
        if (current.length() > 0) {
            tokens.add(current.toString());
            current.setLength(0);
        }
    }
}