package io.github.heun0180.portfolio.bingo;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.LinkedHashMap;
import java.util.HashSet;
import static io.github.heun0180.portfolio.bingo.AggregationData.*;

@Service
public class CalculationService {
    private final EventRepository events;
    private final ParticipantRepository participants;
    private final ProcessedMatchRepository processed;
    private final AggregationGuard guard;
    private final ScoringPolicy policy;
    private final EntityManager em;

    public CalculationService(EventRepository events, ParticipantRepository participants,
            ProcessedMatchRepository processed, AggregationGuard guard, ScoringPolicy policy, EntityManager em) {
        this.events = events; this.participants = participants; this.processed = processed;
        this.guard = guard; this.policy = policy; this.em = em;
    }

    @Transactional
    public int apply(Work work, CollectionResult collection) {
        BingoEvent event = events.lockById(work.eventId()).orElseThrow();
        guard.verify(event, work);
        if (!collection.complete()) {
            throw new RejectedAggregation(RejectedAggregation.Reason.INCOMPLETE);
        }
        var unique = new LinkedHashMap<String, MatchFact>();
        for (MatchFact fact : collection.matches()) {
            MatchFact previous = unique.putIfAbsent(fact.matchId(), fact);
            if (previous != null && !previous.equals(fact)) {
                throw new IllegalArgumentException("conflicting duplicate match");
            }
        }
        int inserted = 0;
        // 입력은 ID 오름차순이다. 모든 계산은 이벤트 → 참가자 순서로 잠근다.
        for (ParticipantInput input : work.inputs().participants()) {
            if (work.participantId() != null && input.id() != work.participantId()) continue;
            BingoParticipant participant = participants.lockById(work.eventId(), input.id()).orElseThrow();
            em.refresh(participant);
            var seen = new HashSet<>(processed.findMatchIds(work.eventId(), input.id()));
            for (MatchFact fact : unique.values()) {
                Integer kills = fact.killsByAccount().get(input.accountCode());
                if (kills == null || !seen.add(fact.matchId())) continue;
                participant.addProgress(policy.contribution(input.accountCode(), kills, work.inputs().pointsPerKill()));
                processed.save(new ProcessedMatch(event, participant, fact.matchId()));
                inserted++;
            }
            // SQL 실행 후 발생한 예외도 롤백되는지 테스트에서 관측할 수 있도록 flush한다.
            em.flush();
        }
        return inserted;
    }
}
