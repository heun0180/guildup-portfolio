package io.github.heun0180.portfolio.bingo;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.github.heun0180.portfolio.bingo.AggregationData.Work;

@Service
public class PreparationService {
    private final EventRepository events;
    private final AggregationGuard guard;

    public PreparationService(EventRepository events, AggregationGuard guard) {
        this.events = events;
        this.guard = guard;
    }

    @Transactional
    public Work prepare(long eventId, Long participantId) {
        BingoEvent event = events.lockById(eventId).orElseThrow();
        return guard.claim(event, participantId);
    }
}
