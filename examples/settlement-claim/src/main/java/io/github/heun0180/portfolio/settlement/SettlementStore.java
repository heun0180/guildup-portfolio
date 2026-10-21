package io.github.heun0180.portfolio.settlement;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import java.time.*;
import java.util.*;
import static io.github.heun0180.portfolio.settlement.SettlementData.*;
import static io.github.heun0180.portfolio.settlement.RejectedClaim.Reason.*;

@Service
public class SettlementStore {
    public static final Duration PUBLICATION_DELAY = Duration.ofMinutes(30);
    public static final Duration RECLAIM_AFTER = Duration.ofMinutes(10);
    private final CompetitionRepository competitions;
    private final MatchResultRepository results;
    private final RewardService rewards;
    private final Clock clock;
    private final EntityManager em;
    private final FinishCheckpoint checkpoint;

    public SettlementStore(CompetitionRepository competitions, MatchResultRepository results,
            RewardService rewards, Clock clock, EntityManager em, FinishCheckpoint checkpoint) {
        this.competitions = competitions; this.results = results; this.rewards = rewards;
        this.clock = clock; this.em = em; this.checkpoint = checkpoint;
    }

    @Transactional
    public void request(long id) {
        Competition c = competitions.lockById(id).orElseThrow();
        Instant now = clock.instant();
        if (c.getStatus() != Status.IN_PROGRESS) throw new RejectedClaim(STATE);
        if (now.isBefore(c.getEndsAt())) throw new RejectedClaim(NOT_ENDED);
        c.request(now, now.plus(PUBLICATION_DELAY));
    }

    @Transactional(readOnly = true)
    public List<Long> dueIds() { return competitions.findDue(Status.RESULT_PENDING, clock.instant(), PageRequest.of(0, 20)); }

    @Transactional
    public Work claim(long id) {
        Competition c = competitions.lockById(id).orElse(null);
        Instant now = clock.instant();
        if (c == null || c.getStatus() != Status.RESULT_PENDING || c.getResultPublishAt() == null
                || now.isBefore(c.getResultPublishAt())) return null;
        if (c.getFinalizationStartedAt() != null && now.isBefore(c.getFinalizationStartedAt().plus(RECLAIM_AFTER))) return null;
        UUID token = UUID.randomUUID();
        c.claim(now, token);
        return new Work(id, now, token);
    }

    @Transactional
    public void finish(Work work, Snapshot snapshot) {
        Competition c = competitions.lockById(work.competitionId()).orElseThrow();
        if (c.getStatus() != Status.RESULT_PENDING) throw new RejectedClaim(STATE);
        if (!c.owns(work.token())) throw new RejectedClaim(OWNERSHIP);
        // 경과 시간만으로 거부하지 않는다. 현재 UUID가 같은지를 검사한다.
        var unique = new LinkedHashMap<String, MatchPoints>();
        for (MatchPoints match : snapshot.matches()) {
            MatchPoints previous = unique.putIfAbsent(match.matchId(), match);
            if (previous != null && !previous.equals(match)) throw new IllegalArgumentException("conflicting synthetic match");
        }
        long total = 0;
        for (MatchPoints match : unique.values()) {
            total = Math.addExact(total, match.points());
            results.save(new MatchResultEntity(c, match));
        }
        rewards.credit(c); // REQUIRED: 최종 저장과 같은 물리 트랜잭션에 참여한다.
        c.complete(total, clock.instant());
        em.flush();
        checkpoint.afterFlush();
    }

    @Transactional
    public boolean recordFailure(Work work) {
        Competition c = competitions.lockById(work.competitionId()).orElse(null);
        if (c == null || !c.owns(work.token())) return false;
        c.fail();
        return true;
    }
}
