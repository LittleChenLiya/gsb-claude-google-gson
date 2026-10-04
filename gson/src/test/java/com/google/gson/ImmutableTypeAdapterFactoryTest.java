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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;

/** Tests for {@link ImmutableTypeAdapterFactory}. */
public class ImmutableTypeAdapterFactoryTest {
  /** Immutable test class whose constructor counts successful invocations. */
  static final class Pair {
    static final AtomicInteger constructions = new AtomicInteger();

    final int id;
    final String name;

    Pair(int id, String name) {
      if (id < 0) {
        throw new IllegalArgumentException("id must be >= 0: " + id);
      }
      this.id = id;
      this.name = name;
      constructions.incrementAndGet();
    }
  }

  /** Immutable class with a generic constructor parameter. */
  static final class Box<T> {
    final List<T> items;
    final String label;

    Box(List<T> items, String label) {
      this.items = items;
      this.label = label;
    }
  }

  /** Mutable class used to verify that the default reflective adapter is unchanged. */
  static final class MutablePair {
    int id;
    String name;
  }

  private static ImmutableTypeAdapterFactory pairFactory() {
    try {
      Constructor<Pair> ctor = Pair.class.getDeclaredConstructor(int.class, String.class);
      return ImmutableTypeAdapterFactory.builder(Pair.class, ctor)
          .parameter("id")
          .parameter("name")
          .build();
    } catch (NoSuchMethodException e) {
      throw new AssertionError(e);
    }
  }

  private static Gson gsonWith(TypeAdapterFactory factory) {
    return new GsonBuilder().registerTypeAdapterFactory(factory).create();
  }

  @Before
  public void resetConstructionCount() {
    Pair.constructions.set(0);
  }

  @Test
  public void constructsImmutablePairFromJson() {
    Gson gson = gsonWith(pairFactory());

    Pair pair = gson.fromJson("{\"id\":1,\"name\":\"a\"}", Pair.class);

    assertThat(pair.id).isEqualTo(1);
    assertThat(pair.name).isEqualTo("a");
    assertThat(Pair.constructions.get()).isEqualTo(1);
  }

  @Test
  public void constructorExceptionPreservesCauseAndJsonPath() {
    Gson gson = gsonWith(pairFactory());

    JsonParseException e =
        assertThrows(
            JsonParseException.class, () -> gson.fromJson("{\"id\":-1,\"name\":\"a\"}", Pair.class));

    assertThat(e).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
    assertThat(e).hasCauseThat().hasMessageThat().isEqualTo("id must be >= 0: -1");
    assertThat(e).hasMessageThat().contains("JSON path $");
    // The constructor body threw before completing, so no instance was constructed.
    assertThat(Pair.constructions.get()).isEqualTo(0);
  }

  @Test
  public void missingRequiredParameterFailsBeforeConstruction() {
    Gson gson = gsonWith(pairFactory());

    JsonParseException e =
        assertThrows(JsonParseException.class, () -> gson.fromJson("{\"id\":1}", Pair.class));

    assertThat(e).hasMessageThat().contains("'name'");
    assertThat(e).hasMessageThat().contains("Missing required");
    assertThat(Pair.constructions.get()).isEqualTo(0);
  }

  @Test
  public void duplicateJsonFieldDoesNotOverwriteFirstValue() {
    Gson gson = gsonWith(pairFactory());

    JsonParseException e =
        assertThrows(
            JsonParseException.class, () -> gson.fromJson("{\"id\":1,\"id\":2,\"name\":\"a\"}", Pair.class));

    assertThat(e).hasMessageThat().contains("Conflicting JSON fields");
    assertThat(Pair.constructions.get()).isEqualTo(0);
  }

  @Test
  public void aliasConflictIsRejectedInsteadOfLastValueWinning() {
    Constructor<Pair> ctor;
    try {
      ctor = Pair.class.getDeclaredConstructor(int.class, String.class);
    } catch (NoSuchMethodException e) {
      throw new AssertionError(e);
    }
    ImmutableTypeAdapterFactory factory =
        ImmutableTypeAdapterFactory.builder(Pair.class, ctor)
            .parameter("id")
            .parameter("name", "title")
            .build();
    Gson gson = gsonWith(factory);

    // A single alias use works and binds to the same parameter.
    Pair pair = gson.fromJson("{\"id\":1,\"title\":\"b\"}", Pair.class);
    assertThat(pair.name).isEqualTo("b");
    assertThat(Pair.constructions.get()).isEqualTo(1);

    // Both the field name and its alias occurring is an explicit conflict, not last-wins.
    JsonParseException e =
        assertThrows(
            JsonParseException.class,
            () -> gson.fromJson("{\"id\":1,\"name\":\"a\",\"title\":\"b\"}", Pair.class));
    assertThat(e).hasMessageThat().contains("Conflicting JSON fields 'name' and 'title'");
    assertThat(e).hasMessageThat().contains("refusing to overwrite");
    assertThat(Pair.constructions.get()).isEqualTo(1);
  }

  @Test
  public void unknownFieldRejectedByDefault() {
    Gson gson = gsonWith(pairFactory());

    JsonSyntaxException e =
        assertThrows(
            JsonSyntaxException.class,
            () -> gson.fromJson("{\"id\":1,\"name\":\"a\",\"extra\":true}", Pair.class));

    assertThat(e).hasMessageThat().contains("Unknown JSON field 'extra'");
    assertThat(Pair.constructions.get()).isEqualTo(0);
  }

