package io.github.heun0180.portfolio.settlement;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class SyntheticCollector implements ResultCollector {
    @Override public SettlementData.Snapshot collect(SettlementData.Work work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("collection inside transaction");
        return SettlementData.Snapshot.sample();
    }
}
