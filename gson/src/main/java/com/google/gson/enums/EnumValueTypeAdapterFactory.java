/*
 * Copyright (C) 2026 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.gson.enums;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Type adapter factory which adds forward-compatible enum handling to fields and types declared as
 * {@link EnumValue}{@code <E>}. It does <b>not</b> change the handling of plain enum types; plain
 * enums are still deserialized to {@code null} for unknown strings by default.
 *
 * <h2>Registration</h2>
 *
 * <p>The factory is not registered by default and must be added explicitly:
 *
 * <pre>{@code
 * Gson gson = new GsonBuilder()
 *     .registerTypeAdapterFactory(new EnumValueTypeAdapterFactory())
 *     .create();
 * }</pre>
 *
 * <p>After registration, {@code EnumValue<E>} is recognized even when nested inside generic
 * structures, for example {@code List<EnumValue<Color>>} or {@code Map<String, EnumValue<Color>>}.
 *
 * <h2>Read behavior</h2>
 *
 * <ul>
 *   <li>A string matching the {@link SerializedName#value()} or any {@link
 *       SerializedName#alternate()} of a constant (or the declared constant name) deserializes to
 *       the known constant.
 *   <li>Any other string is preserved verbatim as an unknown value ({@link EnumValue#isUnknown()}).
 *   <li>An explicit JSON {@code null} deserializes to the null state ({@link EnumValue#isNull()}),
 *       which is distinct from an unknown string.
 *   <li>JSON numbers and booleans are rejected with a {@link JsonSyntaxException}; they are never
 *       coerced into an enum string.
 * </ul>
 *
 * <h2>Write behavior</h2>
 *
 * <p>Known constants are written using their canonical serialized name (the {@code value} of
 * {@code @SerializedName} if present, otherwise the declared constant name); the user-defined
 * {@code toString()} implementation of the enum is never called. Unknown values are written back
 * using the preserved original string, and the null state is written as JSON {@code null}.
 *
 * <p>Building the adapter for an enum type fails fast with an {@link IllegalArgumentException} if
 * two different constants claim the same serialized name or alternate; the last declaration never
 * silently overwrites the previous one.
 */
