package com.tribule.ledger.ledger;

public enum TransactionKind {
    FUNDING,
    TRANSFER,
    FX_CONVERSION,
    AUTHORIZATION_HOLD,
    HOLD_RELEASE,
    SETTLEMENT,
    REVERSAL,
    RATE_CORRECTION_ADJUSTMENT,
    RECON_ADJUSTMENT
}
