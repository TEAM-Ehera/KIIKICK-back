package com.teamehera.kiikick.match.api;

import com.teamehera.kiikick.auth.security.AccessTokenService;
import com.teamehera.kiikick.friend.domain.FriendPair;
import com.teamehera.kiikick.friend.repository.FriendPairRepository;
import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.repository.MatchCommentReportRepository;
import com.teamehera.kiikick.match.repository.MatchCommentRepository;
import com.teamehera.kiikick.match.repository.MatchCommentRepository.Comment;
import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.UUID;

import static com.teamehera.kiikick.auth.security.TestJwtRequests.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:match_comment_report_controller;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class MatchCommentReportControllerTest {
    private static final String PATH = "/api/v1/matches/{matchId}/comments/{commentId}/reports";
    private static final String VALID_BODY = """
            {"reason":"SPAM"}
            """;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 0, 0);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccessTokenService tokens;
    @Autowired UserRepository users;
    @Autowired MatchRepository matches;
    @Autowired MatchCommentRepository comments;
    @Autowired MatchCommentReportRepository reports;
    @Autowired FriendPairRepository friends;

    private final UUID author = UUID.randomUUID();
    private final UUID reporter = UUID.randomUUID();
    private final UUID match = UUID.randomUUID();
    private final UUID comment = UUID.randomUUID();

    @BeforeEach
    void seed() {
        users.save(author, null);
        users.save(reporter, null);
        users.completeOnboarding(author, author.toString().substring(0, 16), NOW);
        users.completeOnboarding(reporter, reporter.toString().substring(0, 16), NOW);
        var pair = FriendPairRepository.normalize(author, reporter);
        friends.save(new FriendPair(UUID.randomUUID(), pair[0], pair[1], author, "ACCEPTED", NOW, NOW));
        matches.save(new Match(match, author, null, "친구 경기", null, null, null, "COMPLETED",
                NOW, NOW.plusHours(1), 1, NOW, NOW));
        comments.save(new Comment(comment, match, author, "신고 대상 댓글", NOW));
    }

    @Test
    void createsReportWithoutIdempotencyKeyAndStoresAuthenticatedReporter() throws Exception {
        String body = mvc.perform(request(comment, VALID_BODY).with(bearer(tokens, reporter)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.commentId").value(comment.toString()))
                .andExpect(jsonPath("$.meta.requestId").exists())
                .andReturn().getResponse().getContentAsString();

        UUID reportId = UUID.fromString(json.readTree(body).at("/data/id").stringValue());
        var saved = reports.findById(reportId).orElseThrow();
        assertThat(saved.reporterId()).isEqualTo(reporter);
        assertThat(saved.commentAuthorId()).isEqualTo(author);
        assertThat(saved.commentContent()).isEqualTo("신고 대상 댓글");
    }

    @Test
    void rejectsRequestWithoutJwt() throws Exception {
        mvc.perform(request(comment, VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void rejectsMissingComment() throws Exception {
        mvc.perform(request(UUID.randomUUID(), VALID_BODY).with(bearer(tokens, reporter)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void rejectsDuplicateReport() throws Exception {
        mvc.perform(request(comment, VALID_BODY).with(bearer(tokens, reporter)))
                .andExpect(status().isCreated());
        mvc.perform(request(comment, VALID_BODY).with(bearer(tokens, reporter)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("COMMENT_ALREADY_REPORTED"));
    }

    @Test
    void rejectsReportingOwnComment() throws Exception {
        mvc.perform(request(comment, VALID_BODY).with(bearer(tokens, author)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("SELF_REPORT_NOT_ALLOWED"));
    }

    @Test
    void rejectsInvalidReason() throws Exception {
        mvc.perform(request(comment, """
                {"reason":"UNKNOWN"}
                """).with(bearer(tokens, reporter)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.fields[0]").value("reason"));
    }

    @Test
    void rejectsClientSuppliedReporterId() throws Exception {
        mvc.perform(request(comment, """
                {"reason":"SPAM","reporterId":"%s"}
                """.formatted(author)).with(bearer(tokens, reporter)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.fields[0]").value("reporterId"));
    }

    private MockHttpServletRequestBuilder request(UUID commentId, String body) {
        return post(PATH, match, commentId).contentType("application/json").content(body);
    }
}
