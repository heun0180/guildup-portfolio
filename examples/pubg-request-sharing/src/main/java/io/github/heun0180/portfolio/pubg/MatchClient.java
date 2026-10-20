package io.github.heun0180.portfolio.pubg;

import java.util.Optional;

@FunctionalInterface
public interface MatchClient {
    /**
     * 존재하는 경기는 값, 404에 해당하는 응답만 Optional.empty()로 표현한다.
     * 통신 오류, 429, 5xx 등을 empty로 바꾸지 않는다. 실제 HTTP 구현은 포함하지 않는다.
     * 서비스가 병렬 호출하므로 구현은 thread-safe해야 한다.
     */
    Optional<Match> fetch(MatchKey key) throws Exception;
}
