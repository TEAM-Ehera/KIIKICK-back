package com.teamehera.kiikick.testsupport;

import com.teamehera.kiikick.friend.domain.FriendPair;
import com.teamehera.kiikick.friend.domain.UserBlock;
import com.teamehera.kiikick.friend.repository.FriendDirectoryRepository;
import com.teamehera.kiikick.friend.repository.FriendPairRepository;
import com.teamehera.kiikick.friend.repository.UserBlockRepository;
import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.notification.domain.Notification;
import com.teamehera.kiikick.notification.repository.NotificationRepository;
import com.teamehera.kiikick.user.repository.UserRepository;
import com.teamehera.kiikick.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;
import static org.assertj.core.api.Assertions.*;

@Transactional
public abstract class QueryAccessContract {
    @Autowired UserRepository users;
    @Autowired UserService service;
    @Autowired FriendDirectoryRepository directory;
    @Autowired FriendPairRepository pairs;
    @Autowired UserBlockRepository blocks;
    @Autowired NotificationRepository notifications;
    @Autowired MatchRepository matches;
    @Autowired JdbcTemplate jdbc;
    private static final LocalDateTime TIME = LocalDateTime.of(9998, 1, 1, 0, 0);

    @Test
    void userCursorPreservesMixedSortAndExcludesDeletedUsers() {
        var ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()).stream()
                .sorted((a, b) -> a.toString().compareTo(b.toString())).toList();
        for (UUID id : ids) {
            users.save(id, "page");
            jdbc.update("UPDATE users SET created_at = ? WHERE id = ?", TIME, uuidToBytes(id));
        }
        users.softDelete(ids.get(1));
        var first = service.list(null, 1);
        assertThat(first.data()).extracting(row -> row.id()).containsExactly(ids.get(0));
        var second = service.list(first.nextCursor(), 1);
        assertThat(second.data()).extracting(row -> row.id()).containsExactly(ids.get(2));
        assertThatThrownBy(() -> service.list("broken", 20)).hasMessage("INVALID_CURSOR");
        assertThatThrownBy(() -> service.list(null, 101)).hasMessage("INVALID_REQUEST");
    }

    @Test
    void relationshipPagesMergeBothDirectionsWithoutGapsAtEqualTimes() {
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 5; i++) { UUID id = UUID.randomUUID(); ids.add(id); onboard(id, "friend"); }
        ids.sort((a, b) -> a.toString().compareTo(b.toString()));
        UUID actor = ids.get(2);
        var expected = new ArrayList<UUID>();
        for (int i : List.of(0, 1, 3, 4)) {
            UUID pair = UUID.randomUUID(); expected.add(pair);
            pairs.save(new FriendPair(pair, i < 2 ? ids.get(i) : actor, i < 2 ? actor : ids.get(i),
                    actor, "ACCEPTED", TIME, TIME));
        }
        expected.sort((a, b) -> b.toString().compareTo(a.toString()));
        var found = new ArrayList<UUID>();
        LocalDateTime before = null; UUID beforeId = null;
        for (int i = 0; i < 4; i++) {
            var page = directory.friends(actor, before, beforeId, 1);
            assertThat(page).hasSize(1);
            var row = page.getFirst(); assertThat(row.acceptedAt()).isEqualTo(TIME); found.add(row.pairId()); before = row.acceptedAt(); beforeId = row.pairId();
        }
        assertThat(found).containsExactlyElementsOf(expected);
        assertThat(directory.friends(actor, before, beforeId, 1)).isEmpty();
        blocks.save(new UserBlock(ids.get(0), actor, TIME));
        assertThat(directory.friends(actor, null, null, 20)).hasSize(3);
    }

    @Test
    void searchFiltersHiddenCandidatesBeforeLimitingAndEscapesWildcards() {
        UUID actor = UUID.randomUUID(); onboard(actor, "actor");
        UUID hidden = UUID.randomUUID(); onboard(hidden, "AA_민수");
        UUID visible = UUID.randomUUID(); onboard(visible, "ZZ_민수");
        UUID unrelated = UUID.randomUUID(); onboard(unrelated, "ZZX민수");
        blocks.save(new UserBlock(hidden, actor, TIME));
        assertThat(directory.search(actor, "_민", null, null, 1))
                .extracting(FriendDirectoryRepository.Row::userId).containsExactly(visible);
        assertThat(directory.search(actor, "_민", "ZZ_민수", visible, 1)).isEmpty();
    }

    @Test
    void typedUnreadCountsFollowCurrentVisibilityAndReadState() {
        UUID actor = UUID.randomUUID(), sender = UUID.randomUUID();
        onboard(actor, "recipient"); onboard(sender, "sender");
        UUID match = UUID.randomUUID();
        matches.save(new Match(match, actor, null, null, null, null, null, "DRAFT", null, null, 1, TIME, TIME));
        UUID pairId = UUID.randomUUID();
        boolean actorLow = actor.toString().compareTo(sender.toString()) < 0;
        pairs.save(new FriendPair(pairId, actorLow ? actor : sender, actorLow ? sender : actor,
                sender, "PENDING", null, TIME));
        UUID matchNotification = notify(actor, "MATCH", match);
        notify(actor, "FRIEND_REQUEST", pairId);
        notify(actor, "MATCH", UUID.randomUUID());
        assertThat(notifications.countVisibleUnread(actor)).isEqualTo(2);
        blocks.save(new UserBlock(sender, actor, TIME));
        assertThat(notifications.countVisibleUnread(actor)).isEqualTo(1);
        notifications.markRead(matchNotification, actor, TIME);
        assertThat(notifications.countVisibleUnread(actor)).isZero();
    }

    private void onboard(UUID id, String name) {
        users.save(id, name);
        users.completeOnboarding(id, name, TIME);
    }

    private UUID notify(UUID recipient, String type, UUID target) {
        UUID id = UUID.randomUUID();
        notifications.save(new Notification(id, recipient, type, "title", "body", type, target,
                id.toString(), null, TIME));
        return id;
    }
}
