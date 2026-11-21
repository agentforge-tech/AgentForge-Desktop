package cloud.changlu.agentforge.desktop.modules.chat.controller;

import cloud.changlu.agentforge.desktop.modules.chat.domain.AgentInterruptResumeReq;
import cloud.changlu.agentforge.desktop.modules.chat.domain.AgentInterruptResumeVo;
import cloud.changlu.agentforge.desktop.modules.chat.domain.ChatStopVo;
import cloud.changlu.agentforge.desktop.modules.chat.domain.ChatStreamRequest;
import cloud.changlu.agentforge.desktop.modules.chat.domain.ChatTaskSubmitVo;
import cloud.changlu.agentforge.desktop.modules.chat.service.ChatTaskService;
import cloud.changlu.agentforge.desktop.modules.chat.service.ReconnectableChatStreamService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 二段式聊天接口：submit 启动任务，stream 只消费事件，stop 独立取消。 */
@RestController
@RequestMapping("/api/chat/tasks")
public class ChatTaskController {

    private final ChatTaskService taskService;
    private final ReconnectableChatStreamService streamService;

    public ChatTaskController(
            ChatTaskService taskService,
            ReconnectableChatStreamService streamService) {
        this.taskService = taskService;
        this.streamService = streamService;
    }

    @PostMapping("/submit")
    public ChatTaskSubmitVo submit(@RequestBody ChatStreamRequest request) {
        return taskService.submit(request);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam Long messageId,
            @RequestParam(value = "afterSeq", defaultValue = "0") Long afterSeq,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        return streamService.subscribe(messageId, resolveCursor(afterSeq, lastEventId));
    }

    @PostMapping("/stop")
    public ChatStopVo stop(@RequestParam Long messageId) {
        return taskService.stop(messageId);
    }

    @PostMapping("/interrupts/resume")
    public AgentInterruptResumeVo resume(@RequestBody AgentInterruptResumeReq request) {
        return taskService.resumeInterrupt(request);
    }

    private static long resolveCursor(Long afterSeq, String lastEventId) {
        long cursor = afterSeq == null ? 0L : Math.max(0L, afterSeq);
        if (cursor != 0L || lastEventId == null || lastEventId.trim().isEmpty()) return cursor;
        try {
            return Math.max(0L, Long.parseLong(lastEventId.trim()));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
