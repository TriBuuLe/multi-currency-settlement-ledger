package com.tribule.ledger.config;

import com.tribule.ledger.ledger.BalanceService;
import com.tribule.ledger.ledger.TrialBalanceLine;
import com.tribule.ledger.recon.ReconciliationService;
import com.tribule.ledger.settlement.AuthorizationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The gauges worth putting on a dashboard.
 *
 * <p>Latency and error rate tell you the service is unwell. These tell you the
 * money is wrong, which is a different and much worse problem:
 *
 * <ul>
 *   <li>{@code ledger.trial_balance.residual} -- must be 0 for every currency.
 *       Anything else means the books do not add up. Alert on it.
 *   <li>{@code ledger.reconciliation.open_breaks} -- differences against the bank
 *       that no rule could explain, and that are therefore growing until someone
 *       looks.
 *   <li>{@code ledger.authorizations.open} -- how much unsettled risk is sitting on
 *       the books right now.
 * </ul>
 *
 * <p>Refreshed on a timer rather than computed per scrape, so that pointing three
 * Prometheus replicas at the service does not turn a dashboard into load.
 */
@Component
public class LedgerMetrics {

    private static final Logger log = LoggerFactory.getLogger(LedgerMetrics.class);

    private final BalanceService balances;
    private final ReconciliationService reconciliation;
    private final AuthorizationRepository authorizations;
    private final MultiGauge trialBalanceResidual;
    private final AtomicInteger openBreaks = new AtomicInteger();
    private final AtomicInteger openAuthorizations = new AtomicInteger();

    public LedgerMetrics(BalanceService balances,
                        ReconciliationService reconciliation,
                        AuthorizationRepository authorizations,
                        MeterRegistry registry) {
        this.balances = balances;
        this.reconciliation = reconciliation;
        this.authorizations = authorizations;
        this.trialBalanceResidual = MultiGauge.builder("ledger.trial_balance.residual")
                .description("Signed sum of all balances in a currency; must always be zero")
                .baseUnit("minor units")
                .register(registry);
        registry.gauge("ledger.reconciliation.open_breaks", openBreaks);
        registry.gauge("ledger.authorizations.open", openAuthorizations);
    }

    @Scheduled(fixedDelayString = "${ledger.metrics.refresh-ms:30000}", initialDelayString = "${ledger.metrics.initial-delay-ms:10000}")
    public void refresh() {
        try {
            List<TrialBalanceLine> lines = balances.trialBalance();
            trialBalanceResidual.register(lines.stream()
                    .map(line -> MultiGauge.Row.of(Tags.of("currency", line.currencyCode()), line.residualMinor()))
                    .toList(), true);
            openBreaks.set(reconciliation.openBreakCount());
            openAuthorizations.set(authorizations.findPending().size());
        } catch (RuntimeException e) {
            log.warn("could not refresh ledger metrics", e);
        }
    }
}
