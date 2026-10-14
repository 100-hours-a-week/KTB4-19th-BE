package com.homes.zipsai.migration.service;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.persister.entity.AbstractEntityPersister;
import org.springframework.stereotype.Component;

@Component
public class OutboxEntityTables {

    private static final String ID_FIELD_SUFFIX = "Id";

    private final Map<Class<?>, String> tableNamesByEntityClass = new HashMap<>();
    private final Map<String, String> idColumnsByTableName = new HashMap<>();
    private final Map<String, String> tableNamesByEntityName = new HashMap<>();

    public OutboxEntityTables(EntityManagerFactory entityManagerFactory) {
        entityManagerFactory.unwrap(SessionFactoryImplementor.class)
            .getMappingMetamodel()
            .forEachEntityDescriptor(persister -> {
                AbstractEntityPersister entityPersister = (AbstractEntityPersister) persister;
                tableNamesByEntityClass.put(entityPersister.getMappedClass(), entityPersister.getTableName());
                idColumnsByTableName.put(entityPersister.getTableName(), entityPersister.getIdentifierColumnNames()[0]);
                tableNamesByEntityName.put(entityNameOf(entityPersister.getMappedClass().getSimpleName()),
                    entityPersister.getTableName());
            });
    }

    public String getTableName(Class<?> entityClass) {
        String tableName = tableNamesByEntityClass.get(entityClass);
        if (tableName == null) {
            throw new IllegalArgumentException("엔티티 테이블이 아닙니다: " + entityClass.getName());
        }
        return tableName;
    }

    public Optional<String> findTableNameByIdField(String idField) {
        if (idField == null || !idField.endsWith(ID_FIELD_SUFFIX)) {
            return Optional.empty();
        }
        String entityName = idField.substring(0, idField.length() - ID_FIELD_SUFFIX.length());
        return Optional.ofNullable(tableNamesByEntityName.get(entityNameOf(entityName)));
    }

    public String getIdColumn(String tableName) {
        String idColumn = idColumnsByTableName.get(tableName);
        if (idColumn == null) {
            throw new IllegalArgumentException("엔티티 테이블이 아닙니다: " + tableName);
        }
        return idColumn;
    }

    private static String entityNameOf(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
