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
        var results = new LinkedHashMap<String, MetricAggregate>();
        var coverage = new LinkedHashMap<String, Coverage>();
        for (String metric : METRICS) {
            var result = calculate(rows, metric);
            results.put(metric, result);
            coverage.put(metric, new Coverage(result.includedQuarterCount(), result.totalQuarterCount()));
        }
        long matchCount = rows.stream().map(QuarterData::matchId).distinct().count();
        int missingStats = (int) rows.stream().filter(row -> row.goals() == null || row.assists() == null).count();
        var versions = rows.stream().map(QuarterData::version).filter(java.util.Objects::nonNull).distinct().sorted().toList();
        return new AnalyticsSummary(matchCount, rows.size(), integerValue(results.get("goals")),
                integerValue(results.get("assists")), results,
                new Meta(rows.size(), missingStats, coverage, versions, rows.isEmpty() ? "NO_COMPLETED_QUARTERS" : null));
    }

    static MetricAggregate calculate(List<QuarterData> rows, String metric) {
        if (rows.isEmpty()) {
            return new MetricAggregate(isDirect(metric) ? 0.0 : null, 0, 0, "NO_DATA");
        }

        double aggregate = MAXIMUMS.contains(metric) ? Double.NEGATIVE_INFINITY : 0;
        double weight = 0;
        int included = 0;
        for (var row : rows) {
            Double value = value(row, metric);
            if (value == null) {
                continue;
            }
            // Ratios use their actual denominators. A quarter mean never counts as one unweighted sample.
            switch (metric) {
                case "averageSpeedMps" -> {
                    if (!positive(row.elapsedSeconds()) || !row.values().containsKey("totalDistanceM")) {
                        continue;
                    }
                    aggregate += row.values().get("totalDistanceM");
                    weight += row.elapsedSeconds();
                }
                case "averageHeartRateBpm" -> {
                    if (!positive(row.validHeartSeconds())) {
                        continue;
                    }
                    aggregate += value * row.validHeartSeconds();
                    weight += row.validHeartSeconds();
                }
                case "maxSpeedMps", "maxHeartRateBpm" -> aggregate = Math.max(aggregate, value);
                default -> aggregate += value;
            }
            included++;
        }

        if (included < rows.size()) {
            return new MetricAggregate(null, included, rows.size(), "INCOMPLETE");
        }
        if (!comparable(rows, List.of(), metric)) {
            return new MetricAggregate(null, included, rows.size(), "POLICY_REQUIRED");
        }
        double result = weight == 0 ? aggregate : aggregate / weight;
        if (!Double.isFinite(result)) {
            return new MetricAggregate(null, included, rows.size(), "POLICY_REQUIRED");
        }
        return new MetricAggregate(result, included, rows.size(), "READY");
    }

    static Double delta(AnalyticsSummary left, List<QuarterData> leftRows, AnalyticsSummary right,
                        List<QuarterData> rightRows, String metric) {
        var first = left.metrics().get(metric);
        var second = right.metrics().get(metric);
        if (!"READY".equals(first.status()) || !"READY".equals(second.status()) || !comparable(leftRows, rightRows, metric)) {
            return null;
        }
        double difference = first.value() - second.value();
        return Double.isFinite(difference) ? difference : null;
    }

    private static boolean comparable(List<QuarterData> left, List<QuarterData> right, String metric) {
        if (!VERSION_SENSITIVE.contains(metric)) {
            return true;
        }
        var versions = new HashSet<String>();
        var references = new HashSet<Double>();
        for (var rows : List.of(left, right)) {
            for (var row : rows) {
                if (row.version() == null || row.version().isBlank()) {
                    return false;
                }
                versions.add(row.version());
                if ("trimpEdwards".equals(metric)) {
                    if (!positive(row.referenceBpm())) {
                        return false;
                    }
                    references.add(row.referenceBpm());
                }
            }
        }
        return versions.size() <= 1 && references.size() <= 1;
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
