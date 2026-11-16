package cloud.changlu.agentforge.desktop.modules.chat.governance;

import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionRule;
import cloud.changlu.agentforge.agent.governance.permission.engine.PermissionContext;
import cloud.changlu.agentforge.agent.governance.permission.spi.PermissionRuleHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 从 bash Tool Call 的 JSON 参数中提取 command，再交给命令规则判断。 */
public final class ShellCommandPermissionRuleHandler implements PermissionRuleHandler {

    private static final String BASH_TOOL = "bash";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String type() {
        return ShellCommandPermissionRule.TYPE;
    }

    @Override
    public boolean matches(PermissionRule rule, PermissionContext context) {
        if (!(rule instanceof ShellCommandPermissionRule) || context == null) {
            return false;
        }
        if (context.getRequest() == null || !BASH_TOOL.equals(context.getRequest().name())) {
            return false;
        }
        return ((ShellCommandPermissionRule) rule).matches(extractCommand(context.getRequest().arguments()));
    }

    static String extractCommand(String arguments) {
        if (arguments == null || arguments.trim().isEmpty()) {
            return "";
        }
        try {
            JsonNode node = MAPPER.readTree(arguments);
            JsonNode command = node == null ? null : node.get("command");
            if (command != null && command.isTextual()) {
                return command.asText();
            }
        } catch (Exception ignored) {
            // Fall through to raw arguments for non-JSON tool payloads.
        }
        return arguments;
    }
}