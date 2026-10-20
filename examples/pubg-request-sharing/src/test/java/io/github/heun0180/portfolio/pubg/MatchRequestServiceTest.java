package io.github.heun0180.portfolio.pubg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class MatchRequestServiceTest {
    private static final MatchKey KEY = new MatchKey("kakao", "synthetic-match");
    private static final Duration SUCCESS_TTL = Duration.ofSeconds(30);
    private static final Duration NOT_FOUND_TTL = Duration.ofSeconds(5);
    private static final Match MATCH = new Match(KEY.matchId(), "synthetic-squad");

    @Test
    void twoCallerThreadsShareOneInFlightClientCall() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MatchClient client = key -> {
            calls.incrementAndGet();
            entered.countDown();
            await(release);
            return Optional.of(MATCH);
        };
        try (var service = service(client, new MutableClock(), 2);
             var callers = Executors.newFixedThreadPool(2)) {
            try {
                var first = result(callers.submit(() -> service.find(KEY)));
                await(entered);
                // find가 반환됐으므로 B는 이미 공유 Future에 연결됨. Client는 아직 대기 중.
                var second = result(callers.submit(() -> service.find(KEY)));
                assertFalse(first.isDone());
                assertFalse(second.isDone());
                assertEquals(1, service.inFlightCount());
                assertEquals(1, calls.get());
                release.countDown();
                assertSame(MATCH, result(first).orElseThrow());
                assertSame(MATCH, result(second).orElseThrow());
                assertEquals(1, calls.get());
                assertEquals(0, service.inFlightCount());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void distinctMatchesRespectSemaphoreLimitAcrossAllRequests() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch firstTwoEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        MatchClient client = key -> {
            calls.incrementAndGet();
            int current = active.incrementAndGet();
            peak.accumulateAndGet(current, Math::max);
            try {
                firstTwoEntered.countDown();
                await(release);
                return Optional.of(new Match(key.matchId(), "synthetic-squad"));
            } finally {
                active.decrementAndGet();
            }
        };
        try (var service = service(client, new MutableClock(), 2)) {
            try {
                List<CompletableFuture<Optional<Match>>> requests = IntStream.range(0, 8)
                        .mapToObj(i -> service.find(new MatchKey("kakao", "synthetic-" + i)))
                        .toList();
                await(firstTwoEntered);
                assertEquals(2, active.get());
                assertEquals(2, calls.get());
                assertTrue(requests.stream().noneMatch(CompletableFuture::isDone));
                release.countDown();
                for (var request : requests) {
                    assertTrue(result(request).isPresent());
                }
                assertEquals(8, calls.get());
                assertEquals(2, peak.get());
                assertEquals(0, active.get());
                assertEquals(0, service.inFlightCount());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void sameMatchIdOnDifferentShardsUsesIndependentFlightsAndCacheEntries() throws Exception {
        Set<MatchKey> seen = ConcurrentHashMap.newKeySet();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        MatchKey steamKey = new MatchKey("steam", KEY.matchId());
        MatchClient client = key -> {
            calls.incrementAndGet();
            seen.add(key);
            bothEntered.countDown();
            await(release);
            return Optional.of(new Match(key.matchId(), "synthetic-" + key.shard()));
        };
        try (var service = service(client, new MutableClock(), 2)) {
            try {
                var kakao = service.find(KEY);
                var steam = service.find(steamKey);
                await(bothEntered);
                release.countDown();
                assertEquals("synthetic-kakao", result(kakao).orElseThrow().mode());
                assertEquals("synthetic-steam", result(steam).orElseThrow().mode());
                assertEquals(result(kakao), result(service.find(KEY)));
                assertEquals(result(steam), result(service.find(steamKey)));
                assertEquals(Set.of(KEY, steamKey), seen);
                assertEquals(2, calls.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void successfulCacheHitDoesNotCallClientAgain() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var service = service(key -> {
            calls.incrementAndGet();
            return Optional.of(MATCH);
        }, new MutableClock(), 1)) {
            assertEquals(Optional.of(MATCH), result(service.find(KEY)));
            assertEquals(Optional.of(MATCH), result(service.find(KEY)));
            assertEquals(1, calls.get());
        }
    }

    @ParameterizedTest(name = "TTL: found={0}")
    @ValueSource(booleans = {true, false})
    void successAndNotFoundExpireAtTheirOwnTtl(boolean found) throws Exception {
        MutableClock clock = new MutableClock();
        AtomicInteger calls = new AtomicInteger();
        Optional<Match> expected = found ? Optional.of(MATCH) : Optional.empty();
        Duration ttl = found ? SUCCESS_TTL : NOT_FOUND_TTL;
        try (var service = service(key -> {
            calls.incrementAndGet();
            return expected;
        }, clock, 1)) {
            assertEquals(expected, result(service.find(KEY)));
            clock.advance(ttl.minusNanos(1));
            assertEquals(expected, result(service.find(KEY)));
            assertEquals(1, calls.get(), "만료 직전에는 성공과 404 모두 캐시 적중");
            clock.advance(Duration.ofNanos(1));
            assertEquals(expected, result(service.find(KEY)));
            assertEquals(2, calls.get(), "정확한 만료 경계에서는 재조회");
        }
    }

    @ParameterizedTest
    @MethodSource("clientFailures")
    void failureReachesAllWaitersAndClearsFlightBeforeRetry(Exception expectedFailure) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch fail = new CountDownLatch(1);
        MatchClient client = key -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                await(fail);
                throw expectedFailure;
            }
            return Optional.of(MATCH);
        };
        try (var service = service(client, new MutableClock(), 1)) {
            try {
                var first = service.find(KEY);
                await(entered);
                var second = service.find(KEY);
                assertFalse(second.isDone());
                assertEquals(1, calls.get());
                fail.countDown();
                for (var request : List.of(first, second)) {
                    ExecutionException observed = assertThrows(ExecutionException.class,
                            () -> result(request));
                    assertSame(expectedFailure, observed.getCause());
                }
                assertEquals(0, service.inFlightCount());
                assertEquals(Optional.of(MATCH), result(service.find(KEY)));
                assertEquals(2, calls.get(), "실패는 negative cache에 저장하지 않음");
                assertEquals(Optional.of(MATCH), result(service.find(KEY)));
                assertEquals(2, calls.get());
            } finally {
                fail.countDown();
            }
        }
    }

    static Stream<Exception> clientFailures() {
        return Stream.of(new IOException("synthetic transport failure"),
                new IllegalStateException("synthetic client failure"));
    }

    @ParameterizedTest(name = "cache-registration race: found={0}")
    @ValueSource(booleans = {true, false})
    void staleCacheMissBeforeFlightRegistrationDoesNotStartAnotherClientCall(boolean found) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        PausingCache cache = new PausingCache(new TtlCache<>(new MutableClock()));
        Optional<Match> expected = found ? Optional.of(MATCH) : Optional.empty();
        MatchClient client = key -> {
            calls.incrementAndGet();
            return expected;
        };
        try (var service = new MatchRequestService(client, cache, SUCCESS_TTL, NOT_FOUND_TTL, 1);
             var callers = Executors.newFixedThreadPool(1)) {
            try {
                // A는 cache miss를 읽은 뒤 Future 등록 직전에 멈춘다.
                var delayedCaller = callers.submit(() -> service.find(KEY));
                await(cache.missObserved);
                // B가 같은 키의 수집, 캐시 저장, in-flight 제거까지 모두 마친다.
                assertEquals(expected, result(service.find(KEY)));
                assertEquals(0, service.inFlightCount());
                assertEquals(1, calls.get());
                // A의 오래된 miss가 반환돼도 등록 후 재조회에서 B의 캐시를 발견해야 한다.
                cache.resume.countDown();
                assertEquals(expected, result(result(delayedCaller)));
                assertEquals(1, calls.get(), "새 소유자가 된 A가 캐시를 재확인해야 중복 수집을 막음");
                assertEquals(0, service.inFlightCount());
            } finally {
                cache.resume.countDown();
            }
        }
    }

    @Test
    void cancellingOneReturnedFutureDoesNotCancelSharedWork() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (var service = service(key -> {
            calls.incrementAndGet();
            entered.countDown();
            await(release);
            return Optional.of(MATCH);
        }, new MutableClock(), 1)) {
            try {
                var first = service.find(KEY);
                await(entered);
                var second = service.find(KEY);
                assertTrue(first.cancel(false));
                assertEquals(1, service.inFlightCount());
                release.countDown();
                assertEquals(Optional.of(MATCH), result(second));
                assertEquals(Optional.of(MATCH), result(service.find(KEY)));
                assertTrue(first.isCancelled());
                assertEquals(1, calls.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void closedServiceRejectsNewRequestsWithoutLeavingAFlight() {
        AtomicInteger calls = new AtomicInteger();
        var service = service(key -> {
            calls.incrementAndGet();
            return Optional.of(MATCH);
        }, new MutableClock(), 1);
        service.close();
        ExecutionException failure = assertThrows(ExecutionException.class, () -> result(service.find(KEY)));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals(0, calls.get());
        assertEquals(0, service.inFlightCount());
    }

    private static MatchRequestService service(MatchClient client, MutableClock clock, int concurrency) {
        return new MatchRequestService(client, clock, SUCCESS_TTL, NOT_FOUND_TTL, concurrency);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("test gate timed out");
        }
    }

    private static <T> T result(Future<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    /** 오래된 miss만 지연한다. 서비스의 운영 경로에 테스트용 sleep/hook을 넣지 않는다. */
    private static final class PausingCache implements ResultCache<MatchKey, Optional<Match>> {
        private final ResultCache<MatchKey, Optional<Match>> delegate;
        private final AtomicBoolean pauseNextMiss = new AtomicBoolean(true);
        final CountDownLatch missObserved = new CountDownLatch(1);
        final CountDownLatch resume = new CountDownLatch(1);

        PausingCache(ResultCache<MatchKey, Optional<Match>> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<Optional<Match>> get(MatchKey key) {
            Optional<Optional<Match>> observed = delegate.get(key);
            if (observed.isEmpty() && pauseNextMiss.compareAndSet(true, false)) {
                missObserved.countDown();
                try {
                    await(resume);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
            }
            return observed;
        }

        @Override
        public void put(MatchKey key, Optional<Match> value, Duration ttl) {
            delegate.put(key, value, ttl);
        }
    }
}