public final class EnumValueTypeAdapterFactory implements TypeAdapterFactory {
  @SuppressWarnings({"rawtypes", "unchecked"})
  @Override
  public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> typeToken) {
    Class<? super T> rawType = typeToken.getRawType();
    if (rawType != EnumValue.class) {
      return null;
    }

    Type type = typeToken.getType();
    Type enumType;
    if (type instanceof ParameterizedType) {
      Type[] typeArguments = ((ParameterizedType) type).getActualTypeArguments();
      enumType = typeArguments[0];
      if (enumType instanceof WildcardType) {
        enumType = ((WildcardType) enumType).getUpperBounds()[0];
      }
    } else {
      // Raw EnumValue usage: the enum element type is not known.
      throw new IllegalArgumentException(
          "EnumValue requires an explicit enum type argument, for example EnumValue<MyEnum>;"
              + " raw EnumValue is not supported");
    }

    Class<?> enumClass = TypeToken.get(enumType).getRawType();
    if (!Enum.class.isAssignableFrom(enumClass) || enumClass == Enum.class) {
      throw new IllegalArgumentException(
          "EnumValue requires an enum type argument, but got: " + enumType);
    }
    if (!enumClass.isEnum()) {
      // Handle anonymous enum subclasses (constant-specific bodies).
      enumClass = enumClass.getSuperclass();
    }

    return (TypeAdapter<T>) new EnumValueTypeAdapter<>(enumClass);
  }

  /**
   * Taken from Java 19 method {@code HashMap.newHashMap}, using default load factor {@code 0.75F}.
   */
  private static int calculateHashMapCapacity(int numMappings) {
    return (int) Math.ceil(numMappings / 0.75F);
  }

  private static final class EnumValueTypeAdapter<E extends Enum<E>>
      extends TypeAdapter<EnumValue<E>> {
    private final Map<String, E> stringToConstant;
    private final Map<E, String> constantToCanonicalName;

    @SuppressWarnings("unchecked")
    EnumValueTypeAdapter(Class<?> rawEnumClass) {
      Class<E> enumClass = (Class<E>) rawEnumClass;
      try {
        // Uses reflection to find enum constants to work around name mismatches for obfuscated
        // classes, mirroring the built-in EnumTypeAdapter.
        Field[] fields = enumClass.getDeclaredFields();
        int constantCount = 0;
        for (Field f : fields) {
          if (f.isEnumConstant()) {
            fields[constantCount++] = f;
          }
        }
        fields = Arrays.copyOf(fields, constantCount);

        int hashMapCapacity = calculateHashMapCapacity(constantCount);
        stringToConstant = new HashMap<>(hashMapCapacity);
        constantToCanonicalName = new HashMap<>(hashMapCapacity);

        AccessibleObject.setAccessible(fields, true);

        for (Field constantField : fields) {
          E constant = (E) constantField.get(null);
          String canonicalName = constant.name();

          SerializedName annotation = constantField.getAnnotation(SerializedName.class);
          if (annotation != null) {
            canonicalName = annotation.value();
            for (String alternate : annotation.alternate()) {
              putStringMapping(enumClass, alternate, constant);
            }
          }
          putStringMapping(enumClass, canonicalName, constant);
          constantToCanonicalName.put(constant, canonicalName);
        }
      } catch (IllegalAccessException e) {
        // Impossible due to the setAccessible call above; enum constants are implicitly public.
        throw new AssertionError(e);
      }
    }

    private void putStringMapping(Class<?> enumClass, String jsonName, E constant) {
      E existing = stringToConstant.get(jsonName);
      if (existing != null && existing != constant) {
        throw new IllegalArgumentException(
            "Enum type "
                + enumClass.getName()
                + " has multiple constants claiming the serialized name/alternate '"
                + jsonName
                + "': '"
                + existing.name()
                + "' and '"
                + constant.name()
                + "'");
      }
      // Duplicate mappings declared by the same constant (e.g. alternate equal to value) are
      // harmless and ignored.
      stringToConstant.put(jsonName, constant);
    }

    @Override
    public EnumValue<E> read(JsonReader in) throws IOException {
      JsonToken token = in.peek();
      switch (token) {
        case NULL:
          in.nextNull();
          return EnumValue.ofNull();
        case STRING:
          String jsonString = in.nextString();
          E constant = stringToConstant.get(jsonString);
          if (constant != null) {
            return EnumValue.of(constant);
          }
          return EnumValue.unknown(jsonString);
        default:
          // Reject numbers and booleans (and structures) instead of silently coercing them to a
          // string, which could be mistaken for an unknown enum value.
          throw new JsonSyntaxException(
              "Expected a JSON string for enum "
                  + enumTypeName()
                  + " but got "
                  + token
                  + " at path "
                  + in.getPath());
      }
    }

    private String enumTypeName() {
      // Use only declared constant names for the message; never calls enum toString().
      StringBuilder sb = new StringBuilder("[");
      boolean first = true;
      for (E constant : constantToCanonicalName.keySet()) {
        if (!first) {
          sb.append(", ");
        }
        first = false;
        sb.append(constant.name());
      }
      return sb.append(']').toString();
    }

    @Override
    public void write(JsonWriter out, EnumValue<E> value) throws IOException {
      if (value == null) {
        out.nullValue();
        return;
      }
      if (value.isKnown()) {
        // Look up the canonical name; never calls constant.toString().
        out.value(constantToCanonicalName.get(value.getConstant()));
      } else if (value.isUnknown()) {
        out.value(value.getRawValue());
      } else {
        out.nullValue();
      }
    }
  }
}
