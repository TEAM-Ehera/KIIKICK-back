package com.teamehera.kiikick.match.api;

import com.teamehera.kiikick.friend.domain.FriendPair;
import com.teamehera.kiikick.friend.domain.UserBlock;
import com.teamehera.kiikick.friend.repository.FriendPairRepository;
import com.teamehera.kiikick.friend.repository.UserBlockRepository;
import com.teamehera.kiikick.notification.domain.Notification;
import com.teamehera.kiikick.notification.repository.DeviceRepository;
import com.teamehera.kiikick.notification.repository.NotificationRepository;
import com.teamehera.kiikick.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class NotificationControllerTest extends AuthenticatedApiTest {
    private static final String NOTIFICATIONS = "/api/v1/notifications";
    private final UUID actor = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired NotificationRepository notifications;
    @Autowired DeviceRepository devices;
    @Autowired FriendPairRepository friends;
    @Autowired UserBlockRepository blocks;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:h2:mem:notification_controller;MODE=MySQL");
        properties.add("kiikick.notification.push-token-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @BeforeEach
    void createUsers() {
        users.save(actor, null);
        users.save(other, null);
    }

    @Test
    void listCountAndReadExcludeForeignTargetsAndKeepTheFirstReadTime() throws Exception {
        UUID match = createMatch(actor);
        UUID foreignMatch = createMatch(other);
        var now = LocalDateTime.of(2026, 11, 4, 0, 0);
        UUID older = notify(actor, match, now);
        UUID newer = notify(actor, match, now.plusSeconds(1));
        UUID hidden = notify(actor, foreignMatch, now.plusSeconds(2));
        UUID otherNotification = notify(other, foreignMatch, now.plusSeconds(3));

        var page = mvc.perform(get(NOTIFICATIONS).header("Authorization", bearer(actor)).param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(newer.toString()))
                .andExpect(jsonPath("$.data[0].target.id").value(match.toString()))
                .andExpect(header().exists("X-Request-Id")).andReturn().getResponse();
        String cursor = json.readTree(page.getContentAsString()).at("/meta/pagination/nextCursor").stringValue();
        mvc.perform(get(NOTIFICATIONS).header("Authorization", bearer(actor)).param("cursor", cursor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(older.toString()));
        mvc.perform(get(NOTIFICATIONS).header("Authorization", bearer(actor)).param("cursor", cursor).param("unreadOnly", "true"))
                .andExpect(status().isBadRequest());
        assertCount(2);
        for (UUID denied : new UUID[]{hidden, otherNotification}) {
            mvc.perform(write(put(NOTIFICATIONS + "/" + denied + "/read"), actor)).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/v1/matches/" + foreignMatch).header("Authorization", bearer(actor))).andExpect(status().isNotFound());

        String read = NOTIFICATIONS + "/" + newer + "/read";
        var first = mvc.perform(write(put(read), actor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readAt").isNotEmpty()).andReturn().getResponse();
        String firstReadAt = json.readTree(first.getContentAsString()).at("/data/readAt").stringValue();
        mvc.perform(write(put(read), actor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readAt").value(firstReadAt));
        assertCount(1);
        mvc.perform(get(NOTIFICATIONS).header("Authorization", bearer(actor)).param("unreadOnly", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(older.toString()))
                .andExpect(jsonPath("$.data[0].readAt").value(nullValue()));
    }

    @Test
    void blockedFriendRequestNotificationCannotBeReplayed() throws Exception {
        var time = LocalDateTime.of(2026, 11, 4, 0, 0);
        UUID request = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        var ordered = FriendPairRepository.normalize(actor, other);
        friends.save(new FriendPair(request, ordered[0], ordered[1], other, "PENDING", null, time));
        notifications.save(new Notification(id, actor, "FRIEND_REQUEST_RECEIVED", "Request", null,
                "FRIEND_REQUEST", request, id.toString(), null, time));
        assertCount(1);
        UUID key = UUID.randomUUID();
        String read = NOTIFICATIONS + "/" + id + "/read";
        mvc.perform(put(read).header("Authorization", bearer(actor)).header("Idempotency-Key", key)).andExpect(status().isOk());

        blocks.save(new UserBlock(other, actor, time));
        mvc.perform(get(NOTIFICATIONS).header("Authorization", bearer(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(put(read).header("Authorization", bearer(actor)).header("Idempotency-Key", key))
                .andExpect(status().isNotFound());
    }

    @Test
    void deviceTokenIsEncryptedAndRevocationPreservesOwnership() throws Exception {
        UUID id = UUID.randomUUID();
        String path = "/api/v1/devices/" + id;
        var withoutToken = Map.of("platform", "IOS", "pushEnabled", true);
        mvc.perform(register(path, actor, UUID.randomUUID(), withoutToken))
                .andExpect(status().isUnprocessableContent());
        String raw = "device-token-fixture";
        UUID key = UUID.randomUUID();
        var body = Map.of("platform", "IOS", "pushEnabled", true, "pushToken", raw);
        var saved = mvc.perform(register(path, actor, key, body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pushEnabled").value(true)).andExpect(jsonPath("$.data.pushToken").doesNotExist())
                .andReturn().getResponse();
        assertFalse(saved.getContentAsString().contains(raw));
        String ciphertext = devices.findByIdAndUserId(id, actor).orElseThrow().pushTokenCiphertext();
        assertNotNull(ciphertext);
        assertFalse(ciphertext.contains(raw));
        mvc.perform(register(path, actor, key, body))
                .andExpect(content().string(saved.getContentAsString()));
        mvc.perform(register(path, actor, UUID.randomUUID(), withoutToken)).andExpect(status().isOk());
        assertEquals(ciphertext, devices.findByIdAndUserId(id, actor).orElseThrow().pushTokenCiphertext());
        mvc.perform(register(path, actor, UUID.randomUUID(), Map.of("platform", "WATCHOS", "pushEnabled", false)))
                .andExpect(status().isConflict());
        mvc.perform(register(path, other, UUID.randomUUID(), Map.of("platform", "IOS", "pushEnabled", false)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DEVICE_OWNERSHIP_CONFLICT"));
        mvc.perform(write(delete(path), other)).andExpect(status().isNotFound());

        UUID revokeKey = UUID.randomUUID();
        for (int i = 0; i < 2; i++) {
            mvc.perform(delete(path).header("Authorization", bearer(actor)).header("Idempotency-Key", revokeKey))
                    .andExpect(status().isNoContent()).andExpect(header().exists("X-Request-Id"));
        }
        var revoked = devices.findByIdAndUserId(id, actor).orElseThrow();
        assertNotNull(revoked.revokedAt());
        assertNull(revoked.pushTokenCiphertext());
        assertFalse(revoked.pushEnabled());
        mvc.perform(register(path, actor, UUID.randomUUID(), body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    private void assertCount(int count) throws Exception {
        mvc.perform(get(NOTIFICATIONS + "/unread-count").header("Authorization", bearer(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.count").value(count));
    }

    private UUID notify(UUID recipient, UUID match, LocalDateTime createdAt) {
        UUID id = UUID.randomUUID();
        notifications.save(new Notification(id, recipient, "TEST_MATCH", "Title", null,
                "MATCH", match, id.toString(), null, createdAt));
        return id;
    }

    private UUID createMatch(UUID owner) throws Exception {
        String location = mvc.perform(write(post("/api/v1/matches"), owner).contentType("application/json").content("{}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location");
        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    private MockHttpServletRequestBuilder register(String path, UUID user, UUID key, Map<String, ?> body) {
        return put(path).header("Authorization", bearer(user)).header("Idempotency-Key", key)
                .contentType("application/json").content(json.writeValueAsString(body));
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, UUID user) {
        return request.header("Authorization", bearer(user)).header("Idempotency-Key", UUID.randomUUID());
    }
}
