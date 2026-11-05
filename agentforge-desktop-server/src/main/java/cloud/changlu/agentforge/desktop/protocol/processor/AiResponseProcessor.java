package cloud.changlu.agentforge.desktop.protocol.processor;

import cloud.changlu.agentforge.desktop.protocol.StreamEventResponse;
import cloud.changlu.agentforge.desktop.protocol.enums.StreamEventEnums;

public class AiResponseProcessor implements IStreamSceneProcessor {
    @Override
    public String getSceneCode() { return StreamEventEnums.EventEnum.RESP.getCode(); }

    public static StreamEventResponse respText(String content, Boolean isFinish) {
        return StreamEventResponse.of(
                        StreamEventEnums.EventEnum.RESP, StreamEventEnums.TypeEnum.TEXT)
                .content(content)
                .finish(isFinish);
    }

    public static StreamEventResponse respEnd(Boolean isFinish) {
        return StreamEventResponse.of(StreamEventEnums.EventEnum.RESP_END).finish(isFinish);
    }
}
