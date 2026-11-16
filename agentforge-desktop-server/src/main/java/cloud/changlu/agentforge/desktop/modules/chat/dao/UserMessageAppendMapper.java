package cloud.changlu.agentforge.desktop.modules.chat.dao;

import cloud.changlu.agentforge.desktop.modules.chat.domain.UserMessageAppendPojo;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserMessageAppendMapper {

    @Insert("INSERT INTO ai_agent_session_message_append(session_id,content,status,queued_at,create_time,modify_time)"
            + " VALUES(#{sessionId},#{content},#{status},#{queuedAt},#{createTime},#{modifyTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UserMessageAppendPojo pojo);

    @Select("SELECT * FROM ai_agent_session_message_append WHERE session_id=#{sessionId} AND status=#{status} ORDER BY id ASC")
    List<UserMessageAppendPojo> selectPending(@Param("sessionId") String sessionId, @Param("status") int status);

    @Select("SELECT * FROM ai_agent_session_message_append WHERE session_id=#{sessionId} AND id=#{id} AND status=#{status}")
    UserMessageAppendPojo selectPendingById(@Param("sessionId") String sessionId,
                                            @Param("id") Long id,
                                            @Param("status") int status);

    @Select("SELECT COUNT(1) FROM ai_agent_session_message_append WHERE session_id=#{sessionId} AND status=#{status}")
    int countPending(@Param("sessionId") String sessionId, @Param("status") int status);

    @Delete("DELETE FROM ai_agent_session_message_append WHERE session_id=#{sessionId} AND id=#{id} AND status=#{status}")
    int deletePendingById(@Param("sessionId") String sessionId,
                          @Param("id") Long id,
                          @Param("status") int status);

    @Delete("DELETE FROM ai_agent_session_message_append WHERE id=#{id}")
    int deleteById(@Param("id") Long id);

    @Delete("DELETE FROM ai_agent_session_message_append WHERE session_id=#{sessionId}")
    int deleteBySessionId(@Param("sessionId") String sessionId);
}