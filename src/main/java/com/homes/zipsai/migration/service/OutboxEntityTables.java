package com.homes.zipsai.migration.service;

import java.util.HashMap;
import java.util.Map;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.persister.entity.AbstractEntityPersister;
import org.springframework.stereotype.Component;

@Component
public class OutboxEntityTables {

    private final Map<Class<?>, String> tableNamesByEntityClass = new HashMap<>();
    private final Map<String, String> idColumnsByTableName = new HashMap<>();

    public OutboxEntityTables(EntityManagerFactory entityManagerFactory) {
        entityManagerFactory.unwrap(SessionFactoryImplementor.class)
            .getMappingMetamodel()
            .forEachEntityDescriptor(persister -> {
                AbstractEntityPersister entityPersister = (AbstractEntityPersister) persister;
                tableNamesByEntityClass.put(entityPersister.getMappedClass(), entityPersister.getTableName());
                idColumnsByTableName.put(entityPersister.getTableName(), entityPersister.getIdentifierColumnNames()[0]);
            });
    }

    public String getTableName(Class<?> entityClass) {
        String tableName = tableNamesByEntityClass.get(entityClass);
        if (tableName == null) {
            throw new IllegalArgumentException("엔티티 테이블이 아닙니다: " + entityClass.getName());
        }
        return tableName;
    }

    public String getIdColumn(String tableName) {
        String idColumn = idColumnsByTableName.get(tableName);
        if (idColumn == null) {
            throw new IllegalArgumentException("엔티티 테이블이 아닙니다: " + tableName);
        }
        return idColumn;
    }
}
