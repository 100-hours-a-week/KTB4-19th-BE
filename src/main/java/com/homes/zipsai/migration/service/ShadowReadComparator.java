package com.homes.zipsai.migration.service;

import java.lang.reflect.RecordComponent;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.homes.zipsai.migration.config.ConditionalOnMigrationEnabled;
import com.homes.zipsai.migration.domain.MigrationDatabase;
import com.homes.zipsai.migration.domain.ShadowReadComparison;
import com.homes.zipsai.migration.domain.ShadowReadComparison.FieldDifference;
import com.homes.zipsai.migration.domain.ShadowReadQuery;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnMigrationEnabled
@RequiredArgsConstructor
public class ShadowReadComparator {

    private static final Pattern URL_QUERY = Pattern.compile("(https?://[^\"?\\s]+)\\?[^\"\\s]*");
    private static final String ID_SUFFIX = "Id";

    private final ObjectMapper objectMapper;

    public ShadowReadComparison compare(ShadowReadQuery firstQuery, ShadowReadQuery secondQuery) {
        ShadowReadQuery v1Query = firstQuery.database() == MigrationDatabase.V1 ? firstQuery : secondQuery;
        ShadowReadQuery v2Query = firstQuery.database() == MigrationDatabase.V1 ? secondQuery : firstQuery;
        Object v1Value = normalize(v1Query.result());
        Object v2Value = normalize(v2Query.result());
        Map<String, Object> v1Items = itemsByKey(v1Value);
        Map<String, Object> v2Items = itemsByKey(v2Value);

        List<String> v1CommonKeys = commonKeys(v1Items, v2Items);
        List<String> v2CommonKeys = commonKeys(v2Items, v1Items);
        List<FieldDifference> fieldDifferences = new ArrayList<>();
        for (String key : v1CommonKeys) {
            addFieldDifferences(fieldDifferences, key, v1Items.get(key), v2Items.get(key));
        }
        return ShadowReadComparison.of(v1Query, v2Query, keysMissingFrom(v1Items, v2Items),
            keysMissingFrom(v2Items, v1Items), !v1CommonKeys.equals(v2CommonKeys), fieldDifferences);
    }

    private Object normalize(Object value) {
        return switch (value) {
            case null -> null;
            case String text -> URL_QUERY.matcher(text).replaceAll("$1");
            case Number _, Boolean _, Enum<?> _, TemporalAccessor _ -> value;
            case Optional<?> optional -> normalize(optional.orElse(null));
            case Collection<?> values -> values.stream().map(this::normalize).toList();
            case Record record -> componentsOf(record);
            default -> URL_QUERY.matcher(objectMapper.writeValueAsString(value)).replaceAll("$1");
        };
    }

    private Map<String, Object> componentsOf(Record record) {
        Map<String, Object> components = new LinkedHashMap<>();
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            components.put(component.getName(), normalize(valueOf(record, component)));
        }
        return components;
    }

    private static Object valueOf(Record record, RecordComponent component) {
        try {
            return component.getAccessor().invoke(record);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Map<String, Object> itemsByKey(Object value) {
        Map<String, Object> items = new LinkedHashMap<>();
        if (!(value instanceof List<?>)) {
            items.put(ShadowReadComparison.ROOT_KEY, withoutList(value));
        }
        List<?> list = listOf(value);
        if (list != null) {
            for (int index = 0; index < list.size(); index++) {
                items.put(keyOf(list.get(index), index), list.get(index));
            }
        }
        return items;
    }

    private static List<?> listOf(Object value) {
        return switch (value) {
            case List<?> list -> list;
            case Map<?, ?> fields -> fields.values().stream()
                .filter(field -> field instanceof List<?>)
                .map(field -> (List<?>) field)
                .findFirst()
                .orElse(null);
            case null, default -> null;
        };
    }

    private static Object withoutList(Object value) {
        if (!(value instanceof Map<?, ?> fields)) {
            return value;
        }
        Map<Object, Object> root = new LinkedHashMap<>(fields);
        fields.entrySet().stream()
            .filter(field -> field.getValue() instanceof List<?>)
            .findFirst()
            .ifPresent(field -> root.remove(field.getKey()));
        return root;
    }

    private static String keyOf(Object item, int index) {
        String idField = idFieldOf(item);
        if (idField == null) {
            return ShadowReadComparison.INDEX_KEY_PREFIX + index;
        }
        return String.valueOf(((Map<?, ?>) item).get(idField));
    }

    private static String idFieldOf(Object item) {
        if (!(item instanceof Map<?, ?> fields)) {
            return null;
        }
        for (Map.Entry<?, ?> field : fields.entrySet()) {
            if (field.getKey() instanceof String name && name.endsWith(ID_SUFFIX) && isScalar(field.getValue())) {
                return name;
            }
        }
        return null;
    }

    private static boolean isScalar(Object value) {
        return value != null && !(value instanceof Map<?, ?>) && !(value instanceof List<?>);
    }

    private static List<String> keysMissingFrom(Map<String, Object> items, Map<String, Object> otherItems) {
        return items.keySet().stream()
            .filter(key -> !otherItems.containsKey(key))
            .toList();
    }

    private static List<String> commonKeys(Map<String, Object> items, Map<String, Object> otherItems) {
        return items.keySet().stream()
            .filter(otherItems::containsKey)
            .toList();
    }

    private void addFieldDifferences(List<FieldDifference> differences, String key, Object v1Item,
                                     Object v2Item) {
        if (Objects.equals(v1Item, v2Item)) {
            return;
        }
        if (!(v1Item instanceof Map<?, ?> v1Fields) || !(v2Item instanceof Map<?, ?> v2Fields)) {
            differences.add(new FieldDifference(key, ShadowReadComparison.ROOT_KEY,
                loggedValue(ShadowReadComparison.ROOT_KEY, v1Item),
                loggedValue(ShadowReadComparison.ROOT_KEY, v2Item)));
            return;
        }
        Set<Object> fields = new LinkedHashSet<>(v1Fields.keySet());
        fields.addAll(v2Fields.keySet());
        for (Object field : fields) {
            Object v1Value = v1Fields.get(field);
            Object v2Value = v2Fields.get(field);
            if (!Objects.equals(v1Value, v2Value)) {
                String name = String.valueOf(field);
                differences.add(new FieldDifference(key, name, loggedValue(name, v1Value),
                    loggedValue(name, v2Value)));
            }
        }
    }

    private String loggedValue(String field, Object value) {
        if (value == null) {
            return null;
        }
        String json = objectMapper.writeValueAsString(value);
        String plainText = isScalar(value) ? textOf(json) : null;
        return ShadowReadValues.mask(field, plainText, json);
    }

    private String textOf(String json) {
        return json.startsWith("\"") ? objectMapper.readValue(json, String.class) : json;
    }
}
