/*
 * Copyright (C) 2026 The Gson Authors
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

import com.google.gson.internal.GsonTypes;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A {@link TypeAdapterFactory} that deserializes immutable classes by invoking a single
 * explicitly registered constructor, binding JSON object fields to constructor parameters.
 *
 * <p>Unlike Gson's default reflective adapter, this factory never uses {@code sun.misc.Unsafe}
 * and never writes to {@code final} fields after construction. The caller registers the
 * constructor and, for each constructor parameter (in declaration order), the JSON field name
 * under which its value appears, whether the parameter is required or optional, and optionally
 * alternate JSON names (aliases). Parameter types are resolved through Gson's regular {@link
 * TypeAdapter} machinery; generic parameter types are resolved against the actual {@link
 * TypeToken} being deserialized, so existing adapters (including adapters for nested generic
 * types such as {@code List<Integer>}) are reused.
 *
 * <p>The constructor is invoked exactly once per JSON object, and only after all fields of the
 * object have been read and validated. If two JSON fields map to the same parameter (for example
 * a field name and one of its aliases both occur), deserialization fails with an explicit
 * conflict error instead of silently letting the later value overwrite the earlier one. Unknown
 * JSON fields are handled according to the registered {@link UnknownFieldPolicy}. If the
 * constructor throws an exception, the original cause is preserved and the JSON path is included
 * in the error message.
 *
 * <p>Serialization is delegated to Gson's regular (reflective) adapter for the type.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * class Pair {
 *   final int id;
 *   final String name;
 *
 *   Pair(int id, String name) {
 *     this.id = id;
 *     this.name = name;
 *   }
 * }
 *
 * Constructor<Pair> ctor = Pair.class.getDeclaredConstructor(int.class, String.class);
 * ImmutableTypeAdapterFactory factory = ImmutableTypeAdapterFactory.builder(Pair.class, ctor)
 *     .parameter("id")
 *     .parameter("name")
 *     .build();
 * Gson gson = new GsonBuilder().registerTypeAdapterFactory(factory).create();
 * Pair pair = gson.fromJson("{\"id\":1,\"name\":\"a\"}", Pair.class);
 * }</pre>
 *
 * <p>This factory only affects the types explicitly registered with it; Gson's default
 * reflective adapter for all other types is unchanged.
 */
public final class ImmutableTypeAdapterFactory implements TypeAdapterFactory {
  /** Policy for JSON object fields that are not bound to any constructor parameter. */
  public enum UnknownFieldPolicy {
    /** Reject the JSON with a {@link JsonSyntaxException} naming the unknown field. */
    REJECT,
    /** Skip the unknown field and its value. */
    SKIP
  }

  /**
   * Starts a registration for the immutable class {@code rawType} whose instances are created by
   * invoking {@code constructor}. The constructor is made accessible; it is invoked reflectively
   * exactly once per deserialized JSON object.
   */
  public static <T> Builder<T> builder(Class<T> rawType, Constructor<T> constructor) {
    return new Builder<>(rawType, constructor);
  }

  /**
   * Builder for the registration of a single immutable class. One {@link #parameter(String,
   * String...)} or {@link #optionalParameter(String, Object, String...)} call is required per
   * constructor parameter, in constructor parameter order.
   */
  public static final class Builder<T> {
    private final Class<T> rawType;
    private final Constructor<T> constructor;
    private final List<ParamSpec> params = new ArrayList<>();
    private UnknownFieldPolicy unknownFieldPolicy = UnknownFieldPolicy.REJECT;

    private Builder(Class<T> rawType, Constructor<T> constructor) {
      this.rawType = Objects.requireNonNull(rawType, "rawType");
      this.constructor = Objects.requireNonNull(constructor, "constructor");
      if (constructor.getDeclaringClass() != rawType) {
        throw new IllegalArgumentException(
            "Constructor " + constructor + " does not belong to " + rawType.getName());
      }
    }

    /**
     * Binds the next constructor parameter to a required JSON field with the given name and
     * optional aliases. Deserialization fails if neither the name nor any of its aliases occurs
     * in the JSON object.
     */
    public Builder<T> parameter(String jsonName, String... aliases) {
      return addParam(jsonName, true, null, aliases);
    }

