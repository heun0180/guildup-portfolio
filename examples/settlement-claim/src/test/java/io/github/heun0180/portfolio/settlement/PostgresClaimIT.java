package io.github.heun0180.portfolio.settlement;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.time.OffsetDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.heun0180.portfolio.settlement.SettlementData.*;

/** postgres 프로필과 폐기 가능한 로컬 DB 실행 스크립트에서만 선택한다. */
class PostgresClaimIT extends SettlementContract {
    @DynamicPropertySource static void disposablePostgres(DynamicPropertyRegistry registry) {
        String port = System.getenv("SETTLEMENT_TEST_PG_PORT");
        String database = System.getenv("SETTLEMENT_TEST_PG_DATABASE");
        if (port == null || !port.matches("[0-9]{1,5}") || Integer.parseInt(port)<1 || Integer.parseInt(port)>65535
                || database == null || !database.matches("settlement_example_[a-f0-9]{32}")) {
            throw new IllegalStateException("Run scripts/verify-postgres.py against its disposable local cluster");
        }
        registry.add("spring.datasource.url",() -> "jdbc:postgresql://127.0.0.1:"+port+"/"+database);
        registry.add("spring.datasource.username",() -> "example_runner");
        registry.add("spring.datasource.driver-class-name",() -> "org.postgresql.Driver");
    }

    @Test void postgresRoundsTimestampButUuidStillOwnsWork() {
        due();
        clock.set(clock.instant().plusNanos(123456789));
        Work work = store.claim(id);
        var saved = jdbc.queryForObject("select finalization_started_at from competition where id = ?",
                (row,index) -> row.getObject(1,OffsetDateTime.class).toInstant(),id);
        assertNotEquals(work.claimAt(),saved);
        assertEquals(0,saved.getNano()%1000);
        assertEquals(work.token(),state().token());
        System.out.println("PG_PRECISION input="+work.claimAt()+" stored="+saved+" uuidEqual=true");
        store.finish(work,Snapshot.sample());
        assertCompleted(7,"synthetic-match");
        assertNull(state().token());assertNull(state().claimAt());
    }
}
