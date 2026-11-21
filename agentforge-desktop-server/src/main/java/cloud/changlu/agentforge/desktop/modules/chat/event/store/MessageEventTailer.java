package cloud.changlu.agentforge.desktop.modules.chat.event.store;

import java.util.List;

/** 事件尾读等待语义；SQLite 使用轮询，未来可替换为 Redis BLOCK。 */
public interface MessageEventTailer {

    List<StoredMessageEvent> awaitAfter(
            long messageId, long afterSeq, int limit, long waitMillis);
}
