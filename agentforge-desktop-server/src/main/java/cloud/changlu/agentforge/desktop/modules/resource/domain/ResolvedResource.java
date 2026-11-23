package cloud.changlu.agentforge.desktop.modules.resource.domain;

import java.nio.file.Path;

/**
 * 已校验的本地资源。
 *
 * @author changlu
 * @since 2026-11-22
 */
public final class ResolvedResource {

    private final Path path;
    private final String name;
    private final String fileType;
    private final long size;
    private final String type;
    private final boolean directory;

    public ResolvedResource(
            Path path,
            String name,
            String fileType,
            long size,
            String type,
            boolean directory) {
        this.path = path;
        this.name = name;
        this.fileType = fileType;
        this.size = size;
        this.type = type;
        this.directory = directory;
    }

    public Path getPath() {
        return path;
    }

    /** 传给框架层的资源地址；桌面端直接使用本机绝对路径。 */
    public String getUrl() {
        return path.toAbsolutePath().toString();
    }

    public String getName() {
        return name;
    }

    public String getFileType() {
        return fileType;
    }

    public long getSize() {
        return size;
    }

    public String getType() {
        return type;
    }

    public boolean isDirectory() {
        return directory;
    }
}
