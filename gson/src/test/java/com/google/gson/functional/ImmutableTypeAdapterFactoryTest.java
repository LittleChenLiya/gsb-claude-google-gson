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

package com.google.gson.functional;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ImmutableTypeAdapterFactory;
import com.google.gson.ImmutableTypeAdapterFactory.AliasConflictStrategy;
import com.google.gson.ImmutableTypeAdapterFactory.UnknownFieldStrategy;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Functional tests for {@link ImmutableTypeAdapterFactory}.
 *
 * @author Inderjeet Singh
 */
public class ImmutableTypeAdapterFactoryTest {

  /** Immutable pair with a constructor invocation counter. */
  public static final class Pair {
    @SuppressWarnings("NonFinalStaticField")
    private static int constructorCount;

    public final int id;
    public final String name;

    public Pair(int id, String name) {
      constructorCount++;
      this.id = id;
      this.name = name;
    }

    static int getConstructorCount() {
      return constructorCount;
    }

    static void resetConstructorCount() {
      constructorCount = 0;
    }
  }

  /** Generic immutable pair; type variables must resolve per the actual TypeToken. */
  public static final class GenericPair<A, B> {
    public final A first;
    public final B second;

    public GenericPair(A first, B second) {
      this.first = first;
      this.second = second;
    }
  }

  /** Immutable class with a nested generic (List) parameter. */
  public static final class WithList {
    public final List<String> items;

    public WithList(List<String> items) {
      this.items = items;
    }
  }

  /** Immutable class whose constructor always throws. */
  public static final class Throwing {
    public final int id;

    public Throwing(int id) {
      this.id = id;
      throw new IllegalStateException("boom " + id);
    }
  }

  /** Immutable class with an optional parameter. */
  public static final class OptionalParam {
    public final int id;
    public final String nickname;

    public OptionalParam(int id, String nickname) {
      this.id = id;
      this.nickname = nickname;
    }
  }

  /** Immutable class with a private constructor; the factory must make it accessible. */
  public static final class PrivateConstructor {
    public final int id;
    public final String name;

    @SuppressWarnings("UnusedMethod") // invoked via reflection
    private PrivateConstructor(int id, String name) {
      this.id = id;
      this.name = name;
    }
  }

  /** Immutable class with a primitive optional parameter. */
  public static final class OptionalPrimitive {
    public final int id;
    public final int count;

    public OptionalPrimitive(int id, int count) {
      this.id = id;
      this.count = count;
    }
  }

  @Before
  public void setUp() {
    Pair.resetConstructorCount();
  }

  private static Gson gsonWith(ImmutableTypeAdapterFactory factory) {
    return new GsonBuilder().registerTypeAdapterFactory(factory).create();
  }

  /** The two-field example from the registration API: immutable Pair built from id and name. */
  @Test
  public void testConstructImmutablePair() {
    ImmutableTypeAdapterFactory factory =
        new ImmutableTypeAdapterFactory()
            .register(Pair.class)
            .constructor(int.class, String.class)
            .parameter(0, true, "id")
            .parameter(1, true, "name")
            .register();

    Gson gson = gsonWith(factory);
    Pair pair = gson.fromJson("{\"id\":1,\"name\":\"foo\"}", Pair.class);

    assertThat(pair.id).isEqualTo(1);
    assertThat(pair.name).isEqualTo("foo");
    assertThat(Pair.getConstructorCount()).isEqualTo(1);
  }

  /** A wrong id (string where int is expected) must not invoke the constructor. */
  @Test
  public void testWrongIdDoesNotInvokeConstructor() {
    ImmutableTypeAdapterFactory factory =
        new ImmutableTypeAdapterFactory()
            .register(Pair.class)
            .constructor(int.class, String.class)
            .parameter(0, true, "id")
            .parameter(1, true, "name")
            .register();

    Gson gson = gsonWith(factory);
    assertThrows(
        JsonParseException.class,
        () -> gson.fromJson("{\"id\":\"abc\",\"name\":\"foo\"}", Pair.class));
    assertThat(Pair.getConstructorCount()).isEqualTo(0);
  }

