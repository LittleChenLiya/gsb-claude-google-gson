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

package com.google.gson;

import com.google.gson.annotations.SerializedName;
import com.google.gson.internal.GsonTypes;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * A type adapter factory for {@link EnumValue} that preserves unknown JSON strings instead of
 * silently converting them to {@code null}.
 *
 * <h2>Registration</h2>
 *
 * <p>This factory is not registered by default. Register it explicitly with a {@link GsonBuilder}:
 *
 * <pre>{@code
 * Gson gson = new GsonBuilder()
 *     .registerTypeAdapterFactory(new EnumValueTypeAdapterFactory())
 *     .create();
 * }</pre>
 *
 * <p>Once registered, fields of type {@code EnumValue<MyEnum>} (including generic collections such
 * as {@code List<EnumValue<MyEnum>>}) are handled. Plain {@code MyEnum} fields keep their existing
 * behavior.
 *
 * <h2>Reading</h2>
 *
 * <ul>
 *   <li>A JSON string that matches an enum constant (by {@link Enum#name() name}, {@link
 *       SerializedName#value()}, or {@link SerializedName#alternate() alternates}) produces a known
 *       {@code EnumValue}.
 *   <li>A JSON string that matches no constant produces an unknown {@code EnumValue} that
 *       preserves the original string.
 *   <li>JSON {@code null} produces a null {@code EnumValue}, distinct from an unknown value.
 *   <li>JSON numbers and booleans are rejected; they are not silently converted to strings.
 * </ul>
 *
 * <h2>Writing</h2>
 *
 * <ul>
 *   <li>A known value is written using the enum constant's {@link Enum#name() canonical name}; the
 *       enum's {@code toString()} method is never called.
 *   <li>An unknown value is written as its original JSON string.
 *   <li>A null value is written as JSON {@code null}.
 * </ul>
 *
 * <h2>Alias collisions</h2>
 *
 * <p>If two enum constants share an alias (for example two constants annotated with the same {@link
 * SerializedName#value()}), the adapter cannot be built and an {@link IllegalArgumentException} is
 * thrown. This prevents the last constant from silently overwriting the first.
 *
 * @since 2.14.1
 */
public final class EnumValueTypeAdapterFactory implements TypeAdapterFactory {

  @Override
  public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> typeToken) {
    Class<? super T> rawType = typeToken.getRawType();
    if (rawType != EnumValue.class) {
      return null;
    }

    Class<? extends Enum<?>> enumClass = resolveEnumClass(typeToken.getType(), rawType);
    if (enumClass == null) {
      // Raw or unresolvable type: preserve every string as unknown.
      @SuppressWarnings({"unchecked", "rawtypes"})
      TypeAdapter<T> adapter = (TypeAdapter<T>) new PreservingAdapter();
      return adapter;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    TypeAdapter<T> adapter = new Adapter(enumClass);
    return adapter;
  }

  /**
   * Resolves the enum class from the type argument of {@code EnumValue<T>}.
   *
   * @return the enum class, or {@code null} if it cannot be determined
   */
  private static Class<? extends Enum<?>> resolveEnumClass(Type type, Class<?> rawType) {
    if (!(type instanceof ParameterizedType)) {
      return null;
    }
    Type[] typeArguments = ((ParameterizedType) type).getActualTypeArguments();
    if (typeArguments.length != 1) {
      return null;
    }
    Type resolved = GsonTypes.resolve(type, rawType, typeArguments[0]);
    Class<?> raw = GsonTypes.getRawType(resolved);
    if (raw == null || !Enum.class.isAssignableFrom(raw) || raw == Enum.class) {
      return null;
    }
    // Handle anonymous enum subclasses
    if (!raw.isEnum()) {
      raw = raw.getSuperclass();
    }
    @SuppressWarnings("unchecked")
    Class<? extends Enum<?>> enumClass = (Class<? extends Enum<?>>) raw;
    return enumClass;
  }

  /** Adapter for a concrete enum type. */
  private static final class Adapter<E extends Enum<E>> extends TypeAdapter<EnumValue<E>> {
    private final Class<E> enumClass;
    private final Map<String, E> aliases;

    Adapter(Class<E> enumClass) {
      this.enumClass = enumClass;
      this.aliases = buildAliases(enumClass);
    }

    private static <E extends Enum<E>> Map<String, E> buildAliases(Class<E> enumClass) {
      Map<String, E> aliases = new HashMap<>();
      Field[] fields = enumClass.getDeclaredFields();
      AccessibleObject.setAccessible(fields, true);
      for (Field field : fields) {
        if (!field.isEnumConstant()) {
          continue;
        }
        E constant;
        try {
          constant = enumClass.cast(field.get(null));
        } catch (IllegalAccessException e) {
          throw new AssertionError(e);
        }
        addAlias(aliases, constant.name(), constant, enumClass);
        SerializedName annotation = field.getAnnotation(SerializedName.class);
        if (annotation != null) {
          addAlias(aliases, annotation.value(), constant, enumClass);
          for (String alternate : annotation.alternate()) {
            addAlias(aliases, alternate, constant, enumClass);
          }
        }
      }
      return aliases;
    }

    private static <E extends Enum<E>> void addAlias(
        Map<String, E> aliases, String alias, E constant, Class<E> enumClass) {
      E previous = aliases.put(alias, constant);
      if (previous != null && previous != constant) {
        throw new IllegalArgumentException(
            "Duplicate enum alias '"
                + alias
                + "' for constants "
                + previous
                + " and "
                + constant
                + " in "
                + enumClass.getName());
      }
    }

    @Override
    public void write(JsonWriter out, EnumValue<E> value) throws IOException {
      if (value == null) {
        out.nullValue();
        return;
      }
      if (value.isKnown()) {
        // Use the canonical name; never call the enum's user toString().
        out.value(value.getValue().name());
      } else if (value.isNull()) {
        out.nullValue();
      } else {
        out.value(value.getRawValue());
      }
    }

    @Override
    public EnumValue<E> read(JsonReader in) throws IOException {
      JsonToken token = in.peek();
      if (token == JsonToken.NULL) {
        in.nextNull();
        return EnumValue.ofNull();
      }
      if (token != JsonToken.STRING) {
        throw new JsonParseException(
            "Expected a string or null for EnumValue<"
                + enumClass.getSimpleName()
                + "> but was "
                + token
                + " at path "
                + in.getPath());
      }
      String raw = in.nextString();
      E constant = aliases.get(raw);
      if (constant != null) {
        return EnumValue.of(constant);
      }
      return EnumValue.unknown(raw);
    }
  }

  /**
   * Adapter for a raw or unresolvable {@code EnumValue} type. Every string is preserved as
   * unknown; nothing is silently converted to null.
   */
  private static final class PreservingAdapter extends TypeAdapter<EnumValue<?>> {
    @Override
    public void write(JsonWriter out, EnumValue<?> value) throws IOException {
      if (value == null) {
        out.nullValue();
        return;
      }
      if (value.isKnown()) {
        out.value(value.getValue().name());
      } else if (value.isNull()) {
        out.nullValue();
      } else {
        out.value(value.getRawValue());
      }
    }

    @Override
    @SuppressWarnings("rawtypes")
    public EnumValue read(JsonReader in) throws IOException {
      JsonToken token = in.peek();
      if (token == JsonToken.NULL) {
        in.nextNull();
        return EnumValue.ofNull();
      }
      if (token != JsonToken.STRING) {
        throw new JsonParseException(
            "Expected a string or null for EnumValue but was "
                + token
                + " at path "
                + in.getPath());
      }
      return EnumValue.unknown(in.nextString());
    }
  }
}
