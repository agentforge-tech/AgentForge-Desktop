package cloud.changlu.agentforge.desktop.store;

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
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AgentForge Desktop 的 SQLite 增量脚本迁移器。
 *
 * <p>该组件在 Spring Bean 初始化阶段自动执行，主要职责是：
 *
 * <ol>
 *   <li>建立 {@code desktop_schema_migration} 迁移台账；
 *   <li>从应用 classpath 扫描并按路径排序增量 SQL；
 *   <li>跳过已执行且校验和未变的脚本，执行尚未记录的脚本；
 *   <li>将每个脚本和它的台账记录放在同一个数据库事务中提交；
 * </ol>
 *
 * <p>任意增量脚本失败都会抛出异常，使 Spring 容器启动失败。这样可以避免应用在数据库结构不完整时继续对外提供服务。
 *
 * <p>增量 SQL 的仓库源目录为 {@code agentforge-desktop-server/sql/sqllite}（保留当前项目目录拼写），Maven
 * 打包时会将它映射到 {@code db/sqlite/increment/}。
 */
@Component
public class DesktopSchemaMigrator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DesktopSchemaMigrator.class);

    /** 打包后增量 SQL 在 classpath/JAR 中的统一根目录。 */
    private static final String PREFIX = "db/sqlite/increment/";

    /** 用于获取原始 JDBC Connection，以手动控制单个脚本的事务边界。 */
    private final DataSource dataSource;

    /** 用于创建和读取迁移台账表；台账是判定 SQL 是否已执行的唯一依据。 */
    private final JdbcTemplate jdbcTemplate;

    public DesktopSchemaMigrator(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Spring 在属性注入完成后自动回调。
     *
     * <p>迁移在 Web 服务完成启动前执行；如果 {@link #migrate()} 抛出异常，当前 Bean 创建失败，进而终止
     * Spring Boot 启动。
     */
    @Override
    public void afterPropertiesSet() {
        migrate();
    }

    /**
     * 执行一次完整的数据库增量迁移。
     *
     * <p>步骤为：创建台账表 -> 读取已执行记录 -> 扫描并排序 SQL -> 校验已执行脚本 -> 依次执行新脚本。
     */
    public void migrate() {
        long migrationStartedAt = System.currentTimeMillis();
        log.info("SQLite incremental migration started, classpath root: {}", PREFIX);

        // 1、先确保迁移台账存在。首次启动时 SQLite 数据库和该表都可以不存在。
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS desktop_schema_migration ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "script_name TEXT NOT NULL,"
                        + "version TEXT NOT NULL,"
                        + "checksum TEXT NOT NULL,"
                        + "applied_at INTEGER NOT NULL)");

        // 2、将已执行脚本加载到内存：key 是 classpath 相对路径，value 是执行时的 SHA-256。
        final Map<String, String> applied = new HashMap<String, String>();
        jdbcTemplate.query(
                "SELECT script_name, checksum FROM desktop_schema_migration",
                rs -> {
                    applied.put(rs.getString("script_name"), rs.getString("checksum"));
                });
        log.info("SQLite migration ledger loaded: {} applied script(s)", applied.size());

        // 3、扫描全部 SQL 并按相对路径字典序排序，文件名中的时间戳因此决定执行顺序。
        List<ScriptResource> scripts = scanScripts();
        if (scripts.isEmpty()) {
            log.warn("no incremental sql found under classpath:{}", PREFIX);
            return;
        }
        int pendingCount = 0;
        for (ScriptResource script : scripts) {
            if (!applied.containsKey(script.name)) {
                pendingCount++;
            }
        }
        log.info(
                "SQLite incremental SQL scan completed: {} discovered, {} pending, {} already applied",
                scripts.size(), pendingCount, scripts.size() - pendingCount);

        if (pendingCount == 0) {
            // 即使没有新脚本，下方循环仍会逐个校验已执行脚本的 checksum。
            log.info("No pending incremental SQL; verifying checksums of applied scripts");
        }

        int appliedThisRun = 0;
        for (ScriptResource script : scripts) {
            try {
                byte[] bytes = read(script.resource);
                String checksum = sha256(bytes);
                String previous = applied.get(script.name);
                if (previous != null) {
                    // 4、已执行脚本不允许再被修改；同名但内容变化时立即中止启动。
                    if (!previous.equals(checksum)) {
                        throw new IllegalStateException(
                                "incremental sql changed after execution: " + script.name);
                    }
                    // 名称和校验和都一致，说明该脚本已安全执行，直接跳过。
                    continue;
                }
                // 5、新脚本：在一个事务中执行 SQL 并写入迁移台账。
                int sequence = appliedThisRun + 1;
                log.info(
                        "Applying incremental SQL [{}/{}]: script={}, version={}, checksum={}",
                        sequence,
                        pendingCount,
                        script.name,
                        versionOf(script.name),
                        shortChecksum(checksum));
                execute(script, bytes, checksum);
                appliedThisRun++;
                // 6、不向用户目录导出 SQL 副本；后续升级只依赖数据库迁移台账。
                log.info(
                        "Incremental SQL applied [{}/{}]: script={}",
                        appliedThisRun,
                        pendingCount,
                        script.name);
            } catch (RuntimeException e) {
                log.error("Incremental SQL migration failed: script={}", script.name, e);
                throw e;
            } catch (Exception e) {
                log.error("Incremental SQL migration failed: script={}", script.name, e);
                throw new IllegalStateException("failed to apply incremental sql: " + script.name, e);
            }
        }
        log.info(
                "SQLite incremental migration completed: {} applied this run, {} total discovered, elapsed={} ms",
                appliedThisRun,
                scripts.size(),
                System.currentTimeMillis() - migrationStartedAt);
    }

    /**
     * 扫描 {@value #PREFIX} 下所有层级的 {@code *.sql}。
     *
     * <p>使用 {@code classpath*:} 使本地 classes 目录和打包后 JAR 中的资源都能被找到。返回结果按相对路径
     * 排序，所以新增脚本应使用可字典序排列的版本目录和时间戳文件名。
     */
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

    /**
     * 在单个 JDBC 事务中执行一个 SQL 脚本并记录台账。
     *
     * <p>SQL 执行和台账 INSERT 必须同时成功；任一步失败都回滚。这保证不会出现“表结构已改变，但台账
     * 没记录”或“台账已记录，但 SQL 未生效”的中间状态。
     */
    private void execute(ScriptResource script, byte[] bytes, String checksum) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        boolean autoCommit = true;
        try {
            // 保留连接原始 autoCommit 状态，方法结束前恢复，避免污染连接池中的连接。
            autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);

            // ScriptUtils 按 UTF-8 解析并执行多条 SQL 语句，任一语句失败会抛出异常。
            ScriptUtils.executeSqlScript(
                    connection,
                    new EncodedResource(
                            new ByteArrayResource(bytes), StandardCharsets.UTF_8));
            log.info("Incremental SQL statements executed successfully: script={}", script.name);

            // 脚本成功后写入文件名、版本目录、校验和与执行时间。
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

            // SQL 与台账记录在这里一次性对外生效。
            connection.commit();
            log.info(
                    "Migration ledger updated and transaction committed: table=desktop_schema_migration,"
                            + " script={}, version={}, checksum={}",
                    script.name,
                    versionOf(script.name),
                    shortChecksum(checksum));
        } catch (Exception e) {
            try {
                // 使该脚本本次已执行的所有语句回滚，下次启动仍会把它视为待执行脚本。
                connection.rollback();
                log.warn("Incremental SQL transaction rolled back: script={}", script.name);
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

    /** 完整读取 classpath 资源的原始字节，这些字节同时用于执行和计算校验和。 */
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

    /**
     * 从脚本相对路径提取版本。例如 {@code 1.0.x/20260101000000_desktop_baseline.sql} 返回 {@code 1.0.x}。
     */
    private static String versionOf(String name) {
        int slash = name.lastIndexOf('/');
        return slash > 0 ? name.substring(0, slash) : "";
    }

    /** 日志中只输出 checksum 前 12 位，既能快速对照台账，又避免日志过长。 */
    private static String shortChecksum(String checksum) {
        return checksum.length() <= 12 ? checksum : checksum.substring(0, 12);
    }

    /** 计算 SQL 原始字节的小写十六进制 SHA-256，用于检测已执行脚本是否被篡改。 */
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

    /** 增量脚本的 classpath 相对名称及实际 Spring Resource。 */
    private static final class ScriptResource {
        private final String name;
        private final Resource resource;

        private ScriptResource(String name, Resource resource) {
            this.name = name;
            this.resource = resource;
        }
    }
}
