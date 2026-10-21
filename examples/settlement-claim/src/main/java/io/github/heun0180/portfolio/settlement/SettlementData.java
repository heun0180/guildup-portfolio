package io.github.heun0180.portfolio.settlement;

import java.time.Instant;
import java.util.*;

public final class SettlementData {
    private SettlementData() { }
    public enum Status { IN_PROGRESS, RESULT_PENDING, COMPLETED }
    /** claimAt은 재시도 설명용 시각이며 소유권 동등 비교에 사용하지 않는다. */
    public record Work(long competitionId, Instant claimAt, UUID token) { }
    public record MatchPoints(String matchId, long points) {
        public MatchPoints {
            if (matchId == null || matchId.isBlank() || points < 0) throw new IllegalArgumentException("invalid synthetic result");
        }
    }
    public record Snapshot(List<MatchPoints> matches) {
        public Snapshot { matches = List.copyOf(matches); }
        public static Snapshot sample() { return new Snapshot(List.of(new MatchPoints("synthetic-match", 7))); }
    }
    public record PollReport(int completed, int failed, int notClaimed) { }
}
