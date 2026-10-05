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

package com.google.gson.internal.bind;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.FieldNamingStrategy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.google.gson.annotations.SerializedName;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Tests for {@link ReflectiveTypeAdapterFactory#checkFieldNaming}, an explicit, side-effect-free
 * precheck of the JSON field names of a type.
 */
public class ReflectiveTypeAdapterFactoryPrecheckTest {

  /** A naming strategy that maps every field to the same JSON name "id". */
  private static final FieldNamingStrategy ALL_FIELDS_AS_ID = f -> "id";

  // -------------------------------------------------------------------------------------------
  // Scenario 1: parent and child fields both map to "id" via the naming strategy
  // -------------------------------------------------------------------------------------------

  static class Parent {
    @SuppressWarnings("unused")
    String id;
  }

  static class Child extends Parent {
    @SuppressWarnings("unused")
    String childId;
  }

  @Test
  public void testPrecheckLocatesBothFieldsWhenStrategyCollidesNames() {
    Gson gson = new GsonBuilder().setFieldNamingStrategy(ALL_FIELDS_AS_ID).create();
    TypeToken<Child> type = TypeToken.get(Child.class);

    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> ReflectiveTypeAdapterFactory.checkFieldNaming(gson, type));

    // Both fields involved in the conflict must be located in the error message.
    assertThat(e.getMessage()).contains("'id'");
    assertThat(e.getMessage()).contains("Child#childId");
    assertThat(e.getMessage()).contains("Parent#id");
  }

  @Test
  public void testPrecheckReportsReadConflictEvenWhenWriteConflictExcluded() {
    // Exclude Child.childId from SERIALIZATION only; it still participates in deserialization.
    ExclusionStrategy serializeOnly =
        new ExclusionStrategy() {
          @Override
          public boolean shouldSkipField(FieldAttributes f) {
            return f.getDeclaringClass() == Child.class && f.getName().equals("childId");
          }

          @Override
          public boolean shouldSkipClass(Class<?> clazz) {
            return false;
          }
        };
    Gson gson =
        new GsonBuilder()
            .setFieldNamingStrategy(ALL_FIELDS_AS_ID)
            .addSerializationExclusionStrategy(serializeOnly)
            .create();
    TypeToken<Child> type = TypeToken.get(Child.class);

    // Serialization now has only Parent.id -> "id", so there is no write conflict.
    // Deserialization still has both fields -> the read direction must report the conflict.
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> ReflectiveTypeAdapterFactory.checkFieldNaming(gson, type));

    assertThat(e.getMessage()).contains("Child#childId");
    assertThat(e.getMessage()).contains("Parent#id");
  }

  @Test
  public void testDefaultAdapterBehaviorStillReportsConflict() {
    // The reflection adapter itself must still detect the conflict when it is created, so the
    // default behavior is unchanged.
    Gson gson = new GsonBuilder().setFieldNamingStrategy(ALL_FIELDS_AS_ID).create();
    assertThrows(IllegalArgumentException.class, () -> gson.getAdapter(Child.class));
  }

  // -------------------------------------------------------------------------------------------
  // Scenario 2: alternate names are counted in the read (deserialization) name set
  // -------------------------------------------------------------------------------------------

  static class WithAlternates {
    @SerializedName(value = "id", alternate = {"idAlt"})
    @SuppressWarnings("unused")
    String id;

    @SuppressWarnings("unused")
    String idAlt;
  }

  @Test
  public void testPrecheckCountsAlternatesInReadSet() {
    Gson gson = new Gson();
    TypeToken<WithAlternates> type = TypeToken.get(WithAlternates.class);

    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> ReflectiveTypeAdapterFactory.checkFieldNaming(gson, type));

    assertThat(e.getMessage()).contains("'idAlt'");
    assertThat(e.getMessage()).contains("WithAlternates#id");
    assertThat(e.getMessage()).contains("WithAlternates#idAlt");
  }

  static class AlternatesAreReadOnly {
    @SerializedName(value = "a", alternate = {"shared"})
    @SuppressWarnings("unused")
    String fieldA;

    @SerializedName(value = "b", alternate = {"shared"})
    @SuppressWarnings("unused")
    String fieldB;
  }

  @Test
  public void testPrecheckDoesNotCountAlternatesInWriteSet() {
    Gson gson = new Gson();
    TypeToken<AlternatesAreReadOnly> type = TypeToken.get(AlternatesAreReadOnly.class);

    // Serialization uses only the default names ("a", "b"), so there is no write conflict.
    // Deserialization includes the alternate "shared" for both fields, so there is a read conflict.
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> ReflectiveTypeAdapterFactory.checkFieldNaming(gson, type));

    assertThat(e.getMessage()).contains("'shared'");
  }

  // -------------------------------------------------------------------------------------------
  // Scenario 3: the precheck does not create objects or invoke field getters
  // -------------------------------------------------------------------------------------------

  static class CountingConstructor {
    static final AtomicInteger constructorCalls = new AtomicInteger();

    @SuppressWarnings("unused")
    String id;

    CountingConstructor() {
      constructorCalls.incrementAndGet();
    }
  }

  @Test
  public void testPrecheckDoesNotInstantiateType() {
    CountingConstructor.constructorCalls.set(0);
    Gson gson = new Gson();
    TypeToken<CountingConstructor> type = TypeToken.get(CountingConstructor.class);

    ReflectiveTypeAdapterFactory.checkFieldNaming(gson, type);

    // The precheck must not construct the type.
    assertThat(CountingConstructor.constructorCalls.get()).isEqualTo(0);
  }

  // -------------------------------------------------------------------------------------------
  // Negative cases: no conflict
  // -------------------------------------------------------------------------------------------

  static class NoConflict {
    @SuppressWarnings("unused")
    String a;

    @SuppressWarnings("unused")
    String b;
  }

  @Test
  public void testPrecheckPassesWhenNoConflict() {
    Gson gson = new Gson();
    TypeToken<NoConflict> type = TypeToken.get(NoConflict.class);
    // Should not throw.
    ReflectiveTypeAdapterFactory.checkFieldNaming(gson, type);
  }

  static class ExcludedBothDirections {
    @SuppressWarnings("unused")
    transient String a;

    @SuppressWarnings("unused")
    transient String b;
  }

  @Test
  public void testPrecheckIgnoresFieldsExcludedInBothDirections() {
    Gson gson = new Gson();
    TypeToken<ExcludedBothDirections> type = TypeToken.get(ExcludedBothDirections.class);
    // transient fields are excluded by default for both directions, so no conflict.
    ReflectiveTypeAdapterFactory.checkFieldNaming(gson, type);
  }

  // -------------------------------------------------------------------------------------------
  // Generic field types are resolved along the type token
  // -------------------------------------------------------------------------------------------

  static class GenericBase<T> {
    @SuppressWarnings("unused")
    T value;
  }

  static class GenericDerived extends GenericBase<String> {
    @SuppressWarnings("unused")
    String derived;
  }

  @Test
  public void testPrecheckWalksGenericHierarchy() {
    Gson gson = new Gson();
    TypeToken<GenericDerived> type = TypeToken.get(GenericDerived.class);
    // Should not throw; the generic superclass is resolved along the type token.
    ReflectiveTypeAdapterFactory.checkFieldNaming(gson, type);
  }
}
