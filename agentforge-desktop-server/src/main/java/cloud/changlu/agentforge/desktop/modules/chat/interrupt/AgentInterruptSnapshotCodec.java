package cloud.changlu.agentforge.desktop.modules.chat.interrupt;

import cloud.changlu.agentforge.agent.governance.interrupt.domain.AgentInterruptSnapshot;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptBatchStatus;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptRequest;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptType;
import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionDenial;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskQuestion;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserOption;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserRequest;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserSupport;
import cloud.changlu.agentforge.desktop.modules.chat.domain.AgentInterruptSnapshotPojo;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Framework 中断快照与 snapshot_json 的编解码器。
 *
 * <p>序列化归属 contextAttributes 与冻结的 toolRequests / interrupts / denials；batchId、memoryId、step、status 以列字段为准。
 * 反序列化执行 fail-closed 校验，未知 schema、状态、类型或快照结构不一致时禁止恢复。
 */
public final class AgentInterruptSnapshotCodec {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;

    public AgentInterruptSnapshotCodec(ObjectMapper objectMapper) {
        this.objectMapper =
                objectMapper
                        .copy()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    public int schemaVersion() {
        return CURRENT_SCHEMA_VERSION;
    }

    public String memoryId(Object memoryId) {
        if (memoryId == null) {
            throw new IllegalArgumentException("interrupt snapshot memoryId must not be null");
        }
        if (!(memoryId instanceof String)) {
            throw new IllegalArgumentException(
                    "interrupt snapshot memoryId must be String, actual: "
                            + memoryId.getClass().getName());
        }
        return (String) memoryId;
    }

    public String encode(AgentInterruptSnapshot snapshot) {
        StoredSnapshot stored = new StoredSnapshot();
        stored.setSchemaVersion(CURRENT_SCHEMA_VERSION);
        stored.setContextAttributes(snapshot.getContextAttributes());
        stored.setToolRequests(toStoredRequests(snapshot.getToolRequests()));
        stored.setInterrupts(toStoredInterrupts(snapshot.getInterrupts()));
        stored.setDenials(toStoredDenials(snapshot.getDenials()));
        try {
            return objectMapper.writeValueAsString(stored);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "interrupt snapshot encode failed: " + snapshot.getBatchId(), e);
        }
    }

    public AgentInterruptSnapshot decode(AgentInterruptSnapshotPojo pojo) {
        if (pojo == null) {
            throw new IllegalArgumentException("interrupt snapshot pojo must not be null");
        }
        String batchId = pojo.getBatchId();
        try {
            StoredSnapshot stored = objectMapper.readValue(pojo.getSnapshotJson(), StoredSnapshot.class);
            if (stored.getSchemaVersion() == null
                    || pojo.getSchemaVersion() == null
                    || stored.getSchemaVersion().intValue() != pojo.getSchemaVersion().intValue()
                    || stored.getSchemaVersion().intValue() != CURRENT_SCHEMA_VERSION) {
                throw new IllegalStateException(
                        "unsupported interrupt snapshot schemaVersion: "
                                + stored.getSchemaVersion());
            }
            List<ToolExecutionRequest> toolRequests =
                    toToolRequests(require(stored.getToolRequests(), "toolRequests"), batchId);
            List<InterruptRequest> interrupts =
                    toInterrupts(
                            require(stored.getInterrupts(), "interrupts"), toolRequests, batchId);
            List<PermissionDenial> denials =
                    toDenials(require(stored.getDenials(), "denials"), toolRequests, batchId);
            return new AgentInterruptSnapshot(
                    batchId,
                    pojo.getMemoryId(),
                    pojo.getSourceExecutionId(),
                    pojo.getStep() == null ? 0 : pojo.getStep(),
                    status(pojo.getStatus(), batchId),
                    toolRequests,
                    interrupts,
                    denials,
                    stored.getContextAttributes());
        } catch (IllegalStateException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException("interrupt snapshot decode failed: " + batchId, e);
        } catch (IOException e) {
            throw new IllegalStateException("interrupt snapshot decode failed: " + batchId, e);
        }
    }

