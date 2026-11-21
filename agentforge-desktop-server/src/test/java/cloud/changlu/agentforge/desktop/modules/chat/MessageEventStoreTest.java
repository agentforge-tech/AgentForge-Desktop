package cloud.changlu.agentforge.desktop.modules.chat;

import cloud.changlu.agentforge.desktop.modules.chat.event.sink.StreamEventSink;
import cloud.changlu.agentforge.desktop.modules.chat.event.store.MessageEventFactory;
import cloud.changlu.agentforge.desktop.modules.chat.event.store.StoredMessageEvent;
import cloud.changlu.agentforge.service.protocol.processor.AiResponseProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "agentforge.desktop.root-dir=target/test-message-event-store")
class MessageEventStoreTest {

    @Autowired private MessageEventFactory eventFactory;

    @Test
    void bufferedSinkPersistsOrderedReplayableEvents() {
        long messageId = 9001L;
        eventFactory.deleteByMessageId(messageId);
        StreamEventSink sink = eventFactory.createSink(messageId);
        sink.publish(AiResponseProcessor.respText("a", false));
        sink.publish(AiResponseProcessor.respText("b", false));
        sink.publish(AiResponseProcessor.respEnd(true));
        sink.close();

        List<StoredMessageEvent> events = eventFactory.readAll(messageId);
        assertEquals(2, events.size());
        assertEquals(Long.valueOf(1L), events.get(0).getEventSeq());
        assertEquals(Long.valueOf(2L), events.get(1).getEventSeq());
        assertTrue(events.get(0).getPayload().contains("ab"));
        assertEquals(1, eventFactory.readAfter(messageId, 1L, 10).size());
        assertEquals("resp_end", eventFactory.readAfter(messageId, 1L, 10).get(0).getEventType());
    }
}
