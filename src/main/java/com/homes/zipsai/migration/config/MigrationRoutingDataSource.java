package com.homes.zipsai.migration.config;

import java.util.Map;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import com.homes.zipsai.migration.domain.MigrationDatabase;

public class MigrationRoutingDataSource extends AbstractRoutingDataSource {

    private final Supplier<MigrationDatabase> databaseSelector;

    public MigrationRoutingDataSource(DataSource v1DataSource, DataSource v2DataSource,
                                      Supplier<MigrationDatabase> databaseSelector) {
        this.databaseSelector = databaseSelector;
        setTargetDataSources(Map.of(MigrationDatabase.V1, v1DataSource, MigrationDatabase.V2, v2DataSource));
        afterPropertiesSet();
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return databaseSelector.get();
    }
}
