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

package com.google.gson.functional;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.gson.EnumValue;
import com.google.gson.EnumValueTypeAdapterFactory;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Functional tests for {@link EnumValue} and {@link EnumValueTypeAdapterFactory}.
 */
public class EnumValueTest {

  private Gson gson;

  @Before
  public void setUp() {
    gson = new GsonBuilder().registerTypeAdapterFactory(new EnumValueTypeAdapterFactory()).create();
  }

  private enum Color {
    @SerializedName("red")
    RED,
    @SerializedName("crimson")
    CRIMSON,
    @SerializedName(value = "blue", alternate = {"navy", "azure"})
    BLUE,
    GREEN
  }

  @Test
  public void testKnownValue_serializesCanonicalName() {
    EnumValue<Color> value = gson.fromJson("\"red\"", new TypeToken<EnumValue<Color>>() {});
    assertThat(value.isKnown()).isTrue();
    assertThat(value.getValue()).isEqualTo(Color.RED);
    assertThat(value.getRawValue()).isNull();
    // Output uses the canonical name, not the SerializedName value.
    assertThat(gson.toJson(value, new TypeToken<EnumValue<Color>>() {}.getType())).isEqualTo("\"RED\"");
  }

  @Test
  public void testKnownValueByAlternate_serializesCanonicalName() {
    EnumValue<Color> value = gson.fromJson("\"navy\"", new TypeToken<EnumValue<Color>>() {});
    assertThat(value.isKnown()).isTrue();
    assertThat(value.getValue()).isEqualTo(Color.BLUE);
    assertThat(gson.toJson(value, new TypeToken<EnumValue<Color>>() {}.getType())).isEqualTo("\"BLUE\"");
  }

  @Test
  public void testCanonicalNameReadBack_isKnown() {
    // The canonical name must also resolve as a known value so round trips work.
    EnumValue<Color> value = gson.fromJson("\"BLUE\"", new TypeToken<EnumValue<Color>>() {});
    assertThat(value.isKnown()).isTrue();
    assertThat(value.getValue()).isEqualTo(Color.BLUE);
  }

  @Test
  public void testUnknownValue_preservesRawString() {
    EnumValue<Color> value = gson.fromJson("\"future\"", new TypeToken<EnumValue<Color>>() {});
    assertThat(value.isUnknown()).isTrue();
    assertThat(value.isKnown()).isFalse();
    assertThat(value.isNull()).isFalse();
    assertThat(value.getValue()).isNull();
    assertThat(value.getRawValue()).isEqualTo("future");
    assertThat(gson.toJson(value, new TypeToken<EnumValue<Color>>() {}.getType())).isEqualTo("\"future\"");
  }

  @Test
  public void testNullValue_isDistinctFromUnknown() {
    EnumValue<Color> value = gson.fromJson("null", new TypeToken<EnumValue<Color>>() {});
    assertThat(value.isNull()).isTrue();
    assertThat(value.isKnown()).isFalse();
    assertThat(value.isUnknown()).isFalse();
    assertThat(value.getValue()).isNull();
    assertThat(value.getRawValue()).isNull();
    assertThat(gson.toJson(value, new TypeToken<EnumValue<Color>>() {}.getType())).isEqualTo("null");
  }

  @Test
  public void testNullReference_serializesToNull() {
    String json = gson.toJson(null, new TypeToken<EnumValue<Color>>() {}.getType());
    assertThat(json).isEqualTo("null");
  }

  @Test
  public void testListRoundTrip() {
    List<EnumValue<Color>> list = new ArrayList<>();
    list.add(EnumValue.of(Color.RED));
    list.add(EnumValue.unknown("future"));
    list.add(null);
    list.add(EnumValue.of(Color.BLUE));

    TypeToken<List<EnumValue<Color>>> type = new TypeToken<List<EnumValue<Color>>>() {};
    String json = gson.toJson(list, type.getType());
    assertThat(json).isEqualTo("[\"RED\",\"future\",null,\"BLUE\"]");

    List<EnumValue<Color>> restored = gson.fromJson(json, type);
    assertThat(restored).hasSize(4);
    assertThat(restored.get(0).isKnown()).isTrue();
    assertThat(restored.get(0).getValue()).isEqualTo(Color.RED);
    assertThat(restored.get(1).isUnknown()).isTrue();
    assertThat(restored.get(1).getRawValue()).isEqualTo("future");
    // JSON null becomes a null-state EnumValue, distinct from a Java null element.
    assertThat(restored.get(2).isNull()).isTrue();
    assertThat(restored.get(3).getValue()).isEqualTo(Color.BLUE);
  }

