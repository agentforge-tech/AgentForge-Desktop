package cloud.changlu.agentforge.desktop.modules.chat.event.store;

import java.util.List;

/** 可替换的消息事件存储 SPI。桌面端默认 SQLite，也支持进程内存实现。 */
public interface MessageEventStore {

    StoredMessageEvent append(long messageId, long eventSeq, String eventType, String payload);

    List<StoredMessageEvent> readAfter(long messageId, long afterSeq, int limit);

    List<StoredMessageEvent> readAll(long messageId);

    long maxSeq(long messageId);

    void deleteByMessageId(long messageId);
}
