package io.github.heun0180.portfolio.bingo;

import java.util.*;
import java.util.stream.Collectors;

/** 엔티티를 트랜잭션 밖으로 전달하지 않고 불변 입력만 수집 단계에 전달한다. */
public final class AggregationData {
    private AggregationData() { }

    public record ParticipantInput(long id, String accountCode) { }
    public record Inputs(int pointsPerKill, List<ParticipantInput> participants) {
        public Inputs { participants = List.copyOf(participants); }
    }
    /** participantId == null은 전체 집계, 그 외에는 해당 참가자만 집계한다. */
    public record Work(long eventId, long generation, Inputs inputs, Long participantId) { }
    public record MatchFact(String matchId, Map<String, Integer> killsByAccount) {
        public MatchFact {
            if (matchId == null || matchId.isBlank()) throw new IllegalArgumentException("blank match");
            killsByAccount = Map.copyOf(killsByAccount);
            if (killsByAccount.values().stream().anyMatch(v -> v < 0)) {
                throw new IllegalArgumentException("negative kills");
            }
        }
    }
    public record CollectionResult(Set<String> requestedIds, List<MatchFact> matches, Set<String> failedIds) {
        public CollectionResult {
            requestedIds = Set.copyOf(requestedIds);
            matches = List.copyOf(matches);
            failedIds = Set.copyOf(failedIds);
        }
        public boolean complete() {
            Set<String> actual = matches.stream().map(MatchFact::matchId).collect(Collectors.toSet());
            return failedIds.isEmpty() && actual.equals(requestedIds);
        }
        public static CollectionResult success(MatchFact... facts) {
            return new CollectionResult(Arrays.stream(facts).map(MatchFact::matchId).collect(Collectors.toSet()),
                    List.of(facts), Set.of());
        }
    }
    public record ProcessedKey(long participantId, String matchId) { }
}
