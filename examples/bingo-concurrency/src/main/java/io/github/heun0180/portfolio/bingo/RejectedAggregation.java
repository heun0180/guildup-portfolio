package io.github.heun0180.portfolio.bingo;

public class RejectedAggregation extends RuntimeException {
    public enum Reason { GENERATION, INPUTS, INCOMPLETE }
    private final Reason reason;

    public RejectedAggregation(Reason reason) {
        super("Aggregation rejected: " + reason);
        this.reason = reason;
    }
    public Reason reason() { return reason; }
}