  @Test
  public void testObjectWithEnumValueField() {
    String json = "{\"color\":\"red\",\"next\":\"future\",\"missing\":null}";
    Paint paint = gson.fromJson(json, Paint.class);
    assertThat(paint.color.getValue()).isEqualTo(Color.RED);
    assertThat(paint.next.isUnknown()).isTrue();
    assertThat(paint.next.getRawValue()).isEqualTo("future");
    assertThat(paint.missing.isNull()).isTrue();

    // By default Gson skips null fields; with serializeNulls() the null-state EnumValue is
    // written as JSON null.
    Gson gsonWithNulls =
        new GsonBuilder()
            .registerTypeAdapterFactory(new EnumValueTypeAdapterFactory())
            .serializeNulls()
            .create();
    String written = gsonWithNulls.toJson(paint);
    assertThat(written).contains("\"color\":\"RED\"");
    assertThat(written).contains("\"next\":\"future\"");
    assertThat(written).contains("\"missing\":null");
  }

  private static class Paint {
    EnumValue<Color> color;
    EnumValue<Color> next;
    EnumValue<Color> missing;
  }

  @Test
  public void testNumber_isRejected() {
    JsonParseException thrown =
        assertThrows(
            JsonParseException.class,
            () -> gson.fromJson("123", new TypeToken<EnumValue<Color>>() {}));
    assertThat(thrown).hasMessageThat().contains("EnumValue");
  }

  @Test
  public void testBoolean_isRejected() {
    assertThrows(
        JsonParseException.class,
        () -> gson.fromJson("true", new TypeToken<EnumValue<Color>>() {}));
  }

  @Test
  public void testPlainEnum_keepsDefaultBehavior() {
    // Plain enums are unaffected: unknown values still become null, and the SerializedName
    // value is used for output.
    assertThat(gson.fromJson("\"future\"", Color.class)).isNull();
    assertThat(gson.fromJson("\"red\"", Color.class)).isEqualTo(Color.RED);
    assertThat(gson.toJson(Color.RED)).isEqualTo("\"red\"");
  }

  private enum ToStringEnum {
    A,
    B;

    @Override
    public String toString() {
      return "custom-" + name();
    }
  }

  @Test
  public void testSerialization_doesNotCallUserToString() {
    EnumValue<ToStringEnum> value = EnumValue.of(ToStringEnum.A);
    String json = gson.toJson(value, new TypeToken<EnumValue<ToStringEnum>>() {}.getType());
    assertThat(json).isEqualTo("\"A\"");
  }

  @Test
  public void testToStringEnum_unknownAndKnownRoundTrip() {
    // Known value resolves by canonical name; unknown string is preserved.
    assertThat(gson.fromJson("\"A\"", new TypeToken<EnumValue<ToStringEnum>>() {}).getValue())
        .isEqualTo(ToStringEnum.A);
    assertThat(gson.fromJson("\"custom-A\"", new TypeToken<EnumValue<ToStringEnum>>() {}).isUnknown())
        .isTrue();
    assertThat(gson.fromJson("\"nope\"", new TypeToken<EnumValue<ToStringEnum>>() {}).getRawValue())
        .isEqualTo("nope");
  }

  private enum CollidingBySerializedName {
    @SerializedName("x")
    A,
    @SerializedName("x")
    B
  }