  /** A missing required name must not invoke the constructor. */
  @Test
  public void testMissingRequiredNameDoesNotInvokeConstructor() {
    ImmutableTypeAdapterFactory factory =
        new ImmutableTypeAdapterFactory()
            .register(Pair.class)
            .constructor(int.class, String.class)
            .parameter(0, true, "id")
            .parameter(1, true, "name")
            .register();

    Gson gson = gsonWith(factory);
    assertThrows(JsonParseException.class, () -> gson.fromJson("{\"id\":1}", Pair.class));
    assertThat(Pair.getConstructorCount()).isEqualTo(0);
  }

  /** Required parameters that are present work; the constructor is invoked exactly once. */
  @Test
  public void testConstructorInvokedOnce() {
    ImmutableTypeAdapterFactory factory =
        new ImmutableTypeAdapterFactory()
            .register(Pair.class)
            .constructor(int.class, String.class)
            .parameter(0, true, "id")
            .parameter(1, true, "name")
            .register();

    Gson gson = gsonWith(factory);
    Pair pair = gson.fromJson("{\"id\":1,\"name\":\"foo\"}", Pair.class);
    assertThat(pair).isNotNull();
    assertThat(Pair.getConstructorCount()).isEqualTo(1);
  }

  /** Generic parameters resolve per the actual TypeToken. */
  @Test
  public void testGenericTypeResolution() {
    ImmutableTypeAdapterFactory factory =
        new ImmutableTypeAdapterFactory()
            .register(GenericPair.class)
            .constructor(Object.class, Object.class)
            .parameter(0, true, "first")
            .parameter(1, true, "second")
            .register();

    Gson gson = gsonWith(factory);
    GenericPair<Integer, String> pair =
        gson.fromJson(
            "{\"first\":1,\"second\":\"x\"}",
            new TypeToken<GenericPair<Integer, String>>() {}.getType());

    assertThat(pair.first).isEqualTo(1);
    assertThat(pair.second).isEqualTo("x");
  }

  /** Nested generic parameters continue to use the already-registered adapters. */
  @Test
  public void testNestedGenericParameterUsesExistingAdapter() {
    ImmutableTypeAdapterFactory factory =
        new ImmutableTypeAdapterFactory()
            .register(WithList.class)
            .constructor(List.class)
            .parameter(0, true, "items")
            .register();

    Gson gson = gsonWith(factory);
    WithList result = gson.fromJson("{\"items\":[\"a\",\"b\"]}", WithList.class);

    assertThat(result.items).containsExactly("a", "b").inOrder();
  }

  /** Two aliases hitting the same parameter are rejected; the later value does not overwrite. */
  @Test
  public void testAliasConflictRejected() {
    ImmutableTypeAdapterFactory factory =
        new ImmutableTypeAdapterFactory()
            .register(Pair.class)
            .constructor(int.class, String.class)
            .parameter(0, true, "id")
            .parameter(1, true, "name", "aliasName")
            .register();

    Gson gson = gsonWith(factory);
    JsonParseException thrown =
        assertThrows(
            JsonParseException.class,
            () -> gson.fromJson(
                "{\"id\":1,\"name\":\"first\",\"aliasName\":\"second\"}", Pair.class));
    assertThat(thrown).hasMessageThat().contains("Duplicate value");
    assertThat(Pair.getConstructorCount()).isEqualTo(0);
  }

  /** FIRST_WINS keeps the first value and skips later values. */
  @Test
  public void testAliasConflictFirstWins() {
    ImmutableTypeAdapterFactory factory =
        new ImmutableTypeAdapterFactory()
            .register(Pair.class)
            .constructor(int.class, String.class)
            .parameter(0, true, "id")
            .parameter(1, true, "name", "aliasName")
            .aliasConflictStrategy(AliasConflictStrategy.FIRST_WINS)
            .register();

    Gson gson = gsonWith(factory);
    Pair pair =
        gson.fromJson("{\"id\":1,\"name\":\"first\",\"aliasName\":\"second\"}", Pair.class);

    assertThat(pair.name).isEqualTo("first");
    assertThat(Pair.getConstructorCount()).isEqualTo(1);
  }

