package cloud.changlu.agentforge.desktop.protocol.processor;

import cloud.changlu.agentforge.desktop.protocol.StreamEventResponse;
import cloud.changlu.agentforge.desktop.protocol.enums.StreamEventEnums;

public class AiErrorProcessor implements IStreamSceneProcessor {
    @Override
    public String getSceneCode() { return StreamEventEnums.EventEnum.ERROR.getCode(); }

    public static StreamEventResponse respError(String content) {
        return StreamEventResponse.of(
                        StreamEventEnums.EventEnum.ERROR, StreamEventEnums.TypeEnum.TEXT)
                .content(content)
                .finish(true);
    }
}
