package com.homes.zipsai.migration.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.homes.zipsai.global.util.TextUtils;
import com.homes.zipsai.migration.config.ConditionalOnMigrationEnabled;
import com.homes.zipsai.migration.domain.MigrationDatabase;
import com.homes.zipsai.migration.domain.ShadowReadComparison;
import com.homes.zipsai.migration.domain.ShadowReadComparison.FieldDifference;
import com.homes.zipsai.migration.domain.ShadowReadQuery;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
@ConditionalOnMigrationEnabled
@RequiredArgsConstructor
public class ShadowReadComparator {

    private static final Pattern URL_QUERY = Pattern.compile("(https?://[^\"?]+)\\?[^\"]*");
    private static final Pattern PLAIN_VALUE_FIELD = Pattern.compile(".*(Id|At|Code|Status|Type|Label|Count)|hasNext");
    private static final String ID_SUFFIX = "Id";
    private static final String ROOT_KEY = "$";
    private static final String INDEX_KEY_PREFIX = "#";
    private static final String HASH_PREFIX = "sha256:";
    private static final int HASH_LENGTH = 16;
    private static final int VALUE_MAX_LENGTH = 100;

    private final ObjectMapper objectMapper;

    public ShadowReadComparison compare(ShadowReadQuery firstQuery, ShadowReadQuery secondQuery) {
        ShadowReadQuery v1Query = firstQuery.database() == MigrationDatabase.V1 ? firstQuery : secondQuery;
        ShadowReadQuery v2Query = firstQuery.database() == MigrationDatabase.V1 ? secondQuery : firstQuery;
        Map<String, JsonNode> v1Items = itemsByKey(toTree(v1Query.result()));
        Map<String, JsonNode> v2Items = itemsByKey(toTree(v2Query.result()));

        List<String> onlyInV1Ids = keysMissingFrom(v1Items, v2Items);
        List<String> onlyInV2Ids = keysMissingFrom(v2Items, v1Items);
        List<String> v1CommonKeys = commonKeys(v1Items, v2Items);
        List<String> v2CommonKeys = commonKeys(v2Items, v1Items);
        List<FieldDifference> fieldDifferences = new ArrayList<>();
        for (String key : v1CommonKeys) {
            addFieldDifferences(fieldDifferences, key, v1Items.get(key), v2Items.get(key));
        }
        return ShadowReadComparison.of(v1Query, v2Query, onlyInV1Ids, onlyInV2Ids,
            !v1CommonKeys.equals(v2CommonKeys), fieldDifferences);
    }

    private JsonNode toTree(Object result) {
        String json = URL_QUERY.matcher(objectMapper.writeValueAsString(result)).replaceAll("$1");
        return objectMapper.readTree(json);
    }

    private static Map<String, JsonNode> itemsByKey(JsonNode tree) {
        Map<String, JsonNode> items = new LinkedHashMap<>();
        if (tree.isArray()) {
            addListItems(items, tree);
            return items;
        }
        String listField = findListField(tree);
        if (listField == null) {
            items.put(ROOT_KEY, tree);
            return items;
        }
        ObjectNode root = ((ObjectNode) tree).deepCopy();
        root.remove(listField);
        items.put(ROOT_KEY, root);
        addListItems(items, tree.get(listField));
        return items;
    }

    private static String findListField(JsonNode tree) {
        if (!tree.isObject()) {
            return null;
        }
        for (Map.Entry<String, JsonNode> property : tree.properties()) {
            if (property.getValue().isArray()) {
                return property.getKey();
            }
        }
        return null;
    }

    private static void addListItems(Map<String, JsonNode> items, JsonNode list) {
        for (int index = 0; index < list.size(); index++) {
            JsonNode item = list.get(index);
            items.put(keyOf(item, index), item);
        }
    }

    private static String keyOf(JsonNode item, int index) {
        if (item.isObject()) {
            for (Map.Entry<String, JsonNode> property : item.properties()) {
                if (property.getKey().endsWith(ID_SUFFIX) && property.getValue().isValueNode()
                    && !property.getValue().isNull()) {
                    return property.getValue().asString();
                }
            }
        }
        return INDEX_KEY_PREFIX + index;
    }

    private static List<String> keysMissingFrom(Map<String, JsonNode> items, Map<String, JsonNode> otherItems) {
        List<String> keys = new ArrayList<>();
        for (String key : items.keySet()) {
            if (!otherItems.containsKey(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static List<String> commonKeys(Map<String, JsonNode> items, Map<String, JsonNode> otherItems) {
        List<String> keys = new ArrayList<>();
        for (String key : items.keySet()) {
            if (otherItems.containsKey(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static void addFieldDifferences(List<FieldDifference> differences, String key, JsonNode v1Item,
                                            JsonNode v2Item) {
        if (v1Item.equals(v2Item)) {
            return;
        }
        if (!v1Item.isObject() || !v2Item.isObject()) {
            differences.add(new FieldDifference(key, ROOT_KEY, hash(v1Item), hash(v2Item)));
            return;
        }
        Set<String> fields = new LinkedHashSet<>(v1Item.propertyNames());
        fields.addAll(v2Item.propertyNames());
        for (String field : fields) {
            JsonNode v1Value = v1Item.get(field);
            JsonNode v2Value = v2Item.get(field);
            if (v1Value == null || !v1Value.equals(v2Value)) {
                differences.add(new FieldDifference(key, field, loggedValue(field, v1Value),
                    loggedValue(field, v2Value)));
            }
        }
    }

    private static String loggedValue(String field, JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (PLAIN_VALUE_FIELD.matcher(field).matches() && value.isValueNode()) {
            return TextUtils.truncate(value.asString(), VALUE_MAX_LENGTH);
        }
        return hash(value);
    }

    private static String hash(JsonNode value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toString().getBytes(StandardCharsets.UTF_8));
            return HASH_PREFIX + HexFormat.of().formatHex(digest).substring(0, HASH_LENGTH);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
