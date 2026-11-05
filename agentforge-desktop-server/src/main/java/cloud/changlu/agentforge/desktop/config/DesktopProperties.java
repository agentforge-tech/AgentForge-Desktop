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

    public Path rootPath() {
        return Paths.get(rootDir).toAbsolutePath().normalize();
    }

    public Path configPath() {
        if (configFile != null && !configFile.trim().isEmpty()) {
            return Paths.get(configFile.trim()).toAbsolutePath().normalize();
        }
        return rootPath().resolve("config.json");
    }

    public Path dbPath() {
        return rootPath().resolve("data").resolve("agentforge-desktop.db");
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
}
