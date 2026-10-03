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
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * A {@link TypeAdapterFactory} for maps that checks for collisions between the JSON field names of
 * map keys <em>before</em> writing any output.
 *
 * <p>When a map is serialized as a JSON object, each key is converted to a field name using the
 * map's key type adapter. Keys of different Java types can produce the same field name. For example
 * the integer {@code 1} and the string {@code "1"} both become the field name {@code "1"}, and a
 * {@code null} key and the string {@code "null"} both become {@code "null"}. Such collisions would
 * silently overwrite each other in the emitted JSON object, losing data.
 *
 * <p>This factory detects these collisions and rejects them with a {@link JsonSyntaxException}
 * that reports the positions of both colliding keys. The check is performed entirely before any
 * output is written, so a failed check never produces partial JSON and never calls the value
 * adapter. The input map is never modified.
 *
 * <p>Complex keys (keys that serialize to a JSON object or array) are explicitly not supported by
 * this factory and are rejected with a {@link JsonSyntaxException} reporting the key's position.
 *
 * <p>This factory is <strong>not</strong> registered by default; the default map serialization is
 * unchanged. Register it explicitly when the additional safety is desired:
 *
 * <pre>{@code
 * Gson gson =
 *     new GsonBuilder().registerTypeAdapterFactory(new CheckedMapTypeAdapterFactory()).create();
 * }</pre>
 */
public final class CheckedMapTypeAdapterFactory implements TypeAdapterFactory {
  private final ConstructorConstructor constructorConstructor;

  /** Creates a factory using a default constructor constructor. */
  public CheckedMapTypeAdapterFactory() {
    this(new ConstructorConstructor(Collections.emptyMap(), true, Collections.emptyList()));
  }

  /** Creates a factory using the given constructor constructor. */
  public CheckedMapTypeAdapterFactory(ConstructorConstructor constructorConstructor) {
    this.constructorConstructor = Objects.requireNonNull(constructorConstructor);
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

      Map<K, V> map = constructor.construct();

      if (peek == JsonToken.BEGIN_ARRAY) {
        in.beginArray();
        while (in.hasNext()) {
          in.beginArray(); // entry array
          K key = keyTypeAdapter.read(in);
          if (map.containsKey(key)) {
            throw new JsonSyntaxException(
                "Duplicate key '" + key + "'; at path " + in.getPreviousPath());
          }
          V value = valueTypeAdapter.read(in);
          map.put(key, value);
          in.endArray();
        }
        in.endArray();
      } else {
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
      }
      return map;
    }

    @Override
    public void write(JsonWriter out, Map<K, V> map) throws IOException {
      if (map == null) {
        out.nullValue();
        return;
      }

      int size = map.size();
      // Precompute all field names before writing anything. Each key adapter is invoked exactly
      // once here; the resulting JSON element is reused when writing the name. This ensures a
      // collision never leaves partial output behind and no value adapter is ever called.
      String[] names = new String[size];
      Object[] values = new Object[size];
      int index = 0;
      for (Map.Entry<K, V> entry : map.entrySet()) {
        K key = entry.getKey();
        JsonElement keyElement = keyTypeAdapter.toJsonTree(key);
        names[index] = keyToString(keyElement, index);
        values[index] = entry.getValue();
        index++;
      }

      for (int i = 0; i < size; i++) {
        for (int j = i + 1; j < size; j++) {
          if (names[i].equals(names[j])) {
            throw new JsonSyntaxException(
                "Map keys at positions "
                    + i
                    + " and "
                    + j
                    + " both serialize to the same field name '"
                    + names[i]
                    + "'");
          }
        }
      }

      out.beginObject();
      for (int i = 0; i < size; i++) {
        out.name(names[i]);
        @SuppressWarnings("unchecked")
        V value = (V) values[i];
        valueTypeAdapter.write(out, value);
      }
      out.endObject();
    }

    private String keyToString(JsonElement keyElement, int position) {
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
        throw new JsonSyntaxException(
            "Map key at position "
                + position
                + " is a complex key ("
                + (keyElement.isJsonObject() ? "object" : "array")
                + ") which is not supported by the checked map adapter");
      }
    }
  }
}
