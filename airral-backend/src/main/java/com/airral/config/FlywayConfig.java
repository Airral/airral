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
                .load();
    }
}
