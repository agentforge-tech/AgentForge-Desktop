package cloud.changlu.agentforge.desktop.modules.chat.event.sink;

import cloud.changlu.agentforge.service.protocol.StreamEventResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 兼容旧版一段式接口的实时 SSE Sink。 */
public final class SseEmitterEventSink implements StreamEventSink {

    private final SseEmitter emitter;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public SseEmitterEventSink(SseEmitter emitter) {
        this.emitter = emitter;
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(error -> closed.set(true));
    }

    @Override
    public synchronized void publish(StreamEventResponse event) {
        if (closed.get() || event == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().data(event));
        } catch (IOException | IllegalStateException ignored) {
            closed.set(true);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 客户端可能已经断开。
            }
        }
    }
}
