package cloud.changlu.agentforge.desktop.config;

import cloud.changlu.agentforge.agent.extend.middlewares.append.event.UserMessageAppendEventListener;
import cloud.changlu.agentforge.agent.governance.interrupt.spi.AgentInterruptStore;
import cloud.changlu.agentforge.agent.extend.middlewares.append.store.UserMessageAppendStore;
import cloud.changlu.agentforge.desktop.modules.chat.append.SqliteUserMessageAppendStore;
import cloud.changlu.agentforge.desktop.modules.chat.context.DesktopAgentContextAccessor;
import cloud.changlu.agentforge.desktop.modules.chat.dao.AgentInterruptSnapshotMapper;
import cloud.changlu.agentforge.desktop.modules.chat.dao.UserMessageAppendMapper;
import cloud.changlu.agentforge.desktop.modules.chat.interrupt.AgentInterruptSnapshotCodec;
import cloud.changlu.agentforge.desktop.modules.chat.interrupt.DesktopInterruptEventContentFactory;
import cloud.changlu.agentforge.desktop.modules.chat.interrupt.SqliteAgentInterruptStore;
import cloud.changlu.agentforge.desktop.modules.chat.middleware.DesktopUserMessageAppendEventListener;
import cloud.changlu.agentforge.desktop.modules.chat.middleware.ServiceStreamingMiddleware;
import cloud.changlu.agentforge.desktop.modules.chat.middleware.SessionLifecycleMiddleware;
import cloud.changlu.agentforge.desktop.modules.session.dao.AgentSessionMapper;
import cloud.changlu.agentforge.desktop.modules.session.dao.AgentSessionMessageMapper;
import cloud.changlu.agentforge.desktop.store.SqliteChatMemoryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class DesktopAgentConfiguration {

    @Bean
    public DesktopAgentContextAccessor desktopAgentContextAccessor() {
        return new DesktopAgentContextAccessor();
    }

    @Bean
    public DesktopInterruptEventContentFactory desktopInterruptEventContentFactory(
            DesktopAgentContextAccessor contextAccessor) {
        return new DesktopInterruptEventContentFactory(contextAccessor);
    }

    @Bean
    public SessionLifecycleMiddleware sessionLifecycleMiddleware(
            AgentSessionMapper agentSessionMapper,
            AgentSessionMessageMapper agentSessionMessageMapper,
            ObjectMapper objectMapper) {
        return new SessionLifecycleMiddleware(
                agentSessionMapper, agentSessionMessageMapper, objectMapper);
    }

    @Bean
    public ServiceStreamingMiddleware serviceStreamingMiddleware(
            SessionLifecycleMiddleware sessionLifecycleMiddleware,
            DesktopInterruptEventContentFactory interruptContentFactory) {
        return new ServiceStreamingMiddleware(
                sessionLifecycleMiddleware, interruptContentFactory);
    }

    @Bean
    public AgentInterruptStore agentInterruptStore(
            AgentInterruptSnapshotMapper mapper,
            ObjectMapper objectMapper,
            DesktopAgentContextAccessor contextAccessor) {
        return new SqliteAgentInterruptStore(
                mapper, new AgentInterruptSnapshotCodec(objectMapper), contextAccessor);
    }

    @Bean
    public UserMessageAppendStore userMessageAppendStore(UserMessageAppendMapper mapper) {
        return new SqliteUserMessageAppendStore(mapper);
    }

    @Bean
    public UserMessageAppendEventListener userMessageAppendEventListener(
            ServiceStreamingMiddleware streamingMiddleware,
            SessionLifecycleMiddleware lifecycleMiddleware) {
        return new DesktopUserMessageAppendEventListener(
                streamingMiddleware, lifecycleMiddleware);
    }

    @Bean
    public SqliteChatMemoryStore sqliteChatMemoryStore(JdbcTemplate jdbcTemplate) {
        return new SqliteChatMemoryStore(jdbcTemplate);
    }
}
