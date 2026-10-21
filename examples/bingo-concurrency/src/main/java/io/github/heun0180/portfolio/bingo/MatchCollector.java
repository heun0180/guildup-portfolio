package io.github.heun0180.portfolio.bingo;

import io.github.heun0180.portfolio.bingo.AggregationData.*;

@FunctionalInterface
public interface MatchCollector {
    CollectionResult collect(Work work);
}
