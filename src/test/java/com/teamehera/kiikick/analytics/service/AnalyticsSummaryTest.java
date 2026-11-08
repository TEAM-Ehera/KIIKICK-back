package com.teamehera.kiikick.analytics.service;

import com.teamehera.kiikick.analytics.repository.AnalyticsRepository.QuarterData;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class AnalyticsSummaryTest {
    private static final UUID MATCH = UUID.randomUUID();

    @Test
    void averagesUseTheirActualDenominatorsAndMaximumsStayMaximums() {
        var rows = List.of(row("v1", 180.0, 10.0, 10.0, 100.0, 100.0),
                row("v1", 180.0, 90.0, 30.0, 900.0, 160.0));
        var result = AnalyticsSummary.from(rows);
        assertThat(result.matchCount()).isEqualTo(1);
        assertThat(result.goals()).isEqualTo(2);
        assertThat(result.metrics().get("averageSpeedMps").value()).isEqualTo(10);
        assertThat(result.metrics().get("averageHeartRateBpm").value()).isEqualTo(145);
        assertThat(result.metrics().get("maxSpeedMps").value()).isEqualTo(90);
    }

    @Test
    void missingDataTakesPrecedenceOverVersionMismatch() {
        var rows = List.of(row("v1", 180.0, 10.0, 10.0, 100.0, 100.0),
                new QuarterData(MATCH, null, null, null, null, "v2", Map.of(), null, null, null));
        var result = AnalyticsSummary.from(rows);
        assertThat(result.metrics().get("trimpEdwards").status()).isEqualTo("INCOMPLETE");
        assertThat(result.goals()).isNull();
        assertThat(result.meta().missingStatsQuarterCount()).isEqualTo(1);
    }

    @Test
    void versionAndReferencePoliciesAreCheckedAcrossComparisons() {
        var left = fold(row("v1", 180.0, 10.0, 10.0, 100.0, 100.0));
        var right = fold(row("v1", 190.0, 10.0, 10.0, 100.0, 100.0));
        assertThat(AnalyticsSummary.delta(left, right, "trimpEdwards")).isNull();
        assertThat(AnalyticsSummary.delta(left, right, "accelerationCount")).isZero();
        assertThat(AnalyticsSummary.delta(left, fold(row("v2", 180.0, 10.0, 10.0, 100.0, 100.0)),
                "accelerationCount")).isNull();
        assertThat(AnalyticsSummary.calculate(List.of(row("", 180.0, 10.0, 10.0, 100.0, 100.0)),
                "accelerationCount").status()).isEqualTo("POLICY_REQUIRED");
    }

    @Test
    void emptyAndNonFiniteResultsPreserveAvailabilityContracts() {
        assertThat(AnalyticsSummary.calculate(List.of(), "goals").value()).isZero();
        assertThat(AnalyticsSummary.calculate(List.of(), "maxSpeedMps").value()).isNull();
        var overflow = row("v1", 180.0, 1.0, 10.0, Double.MAX_VALUE, 100.0);
        assertThat(AnalyticsSummary.calculate(List.of(overflow, overflow), "totalDistanceM").status())
                .isEqualTo("POLICY_REQUIRED");
    }

    private static AnalyticsSummary.Accumulator fold(QuarterData row) {
        var fold = new AnalyticsSummary.Accumulator(); fold.accept(row); return fold;
    }

    private static QuarterData row(String version, Double reference, Double seconds, Double hrSeconds,
                                   Double distance, Double bpm) {
        return new QuarterData(MATCH, null, null, 1, 2, version,
                Map.of("averageSpeedMps", distance / seconds, "totalDistanceM", distance,
                        "averageHeartRateBpm", bpm, "maxSpeedMps", seconds,
                        "accelerationCount", 3.0, "trimpEdwards", 5.0), seconds, hrSeconds, reference);
    }
}
