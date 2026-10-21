package io.github.heun0180.portfolio.bingo;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface EventRepository extends JpaRepository<BingoEvent, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from BingoEvent e where e.id = :id")
    Optional<BingoEvent> lockById(@Param("id") long id);
}
