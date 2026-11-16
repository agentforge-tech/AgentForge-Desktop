package cloud.changlu.agentforge.desktop.modules.chat.service;

import cloud.changlu.agentforge.agent.extend.middlewares.append.domain.AppendedUserMessage;
import cloud.changlu.agentforge.agent.extend.middlewares.append.domain.UserMessageAppendResult;
import cloud.changlu.agentforge.agent.extend.middlewares.append.service.IUserMessageAppendService;
import cloud.changlu.agentforge.agent.extend.middlewares.append.store.UserMessageAppendStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** Agent Loop 与 HTTP 入口共享的追加消息门面。 */
@Service
public class UserMessageAppendService implements IUserMessageAppendService {

    private final UserMessageAppendStore store;

    public UserMessageAppendService(UserMessageAppendStore store) {
        this.store = store;
    }

    @Override
    public boolean open(Object memoryId) {
        return store.open(memoryId);
    }

    @Override
    public UserMessageAppendResult append(Object memoryId, String content) {
        if (memoryId == null || content == null || content.trim().isEmpty()) {
            return UserMessageAppendResult.rejected(UserMessageAppendResult.Code.INVALID_ARGUMENT);
        }
        String normalized = content.trim();
        return store.enqueue(
                memoryId, new AppendedUserMessage(null, normalized, System.currentTimeMillis()));
    }

    @Override
    public List<AppendedUserMessage> drain(Object memoryId) {
        return store.drain(memoryId);
    }

    @Override
    public Optional<AppendedUserMessage> cancel(Object memoryId, Long appendId) {
        return store.remove(memoryId, appendId);
    }

    @Override
    public List<AppendedUserMessage> closeAndDrain(Object memoryId) {
        return store.closeAndDrain(memoryId);
    }

    @Override
    public void clear(Object memoryId) {
        store.clear(memoryId);
    }

    @Override
    public int size(Object memoryId) {
        return store.size(memoryId);
    }
}