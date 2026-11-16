package cloud.changlu.agentforge.desktop.modules.chat.interrupt;

import cloud.changlu.agentforge.agent.governance.interrupt.domain.AgentInterruptSnapshot;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptBatchStatus;
import cloud.changlu.agentforge.agent.governance.interrupt.spi.AgentInterruptStore;
import cloud.changlu.agentforge.desktop.modules.chat.dao.AgentInterruptSnapshotMapper;
import cloud.changlu.agentforge.desktop.modules.chat.domain.AgentInterruptSnapshotPojo;
import org.springframework.dao.DataAccessException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * SQLite 版 AgentInterruptStore。
 *
 * <p>与 InMemoryAgentInterruptStore 语义对齐：首次 save 唯一、get 不存在报错、claim 以单条
 * PENDING -> RESUMING 的条件更新完成 CAS、complete 幂等。快照可跨 Runtime 重建保留，但不承诺
 * 跨进程自动恢复执行。
 */
public final class SqliteAgentInterruptStore implements AgentInterruptStore {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final AgentInterruptSnapshotMapper mapper;
    private final AgentInterruptSnapshotCodec codec;

    public SqliteAgentInterruptStore(
            AgentInterruptSnapshotMapper mapper, AgentInterruptSnapshotCodec codec) {
        this.mapper = mapper;
        this.codec = codec;
    }

    @Override
    public void save(AgentInterruptSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("interrupt snapshot must not be null");
        }
        String batchId = snapshot.getBatchId();
        String now = now();
        AgentInterruptSnapshotPojo pojo = new AgentInterruptSnapshotPojo();
        pojo.setBatchId(batchId);
        pojo.setMemoryId(codec.memoryId(snapshot.getMemoryId()));
        pojo.setSourceExecutionId(snapshot.getSourceExecutionId());
        pojo.setStep(snapshot.getStep());
        pojo.setStatus(snapshot.getStatus().name());
        pojo.setSchemaVersion(codec.schemaVersion());
        pojo.setSnapshotJson(codec.encode(snapshot));
        pojo.setVersion(0L);
        pojo.setCreateTime(now);
        pojo.setModifyTime(now);
        try {
            mapper.insert(pojo);
        } catch (DataAccessException e) {
            if (isDuplicateKey(e)) {
                throw new IllegalStateException("interrupt batch already exists: " + batchId, e);
            }
            throw e;
        }
    }

    @Override
    public AgentInterruptSnapshot get(String batchId) {
        AgentInterruptSnapshotPojo pojo = mapper.selectByBatchId(batchId);
        if (pojo == null) {
            throw new IllegalArgumentException("interrupt batch not found: " + batchId);
        }
        return codec.decode(pojo);
    }

    @Override
    public void claim(String batchId) {
        if (mapper.claim(batchId, now()) == 1) {
            return;
        }
        AgentInterruptSnapshotPojo current = mapper.selectByBatchId(batchId);
        if (current == null) {
            throw new IllegalArgumentException("interrupt batch not found: " + batchId);
        }
        InterruptBatchStatus status = codec.status(current.getStatus(), batchId);
        if (status == InterruptBatchStatus.COMPLETED) {
            throw new IllegalStateException("interrupt batch already completed: " + batchId);
        }
        if (status == InterruptBatchStatus.RESUMING) {
            throw new IllegalStateException("interrupt batch is being resumed: " + batchId);
        }
        throw new IllegalStateException(
                "interrupt batch claim conflict, status=" + status + ", batch=" + batchId);
    }

    @Override
    public void complete(String batchId) {
        mapper.complete(batchId, now());
    }

    private static boolean isDuplicateKey(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("unique constraint failed")
                        || lower.contains("duplicate entry")
                        || lower.contains("duplicate key")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static String now() {
        return LocalDateTime.now().format(FORMATTER);
    }
}