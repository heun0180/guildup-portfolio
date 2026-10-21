package io.github.heun0180.portfolio.settlement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import java.time.*;

@SpringBootApplication
public class SettlementExampleApplication {
    @Bean AdjustableClock clock() { return new AdjustableClock(Instant.parse("2030-01-01T00:00:00Z")); }
    @Bean FinishCheckpoint checkpoint() { return () -> { }; }
    public static void main(String[] args) {
        try (var context = SpringApplication.run(SettlementExampleApplication.class,args)) {
            var clock = context.getBean(AdjustableClock.class);
            var database = context.getBean(ExampleDatabase.class);
            var worker = context.getBean(SettlementWorker.class);
            var poller = context.getBean(SettlementPoller.class);
            long id = database.create(clock.instant().minusSeconds(1));
            worker.request(id);
            System.out.println("30분 전 poll: " + poller.tick());
            clock.advance(Duration.ofMinutes(30));
            System.out.println("30분 후 poll: " + poller.tick());
            System.out.println("최종 상태: " + database.read(id));
            System.out.println("중복 poll: " + poller.tick());
        }
    }
}
