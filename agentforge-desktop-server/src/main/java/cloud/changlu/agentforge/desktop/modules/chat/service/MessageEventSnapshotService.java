package cloud.changlu.agentforge.desktop.modules.chat.service;

import cloud.changlu.agentforge.desktop.modules.chat.event.store.MessageEventStore;
import cloud.changlu.agentforge.desktop.modules.chat.event.store.StoredMessageEvent;
import cloud.changlu.agentforge.desktop.modules.session.dao.AgentSessionMessageMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.stereotype.Service;

import java.util.Date;

/** 从事件表生成历史接口继续使用的 events JSON 快照。 */
@Service
public class MessageEventSnapshotService {

    private final MessageEventStore eventStore;
    private final AgentSessionMessageMapper messageMapper;
    private final ObjectMapper objectMapper;

    public MessageEventSnapshotService(
            MessageEventStore eventStore,
            AgentSessionMessageMapper messageMapper,
            ObjectMapper objectMapper) {
        this.eventStore = eventStore;
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
    }

    public String saveSnapshot(long messageId) {
        ArrayNode array = objectMapper.createArrayNode();
        try {
            for (StoredMessageEvent event : eventStore.readAll(messageId)) {
                JsonNode node = objectMapper.readTree(event.getPayload());
                array.add(node);
            }
            String json = objectMapper.writeValueAsString(array);
            messageMapper.updateEvents(messageId, json, formatNow());
            return json;
        } catch (Exception e) {
            throw new IllegalStateException("生成消息事件快照失败, messageId=" + messageId, e);
        }
    }

    private static String formatNow() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }
}
