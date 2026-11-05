package cloud.changlu.agentforge.desktop.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class DesktopDataSourceConfig {

    @Bean
    public DataSource dataSource(DesktopProperties properties) throws IOException {
        Path db = properties.dbPath();
        Files.createDirectories(db.getParent());
        Files.createDirectories(properties.rootPath().resolve("logs"));

        HikariConfig config = new HikariConfig();
        config.setPoolName("agentforge-desktop-sqlite");
        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl("jdbc:sqlite:" + db.toAbsolutePath()
                + "?busy_timeout=5000&journal_mode=WAL&synchronous=NORMAL&foreign_keys=on");
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setConnectionTestQuery("SELECT 1");
        return new HikariDataSource(config);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
