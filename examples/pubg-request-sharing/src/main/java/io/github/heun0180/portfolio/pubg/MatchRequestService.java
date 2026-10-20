package io.github.heun0180.portfolio.pubg;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** JVM 안의 한 인스턴스에서 동일 키의 진행 중 요청을 공유한다. */
public final class MatchRequestService implements AutoCloseable {
    private final MatchClient client;
    private final ResultCache<MatchKey, Optional<Match>> cache;
    private final Duration successTtl;
    private final Duration notFoundTtl;
    private final ConcurrentHashMap<MatchKey, CompletableFuture<Optional<Match>>> inFlight =
            new ConcurrentHashMap<>();
    private final Semaphore permits;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean closed = new AtomicBoolean();

    public MatchRequestService(MatchClient client, Clock clock, Duration successTtl,
                               Duration notFoundTtl, int maxConcurrentCalls) {
        this(client, new TtlCache<>(clock), successTtl, notFoundTtl, maxConcurrentCalls);
    }

    MatchRequestService(MatchClient client, ResultCache<MatchKey, Optional<Match>> cache,
                        Duration successTtl, Duration notFoundTtl, int maxConcurrentCalls) {
        this.client = Objects.requireNonNull(client, "client");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.successTtl = positive(successTtl);
        this.notFoundTtl = positive(notFoundTtl);
        if (maxConcurrentCalls < 1) {
            throw new IllegalArgumentException("maxConcurrentCalls must be positive");
        }
        this.permits = new Semaphore(maxConcurrentCalls, true);
    }

    public CompletableFuture<Optional<Match>> find(MatchKey key) {
        Objects.requireNonNull(key, "key");
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("service is closed"));
        }
        Optional<Optional<Match>> cached = cache.get(key);
        if (cached.isPresent()) {
            return CompletableFuture.completedFuture(cached.get());
        }

        CompletableFuture<Optional<Match>> owner = new CompletableFuture<>();
        CompletableFuture<Optional<Match>> existing = inFlight.putIfAbsent(key, owner);
        if (existing != null) {
            return existing.copy();
        }

        try {
            // 첫 조회 이후 이전 작업이 캐시를 채우고 inFlight에서 사라졌을 수 있다.
            Optional<Optional<Match>> rechecked = cache.get(key);
            if (rechecked.isPresent()) {
                finish(key, owner, rechecked.get(), null);
            } else {
                CompletableFuture.supplyAsync(() -> fetchAndCache(key), workers)
                        .whenComplete((value, failure) -> finish(key, owner, value, failure));
            }
        } catch (RuntimeException | Error failure) {
            // 예: 종료와의 경쟁으로 executor가 작업 접수를 거부한 경우.
            finish(key, owner, null, failure);
        }
        // 호출자가 cancel/complete/orTimeout해도 내부 공유 Future는 변경되지 않는다.
        return owner.copy();
    }

    private Optional<Match> fetchAndCache(MatchKey key) {
        boolean acquired = false;
        try {
            permits.acquire();
            acquired = true;
            Optional<Match> result = Objects.requireNonNull(client.fetch(key), "client result");
            cache.put(key, result, result.isPresent() ? successTtl : notFoundTtl);
            return result;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new CompletionException(failure);
        } catch (Exception failure) {
            throw new CompletionException(failure);
        } finally {
            if (acquired) {
                permits.release();
            }
        }
    }

    private void finish(MatchKey key, CompletableFuture<Optional<Match>> owner,
                        Optional<Match> value, Throwable failure) {
        // 성공 캐시는 이미 저장됨. 실패도 제거 후 통지해 다음 호출이 즉시 재시도할 수 있다.
        inFlight.remove(key, owner);
        if (failure == null) {
            owner.complete(value);
        } else {
            while (failure instanceof CompletionException && failure.getCause() != null) {
                failure = failure.getCause();
            }
            owner.completeExceptionally(failure);
        }
    }

    int inFlightCount() {
        return inFlight.size();
    }

    @Override
    public void close() {
        closed.set(true);
        workers.close();
    }

    private static Duration positive(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        return ttl;
    }
}
