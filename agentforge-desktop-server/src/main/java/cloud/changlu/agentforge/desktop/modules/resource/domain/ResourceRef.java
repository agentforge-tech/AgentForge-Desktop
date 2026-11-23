package cloud.changlu.agentforge.desktop.modules.resource.domain;

/**
 * 前端输入框内联引用的本地资源。
 *
 * <p>桌面端不上传文件，资源始终以本机绝对路径传递；类型字段仅用于前端展示与后端兜底识别。
 *
 * @author changlu
 * @since 2026-11-22
 */
public class ResourceRef {

    /** 本机绝对路径。 */
    private String path;

    /** 展示名称，默认取文件名。 */
    private String name;

    /** 小写扩展名，不含点；目录为空。 */
    private String fileType;

    /** 字节数；目录为 0。 */
    private Long size;

    /** image / file / folder / document / html / pdf。 */
    private String type;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}
