package com.teamehera.kiikick.match.service;

import com.teamehera.kiikick.match.repository.MatchCommentReportRepository;
import com.teamehera.kiikick.match.repository.MatchCommentReportRepository.Report;
import com.teamehera.kiikick.match.repository.MatchCommentReportRepository.Resolution;
import com.teamehera.kiikick.match.repository.MatchCommentReportRepository.Status;
import com.teamehera.kiikick.match.repository.MatchCommentRepository;
import com.teamehera.kiikick.match.service.MatchListQuery.InvalidListRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class AdminCommentReportService {
    private final MatchCommentReportRepository reports;
    private final MatchCommentRepository comments;

    public AdminCommentReportService(MatchCommentReportRepository reports, MatchCommentRepository comments) {
        this.reports = reports;
        this.comments = comments;
    }

    public Page list(Status status, String cursor, Integer limit) {
        int size = limit == null ? 20 : limit;
        if (size < 1 || size > 100) {
            throw InvalidListRequest.of("INVALID_REQUEST");
        }
        String scope = "1|comment-reports|" + status + "|";
        var boundary = cursor == null ? null : decode(cursor, scope);
        var rows = reports.page(status, boundary == null ? null : boundary.time(),
                boundary == null ? null : boundary.id(), size + 1);
        if (rows.size() <= size) {
            return new Page(rows, null);
        }
        var page = rows.subList(0, size);
        var last = page.getLast();
        String next = Base64.getUrlEncoder().withoutPadding().encodeToString(
                (scope + last.reportedAt() + "|" + last.id()).getBytes(StandardCharsets.UTF_8));
        return new Page(page, next);
    }

    public Report get(UUID reportId) {
        return reports.findById(reportId).orElseThrow(MatchOwnership.ResourceNotFound::new);
    }

    @Transactional
    public Report process(UUID reportId, UUID processedBy, Resolution resolution) {
        var report = get(reportId);
        var processedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        if (reports.markProcessed(reportId, processedBy, processedAt, resolution) == 0) {
            throw new ReportAlreadyProcessed();
        }

        switch (resolution) {
            case NO_ACTION -> { }
            case HIDDEN -> {
                var comment = comments.findByIdForUpdate(report.commentId())
                        .orElseThrow(CommentAlreadyDeleted::new);
                if (comment.hiddenAt() == null) {
                    comments.hide(comment.id(), processedAt);
                }
            }
            case DELETED -> comments.deleteById(report.commentId());
        }
        return get(reportId);
    }

    private static Boundary decode(String cursor, String scope) {
        if (cursor.length() <= 256) {
            try {
                String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                if (value.startsWith(scope)) {
                    String[] parts = value.substring(scope.length()).split("\\|", -1);
                    if (parts.length == 2) {
                        var time = LocalDateTime.parse(parts[0]);
                        var id = UUID.fromString(parts[1]);
                        if (time.getYear() >= 1000 && time.getYear() <= 9999 && id.toString().equals(parts[1])) {
                            return new Boundary(time, id);
                        }
                    }
                }
            } catch (IllegalArgumentException | DateTimeParseException ignored) {
                // 잘못된 커서는 같은 오류로 처리한다.
            }
        }
        throw InvalidListRequest.of("INVALID_CURSOR");
    }

    private record Boundary(LocalDateTime time, UUID id) {}

    public record Page(List<Report> reports, String nextCursor) {}

    public static final class ReportAlreadyProcessed extends RuntimeException {}

    public static final class CommentAlreadyDeleted extends RuntimeException {}
}
