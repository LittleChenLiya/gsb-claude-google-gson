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

package com.google.gson;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.gson.internal.GsonTypes;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A {@link TypeAdapterFactory} for <em>immutable</em> classes whose instances are created by
 * invoking a constructor with values read from JSON, instead of reflectively writing fields after
 * construction. This makes it possible to deserialize classes with {@code final} fields (and other
 * classes that should not be mutated after construction) without relying on {@code
 * sun.misc.Unsafe} or post-construction field writes.
 *
 * <h2>Registration</h2>
 *
 * <p>A binding is registered for a raw class by supplying a constructor and, for each constructor
 * parameter, the JSON field name (or names) that maps to it and whether the parameter is required:
 *
 * <pre>{@code
 * ImmutableTypeAdapterFactory factory =
 *     new ImmutableTypeAdapterFactory()
 *         .register(Pair.class)
 *         .constructor(int.class, String.class)
 *         .parameter(0, true, "id")
 *         .parameter(1, true, "name")
 *         .register();
 *
 * Gson gson = new GsonBuilder().registerTypeAdapterFactory(factory).create();
 * Pair pair = gson.fromJson("{\"id\":1,\"name\":\"foo\"}", Pair.class);
 * }</pre>
 *
 * <p>Each parameter is bound by its zero-based index in the constructor's parameter list. The
 * parameter's type is taken from the constructor's generic parameter types and resolved against the
 * actual {@link TypeToken} the adapter is created for, so type variables are resolved correctly:
 * for a {@code Pair<A, B>} deserialized as {@code Pair<Integer, String>}, the parameter of type
 * {@code A} is read as {@code Integer} and the parameter of type {@code B} as {@code String}.
 * Values are read through Gson's normal {@link TypeAdapter} mechanism, so nested generic types use
 * the already-registered adapters.
 *
 * <h2>Required and optional parameters</h2>
 *
 * <p>A required parameter must be present in the JSON object; if it is missing, an exception is
 * thrown and the constructor is never invoked. An optional parameter that is missing is padded with
 * {@code null} (or the primitive default for primitive parameters). A JSON field explicitly set to
 * {@code null} is considered present.
 *
 * <h2>Alias conflicts</h2>
 *
 * <p>A single parameter may be reachable through several JSON names (aliases). If a JSON object
 * supplies a value for the same parameter more than once (for example through two aliases), the
 * configured {@link AliasConflictStrategy} decides what happens. The default is {@link
 * AliasConflictStrategy#REJECT}: the second value does not overwrite the first and an exception is
 * thrown.
 *
 * <h2>Unknown fields</h2>
 *
 * <p>JSON fields that are not bound to any parameter are handled according to the configured {@link
 * UnknownFieldStrategy}. The default is {@link UnknownFieldStrategy#IGNORE}, matching Gson's
 * default behavior.
 *
 * <h2>Constructor invocation</h2>
 *
 * <p>The constructor is invoked at most once, and only after every field has been read and all
 * required parameters have been validated. If the constructor throws, the cause is preserved and
 * the exception message includes the JSON path at which construction was attempted.
 *
 * <p>This factory only handles types for which a binding was {@link #register(Class) registered};
 * for all other types it returns {@code null} so the default reflective adapter (or another
 * registered factory) is used unchanged.
 *
 * @since 2.14.1
 */
public final class ImmutableTypeAdapterFactory implements TypeAdapterFactory {

  /** Strategy for handling a parameter value that is specified more than once. */
  public enum AliasConflictStrategy {
    /** Reject the duplicate value with an exception; the later value does not overwrite. */
    REJECT,
    /** Keep the first value and skip later values for the same parameter. */
    FIRST_WINS,
    /** Use the last value read; earlier values are overwritten. */
    LAST_WINS
  }

  /** Strategy for handling JSON fields that are not bound to any constructor parameter. */
  public enum UnknownFieldStrategy {
    /** Silently skip unknown fields (Gson's default behavior). */
    IGNORE,
    /** Throw an exception when an unknown field is encountered. */
    FAIL
  }

  private final Map<Class<?>, Registration<?>> registrations = new LinkedHashMap<>();

  public ImmutableTypeAdapterFactory() {}

  /**
   * Starts registering a binding for the given raw type.
   *
   * @param type the class to bind
   * @return a registration builder
   * @throws IllegalArgumentException if a binding for {@code type} was already registered
   */
  public <T> Registration<T> register(Class<T> type) {
    Objects.requireNonNull(type, "type == null");
    if (registrations.containsKey(type)) {
      throw new IllegalArgumentException(
          "A binding for " + type.getName() + " is already registered");
    }
    Registration<T> registration = new Registration<>(this, type);
    registrations.put(type, registration);
    return registration;
  }

  @Override
  @SuppressWarnings({"unchecked", "rawtypes"})
  public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> typeToken) {
    Registration registration = registrations.get(typeToken.getRawType());
    if (registration == null) {
      return null;
    }
    return (TypeAdapter<T>) registration.createAdapter(gson, typeToken);
  }

  /**
   * A registration for a single immutable type.
   *
   * @param <T> the type being registered
   */
  public static final class Registration<T> {
    private final ImmutableTypeAdapterFactory factory;
    private final Class<T> type;
    private Constructor<T> constructor;
    private final List<ParameterBinding> parameters = new ArrayList<>();
    private AliasConflictStrategy aliasConflictStrategy = AliasConflictStrategy.REJECT;
    private UnknownFieldStrategy unknownFieldStrategy = UnknownFieldStrategy.IGNORE;

    Registration(ImmutableTypeAdapterFactory factory, Class<T> type) {
      this.factory = factory;
      this.type = type;
    }

    /**
     * Sets the constructor to invoke.
     *
     * @param constructor the constructor
     * @return this registration
     */
    public Registration<T> constructor(Constructor<T> constructor) {
      Objects.requireNonNull(constructor, "constructor == null");
      if (constructor.getDeclaringClass() != type) {
        throw new IllegalArgumentException(
            "Constructor " + constructor + " does not declare " + type.getName());
      }
      this.constructor = constructor;
      return this;
    }

    /**
     * Sets the constructor to invoke, located by its parameter types. Unlike {@link
     * Class#getConstructor(Class...)}, this finds non-public constructors too; the factory makes
     * the chosen constructor accessible.
     *
     * @param parameterTypes the constructor's parameter types
     * @return this registration
     * @throws IllegalArgumentException if a constructor matching {@code parameterTypes} does not
     *     exist
     */
    public Registration<T> constructor(Class<?>... parameterTypes) {
      Objects.requireNonNull(parameterTypes, "parameterTypes == null");
      try {
        this.constructor = type.getDeclaredConstructor(parameterTypes);
      } catch (NoSuchMethodException e) {
        throw new IllegalArgumentException(
            "No constructor of " + type.getName() + " matches parameter types "
                + Arrays.toString(parameterTypes),
            e);
      }
      return this;
    }

    /**
     * Binds a constructor parameter to one or more JSON field names.
     *
     * @param index the zero-based index of the parameter in the constructor's parameter list
     * @param required whether the parameter is required
     * @param jsonNames the JSON field names (aliases) that map to this parameter
     * @return this registration
     */
    public Registration<T> parameter(int index, boolean required, String... jsonNames) {
      if (index < 0) {
        throw new IllegalArgumentException("index must be >= 0 but was " + index);
      }
      Objects.requireNonNull(jsonNames, "jsonNames == null");
      if (jsonNames.length == 0) {
        throw new IllegalArgumentException("at least one JSON name must be provided");
      }
      for (String jsonName : jsonNames) {
        Objects.requireNonNull(jsonName, "jsonName == null");
      }
      parameters.add(new ParameterBinding(index, required, jsonNames.clone()));
      return this;
    }

    /** Sets the strategy used when a parameter value is specified more than once. */
    public Registration<T> aliasConflictStrategy(AliasConflictStrategy strategy) {
      this.aliasConflictStrategy = Objects.requireNonNull(strategy, "strategy == null");
      return this;
    }

    /** Sets the strategy used for JSON fields not bound to any parameter. */
    public Registration<T> unknownFieldStrategy(UnknownFieldStrategy strategy) {
      this.unknownFieldStrategy = Objects.requireNonNull(strategy, "strategy == null");
      return this;
    }

    /**
     * Validates and registers the binding.
     *
     * @return the factory this registration belongs to
     */
    @CanIgnoreReturnValue
    public ImmutableTypeAdapterFactory register() {
      validate();
      return factory;
    }

    private void validate() {
      if (constructor == null) {
        throw new IllegalStateException("constructor has not been set for " + type.getName());
      }
      int parameterCount = constructor.getParameterCount();
      boolean[] bound = new boolean[parameterCount];
      Set<String> usedNames = new HashSet<>();
      for (ParameterBinding parameter : parameters) {
        if (parameter.index >= parameterCount) {
          throw new IllegalArgumentException(
              "Parameter index "
                  + parameter.index
                  + " is out of range; constructor "
                  + constructor
                  + " has "
                  + parameterCount
                  + " parameters");
        }
        if (bound[parameter.index]) {
          throw new IllegalArgumentException(
              "Parameter index " + parameter.index + " is bound more than once");
        }
        bound[parameter.index] = true;
        for (String jsonName : parameter.jsonNames) {
          if (!usedNames.add(jsonName)) {
            throw new IllegalArgumentException(
                "JSON field name '" + jsonName + "' is bound to more than one parameter");
          }
        }
      }
      for (int i = 0; i < parameterCount; i++) {
        if (!bound[i]) {
          throw new IllegalStateException(
              "Constructor parameter " + i + " of " + type.getName() + " is not bound");
        }
      }
    }

    TypeAdapter<T> createAdapter(Gson gson, TypeToken<T> typeToken) {
      Type[] genericParameterTypes = constructor.getGenericParameterTypes();
      ParameterAdapter[] parameterAdapters = new ParameterAdapter[genericParameterTypes.length];
      Map<String, Integer> nameToIndex = new HashMap<>();
      for (ParameterBinding parameter : parameters) {
        Type parameterType =
            GsonTypes.resolve(
                typeToken.getType(),
                typeToken.getRawType(),
                genericParameterTypes[parameter.index]);
        TypeAdapter<?> adapter = gson.getAdapter(TypeToken.get(parameterType));
        parameterAdapters[parameter.index] = new ParameterAdapter(adapter, parameter.required);
        for (String jsonName : parameter.jsonNames) {
          nameToIndex.put(jsonName, parameter.index);
        }
      }

      TypeAdapter<T> writeDelegate = gson.getDelegateAdapter(factory, typeToken);
      return new ImmutableAdapter<>(
          writeDelegate,
          constructor,
          parameterAdapters,
          nameToIndex,
          aliasConflictStrategy,
          unknownFieldStrategy);
    }
  }

  private static final class ParameterBinding {
    final int index;
    final boolean required;
    final String[] jsonNames;

    ParameterBinding(int index, boolean required, String[] jsonNames) {
      this.index = index;
      this.required = required;
      this.jsonNames = jsonNames;
    }
  }

  private static final class ParameterAdapter {
    final TypeAdapter<Object> adapter;
    final boolean required;

    @SuppressWarnings("unchecked")
    ParameterAdapter(TypeAdapter<?> adapter, boolean required) {
      this.adapter = (TypeAdapter<Object>) adapter;
      this.required = required;
    }
  }

  private static final class ImmutableAdapter<T> extends TypeAdapter<T> {
    private static final Map<Class<?>, Object> PRIMITIVE_DEFAULTS = primitiveDefaults();

    private final TypeAdapter<T> writeDelegate;
    private final Constructor<T> constructor;
    private final ParameterAdapter[] parameterAdapters;
    private final Map<String, Integer> nameToIndex;
    private final AliasConflictStrategy aliasConflictStrategy;
    private final UnknownFieldStrategy unknownFieldStrategy;
    private final Object[] primitiveDefaults;

    ImmutableAdapter(
        TypeAdapter<T> writeDelegate,
        Constructor<T> constructor,
        ParameterAdapter[] parameterAdapters,
        Map<String, Integer> nameToIndex,
        AliasConflictStrategy aliasConflictStrategy,
        UnknownFieldStrategy unknownFieldStrategy) {
      this.writeDelegate = writeDelegate;
      this.constructor = constructor;
      this.parameterAdapters = parameterAdapters;
      this.nameToIndex = nameToIndex;
      this.aliasConflictStrategy = aliasConflictStrategy;
      this.unknownFieldStrategy = unknownFieldStrategy;

      Class<?>[] parameterTypes = constructor.getParameterTypes();
      this.primitiveDefaults = new Object[parameterTypes.length];
      for (int i = 0; i < parameterTypes.length; i++) {
        primitiveDefaults[i] = PRIMITIVE_DEFAULTS.get(parameterTypes[i]);
      }
      constructor.setAccessible(true);
    }

    private static Map<Class<?>, Object> primitiveDefaults() {
      Map<Class<?>, Object> defaults = new LinkedHashMap<>();
      defaults.put(byte.class, (byte) 0);
      defaults.put(short.class, (short) 0);
      defaults.put(int.class, 0);
      defaults.put(long.class, 0L);
      defaults.put(float.class, 0F);
      defaults.put(double.class, 0D);
      defaults.put(char.class, '\0');
      defaults.put(boolean.class, false);
      return defaults;
    }

    @Override
    public void write(JsonWriter out, T value) throws IOException {
      writeDelegate.write(out, value);
    }

    @Override
    public T read(JsonReader in) throws IOException {
      if (in.peek() == JsonToken.NULL) {
        in.nextNull();
        return null;
      }

      int parameterCount = parameterAdapters.length;
      Object[] args = new Object[parameterCount];
      boolean[] present = new boolean[parameterCount];
      System.arraycopy(primitiveDefaults, 0, args, 0, parameterCount);

      in.beginObject();
      while (in.hasNext()) {
        String name = in.nextName();
        Integer index = nameToIndex.get(name);
        if (index == null) {
          if (unknownFieldStrategy == UnknownFieldStrategy.FAIL) {
            throw new JsonParseException(
                "Unknown field '" + name + "' at path " + in.getPath());
          }
          in.skipValue();
          continue;
        }

        if (present[index]) {
          switch (aliasConflictStrategy) {
            case REJECT:
              throw new JsonParseException(
                  "Duplicate value for parameter bound to '"
                      + name
                      + "' at path "
                      + in.getPath());
            case FIRST_WINS:
              in.skipValue();
              continue;
            case LAST_WINS:
              // Read below and overwrite the earlier value.
              break;
          }
        }

        args[index] = parameterAdapters[index].adapter.read(in);
        present[index] = true;
      }
      in.endObject();

      for (int i = 0; i < parameterCount; i++) {
        if (parameterAdapters[i].required && !present[i]) {
          throw new JsonParseException(
              "Missing required field for constructor parameter "
                  + i
                  + " of "
                  + constructor.getDeclaringClass().getName()
                  + " at path "
                  + in.getPath());
        }
      }

      try {
        return constructor.newInstance(args);
      } catch (IllegalAccessException e) {
        throw new JsonIOException(
            "Failed to invoke constructor " + constructor + " at path " + in.getPath(), e);
      } catch (InstantiationException e) {
        throw new JsonIOException(
            "Failed to invoke constructor " + constructor + " at path " + in.getPath(), e);
      } catch (IllegalArgumentException e) {
        // Should not be possible unless an adapter returned a value of the wrong type
        throw new JsonParseException(
            "Failed to invoke constructor "
                + constructor
                + " with args "
                + Arrays.toString(args)
                + " at path "
                + in.getPath(),
            e);
      } catch (InvocationTargetException e) {
        throw new JsonParseException(
            "Failed to invoke constructor "
                + constructor
                + " with args "
                + Arrays.toString(args)
                + " at path "
                + in.getPath(),
            e.getCause());
      }
    }
  }
}
