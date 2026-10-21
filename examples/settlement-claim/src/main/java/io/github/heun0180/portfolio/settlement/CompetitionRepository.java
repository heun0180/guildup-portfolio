package io.github.heun0180.portfolio.settlement;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.*;

public interface CompetitionRepository extends JpaRepository<Competition, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Competition c where c.id = :id")
    Optional<Competition> lockById(@Param("id") long id);

    @Query("select c.id from Competition c where c.status = :status and c.resultPublishAt <= :now order by c.resultPublishAt, c.id")
    List<Long> findDue(@Param("status") SettlementData.Status status, @Param("now") Instant now, Pageable page);
}
