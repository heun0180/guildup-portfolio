package io.github.heun0180.portfolio.bingo;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ParticipantRepository extends JpaRepository<BingoParticipant, Long> {
    List<BingoParticipant> findByEventIdOrderByIdAsc(long eventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from BingoParticipant p where p.event.id = :eventId and p.id = :id")
    Optional<BingoParticipant> lockById(@Param("eventId") long eventId, @Param("id") long id);
}