    public InterruptBatchStatus status(String value, String batchId) {
        try {
            return InterruptBatchStatus.valueOf(value);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "unknown interrupt batch status '" + value + "' for batch: " + batchId, e);
        }
    }

    private static List<StoredToolRequest> toStoredRequests(List<ToolExecutionRequest> requests) {
        List<StoredToolRequest> stored = new ArrayList<StoredToolRequest>();
        if (requests != null) {
            for (ToolExecutionRequest request : requests) {
                stored.add(new StoredToolRequest(request.id(), request.name(), request.arguments()));
            }
        }
        return stored;
    }

    private static List<StoredInterrupt> toStoredInterrupts(List<InterruptRequest> interrupts) {
        List<StoredInterrupt> stored = new ArrayList<StoredInterrupt>();
        if (interrupts != null) {
            for (InterruptRequest interrupt : interrupts) {
                StoredInterrupt item = new StoredInterrupt();
                item.setInterruptId(interrupt.getInterruptId());
                item.setType(interrupt.getType().name());
                item.setToolRequest(toStoredRequest(interrupt.getToolRequest()));
                if (interrupt.getType() == InterruptType.ASK_USER) {
                    Object payload = interrupt.getPayload();
                    if (!(payload instanceof AskUserRequest)) {
                        throw new IllegalStateException(
                                "ASK_USER interrupt payload must be AskUserRequest: "
                                        + interrupt.getInterruptId());
                    }
                    item.setPayload(toStoredPayload((AskUserRequest) payload));
                } else if (interrupt.getPayload() != null) {
                    throw new IllegalStateException(
                            "PERMISSION_APPROVAL interrupt payload must be null: "
                                    + interrupt.getInterruptId());
                }
                stored.add(item);
            }
        }
        return stored;
    }

    private static List<StoredDenial> toStoredDenials(List<PermissionDenial> denials) {
        List<StoredDenial> stored = new ArrayList<StoredDenial>();
        if (denials != null) {
            for (PermissionDenial denial : denials) {
                stored.add(new StoredDenial(toStoredRequest(denial.getRequest()), denial.getReason()));
            }
        }
        return stored;
    }

    private static StoredToolRequest toStoredRequest(ToolExecutionRequest request) {
        return request == null
                ? null
                : new StoredToolRequest(request.id(), request.name(), request.arguments());
    }

    private static StoredAskUserPayload toStoredPayload(AskUserRequest request) {
        StoredAskUserPayload payload = new StoredAskUserPayload();
        payload.setToolCallId(request.getToolCallId());
        payload.setToolName(request.getToolName());
        payload.setReason(request.getReason());
        payload.setSubmitLabel(request.getSubmitLabel());
        List<StoredQuestion> questions = new ArrayList<StoredQuestion>();
        for (AskQuestion question : request.getQuestions()) {
            StoredQuestion item = new StoredQuestion();
            item.setId(question.getId());
            item.setTitle(question.getTitle());
            item.setDescription(question.getDescription());
            item.setType(question.getType() == null ? null : question.getType().name());
            item.setRequired(question.getRequired());
            item.setMinSelections(question.getMinSelections());
            item.setMaxSelections(question.getMaxSelections());
            item.setMaxLength(question.getMaxLength());
            item.setPlaceholder(question.getPlaceholder());
            List<StoredOption> options = new ArrayList<StoredOption>();
            if (question.getOptions() != null) {
                for (AskUserOption option : question.getOptions()) {
                    options.add(new StoredOption(option.getId(), option.getLabel(), option.getDescription()));
                }
            }
            item.setOptions(options);
            questions.add(item);
        }
        payload.setQuestions(questions);
        return payload;
    }

    private static List<ToolExecutionRequest> toToolRequests(
            List<StoredToolRequest> storedRequests, String batchId) {
        List<ToolExecutionRequest> requests = new ArrayList<ToolExecutionRequest>();
        for (StoredToolRequest stored : storedRequests) {
            ToolExecutionRequest request = toToolRequest(stored, batchId);
            requests.add(request);
        }
        return requests;
    }

    private static ToolExecutionRequest toToolRequest(StoredToolRequest stored, String batchId) {
        if (stored == null
                || blank(stored.getId())
                || blank(stored.getName())
                || stored.getArguments() == null) {
            throw new IllegalStateException("invalid tool request in interrupt snapshot: " + batchId);
        }
        return ToolExecutionRequest.from(stored.getId(), stored.getName(), stored.getArguments());
    }

    private static List<InterruptRequest> toInterrupts(
            List<StoredInterrupt> storedInterrupts,
            List<ToolExecutionRequest> toolRequests,
            String batchId) {
        List<InterruptRequest> interrupts = new ArrayList<InterruptRequest>();
        for (StoredInterrupt stored : storedInterrupts) {
            if (stored == null || blank(stored.getInterruptId()) || blank(stored.getType())) {
                throw new IllegalStateException("invalid interrupt item in snapshot: " + batchId);
            }
            InterruptType type;
            try {
                type = InterruptType.valueOf(stored.getType());
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                        "unknown interrupt type '" + stored.getType() + "' in snapshot: " + batchId,
                        e);
            }
            ToolExecutionRequest toolRequest = toToolRequest(stored.getToolRequest(), batchId);
            if (!toolRequests.contains(toolRequest)) {
                throw new IllegalStateException(
                        "interrupt toolRequest not found in toolRequests: " + batchId);
            }
            Object payload = null;
            if (type == InterruptType.ASK_USER) {
                if (stored.getPayload() == null) {
                    throw new IllegalStateException(
                            "ASK_USER interrupt payload is missing: " + stored.getInterruptId());
                }
                payload = toAskUserRequest(stored.getPayload(), toolRequest, batchId);
            } else if (stored.getPayload() != null) {
                throw new IllegalStateException(
                        "PERMISSION_APPROVAL interrupt payload must be null: "
                                + stored.getInterruptId());
            }
            interrupts.add(new InterruptRequest(stored.getInterruptId(), type, toolRequest, payload));
        }
        return interrupts;
    }

    private static AskUserRequest toAskUserRequest(
            StoredAskUserPayload payload, ToolExecutionRequest toolRequest, String batchId) {
        if (!toolRequest.id().equals(payload.getToolCallId())) {
            throw new IllegalStateException(
                    "ASK_USER payload toolCallId mismatch in snapshot: " + batchId);
        }
        AskUserRequest parsed;
        try {
            parsed = AskUserSupport.parse(toolRequest);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "ASK_USER payload cannot be rebuilt from tool arguments: " + batchId, e);
        }
        // 以持久化 payload 作为冻结基准：当前解析结果必须与其逐字段一致，否则视为 schema 漂移并 fail-closed。
        validatePersistedPayload(payload, parsed, batchId);
        return parsed;
    }

    private static void validatePersistedPayload(
            StoredAskUserPayload stored, AskUserRequest parsed, String batchId) {
        if (!equals(stored.getToolCallId(), parsed.getToolCallId())
                || !equalsNullable(stored.getToolName(), parsed.getToolName())
                || !equalsNullable(stored.getReason(), parsed.getReason())
                || !equalsNullable(stored.getSubmitLabel(), parsed.getSubmitLabel())) {
            throw new IllegalStateException(
                    "ASK_USER payload drifted from tool arguments: " + batchId);
        }
        List<StoredQuestion> storedQuestions = stored.getQuestions();
        if (storedQuestions == null
                || storedQuestions.size() != parsed.getQuestions().size()) {
            throw new IllegalStateException(
                    "ASK_USER questions drifted from tool arguments: " + batchId);
        }
        for (int i = 0; i < storedQuestions.size(); i++) {
            validateQuestion(storedQuestions.get(i), parsed.getQuestions().get(i), batchId);
        }
    }

    private static void validateQuestion(
            StoredQuestion stored, AskQuestion parsed, String batchId) {
        if (stored == null
                || !equals(stored.getId(), parsed.getId())
                || !equals(stored.getTitle(), parsed.getTitle())
                || !equalsNullable(stored.getDescription(), parsed.getDescription())
                || !equalsNullable(stored.getType(), parsed.getType() == null ? null : parsed.getType().name())
                || !equalsNullable(stored.getRequired(), parsed.getRequired())
                || !equalsNullable(stored.getMinSelections(), parsed.getMinSelections())
                || !equalsNullable(stored.getMaxSelections(), parsed.getMaxSelections())
                || !equalsNullable(stored.getMaxLength(), parsed.getMaxLength())
                || !equalsNullable(stored.getPlaceholder(), parsed.getPlaceholder())) {
            throw new IllegalStateException(
                    "ASK_USER question drifted from tool arguments: " + batchId);
        }
        List<StoredOption> storedOptions = stored.getOptions();
        List<AskUserOption> parsedOptions = parsed.getOptions();
        int storedSize = storedOptions == null ? 0 : storedOptions.size();
        int parsedSize = parsedOptions == null ? 0 : parsedOptions.size();
        if (storedSize != parsedSize) {
            throw new IllegalStateException(
                    "ASK_USER options drifted from tool arguments: " + batchId);
        }
        for (int i = 0; i < storedSize; i++) {
            StoredOption storedOption = storedOptions.get(i);
            AskUserOption parsedOption = parsedOptions.get(i);
            if (storedOption == null
                    || !equals(storedOption.getId(), parsedOption.getId())
                    || !equalsNullable(storedOption.getLabel(), parsedOption.getLabel())
                    || !equalsNullable(storedOption.getDescription(), parsedOption.getDescription())) {
                throw new IllegalStateException(
                        "ASK_USER option drifted from tool arguments: " + batchId);
            }
        }
    }

    private static boolean equals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static boolean equalsNullable(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private static List<PermissionDenial> toDenials(
            List<StoredDenial> storedDenials,
            List<ToolExecutionRequest> toolRequests,
            String batchId) {
        List<PermissionDenial> denials = new ArrayList<PermissionDenial>();
        for (StoredDenial stored : storedDenials) {
            ToolExecutionRequest request = toToolRequest(stored.getToolRequest(), batchId);
            if (!toolRequests.contains(request)) {
                throw new IllegalStateException(
                        "denial toolRequest not found in toolRequests: " + batchId);
            }
            denials.add(new PermissionDenial(request, stored.getReason()));
        }
        return denials;
    }

    private static <T> T require(T value, String field) {
        if (value == null) {
            throw new IllegalStateException("interrupt snapshot missing field: " + field);
        }
        return value;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static final class StoredSnapshot {
        private Integer schemaVersion;
        private Map<String, String> contextAttributes;
        private List<StoredToolRequest> toolRequests;
        private List<StoredInterrupt> interrupts;
        private List<StoredDenial> denials;

        public Integer getSchemaVersion() {
            return schemaVersion;
        }

        public void setSchemaVersion(Integer schemaVersion) {
            this.schemaVersion = schemaVersion;
        }

        public Map<String, String> getContextAttributes() {
            return contextAttributes == null
                    ? new LinkedHashMap<String, String>()
                    : contextAttributes;
        }

        public void setContextAttributes(Map<String, String> contextAttributes) {
            this.contextAttributes =
                    contextAttributes == null
                            ? new LinkedHashMap<String, String>()
                            : new LinkedHashMap<String, String>(contextAttributes);
        }

        public List<StoredToolRequest> getToolRequests() {
            return toolRequests;
        }

        public void setToolRequests(List<StoredToolRequest> toolRequests) {
            this.toolRequests = toolRequests;
        }

        public List<StoredInterrupt> getInterrupts() {
            return interrupts;
        }

        public void setInterrupts(List<StoredInterrupt> interrupts) {
            this.interrupts = interrupts;
        }

        public List<StoredDenial> getDenials() {
            return denials;
        }

        public void setDenials(List<StoredDenial> denials) {
            this.denials = denials;
        }
    }

    public static final class StoredToolRequest {
        private String id;
        private String name;
        private String arguments;

        public StoredToolRequest() {}

        public StoredToolRequest(String id, String name, String arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getArguments() {
            return arguments;
        }

        public void setArguments(String arguments) {
            this.arguments = arguments;
        }
    }

    public static final class StoredInterrupt {
        private String interruptId;
        private String type;
        private StoredToolRequest toolRequest;
        private StoredAskUserPayload payload;

        public String getInterruptId() {
            return interruptId;
        }

        public void setInterruptId(String interruptId) {
            this.interruptId = interruptId;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public StoredToolRequest getToolRequest() {
            return toolRequest;
        }

        public void setToolRequest(StoredToolRequest toolRequest) {
            this.toolRequest = toolRequest;
        }

        public StoredAskUserPayload getPayload() {
            return payload;
        }

        public void setPayload(StoredAskUserPayload payload) {
            this.payload = payload;
        }
    }

    public static final class StoredDenial {
        private StoredToolRequest toolRequest;
        private String reason;

        public StoredDenial() {}

        public StoredDenial(StoredToolRequest toolRequest, String reason) {
            this.toolRequest = toolRequest;
            this.reason = reason;
        }

        public StoredToolRequest getToolRequest() {
            return toolRequest;
        }

        public void setToolRequest(StoredToolRequest toolRequest) {
            this.toolRequest = toolRequest;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }

    public static final class StoredAskUserPayload {
        private String toolCallId;
        private String toolName;
        private List<StoredQuestion> questions;
        private String reason;
        private String submitLabel;

        public String getToolCallId() {
            return toolCallId;
        }

        public void setToolCallId(String toolCallId) {
            this.toolCallId = toolCallId;
        }

        public String getToolName() {
            return toolName;
        }

        public void setToolName(String toolName) {
            this.toolName = toolName;
        }

        public List<StoredQuestion> getQuestions() {
            return questions;
        }

        public void setQuestions(List<StoredQuestion> questions) {
            this.questions = questions;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public String getSubmitLabel() {
            return submitLabel;
        }

        public void setSubmitLabel(String submitLabel) {
            this.submitLabel = submitLabel;
        }
    }

    public static final class StoredQuestion {
        private String id;
        private String title;
        private String description;
        private String type;
        private Boolean required;
        private List<StoredOption> options;
        private Integer minSelections;
        private Integer maxSelections;
        private Integer maxLength;
        private String placeholder;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public Boolean getRequired() {
            return required;
        }

        public void setRequired(Boolean required) {
            this.required = required;
        }

        public List<StoredOption> getOptions() {
            return options;
        }

        public void setOptions(List<StoredOption> options) {
            this.options = options;
        }

        public Integer getMinSelections() {
            return minSelections;
        }

        public void setMinSelections(Integer minSelections) {
            this.minSelections = minSelections;
        }

        public Integer getMaxSelections() {
            return maxSelections;
        }

        public void setMaxSelections(Integer maxSelections) {
            this.maxSelections = maxSelections;
        }

        public Integer getMaxLength() {
            return maxLength;
        }

        public void setMaxLength(Integer maxLength) {
            this.maxLength = maxLength;
        }

        public String getPlaceholder() {
            return placeholder;
        }

        public void setPlaceholder(String placeholder) {
            this.placeholder = placeholder;
        }
    }

    public static final class StoredOption {
        private String id;
        private String label;
        private String description;

        public StoredOption() {}

        public StoredOption(String id, String label, String description) {
            this.id = id;
            this.label = label;
            this.description = description;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }
}