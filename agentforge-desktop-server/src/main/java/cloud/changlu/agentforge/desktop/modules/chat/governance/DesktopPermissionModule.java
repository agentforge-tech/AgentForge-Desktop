package cloud.changlu.agentforge.desktop.modules.chat.governance;

import cloud.changlu.agentforge.agent.governance.PermissionInterruptModule;
import cloud.changlu.agentforge.agent.governance.interrupt.spi.AgentInterruptStore;
import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionDecision;
import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionMode;

/**
 * Desktop 内置权限与 HITL 能力。
 *
 * <p>不需要用户在配置页开启：所有本地 Agent 默认启用 ask_user，并对 bash 中删除、提权、磁盘等
 * 高风险命令发起一次性审批。审批采用 DEFAULT 规则模式，未命中命令规则的工具直接放行。
 */
public final class DesktopPermissionModule {

    private static final int MODE_BYPASS = 2;

    private DesktopPermissionModule() {}

    public static PermissionInterruptModule build(AgentInterruptStore interruptStore) {
        return build(interruptStore, PermissionMode.DEFAULT);
    }

    public static PermissionInterruptModule build(
            AgentInterruptStore interruptStore, PermissionMode permissionMode) {
        if (interruptStore == null) {
            throw new IllegalArgumentException("interruptStore must not be null");
        }
        return PermissionInterruptModule.builder()
                .activePermissionMode(
                        permissionMode == null ? PermissionMode.DEFAULT : permissionMode)
                .interruptStore(interruptStore)
                .enableAskUser()
                .ruleHandler(new ShellCommandPermissionRuleHandler())
                .rule(
                        new ShellCommandPermissionRule(
                                "desktop_bash_high_risk", PermissionDecision.ASK))
                .build();
    }

    /** 前端 mode=2 表示完全访问，其余按默认权限处理。 */
    public static PermissionMode resolveMode(Integer mode) {
        return mode != null && mode == MODE_BYPASS
                ? PermissionMode.BYPASS
                : PermissionMode.DEFAULT;
    }
}