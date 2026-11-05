package cloud.changlu.agentforge.desktop.store;

import cloud.changlu.agentforge.desktop.config.DesktopProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class DesktopSchemaMigrator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DesktopSchemaMigrator.class);
    private static final String PREFIX = "db/sqlite/increment/";

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final DesktopProperties properties;

    public DesktopSchemaMigrator(
            DataSource dataSource, JdbcTemplate jdbcTemplate, DesktopProperties properties) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        migrate();
    }

    public void migrate() {
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS desktop_schema_migration ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "script_name TEXT NOT NULL,"
                        + "version TEXT NOT NULL,"
                        + "checksum TEXT NOT NULL,"
                        + "applied_at INTEGER NOT NULL)");

        final Map<String, String> applied = new HashMap<String, String>();
        jdbcTemplate.query(
                "SELECT script_name, checksum FROM desktop_schema_migration",
                rs -> {
                    applied.put(rs.getString("script_name"), rs.getString("checksum"));
                });

        List<ScriptResource> scripts = scanScripts();
        if (scripts.isEmpty()) {
            log.warn("no incremental sql found under classpath:{}", PREFIX);
            return;
        }
        for (ScriptResource script : scripts) {
            try {
                byte[] bytes = read(script.resource);
                String checksum = sha256(bytes);
                String previous = applied.get(script.name);
                if (previous != null) {
                    if (!previous.equals(checksum)) {
                        throw new IllegalStateException(
                                "incremental sql changed after execution: " + script.name);
                    }
                    continue;
                }
                execute(script, bytes, checksum);
                archive(script, bytes);
                log.info("applied incremental sql: {}", script.name);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("failed to apply incremental sql: " + script.name, e);
            }
        }
    }

    private List<ScriptResource> scanScripts() {
        List<ScriptResource> scripts = new ArrayList<ScriptResource>();
        try {
            Resource[] resources =
                    new PathMatchingResourcePatternResolver()
                            .getResources("classpath*:" + PREFIX + "**/*.sql");
            for (Resource resource : resources) {
                String url = resource.getURL().toString();
                int index = url.indexOf(PREFIX);
                String name = index >= 0 ? url.substring(index + PREFIX.length()) : resource.getFilename();
                name = name.replace('\\', '/');
                scripts.add(new ScriptResource(name, resource));
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to scan incremental sql", e);
        }
        scripts.sort(Comparator.comparing(item -> item.name));
        return scripts;
    }

    private void execute(ScriptResource script, byte[] bytes, String checksum) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        boolean autoCommit = true;
        try {
            autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            ScriptUtils.executeSqlScript(
                    connection,
                    new EncodedResource(
                            new ByteArrayResource(bytes), StandardCharsets.UTF_8));
            java.sql.PreparedStatement statement =
                    connection.prepareStatement(
                            "INSERT INTO desktop_schema_migration(script_name,version,checksum,applied_at)"
                                    + " VALUES(?,?,?,?)");
            try {
                statement.setString(1, script.name);
                statement.setString(2, versionOf(script.name));
                statement.setString(3, checksum);
                statement.setLong(4, System.currentTimeMillis());
                statement.executeUpdate();
            } finally {
                statement.close();
            }
            connection.commit();
        } catch (Exception e) {
            try {
                connection.rollback();
            } catch (Exception ignored) {
                // ignore rollback failure
            }
            throw new IllegalStateException("execute failed: " + script.name, e);
        } finally {
            try {
                connection.setAutoCommit(autoCommit);
            } catch (Exception ignored) {
                // ignore
            }
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private void archive(ScriptResource script, byte[] bytes) throws IOException {
        Path target = properties.rootPath().resolve("sql-applied").resolve(script.name);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private static byte[] read(Resource resource) throws IOException {
        java.io.InputStream input = resource.getInputStream();
        try {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static String versionOf(String name) {
        int slash = name.lastIndexOf('/');
        return slash > 0 ? name.substring(0, slash) : "";
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(bytes);
            StringBuilder builder = new StringBuilder(hashed.length * 2);
            for (byte value : hashed) {
                builder.append(String.format("%02x", value & 0xff));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("sha256 unavailable", e);
        }
    }

    private static final class ScriptResource {
        private final String name;
        private final Resource resource;

        private ScriptResource(String name, Resource resource) {
            this.name = name;
            this.resource = resource;
        }
    }
}
