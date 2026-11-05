package cloud.changlu.agentforge.desktop.store;

import cloud.changlu.agentforge.agent.memory.store.ChatMemoryStore;
import cloud.changlu.agentforge.agent.memory.store.ChatMessageCodec;
import cloud.changlu.agentforge.model.chat.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

public class SqliteChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteChatMemoryStore.class);
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;
    private final ChatMessageCodec codec = new ChatMessageCodec();

    public SqliteChatMemoryStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        List<String> rows =
                jdbcTemplate.query(
                        "SELECT content FROM ai_agent_memory WHERE memory_id = ? ORDER BY id DESC LIMIT 1",
                        (rs, rowNum) -> rs.getString(1),
                        memoryIdOf(memoryId));
        if (rows.isEmpty() || rows.get(0) == null) {
            return Collections.emptyList();
        }
        try {
            return codec.decodeAll(rows.get(0));
        } catch (RuntimeException e) {
            log.warn("failed to decode chat memory, memoryId={}", memoryId, e);
            return Collections.emptyList();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String id = memoryIdOf(memoryId);
        if (messages == null || messages.isEmpty()) {
            deleteMessages(id);
            return;
        }
        String content = codec.encodeAll(messages);
        String now = now();
        try {
            int updated =
                    jdbcTemplate.update(
                            "UPDATE ai_agent_memory SET content = ?, modify_time = ? WHERE memory_id = ?",
                            content,
                            now,
                            id);
            if (updated == 0) {
                jdbcTemplate.update(
                        "INSERT INTO ai_agent_memory(memory_id, content, create_time, modify_time)"
                                + " VALUES(?,?,?,?)",
                        id,
                        content,
                        now,
                        now);
            }
        } catch (RuntimeException e) {
            log.warn("failed to persist chat memory, memoryId={}, size={}", id, messages.size(), e);
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        try {
            jdbcTemplate.update("DELETE FROM ai_agent_memory WHERE memory_id = ?", memoryIdOf(memoryId));
        } catch (RuntimeException e) {
            log.warn("failed to delete chat memory, memoryId={}", memoryId, e);
        }
    }

    private static String memoryIdOf(Object memoryId) {
        return memoryId == null ? "default" : String.valueOf(memoryId);
    }

    private static String now() {
        return LocalDateTime.now().format(FORMATTER);
    }
}
