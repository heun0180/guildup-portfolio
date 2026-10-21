package io.github.heun0180.portfolio.settlement;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MatchResultRepository extends JpaRepository<MatchResultEntity, Long> {
    List<MatchResultEntity> findByCompetitionIdOrderByMatchId(long competitionId);
}
