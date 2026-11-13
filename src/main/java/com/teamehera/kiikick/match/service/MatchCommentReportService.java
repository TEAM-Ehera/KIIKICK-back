package com.teamehera.kiikick.match.service;

import com.teamehera.kiikick.friend.repository.UserBlockRepository;
import com.teamehera.kiikick.match.repository.MatchCommentReportRepository;
import com.teamehera.kiikick.match.repository.MatchCommentReportRepository.Report;
import com.teamehera.kiikick.match.repository.MatchCommentRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

@Service
public class MatchCommentReportService {
    private static final int MAX_DETAIL_LENGTH = 1000;
    private static final String REPORTER_COMMENT_UNIQUE = "uq_match_comment_reports_reporter_comment";

    private final MatchSocialAccess access;
    private final MatchCommentRepository comments;
    private final MatchCommentReportRepository reports;
    private final UserBlockRepository blocks;

    public MatchCommentReportService(MatchSocialAccess access, MatchCommentRepository comments,
                                     MatchCommentReportRepository reports, UserBlockRepository blocks) {
        this.access = access;
        this.comments = comments;
        this.reports = reports;
        this.blocks = blocks;
    }

    @Transactional
    public Report submit(UUID reporterId, UUID matchId, UUID commentId, String reason, String detail) {
        access.requireVisibleMatch(reporterId, matchId);
        Reason reportReason = parseReason(reason);
        if (detail != null && detail.codePointCount(0, detail.length()) > MAX_DETAIL_LENGTH) {
            throw new InvalidInput("detail");
        }
        String reportDetail = detail == null || detail.isBlank() ? null : detail;

        var comment = comments.findByIdForUpdate(matchId, commentId)
                .orElseThrow(MatchOwnership.ResourceNotFound::new);
        if (comment.hiddenAt() != null || blocks.existsEitherDirection(reporterId, comment.authorId())) {
            throw new MatchOwnership.ResourceNotFound();
        }
        if (reporterId.equals(comment.authorId())) {
            throw new SelfReportNotAllowed();
        }

        var reportedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        var report = new Report(UUID.randomUUID(), reporterId, comment.authorId(), comment.id(),
                comment.content(), reportReason.name(), reportDetail, reportedAt, null, null, null);
        try {
            reports.save(report);
        } catch (DuplicateKeyException exception) {
            String message = exception.getMostSpecificCause().getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(REPORTER_COMMENT_UNIQUE)) {
                throw new CommentAlreadyReported(exception);
            }
            throw exception;
        }
        return report;
    }

    private static Reason parseReason(String reason) {
        if (reason != null) {
            try {
                return Reason.valueOf(reason);
            } catch (IllegalArgumentException ignored) {
                // 알 수 없는 코드는 누락된 사유와 같은 필드 오류로 처리한다.
            }
        }
        throw new InvalidInput("reason");
    }

    public enum Reason { SPAM, ABUSE, PERSONAL_INFORMATION, OTHER }

    public static final class InvalidInput extends RuntimeException {
        public InvalidInput(String field) {
            super(field);
        }
    }

    public static final class SelfReportNotAllowed extends RuntimeException {}

    public static final class CommentAlreadyReported extends RuntimeException {
        public CommentAlreadyReported(DuplicateKeyException cause) {
            super(cause);
        }
    }
}
