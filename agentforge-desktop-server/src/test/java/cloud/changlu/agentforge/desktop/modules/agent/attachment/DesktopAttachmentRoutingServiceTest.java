package cloud.changlu.agentforge.desktop.modules.agent.attachment;

import cloud.changlu.agentforge.agent.domain.AgentAttachmentType;
import cloud.changlu.agentforge.agent.domain.AttachmentPayloadType;
import cloud.changlu.agentforge.model.registry.enums.LlmEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DesktopAttachmentRoutingServiceTest {

    private final DesktopAttachmentRoutingService service =
            new DesktopAttachmentRoutingService();

    @Test
    void localImageRoutesToInlineForVisionModel() {
        assertEquals(
                AttachmentPayloadType.INLINE,
                service.route(
                        AgentAttachmentType.IMAGE,
                        "/Users/me/shot.png",
                        1024L,
                        true,
                        LlmEnum.OPENAI.getCode()));
    }

    @Test
    void textModelDegradesImageToText() {
        assertEquals(
                AttachmentPayloadType.TEXT,
                service.route(
                        AgentAttachmentType.IMAGE,
                        "/Users/me/shot.png",
                        1024L,
                        false,
                        LlmEnum.OPENAI.getCode()));
    }

    @Test
    void publicUrlIsUsedWhenModelCanAccessIt() {
        assertEquals(
                AttachmentPayloadType.URL,
                service.route(
                        AgentAttachmentType.IMAGE,
                        "https://example.com/a.png",
                        1024L,
                        true,
                        LlmEnum.OPENAI.getCode()));
    }

    @Test
    void anthropicAlwaysRequiresInline() {
        assertEquals(
                AttachmentPayloadType.INLINE,
                service.route(
                        AgentAttachmentType.IMAGE,
                        "https://example.com/a.png",
                        1024L,
                        true,
                        LlmEnum.ANTHROPIC.getCode()));
    }

    @Test
    void oversizedInlineImageFallsBackToText() {
        assertEquals(
                AttachmentPayloadType.TEXT,
                service.route(
                        AgentAttachmentType.IMAGE,
                        "/Users/me/large.png",
                        5L * 1024 * 1024,
                        true,
                        LlmEnum.OPENAI.getCode()));
    }

    @Test
    void nonImageNeverUsesImageChannel() {
        assertEquals(
                AttachmentPayloadType.TEXT,
                service.route(
                        AgentAttachmentType.TEXT,
                        "/Users/me/notes.md",
                        1024L,
                        true,
                        LlmEnum.OPENAI.getCode()));
    }
}