    /**
     * Binds the next constructor parameter to an optional JSON field with the given name and
     * optional aliases. If the JSON object contains neither the name nor any alias, {@code
     * defaultValue} is passed to the constructor.
     */
    public Builder<T> optionalParameter(String jsonName, Object defaultValue, String... aliases) {
      return addParam(jsonName, false, defaultValue, aliases);
    }

    /**
     * Sets the policy for JSON fields not bound to any parameter; defaults to {@link
     * UnknownFieldPolicy#REJECT}.
     */
    public Builder<T> onUnknownField(UnknownFieldPolicy policy) {
      this.unknownFieldPolicy = Objects.requireNonNull(policy, "policy");
      return this;
    }

    private Builder<T> addParam(
        String jsonName, boolean required, Object defaultValue, String... aliases) {
      params.add(
          new ParamSpec(
              Objects.requireNonNull(jsonName, "jsonName"), required, defaultValue,
              aliases.clone()));
      return this;
    }

    /**
     * Validates the registration and returns a factory that can be registered with {@link
     * GsonBuilder#registerTypeAdapterFactory(TypeAdapterFactory)}.
     *
     * @throws IllegalArgumentException if the number of bound parameters does not match the
     *     constructor parameter count, or if two parameters claim the same JSON name or alias
     */
    public ImmutableTypeAdapterFactory build() {
      int expected = constructor.getParameterCount();
      if (params.size() != expected) {
        throw new IllegalArgumentException(
            "Registered "
                + params.size()
                + " parameter binding(s) but constructor "
                + constructor
                + " takes "
                + expected
                + " parameter(s)");
      }
      Map<String, Integer> jsonNameToParam = new LinkedHashMap<>();
      for (int i = 0; i < params.size(); i++) {
        ParamSpec spec = params.get(i);
        List<String> names = new ArrayList<>();
        names.add(spec.jsonName);
        for (String alias : spec.aliases) {
          names.add(Objects.requireNonNull(alias, "alias"));
        }
        for (String name : names) {
          Integer previous = jsonNameToParam.putIfAbsent(name, i);
          if (previous != null) {
            throw new IllegalArgumentException(
                "JSON field name '"
                    + name
                    + "' is bound to both constructor parameter "
                    + previous
                    + " and parameter "
                    + i
                    + " of "
                    + constructor);
          }
        }
      }
      constructor.setAccessible(true);
      return new ImmutableTypeAdapterFactory(
          rawType, constructor, new ArrayList<>(params), jsonNameToParam, unknownFieldPolicy);
    }
  }

  private static final class ParamSpec {
    final String jsonName;
    final boolean required;
    final Object defaultValue;
    final String[] aliases;

    ParamSpec(String jsonName, boolean required, Object defaultValue, String[] aliases) {
      this.jsonName = jsonName;
      this.required = required;
      this.defaultValue = defaultValue;
      this.aliases = aliases;
    }
  }

  private final Class<?> rawType;
  private final Constructor<?> constructor;
  private final List<ParamSpec> params;
  private final Map<String, Integer> jsonNameToParam;
  private final UnknownFieldPolicy unknownFieldPolicy;

  private ImmutableTypeAdapterFactory(
      Class<?> rawType,
      Constructor<?> constructor,
      List<ParamSpec> params,
      Map<String, Integer> jsonNameToParam,
      UnknownFieldPolicy unknownFieldPolicy) {
    this.rawType = rawType;
    this.constructor = constructor;
    this.params = params;
    this.jsonNameToParam = jsonNameToParam;
    this.unknownFieldPolicy = unknownFieldPolicy;
  }

