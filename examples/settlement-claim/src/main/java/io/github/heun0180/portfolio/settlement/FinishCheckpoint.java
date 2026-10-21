package io.github.heun0180.portfolio.settlement;

/** SQL flush 후 장애를 주입하는 테스트 관측점. 기본 구현은 아무 작업도 하지 않는다. */
@FunctionalInterface
public interface FinishCheckpoint { void afterFlush(); }