  @Test
  public void testCollisionBySerializedName_isRejectedAtAdapterCreation() {
    TypeToken<EnumValue<CollidingBySerializedName>> type =
        new TypeToken<EnumValue<CollidingBySerializedName>>() {};
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> gson.getAdapter(type));
    assertThat(thrown).hasMessageThat().contains("x");
    assertThat(thrown).hasMessageThat().contains("A");
    assertThat(thrown).hasMessageThat().contains("B");
  }

  private enum CollidingByAlternate {
    @SerializedName(value = "a", alternate = {"shared"})
    A,
    @SerializedName(value = "b", alternate = {"shared"})
    B
  }

  @Test
  public void testCollisionByAlternate_isRejectedAtAdapterCreation() {
    TypeToken<EnumValue<CollidingByAlternate>> type =
        new TypeToken<EnumValue<CollidingByAlternate>>() {};
    assertThrows(IllegalArgumentException.class, () -> gson.getAdapter(type));
  }

  private enum CollidingByNameAndSerializedName {
    @SerializedName("B")
    A,
    B
  }

  @Test
  public void testCollisionByNameAndSerializedName_isRejectedAtAdapterCreation() {
    TypeToken<EnumValue<CollidingByNameAndSerializedName>> type =
        new TypeToken<EnumValue<CollidingByNameAndSerializedName>>() {};
    assertThrows(IllegalArgumentException.class, () -> gson.getAdapter(type));
  }

  @Test
  public void testCollision_doesNotLetLastConstantOverwriteFirst() {
    // The rejection must happen at adapter creation, not silently resolve to the last constant.
    TypeToken<EnumValue<CollidingBySerializedName>> type =
        new TypeToken<EnumValue<CollidingBySerializedName>>() {};
    assertThrows(IllegalArgumentException.class, () -> gson.getAdapter(type));
  }

  /**
   * A counting factory wrapper that records how many times {@code create()} is called. This
   * verifies that the adapter is built once per enum type and reused, not rebuilt on every read.
   */
  private static final class CountingFactory implements TypeAdapterFactory {
    private final TypeAdapterFactory delegate;
    int createCount;

    CountingFactory(TypeAdapterFactory delegate) {
      this.delegate = delegate;
    }

    @Override
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
      TypeAdapter<T> adapter = delegate.create(gson, type);
      if (adapter != null) {
        createCount++;
      }
      return adapter;
    }
  }

  @Test
  public void testAdapterIsCreatedOncePerType() {
    CountingFactory counting = new CountingFactory(new EnumValueTypeAdapterFactory());
    Gson gsonWithCounting =
        new GsonBuilder().registerTypeAdapterFactory(counting).create();

    TypeToken<EnumValue<Color>> colorType = new TypeToken<EnumValue<Color>>() {};
    TypeToken<EnumValue<ToStringEnum>> otherType = new TypeToken<EnumValue<ToStringEnum>>() {};

    // Use each type multiple times; the adapter should be created only once per type.
    for (int i = 0; i < 3; i++) {
      gsonWithCounting.fromJson("\"red\"", colorType);
      gsonWithCounting.toJson(EnumValue.of(Color.RED), colorType.getType());
      gsonWithCounting.fromJson("\"A\"", otherType);
    }
    assertThat(counting.createCount).isEqualTo(2);
  }

  @Test
  public void testEnumValueOfNullState() {
    // The null state is accessible from the adapter; verify it round trips distinctly.
    EnumValue<Color> value = gson.fromJson("null", new TypeToken<EnumValue<Color>>() {});
    assertThat(value.isNull()).isTrue();
    assertThat(value).isEqualTo(EnumValue.ofNull());
    assertThat(value).isNotEqualTo(EnumValue.unknown("null"));
  }

  @Test
  public void testEnumValueEquality() {
    assertThat(EnumValue.of(Color.RED)).isEqualTo(EnumValue.of(Color.RED));
    assertThat(EnumValue.of(Color.RED)).isNotEqualTo(EnumValue.of(Color.BLUE));
    assertThat(EnumValue.unknown("x")).isEqualTo(EnumValue.unknown("x"));
    assertThat(EnumValue.unknown("x")).isNotEqualTo(EnumValue.unknown("y"));
    assertThat(EnumValue.of(Color.RED)).isNotEqualTo(EnumValue.unknown("RED"));
  }

  @Test
  public void testRawEnumValue_preservesUnknown() {
    // A raw EnumValue field cannot resolve constants, so every string is preserved as unknown.
    String json = "{\"raw\":\"anything\"}";
    RawHolder holder = gson.fromJson(json, RawHolder.class);
    assertThat(holder.raw.isUnknown()).isTrue();
    assertThat(holder.raw.getRawValue()).isEqualTo("anything");
    assertThat(gson.toJson(holder)).contains("\"raw\":\"anything\"");
  }

  private static class RawHolder {
    @SuppressWarnings("rawtypes")
    EnumValue raw;
  }
}
