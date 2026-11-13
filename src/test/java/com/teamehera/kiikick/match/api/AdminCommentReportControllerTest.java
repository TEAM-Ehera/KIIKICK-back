package com.teamehera.kiikick.match.api;

import com.teamehera.kiikick.auth.security.AccessTokenService;
import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.repository.MatchCommentReportRepository;
import com.teamehera.kiikick.match.repository.MatchCommentReportRepository.Report;
import com.teamehera.kiikick.match.repository.MatchCommentRepository;
import com.teamehera.kiikick.match.repository.MatchCommentRepository.Comment;
import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:admin_comment_reports;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class AdminCommentReportControllerTest {
    private static final String PATH = "/api/v1/admin/comment-reports";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 0, 0);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessTokenService tokens;
    @Autowired UserRepository users;
    @Autowired MatchRepository matches;
    @Autowired MatchCommentRepository comments;
    @Autowired MatchCommentReportRepository reports;

    private final UUID author = UUID.randomUUID();
    private final UUID reporter = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();
    private final UUID secondAdmin = UUID.randomUUID();
    private final UUID match = UUID.randomUUID();
    private final UUID comment = UUID.randomUUID();

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM match_comment_reports");
        for (UUID id : new UUID[]{author, reporter, admin, secondAdmin}) {
            users.save(id, null);
        }
        jdbc.update("INSERT INTO admin_users (user_id) VALUES (?), (?)", uuidToBytes(admin), uuidToBytes(secondAdmin));
        matches.save(new Match(match, author, null, "경기", null, null, null, "COMPLETED",
                NOW, NOW.plusHours(1), 1, NOW, NOW));
        comments.save(new Comment(comment, match, author, "원문", NOW));
    }

    @Test
    void administratorCanReadSnapshotsAndUseFilteredCursorPagination() throws Exception {
        var first = seedReport(reporter, NOW);
        var second = seedReport(secondAdmin, NOW.plusSeconds(1));
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(auth(get(PATH + "/{id}", first), reporter)).andExpect(status().isForbidden());
        mvc.perform(auth(patch(PATH + "/{id}", first), reporter).contentType("application/json")
                        .content("{\"resolution\":\"DELETED\"}")).andExpect(status().isForbidden());

        String body = mvc.perform(auth(get(PATH).param("limit", "1"), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(second.toString()))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data[0].reportedAt").value(endsWith("+09:00")))
                .andExpect(jsonPath("$.meta.pagination.hasNext").value(true))
                .andReturn().getResponse().getContentAsString();
        String cursor = json.readTree(body).at("/meta/pagination/nextCursor").stringValue();
        mvc.perform(auth(get(PATH).param("cursor", cursor), admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(first.toString()));
        mvc.perform(auth(get(PATH).param("status", "ALL").param("cursor", cursor), admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_CURSOR"));
        mvc.perform(auth(get(PATH).param("status", "PROCESSED"), admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(auth(get(PATH + "/{id}", first), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commentAuthorId").value(author.toString()))
                .andExpect(jsonPath("$.data.reporterId").value(reporter.toString()))
                .andExpect(jsonPath("$.data.commentContent").value("원문"))
                .andExpect(jsonPath("$.data.detail").value("상세"));
        mvc.perform(auth(get(PATH + "/{id}", UUID.randomUUID()), admin)).andExpect(status().isNotFound());
        mvc.perform(auth(patch(PATH + "/{id}", first), admin).contentType("application/json")
                        .content("{\"resolution\":\"UNKNOWN\"}"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void hiddenAndDeletedActionsKeepReportsIndependent() throws Exception {
        var first = seedReport(reporter, NOW);
        var second = seedReport(secondAdmin, NOW);
        var third = seedReport(admin, NOW);
        process(first, admin, "HIDDEN").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processedBy").value(admin.toString()))
                .andExpect(jsonPath("$.data.resolution").value("HIDDEN"));
        var hiddenAt = comments.findByIdForUpdate(comment).orElseThrow().hiddenAt();
        assertThat(hiddenAt).isNotNull();
        assertThat(reports.findById(second).orElseThrow().processedAt()).isNull();
        process(second, secondAdmin, "HIDDEN").andExpect(status().isOk());
        assertThat(comments.findByIdForUpdate(comment).orElseThrow().hiddenAt()).isEqualTo(hiddenAt);
        process(third, admin, "DELETED").andExpect(status().isOk());
        assertThat(comments.findByIdForUpdate(comment)).isEmpty();
        mvc.perform(auth(get(PATH + "/{id}", first), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commentContent").value("원문"))
                .andExpect(jsonPath("$.data.status").value("PROCESSED"));
        process(first, admin, "NO_ACTION").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REPORT_ALREADY_PROCESSED"));
    }

    @Test
    void publicListExcludesHiddenAndDeletedCommentsWhileReportsRemain() throws Exception {
        var hiddenReport = seedReport(reporter, NOW);
        var pendingReport = seedReport(secondAdmin, NOW);
        UUID deletedComment = UUID.randomUUID();
        UUID visibleComment = UUID.randomUUID();
        comments.save(new Comment(deletedComment, match, author, "삭제 대상", NOW));
        comments.save(new Comment(visibleComment, match, author, "정상 댓글", NOW));
        UUID deletedReport = UUID.randomUUID();
        reports.save(new Report(deletedReport, reporter, author, deletedComment, "삭제 대상",
                "SPAM", null, NOW, null, null, null));

        String list = "/api/v1/matches/{matchId}/comments";
        mvc.perform(auth(get(list, match), author)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id").value(containsInAnyOrder(
                        comment.toString(), deletedComment.toString(), visibleComment.toString())));
        process(hiddenReport, admin, "HIDDEN").andExpect(status().isOk());
        mvc.perform(auth(get(list, match), author)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id").value(containsInAnyOrder(
                        deletedComment.toString(), visibleComment.toString())));
        process(deletedReport, admin, "DELETED").andExpect(status().isOk());
        mvc.perform(auth(get(list, match), author)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id").value(containsInAnyOrder(visibleComment.toString())));

        assertThat(reports.findById(hiddenReport).orElseThrow().commentContent()).isEqualTo("원문");
        assertThat(reports.findById(deletedReport).orElseThrow().commentContent()).isEqualTo("삭제 대상");
        assertThat(reports.findById(pendingReport).orElseThrow().processedAt()).isNull();
    }

    @Test
    void missingCommentRollsBackHideAndAllowsOtherResolutions() throws Exception {
        var first = seedReport(reporter, NOW);
        var second = seedReport(secondAdmin, NOW);
        comments.deleteById(comment);

        process(first, admin, "HIDDEN").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("COMMENT_ALREADY_DELETED"));
        var pending = reports.findById(first).orElseThrow();
        assertThat(pending.processedAt()).isNull();
        assertThat(pending.processedBy()).isNull();
        assertThat(pending.resolution()).isNull();

        process(first, admin, "NO_ACTION").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolution").value("NO_ACTION"));
        process(second, admin, "DELETED").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolution").value("DELETED"));
    }

    @Test
    void concurrentRequestsCanProcessAReportOnlyOnce() throws Exception {
        var reportId = seedReport(reporter, NOW);
        var start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return process(reportId, admin, "HIDDEN").andReturn().getResponse().getStatus();
            });
            var second = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return process(reportId, secondAdmin, "DELETED").andReturn().getResponse().getStatus();
            });
            int hideStatus = first.get(10, TimeUnit.SECONDS);
            int deleteStatus = second.get(10, TimeUnit.SECONDS);
            assertThat(new int[]{hideStatus, deleteStatus}).containsExactlyInAnyOrder(200, 409);
            var result = reports.findById(reportId).orElseThrow();
            if (hideStatus == 200) {
                assertThat(result.processedBy()).isEqualTo(admin);
                assertThat(comments.findByIdForUpdate(comment).orElseThrow().hiddenAt()).isNotNull();
            } else {
                assertThat(result.processedBy()).isEqualTo(secondAdmin);
                assertThat(comments.findByIdForUpdate(comment)).isEmpty();
            }
        }
    }

    private UUID seedReport(UUID reporterId, LocalDateTime reportedAt) {
        UUID id = UUID.randomUUID();
        reports.save(new Report(id, reporterId, author, comment, "원문", "SPAM", "상세", reportedAt, null, null, null));
        return id;
    }

    private org.springframework.test.web.servlet.ResultActions process(UUID reportId, UUID actor, String resolution)
            throws Exception {
        return mvc.perform(auth(patch(PATH + "/{id}", reportId), actor)
                .contentType("application/json").content("{\"resolution\":\"" + resolution + "\"}"));
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, UUID actor) {
        return request.with(com.teamehera.kiikick.auth.security.TestJwtRequests.bearer(tokens, actor));
    }
}
