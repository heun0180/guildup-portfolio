package io.github.heun0180.portfolio.bingo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BingoExampleApplication {
    public static void main(String[] args) {
        // 웹 서버 없이 합성 데이터로 두 번 집계한 뒤 H2와 Spring context를 닫는다.
        try (var context = SpringApplication.run(BingoExampleApplication.class, args)) {
            var database = context.getBean(ExampleDatabase.class);
            var aggregation = context.getBean(AggregationService.class);
            var fixture = database.createFixture();
            aggregation.aggregate(fixture.eventId(), null);
            System.out.println("첫 집계: " + database.read(fixture.eventId()));
            aggregation.aggregate(fixture.eventId(), null);
            System.out.println("같은 경기 재집계: " + database.read(fixture.eventId()));
        }
    }
}
