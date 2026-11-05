package com.teamehera.kiikick.match.api;

import com.teamehera.kiikick.analytics.service.AnalyticsQuery;
import com.teamehera.kiikick.analytics.service.AnalyticsQuery.Bucket;
import com.teamehera.kiikick.analytics.service.AnalyticsQuery.InvalidAnalytics;
import com.teamehera.kiikick.analytics.service.AnalyticsQuery.Period;
import com.teamehera.kiikick.analytics.service.AnalyticsSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

@MatchApiController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {
    private final AnalyticsQuery query;

    public AnalyticsController(AnalyticsQuery query) {
        this.query = query;
    }

    @GetMapping("/summary")
    public MatchApiResponse.Success<AnalyticsSummary> summary(Principal principal,
            @RequestParam(required = false) UUID subjectUserId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        UUID actor = MatchController.actor(principal);
        return success(query.summary(actor, subject(actor, subjectUserId), period(from, to)));
    }

    @GetMapping("/trends")
    public AnalyticsQuery.TrendPage trends(Principal principal, @RequestParam(required = false) UUID subjectUserId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String timeZone, @RequestParam(required = false) String metric,
            @RequestParam(required = false) String bucket, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        UUID actor = MatchController.actor(principal);
        if (timeZone != null && !"Asia/Seoul".equals(timeZone)) {
            throw new InvalidAnalytics("timeZone");
        }
        return query.trends(actor, subject(actor, subjectUserId), period(from, to), AnalyticsQuery.requireMetric(metric),
                bucket(bucket), cursor, limit);
    }

    @GetMapping("/match-comparison")
    public MatchApiResponse.Success<AnalyticsQuery.Comparison> compareMatches(Principal principal,
            @RequestParam(required = false) UUID matchId, @RequestParam(required = false) UUID previousMatchId,
            @RequestParam(required = false) String metric) {
        UUID actor = MatchController.actor(principal);
        if (matchId == null) {
            throw new InvalidAnalytics("matchId");
        }
        return success(query.compareMatches(actor, matchId, previousMatchId, AnalyticsQuery.requireMetric(metric)));
    }

    @GetMapping("/positions")
    public MatchApiResponse.Success<List<AnalyticsQuery.PositionAnalysis>> positions(Principal principal,
            @RequestParam(required = false) UUID subjectUserId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        UUID actor = MatchController.actor(principal);
        return success(query.positions(actor, subject(actor, subjectUserId), period(from, to)));
    }

    @GetMapping("/friend-comparison")
    public MatchApiResponse.Success<AnalyticsQuery.FriendComparison> compareFriend(Principal principal,
            @RequestParam(required = false) UUID friendUserId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String metric) {
        UUID actor = MatchController.actor(principal);
        if (friendUserId == null) {
            throw new InvalidAnalytics("friendUserId");
        }
        return success(query.compareFriend(actor, friendUserId, period(from, to), AnalyticsQuery.requireMetric(metric)));
    }

    private static Bucket bucket(String value) {
        if ("DAY".equals(value)) {
            return Bucket.DAY;
        }
        if ("MONTH".equals(value)) {
            return Bucket.MONTH;
        }
        throw new InvalidAnalytics("bucket");
    }

    private static Period period(String from, String to) {
        var start = boundary(from, "from");
        var end = boundary(to, "to");
        if (start != null && end != null && !start.isBefore(end)) {
            throw new InvalidAnalytics("to");
        }
        return new Period(start, end);
    }

    private static LocalDateTime boundary(String value, String field) {
        if (value == null) {
            return null;
        }
        try {
            var time = OffsetDateTime.parse(value);
            var utc = MatchApiResponse.toUtc(time);
            if (time.getOffset().equals(ZoneOffset.ofHours(9)) && utc.getYear() >= 1000 && utc.getYear() <= 9999) {
                return utc;
            }
        } catch (DateTimeParseException ignored) {
            // Match the same error for malformed timestamps and unsupported offsets.
        }
        throw new InvalidAnalytics(field);
    }

    private static UUID subject(UUID actor, UUID requested) {
        return requested == null ? actor : requested;
    }

    private static <T> MatchApiResponse.Success<T> success(T data) {
        return new MatchApiResponse.Success<>(data, MatchApiResponse.Meta.create());
    }
}
