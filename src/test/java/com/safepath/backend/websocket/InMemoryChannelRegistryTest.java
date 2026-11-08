
package com.safepath.backend.websocket;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InMemoryChannelRegistryTest {

    @Test
    void 채널_자동_생성_테스트() {
        InMemoryChannelRegistry registry =
                new InMemoryChannelRegistry();

        String channelId = registry.create();

        assertNotNull(channelId);
        assertDoesNotThrow(() -> UUID.fromString(channelId));
        assertTrue(registry.exists(channelId));

        System.out.println("생성된 채널: " + channelId);
    }

    @Test
    void 채널_구독자_등록_테스트() {
        InMemoryChannelRegistry registry =
                new InMemoryChannelRegistry();

        String channelId = registry.create();

        assertDoesNotThrow(() ->
                registry.join(channelId, "session-1")
        );
    }

    @Test
    void 존재하지_않는_채널_접속_테스트() {
        InMemoryChannelRegistry registry =
                new InMemoryChannelRegistry();

        assertThrows(
                IllegalArgumentException.class,
                () -> registry.join("invalid-channel", "session-1")
        );
    }

    @Test
    void 마지막_접속자_종료_테스트() {
        InMemoryChannelRegistry registry =
                new InMemoryChannelRegistry();

        String channelId = registry.create();

        registry.join(channelId, "session-1");
        registry.leaveAll("session-1");

        // 현재 구현은 마지막 접속자가 나가면 채널 삭제
        assertFalse(registry.exists(channelId));
    }
}
