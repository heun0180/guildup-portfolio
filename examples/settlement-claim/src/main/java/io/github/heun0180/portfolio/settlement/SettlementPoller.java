package io.github.heun0180.portfolio.settlement;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static io.github.heun0180.portfolio.settlement.SettlementData.PollReport;

/** 스케줄러의 한 차례 poll. 자동 타이머 대신 테스트와 데모에서 명시적으로 호출한다. */
@Service
@Transactional(propagation = Propagation.NEVER)
public class SettlementPoller {
    private final SettlementStore store;
    private final SettlementWorker worker;
    public SettlementPoller(SettlementStore store, SettlementWorker worker) { this.store = store; this.worker = worker; }
    public PollReport tick() {
        int completed = 0, failed = 0, skipped = 0;
        for (long id : store.dueIds()) {
            try { if (worker.publish(id)) completed++; else skipped++; }
            catch (RuntimeException failure) { failed++; }
        }
        return new PollReport(completed, failed, skipped);
    }
}
