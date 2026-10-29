package com.teamehera.kiikick.workout.repository;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.teamehera.kiikick.workout.domain.HeartRateSample;
import com.teamehera.kiikick.workout.domain.TrackPoint;
import com.teamehera.kiikick.workout.domain.WorkoutPayload;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
class WorkoutPayloadRepositoryTest {

    @Autowired
    private WorkoutPayloadRepository workoutPayloadRepository;

    @Autowired
    private TrackPointRepository trackPointRepository;

    @Autowired
    private HeartRateSampleRepository heartRateSampleRepository;

    @Autowired
    private DataSource dataSource;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void savesPayloadAndReturnsSamplesInSampleSequenceOrder() {
        LocalDateTime startDate = LocalDateTime.parse("2024-10-07T13:20:00.123456");
        WorkoutPayload payload = workoutPayloadRepository.saveAndFlush(
                new WorkoutPayload(startDate, startDate.plusMinutes(10), 420.5, 3182.4)
        );
        trackPointRepository.saveAllAndFlush(List.of(
                trackPoint(payload, 1, 37.2, 2.0, 90.0),
                trackPoint(payload, 0, 37.1, -1.0, -1.0)
        ));
        heartRateSampleRepository.saveAllAndFlush(List.of(
                heartRateSample(payload, 1, 145.0),
                heartRateSample(payload, 0, 142.0)
        ));

        entityManager.clear();

        WorkoutPayload foundPayload = workoutPayloadRepository.findById(payload.getId()).orElseThrow();
        List<TrackPoint> foundPoints = trackPointRepository
                .findAllByWorkoutPayloadIdOrderBySampleSeqAsc(payload.getId());
        List<HeartRateSample> foundHeartRates = heartRateSampleRepository
                .findAllByWorkoutPayloadIdOrderBySampleSeqAsc(payload.getId());

        assertAll(
                () -> assertEquals(startDate, foundPayload.getStartDate()),
                () -> assertEquals(List.of(0, 1), foundPoints.stream().map(TrackPoint::getSampleSeq).toList()),
                () -> assertEquals(-1.0, foundPoints.getFirst().getSpeed()),
                () -> assertEquals(-1.0, foundPoints.getFirst().getCourse()),
                () -> assertEquals(
                        List.of(0, 1),
                        foundHeartRates.stream().map(HeartRateSample::getSampleSeq).toList()
                )
        );
    }

    @Test
    void mapsQuotedTableAndColumnNamesAsExactCamelCaseInH2() throws Exception {
        try (var connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();

            assertAll(
                    () -> assertTrue(tableExists(metadata, "workoutPayloads")),
                    () -> assertTrue(tableExists(metadata, "trackPoints")),
                    () -> assertTrue(tableExists(metadata, "heartRateSamples")),
                    () -> assertTrue(columns(metadata, "workoutPayloads").containsAll(Set.of(
                            "id", "startDate", "endDate", "totalCalories", "totalDistance"
                    ))),
                    () -> assertTrue(columns(metadata, "trackPoints").containsAll(Set.of(
                            "id", "workoutPayloadId", "sampleSeq", "latitude", "longitude",
                            "altitude", "horizontalAccuracy", "speed", "course", "timestamp"
                    ))),
                    () -> assertTrue(columns(metadata, "heartRateSamples").containsAll(Set.of(
                            "id", "workoutPayloadId", "sampleSeq", "timestamp", "bpm"
                    ))),
                    () -> assertEquals(Types.TIMESTAMP, columnType(metadata, "workoutPayloads", "startDate")),
                    () -> assertEquals(Types.TIMESTAMP, columnType(metadata, "trackPoints", "timestamp")),
                    () -> assertEquals(Types.TIMESTAMP, columnType(metadata, "heartRateSamples", "timestamp")),
                    () -> assertEquals(Types.BIGINT, columnType(metadata, "trackPoints", "id")),
                    () -> assertEquals(Types.BIGINT, columnType(metadata, "heartRateSamples", "id"))
            );
        }
    }

    @Test
    void rejectsDuplicateSampleSequenceWithinSamePayload() {
        LocalDateTime startDate = LocalDateTime.parse("2024-10-07T13:20:00");
        WorkoutPayload payload = workoutPayloadRepository.saveAndFlush(
                new WorkoutPayload(startDate, startDate.plusMinutes(10), 420.5, 3182.4)
        );

        trackPointRepository.saveAndFlush(trackPoint(payload, 0, 37.1, -1.0, -1.0));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> trackPointRepository.saveAndFlush(trackPoint(payload, 0, 37.2, 2.0, 90.0))
        );
    }

    private boolean tableExists(DatabaseMetaData metadata, String tableName) throws Exception {
        try (ResultSet tables = metadata.getTables(null, null, tableName, new String[]{"TABLE"})) {
            return tables.next();
        }
    }

    private Set<String> columns(DatabaseMetaData metadata, String tableName) throws Exception {
        Set<String> columnNames = new HashSet<>();
        try (ResultSet columns = metadata.getColumns(null, null, tableName, null)) {
            while (columns.next()) {
                columnNames.add(columns.getString("COLUMN_NAME"));
            }
        }
        return columnNames;
    }

    private int columnType(DatabaseMetaData metadata, String tableName, String columnName) throws Exception {
        try (ResultSet columns = metadata.getColumns(null, null, tableName, columnName)) {
            assertTrue(columns.next());
            return columns.getInt("DATA_TYPE");
        }
    }

    private TrackPoint trackPoint(
            WorkoutPayload payload,
            int sampleSeq,
            double latitude,
            double speed,
            double course
    ) {
        return new TrackPoint(
                payload,
                sampleSeq,
                latitude,
                127.1,
                20.1,
                4.8,
                speed,
                course,
                payload.getStartDate().plusSeconds(sampleSeq)
        );
    }

    private HeartRateSample heartRateSample(WorkoutPayload payload, int sampleSeq, double bpm) {
        return new HeartRateSample(
                payload,
                sampleSeq,
                payload.getStartDate().plusSeconds(sampleSeq),
                bpm
        );
    }
}
