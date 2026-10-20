package io.github.heun0180.portfolio.pubg;

import java.util.Objects;

/** 플랫폼과 경기 ID를 함께 식별한다. 호출자는 정규화된 shard 이름을 전달한다. */
public record MatchKey(String shard, String matchId) {
    public MatchKey {
        Objects.requireNonNull(shard, "shard");
        Objects.requireNonNull(matchId, "matchId");
        if (shard.isBlank() || matchId.isBlank()) {
            throw new IllegalArgumentException("shard and matchId must not be blank");
        }
    }
}