  /** LAST_WINS uses the last value read. */
  @Test
  public void testAliasConflictLastWins() {
    ImmutableTypeAdapterFactory factory =
        new ImmutableTypeAdapterFactory()
            .register(Pair.class)
            .constructor(int.class, String.class)
            .parameter(0, true, "id")
            .parameter(1, true, "name", "aliasName")
            .aliasConflictStrategy(AliasConflictStrategy.LAST_WINS)
            .register();

    Gson gson = gsonWith(factory);
    Pair pair =
        gson.fromJson("{\"id\":1,\"name\":\"first\",\"aliasName\":\"second\"}", Pair.class);

    assertThat(pair.name).isEqualTo("second");
    assertThat(Pair.getConstructorCount()).isEqualTo(1);
  }

  /** An optional parameter that is missing is padded with null. */
  @Test
  public void testOptionalMissingPaddedWithNull() {
    ImmutableTypeAdapterFactory factory =
        new ImmutableTypeAdapterFactory()
            .register(OptionalParam.class)
            .constructor(int.class, String.class)
            .parameter(0, true, "id")
            .parameter(1, false, "nickname")
            .register();

    Gson gson = gsonWith(factory);
    OptionalParam result = gson.fromJson("{\"id\":1}", OptionalParam.class);

    assertThat(result.id).isEqualTo(1);
    assertThat(result.nickname).isNull();
  }

  /** An optional primitive parameter that is missing is padded with the primitive default. */
  @Test
  public void testOptionalPrimitiveMissingPaddedWithDefault() {
    ImmutableTypeAdapterFactory factory =
        new ImmutableTypeAdapterFactory()
            .register(OptionalPrimitive.class)
            .constructor(int.class, int.class)
            .parameter(0, true, "id")
            .parameter(1, false, "count")
            .register();

    Gson gson = gsonWith(factory);
    OptionalPrimitive result = gson.fromJson("{\"id\":1}", OptionalPrimitive.class);

    assertThat(result.id).isEqualTo(1);
    assertThat(result.count).isEqualTo(0);
  }

  /** An explicitly null field is considered present. */
  @Test
  public void testExplicitNullIsPresent() {
    ImmutableTypeAdapterFactory factory =
        new ImmutableTypeAdapterFactory()
            .register(OptionalParam.class)
            .constructor(int.class, String.class)
            .parameter(0, true, "id")
            .parameter(1, false, "nickname")
            .register();

    Gson gson = gsonWith(factory);
    OptionalParam result = gson.fromJson("{\"id\":1,\"nickname\":null}", OptionalParam.class);

    assertThat(result.id).isEqualTo(1);
    assertThat(result.nickname).isNull();
  }

  /** Unknown fields are ignored by default. */
  @Test
  public void testUnknownFieldIgnoredByDefault() {
    ImmutableTypeAdapterFactory factory =
        new ImmutableTypeAdapterFactory()
            .register(Pair.class)
            .constructor(int.class, String.class)
            .parameter(0, true, "id")
            .parameter(1, true, "name")
            .register();

    Gson gson = gsonWith(factory);
    Pair pair = gson.fromJson("{\"id\":1,\"name\":\"foo\",\"extra\":42}", Pair.class);

    assertThat(pair.id).isEqualTo(1);
    assertThat(pair.name).isEqualTo("foo");
  }

  /** Unknown fields can be configured to fail. */
  @Test
  public void testUnknownFieldFail() {
    ImmutableTypeAdapterFactory factory =
        new ImmutableTypeAdapterFactory()
            .register(Pair.class)
            .constructor(int.class, String.class)
            .parameter(0, true, "id")
            .parameter(1, true, "name")
            .unknownFieldStrategy(UnknownFieldStrategy.FAIL)
            .register();

    Gson gson = gsonWith(factory);
    JsonParseException thrown =
        assertThrows(
            JsonParseException.class,
            () -> gson.fromJson("{\"id\":1,\"name\":\"foo\",\"extra\":42}", Pair.class));
    assertThat(thrown).hasMessageThat().contains("Unknown field");
  }

