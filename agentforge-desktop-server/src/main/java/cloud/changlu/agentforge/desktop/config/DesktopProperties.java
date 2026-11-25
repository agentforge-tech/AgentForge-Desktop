package cloud.changlu.agentforge.desktop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

@Component
@ConfigurationProperties(prefix = "agentforge.desktop")
public class DesktopProperties {

    private String rootDir = System.getProperty("user.home") + "/.agentforge-desktop";
    private String token = "";
    private String configFile = "";
    private long cancelWaitTimeoutMs = 15000L;
    private long cancelPollIntervalMs = 200L;
    private String chatEventStoreType = "sqlite";
    // Agent 工具命令使用的 shell：桌面端会传入"自带 PATH 的包装脚本"，保证 rg 等工具一定能被解析到。
    private String shell = "";

    public Path rootPath() {
        return Paths.get(rootDir).toAbsolutePath().normalize();
    }

    public Path configPath() {
        if (configFile != null && !configFile.trim().isEmpty()) {
            return Paths.get(configFile.trim()).toAbsolutePath().normalize();
        }
        return rootPath().resolve("conf").resolve("config.json");
    }

    public Path modelsPath() {
        return configPath().resolveSibling("models.json");
    }

    public Path legacyConfigPath() {
        return rootPath().resolve("config.json");
    }

    public Path dbPath() {
        return rootPath().resolve("data").resolve("agentforge-desktop.db");
    }

    public Path globalSkillRoot() {
        return rootPath().resolve("skills");
    }

    public String getRootDir() {
        return rootDir;
    }

    public void setRootDir(String rootDir) {
        this.rootDir = rootDir;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getConfigFile() {
        return configFile;
    }

    public void setConfigFile(String configFile) {
        this.configFile = configFile;
    }

    public long getCancelWaitTimeoutMs() {
        return cancelWaitTimeoutMs;
    }

    public void setCancelWaitTimeoutMs(long cancelWaitTimeoutMs) {
        this.cancelWaitTimeoutMs = cancelWaitTimeoutMs;
    }

    public long getCancelPollIntervalMs() {
        return cancelPollIntervalMs;
    }

    public void setCancelPollIntervalMs(long cancelPollIntervalMs) {
        this.cancelPollIntervalMs = cancelPollIntervalMs;
    }

    public String getChatEventStoreType() {
        return chatEventStoreType;
    }

    public void setChatEventStoreType(String chatEventStoreType) {
        this.chatEventStoreType = chatEventStoreType;
    }

    public String getShell() {
        return shell;
    }

    public void setShell(String shell) {
        this.shell = shell;
    }
}
