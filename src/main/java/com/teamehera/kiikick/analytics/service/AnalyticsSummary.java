package com.teamehera.kiikick.analytics.service;

import com.teamehera.kiikick.analytics.repository.AnalyticsRepository.QuarterData;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record AnalyticsSummary(long matchCount, int quarterCount, Long goals, Long assists,
                               Map<String, MetricAggregate> metrics, Meta meta) {

    static final List<String> METRICS = List.of("goals", "assists", "totalDistanceM", "totalCaloriesKcal",
            "highIntensityDistanceM", "accelerationCount", "decelerationCount", "directionChangeCount",
            "maxSpeedMps", "maxHeartRateBpm", "averageSpeedMps", "averageHeartRateBpm", "trimpEdwards");
    private static final Set<String> VERSION_SENSITIVE = Set.of("accelerationCount", "decelerationCount",
            "directionChangeCount", "trimpEdwards");
    private static final Set<String> MAXIMUMS = Set.of("maxSpeedMps", "maxHeartRateBpm");

    static AnalyticsSummary from(List<QuarterData> rows) {
        var accumulator = new Accumulator();
        rows.forEach(accumulator::accept);
        return accumulator.finish();
    }

    static MetricAggregate calculate(List<QuarterData> rows, String metric) {
        var fold = new MetricFold(metric);
        rows.forEach(fold::accept);
        return fold.finish();
    }

    static Double delta(Accumulator left, Accumulator right, String metric) {
        var first = left.metrics.get(metric).finish(left.total, left.policy);
        var second = right.metrics.get(metric).finish(right.total, right.policy);
        if (!"READY".equals(first.status()) || !"READY".equals(second.status())
                || !left.policy.compatible(right.policy, metric)) {
            return null;
        }
        double difference = first.value() - second.value();
        return Double.isFinite(difference) ? difference : null;
    }

    static final class Accumulator {
        private final Map<String, MetricAccumulator> metrics = new LinkedHashMap<>();
        private final Set<java.util.UUID> matches = new HashSet<>();
        private final Set<String> versions = new java.util.TreeSet<>();
        private final Policy policy = new Policy();
        private int total;
        private int missing;

        Accumulator() {
            METRICS.forEach(metric -> metrics.put(metric, new MetricAccumulator(metric)));
        }

        void accept(QuarterData row) {
            total++;
            matches.add(row.matchId());
            if (row.goals() == null || row.assists() == null) missing++;
            if (row.version() != null) versions.add(row.version());
            policy.accept(row);
            metrics.values().forEach(metric -> metric.accept(row));
        }

        AnalyticsSummary finish() {
            var results = new LinkedHashMap<String, MetricAggregate>();
            var coverage = new LinkedHashMap<String, Coverage>();
            metrics.forEach((name, accumulator) -> {
                var result = accumulator.finish(total, policy);
                results.put(name, result);
                coverage.put(name, new Coverage(result.includedQuarterCount(), total));
            });
            return new AnalyticsSummary(matches.size(), total, integerValue(results.get("goals")),
                    integerValue(results.get("assists")), results,
                    new Meta(total, missing, coverage, List.copyOf(versions),
                            total == 0 ? "NO_COMPLETED_QUARTERS" : null));
        }
    }

    static final class MetricFold {
        private final MetricAccumulator metric;
        private final Policy policy = new Policy();
        private int total;

        MetricFold(String name) { metric = new MetricAccumulator(name); }

        void accept(QuarterData row) {
            total++;
            policy.accept(row);
            metric.accept(row);
        }

        MetricAggregate finish() { return metric.finish(total, policy); }
    }

    private static final class MetricAccumulator {
        private final String metric;
        private double aggregate;
        private double weight;
        private int included;

        private MetricAccumulator(String metric) {
            this.metric = metric;
            aggregate = MAXIMUMS.contains(metric) ? Double.NEGATIVE_INFINITY : 0;
        }

        private void accept(QuarterData row) {
            Double value = value(row, metric);
            if (value == null) return;
            // 평균은 쿼터 개수가 아닌 실제 거리/시간 및 유효 심박 시간으로 가중한다.
            switch (metric) {
                case "averageSpeedMps" -> {
                    if (!positive(row.elapsedSeconds()) || !row.values().containsKey("totalDistanceM")) return;
                    aggregate += row.values().get("totalDistanceM");
                    weight += row.elapsedSeconds();
                }
                case "averageHeartRateBpm" -> {
                    if (!positive(row.validHeartSeconds())) return;
                    aggregate += value * row.validHeartSeconds();
                    weight += row.validHeartSeconds();
                }
                case "maxSpeedMps", "maxHeartRateBpm" -> aggregate = Math.max(aggregate, value);
                default -> aggregate += value;
            }
            included++;
        }

        private MetricAggregate finish(int total, Policy policy) {
            if (total == 0) return new MetricAggregate(isDirect(metric) ? 0.0 : null, 0, 0, "NO_DATA");
            // 누락을 정책 불일치보다 먼저 보고하는 기존 계약을 보존한다.
            if (included < total) return new MetricAggregate(null, included, total, "INCOMPLETE");
            double result = weight == 0 ? aggregate : aggregate / weight;
            if (!policy.compatible(policy, metric) || !Double.isFinite(result)) {
                return new MetricAggregate(null, included, total, "POLICY_REQUIRED");
            }
            return new MetricAggregate(result, included, total, "READY");
        }
    }

    private static final class Policy {
        private String version;
        private Double reference;
        private boolean invalidVersion;
        private boolean invalidReference;

        private void accept(QuarterData row) {
            invalidVersion |= row.version() == null || row.version().isBlank()
                    || (version != null && !version.equals(row.version()));
            if (version == null) version = row.version();
            invalidReference |= !positive(row.referenceBpm())
                    || (reference != null && !reference.equals(row.referenceBpm()));
            if (reference == null) reference = row.referenceBpm();
        }

        private boolean compatible(Policy other, String metric) {
            if (!VERSION_SENSITIVE.contains(metric)) return true;
            if (invalidVersion || other.invalidVersion
                    || (version != null && other.version != null && !version.equals(other.version))) return false;
            return !"trimpEdwards".equals(metric) || (!invalidReference && !other.invalidReference
                    && (reference == null || other.reference == null || reference.equals(other.reference)));
        }
    }

    private static Double value(QuarterData row, String metric) {
        return switch (metric) {
            case "goals" -> row.goals() == null ? null : row.goals().doubleValue();
            case "assists" -> row.assists() == null ? null : row.assists().doubleValue();
            default -> row.values().get(metric);
        };
    }

    private static boolean isDirect(String metric) {
        return "goals".equals(metric) || "assists".equals(metric);
    }

    private static boolean positive(Double value) {
        return value != null && Double.isFinite(value) && value > 0;
    }

    private static Long integerValue(MetricAggregate metric) {
        return metric.value() == null ? null : metric.value().longValue();
    }

    public record MetricAggregate(Double value, int includedQuarterCount, int totalQuarterCount, String status) {}
    public record Coverage(int includedQuarterCount, int totalQuarterCount) {}
    public record Meta(int quarterCount, int missingStatsQuarterCount, Map<String, Coverage> metricCoverage,
                       List<String> calculationVersions, String emptyReason) {}
}