  @Test
  public void unknownFieldSkippedWhenRegistered() {
    try {
      Constructor<Pair> ctor = Pair.class.getDeclaredConstructor(int.class, String.class);
      ImmutableTypeAdapterFactory factory =
          ImmutableTypeAdapterFactory.builder(Pair.class, ctor)
              .parameter("id")
              .parameter("name")
              .onUnknownField(ImmutableTypeAdapterFactory.UnknownFieldPolicy.SKIP)
              .build();
      Gson gson = gsonWith(factory);

      Pair pair = gson.fromJson("{\"id\":1,\"extra\":{\"nested\":[1,2]},\"name\":\"a\"}", Pair.class);

      assertThat(pair.id).isEqualTo(1);
      assertThat(pair.name).isEqualTo("a");
      assertThat(Pair.constructions.get()).isEqualTo(1);
    } catch (NoSuchMethodException e) {
      throw new AssertionError(e);
    }
  }

  @Test
  public void nullHandling() {
    Gson gson = gsonWith(pairFactory());

    // JSON null for the whole object yields null without invoking the constructor.
    assertThat(gson.fromJson("null", Pair.class)).isNull();
    assertThat(Pair.constructions.get()).isEqualTo(0);

    // JSON null is a valid value for a reference type parameter.
    Pair pair = gson.fromJson("{\"id\":1,\"name\":null}", Pair.class);
    assertThat(pair.name).isNull();
    assertThat(Pair.constructions.get()).isEqualTo(1);

    // JSON null for a primitive parameter is rejected before construction.
    JsonSyntaxException e =
        assertThrows(
            JsonSyntaxException.class, () -> gson.fromJson("{\"id\":null,\"name\":\"a\"}", Pair.class));
    assertThat(e).hasMessageThat().contains("primitive constructor parameter 'id'");
    assertThat(Pair.constructions.get()).isEqualTo(1);
  }

  @Test
  public void optionalParameterFallsBackToRegisteredDefault() {
    try {
      Constructor<Pair> ctor = Pair.class.getDeclaredConstructor(int.class, String.class);
      ImmutableTypeAdapterFactory factory =
          ImmutableTypeAdapterFactory.builder(Pair.class, ctor)
              .parameter("id")
              .optionalParameter("name", "unnamed")
              .build();
      Gson gson = gsonWith(factory);

      Pair pair = gson.fromJson("{\"id\":7}", Pair.class);

      assertThat(pair.id).isEqualTo(7);
      assertThat(pair.name).isEqualTo("unnamed");
      assertThat(Pair.constructions.get()).isEqualTo(1);
    } catch (NoSuchMethodException e) {
      throw new AssertionError(e);
    }
  }

  @Test
  public void genericParameterResolvedAgainstActualTypeToken() {
    try {
      @SuppressWarnings("rawtypes")
      Constructor<Box> ctor = Box.class.getDeclaredConstructor(List.class, String.class);
      ImmutableTypeAdapterFactory factory =
          ImmutableTypeAdapterFactory.builder(Box.class, ctor)
              .parameter("items")
              .parameter("label")
              .build();
      Gson gson = gsonWith(factory);

      Box<Integer> box =
          gson.fromJson("{\"items\":[1,2,3],\"label\":\"l\"}", new TypeToken<Box<Integer>>() {});

      // The nested generic parameter List<T> was resolved to List<Integer> and handled by
      // Gson's existing collection adapter.
      assertThat(box.items).containsExactly(1, 2, 3).inOrder();
      assertThat(box.label).isEqualTo("l");
    } catch (NoSuchMethodException e) {
      throw new AssertionError(e);
    }
  }

  @Test
  public void serializationDelegatesToRegularAdapter() {
    Gson gson = gsonWith(pairFactory());

    Pair pair = gson.fromJson("{\"id\":1,\"name\":\"a\"}", Pair.class);

    assertThat(gson.toJson(pair)).isEqualTo("{\"id\":1,\"name\":\"a\"}");
  }

  @Test
  public void defaultReflectiveAdapterUnchangedForOtherTypes() {
    Gson gson = gsonWith(pairFactory());

    // Types not registered with the factory still use Gson's default reflective adapter.
    MutablePair pair = gson.fromJson("{\"id\":1,\"name\":\"a\",\"unknown\":1}", MutablePair.class);

    assertThat(pair.id).isEqualTo(1);
    assertThat(pair.name).isEqualTo("a");
    assertThat(Pair.constructions.get()).isEqualTo(0);
  }

  @Test
  public void builderRejectsMismatchedParameterCount() {
    try {
      Constructor<Pair> ctor = Pair.class.getDeclaredConstructor(int.class, String.class);
      ImmutableTypeAdapterFactory.Builder<Pair> builder =
          ImmutableTypeAdapterFactory.builder(Pair.class, ctor).parameter("id");

      IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);

      assertThat(e).hasMessageThat().contains("1 parameter binding(s)");
    } catch (NoSuchMethodException e) {
      throw new AssertionError(e);
    }
  }

  @Test
  public void builderRejectsDuplicateJsonNames() {
    try {
      Constructor<Pair> ctor = Pair.class.getDeclaredConstructor(int.class, String.class);
      ImmutableTypeAdapterFactory.Builder<Pair> builder =
          ImmutableTypeAdapterFactory.builder(Pair.class, ctor)
              .parameter("id", "name")
              .parameter("name");

      IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);

      assertThat(e).hasMessageThat().contains("'name'");
    } catch (NoSuchMethodException e) {
      throw new AssertionError(e);
    }
  }
}
