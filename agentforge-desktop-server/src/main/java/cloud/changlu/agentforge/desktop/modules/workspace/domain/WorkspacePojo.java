package cloud.changlu.agentforge.desktop.modules.workspace.domain;

public class WorkspacePojo {
    private Long id;
    private String name;
    private String folderPath;
    private Integer workspaceType;
    private Integer status;
    private String remark;
    private Integer sortOrder;
    private String createTime;
    private String modifyTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getFolderPath() { return folderPath; }
    public void setFolderPath(String folderPath) { this.folderPath = folderPath; }
    public Integer getWorkspaceType() { return workspaceType; }
    public void setWorkspaceType(Integer workspaceType) { this.workspaceType = workspaceType; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }
    public String getModifyTime() { return modifyTime; }
    public void setModifyTime(String modifyTime) { this.modifyTime = modifyTime; }
}
