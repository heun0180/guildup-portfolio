package io.github.heun0180.portfolio.settlement;

public class RejectedClaim extends RuntimeException {
    public enum Reason { STATE, OWNERSHIP, NOT_ENDED }
    private final Reason reason;
    public RejectedClaim(Reason reason) { super(reason.name()); this.reason = reason; }
    public Reason reason() { return reason; }
}
