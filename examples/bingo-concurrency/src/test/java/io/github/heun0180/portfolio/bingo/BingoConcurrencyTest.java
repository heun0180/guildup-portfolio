package io.github.heun0180.portfolio.bingo;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

import static io.github.heun0180.portfolio.bingo.AggregationData.*;
import static io.github.heun0180.portfolio.bingo.RejectedAggregation.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

/** 클래스/메서드에 테스트용 @Transactional을 붙이지 않는다. 각 서비스 호출은 실제 커밋된다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(BingoConcurrencyTest.ControlledBeans.class)
@Timeout(30)
class BingoConcurrencyTest {
    @Autowired AggregationService aggregation;
    @Autowired PreparationService preparation;
    @Autowired ExampleDatabase database;
    @Autowired EventRepository events;
    @Autowired ParticipantRepository participants;
    @Autowired ControlledCollector collector;
    @Autowired ControlledPolicy policy;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    ExampleDatabase.Fixture fixture;
    TransactionTemplate tx;

    @BeforeEach
    void createCommittedFixture() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        collector.plans.clear();
        collector.outsideCalls.set(0);
        policy.failingAccount = null;
        policy.beforeFailure = () -> { };
        tx = new TransactionTemplate(manager);
        fixture = database.createFixture();
    }

    @Test
    void normalAggregationCommitsAndCollectionRunsWithoutTransaction() {
        long initial = state().generation();
        collector.add(CollectionResult.success(fact("normal", 3, 4)));
        assertEquals(2, aggregation.aggregate(fixture.eventId(), null));
        assertProgress(3, 4);
        assertEquals(keys("normal"), state().processed());
        assertEquals(initial + 1, state().generation());
        assertEquals(1, collector.outsideCalls.get());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());

        assertThrows(IllegalTransactionStateException.class, () -> tx.executeWithoutResult(status ->
                aggregation.aggregate(fixture.eventId(), null)));
        assertEquals(initial + 1, state().generation(), "상위 트랜잭션이 있으면 준비 전에 거부");
    }

    @Test
    void repeatedMatchAndDuplicateInputCannotAccumulateTwice() {
        MatchFact match = fact("repeat", 3, 4);
        collector.add(CollectionResult.success(match, match));
        assertEquals(2, aggregation.aggregate(fixture.eventId(), null));
        collector.add(CollectionResult.success(match));
        assertEquals(0, aggregation.aggregate(fixture.eventId(), null));
        assertProgress(3, 4);
        assertEquals(keys("repeat"), state().processed());
        // 애플리케이션의 seen 검사 외에 실제 H2 유일 제약도 검증한다.
        assertThrows(DataIntegrityViolationException.class, () -> tx.executeWithoutResult(status ->
                jdbc.update("insert into processed_match (event_id, participant_id, match_id) values (?, ?, ?)",
                        fixture.eventId(), fixture.firstId(), "repeat")));
        assertEquals(keys("repeat"), state().processed());
        assertProgress(3, 4);
    }

    @Test @Tag("concurrency")
    void olderWorkCannotApplyAfterNewerFullAggregationCompletes() throws Exception {
        runPausedWorkRejected(null, GENERATION, work -> {
            collector.add(CollectionResult.success(fact("new", 9, 11)));
            aggregation.aggregate(fixture.eventId(), null);
        });
        assertProgress(9, 11);
        assertEquals(keys("new"), state().processed());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true}) @Tag("concurrency")
    void fullAndPersonalAggregationsShareEventGeneration(boolean oldPersonal) throws Exception {
        Long oldTarget = oldPersonal ? fixture.firstId() : null;
        Long newTarget = oldPersonal ? null : fixture.firstId();
        runPausedWorkRejected(oldTarget, GENERATION, work -> {
            collector.add(CollectionResult.success(fact("new-scope", 7, 9)));
            aggregation.aggregate(fixture.eventId(), newTarget);
        });
        assertProgress(7, oldPersonal ? 9 : 0);
        assertEquals(oldPersonal ? keys("new-scope")
                : Set.of(new ProcessedKey(fixture.firstId(), "new-scope")), state().processed());
    }

    @Test @Tag("concurrency")
    void policyChangeDuringCollectionRejectsOldWork() throws Exception {
        runPausedWorkRejected(null, GENERATION, work -> tx.executeWithoutResult(status ->
                events.lockById(fixture.eventId()).orElseThrow().changePolicy(2)));
        assertEquals(2, state().pointsPerKill());
        assertProgress(0, 0);
        assertTrue(state().processed().isEmpty());
    }

    @Test @Tag("concurrency")
    void participantInputChangeIsRejectedEvenWithoutEventVersionChange() throws Exception {
        runPausedWorkRejected(null, INPUTS, work -> {
            tx.executeWithoutResult(status -> {
                events.lockById(fixture.eventId()).orElseThrow();
                participants.lockById(fixture.eventId(), fixture.firstId()).orElseThrow()
                        .changeAccount("synthetic-replacement");
            });
            assertEquals(work.generation(), state().generation(), "참가자 변경만으로 이벤트 버전은 증가하지 않음");
        });
        assertProgress(0, 0);
        assertTrue(state().processed().isEmpty());
    }

    @Test @Tag("concurrency")
    void newPreparationWithoutResultWritesInvalidatesOlderWork() throws Exception {
        runPausedWorkRejected(null, GENERATION, work -> {
            Work newer = preparation.prepare(fixture.eventId(), null);
            assertEquals(work.generation() + 1, newer.generation());
            assertEquals(newer.generation(), state().generation(), "새 준비 트랜잭션은 이미 커밋됨");
        });
        assertProgress(0, 0);
        assertTrue(state().processed().isEmpty());
    }

    @Test @Tag("concurrency")
    void concurrentPreparationsGetDistinctCommittedGenerations() throws Exception {
        long before = state().generation();
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<Work> task = () -> {
                ready.countDown();
                await(start);
                return preparation.prepare(fixture.eventId(), null);
            };
            var first = workers.submit(task);
            var second = workers.submit(task);
            try {
                await(ready);
            } finally {
                start.countDown();
            }
            assertEquals(Set.of(before + 1, before + 2),
                    Set.of(first.get(10, TimeUnit.SECONDS).generation(), second.get(10, TimeUnit.SECONDS).generation()));
        }
        assertEquals(before + 2, state().generation());
        assertProgress(0, 0);
    }

    @Test
    void calculationFailureRollsBackFlushedProgressAndHistoryTogether() {
        AtomicBoolean sawUncommittedRows = new AtomicBoolean();
        long before = state().generation();
        policy.failingAccount = "synthetic-b";
        policy.beforeFailure = () -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(5L, jdbc.queryForObject("select progress from bingo_participant where id = ?",
                    Long.class, fixture.firstId()));
            assertEquals(1L, jdbc.queryForObject("select count(*) from processed_match where event_id = ?",
                    Long.class, fixture.eventId()));
            sawUncommittedRows.set(true);
        };
        collector.add(CollectionResult.success(fact("rollback", 5, 6)));
        assertThrows(InjectedFailure.class, () -> aggregation.aggregate(fixture.eventId(), null));
        assertTrue(sawUncommittedRows.get(), "SQL에 반영된 미커밋 값까지 관측한 뒤 실패해야 함");
        assertProgress(0, 0);
        assertTrue(state().processed().isEmpty());
        assertEquals(before + 1, state().generation(), "앞서 커밋한 준비 세대는 롤백하지 않음");

        policy.failingAccount = null;
        collector.add(CollectionResult.success(fact("rollback", 5, 6)));
        assertEquals(2, aggregation.aggregate(fixture.eventId(), null));
        assertProgress(5, 6);
        assertEquals(keys("rollback"), state().processed());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void incompleteCollectionPreservesPreviouslyCommittedProgress(boolean partialSuccess) {
        collector.add(CollectionResult.success(fact("prior", 5, 6)));
        aggregation.aggregate(fixture.eventId(), null);
        MatchFact newMatch = fact("new", 7, 8);
        collector.add(new CollectionResult(Set.of("new", "missing"),
                partialSuccess ? List.of(newMatch) : List.of(), Set.of("missing")));
        var rejected = assertThrows(RejectedAggregation.class, () -> aggregation.aggregate(fixture.eventId(), null));
        assertEquals(INCOMPLETE, rejected.reason());
        assertProgress(5, 6);
        assertEquals(keys("prior"), state().processed());

        collector.add(CollectionResult.success(newMatch, fact("missing", 1, 1)));
        aggregation.aggregate(fixture.eventId(), null);
        assertProgress(13, 15);
        var expected = new HashSet<>(keys("prior"));
        expected.addAll(keys("new"));
        expected.addAll(keys("missing"));
        assertEquals(expected, state().processed());
    }

    private void runPausedWorkRejected(Long oldTarget, RejectedAggregation.Reason reason,
                                       Consumer<Work> duringCollection) throws Exception {
        Plan paused = new Plan(CollectionResult.success(fact("old", 3, 4)), true);
        collector.plans.add(paused);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var old = worker.submit(() -> aggregation.aggregate(fixture.eventId(), oldTarget));
            try {
                await(paused.entered);
                assertEquals(paused.work.generation(), state().generation(), "준비가 별도 트랜잭션으로 커밋됨");
                duringCollection.accept(paused.work);
            } finally {
                paused.release.countDown();
            }
            ExecutionException failure = assertThrows(ExecutionException.class, () -> old.get(10, TimeUnit.SECONDS));
            assertEquals(reason, assertInstanceOf(RejectedAggregation.class, failure.getCause()).reason());
        }
    }

    private ExampleDatabase.State state() { return database.read(fixture.eventId()); }
    private void assertProgress(long first, long second) {
        assertEquals(Map.of(fixture.firstId(), first, fixture.secondId(), second), state().progress());
    }
    private Set<ProcessedKey> keys(String matchId) {
        return Set.of(new ProcessedKey(fixture.firstId(), matchId), new ProcessedKey(fixture.secondId(), matchId));
    }
    private static MatchFact fact(String id, int first, int second) {
        return new MatchFact(id, Map.of("synthetic-a", first, "synthetic-b", second));
    }
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test gate timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    static final class Plan {
        final CollectionResult result;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release;
        volatile Work work;
        Plan(CollectionResult result, boolean pause) {
            this.result = result;
            this.release = new CountDownLatch(pause ? 1 : 0);
        }
    }
    static final class ControlledCollector implements MatchCollector {
        final Queue<Plan> plans = new ConcurrentLinkedQueue<>();
        final AtomicInteger outsideCalls = new AtomicInteger();
        void add(CollectionResult result) { plans.add(new Plan(result, false)); }
        @Override public CollectionResult collect(Work work) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            outsideCalls.incrementAndGet();
            Plan plan = Objects.requireNonNull(plans.poll(), "missing synthetic collection plan");
            plan.work = work;
            plan.entered.countDown();
            await(plan.release);
            return plan.result;
        }
    }
    static final class ControlledPolicy implements ScoringPolicy {
        volatile String failingAccount;
        volatile Runnable beforeFailure = () -> { };
        @Override public long contribution(String accountCode, int kills, int pointsPerKill) {
            if (accountCode.equals(failingAccount)) {
                beforeFailure.run();
                throw new InjectedFailure();
            }
            return new KillCountPolicy().contribution(accountCode, kills, pointsPerKill);
        }
    }
    static final class InjectedFailure extends RuntimeException { }

    @TestConfiguration(proxyBeanMethods = false)
    static class ControlledBeans {
        @Bean @Primary ControlledCollector controlledCollector() { return new ControlledCollector(); }
        @Bean @Primary ControlledPolicy controlledPolicy() { return new ControlledPolicy(); }
    }
}
