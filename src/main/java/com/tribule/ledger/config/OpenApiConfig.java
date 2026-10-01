package com.tribule.ledger.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ledgerOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Multi-Currency Settlement Ledger")
                .version("1.0.0")
                .description("""
                        An append-only double-entry ledger that holds several currencies at once, prices \
                        conversions from bitemporal FX rates, measures the exposure carried between \
                        authorization and settlement, and reconciles itself against external statements.

                        Every mutating endpoint requires an `Idempotency-Key` header. Retrying a request \
                        with the same key and the same body replays the original response rather than \
                        posting a second transaction.
                        """)
                .license(new License().name("MIT")));
    }
}
