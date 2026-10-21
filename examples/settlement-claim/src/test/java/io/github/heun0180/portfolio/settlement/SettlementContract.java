package io.github.heun0180.portfolio.settlement;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.heun0180.portfolio.settlement.SettlementData.*;
import static io.github.heun0180.portfolio.settlement.RejectedClaim.Reason.*;

/** 테스트용 @Transactional 없이 서비스 프록시의 실제 커밋을 검증한다. */
@SpringBootTest(classes = SettlementExampleApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(SettlementContract.Controls.class)
@Timeout(30)
abstract class SettlementContract {
    @Autowired SettlementWorker worker;
    @Autowired SettlementStore store;
    @Autowired SettlementPoller poller;
    @Autowired ExampleDatabase database;
    @Autowired AdjustableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired ControlledCollector collector;
    @Autowired ControlledCheckpoint checkpoint;
    TransactionTemplate tx;
    long id;
    static final Instant START = Instant.parse("2030-01-01T00:00:00Z");

    @BeforeEach void fixture() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        database.reset();
        clock.set(START);
        tx = new TransactionTemplate(manager);
        collector.plans.clear(); collector.calls = 0;
        checkpoint.action = () -> { };
        id = database.create(START.minusSeconds(1));
    }

    @Test void publicationWaitsThirtyMinutesAndPollsOnce() {
        worker.request(id);
        assertEquals(START, state().requestedAt());
        assertEquals(START.plus(Duration.ofMinutes(30)), state().publishAt());
        clock.set(state().publishAt().minusMillis(1));
        assertEquals(new PollReport(0,0,0), poller.tick());
        assertFalse(worker.publish(id));
        assertEquals(0, collector.calls);
        clock.advance(Duration.ofMillis(1));
        collector.add(Snapshot.sample());
        assertEquals(new PollReport(1,0,0), poller.tick());
        assertCompleted(7, "synthetic-match");
        assertEquals(new PollReport(0,0,0), poller.tick());
        assertEquals(1, collector.calls);
    }

    @Test void resultRequestRejectsBeforeEndAndDuplicateRequest() {
        long future = database.create(START.plusSeconds(1));
        assertReason(NOT_ENDED, () -> worker.request(future));
        assertEquals(Status.IN_PROGRESS, database.read(future).status());
        worker.request(id);
        assertReason(STATE, () -> worker.request(id));
        assertEquals(0, collector.calls);
    }

    @Test @Tag("concurrency") void simultaneousClaimsHaveOneOwner() throws Exception {
        due();
        var claims = race(() -> store.claim(id));
        assertEquals(1, claims.stream().filter(Objects::nonNull).count());
        Work owner = claims.stream().filter(Objects::nonNull).findFirst().orElseThrow();
        assertNotNull(owner.token());
        assertEquals(owner.token(), state().token());
        assertEquals(owner.claimAt(), state().claimAt());
    }

    @Test void claimIsBlockedUntilTenMinutes() {
        Work first = claim();
        clock.set(first.claimAt().plus(Duration.ofMinutes(10)).minusNanos(1));
        assertNull(store.claim(id));
        assertEquals(first.token(), state().token());
    }

    @Test void exactTenMinutesReplacesUuid() {
        Work first = claim();
        clock.set(first.claimAt().plus(Duration.ofMinutes(10)));
        Work second = store.claim(id);
        assertNotNull(second);
        assertNotEquals(first.token(), second.token());
        assertEquals(second.token(), state().token());
        assertEquals(clock.instant(), state().claimAt());
    }

    @Test @Tag("concurrency") void oldWorkerIsRejectedWhileReplacementIsPending() throws Exception {
        pausedOldWork(false, work -> {
            clock.advance(Duration.ofMinutes(10));
            Work newer = store.claim(id);
            assertNotEquals(work.token(), newer.token());
            assertEquals(newer.token(), state().token());
        }, OWNERSHIP);
        assertEquals(Status.RESULT_PENDING, state().status());
        assertNotNull(state().token());
        assertNull(state().error());
        assertNoFinancialWrites();
    }

    @Test @Tag("concurrency") void completedReplacementRejectsOldSaveAndLateFailure() throws Exception {
        pausedOldWork(false, work -> {
            clock.advance(Duration.ofMinutes(10));
            collector.add(new Snapshot(List.of(new MatchPoints("new-result", 11))));
            assertTrue(worker.publish(id));
        }, STATE);
        assertCompleted(11, "new-result");
        assertNull(state().token());
        assertNull(state().claimAt());
        assertNull(state().error());
    }

    @Test @Tag("concurrency") void lateCollectionFailureCannotRemoveReplacementClaim() throws Exception {
        pausedOldWork(true, work -> {
            clock.advance(Duration.ofMinutes(10));
            Work replacement = store.claim(id);
            assertNotEquals(work.token(), replacement.token());
        }, null);
        assertEquals(Status.RESULT_PENDING, state().status());
        assertNotNull(state().token());
        assertEquals(clock.instant(), state().claimAt());
        assertNull(state().error());
        assertNoFinancialWrites();
    }

    @Test void tenMinutesWithoutReplacementStillAllowsFinish() {
        Work work = claim();
        clock.advance(Duration.ofMinutes(11));
        store.finish(work, Snapshot.sample());
        assertCompleted(7, "synthetic-match");
    }

    @Test void failureRetryUsesClaimStartInsteadOfFailureTime() {
        Work first = claim();
        clock.advance(Duration.ofMinutes(9));
        assertTrue(store.recordFailure(first));
        assertEquals(Status.RESULT_PENDING, state().status());
        assertNull(state().token());
        assertEquals(first.claimAt(), state().claimAt());
        assertNotNull(state().error());
        assertFalse(store.recordFailure(first));
        assertNull(store.claim(id));
        clock.set(first.claimAt().plus(Duration.ofMinutes(10)));
        Work retry = store.claim(id);
        assertNotNull(retry);
        assertNotEquals(first.token(), retry.token());
        assertNull(state().error());
    }

    @Test void finalSaveRollsBackResultsRewardsTotalAndCompletedState() {
        Work work = claim();
        checkpoint.action = () -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("COMPLETED", jdbc.queryForObject("select status from competition where id = ?",String.class,id));
            assertEquals(7L, jdbc.queryForObject("select total_points from competition where id = ?",Long.class,id));
            assertEquals(1L, jdbc.queryForObject("select count(*) from match_result where competition_id = ?",Long.class,id));
            assertEquals(1L, jdbc.queryForObject("select count(*) from reward_entry where competition_id = ?",Long.class,id));
            assertEquals(3L, jdbc.queryForObject("select balance from reward_account where id = ?",Long.class,state().rewardAccountId()));
            throw new InjectedFailure();
        };
        assertThrows(InjectedFailure.class, () -> store.finish(work, Snapshot.sample()));
        assertEquals(Status.RESULT_PENDING, state().status());
        assertEquals(work.token(), state().token());
        assertEquals(work.claimAt(), state().claimAt());
        assertNull(state().completedAt());
        assertNoFinancialWrites();
        assertTrue(store.recordFailure(work));
        assertNull(state().token());
        assertNotNull(state().error());
        assertEquals(work.claimAt(), state().claimAt());
    }

    @Test void workerRecordsFailureAfterRollbackAndRetryCommits() {
        due();
        checkpoint.action = () -> { throw new InjectedFailure(); };
        collector.add(Snapshot.sample());
        assertThrows(InjectedFailure.class, () -> worker.publish(id));
        Instant originalStart = state().claimAt();
        assertEquals(Status.RESULT_PENDING,state().status());
        assertNull(state().token());
        assertNotNull(state().error());
        assertNoFinancialWrites();
        checkpoint.action = () -> { };
        clock.set(originalStart.plus(Duration.ofMinutes(10)));
        collector.add(Snapshot.sample());
        assertTrue(worker.publish(id));
        assertCompleted(7,"synthetic-match");
        assertNull(state().error());
    }

    @Test void collectionFailureCommitsFailureWithoutAnyResultOrReward() {
        due();
        collector.plans.add(new Plan(Snapshot.sample(),false,true));
        assertThrows(InjectedFailure.class, () -> worker.publish(id));
        assertEquals(Status.RESULT_PENDING,state().status());
        assertEquals(clock.instant(),state().claimAt());
        assertNull(state().token());
        assertEquals("Synthetic collection or persistence failed. Retry after the original claim window.",state().error());
        assertNoFinancialWrites();
        assertNull(store.claim(id));
    }

    @Test void failureAfterReclaimWindowAllowsImmediateNewClaim() {
        Work work = claim();
        clock.advance(Duration.ofMinutes(11));
        assertTrue(store.recordFailure(work));
        assertEquals(work.claimAt(),state().claimAt());
        Work retry = store.claim(id);
        assertNotNull(retry);
        assertNotEquals(work.token(),retry.token());
        assertEquals(clock.instant(),retry.claimAt());
    }

    @Test void repeatedFinishAndDatabaseConstraintsPreventDuplicateRewards() {
        Work work = claim();
        store.finish(work,Snapshot.sample());
        assertReason(STATE, () -> store.finish(work,Snapshot.sample()));
        assertFalse(store.recordFailure(work));
        assertNull(store.claim(id));
        long account = state().rewardAccountId();
        assertThrows(DataIntegrityViolationException.class, () -> tx.executeWithoutResult(status ->
                jdbc.update("insert into reward_entry (competition_id,account_id,amount) values (?,?,?)",id,account,3)));
        assertThrows(DataIntegrityViolationException.class, () -> tx.executeWithoutResult(status ->
                jdbc.update("insert into match_result (competition_id,match_id,points) values (?,?,?)",id,"synthetic-match",7)));
        assertCompleted(7,"synthetic-match");
    }

    @Test void uuidIdentitySurvivesExplicitTimestampPrecisionChange() {
        due();
        clock.set(clock.instant().plusNanos(123456789));
        Work work = store.claim(id);
        Instant microseconds = work.claimAt().truncatedTo(ChronoUnit.MICROS);
        tx.executeWithoutResult(status -> jdbc.update("update competition set finalization_started_at = ? where id = ?",
                OffsetDateTime.ofInstant(microseconds,ZoneOffset.UTC),id));
        assertNotEquals(work.claimAt(),state().claimAt());
        assertEquals(work.token(),state().token());
        store.finish(work,Snapshot.sample());
        assertCompleted(7,"synthetic-match");
    }

    @Test void nullAndWrongUuidCannotSavePendingResult() {
        Work work = claim();
        assertReason(OWNERSHIP, () -> store.finish(new Work(id,work.claimAt(),null),Snapshot.sample()));
        assertReason(OWNERSHIP, () -> store.finish(new Work(id,work.claimAt(),UUID.randomUUID()),Snapshot.sample()));
        assertFalse(store.recordFailure(new Work(id,work.claimAt(),null)));
        assertEquals(work.token(),state().token());
        assertNoFinancialWrites();
    }

    @Test void duplicateInputMatchesAreCountedOnce() {
        Work work = claim();
        MatchPoints match = new MatchPoints("duplicate-input",7);
        store.finish(work,new Snapshot(List.of(match,match)));
        assertCompleted(7,"duplicate-input");
    }

    @Test @Tag("concurrency") void concurrentFinishesCommitOneResultAndOneReward() throws Exception {
        Work work = claim();
        var attempts = race(() -> {
            try { store.finish(work,Snapshot.sample());return "committed"; }
            catch (RejectedClaim rejected) { assertEquals(STATE,rejected.reason());return "rejected"; }
        });
        assertEquals(1,attempts.stream().filter("committed"::equals).count());
        assertEquals(1,attempts.stream().filter("rejected"::equals).count());
        assertCompleted(7,"synthetic-match");
    }

    @Test void ambientTransactionIsRejectedBeforeRequestOrClaim() {
        assertThrows(IllegalTransactionStateException.class, () -> tx.executeWithoutResult(status -> worker.request(id)));
        assertEquals(Status.IN_PROGRESS,state().status());
        due();
        assertThrows(IllegalTransactionStateException.class, () -> tx.executeWithoutResult(status -> worker.publish(id)));
        assertNull(state().token());
        assertNull(state().claimAt());
        assertEquals(0,collector.calls);
    }

    private void pausedOldWork(boolean collectFails, Consumer<Work> duringCollection,
                               RejectedClaim.Reason expected) throws Exception {
        due();
        Plan plan = new Plan(new Snapshot(List.of(new MatchPoints("old-result",3))),true,collectFails);
        collector.plans.add(plan);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Boolean> old = executor.submit(() -> worker.publish(id));
            try {
                await(plan.entered);
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals(plan.work.token(),state().token(),"Claim 트랜잭션이 독립 커밋됨");
                assertNull(store.claim(id),"외부 수집 중에도 다른 연결이 행을 잠그고 검사할 수 있음");
                duringCollection.accept(plan.work);
            } finally { plan.release.countDown(); }
            ExecutionException rejected = assertThrows(ExecutionException.class, () -> old.get(10,TimeUnit.SECONDS));
            if (collectFails) assertInstanceOf(InjectedFailure.class,rejected.getCause());
            else assertEquals(expected,assertInstanceOf(RejectedClaim.class,rejected.getCause()).reason());
        }
    }
    private <T> List<T> race(Callable<T> task) throws Exception {
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<T> gated = () -> { ready.countDown();await(start);return task.call(); };
            Future<T> a = executor.submit(gated), b = executor.submit(gated);
            try { await(ready); } finally { start.countDown(); }
            return Arrays.asList(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
    }
    void due() { worker.request(id);clock.set(state().publishAt()); }
    Work claim() { due();return Objects.requireNonNull(store.claim(id)); }
    ExampleDatabase.State state() { return database.read(id); }
    void assertCompleted(long points,String matchId) {
        assertEquals(Status.COMPLETED,state().status());assertEquals(points,state().totalPoints());
        assertEquals(Map.of(matchId,points),state().matches());
        assertEquals(3,state().rewardBalance());assertEquals(1,state().rewardEntries());
        assertNotNull(state().completedAt());
    }
    void assertNoFinancialWrites() {
        assertEquals(0,state().totalPoints());assertTrue(state().matches().isEmpty());
        assertEquals(0,state().rewardBalance());assertEquals(0,state().rewardEntries());
    }
    void assertReason(RejectedClaim.Reason reason,org.junit.jupiter.api.function.Executable call) {
        assertEquals(reason,assertThrows(RejectedClaim.class,call).reason());
    }
    static void await(CountDownLatch latch) {
        try { if (!latch.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("test gate timeout"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt();throw new IllegalStateException(failure); }
    }
    static final class InjectedFailure extends RuntimeException { }
    static final class Plan {
        final Snapshot snapshot; final boolean fail;
        final CountDownLatch entered = new CountDownLatch(1), release;
        volatile Work work;
        Plan(Snapshot snapshot,boolean paused,boolean fail) {
            this.snapshot=snapshot;this.fail=fail;release=new CountDownLatch(paused?1:0);
        }
    }
    static final class ControlledCollector implements ResultCollector {
        final Queue<Plan> plans = new ConcurrentLinkedQueue<>();
        volatile int calls;
        void add(Snapshot snapshot) { plans.add(new Plan(snapshot,false,false)); }
        @Override public Snapshot collect(Work work) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());calls++;
            Plan plan = Objects.requireNonNull(plans.poll(),"no synthetic plan");
            plan.work=work;plan.entered.countDown();await(plan.release);
            if (plan.fail) throw new InjectedFailure();
            return plan.snapshot;
        }
    }
    static final class ControlledCheckpoint implements FinishCheckpoint {
        volatile Runnable action = () -> { };
        @Override public void afterFlush() { action.run(); }
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class Controls {
        @Bean @Primary ControlledCollector controlledCollector() { return new ControlledCollector(); }
        @Bean @Primary ControlledCheckpoint controlledCheckpoint() { return new ControlledCheckpoint(); }
    }
}
