package cloud.changlu.agentforge.desktop.modules.resource.read;

import cloud.changlu.agentforge.agent.extend.middlewares.read.domain.ReadBatchResult;
import cloud.changlu.agentforge.agent.extend.middlewares.read.support.ReadResultJson;
import cloud.changlu.agentforge.agent.extend.middlewares.read.tool.ReadToolOptions;
import cloud.changlu.agentforge.model.tool.P;
import cloud.changlu.agentforge.model.tool.Tool;

import java.util.Collections;
import java.util.List;

/**
 * 桌面端无状态资源读取工具。
 *
 * <p>与 AgentForge service 的 read_resource 保持同名，便于 Middleware 采集；差异是同时接受本机绝对路径。
 *
 * @author changlu
 * @since 2026-11-22
 */
public final class DesktopReadResourceTool {

    public static final String TOOL_NAME = "read_resource";

    private final DesktopLocalReadFetcher fetcher;

    public DesktopReadResourceTool() {
        this(ReadToolOptions.defaults());
    }

    public DesktopReadResourceTool(ReadToolOptions options) {
        this.fetcher = new DesktopLocalReadFetcher(options, null);
    }

    @Tool(
            name = TOOL_NAME,
            value =
                    "Read one or more local resources or web links by path/URL. "
                            + "Use this when the user references local images, files, folders, or article links that you need to understand. "
                            + "Local paths are absolute paths on the user's machine. "
                            + "For images the returned metadata is assembled into the next model call as an image attachment. "
                            + "For text-like files and directories the returned metadata may include a readable excerpt.")
    public String read(
            @P(
                            name = "paths",
                            description =
                                    "List of 1 to 5 absolute local paths or http/https URLs, for example [\"/Users/me/a.png\"]")
                    List<String> paths,
            @P(name = "hint", description = "why these resources are needed", required = false)
                    String hint) {
        ReadBatchResult batch =
                fetcher.fetch(paths == null ? Collections.<String>emptyList() : paths, hint);
        return ReadResultJson.toJson(batch);
    }
}
