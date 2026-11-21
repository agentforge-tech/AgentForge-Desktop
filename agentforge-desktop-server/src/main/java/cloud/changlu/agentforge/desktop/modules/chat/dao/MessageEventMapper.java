package cloud.changlu.agentforge.desktop.modules.chat.dao;

import cloud.changlu.agentforge.desktop.modules.chat.event.store.StoredMessageEvent;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MessageEventMapper {

    @Insert("INSERT INTO ai_agent_session_message_event(message_id,event_seq,event_type,payload)"
            + " VALUES(#{messageId},#{eventSeq},#{eventType},#{payload})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(StoredMessageEvent event);

    @Select("SELECT id,message_id,event_seq,event_type,payload"
            + " FROM ai_agent_session_message_event WHERE message_id=#{messageId}"
            + " AND event_seq>#{afterSeq} ORDER BY event_seq ASC LIMIT #{limit}")
    List<StoredMessageEvent> selectAfter(
            @Param("messageId") Long messageId,
            @Param("afterSeq") Long afterSeq,
            @Param("limit") Integer limit);

    @Select("SELECT id,message_id,event_seq,event_type,payload"
            + " FROM ai_agent_session_message_event WHERE message_id=#{messageId}"
            + " ORDER BY event_seq ASC")
    List<StoredMessageEvent> selectAll(@Param("messageId") Long messageId);

    @Select("SELECT MAX(event_seq) FROM ai_agent_session_message_event WHERE message_id=#{messageId}")
    Long selectMaxSeq(@Param("messageId") Long messageId);

    @Delete("DELETE FROM ai_agent_session_message_event WHERE message_id=#{messageId}")
    int deleteByMessageId(@Param("messageId") Long messageId);
}
