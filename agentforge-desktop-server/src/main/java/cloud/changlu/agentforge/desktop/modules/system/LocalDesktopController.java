package cloud.changlu.agentforge.desktop.modules.system;

import cloud.changlu.agentforge.desktop.common.ApiResponse;
import cloud.changlu.agentforge.desktop.config.DesktopProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/local")
public class LocalDesktopController {

    private final DesktopProperties properties;

    public LocalDesktopController(DesktopProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(info());
    }

    @GetMapping("/app-config")
    public ApiResponse<Map<String, Object>> appConfig() {
        return ApiResponse.ok(info());
    }

    private Map<String, Object> info() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("ready", true);
        data.put("workspace", properties.rootPath().toString());
        data.put("config", properties.configPath().toString());
        data.put("db", properties.dbPath().toString());
        data.put("version", "0.1.0");
        return data;
    }
}
