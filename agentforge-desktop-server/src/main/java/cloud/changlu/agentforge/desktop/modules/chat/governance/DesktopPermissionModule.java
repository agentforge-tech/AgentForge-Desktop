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

    private DesktopPermissionModule() {}

    public static PermissionInterruptModule build(AgentInterruptStore interruptStore) {
        if (interruptStore == null) {
            throw new IllegalArgumentException("interruptStore must not be null");
        }
        return PermissionInterruptModule.builder()
                .activePermissionMode(PermissionMode.DEFAULT)
                .interruptStore(interruptStore)
                .enableAskUser()
                .ruleHandler(new ShellCommandPermissionRuleHandler())
                .rule(
                        new ShellCommandPermissionRule(
                                "desktop_bash_high_risk", PermissionDecision.ASK))
                .build();
    }
}