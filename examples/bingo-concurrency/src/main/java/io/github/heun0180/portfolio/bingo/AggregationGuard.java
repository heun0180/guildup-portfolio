package io.github.heun0180.portfolio.bingo;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import static io.github.heun0180.portfolio.bingo.AggregationData.*;

@Service
public class AggregationGuard {
    private final EntityManager em;
    private final ParticipantRepository participants;

    public AggregationGuard(EntityManager em, ParticipantRepository participants) {
        this.em = em;
        this.participants = participants;
    }

    public Work claim(BingoEvent event, Long participantId) {
        // 호출한 준비 서비스가 이미 이벤트 행을 잠근 상태다.
        em.flush();
        em.refresh(event);
        em.lock(event, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        Inputs inputs = snapshot(event);
        if (participantId != null && inputs.participants().stream().noneMatch(p -> p.id() == participantId)) {
            throw new IllegalArgumentException("participant does not belong to event");
        }
        return new Work(event.getId(), event.getVersion(), inputs, participantId);
    }

    public void verify(BingoEvent event, Work work) {
        if (event.getVersion() != work.generation()) {
            throw new RejectedAggregation(RejectedAggregation.Reason.GENERATION);
        }
        if (!snapshot(event).equals(work.inputs())) {
            throw new RejectedAggregation(RejectedAggregation.Reason.INPUTS);
        }
    }

    private Inputs snapshot(BingoEvent event) {
        return new Inputs(event.getPointsPerKill(), participants.findByEventIdOrderByIdAsc(event.getId()).stream()
                .map(p -> new ParticipantInput(p.getId(), p.getAccountCode())).toList());
    }
}
