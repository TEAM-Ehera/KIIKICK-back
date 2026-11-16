package com.teamehera.kiikick.match.repository;

import com.teamehera.kiikick.auth.repository.AdminUserRepository;
import com.teamehera.kiikick.match.repository.MatchCommentRepository.Comment;
import com.teamehera.kiikick.match.repository.MatchCommentReportRepository.Report;
import com.teamehera.kiikick.match.repository.MatchCommentReportRepository.Resolution;
import com.teamehera.kiikick.match.repository.MatchCommentReportRepository.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchCommentRepositoryTest {
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 11, 16, 0, 0, 0, 123_456_000);
    private static final LocalDateTime DELETED_AT = CREATED_AT.plusSeconds(1);
    private final UUID match = UUID.randomUUID();
    private final UUID otherMatch = UUID.randomUUID();
    private final UUID author = UUID.randomUUID();
    private final UUID reader = UUID.randomUUID();
    private final UUID replyAuthor = UUID.randomUUID();
    private SingleConnectionDataSource database;
    private JdbcTemplate jdbc;
    private MatchCommentRepository comments;

    @BeforeEach
    void setUp() {
        // Keep the schema session open for H2's CHECK constraints on the report table.
        database = new SingleConnectionDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL", "sa", "", true);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(database);
        jdbc = new JdbcTemplate(database);
        comments = new MatchCommentRepository(jdbc);
        for (UUID user : List.of(author, reader, replyAuthor)) {
            jdbc.update("INSERT INTO users (id, created_at, updated_at) VALUES (?, ?, ?)",
                    uuidToBytes(user), CREATED_AT, CREATED_AT);
        }
        for (UUID matchId : List.of(match, otherMatch)) {
            jdbc.update("""
                    INSERT INTO matches (id, owner_id, status, version, created_at, updated_at)
                    VALUES (?, ?, 'DRAFT', 1, ?, ?)
                    """, uuidToBytes(matchId), uuidToBytes(author), CREATED_AT, CREATED_AT);
        }
    }

    @AfterEach
    void shutDownDatabase() {
        if (database != null) {
            database.destroy();
        }
    }

    @Test
    void pagesRootsAndRepliesSeparatelyInCursorOrder() {
        var root = new Comment(new UUID(0, 1), match, author, "root", CREATED_AT);
        var otherRoot = new Comment(new UUID(0, 2), match, author, "other root", CREATED_AT);
        var older = new Comment(new UUID(0, 3), match, replyAuthor, "older", CREATED_AT.minusSeconds(1), root.id(), null);
        var first = new Comment(new UUID(0, 4), match, replyAuthor, "first", CREATED_AT, root.id(), null);
        var second = new Comment(new UUID(0, 5), match, replyAuthor, "second", CREATED_AT, root.id(), null);
        List.of(root, otherRoot, older, first, second).forEach(comments::save);

        assertEquals(List.of(otherRoot, root), comments.pageTopLevel(match, reader, null, null, 10));
        assertEquals(List.of(second, first), comments.pageReplies(match, root.id(), reader, null, null, 2));
        assertEquals(List.of(older), comments.pageReplies(match, root.id(), reader, first.createdAt(), first.id(), 2));
        assertTrue(comments.pageReplies(match, otherRoot.id(), reader, null, null, 10).isEmpty());
    }

    @Test
    void masksDeletedRootWhileRetainingRepliesAndLegacyListBehavior() {
        var root = new Comment(UUID.randomUUID(), match, author, "original", CREATED_AT);
        var reply = new Comment(UUID.randomUUID(), match, replyAuthor, "reply", CREATED_AT, root.id(), null);
        comments.save(root);
        comments.save(reply);

        assertEquals(0, comments.softDelete(match, root.id(), reader, DELETED_AT));
        assertEquals(1, comments.softDelete(match, root.id(), author, DELETED_AT));
        assertEquals(0, comments.delete(match, root.id(), author));
        var deleted = comments.pageTopLevel(match, reader, null, null, 10).getFirst();
        assertEquals(root.id(), deleted.id());
        assertEquals(DELETED_AT, deleted.deletedAt());
        assertNull(deleted.content());
        assertTrue(comments.page(match, reader, null, null, 10).isEmpty());
        assertEquals(List.of(reply), comments.pageReplies(match, root.id(), reader, null, null, 10));

        assertEquals(1, comments.delete(match, reply.id(), replyAuthor));
        assertTrue(comments.pageReplies(match, root.id(), reader, null, null, 10).isEmpty());
        assertEquals(List.of(deleted), comments.pageTopLevel(match, reader, null, null, 10));
    }

    @Test
    void locksOnlyVisibleActiveTopLevelCommentsInTheSameMatch() {
        var root = new Comment(UUID.randomUUID(), match, author, "root", CREATED_AT);
        var blockedRoot = new Comment(UUID.randomUUID(), match, replyAuthor, "blocked", CREATED_AT);
        var deleted = new Comment(UUID.randomUUID(), match, author, "deleted", CREATED_AT, null, DELETED_AT);
        var reply = new Comment(UUID.randomUUID(), match, reader, "reply", CREATED_AT, root.id(), null);
        List.of(root, blockedRoot, deleted, reply).forEach(comments::save);
        block(replyAuthor, reader);

        new TransactionTemplate(new DataSourceTransactionManager(database)).executeWithoutResult(status -> {
            assertEquals(root, comments.findReplyParentForUpdate(match, root.id(), reader).orElseThrow());
            assertTrue(comments.findReplyParentForUpdate(otherMatch, root.id(), reader).isEmpty());
            assertTrue(comments.findReplyParentForUpdate(match, reply.id(), reader).isEmpty());
            assertTrue(comments.findReplyParentForUpdate(match, deleted.id(), reader).isEmpty());
            assertTrue(comments.findReplyParentForUpdate(match, blockedRoot.id(), reader).isEmpty());
        });
    }

    @Test
    void filtersBlocksOnBothParentAndReplyInEitherDirection() {
        var root = new Comment(UUID.randomUUID(), match, author, "root", CREATED_AT);
        var reply = new Comment(UUID.randomUUID(), match, replyAuthor, "reply", CREATED_AT, root.id(), null);
        comments.save(root);
        comments.save(reply);

        block(reader, replyAuthor);
        assertEquals(List.of(root), comments.pageTopLevel(match, reader, null, null, 10));
        assertTrue(comments.pageReplies(match, root.id(), reader, null, null, 10).isEmpty());
        assertTrue(comments.findReportableForUpdate(match, reply.id(), reader).isEmpty());

        jdbc.update("DELETE FROM user_blocks WHERE blocker_id = ? AND blocked_id = ?",
                uuidToBytes(reader), uuidToBytes(replyAuthor));
        block(author, reader);
        assertTrue(comments.pageTopLevel(match, reader, null, null, 10).isEmpty());
        assertTrue(comments.pageReplies(match, root.id(), reader, null, null, 10).isEmpty());
        assertTrue(comments.findReportableForUpdate(match, reply.id(), reader).isEmpty());
    }

    @Test
    void hidingAParentExcludesItsThreadWithoutDeletingRows() {
        var root = new Comment(UUID.randomUUID(), match, author, "root", CREATED_AT);
        var reply = new Comment(UUID.randomUUID(), match, replyAuthor, "reply", CREATED_AT, root.id(), null);
        comments.save(root);
        comments.save(reply);

        new TransactionTemplate(new DataSourceTransactionManager(database)).executeWithoutResult(status -> {
            assertEquals(root, comments.findReportableForUpdate(match, root.id(), reader).orElseThrow());
            assertEquals(reply, comments.findReportableForUpdate(match, reply.id(), reader).orElseThrow());
            assertTrue(comments.findReportableForUpdate(otherMatch, reply.id(), reader).isEmpty());
            var hiddenAt = CREATED_AT.plusSeconds(2);
            assertEquals(1, comments.hide(root.id(), hiddenAt));
            assertEquals(0, comments.hide(root.id(), hiddenAt.plusSeconds(1)));
            assertTrue(comments.pageTopLevel(match, reader, null, null, 10).isEmpty());
            assertTrue(comments.pageReplies(match, root.id(), reader, null, null, 10).isEmpty());
            assertTrue(comments.findReplyParentForUpdate(match, root.id(), reader).isEmpty());
            assertTrue(comments.findReportableForUpdate(match, root.id(), reader).isEmpty());
            assertTrue(comments.findReportableForUpdate(match, reply.id(), reader).isEmpty());
            assertEquals(1, comments.softDeleteById(root.id(), DELETED_AT));
            var removed = comments.findByIdForUpdate(root.id()).orElseThrow();
            assertEquals(hiddenAt, removed.hiddenAt());
            assertEquals(DELETED_AT, removed.deletedAt());
            assertEquals(reply, comments.findByIdForUpdate(reply.id()).orElseThrow());
            assertTrue(comments.pageReplies(match, root.id(), reader, null, null, 10).isEmpty());
        });
    }

    @Test
    void adminSoftDeletionKeepsRepliesReportableAndPreservesReportSnapshots() {
        var root = new Comment(UUID.randomUUID(), match, author, "root original", CREATED_AT);
        var reply = new Comment(UUID.randomUUID(), match, replyAuthor, "reply original", CREATED_AT, root.id(), null);
        comments.save(root);
        comments.save(reply);
        var reports = new MatchCommentReportRepository(jdbc);

        new TransactionTemplate(new DataSourceTransactionManager(database)).executeWithoutResult(status -> {
            var rootReport = new Report(UUID.randomUUID(), reader, author, root.id(), root.content(),
                    "ABUSE", null, CREATED_AT, null, null, null);
            reports.save(rootReport);
            assertEquals(1, comments.softDeleteById(root.id(), DELETED_AT));
            assertEquals(0, comments.softDeleteById(root.id(), DELETED_AT.plusSeconds(1)));
            assertEquals(0, comments.hide(root.id(), DELETED_AT.plusSeconds(1)));
            assertTrue(comments.findReplyParentForUpdate(match, root.id(), reader).isEmpty());
            assertTrue(comments.findReportableForUpdate(match, root.id(), reader).isEmpty());
            assertEquals(List.of(reply), comments.pageReplies(match, root.id(), reader, null, null, 10));
            var reportable = comments.findReportableForUpdate(match, reply.id(), reader).orElseThrow();
            var replyReport = new Report(UUID.randomUUID(), reader, reportable.authorId(), reportable.id(),
                    reportable.content(), "SPAM", null, CREATED_AT, null, null, null);
            reports.save(replyReport);
            assertThrows(DuplicateKeyException.class, () -> reports.save(new Report(UUID.randomUUID(),
                    reader, reportable.authorId(), reportable.id(), reportable.content(),
                    "SPAM", null, CREATED_AT, null, null, null)));
            assertEquals(1, reports.markProcessed(rootReport.id(), reader, DELETED_AT, Resolution.DELETED));
            assertEquals(0, reports.markProcessed(rootReport.id(), reader, DELETED_AT, Resolution.HIDDEN));
            assertEquals(List.of(replyReport), reports.page(Status.PENDING, null, null, 10));
            var processed = reports.page(Status.PROCESSED, null, null, 10).getFirst();
            assertEquals(root.content(), processed.commentContent());
            assertEquals(DELETED_AT, processed.processedAt());

            assertEquals(1, comments.softDeleteById(reply.id(), DELETED_AT));
            assertTrue(comments.findReportableForUpdate(match, reply.id(), reader).isEmpty());
            assertEquals(replyReport, reports.findById(replyReport.id()).orElseThrow());
        });
    }

    @Test
    void hidingAReplyPreservesItsParent() {
        var root = new Comment(UUID.randomUUID(), match, author, "root", CREATED_AT);
        var reply = new Comment(UUID.randomUUID(), match, replyAuthor, "reply", CREATED_AT, root.id(), null);
        comments.save(root);
        comments.save(reply);

        assertEquals(1, comments.hide(reply.id(), DELETED_AT));
        assertEquals(List.of(root), comments.pageTopLevel(match, reader, null, null, 10));
        assertTrue(comments.pageReplies(match, root.id(), reader, null, null, 10).isEmpty());
        assertTrue(comments.findReportableForUpdate(match, reply.id(), reader).isEmpty());
    }

    @Test
    void requiresAnActiveUserForAdminAccess() {
        var admins = new AdminUserRepository(jdbc);
        assertFalse(admins.isActiveAdmin(reader));
        jdbc.update("INSERT INTO admin_users (user_id) VALUES (?)", uuidToBytes(reader));
        assertTrue(admins.isActiveAdmin(reader));
        jdbc.update("UPDATE users SET deleted_at = ? WHERE id = ?", DELETED_AT, uuidToBytes(reader));
        assertFalse(admins.isActiveAdmin(reader));
    }

    private void block(UUID blocker, UUID blocked) {
        jdbc.update("INSERT INTO user_blocks (blocker_id, blocked_id, created_at) VALUES (?, ?, ?)",
                uuidToBytes(blocker), uuidToBytes(blocked), CREATED_AT);
    }
}
