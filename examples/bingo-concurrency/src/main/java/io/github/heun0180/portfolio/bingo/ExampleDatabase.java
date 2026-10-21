package io.github.heun0180.portfolio.bingo;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;
import static io.github.heun0180.portfolio.bingo.AggregationData.*;

/** 데모와 테스트의 합성 데이터 생성 및 별도 트랜잭션에서의 결과 재조회. */
@Service
public class ExampleDatabase {
    private final EventRepository events;
    private final ParticipantRepository participants;
    private final ProcessedMatchRepository processed;

    public ExampleDatabase(EventRepository events, ParticipantRepository participants, ProcessedMatchRepository processed) {
        this.events = events; this.participants = participants; this.processed = processed;
    }

    @Transactional
    public Fixture createFixture() {
        BingoEvent event = events.save(new BingoEvent(1));
        var first = participants.save(new BingoParticipant(event, "synthetic-a"));
        var second = participants.save(new BingoParticipant(event, "synthetic-b"));
        return new Fixture(event.getId(), first.getId(), second.getId());
    }

    @Transactional(readOnly = true)
    public State read(long eventId) {
        BingoEvent event = events.findById(eventId).orElseThrow();
        return new State(event.getVersion(), event.getPointsPerKill(),
                participants.findByEventIdOrderByIdAsc(eventId).stream().collect(Collectors.toMap(
                        BingoParticipant::getId, BingoParticipant::getProgress)),
                processed.findByEventId(eventId).stream().map(p -> new ProcessedKey(p.getParticipantId(), p.getMatchId()))
                        .collect(Collectors.toSet()));
    }

    public record Fixture(long eventId, long firstId, long secondId) { }
    public record State(long generation, int pointsPerKill, Map<Long, Long> progress, Set<ProcessedKey> processed) {
        public State { progress = Map.copyOf(progress); processed = Set.copyOf(processed); }
    }
}
