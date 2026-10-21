package io.github.heun0180.portfolio.bingo;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AggregationService {
    private final PreparationService preparation;
    private final MatchCollector collector;
    private final CalculationService calculation;

    public AggregationService(PreparationService preparation, MatchCollector collector, CalculationService calculation) {
        this.preparation = preparation; this.collector = collector; this.calculation = calculation;
    }

    /** 상위 트랜잭션이 있으면 시작 전에 거부하여 수집이 DB 트랜잭션에 포함되지 않게 한다. */
    @Transactional(propagation = Propagation.NEVER)
    public int aggregate(long eventId, Long participantId) {
        var work = preparation.prepare(eventId, participantId);
        var collection = collector.collect(work);
        return calculation.apply(work, collection);
    }
}
