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
        name = "`heartRateSamples`",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_heart_rate_samples_payload_seq",
                columnNames = {"`workoutPayloadId`", "`sampleSeq`"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HeartRateSample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "`id`", nullable = false, updatable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "`workoutPayloadId`", nullable = false, updatable = false)
    private WorkoutPayload workoutPayload;

    @Column(name = "`sampleSeq`", nullable = false, updatable = false)
    private Integer sampleSeq;

    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "`timestamp`", nullable = false)
    private LocalDateTime timestamp;

    @Column(name = "`bpm`", columnDefinition = "DOUBLE", nullable = false)
    private Double bpm;

    public HeartRateSample(
            WorkoutPayload workoutPayload,
            Integer sampleSeq,
            LocalDateTime timestamp,
            Double bpm
    ) {
        this.workoutPayload = workoutPayload;
        this.sampleSeq = sampleSeq;
        this.timestamp = timestamp;
        this.bpm = bpm;
        workoutPayload.addHeartRateSample(this);
    }
}
