package com.teamehera.kiikick.match.domain;

import com.teamehera.kiikick.pitch.domain.Pitch;
import com.teamehera.kiikick.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
        name = "matches",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_matches_client_match_id",
                columnNames = "client_match_id"
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Match {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "client_match_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID clientMatchId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pitch_id", nullable = false, updatable = false)
    private Pitch pitch;

    @Column(name = "match_format")
    private String matchFormat;

    @Column(name = "status")
    private String status;

    @Column(name = "scheduled_at")
    private LocalDateTime scheduledAt;

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

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @OneToMany(mappedBy = "match")
    private List<MatchPeriod> periods = new ArrayList<>();

    public Match(
            User user,
            UUID clientMatchId,
            Pitch pitch,
            String matchFormat,
            String status,
            LocalDateTime scheduledAt
    ) {
        this.user = user;
        this.clientMatchId = clientMatchId;
        this.pitch = pitch;
        this.matchFormat = matchFormat;
        this.status = status;
        this.scheduledAt = scheduledAt;
    }

    public void delete() {
        this.deletedAt = LocalDateTime.now();
    }

    public List<MatchPeriod> getPeriods() {
        return List.copyOf(periods);
    }

    void addPeriod(MatchPeriod period) {
        periods.add(period);
    }
}
