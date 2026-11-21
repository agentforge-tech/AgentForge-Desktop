package cloud.changlu.agentforge.desktop.modules.chat.event.sink;

import cloud.changlu.agentforge.service.protocol.StreamEventResponse;

/**
 * 流式事件统一出口。后台任务只依赖 Sink，不依赖 SSE 连接是否存在。
 */
public interface StreamEventSink {

    void publish(StreamEventResponse event);

    default void close() {}
}
