package cloud.changlu.agentforge.desktop.modules.session.enums;

import cloud.changlu.agentforge.agent.step.StopResultState;

public final class SessionStatusTransfer {

    private SessionStatusTransfer() {}

    public static SessionMessageStatusEnum fromStopResult(StopResultState state) {
        if (state == null) {
            return SessionMessageStatusEnum.FAILED;
        }
        switch (state) {
            case NORMAL:
                return SessionMessageStatusEnum.SUCCEEDED;
            case CANCEL:
                return SessionMessageStatusEnum.CANCELLED;
            case MAX_STEPS:
                return SessionMessageStatusEnum.FAILED;
            default:
                return SessionMessageStatusEnum.FAILED;
        }
    }
}
