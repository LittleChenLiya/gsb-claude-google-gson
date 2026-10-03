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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.junit.Before;
import org.junit.Test;

/** Tests for {@link EnumValue} together with {@link EnumValueTypeAdapterFactory}. */
public class EnumValueTypeAdapterFactoryTest {
  private enum Color {
    @SerializedName(
        value = "red",
        alternate = {"crimson", "rouge"})
    RED,
    @SerializedName("green")
    GREEN,
    BLUE
  }

  /** Enum whose {@code toString} must never be called during (de)serialization. */
  private enum WeirdToString {
    FIRST {
      @Override
      public String toString() {
        throw new AssertionError("user toString must not be called");
      }
    },
    SECOND {
      @Override
      public String toString() {
        throw new AssertionError("user toString must not be called");
      }
    };

    @Override
    public String toString() {
      throw new AssertionError("user toString must not be called");
    }
  }

  /** Two constants claiming the same alternate: the adapter must refuse this. */
  private enum CollidingAlias {
    @SerializedName(value = "a", alternate = "shared")
    A,
    @SerializedName(value = "b", alternate = "shared")
    B
  }

  /** Two constants claiming the same canonical serialized name: also a collision. */
  private enum CollidingName {
    @SerializedName("shared")
    A,
    @SerializedName("shared")
    B
  }

  /** An alternate equal to the same constant's own value is allowed. */
  private enum SelfDuplicateAlias {
    @SerializedName(
        value = "a",
        alternate = {"a", "a"})
    A,
    B
  }