  /** A constructor exception preserves the cause and the JSON path. */
  @Test
  public void testConstructorExceptionPreservesCauseAndPath() {
    ImmutableTypeAdapterFactory factory =
        new ImmutableTypeAdapterFactory()
            .register(Throwing.class)
            .constructor(int.class)
            .parameter(0, true, "id")
            .register();

    Gson gson = gsonWith(factory);
    JsonParseException thrown =
        assertThrows(JsonParseException.class, () -> gson.fromJson("{\"id\":5}", Throwing.class));

    assertThat(thrown.getCause()).isInstanceOf(IllegalStateException.class);
    assertThat(thrown.getCause()).hasMessageThat().isEqualTo("boom 5");
    assertThat(thrown).hasMessageThat().contains("path");
  }

  /** Serialization delegates to the reflective adapter. */
  @Test
  public void testSerializationDelegates() {
    ImmutableTypeAdapterFactory factory =
        new ImmutableTypeAdapterFactory()
            .register(Pair.class)
            .constructor(int.class, String.class)
            .parameter(0, true, "id")
            .parameter(1, true, "name")
            .register();

    Gson gson = gsonWith(factory);
    String json = gson.toJson(new Pair(7, "bar"));

    assertThat(json).contains("\"id\":7");
    assertThat(json).contains("\"name\":\"bar\"");
  }

  /** Registering the same type twice is rejected. */
  @Test
  public void testDuplicateRegistrationRejected() {
    ImmutableTypeAdapterFactory factory = new ImmutableTypeAdapterFactory();
    factory
        .register(Pair.class)
        .constructor(int.class, String.class)
        .parameter(0, true, "id")
        .parameter(1, true, "name")
        .register();
    try {
      factory
          .register(Pair.class)
          .constructor(int.class, String.class)
          .parameter(0, true, "id")
          .parameter(1, true, "name")
          .register();
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().contains("already registered");
    }
  }

  /** A parameter index out of range is rejected at registration time. */
  @Test
  public void testParameterIndexOutOfRangeRejected() {
    ImmutableTypeAdapterFactory factory = new ImmutableTypeAdapterFactory();
    try {
      factory
          .register(Pair.class)
          .constructor(int.class, String.class)
          .parameter(0, true, "id")
          .parameter(5, true, "name")
          .register();
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().contains("out of range");
    }
  }

  /** A JSON name bound to more than one parameter is rejected at registration time. */
  @Test
  public void testDuplicateJsonNameRejected() {
    ImmutableTypeAdapterFactory factory = new ImmutableTypeAdapterFactory();
    try {
      factory
          .register(Pair.class)
          .constructor(int.class, String.class)
          .parameter(0, true, "id")
          .parameter(1, true, "id")
          .register();
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().contains("more than one parameter");
    }
  }

  /** A constructor parameter that is never bound is rejected at registration time. */
  @Test
  public void testUnboundParameterRejected() {
    ImmutableTypeAdapterFactory factory = new ImmutableTypeAdapterFactory();
    try {
      factory
          .register(Pair.class)
          .constructor(int.class, String.class)
          .parameter(0, true, "id")
          .register();
      fail("Expected IllegalStateException");
    } catch (IllegalStateException expected) {
      assertThat(expected).hasMessageThat().contains("not bound");
    }
  }

  /** The factory returns null for unregistered types so the default adapter is unchanged. */
  @Test
  public void testUnregisteredTypeReturnsNull() {
    ImmutableTypeAdapterFactory factory =
        new ImmutableTypeAdapterFactory()
            .register(Pair.class)
            .constructor(int.class, String.class)
            .parameter(0, true, "id")
            .parameter(1, true, "name")
            .register();

    Gson gson = gsonWith(factory);
    // String is not registered with the factory; it must still work via the default adapter.
    assertThat(gson.fromJson("\"hello\"", String.class)).isEqualTo("hello");
    assertThat(gson.toJson("hello")).isEqualTo("\"hello\"");
  }

