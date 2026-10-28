package com.teamehera.kiikick.pitch.domain;

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
        name = "pitches",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_pitches_client_pitch_id",
                columnNames = "client_pitch_id"
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Pitch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "client_pitch_id", columnDefinition = "BINARY(16)", updatable = false)
    private UUID clientPitchId;

    @Column(name = "name")
    private String name;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @OneToMany(mappedBy = "pitch")
    private List<PitchCorner> corners = new ArrayList<>();

    public Pitch(User user, UUID clientPitchId, String name) {
        this.user = user;
        this.clientPitchId = clientPitchId;
        this.name = name;
    }

    public void changeName(String name) {
        this.name = name;
    }

    public void delete() {
        this.deletedAt = LocalDateTime.now();
    }

    public List<PitchCorner> getCorners() {
        return List.copyOf(corners);
    }

    void addCorner(PitchCorner corner) {
        corners.add(corner);
    }
}
