package io.github.heun0180.portfolio.settlement;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface RewardAccountRepository extends JpaRepository<RewardAccount, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from RewardAccount a where a.id = :id")
    Optional<RewardAccount> lockById(@Param("id") long id);
}
