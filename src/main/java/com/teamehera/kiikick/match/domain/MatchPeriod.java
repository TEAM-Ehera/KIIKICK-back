package com.teamehera.kiikick.match.domain;

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
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(
        name = "match_periods",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_match_periods_match_period_no",
                columnNames = {"match_id", "period_no"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MatchPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "match_id", nullable = false, updatable = false)
    private Match match;

    @Column(name = "period_no")
    private Integer periodNo;

    @Column(name = "status")
    private String status;

    @Column(name = "attack_direction")
    private String attackDirection;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public MatchPeriod(
            Match match,
            Integer periodNo,
            String status,
            String attackDirection,
            LocalDateTime startedAt,
            LocalDateTime endedAt
    ) {
        this.match = match;
        this.periodNo = periodNo;
        this.status = status;
        this.attackDirection = attackDirection;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        match.addPeriod(this);
    }
}
