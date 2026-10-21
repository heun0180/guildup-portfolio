package io.github.heun0180.portfolio.settlement;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RewardEntryRepository extends JpaRepository<RewardEntry, Long> {
    boolean existsByCompetitionId(long competitionId);
    long countByCompetitionId(long competitionId);
}
