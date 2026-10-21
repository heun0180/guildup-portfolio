package io.github.heun0180.portfolio.settlement;

public interface ResultCollector {
    SettlementData.Snapshot collect(SettlementData.Work work);
}
