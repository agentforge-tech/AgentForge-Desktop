package cloud.changlu.agentforge.desktop.modules.chat.event.sink.store;

import cloud.changlu.agentforge.desktop.modules.chat.event.sink.StreamEventSink;
import cloud.changlu.agentforge.desktop.modules.chat.event.store.MessageEventStore;
import cloud.changlu.agentforge.service.protocol.StreamEventResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * SQLite 事件生产 Sink。相邻文本增量合并后再落一行，工具/错误/结束等边界事件先 flush。
 */
public final class BufferedMessageEventSink implements StreamEventSink {

    private static final int MAX_MERGED_CHUNKS = 12;
    private static final int MAX_MERGED_CHARS = 1024;
    private static final long MAX_BUFFER_MILLIS = 80L;

    private final long messageId;
    private final MessageEventStore eventStore;
    private final ObjectMapper objectMapper;
    private long nextSeq;
    private StreamEventResponse bufferedDelta;
    private int bufferedChunks;
    private long bufferedAt;

    public BufferedMessageEventSink(
            long messageId, MessageEventStore eventStore, ObjectMapper objectMapper) {
        this.messageId = messageId;
        this.eventStore = eventStore;
        this.objectMapper = objectMapper;
        this.nextSeq = eventStore.maxSeq(messageId);
    }

    @Override
    public synchronized void publish(StreamEventResponse event) {
        if (event == null) {
            return;
        }
        if (isMergeableDelta(event)) {
            appendDelta(event);
            return;
        }
        flush();
        append(copyOf(event));
    }

    @Override
    public synchronized void close() {
        flush();
    }

    private void appendDelta(StreamEventResponse event) {
        if (bufferedDelta == null || !sameDeltaGroup(bufferedDelta, event)) {
            flush();
            bufferedDelta = copyOf(event);
            bufferedChunks = 1;
            bufferedAt = System.currentTimeMillis();
        } else {
            bufferedDelta.setContent(
                    String.valueOf(bufferedDelta.getContent())
                            + String.valueOf(event.getContent()));
            bufferedChunks++;
        }
        int chars = String.valueOf(bufferedDelta.getContent()).length();
        if (bufferedChunks >= MAX_MERGED_CHUNKS
                || chars >= MAX_MERGED_CHARS
                || System.currentTimeMillis() - bufferedAt >= MAX_BUFFER_MILLIS) {
            flush();
        }
    }

    private void flush() {
        if (bufferedDelta == null) {
            return;
        }
        StreamEventResponse event = bufferedDelta;
        bufferedDelta = null;
        bufferedChunks = 0;
        bufferedAt = 0L;
        append(event);
    }

    private void append(StreamEventResponse event) {
        long seq = ++nextSeq;
        event.setEventSeq(seq);
        try {
            eventStore.append(
                    messageId, seq, event.getEvent(), objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            throw new IllegalStateException("流式事件暂存失败, messageId=" + messageId, e);
        }
    }

    private static boolean isMergeableDelta(StreamEventResponse event) {
        if (!(event.getContent() instanceof String) || Boolean.TRUE.equals(event.getIsFinish())) {
            return false;
        }
        return "resp".equals(event.getEvent()) || "think".equals(event.getEvent());
    }

    private static boolean sameDeltaGroup(StreamEventResponse left, StreamEventResponse right) {
        return equals(left.getEvent(), right.getEvent())
                && equals(left.getType(), right.getType())
                && equals(left.getTitle(), right.getTitle());
    }

    private static boolean equals(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private static StreamEventResponse copyOf(StreamEventResponse source) {
        StreamEventResponse copy = new StreamEventResponse();
        copy.setEvent(source.getEvent());
        copy.setType(source.getType());
        copy.setTitle(source.getTitle());
        copy.setContent(source.getContent());
        copy.setIsFinish(source.getIsFinish());
        return copy;
    }
}
