package cloud.changlu.agentforge.desktop.modules.session.dao;

import cloud.changlu.agentforge.desktop.modules.session.domain.AgentSessionMessagePojo;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AgentSessionMessageMapper {

    @Select("SELECT COALESCE(MAX(seq_num),0) FROM ai_agent_session_message WHERE session_id=#{sessionId} AND is_deleted=0")
    Integer selectMaxSeqNum(@Param("sessionId") Long sessionId);

    @Insert("INSERT INTO ai_agent_session_message(session_id,seq_num,role_type,status,started_at,finished_at,elapsed_ms,model_id,content,events,create_user_id,is_deleted,create_time,modify_time)"
            + " VALUES(#{sessionId},#{seqNum},#{roleType},#{status},#{startedAt},#{finishedAt},#{elapsedMs},#{modelId},#{content},#{events},#{createUserId},#{isDeleted},#{createTime},#{modifyTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AgentSessionMessagePojo pojo);

    @Select("SELECT * FROM ai_agent_session_message WHERE session_id=#{sessionId} AND is_deleted=0 ORDER BY seq_num ASC")
    List<AgentSessionMessagePojo> selectBySessionId(@Param("sessionId") Long sessionId);

    @Update("UPDATE ai_agent_session_message SET status=#{status}, started_at=#{startedAt}, modify_time=#{modifyTime} WHERE id=#{id}")
    int updateStarted(@Param("id") Long id, @Param("status") Integer status,
                      @Param("startedAt") String startedAt, @Param("modifyTime") String modifyTime);

    @Update("UPDATE ai_agent_session_message SET status=#{status}, content=#{content}, events=#{events}, elapsed_ms=#{elapsedMs}, finished_at=#{finishedAt}, modify_time=#{modifyTime} WHERE id=#{id}")
    int finish(@Param("id") Long id, @Param("status") Integer status, @Param("content") String content,
               @Param("events") String events, @Param("elapsedMs") Long elapsedMs,
               @Param("finishedAt") String finishedAt, @Param("modifyTime") String modifyTime);

    @Update("UPDATE ai_agent_session_message SET is_deleted=1, modify_time=#{modifyTime} WHERE session_id=#{sessionId}")
    int logicDeleteBySessionId(@Param("sessionId") Long sessionId, @Param("modifyTime") String modifyTime);

    @Select("SELECT COUNT(1) FROM ai_agent_session_message WHERE session_id=#{sessionId} AND is_deleted=0")
    int countBySessionId(@Param("sessionId") Long sessionId);

    @Select("SELECT MAX(COALESCE(finished_at, create_time)) FROM ai_agent_session_message WHERE session_id=#{sessionId} AND is_deleted=0")
    String selectLastMessageTime(@Param("sessionId") Long sessionId);
}
