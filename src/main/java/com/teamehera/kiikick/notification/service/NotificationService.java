package com.teamehera.kiikick.notification.service;

import com.teamehera.kiikick.match.api.MatchApiResponse;
import com.teamehera.kiikick.match.service.MatchListQuery.InvalidListRequest;
import com.teamehera.kiikick.match.service.MatchListQuery.Pagination;
import com.teamehera.kiikick.match.service.MatchOwnership.ResourceNotFound;
import com.teamehera.kiikick.notification.domain.Device;
import com.teamehera.kiikick.notification.domain.Notification;
import com.teamehera.kiikick.notification.repository.DeviceRepository;
import com.teamehera.kiikick.notification.repository.NotificationRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static com.teamehera.kiikick.match.api.MatchApiResponse.fromUtc;

@Service
public class NotificationService {
    private final NotificationRepository notifications;
    private final DeviceRepository devices;
    private final PushTokenEncryption encryption;

    NotificationService(NotificationRepository notifications, DeviceRepository devices, PushTokenEncryption encryption) {
        this.notifications = notifications;
        this.devices = devices;
        this.encryption = encryption;
    }

    public Page list(UUID actor, boolean unread, String cursor, Integer limit) {
        int size = limit == null ? 20 : limit;
        if (size < 1 || size > 100) {
            throw InvalidListRequest.of("INVALID_REQUEST");
        }
        String scope = "1|notifications|" + actor + "|" + unread + "|";
        var boundary = cursor == null ? null : decode(cursor, scope);
        var rows = notifications.findVisiblePage(actor, unread, boundary == null ? null : boundary.createdAt(),
                boundary == null ? null : boundary.id(), size + 1);
        boolean hasNext = rows.size() > size;
        var page = rows.subList(0, Math.min(size, rows.size()));
        String next = null;
        if (hasNext) {
            var last = page.getLast();
            next = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (scope + last.createdAt() + "|" + last.id()).getBytes(StandardCharsets.UTF_8));
        }
        var meta = MatchApiResponse.Meta.create();
        return new Page(page.stream().map(NotificationView::from).toList(),
                new PageMeta(meta.requestId(), meta.serverTime(), new Pagination(next, hasNext)));
    }

    public Count unread(UUID actor) {
        return new Count(notifications.countVisibleUnread(actor));
    }

    public void requireVisible(UUID actor, UUID id) {
        notifications.findVisible(id, actor).orElseThrow(ResourceNotFound::new);
    }

    public NotificationView read(UUID actor, UUID id) {
        var notification = notifications.findVisible(id, actor).orElseThrow(ResourceNotFound::new);
        if (notification.readAt() == null) {
            // Only the first write records a timestamp, even if another device sends a different key.
            notifications.markRead(id, actor, now());
            notification = notifications.findVisible(id, actor).orElseThrow(ResourceNotFound::new);
        }
        return NotificationView.from(notification);
    }

    public DeviceView register(UUID actor, UUID id, String platform, boolean enabled, boolean tokenPresent, String token) {
        var existing = devices.findById(id).orElse(null);
        if (existing != null) {
            if (!actor.equals(existing.userId())) {
                throw new DeviceOwnershipConflict();
            }
            if (existing.revokedAt() != null || !platform.equals(existing.platform())) {
                throw new InvalidDeviceState();
            }
        }
        String ciphertext = tokenPresent
                ? (token == null ? null : encryption.encrypt(token))
                : (existing == null ? null : existing.pushTokenCiphertext());
        if (enabled && ciphertext == null) {
            throw new MissingPushToken();
        }

        var device = new Device(id, actor, platform, ciphertext, enabled, now(), null);
        if (existing == null) {
            try {
                devices.save(device);
            } catch (DuplicateKeyException exception) {
                // Another user may concurrently claim the same client-generated ID.
                throw new DeviceOwnershipConflict();
            }
        } else if (devices.updateOwned(device) != 1) {
            throw new InvalidDeviceState();
        }
        return new DeviceView(id, platform, enabled, fromUtc(device.lastSeenAt()));
    }

    public void revoke(UUID actor, UUID id) {
        devices.findByIdAndUserId(id, actor).orElseThrow(ResourceNotFound::new);
        devices.revoke(id, actor, now());
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static Boundary decode(String cursor, String scope) {
        if (cursor.length() <= 256) {
            try {
                var decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                if (decoded.startsWith(scope)) {
                    var fields = decoded.substring(scope.length()).split("\\|", -1);
                    if (fields.length == 2) {
                        var time = LocalDateTime.parse(fields[0]);
                        var id = UUID.fromString(fields[1]);
                        if (id.toString().equals(fields[1]) && time.getYear() >= 1000 && time.getYear() <= 9999) {
                            return new Boundary(time, id);
                        }
                    }
                }
            } catch (DateTimeParseException | IllegalArgumentException ignored) {
                // A malformed cursor and one from another recipient or filter share the same error.
            }
        }
        throw InvalidListRequest.of("INVALID_CURSOR");
    }

    private record Boundary(LocalDateTime createdAt, UUID id) {}

    public record Target(String type, UUID id) {}
    public record NotificationView(UUID id, String type, String title, String body, Target target,
                                   OffsetDateTime readAt, OffsetDateTime createdAt) {
        static NotificationView from(Notification notification) {
            return new NotificationView(notification.id(), notification.type(), notification.title(), notification.body(),
                    new Target(notification.targetType(), notification.targetId()), fromUtc(notification.readAt()),
                    fromUtc(notification.createdAt()));
        }
    }
    public record Count(long count) {}
    public record DeviceView(UUID id, String platform, boolean pushEnabled, OffsetDateTime lastSeenAt) {}
    public record Page(List<NotificationView> data, PageMeta meta) {}
    public record PageMeta(UUID requestId, OffsetDateTime serverTime, Pagination pagination) {}
    public static final class DeviceOwnershipConflict extends RuntimeException {}
    public static final class InvalidDeviceState extends RuntimeException {}
    public static final class MissingPushToken extends RuntimeException {}
    public static final class PushTokenUnavailable extends RuntimeException {}

}
