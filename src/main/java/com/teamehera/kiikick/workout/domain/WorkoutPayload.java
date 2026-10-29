package com.teamehera.kiikick.workout.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "`workoutPayloads`")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkoutPayload {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "`id`", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "`startDate`", nullable = false)
    private LocalDateTime startDate;

    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "`endDate`", nullable = false)
    private LocalDateTime endDate;

    @Column(name = "`totalCalories`", columnDefinition = "DOUBLE", nullable = false)
    private Double totalCalories;

    @Column(name = "`totalDistance`", columnDefinition = "DOUBLE", nullable = false)
    private Double totalDistance;

    @OneToMany(mappedBy = "workoutPayload")
    private List<TrackPoint> points = new ArrayList<>();

    @OneToMany(mappedBy = "workoutPayload")
    private List<HeartRateSample> heartRateSamples = new ArrayList<>();

    public WorkoutPayload(
            LocalDateTime startDate,
            LocalDateTime endDate,
            Double totalCalories,
            Double totalDistance
    ) {
        this.startDate = startDate;
        this.endDate = endDate;
        this.totalCalories = totalCalories;
        this.totalDistance = totalDistance;
    }

    void addTrackPoint(TrackPoint trackPoint) {
        points.add(trackPoint);
    }

    void addHeartRateSample(HeartRateSample heartRateSample) {
        heartRateSamples.add(heartRateSample);
    }

    public List<TrackPoint> getPoints() {
        return List.copyOf(points);
    }

    public List<HeartRateSample> getHeartRateSamples() {
        return List.copyOf(heartRateSamples);
    }
}
