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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.FieldNamingStrategy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.google.gson.internal.ConstructorConstructor;
import com.google.gson.internal.Excluder;
import com.google.gson.internal.bind.ReflectiveTypeAdapterFactory.FieldNamingConflictReport;
import com.google.gson.internal.bind.ReflectiveTypeAdapterFactory.FieldNamingConflictReport.Conflict;
import com.google.gson.internal.bind.ReflectiveTypeAdapterFactory.FieldNamingConflictReport.Direction;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Tests for {@link ReflectiveTypeAdapterFactory#checkFieldNamingConflicts(TypeToken)}, the explicit
 * field-name precheck of the reflective adapter.
 */
public class ReflectiveTypeAdapterFactoryFieldNamingPrecheckTest {
  /**
   * Naming strategy under which the parent field {@code id} and the child field {@code childId}
   * both become the JSON name "id".
   */
  private static final FieldNamingStrategy COLLIDING_NAMES =
      field -> field.getName().equals("childId") ? "id" : field.getName();

  private static ReflectiveTypeAdapterFactory newFactory(
      FieldNamingStrategy namingStrategy, Excluder excluder) {
    ConstructorConstructor constructorConstructor =
        new ConstructorConstructor(Collections.emptyMap(), true, Collections.emptyList());
    return new ReflectiveTypeAdapterFactory(
        constructorConstructor,
        namingStrategy,
        excluder,
        new JsonAdapterAnnotationTypeAdapterFactory(constructorConstructor),
        Collections.emptyList());
  }

  private static ReflectiveTypeAdapterFactory newFactory(FieldNamingStrategy namingStrategy) {
    return newFactory(namingStrategy, Excluder.DEFAULT);
  }

  @SuppressWarnings("unused")
  private static class Parent {
    int id;
  }

  @SuppressWarnings("unused")
  private static class Child extends Parent {
    int childId;
  }

  @Test
  public void inheritedFieldsCollidingThroughNamingStrategy_locatesBothFields() throws Exception {
    ReflectiveTypeAdapterFactory factory = newFactory(COLLIDING_NAMES);

    FieldNamingConflictReport report =
        factory.checkFieldNamingConflicts(TypeToken.get(Child.class));

    assertThat(report.hasConflicts()).isTrue();
    Field parentField = Parent.class.getDeclaredField("id");
    Field childField = Child.class.getDeclaredField("childId");

    // Both directions see the collision, and both fields are located
    for (Direction direction : Direction.values()) {
      List<Conflict> conflicts = report.conflicts(direction);
      assertThat(conflicts).hasSize(1);
      Conflict conflict = conflicts.get(0);
      assertThat(conflict.direction()).isEqualTo(direction);
      assertThat(conflict.jsonName()).isEqualTo("id");
      // Most specific class first
      assertThat(conflict.fields()).containsExactly(childField, parentField).inOrder();
    }

    Conflict conflict = report.conflicts().get(0);
    assertThat(conflict.toString()).contains("$Child#childId");
    assertThat(conflict.toString()).contains("$Parent#id");

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, report::throwIfAnyConflict);
    assertThat(exception).hasMessageThat().contains("$Child#childId");
    assertThat(exception).hasMessageThat().contains("$Parent#id");
    assertThat(exception).hasMessageThat().contains("named 'id'");
  }

  @Test
  public void serializationOnlyExclusion_readDirectionStillReportsConflict() {
    ExclusionStrategy skipChildId =
        new ExclusionStrategy() {
          @Override
          public boolean shouldSkipField(FieldAttributes fieldAttributes) {
            return fieldAttributes.getDeclaringClass() == Child.class
                && fieldAttributes.getName().equals("childId");
          }

          @Override
          public boolean shouldSkipClass(Class<?> clazz) {
            return false;
          }
        };
    // Exclude Child#Id for serialization only
    Excluder excluder = Excluder.DEFAULT.withExclusionStrategy(skipChildId, true, false);
    ReflectiveTypeAdapterFactory factory = newFactory(COLLIDING_NAMES, excluder);

    FieldNamingConflictReport report =
        factory.checkFieldNamingConflicts(TypeToken.get(Child.class));

    // Write direction: only Parent#id participates, so there is no conflict
    assertThat(report.serializedNames()).containsExactly("id");
    assertThat(report.conflicts(Direction.SERIALIZE)).isEmpty();

    // Read direction: both fields still participate, so the conflict is still reported
    List<Conflict> readConflicts = report.conflicts(Direction.DESERIALIZE);
    assertThat(readConflicts).hasSize(1);
    assertThat(readConflicts.get(0).jsonName()).isEqualTo("id");
    assertThat(readConflicts.get(0).fields()).hasSize(2);
    assertThat(report.hasConflicts()).isTrue();
  }

  @SuppressWarnings("unused")
  private static class WithAlternate {
    @SerializedName(value = "identifier", alternate = "id")
    int a;

    int id;
  }

  @Test
  public void serializedNameAlternate_countsTowardReadSet() throws Exception {
    ReflectiveTypeAdapterFactory factory = newFactory(FieldNamingPolicy.IDENTITY);

    FieldNamingConflictReport report =
        factory.checkFieldNamingConflicts(TypeToken.get(WithAlternate.class));

    // Write names are distinct: no serialization conflict
    assertThat(report.serializedNames()).containsExactly("identifier", "id");
    assertThat(report.conflicts(Direction.SERIALIZE)).isEmpty();

    // The alternate name "id" is part of the read set and collides with the real "id"
    assertThat(report.deserializedNames()).containsExactly("identifier", "id");
    List<Conflict> readConflicts = report.conflicts(Direction.DESERIALIZE);
    assertThat(readConflicts).hasSize(1);
    Conflict conflict = readConflicts.get(0);
    assertThat(conflict.jsonName()).isEqualTo("id");
    assertThat(conflict.fields())
        .containsExactly(
            WithAlternate.class.getDeclaredField("a"), WithAlternate.class.getDeclaredField("id"))
        .inOrder();
  }

  @SuppressWarnings("unused")
  private static class ChildWithTransient extends Parent {
    transient int childId;
  }

  @Test
  public void excludedFields_doNotParticipate() {
    // Excluder.DEFAULT excludes transient fields for both directions
    ReflectiveTypeAdapterFactory factory = newFactory(COLLIDING_NAMES);

    FieldNamingConflictReport report =
        factory.checkFieldNamingConflicts(TypeToken.get(ChildWithTransient.class));

    assertThat(report.hasConflicts()).isFalse();
    assertThat(report.serializedNames()).containsExactly("id");
    assertThat(report.deserializedNames()).containsExactly("id");
    report.throwIfAnyConflict(); // must not throw
  }

  @SuppressWarnings("unused")
  private static class GenericBox<T> {
    T item;
  }

  @SuppressWarnings("unused")
  private static class IntBox extends GenericBox<Integer> {
    int count;
  }

  @Test
  public void genericFieldTypes_resolvedAlongTypeToken() throws Exception {
    ReflectiveTypeAdapterFactory factory = newFactory(FieldNamingPolicy.IDENTITY);
    Field itemField = GenericBox.class.getDeclaredField("item");

    // Type variable of a generic superclass resolved through the subclass declaration
    FieldNamingConflictReport report =
        factory.checkFieldNamingConflicts(TypeToken.get(IntBox.class));
    assertThat(report.hasConflicts()).isFalse();
    assertThat(report.resolvedFieldTypes().get(itemField)).isEqualTo(Integer.class);

    // Type variable resolved through a parameterized TypeToken
    FieldNamingConflictReport parameterizedReport =
        factory.checkFieldNamingConflicts(new TypeToken<GenericBox<String>>() {});
    assertThat(parameterizedReport.resolvedFieldTypes().get(itemField)).isEqualTo(String.class);
  }

  @SuppressWarnings("unused")
  private static class Counted {
    @SuppressWarnings(
        "NonFinalStaticField") // mutated by test to verify precheck creates no objects
    static int constructorCount;

    @SuppressWarnings("NonFinalStaticField") // mutated by test to verify precheck calls no getters
    static int getterCount;

    int value;

    Counted() {
      constructorCount++;
    }

    int getValue() {
      getterCount++;
      return value;
    }
  }

  @Test
  public void precheck_doesNotInstantiateOrInvokeGetters() {
    Counted.constructorCount = 0;
    Counted.getterCount = 0;
    ReflectiveTypeAdapterFactory factory = newFactory(FieldNamingPolicy.IDENTITY);

    FieldNamingConflictReport report =
        factory.checkFieldNamingConflicts(TypeToken.get(Counted.class));

    assertThat(report.serializedNames()).containsExactly("value");
    // The precheck only reads reflection metadata; it creates no objects and calls no getters
    assertThat(Counted.constructorCount).isEqualTo(0);
    assertThat(Counted.getterCount).isEqualTo(0);
  }

  @Test
  public void precheck_reportsConflictBeforeAnyJsonIsRead_defaultAdapterBehaviorUnchanged() {
    // The precheck reports the conflict without any adapter being created or JSON being read
    ReflectiveTypeAdapterFactory factory = newFactory(COLLIDING_NAMES);
    assertThat(factory.checkFieldNamingConflicts(TypeToken.get(Child.class)).hasConflicts())
        .isTrue();

    // Default adapter behavior is unchanged: Gson still rejects the duplicate field names
    Gson gson = new GsonBuilder().setFieldNamingStrategy(COLLIDING_NAMES).create();
    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> gson.getAdapter(Child.class));
    assertThat(exception).hasMessageThat().contains("declares multiple JSON fields named 'id'");
  }

  @Test
  public void interfaceType_emptyReport() {
    ReflectiveTypeAdapterFactory factory = newFactory(FieldNamingPolicy.IDENTITY);

    FieldNamingConflictReport report =
        factory.checkFieldNamingConflicts(TypeToken.get(Runnable.class));

    assertThat(report.hasConflicts()).isFalse();
    assertThat(report.serializedNames()).isEmpty();
    assertThat(report.deserializedNames()).isEmpty();
    assertThat(report.resolvedFieldTypes()).isEmpty();
    report.throwIfAnyConflict(); // must not throw
  }
}
