package com.teamehera.kiikick.workout.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(
        name = "`trackPoints`",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_track_points_payload_seq",
                columnNames = {"`workoutPayloadId`", "`sampleSeq`"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TrackPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "`id`", nullable = false, updatable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "`workoutPayloadId`", nullable = false, updatable = false)
    private WorkoutPayload workoutPayload;

    @Column(name = "`sampleSeq`", nullable = false, updatable = false)
    private Integer sampleSeq;

    @Column(name = "`latitude`", columnDefinition = "DOUBLE", nullable = false)
    private Double latitude;

    @Column(name = "`longitude`", columnDefinition = "DOUBLE", nullable = false)
    private Double longitude;

    @Column(name = "`altitude`", columnDefinition = "DOUBLE", nullable = false)
    private Double altitude;

    @Column(name = "`horizontalAccuracy`", columnDefinition = "DOUBLE", nullable = false)
    private Double horizontalAccuracy;

    @Column(name = "`speed`", columnDefinition = "DOUBLE", nullable = false)
    private Double speed;

    @Column(name = "`course`", columnDefinition = "DOUBLE", nullable = false)
    private Double course;

    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "`timestamp`", nullable = false)
    private LocalDateTime timestamp;

    public TrackPoint(
            WorkoutPayload workoutPayload,
            Integer sampleSeq,
            Double latitude,
            Double longitude,
            Double altitude,
            Double horizontalAccuracy,
            Double speed,
            Double course,
            LocalDateTime timestamp
    ) {
        this.workoutPayload = workoutPayload;
        this.sampleSeq = sampleSeq;
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitude = altitude;
        this.horizontalAccuracy = horizontalAccuracy;
        this.speed = speed;
        this.course = course;
        this.timestamp = timestamp;
        workoutPayload.addTrackPoint(this);
    }
}
