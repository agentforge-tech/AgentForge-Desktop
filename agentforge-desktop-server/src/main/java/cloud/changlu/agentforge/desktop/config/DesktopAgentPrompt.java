package cloud.changlu.agentforge.desktop.config;

import java.time.LocalDate;

/** System-owned defaults for the local Desktop coding agent. */
public final class DesktopAgentPrompt {

    public static final String LEGACY_DEFAULT =
            "你是一名专业的 AI 助手，请准确、清晰地回答用户问题。";

    public static final String DEFAULT =
            "你是 AgentForge Desktop Coding Agent。你直接运行在用户当前电脑的本地 Runtime 中，应使用可用工具实际完成用户的软件工程任务，而不是只提供操作建议。\n\n"
                    + "# 本地环境\n"
                    + "- workspace 是用户明确选择的本机工作空间；文件工具和命令都直接作用于该工作空间。\n"
                    + "- 不要假设 Docker、/workspace 或云端沙箱存在；以工具返回的真实 macOS/Windows 路径、操作系统和命令结果为准。\n"
                    + "- 优先使用 read、glob、rg、grep、write、edit 等专用工具；仅在运行构建、测试、Git 或专用工具无法表达必要操作时使用 bash。\n\n"
                    + "# 工作方式\n"
                    + "1. 先理解再修改：先阅读相关代码、配置和项目约束，不凭空猜测实现。\n"
                    + "2. 精准定位：优先搜索文件、符号和引用关系，尽量缩小问题范围。\n"
                    + "3. 最小改动：保持项目现有架构、代码风格和兼容性，避免与任务无关的重构。\n"
                    + "4. 保护用户工作：将已有改动视为用户成果，不擅自覆盖、回退或删除。\n"
                    + "5. 按风险验证：修改后运行合适的编译、测试或检查；无法验证时明确说明原因。\n"
                    + "6. 谨慎执行操作：对删除文件、覆盖数据、安装依赖、发布和其他高风险操作，先确认范围与后果。\n"
                    + "7. 清晰沟通：执行中简洁说明进展；完成后先给结果，再概括关键改动和验证情况。\n\n"
                    + "回答使用与用户一致的语言，保持专业、直接、可执行。";

    private DesktopAgentPrompt() {}

    /** Builds the Desktop-owned prompt. HarnessAgent appends its Runtime prompt afterwards. */
    public static String render(String configuredInstructions) {
        StringBuilder prompt = new StringBuilder(DEFAULT);
        prompt.append("\n\n# 当前本机环境\n")
                .append("- 当前日期：")
                .append(LocalDate.now())
                .append("\n- 操作系统：")
                .append(systemProperty("os.name", "unknown"))
                .append(' ')
                .append(systemProperty("os.version", ""))
                .append("\n- 系统架构：")
                .append(systemProperty("os.arch", "unknown"));

        String userInstructions = normalizeUserInstructions(configuredInstructions);
        if (!userInstructions.isEmpty()) {
            prompt.append("\n\n# 用户自定义指令\n<user-instructions>\n")
                    .append(userInstructions)
                    .append("\n</user-instructions>");
        }
        return prompt.toString();
    }

    /** Config persistence stores user additions only, never a copy of the platform prompt. */
    public static String normalizeUserInstructions(String configuredInstructions) {
        if (configuredInstructions == null) {
            return "";
        }
        String value = configuredInstructions.trim();
        if (value.isEmpty() || LEGACY_DEFAULT.equals(value) || DEFAULT.equals(value)) {
            return "";
        }
        return value;
    }

    private static String systemProperty(String name, String fallback) {
        String value = System.getProperty(name);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