  /** Counting object: verifies nested wrappers and counts the known constants. */
  private static class Palette {
    int count;
    List<EnumValue<Color>> colors;

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof Palette)) {
        return false;
      }
      Palette other = (Palette) obj;
      return count == other.count && Objects.equals(colors, other.colors);
    }

    @Override
    public int hashCode() {
      return Objects.hash(count, colors);
    }
  }

  private static class Holder<T> {
    T value;
  }

  private Gson gson;

  @Before
  public void setUp() {
    gson = new GsonBuilder().registerTypeAdapterFactory(new EnumValueTypeAdapterFactory()).create();
  }

  @Test
  public void testKnownNameAndAlternativesDeserializeToCanonicalName() {
    // Canonical SerializedName value
    assertThat(read("\"red\"")).isEqualTo(EnumValue.of(Color.RED));
    // Declared constant name is accepted too
    assertThat(read("\"BLUE\"")).isEqualTo(EnumValue.of(Color.BLUE));
    // Alternates are accepted
    assertThat(read("\"crimson\"")).isEqualTo(EnumValue.of(Color.RED));
    assertThat(read("\"rouge\"")).isEqualTo(EnumValue.of(Color.RED));

    // All known values serialize with the canonical serialized name
    assertThat(gson.toJson(EnumValue.of(Color.RED), enumValueType())).isEqualTo("\"red\"");
    assertThat(gson.toJson(EnumValue.of(Color.BLUE), enumValueType())).isEqualTo("\"BLUE\"");

    // A known alternative reads back and then outputs the canonical name
    EnumValue<Color> value = read("\"crimson\"");
    assertThat(value.isKnown()).isTrue();
    assertThat(value.getConstant()).isEqualTo(Color.RED);
    assertThat(value.getRawValue()).isEqualTo("red");
    assertThat(gson.toJson(value, enumValueType())).isEqualTo("\"red\"");
  }

  @Test
  public void testUnknownStringIsPreserved() {
    EnumValue<Color> future = read("\"future\"");
    assertThat(future.isUnknown()).isTrue();
    assertThat(future.isKnown()).isFalse();
    assertThat(future.getConstant()).isNull();
    assertThat(future.getRawValue()).isEqualTo("future");

    // Round-trips as the exact original string
    assertThat(gson.toJson(future, enumValueType())).isEqualTo("\"future\"");
  }

  @Test
  public void testJsonNullIsSeparateFromUnknownString() {
    EnumValue<Color> explicitNull = read("null");
    assertThat(explicitNull.isNull()).isTrue();
    assertThat(explicitNull.isUnknown()).isFalse();
    assertThat(explicitNull.isKnown()).isFalse();

    EnumValue<Color> unknown = read("\"unknown\"");
    assertThat(unknown.isNull()).isFalse();
    assertThat(unknown.isUnknown()).isTrue();

    // The two states are not equal
    assertThat(explicitNull).isNotEqualTo(unknown);
    assertThat(explicitNull).isEqualTo(EnumValue.ofNull());

    // Null state serializes back to JSON null
    assertThat(gson.toJson(explicitNull, enumValueType())).isEqualTo("null");
  }

  @Test
  public void testListRoundTrip() {
    Type listType = new TypeToken<List<EnumValue<Color>>>() {}.getType();
    String json = "[\"red\",\"crimson\",\"future\",null,\"BLUE\",\"green\",\"another\"]";

    @SuppressWarnings("unchecked")
    List<EnumValue<Color>> values = (List<EnumValue<Color>>) gson.fromJson(json, listType);

    assertThat(values)
        .containsExactly(
            EnumValue.of(Color.RED),
            EnumValue.of(Color.RED),
            EnumValue.unknown("future"),
            EnumValue.<Color>ofNull(),
            EnumValue.of(Color.BLUE),
            EnumValue.of(Color.GREEN),
            EnumValue.unknown("another"))
        .inOrder();

    // Full round trip: known aliases become canonical, everything else is preserved
    assertThat(gson.toJson(values, listType))
        .isEqualTo("[\"red\",\"red\",\"future\",null,\"BLUE\",\"green\",\"another\"]");

    // Reading the canonicalized form yields the same logical values
    @SuppressWarnings("unchecked")
    List<EnumValue<Color>> reread =
        (List<EnumValue<Color>>) gson.fromJson(gson.toJson(values, listType), listType);
    assertThat(reread).isEqualTo(values);
  }

  @Test
  public void testCountingObjectRoundTrip() {
    String json = "{\"count\":3,\"colors\":[\"red\",\"future\",null]}";
    Palette palette = gson.fromJson(json, Palette.class);
    assertThat(palette.count).isEqualTo(3);
    assertThat(palette.colors)
        .containsExactly(
            EnumValue.of(Color.RED), EnumValue.unknown("future"), EnumValue.<Color>ofNull())
        .inOrder();

    long knownCount = palette.colors.stream().filter(EnumValue::isKnown).count();
    long unknownCount = palette.colors.stream().filter(EnumValue::isUnknown).count();
    long nullCount = palette.colors.stream().filter(EnumValue::isNull).count();
    assertThat(knownCount).isEqualTo(1);
    assertThat(unknownCount).isEqualTo(1);
    assertThat(nullCount).isEqualTo(1);

    // Round trips through the object and stays structurally equal
    String reserialized = gson.toJson(palette);
    assertThat(gson.fromJson(reserialized, Palette.class)).isEqualTo(palette);
    assertThat(reserialized).isEqualTo("{\"count\":3,\"colors\":[\"red\",\"future\",null]}");
  }

  @Test
  public void testNumbersAndBooleansAreRejected() {
    assertRejected("1");
    assertRejected("1.5");
    assertRejected("true");
    assertRejected("false");
    assertRejected("{}");
    assertRejected("[]");
  }

  private void assertRejected(String json) {
    assertThrows(json, JsonSyntaxException.class, () -> gson.fromJson(json, enumValueType()));
  }

  @Test
  public void testAliasCollisionBetweenConstantsRejected() {
    Gson strict =
        new GsonBuilder().registerTypeAdapterFactory(new EnumValueTypeAdapterFactory()).create();
    Type type = new TypeToken<EnumValue<CollidingAlias>>() {}.getType();
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> strict.fromJson("\"a\"", type));
    assertThat(e).hasMessageThat().contains("shared");
    assertThat(e).hasMessageThat().contains("A");
    assertThat(e).hasMessageThat().contains("B");

    // Fails already when building the adapter, regardless of the JSON value
    assertThrows(IllegalArgumentException.class, () -> strict.fromJson("null", type));
  }

  @Test
  public void testCanonicalNameCollisionRejected() {
    Gson strict =
        new GsonBuilder().registerTypeAdapterFactory(new EnumValueTypeAdapterFactory()).create();
    Type type = new TypeToken<EnumValue<CollidingName>>() {}.getType();
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> strict.fromJson("\"shared\"", type));
    // Must not let the last constant silently win
    assertThat(e).hasMessageThat().contains("shared");
    assertThat(e).hasMessageThat().contains("A");
    assertThat(e).hasMessageThat().contains("B");
  }

  @Test
  public void testDuplicateNameOnSameConstantAllowed() {
    Type type = new TypeToken<EnumValue<SelfDuplicateAlias>>() {}.getType();
    EnumValue<SelfDuplicateAlias> value = gson.fromJson("\"a\"", type);
    assertThat(value.getConstant()).isEqualTo(SelfDuplicateAlias.A);
    assertThat(gson.toJson(value, type)).isEqualTo("\"a\"");
  }

  @Test
  public void testUserEnumToStringIsNeverCalled() {
    Type type = new TypeToken<EnumValue<WeirdToString>>() {}.getType();
    EnumValue<WeirdToString> value = gson.fromJson("\"FIRST\"", type);
    assertThat(value.getConstant()).isEqualTo(WeirdToString.FIRST);
    assertThat(gson.toJson(value, type)).isEqualTo("\"FIRST\"");
    assertThat(gson.toJson(EnumValue.unknown("future"), type)).isEqualTo("\"future\"");
    // toString of the wrapper uses Enum.name(), not the enum's toString
    assertThat(value.toString()).isEqualTo("EnumValue{FIRST}");
  }

  @Test
  public void testWrapperRecognizedThroughGenericHolder() {
    Type holderType = new TypeToken<Holder<EnumValue<Color>>>() {}.getType();
    Holder<EnumValue<Color>> holder = gson.fromJson("{\"value\":\"future\"}", holderType);
    assertThat(holder.value).isEqualTo(EnumValue.unknown("future"));
    assertThat(gson.toJson(holder, holderType)).isEqualTo("{\"value\":\"future\"}");
  }

  @Test
  public void testPlainEnumsKeepDefaultBehavior() {
    // No EnumValue involved: unknown strings still map to null with default Gson semantics
    Gson plain = new Gson();
    assertThat(plain.fromJson("\"not-a-color\"", Color.class)).isNull();
    assertThat(plain.toJson(Color.RED)).isEqualTo("\"red\"");

    // Also on the configured Gson instance, the plain enum behavior is unchanged
    assertThat(gson.fromJson("\"not-a-color\"", Color.class)).isNull();
    assertThat(gson.toJson(Color.RED)).isEqualTo("\"red\"");
  }

  @Test
  public void testWrapperValueSemantics() {
    assertThat(EnumValue.of(Color.RED)).isEqualTo(EnumValue.of(Color.RED));
    assertThat(EnumValue.of(Color.RED).hashCode()).isEqualTo(EnumValue.of(Color.RED).hashCode());
    assertThat(EnumValue.of(Color.RED)).isNotEqualTo(EnumValue.of(Color.GREEN));
    assertThat(EnumValue.unknown("x")).isEqualTo(EnumValue.unknown("x"));
    assertThat(EnumValue.unknown("x")).isNotEqualTo(EnumValue.unknown("y"));
    assertThat(EnumValue.ofNull()).isEqualTo(EnumValue.ofNull());
    assertThat(EnumValue.ofNull()).isNotEqualTo(EnumValue.unknown("null"));
    assertThat(Arrays.asList(EnumValue.of(Color.RED), null).contains(EnumValue.of(Color.RED)))
        .isTrue();
  }

  @Test
  public void testNullJavaReferenceSerializesAsNull() {
    assertThat(gson.toJson(null, enumValueType())).isEqualTo("null");
    List<EnumValue<Color>> list = new ArrayList<>();
    list.add(null);
    Type listType = new TypeToken<List<EnumValue<Color>>>() {}.getType();
    assertThat(gson.toJson(list, listType)).isEqualTo("[null]");
  }

  @Test
  public void testRawEnumValueTypeRejected() {
    assertThrows(IllegalArgumentException.class, () -> gson.getAdapter(EnumValue.class));
  }

  @Test
  public void testNonEnumTypeArgumentRejected() {
    // Simulates an illegally parameterized type at runtime (source code cannot express
    // EnumValue<String> because of the E extends Enum<E> bound).
    Type stringWrapper =
        new ParameterizedType() {
          @Override
          public Type[] getActualTypeArguments() {
            return new Type[] {String.class};
          }

          @Override
          public Type getRawType() {
            return EnumValue.class;
          }

          @Override
          public Type getOwnerType() {
            return null;
          }

          @Override
          public String toString() {
            return "com.google.gson.enums.EnumValue<java.lang.String>";
          }
        };
    assertThrows(IllegalArgumentException.class, () -> gson.fromJson("\"x\"", stringWrapper));
  }

  @Test
  public void testWildcardResolvesToEnumBoundAndIsRejected() {
    // EnumValue<?> resolves its upper bound to java.lang.Enum, which is not a concrete enum.
    Type wildcardWrapper = new TypeToken<EnumValue<?>>() {}.getType();
    assertThrows(IllegalArgumentException.class, () -> gson.fromJson("\"x\"", wildcardWrapper));
  }

  private static Type enumValueType() {
    return new TypeToken<EnumValue<Color>>() {}.getType();
  }

  private EnumValue<Color> read(String json) {
    return gson.fromJson(json, enumValueType());
  }
}
