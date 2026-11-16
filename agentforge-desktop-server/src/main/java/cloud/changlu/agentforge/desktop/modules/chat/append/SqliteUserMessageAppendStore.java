package cloud.changlu.agentforge.desktop.modules.chat.append;

import cloud.changlu.agentforge.agent.extend.middlewares.append.domain.AppendedUserMessage;
import cloud.changlu.agentforge.agent.extend.middlewares.append.store.AbstractUserMessageAppendStore;
import cloud.changlu.agentforge.desktop.modules.chat.dao.UserMessageAppendMapper;
import cloud.changlu.agentforge.desktop.modules.chat.domain.UserMessageAppendPojo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** SQLite 持久化的追加消息队列，短生命周期记录在 Run 结束后删除。 */
public class SqliteUserMessageAppendStore extends AbstractUserMessageAppendStore {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final UserMessageAppendMapper mapper;

    public SqliteUserMessageAppendStore(UserMessageAppendMapper mapper) {
        super(10);
        this.mapper = mapper;
    }

    @Override
    protected AppendedUserMessage insertMessage(Object memoryId, AppendedUserMessage message) {
        String now = now();
        UserMessageAppendPojo pojo = new UserMessageAppendPojo();
        pojo.setSessionId(key(memoryId));
        pojo.setContent(message.getContent());
        pojo.setStatus(UserMessageAppendStatus.QUEUED.getCode());
        pojo.setQueuedAt(format(message.getQueuedAt()));
        pojo.setCreateTime(now);
        pojo.setModifyTime(now);
        mapper.insert(pojo);
        return new AppendedUserMessage(pojo.getId(), message.getContent(), message.getQueuedAt());
    }

    @Override
    protected List<AppendedUserMessage> listMessages(Object memoryId) {
        List<AppendedUserMessage> messages = new ArrayList<AppendedUserMessage>();
        for (UserMessageAppendPojo pojo :
                mapper.selectPending(key(memoryId), UserMessageAppendStatus.QUEUED.getCode())) {
            messages.add(toMessage(pojo));
        }
        return messages;
    }

    @Override
    protected Optional<AppendedUserMessage> removeMessage(Object memoryId, Long appendId) {
        String sessionId = key(memoryId);
        int status = UserMessageAppendStatus.QUEUED.getCode();
        UserMessageAppendPojo pojo = mapper.selectPendingById(sessionId, appendId, status);
        if (pojo == null || mapper.deletePendingById(sessionId, appendId, status) == 0) {
            return Optional.empty();
        }
        return Optional.of(toMessage(pojo));
    }

    @Override
    protected void deleteMessages(Object memoryId, List<AppendedUserMessage> messages) {
        for (AppendedUserMessage message : messages) {
            mapper.deleteById(message.getAppendId());
        }
    }

    @Override
    protected void clearMessages(Object memoryId) {
        mapper.deleteBySessionId(key(memoryId));
    }

    @Override
    protected int countMessages(Object memoryId) {
        return mapper.countPending(key(memoryId), UserMessageAppendStatus.QUEUED.getCode());
    }

    private static AppendedUserMessage toMessage(UserMessageAppendPojo pojo) {
        return new AppendedUserMessage(pojo.getId(), pojo.getContent(), parse(pojo.getQueuedAt()));
    }

    private static String key(Object memoryId) {
        return String.valueOf(memoryId);
    }

    private static String now() {
        return LocalDateTime.now().format(FORMATTER);
    }

    private static String format(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
                .format(FORMATTER);
    }

    private static long parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            return System.currentTimeMillis();
        }
        try {
            return LocalDateTime.parse(value.trim(), FORMATTER)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
        } catch (RuntimeException ignored) {
            return System.currentTimeMillis();
        }
    }
}