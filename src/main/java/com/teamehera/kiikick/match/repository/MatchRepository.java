package com.teamehera.kiikick.match.repository;

import com.teamehera.kiikick.match.domain.Match;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchRepository extends JpaRepository<Match, UUID> {

    List<Match> findAllByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID userId);

    Optional<Match> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);

    Optional<Match> findByClientMatchId(UUID clientMatchId);
}
