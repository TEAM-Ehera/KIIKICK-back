package com.teamehera.kiikick.match.repository;

import com.teamehera.kiikick.match.domain.MatchPeriod;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchPeriodRepository extends JpaRepository<MatchPeriod, UUID> {

    List<MatchPeriod> findAllByMatchIdOrderByPeriodNoAsc(UUID matchId);
}
