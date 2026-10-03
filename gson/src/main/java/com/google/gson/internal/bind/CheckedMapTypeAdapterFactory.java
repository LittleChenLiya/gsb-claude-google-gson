/*
 * Copyright (C) 2026 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.gson.internal.bind;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonIOException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.internal.ConstructorConstructor;
import com.google.gson.internal.GsonTypes;
import com.google.gson.internal.JsonReaderInternalAccess;
import com.google.gson.internal.ObjectConstructor;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link TypeAdapterFactory} for {@link Map Maps} that serializes to a JSON object only after
 * checking upfront that all keys produce distinct JSON field names.
 *
 * <p>When a {@link Map} is written as a JSON object, keys of different types can collapse onto the
 * same JSON field name. For example the integer key {@code 1} and the string key {@code "1"} both
 * become the field name {@code "1"}, and a {@code null} key and the string key {@code "null"} both
 * become the field name {@code "null"}. The default map adapter silently emits duplicate field
 * names in that case, so data is lost when the JSON is parsed again. This factory instead performs
 * a <em>pre-flight check</em>:
 *
 * <ul>
 *   <li>Before anything is written to the {@link JsonWriter}, every key is converted exactly once
 *       with the key adapter and the resulting JSON field names are collected.
 *   <li>If two keys map to the same field name, a {@link JsonIOException} is thrown reporting the
 *       iteration positions of both offending keys. Nothing has been written to the writer at
 *       that point and the value adapter has not been invoked.
 *   <li>Keys that serialize to a JSON object or JSON array ("complex keys") are not supported by
 *       this adapter and are rejected with a {@link JsonIOException} during the same pre-flight
 *       pass. Use {@code GsonBuilder.enableComplexMapKeySerialization()} or a custom adapter for
 *       such maps instead.
 *   <li>If all field names are distinct, the values are written in the original iteration order
 *       of the map. The input map is never modified, and no post-hoc scanning of the produced
 *       JSON is needed to detect data loss.
 * </ul>
 *
 * <p>This adapter is strictly opt-in; the default {@code Map} serialization is unchanged. Register
 * it explicitly, for example:
 *
 * <pre>{@code
 * Gson gson = new GsonBuilder()
 *     .registerTypeAdapterFactory(new CheckedMapTypeAdapterFactory())
 *     .create();
 * }</pre>
 */
public final class CheckedMapTypeAdapterFactory implements TypeAdapterFactory {
  private final ConstructorConstructor constructorConstructor;

  /** Creates a factory which uses default no-args constructors when deserializing maps. */
  public CheckedMapTypeAdapterFactory() {
    this(new ConstructorConstructor(Collections.emptyMap(), true, Collections.emptyList()));
  }

  public CheckedMapTypeAdapterFactory(ConstructorConstructor constructorConstructor) {
    this.constructorConstructor = constructorConstructor;
  }

