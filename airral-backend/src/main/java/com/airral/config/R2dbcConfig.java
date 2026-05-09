package com.airral.config;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.r2dbc.config.AbstractR2dbcConfiguration;
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.ReactiveTransactionManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Configuration
public class R2dbcConfig extends AbstractR2dbcConfiguration {

    private final ConnectionFactory connectionFactory;

    public R2dbcConfig(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public ConnectionFactory connectionFactory() {
        return connectionFactory;
    }

    @Bean
    @Override
    public R2dbcCustomConversions r2dbcCustomConversions() {
        List<Converter<?, ?>> converters = new ArrayList<>();
        converters.add(new StringListWriteConverter());
        converters.add(new StringArrayReadConverter());
        return new R2dbcCustomConversions(getStoreConversions(), converters);
    }

    @Bean
    public ReactiveTransactionManager reactiveTransactionManager() {
        return new R2dbcTransactionManager(connectionFactory);
    }

    @WritingConverter
    static class StringListWriteConverter implements Converter<List<String>, String[]> {
        @Override
        public String[] convert(List<String> source) {
            return source == null ? null : source.toArray(String[]::new);
        }
    }

    @ReadingConverter
    static class StringArrayReadConverter implements Converter<String[], List<String>> {
        @Override
        public List<String> convert(String[] source) {
            return source == null ? null : Arrays.asList(source);
        }
    }
}
