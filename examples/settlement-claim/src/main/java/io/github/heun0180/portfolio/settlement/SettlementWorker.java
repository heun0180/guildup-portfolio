package io.github.heun0180.portfolio.settlement;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(propagation = Propagation.NEVER)
public class SettlementWorker {
    private final SettlementStore store;
    private final ResultCollector collector;
    public SettlementWorker(SettlementStore store, ResultCollector collector) { this.store = store; this.collector = collector; }
    public void request(long id) { store.request(id); }
    public boolean publish(long id) {
        var work = store.claim(id);
        if (work == null) return false;
        try {
            var snapshot = collector.collect(work);
            store.finish(work, snapshot);
            return true;
        } catch (RuntimeException failure) {
            try { store.recordFailure(work); }
            catch (RuntimeException recordingFailure) { failure.addSuppressed(recordingFailure); }
            throw failure;
        }
    }
}
