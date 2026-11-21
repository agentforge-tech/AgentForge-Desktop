package cloud.changlu.agentforge.desktop.modules.chat.event.store;

import cloud.changlu.agentforge.desktop.config.DesktopProperties;
import cloud.changlu.agentforge.desktop.modules.chat.dao.MessageEventMapper;
import cloud.changlu.agentforge.desktop.modules.chat.event.sink.StreamEventSink;
import cloud.changlu.agentforge.desktop.modules.chat.event.sink.memory.InMemoryMessageEventSink;
import cloud.changlu.agentforge.desktop.modules.chat.event.sink.store.BufferedMessageEventSink;
import cloud.changlu.agentforge.desktop.modules.chat.event.store.memory.InMemoryMessageEventStore;
import cloud.changlu.agentforge.desktop.modules.chat.event.store.sqlite.SqliteMessageEventStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/** 根据桌面配置成对选择事件 Store 与生产 Sink。 */
@Component
public class MessageEventFactory implements MessageEventStore, MessageEventTailer {

    private final MessageEventStore store;
    private final MessageEventTailer tailer;
    private final ObjectMapper objectMapper;
    private final String type;

    public MessageEventFactory(
            DesktopProperties properties,
            MessageEventMapper mapper,
            ObjectMapper objectMapper) {
        this.type = normalize(properties.getChatEventStoreType());
        this.objectMapper = objectMapper;
        MessageEventStore selected;
        if ("sqlite".equals(type)) {
            selected = new SqliteMessageEventStore(mapper);
        } else if ("inmemory".equals(type)) {
            selected = new InMemoryMessageEventStore();
        } else {
            throw new IllegalArgumentException(
                    "不支持的 agentforge.desktop.chat-event-store-type: " + type);
        }
        this.store = selected;
        this.tailer = (MessageEventTailer) selected;
    }

    public StreamEventSink createSink(long messageId) {
        if ("sqlite".equals(type)) {
            return new BufferedMessageEventSink(messageId, store, objectMapper);
        }
        return new InMemoryMessageEventSink(messageId, store, objectMapper);
    }

    @Override
    public StoredMessageEvent append(
            long messageId, long eventSeq, String eventType, String payload) {
        return store.append(messageId, eventSeq, eventType, payload);
    }

    @Override
    public List<StoredMessageEvent> readAfter(long messageId, long afterSeq, int limit) {
        return store.readAfter(messageId, afterSeq, limit);
    }

    @Override
    public List<StoredMessageEvent> readAll(long messageId) {
        return store.readAll(messageId);
    }

    @Override
    public long maxSeq(long messageId) {
        return store.maxSeq(messageId);
    }

    @Override
    public void deleteByMessageId(long messageId) {
        store.deleteByMessageId(messageId);
    }

    @Override
    public List<StoredMessageEvent> awaitAfter(
            long messageId, long afterSeq, int limit, long waitMillis) {
        return tailer.awaitAfter(messageId, afterSeq, limit, waitMillis);
    }

    private static String normalize(String value) {
        String normalized = value == null ? "sqlite" : value.trim().toLowerCase();
        return normalized.isEmpty() ? "sqlite" : normalized;
    }
}
