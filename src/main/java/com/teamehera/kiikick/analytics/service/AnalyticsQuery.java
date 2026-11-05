package com.teamehera.kiikick.analytics.service;

import com.teamehera.kiikick.analytics.repository.AnalyticsRepository;
import com.teamehera.kiikick.analytics.repository.AnalyticsRepository.QuarterData;
import com.teamehera.kiikick.analytics.service.AnalyticsSummary.MetricAggregate;
import com.teamehera.kiikick.friend.repository.FriendPairRepository;
import com.teamehera.kiikick.friend.repository.UserBlockRepository;
import com.teamehera.kiikick.match.api.MatchApiResponse;
import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.service.MatchListQuery.InvalidListRequest;
import com.teamehera.kiikick.match.service.MatchListQuery.Pagination;
import com.teamehera.kiikick.match.service.MatchOwnership;
import com.teamehera.kiikick.match.service.MatchOwnership.ResourceNotFound;
import com.teamehera.kiikick.match.service.MatchResultQuery.ResultNotReady;
import com.teamehera.kiikick.user.repository.UserConsentRepository;
import com.teamehera.kiikick.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.teamehera.kiikick.match.api.MatchApiResponse.fromUtc;
import static com.teamehera.kiikick.match.api.MatchApiResponse.toUtc;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AnalyticsQuery {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String CONSENT_PURPOSE = "FRIEND_COMPARISON";
    private final AnalyticsRepository data;
    private final MatchOwnership ownership;
    private final FriendPairRepository friends;
    private final UserBlockRepository blocks;
    private final UserConsentRepository consents;
    private final UserRepository users;

    public AnalyticsQuery(AnalyticsRepository data, MatchOwnership ownership, FriendPairRepository friends,
                          UserBlockRepository blocks, UserConsentRepository consents, UserRepository users) {
        this.data = data;
        this.ownership = ownership;
        this.friends = friends;
        this.blocks = blocks;
        this.consents = consents;
        this.users = users;
    }

    public AnalyticsSummary summary(UUID actor, UUID subject, Period period) {
        requireAccess(actor, subject);
        return AnalyticsSummary.from(rows(subject, period));
    }

    public List<PositionAnalysis> positions(UUID actor, UUID subject, Period period) {
        requireAccess(actor, subject);
        var rows = rows(subject, period);
        var counts = new HashMap<String, Integer>();
        rows.forEach(row -> counts.merge(row.position(), 1, Integer::sum));
        return counts.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey(Comparator.nullsLast(Comparator.naturalOrder())))
                .map(entry -> new PositionAnalysis(entry.getKey(), entry.getValue(), (double) entry.getValue() / rows.size()))
                .toList();
    }

    public Comparison compareMatches(UUID actor, UUID matchId, UUID previousId, String metric) {
        var match = completed(actor, matchId);
        if (matchId.equals(previousId)) {
            throw new InvalidAnalytics("previousMatchId");
        }
        UUID prior = previousId;
        if (prior == null && match.endedAt() != null) {
            prior = data.previous(actor, matchId, match.endedAt()).orElse(null);
        }
        var currentRows = data.quarters(actor, matchId, null, null);
        var current = AnalyticsSummary.from(currentRows);
        if (prior == null) {
            return new Comparison(current, null, metric, null);
        }
        completed(actor, prior);
        var priorRows = data.quarters(actor, prior, null, null);
        var previous = AnalyticsSummary.from(priorRows);
        return new Comparison(current, previous, metric, AnalyticsSummary.delta(current, currentRows, previous, priorRows, metric));
    }

    public FriendComparison compareFriend(UUID actor, UUID friend, Period period, String metric) {
        if (actor.equals(friend)) {
            throw new InvalidAnalytics("friendUserId");
        }
        requireAccess(actor, friend);
        var myRows = rows(actor, period);
        var friendRows = rows(friend, period);
        var me = AnalyticsSummary.from(myRows);
        var other = AnalyticsSummary.from(friendRows);
        return new FriendComparison(me, other, metric, AnalyticsSummary.delta(me, myRows, other, friendRows, metric));
    }

    public TrendPage trends(UUID actor, UUID subject, Period period, String metric, Bucket bucket,
                            String cursor, Integer limit) {
        requireAccess(actor, subject);
        int size = limit == null ? 20 : limit;
        if (size < 1 || size > 100) {
            throw InvalidListRequest.of("INVALID_REQUEST");
        }
        String scope = "1|" + actor + "|" + subject + "|" + period.from() + "|" + period.to()
                + "|" + metric + "|" + bucket + "|";
        var after = cursor == null ? null : decode(cursor, scope, bucket);
        var extent = period.from() == null || period.to() == null
                ? data.extent(subject, period.from(), period.to()) : null;
        var first = period.from() == null ? extent.first() : period.from();
        var last = period.to() == null ? extent.last() : period.to().minusNanos(1);
        if (first == null || last == null) {
            return trendPage(List.of(), null);
        }
        var initial = bucket.start(first);
        var finalBucket = bucket.start(last);
        if (after != null && (after.isBefore(initial) || after.isAfter(finalBucket))) {
            throw InvalidListRequest.of("INVALID_CURSOR");
        }
        var start = after == null ? initial : bucket.next(after);
        if (start.isAfter(finalBucket)) {
            return trendPage(List.of(), null);
        }

        var starts = new ArrayList<LocalDateTime>();
        var next = start;
        while (!next.isAfter(finalBucket) && starts.size() < size) {
            starts.add(next);
            next = bucket.next(next);
        }
        // Bound the sample-free database projection to the buckets on this page.
        var from = max(period.from(), utc(start));
        var to = min(period.to(), utc(next));
        var grouped = data.quarters(subject, null, from, to).stream().filter(row -> row.endedAt() != null)
                .collect(Collectors.groupingBy(row -> bucket.start(row.endedAt())));
        var points = starts.stream().map(point -> new TrendPoint(seoul(point), seoul(bucket.next(point)), metric,
                AnalyticsSummary.calculate(grouped.getOrDefault(point, List.of()), metric))).toList();
        String nextCursor = next.isAfter(finalBucket) ? null : encode(scope + starts.getLast());
        return trendPage(points, nextCursor);
    }

    private List<QuarterData> rows(UUID subject, Period period) {
        return data.quarters(subject, null, period.from(), period.to());
    }

    private Match completed(UUID actor, UUID matchId) {
        var match = ownership.requireMatch(actor, matchId);
        if (!"COMPLETED".equals(match.status())) {
            throw new ResultNotReady();
        }
        return match;
    }

    private void requireAccess(UUID actor, UUID subject) {
        if (actor.equals(subject)) {
            return;
        }
        // Re-evaluate the relationship and consent in the same read snapshot as the aggregate.
        if (blocks.existsEitherDirection(actor, subject) || users.findById(subject).isEmpty()
                || friends.findByUsers(actor, subject).filter(pair -> "ACCEPTED".equals(pair.status())).isEmpty()) {
            throw new ResourceNotFound();
        }
        if (!granted(actor) || !granted(subject)) {
            throw new ConsentRequired();
        }
    }

    private boolean granted(UUID user) {
        return consents.findLatest(user, CONSENT_PURPOSE).filter(consent -> consent.granted()).isPresent();
    }

    private static TrendPage trendPage(List<TrendPoint> points, String next) {
        var meta = MatchApiResponse.Meta.create();
        return new TrendPage(points, new TrendMeta(meta.requestId(), meta.serverTime(), new Pagination(next, next != null)));
    }

    private static LocalDateTime decode(String cursor, String scope, Bucket bucket) {
        if (cursor.length() <= 500) {
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                if (decoded.startsWith(scope)) {
                    var time = LocalDateTime.parse(decoded.substring(scope.length()));
                    if (time.toLocalTime().equals(LocalTime.MIDNIGHT) && time.getYear() >= 1000
                            && time.getYear() <= 10000 && (bucket == Bucket.DAY || time.getDayOfMonth() == 1)) {
                        return time;
                    }
                }
            } catch (IllegalArgumentException | DateTimeParseException ignored) {
                // Reject malformed cursors and cursors scoped to another query.
            }
        }
        throw InvalidListRequest.of("INVALID_CURSOR");
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static LocalDateTime utc(LocalDateTime seoul) {
        return toUtc(seoul(seoul));
    }

    private static OffsetDateTime seoul(LocalDateTime local) {
        return local.atZone(SEOUL).toOffsetDateTime();
    }

    private static LocalDateTime max(LocalDateTime optional, LocalDateTime bound) {
        return optional != null && optional.isAfter(bound) ? optional : bound;
    }

    private static LocalDateTime min(LocalDateTime optional, LocalDateTime bound) {
        return optional != null && optional.isBefore(bound) ? optional : bound;
    }

    public static String requireMetric(String metric) {
        if (metric == null || !AnalyticsSummary.METRICS.contains(metric)) {
            throw new InvalidAnalytics("metric");
        }
        return metric;
    }

    public enum Bucket {
        DAY, MONTH;

        LocalDateTime start(LocalDateTime utc) {
            var date = fromUtc(utc).toLocalDate();
            return (this == DAY ? date : date.withDayOfMonth(1)).atStartOfDay();
        }

        LocalDateTime next(LocalDateTime start) {
            return this == DAY ? start.plusDays(1) : start.plusMonths(1);
        }
    }

    public record Period(LocalDateTime from, LocalDateTime to) {}
    public record PositionAnalysis(String positionCode, int quarterCount, double ratio) {}
    public record Comparison(AnalyticsSummary current, AnalyticsSummary previous, String metric, Double delta) {}
    public record FriendComparison(AnalyticsSummary me, AnalyticsSummary friend, String metric, Double delta) {}
    public record TrendPoint(OffsetDateTime bucketStart, OffsetDateTime bucketEnd, String metric, MetricAggregate result) {}
    public record TrendPage(List<TrendPoint> data, TrendMeta meta) {}
    public record TrendMeta(UUID requestId, OffsetDateTime serverTime, Pagination pagination) {}

    public static final class InvalidAnalytics extends RuntimeException {
        public InvalidAnalytics(String field) {
            super(field);
        }
    }

    public static final class ConsentRequired extends RuntimeException {}
}
