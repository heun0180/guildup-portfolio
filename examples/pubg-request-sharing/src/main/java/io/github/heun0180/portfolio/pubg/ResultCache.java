package io.github.heun0180.portfolio.pubg;

import java.time.Duration;
import java.util.Optional;

/** 캐시 저장 방식과 요청 공유를 분리하는 내부 경계. V 자체는 null일 수 없다. */
interface ResultCache<K, V> {
    Optional<V> get(K key);

    void put(K key, V value, Duration ttl);
}
