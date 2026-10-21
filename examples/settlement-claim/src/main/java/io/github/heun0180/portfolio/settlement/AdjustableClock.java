package io.github.heun0180.portfolio.settlement;

import java.time.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** 데모와 테스트의 논리 시계. 실제 30분을 기다리지 않는다. */
public final class AdjustableClock extends Clock {
    private final AtomicReference<Instant> time;
    private final ZoneId zone;

    public AdjustableClock(Instant initial) { this(new AtomicReference<>(initial), ZoneOffset.UTC); }
    private AdjustableClock(AtomicReference<Instant> time, ZoneId zone) { this.time = time; this.zone = zone; }
    public void set(Instant instant) { time.set(Objects.requireNonNull(instant)); }
    public void advance(Duration duration) { time.updateAndGet(value -> value.plus(duration)); }
    @Override public Instant instant() { return time.get(); }
    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId zone) { return new AdjustableClock(time, Objects.requireNonNull(zone)); }
}
