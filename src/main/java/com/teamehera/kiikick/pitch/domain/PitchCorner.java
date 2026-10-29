package com.teamehera.kiikick.pitch.domain;

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
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(
        name = "pitch_corners",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_pitch_corners_pitch_order",
                        columnNames = {"pitch_id", "corner_order"}
                ),
                @UniqueConstraint(
                        name = "uk_pitch_corners_pitch_role",
                        columnNames = {"pitch_id", "corner_role"}
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PitchCorner {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", columnDefinition = "BINARY(16)", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pitch_id", nullable = false, updatable = false)
    private Pitch pitch;

    @Column(name = "corner_order", columnDefinition = "TINYINT")
    private Byte cornerOrder;

    @Column(name = "corner_role")
    private String cornerRole;

    @Column(name = "latitude")
    private BigDecimal latitude;

    @Column(name = "longitude")
    private BigDecimal longitude;

    public PitchCorner(
            Pitch pitch,
            Byte cornerOrder,
            String cornerRole,
            BigDecimal latitude,
            BigDecimal longitude
    ) {
        this.pitch = pitch;
        this.cornerOrder = cornerOrder;
        this.cornerRole = cornerRole;
        this.latitude = latitude;
        this.longitude = longitude;
        pitch.addCorner(this);
    }

    public void changeCoordinates(BigDecimal latitude, BigDecimal longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }
}
