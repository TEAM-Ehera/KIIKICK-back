package com.teamehera.kiikick.repository;

import com.teamehera.kiikick.auth.repository.AdminUserRepository;
import com.teamehera.kiikick.auth.security.AccessTokenService;
import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.repository.MatchCommentReportRepository;
import com.teamehera.kiikick.match.repository.MatchCommentReportRepository.Report;
import com.teamehera.kiikick.match.repository.MatchCommentReportRepository.Resolution;
import com.teamehera.kiikick.match.repository.MatchCommentReportRepository.Status;
import com.teamehera.kiikick.match.repository.MatchCommentRepository;
import com.teamehera.kiikick.match.repository.MatchCommentRepository.Comment;
import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.match.service.AdminCommentReportService;
import com.teamehera.kiikick.match.service.MatchCommentReportService;
import com.teamehera.kiikick.testsupport.LocalMySqlInitializer;
import com.teamehera.kiikick.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.sql.init.mode=never")
@AutoConfigureMockMvc
@ContextConfiguration(initializers = LocalMySqlInitializer.class)
class CommentReportMySqlIntegrationTest {
    private static final String ADMIN_PATH = "/api/v1/admin/comment-reports";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 0, 0, 0, 123456000);

    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired MatchRepository matches;
    @Autowired MatchCommentRepository comments;
    @Autowired MatchCommentReportRepository reports;
    @Autowired AdminUserRepository admins;
    @Autowired MatchCommentReportService submissions;
    @Autowired AdminCommentReportService moderation;
    @Autowired AccessTokenService tokens;
    @Autowired MockMvc mvc;

    private final UUID author = UUID.randomUUID();
    private final UUID reporter = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();
    private final UUID match = UUID.randomUUID();
    private final UUID comment = UUID.randomUUID();

    @BeforeEach
    void seed() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("kiikick_repository_test");
        for (UUID user : new UUID[]{author, reporter, admin}) {
            users.save(user, null);
        }
        matches.save(new Match(match, reporter, null, "경기", null, null, null, "COMPLETED",
                NOW, NOW.plusHours(1), 1, NOW, NOW));
        comments.save(new Comment(comment, match, author, "신고 당시 원문", NOW));
    }

    @AfterEach
    void cleanFixtures() {
        jdbc.update("DELETE FROM match_comment_reports WHERE comment_id = ?", uuidToBytes(comment));
        jdbc.update("DELETE FROM matches WHERE id = ?", uuidToBytes(match));
        jdbc.update("DELETE FROM users WHERE id IN (?, ?, ?)", uuidToBytes(author), uuidToBytes(reporter), uuidToBytes(admin));
    }

    @Test
    void savesSnapshotsAndEnforcesCheckAndUniqueConstraints() {
        var pending = report(reporter);
        reports.save(pending);
        assertThat(reports.findById(pending.id())).contains(pending);
        assertThat(reports.page(Status.PENDING, null, null, 100)).contains(pending);
        assertThatThrownBy(() -> reports.save(report(author)))
                .rootCause()
                .isInstanceOfSatisfying(SQLException.class, cause -> {
                    assertThat(cause.getErrorCode()).isEqualTo(3819);
                    assertThat(cause.getMessage()).contains("chk_match_comment_reports_no_self_report");
                });
        assertThatThrownBy(() -> reports.save(report(reporter)))
                .isInstanceOf(DuplicateKeyException.class)
                .hasStackTraceContaining("uq_match_comment_reports_reporter_comment");
        assertThatThrownBy(() -> submissions.submit(reporter, match, comment, "SPAM", null))
                .isInstanceOf(MatchCommentReportService.CommentAlreadyReported.class);
        assertThat(reports.markProcessed(pending.id(), admin, NOW, Resolution.NO_ACTION)).isEqualTo(1);
        assertThat(reports.markProcessed(pending.id(), reporter, NOW, Resolution.DELETED)).isZero();
        var processed = reports.findById(pending.id()).orElseThrow();
        assertThat(processed.processedAt()).isEqualTo(NOW);
        assertThat(processed.processedBy()).isEqualTo(admin);
        assertThat(processed.resolution()).isEqualTo(Resolution.NO_ACTION);
        assertThat(reports.page(Status.PROCESSED, null, null, 100)).contains(processed);
    }

    @Test
    void verifiesAdminAccessAndRevocationWithTheSameToken() throws Exception {
        String authorization = "Bearer " + tokens.issue(admin, UUID.randomUUID()).token();
        mvc.perform(get(ADMIN_PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(ADMIN_PATH).header("Authorization", authorization)).andExpect(status().isForbidden());
        jdbc.update("INSERT INTO admin_users (user_id) VALUES (?)", uuidToBytes(admin));
        assertThat(admins.isActiveAdmin(admin)).isTrue();
        mvc.perform(get(ADMIN_PATH).header("Authorization", authorization)).andExpect(status().isOk());
        jdbc.update("DELETE FROM admin_users WHERE user_id = ?", uuidToBytes(admin));
        mvc.perform(get(ADMIN_PATH).header("Authorization", authorization)).andExpect(status().isForbidden());
        jdbc.update("INSERT INTO admin_users (user_id) VALUES (?)", uuidToBytes(admin));
        jdbc.update("UPDATE users SET deleted_at = ? WHERE id = ?", NOW, uuidToBytes(admin));
        assertThat(admins.isActiveAdmin(admin)).isFalse();
    }

    @Test
    void hidesAndDeletesCommentsWhilePreservingReports() {
        var hidden = report(reporter);
        var remaining = report(admin);
        reports.save(hidden);
        reports.save(remaining);
        assertThat(comments.page(match, reporter, null, null, 10)).hasSize(1);
        assertThat(moderation.process(hidden.id(), admin, Resolution.HIDDEN).resolution()).isEqualTo(Resolution.HIDDEN);
        assertThat(comments.page(match, reporter, null, null, 10)).isEmpty();
        assertThat(reports.findById(remaining.id())).contains(remaining);
        assertThat(moderation.process(remaining.id(), admin, Resolution.DELETED).resolution()).isEqualTo(Resolution.DELETED);
        assertThat(comments.findByIdForUpdate(comment)).isEmpty();
        assertThat(reports.findById(hidden.id()).orElseThrow().commentContent()).isEqualTo(hidden.commentContent());
        assertThat(reports.findById(remaining.id()).orElseThrow().commentContent()).isEqualTo(remaining.commentContent());
    }

    @Test
    void concurrentProcessingHasOneWinnerAndFailedHideRollsBack() throws Exception {
        var pending = report(reporter);
        var rollback = report(admin);
        reports.save(pending);
        reports.save(rollback);
        var start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> processAtBarrier(start, pending.id(), admin));
            var second = executor.submit(() -> processAtBarrier(start, pending.id(), reporter));
            assertThat(new Boolean[]{first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)})
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(reports.findById(pending.id()).orElseThrow().resolution()).isEqualTo(Resolution.DELETED);
        assertThatThrownBy(() -> moderation.process(rollback.id(), admin, Resolution.HIDDEN))
                .isInstanceOf(AdminCommentReportService.CommentAlreadyDeleted.class);
        assertThat(reports.findById(rollback.id())).contains(rollback);
    }

    private boolean processAtBarrier(CyclicBarrier start, UUID reportId, UUID actor) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        try {
            moderation.process(reportId, actor, Resolution.DELETED);
            return true;
        } catch (AdminCommentReportService.ReportAlreadyProcessed expected) {
            return false;
        }
    }

    private Report report(UUID reporterId) {
        return new Report(UUID.randomUUID(), reporterId, author, comment, "신고 당시 원문",
                "SPAM", null, NOW, null, null, null);
    }
}
