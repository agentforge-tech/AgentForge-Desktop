package cloud.changlu.agentforge.desktop.modules.workspace.dao;

import cloud.changlu.agentforge.desktop.modules.workspace.domain.WorkspacePojo;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface WorkspaceMapper {
    @Insert("INSERT INTO ai_workspace(name,folder_path,workspace_type,status,remark,sort_order,create_time,modify_time)"
            + " VALUES(#{name},#{folderPath},#{workspaceType},#{status},#{remark},#{sortOrder},#{createTime},#{modifyTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(WorkspacePojo pojo);

    @Select("SELECT * FROM ai_workspace WHERE id=#{id} AND status<>3")
    WorkspacePojo selectById(@Param("id") Long id);

    @Select("SELECT * FROM ai_workspace WHERE folder_path=#{folderPath} AND status<>3 LIMIT 1")
    WorkspacePojo selectByFolderPath(@Param("folderPath") String folderPath);

    @Select("SELECT * FROM ai_workspace WHERE status<>3 ORDER BY sort_order DESC, modify_time DESC")
    List<WorkspacePojo> selectList();

    @Update("UPDATE ai_workspace SET status=#{status}, modify_time=#{modifyTime} WHERE id=#{id}")
    int updateStatus(@Param("id") Long id, @Param("status") Integer status, @Param("modifyTime") String modifyTime);

    @Update("UPDATE ai_workspace SET name=#{name}, modify_time=#{modifyTime} WHERE id=#{id} AND status<>3")
    int updateName(@Param("id") Long id, @Param("name") String name, @Param("modifyTime") String modifyTime);
}