  /** A custom TypeAdapter for a parameter type is used (nested adapter delegation). */
  @Test
  public void testCustomParameterAdapterUsed() {
    ImmutableTypeAdapterFactory factory =
        new ImmutableTypeAdapterFactory()
            .register(WithList.class)
            .constructor(List.class)
            .parameter(0, true, "items")
            .register();

    // Register a custom adapter for List<String> to confirm it is consulted.
    TypeAdapter<List<String>> listAdapter =
        new TypeAdapter<List<String>>() {
          @Override
          public void write(JsonWriter out, List<String> value) throws IOException {
            out.beginArray();
            for (String s : value) {
              out.value(s);
            }
            out.endArray();
          }

          @Override
          public List<String> read(JsonReader in) throws IOException {
            in.beginArray();
            List<String> result = new ArrayList<>();
            while (in.hasNext()) {
              result.add(in.nextString());
            }
            in.endArray();
            return result;
          }
        };

    Gson gson =
        new GsonBuilder()
            .registerTypeAdapterFactory(factory)
            .registerTypeAdapter(
                new TypeToken<List<String>>() {}.getType(), listAdapter)
            .create();

    WithList result = gson.fromJson("{\"items\":[\"x\",\"y\"]}", WithList.class);
    assertThat(result.items).containsExactly("x", "y").inOrder();
  }

  /** A private constructor is made accessible and invoked via reflection only. */
  @Test
  public void testPrivateConstructorMadeAccessible() {
    ImmutableTypeAdapterFactory factory =
        new ImmutableTypeAdapterFactory()
            .register(PrivateConstructor.class)
            .constructor(int.class, String.class)
            .parameter(0, true, "id")
            .parameter(1, true, "name")
            .register();

    Gson gson = gsonWith(factory);
    PrivateConstructor result = gson.fromJson("{\"id\":3,\"name\":\"z\"}", PrivateConstructor.class);
    assertThat(result.id).isEqualTo(3);
    assertThat(result.name).isEqualTo("z");
  }

  /** Arrays of immutable elements use the registered adapter for each element. */
  @Test
  public void testArrayOfImmutableElements() {
    ImmutableTypeAdapterFactory factory =
        new ImmutableTypeAdapterFactory()
            .register(Pair.class)
            .constructor(int.class, String.class)
            .parameter(0, true, "id")
            .parameter(1, true, "name")
            .register();

    Gson gson = gsonWith(factory);
    Pair[] pairs =
        gson.fromJson(
            "[{\"id\":1,\"name\":\"a\"},{\"id\":2,\"name\":\"b\"}]", Pair[].class);

    assertThat(pairs).hasLength(2);
    assertThat(pairs[0].id).isEqualTo(1);
    assertThat(pairs[1].name).isEqualTo("b");
  }

  /** Null JSON object is handled. */
  @Test
  public void testNullObject() {
    ImmutableTypeAdapterFactory factory =
        new ImmutableTypeAdapterFactory()
            .register(Pair.class)
            .constructor(int.class, String.class)
            .parameter(0, true, "id")
            .parameter(1, true, "name")
            .register();

    Gson gson = gsonWith(factory);
    Pair pair = gson.fromJson("null", Pair.class);
    assertThat(pair).isNull();
  }

  /** Verify the two-field example only uses two fields and binds both. */
  @Test
  public void testTwoFieldExample() {
    ImmutableTypeAdapterFactory factory =
        new ImmutableTypeAdapterFactory()
            .register(Pair.class)
            .constructor(int.class, String.class)
            .parameter(0, true, "id")
            .parameter(1, true, "name")
            .register();

    Gson gson = gsonWith(factory);
    Pair pair = gson.fromJson("{\"id\":42,\"name\":\"answer\"}", Pair.class);
    assertThat(pair.id).isEqualTo(42);
    assertThat(pair.name).isEqualTo("answer");
    long nonStaticFieldCount =
        Arrays.stream(Pair.class.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()))
            .count();
    assertThat(nonStaticFieldCount).isEqualTo(2);
  }
}
