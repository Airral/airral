package com.airral.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ R2dbcProperties.class, FlywayProperties.class })
public class FlywayConfig {

    /**
     * How long to keep trying the first connection, and how often.
     *
     * <p>This exists because of what happens on a cold start. The database is
     * powered down overnight to halve its cost, so a container starting around
     * 08:00 ET may reach Flyway a few seconds before Postgres is accepting
     * connections. That used to mean the first request of the morning, when
     * Cloud Run ran the API at min-instances 0; the deploy now pins the floor
     * at 1, which makes the race more likely to be hit rather than less --
     * Cloud Run is already retrying a replacement instance at the moment the
     * database comes back, instead of waiting for a caller to arrive.
     * Flyway would fail, the bean would fail, the context would
     * abort and the container would exit. Cloud Run then retried into the same
     * race, and the whole API sat in a crash loop until the database happened
     * to settle. That took the service down for real.
     *
     * <p>Sized against the startup probe rather than against how long a Cloud
     * SQL start takes. The deploy allows 30 checks at 10 second intervals, so a
     * container has roughly 300 seconds to become ready, and Spring Boot needs
     * about 20 of those.
     *
     * <p>The interval is a ceiling, not a fixed delay: Flyway backs off
     * exponentially and caps at this value, so twenty retries at five seconds
     * is 1 + 2 + 4 then seventeen more at five -- about 92 seconds in total,
     * confirmed by running it against a closed port. Worth knowing before
     * raising either number, since the product is what has to fit the probe.
     *
     * <p>Deliberately not long enough to cover a database that is switched off.
     * A container that starts and then serves errors from every endpoint is
     * worse than one that fails its readiness check: the failing check is
     * visible, keeps traffic away, and is the honest signal that the dependency
     * is gone. This only bridges the gap where the database is coming up.
     */
    private static final int CONNECT_RETRIES = 20;
    private static final int CONNECT_RETRIES_INTERVAL_SECONDS = 5;

    @Bean(initMethod = "migrate")
    public Flyway flyway(FlywayProperties flywayProperties, R2dbcProperties r2dbcProperties) {
        return Flyway.configure()
                .dataSource(
                        flywayProperties.getUrl() != null ? flywayProperties.getUrl() :
                        r2dbcProperties.getUrl().replace("r2dbc:", "jdbc:"),
                        flywayProperties.getUser() != null ? flywayProperties.getUser() :
                        r2dbcProperties.getUsername(),
                        flywayProperties.getPassword() != null ? flywayProperties.getPassword() :
                        r2dbcProperties.getPassword()
                )
                .locations(flywayProperties.getLocations().stream().toArray(String[]::new))
                .baselineOnMigrate(flywayProperties.isBaselineOnMigrate())
                // Flyway's own retry, rather than a wrapper around migrate().
                // It retries the connection specifically, which is the thing
                // that fails, and leaves migration failures to fail fast -- a
                // broken migration should never be retried twenty times.
                .connectRetries(CONNECT_RETRIES)
                .connectRetriesInterval(CONNECT_RETRIES_INTERVAL_SECONDS)
                .load();
    }
}
