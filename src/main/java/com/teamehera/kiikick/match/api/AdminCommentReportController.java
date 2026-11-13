package com.teamehera.kiikick.match.api;

import com.teamehera.kiikick.match.repository.MatchCommentReportRepository.Report;
import com.teamehera.kiikick.match.repository.MatchCommentReportRepository.Resolution;
import com.teamehera.kiikick.match.repository.MatchCommentReportRepository.Status;
import com.teamehera.kiikick.match.service.AdminCommentReportService;
import com.teamehera.kiikick.match.service.MatchListQuery.PageMeta;
import com.teamehera.kiikick.match.service.MatchListQuery.Pagination;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@MatchApiController
@RequestMapping(value = "/api/v1/admin/comment-reports", produces = MediaType.APPLICATION_JSON_VALUE)
public class AdminCommentReportController {
    private final AdminCommentReportService reports;
    private final ObjectMapper json;

    public AdminCommentReportController(AdminCommentReportService reports, ObjectMapper json) {
        this.reports = reports;
        this.json = json;
    }

    @Operation(summary = "댓글 신고 목록 조회")
    @ApiResponse(responseCode = "200", description = "신고 목록", useReturnTypeSchema = true)
    @GetMapping
    public ReportPage list(@RequestParam(defaultValue = "PENDING") Status status,
                           @RequestParam(required = false) String cursor,
                           @RequestParam(required = false) Integer limit) {
        var page = reports.list(status, cursor, limit);
        var meta = MatchApiResponse.Meta.create();
        return new ReportPage(page.reports().stream().map(ReportSummary::from).toList(),
                new PageMeta(meta.requestId(), meta.serverTime(),
                        new Pagination(page.nextCursor(), page.nextCursor() != null)));
    }

    @Operation(summary = "댓글 신고 상세 조회")
    @ApiResponse(responseCode = "200", description = "신고 상세", useReturnTypeSchema = true)
    @GetMapping("/{reportId}")
    public MatchApiResponse.Success<ReportDetail> detail(@PathVariable UUID reportId) {
        return new MatchApiResponse.Success<>(ReportDetail.from(reports.get(reportId)), MatchApiResponse.Meta.create());
    }

    @Operation(summary = "댓글 신고 처리", requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = ProcessBody.class),
            examples = @ExampleObject(value = "{\"resolution\":\"HIDDEN\"}"))))
    @ApiResponse(responseCode = "200", description = "처리된 신고 상세", useReturnTypeSchema = true)
    @PatchMapping(value = "/{reportId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MatchApiResponse.Success<ReportDetail> process(
            Principal principal, @PathVariable UUID reportId, @RequestBody(required = false) byte[] body) {
        UUID processedBy = MatchController.actor(principal);
        var input = MatchSettings.readBody(json, body);
        MatchSettings.checkObject(input, "body", Set.of("resolution"));
        var value = input.path("resolution");
        if (!value.isString()) {
            throw new MatchSettings.InvalidSettings(List.of("resolution"));
        }
        Resolution resolution;
        try {
            resolution = Resolution.valueOf(value.stringValue());
        } catch (IllegalArgumentException exception) {
            throw new MatchSettings.InvalidSettings(List.of("resolution"));
        }
        return new MatchApiResponse.Success<>(ReportDetail.from(reports.process(reportId, processedBy, resolution)),
                MatchApiResponse.Meta.create());
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record ProcessBody(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Resolution resolution) {}

    public record ReportPage(List<ReportSummary> data, PageMeta meta) {}

    public record ReportSummary(UUID id, UUID reporterId, UUID commentId, String reason,
                                OffsetDateTime reportedAt, Status status) {
        static ReportSummary from(Report report) {
            return new ReportSummary(report.id(), report.reporterId(), report.commentId(), report.reason(),
                    MatchApiResponse.fromUtc(report.reportedAt()), statusOf(report));
        }
    }

    public record ReportDetail(UUID id, UUID reporterId, UUID commentId, String reason, OffsetDateTime reportedAt,
                               Status status, UUID commentAuthorId, String commentContent, String detail,
                               OffsetDateTime processedAt, UUID processedBy, Resolution resolution) {
        static ReportDetail from(Report report) {
            return new ReportDetail(report.id(), report.reporterId(), report.commentId(), report.reason(),
                    MatchApiResponse.fromUtc(report.reportedAt()), statusOf(report), report.commentAuthorId(),
                    report.commentContent(), report.detail(), MatchApiResponse.fromUtc(report.processedAt()),
                    report.processedBy(), report.resolution());
        }
    }

    private static Status statusOf(Report report) {
        return report.processedAt() == null ? Status.PENDING : Status.PROCESSED;
    }
}
