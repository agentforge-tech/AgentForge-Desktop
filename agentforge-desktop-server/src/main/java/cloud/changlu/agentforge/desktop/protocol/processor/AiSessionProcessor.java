package cloud.changlu.agentforge.desktop.protocol.processor;

import cloud.changlu.agentforge.desktop.protocol.StreamEventResponse;
import cloud.changlu.agentforge.desktop.protocol.enums.StreamEventEnums;

public class AiSessionProcessor implements IStreamSceneProcessor {
    @Override
    public String getSceneCode() { return StreamEventEnums.EventEnum.SESSION.getCode(); }

    public static StreamEventResponse sessionCreated(String sessionId) {
        return StreamEventResponse.of(
                        StreamEventEnums.EventEnum.SESSION, StreamEventEnums.TypeEnum.CREATED)
                .content(sessionId)
                .finish(false);
    }
}