  @Override
  public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> typeToken) {
    Type type = typeToken.getType();

    Class<? super T> rawType = typeToken.getRawType();
    if (!Map.class.isAssignableFrom(rawType)) {
      return null;
    }

    Type[] keyAndValueTypes = GsonTypes.getMapKeyAndValueTypes(type, rawType);
    Type keyType = keyAndValueTypes[0];
    Type valueType = keyAndValueTypes[1];
    TypeAdapter<?> keyAdapter = getKeyAdapter(gson, keyType);
    TypeAdapter<?> wrappedKeyAdapter =
        new TypeAdapterRuntimeTypeWrapper<>(gson, keyAdapter, keyType);
    TypeAdapter<?> valueAdapter = gson.getAdapter(TypeToken.get(valueType));
    TypeAdapter<?> wrappedValueAdapter =
        new TypeAdapterRuntimeTypeWrapper<>(gson, valueAdapter, valueType);
    // Don't allow Unsafe usage to create instance; instances might be in broken state and calling
    // Map methods could lead to confusing exceptions
    boolean allowUnsafe = false;
    ObjectConstructor<T> constructor = constructorConstructor.get(typeToken, allowUnsafe);

    @SuppressWarnings({"unchecked", "rawtypes"})
    // we don't define a type parameter for the key or value types
    TypeAdapter<T> result = new Adapter(wrappedKeyAdapter, wrappedValueAdapter, constructor);
    return result;
  }

  /** Returns a type adapter that writes the value as a string. */
  private TypeAdapter<?> getKeyAdapter(Gson context, Type keyType) {
    return (keyType == boolean.class || keyType == Boolean.class)
        ? TypeAdapters.BOOLEAN_AS_STRING
        : context.getAdapter(TypeToken.get(keyType));
  }

  private static final class Adapter<K, V> extends TypeAdapter<Map<K, V>> {
    private final TypeAdapter<K> keyTypeAdapter;
    private final TypeAdapter<V> valueTypeAdapter;
    private final ObjectConstructor<? extends Map<K, V>> constructor;

    Adapter(
        TypeAdapter<K> keyTypeAdapter,
        TypeAdapter<V> valueTypeAdapter,
        ObjectConstructor<? extends Map<K, V>> constructor) {
      this.keyTypeAdapter = keyTypeAdapter;
      this.valueTypeAdapter = valueTypeAdapter;
      this.constructor = constructor;
    }

    @Override
    public Map<K, V> read(JsonReader in) throws IOException {
      JsonToken peek = in.peek();
      if (peek == JsonToken.NULL) {
        in.nextNull();
        return null;
      }
      if (peek == JsonToken.BEGIN_ARRAY) {
        throw new JsonSyntaxException(
            "Checked map adapter only supports maps as JSON objects; complex map keys are not"
                + " supported; at path "
                + in.getPath());
      }

      Map<K, V> map = constructor.construct();
      in.beginObject();
      while (in.hasNext()) {
        JsonReaderInternalAccess.INSTANCE.promoteNameToValue(in);
        K key = keyTypeAdapter.read(in);
        if (map.containsKey(key)) {
          throw new JsonSyntaxException(
              "Duplicate key '" + key + "'; at path " + in.getPreviousPath());
        }
        V value = valueTypeAdapter.read(in);
        map.put(key, value);
      }
      in.endObject();
      return map;
    }

    @Override
    public void write(JsonWriter out, Map<K, V> map) throws IOException {
      if (map == null) {
        out.nullValue();
        return;
      }

      // Pre-flight pass: convert every key exactly once and collect the resulting JSON field
      // names before anything is written to `out`. If this pass fails, the writer is still
      // untouched and the value adapter has never been invoked.
      int size = map.size();
      List<String> fieldNames = new ArrayList<>(size);
      List<V> values = new ArrayList<>(size);
      Map<String, Integer> firstPositionByFieldName = new HashMap<>(size);
      int position = 0;
      for (Map.Entry<K, V> entry : map.entrySet()) {
        K key = entry.getKey();
        JsonElement keyElement = keyTypeAdapter.toJsonTree(key);
        String fieldName = keyToFieldName(keyElement, position, key);
        Integer previousPosition = firstPositionByFieldName.putIfAbsent(fieldName, position);
        if (previousPosition != null) {
          throw new JsonIOException(
              "Map keys at positions "
                  + previousPosition
                  + " and "
                  + position
                  + " both serialize to the JSON field name \""
                  + fieldName
                  + "\" (keys: "
                  + keyDescription(keyAtPosition(map, previousPosition))
                  + " and "
                  + keyDescription(key)
                  + "); refusing to serialize because data would be lost");
        }
        fieldNames.add(fieldName);
        values.add(entry.getValue());
        position++;
      }

      // All field names are distinct; write the values in the original iteration order.
      out.beginObject();
      for (int i = 0; i < position; i++) {
        out.name(fieldNames.get(i));
        valueTypeAdapter.write(out, values.get(i));
      }
      out.endObject();
    }

    private String keyToFieldName(JsonElement keyElement, int position, K key) {
      if (keyElement.isJsonPrimitive()) {
        JsonPrimitive primitive = keyElement.getAsJsonPrimitive();
        if (primitive.isNumber()) {
          return String.valueOf(primitive.getAsNumber());
        } else if (primitive.isBoolean()) {
          return Boolean.toString(primitive.getAsBoolean());
        } else if (primitive.isString()) {
          return primitive.getAsString();
        } else {
          throw new AssertionError();
        }
      } else if (keyElement.isJsonNull()) {
        return "null";
      } else {
        throw new JsonIOException(
            "Map key at position "
                + position
                + " ("
                + keyDescription(key)
                + ") serializes to a JSON object or array; complex map keys are not supported"
                + " by this adapter");
      }
    }

    private static String keyDescription(Object key) {
      return (key instanceof String) ? "\"" + key + "\"" : String.valueOf(key);
    }

    // Iterates the map again only to describe the first colliding key in the exception message;
    // this happens exclusively on the failure path, after the pre-flight pass has already
    // rejected the map and before anything was written.
    private static Object keyAtPosition(Map<?, ?> map, int position) {
      int i = 0;
      for (Object key : map.keySet()) {
        if (i == position) {
          return key;
        }
        i++;
      }
      return null;
    }
  }
}
