package io.github.heun0180.portfolio.bingo;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.stream.Collectors;
import static io.github.heun0180.portfolio.bingo.AggregationData.*;

@Component
public class SyntheticMatchCollector implements MatchCollector {
    @Override
    public CollectionResult collect(Work work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("collection must run outside a DB transaction");
        }
        return CollectionResult.success(new MatchFact("synthetic-match", work.inputs().participants().stream()
                .collect(Collectors.toMap(ParticipantInput::accountCode, ignored -> 2))));
    }
}
