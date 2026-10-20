package io.github.heun0180.portfolio.pubg;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 조회한 키의 만료 항목만 제거하는 작은 메모리 캐시. 크기 제한/LRU는 구현하지 않는다. */
final class TtlCache<K, V> implements ResultCache<K, V> {
    private final Clock clock;
    private final ConcurrentHashMap<K, Entry<V>> entries = new ConcurrentHashMap<>();

    TtlCache(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<V> get(K key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(entry.expiresAt())) {
            // 조회 중 다른 스레드가 새 값을 넣었다면 그 새 항목까지 지우지 않는다.
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    @Override
    public void put(K key, V value, Duration ttl) {
        Objects.requireNonNull(value, "value");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        entries.put(key, new Entry<>(value, clock.instant().plus(ttl)));
    }

    private record Entry<V>(V value, Instant expiresAt) { }
}
