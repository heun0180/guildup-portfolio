package io.github.heun0180.portfolio.bingo;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Set;

public interface ProcessedMatchRepository extends JpaRepository<ProcessedMatch, Long> {
    @Query("select m.matchId from ProcessedMatch m where m.event.id = :eventId and m.participant.id = :participantId")
    Set<String> findMatchIds(@Param("eventId") long eventId, @Param("participantId") long participantId);

    List<ProcessedMatch> findByEventId(long eventId);
}