  @Override
  public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
    if (type.getRawType() != rawType) {
      return null;
    }
    int count = params.size();
    Type[] genericParamTypes = constructor.getGenericParameterTypes();
    @SuppressWarnings("unchecked")
    TypeAdapter<Object>[] paramAdapters = new TypeAdapter[count];
    boolean[] primitiveParams = new boolean[count];
    for (int i = 0; i < count; i++) {
      // Resolve generic parameter types against the actual type arguments of the requested
      // type, then reuse Gson's existing adapters (including nested generic ones).
      Type resolved = GsonTypes.resolve(type.getType(), rawType, genericParamTypes[i]);
      paramAdapters[i] = (TypeAdapter<Object>) gson.getAdapter(TypeToken.get(resolved));
      primitiveParams[i] = resolved instanceof Class<?> && ((Class<?>) resolved).isPrimitive();
    }
    TypeAdapter<T> serializationDelegate = gson.getDelegateAdapter(this, type);
    TypeAdapter<T> adapter =
        new BoundAdapter<>(paramAdapters, primitiveParams, serializationDelegate);
    return adapter.nullSafe();
  }

  private final class BoundAdapter<T> extends TypeAdapter<T> {
    private final TypeAdapter<Object>[] paramAdapters;
    private final boolean[] primitiveParams;
    private final TypeAdapter<T> serializationDelegate;

    BoundAdapter(
        TypeAdapter<Object>[] paramAdapters,
        boolean[] primitiveParams,
        TypeAdapter<T> serializationDelegate) {
      this.paramAdapters = paramAdapters;
      this.primitiveParams = primitiveParams;
      this.serializationDelegate = serializationDelegate;
    }

    @Override
    public void write(JsonWriter out, T value) throws IOException {
      serializationDelegate.write(out, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public T read(JsonReader reader) throws IOException {
      int count = params.size();
      Object[] values = new Object[count];
      // Remembers by which JSON field name each parameter was set, to detect conflicts.
      String[] seenAtField = new String[count];
      reader.beginObject();
      while (reader.hasNext()) {
        String jsonField = reader.nextName();
        Integer index = jsonNameToParam.get(jsonField);
        if (index == null) {
          if (unknownFieldPolicy == UnknownFieldPolicy.SKIP) {
            reader.skipValue();
            continue;
          }
          throw new JsonSyntaxException(
              "Unknown JSON field '"
                  + jsonField
                  + "' for immutable type "
                  + rawType.getName()
                  + " at path "
                  + reader.getPath());
        }
        int i = index;
        if (seenAtField[i] != null) {
          // Explicit conflict policy: never let a later value overwrite an earlier one,
          // even when the two JSON fields are aliases of the same parameter.
          throw new JsonParseException(
              "Conflicting JSON fields '"
                  + seenAtField[i]
                  + "' and '"
                  + jsonField
                  + "' both map to constructor parameter '"
                  + params.get(i).jsonName
                  + "' (index "
                  + i
                  + ") of immutable type "
                  + rawType.getName()
                  + " at path "
                  + reader.getPath()
                  + "; refusing to overwrite the first value");
        }
        Object value = paramAdapters[i].read(reader);
        if (value == null && primitiveParams[i]) {
          throw new JsonSyntaxException(
              "JSON null is not allowed for primitive constructor parameter '"
                  + params.get(i).jsonName
                  + "' (index "
                  + i
                  + ") of immutable type "
                  + rawType.getName()
                  + " at path "
                  + reader.getPath());
        }
        values[i] = value;
        seenAtField[i] = jsonField;
      }
      reader.endObject();

      List<String> missing = null;
      for (int i = 0; i < count; i++) {
        if (seenAtField[i] == null) {
          ParamSpec spec = params.get(i);
          if (spec.required) {
            if (missing == null) {
              missing = new ArrayList<>();
            }
            missing.add("'" + spec.jsonName + "' (index " + i + ")");
          } else {
            values[i] = spec.defaultValue;
          }
        }
      }
      if (missing != null) {
        throw new JsonParseException(
            "Missing required JSON field(s) "
                + missing
                + " for immutable type "
                + rawType.getName()
                + " at path "
                + reader.getPath());
      }

      // All fields have been read and validated; invoke the constructor exactly once.
      String path = reader.getPath();
      try {
        return (T) constructor.newInstance(values);
      } catch (InvocationTargetException e) {
        throw new JsonParseException(
            "Constructor of immutable type "
                + rawType.getName()
                + " threw an exception at JSON path "
                + path,
            e.getCause());
      } catch (InstantiationException | IllegalAccessException | IllegalArgumentException e) {
        throw new JsonParseException(
            "Failed to invoke constructor of immutable type "
                + rawType.getName()
                + " at JSON path "
                + path,
            e);
      }
    }
  }
}
