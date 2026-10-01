package com.tribule.ledger.config;

import com.tribule.ledger.ledger.CurrencyRepository;
import com.tribule.ledger.money.CurrencyUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Deployment-level choices that the domain should not hard-code.
 *
 * <p>The pivot currency is the one every pair can be composed through when it is
 * not quoted directly; the reporting currency is the one exposure and P&amp;L are
 * aggregated into. They are usually the same and do not have to be.
 */
@Component
public class LedgerProperties {

    private final CurrencyRepository currencies;
    private final String pivotCurrency;
    private final String reportingCurrency;
    private final Duration authorizationTtl;
    private final int varLookbackDays;
    private final double varConfidence;

    public LedgerProperties(
            CurrencyRepository currencies,
            @Value("${ledger.pivot-currency:USD}") String pivotCurrency,
            @Value("${ledger.reporting-currency:USD}") String reportingCurrency,
            @Value("${ledger.authorization-ttl:PT48H}") Duration authorizationTtl,
            @Value("${ledger.risk.var-lookback-days:60}") int varLookbackDays,
            @Value("${ledger.risk.var-confidence:0.95}") double varConfidence) {
        this.currencies = currencies;
        this.pivotCurrency = pivotCurrency.toUpperCase();
        this.reportingCurrency = reportingCurrency.toUpperCase();
        this.authorizationTtl = authorizationTtl;
        this.varLookbackDays = varLookbackDays;
        this.varConfidence = varConfidence;
    }

    public String pivotCurrency() {
        return pivotCurrency;
    }

    public CurrencyUnit pivotCurrencyUnit() {
        return currencies.require(pivotCurrency);
    }

    public String reportingCurrency() {
        return reportingCurrency;
    }

    public CurrencyUnit reportingCurrencyUnit() {
        return currencies.require(reportingCurrency);
    }

    public Duration authorizationTtl() {
        return authorizationTtl;
    }

    public int varLookbackDays() {
        return varLookbackDays;
    }

    public double varConfidence() {
        return varConfidence;
    }
}
