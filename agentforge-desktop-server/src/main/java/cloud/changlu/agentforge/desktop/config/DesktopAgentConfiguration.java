package cloud.changlu.agentforge.desktop.config;

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
    public ServiceStreamingMiddleware serviceStreamingMiddleware() {
        return new ServiceStreamingMiddleware();
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
    public SqliteChatMemoryStore sqliteChatMemoryStore(JdbcTemplate jdbcTemplate) {
        return new SqliteChatMemoryStore(jdbcTemplate);
    }
}
