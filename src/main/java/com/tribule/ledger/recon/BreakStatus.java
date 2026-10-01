package com.tribule.ledger.recon;

public enum BreakStatus {
    /** Needs a person. */
    OPEN,
    /** The matcher could both explain it and act on it. */
    AUTO_RESOLVED,
    MANUALLY_RESOLVED
}
