package cloud.changlu.agentforge.desktop.modules.chat.dao;

import cloud.changlu.agentforge.desktop.modules.chat.domain.AgentInterruptSnapshotPojo;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentInterruptSnapshotMapper {

    @Insert("INSERT INTO ai_agent_interrupt_snapshot(batch_id,memory_id,source_execution_id,step,status,"
            + "schema_version,snapshot_json,version,claimed_at,resolved_at,create_time,modify_time)"
            + " VALUES(#{batchId},#{memoryId},#{sourceExecutionId},#{step},#{status},#{schemaVersion},"
            + "#{snapshotJson},#{version},#{claimedAt},#{resolvedAt},#{createTime},#{modifyTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AgentInterruptSnapshotPojo pojo);

    @Select("SELECT * FROM ai_agent_interrupt_snapshot WHERE batch_id=#{batchId}")
    AgentInterruptSnapshotPojo selectByBatchId(@Param("batchId") String batchId);

    @Select("SELECT * FROM ai_agent_interrupt_snapshot WHERE memory_id=#{memoryId}"
            + " AND status='PENDING' ORDER BY id DESC LIMIT 1")
    AgentInterruptSnapshotPojo selectPendingByMemoryId(@Param("memoryId") String memoryId);

    @Update("UPDATE ai_agent_interrupt_snapshot SET status='RESUMING', version=version+1,"
            + " claimed_at=#{now}, modify_time=#{now} WHERE batch_id=#{batchId} AND status='PENDING'")
    int claim(@Param("batchId") String batchId, @Param("now") String now);

    @Update("UPDATE ai_agent_interrupt_snapshot SET status='COMPLETED', version=version+1,"
            + " resolved_at=#{now}, modify_time=#{now} WHERE batch_id=#{batchId} AND status<>'COMPLETED'")
    int complete(@Param("batchId") String batchId, @Param("now") String now);

    @Update("UPDATE ai_agent_interrupt_snapshot SET status='CANCELLED', version=version+1,"
            + " resolved_at=#{now}, modify_time=#{now} WHERE batch_id=#{batchId} AND status='PENDING'")
    int cancel(@Param("batchId") String batchId, @Param("now") String now);
}