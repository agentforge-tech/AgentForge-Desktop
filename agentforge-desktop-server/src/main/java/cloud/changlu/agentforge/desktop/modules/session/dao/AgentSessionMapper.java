package cloud.changlu.agentforge.desktop.modules.session.dao;

import cloud.changlu.agentforge.desktop.modules.session.domain.AgentSessionPojo;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AgentSessionMapper {

    @Select("SELECT * FROM ai_agent_session WHERE session_id=#{sessionId} AND aid=#{aid} AND is_deleted=0 LIMIT 1")
    AgentSessionPojo selectBySessionIdAndAid(@Param("sessionId") String sessionId, @Param("aid") String aid);

    @Select("SELECT * FROM ai_agent_session WHERE id=#{id} AND is_deleted=0")
    AgentSessionPojo selectById(@Param("id") Long id);

    @Select("SELECT * FROM ai_agent_session WHERE session_id=#{sessionId} AND is_deleted=0 LIMIT 1")
    AgentSessionPojo selectBySessionId(@Param("sessionId") String sessionId);

    @Insert("INSERT INTO ai_agent_session(session_id,aid,title,status,model_id,workspace_id,workspace_path,pin_order,create_user_id,is_deleted,create_time,modify_time)"
            + " VALUES(#{sessionId},#{aid},#{title},#{status},#{modelId},#{workspaceId},#{workspacePath},#{pinOrder},#{createUserId},#{isDeleted},#{createTime},#{modifyTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AgentSessionPojo pojo);

    @Update("UPDATE ai_agent_session SET modify_time=#{modifyTime} WHERE id=#{id}")
    int touch(@Param("id") Long id, @Param("modifyTime") String modifyTime);

    @Update("UPDATE ai_agent_session SET model_id=#{modelId}, modify_time=#{modifyTime} WHERE id=#{id}")
    int updateModelId(@Param("id") Long id, @Param("modelId") Long modelId, @Param("modifyTime") String modifyTime);

    @Update("UPDATE ai_agent_session SET context=#{context} WHERE id=#{id}")
    int updateContext(@Param("id") Long id, @Param("context") String context);

    @Update("UPDATE ai_agent_session SET workspace_id=#{workspaceId}, workspace_path=#{workspacePath}, modify_time=#{modifyTime} WHERE id=#{id}")
    int updateWorkspace(@Param("id") Long id, @Param("workspaceId") Long workspaceId, @Param("workspacePath") String workspacePath, @Param("modifyTime") String modifyTime);

    @Update("UPDATE ai_agent_session SET workspace_id=NULL, modify_time=#{modifyTime} WHERE workspace_id=#{workspaceId} AND is_deleted=0")
    int clearWorkspace(@Param("workspaceId") Long workspaceId, @Param("modifyTime") String modifyTime);

    @Select("SELECT * FROM ai_agent_session WHERE is_deleted=0 AND aid=#{aid} AND pin_order=0 ORDER BY modify_time DESC LIMIT #{limit}")
    List<AgentSessionPojo> selectNormalList(@Param("aid") String aid, @Param("limit") int limit);

    /**
     * 不限条数的非置顶会话列表。
     *
     * <p>左栏任务分组与工作空间分组需要完整展示历史会话，避免窗口外的会话“凭空消失”。
     */
    @Select("SELECT * FROM ai_agent_session WHERE is_deleted=0 AND aid=#{aid} AND pin_order=0 ORDER BY modify_time DESC")
    List<AgentSessionPojo> selectAllNormalList(@Param("aid") String aid);

    @Select("SELECT * FROM ai_agent_session WHERE is_deleted=0 AND aid=#{aid} AND pin_order>0 ORDER BY pin_order DESC")
    List<AgentSessionPojo> selectTopList(@Param("aid") String aid);

    @Update("UPDATE ai_agent_session SET pin_order=#{pinOrder}, modify_time=#{modifyTime} WHERE id=#{id}")
    int updatePinOrder(@Param("id") Long id, @Param("pinOrder") Integer pinOrder, @Param("modifyTime") String modifyTime);

    @Select("SELECT COALESCE(MAX(pin_order),0) FROM ai_agent_session WHERE is_deleted=0")
    Integer selectMaxPinOrder();

    @Update("UPDATE ai_agent_session SET title=#{title}, modify_time=#{modifyTime} WHERE id=#{id}")
    int updateTitle(@Param("id") Long id, @Param("title") String title, @Param("modifyTime") String modifyTime);

    @Update("UPDATE ai_agent_session SET is_deleted=1, modify_time=#{modifyTime} WHERE id=#{id}")
    int logicDelete(@Param("id") Long id, @Param("modifyTime") String modifyTime);

    @Update("UPDATE ai_agent_session SET status=#{status}, modify_time=#{modifyTime} WHERE id=#{id}")
    int updateStatus(@Param("id") Long id, @Param("status") Integer status, @Param("modifyTime") String modifyTime);

    @Update("UPDATE ai_agent_session SET status=#{status}, modify_time=#{modifyTime} WHERE session_id=#{sessionId} AND is_deleted=0")
    int updateStatusBySessionId(@Param("sessionId") String sessionId, @Param("status") Integer status, @Param("modifyTime") String modifyTime);
}
