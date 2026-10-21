package io.github.heun0180.portfolio.bingo;

/** 테스트에서는 두 번째 참가자 계산 시 예외를 발생시키는 구현으로 교체한다. */
@FunctionalInterface
public interface ScoringPolicy {
    long contribution(String accountCode, int kills, int pointsPerKill);
}
