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

import java.util.Objects;

/**
 * An enum value wrapper that preserves JSON strings that do not match any enum constant, instead
 * of silently converting them to {@code null}.
 *
 * <p>An {@code EnumValue} is in one of three states:
 *
 * <ul>
 *   <li><b>Known</b>: the JSON string matched an enum constant. {@link #getValue()} returns the
 *       constant and {@link #getRawValue()} returns {@code null}.
 *   <li><b>Unknown</b>: the JSON string did not match any enum constant. {@link #getValue()}
 *       returns {@code null} and {@link #getRawValue()} returns the original string.
 *   <li><b>Null</b>: the JSON value was {@code null}. Both {@link #getValue()} and {@link
 *       #getRawValue()} return {@code null}, but this state is distinct from the unknown state.
 * </ul>
 *
 * <p>This is useful when a remote API can add new enum values. A plain enum field would silently
 * become {@code null} for an unknown value; an {@code EnumValue} keeps the unknown string so it can
 * be inspected or round-tripped.
 *
 * <p>To use it, register an {@link EnumValueTypeAdapterFactory} with a {@link GsonBuilder}:
 *
 * <pre>{@code
 * Gson gson = new GsonBuilder()
 *     .registerTypeAdapterFactory(new EnumValueTypeAdapterFactory())
 *     .create();
 *
 * EnumValue<Color> color = gson.fromJson("\"crimson\"", new TypeToken<EnumValue<Color>>() {});
 * if (color.isKnown()) {
 *   Color c = color.getValue();
 * } else if (color.isUnknown()) {
 *   String raw = color.getRawValue(); // "crimson"
 * } else {
 *   // JSON null
 * }
 * }</pre>
 *
 * @param <T> the enum type
 * @since 2.14.1
 */
public final class EnumValue<T extends Enum<T>> {
  private final T value;
  private final String rawValue;

  private EnumValue(T value, String rawValue) {
    this.value = value;
    this.rawValue = rawValue;
  }

  /**
   * Creates an {@code EnumValue} for a known enum constant.
   *
   * @param value the enum constant; must not be {@code null}
   * @throws NullPointerException if {@code value} is {@code null}
   */
  public static <T extends Enum<T>> EnumValue<T> of(T value) {
    Objects.requireNonNull(value, "value == null");
    return new EnumValue<>(value, null);
  }

  /**
   * Creates an {@code EnumValue} for an unknown JSON string.
   *
   * @param value the original JSON string; must not be {@code null}
   * @throws NullPointerException if {@code value} is {@code null}
   */
  public static <T extends Enum<T>> EnumValue<T> unknown(String value) {
    Objects.requireNonNull(value, "value == null");
    return new EnumValue<>(null, value);
  }

  /** Creates an {@code EnumValue} representing a JSON {@code null} value. */
  public static <T extends Enum<T>> EnumValue<T> ofNull() {
    return new EnumValue<>(null, null);
  }

  /** Returns {@code true} if this value matched an enum constant. */
  public boolean isKnown() {
    return value != null;
  }

  /** Returns {@code true} if this value was a JSON string that matched no enum constant. */
  public boolean isUnknown() {
    return value == null && rawValue != null;
  }

  /** Returns {@code true} if this value was JSON {@code null}. */
  public boolean isNull() {
    return value == null && rawValue == null;
  }

  /**
   * Returns the enum constant, or {@code null} if this value is unknown or JSON null.
   *
   * @see #isKnown()
   * @see #isUnknown()
   * @see #isNull()
   */
  public T getValue() {
    return value;
  }

  /**
   * Returns the original JSON string for an unknown value, or {@code null} if this value is known
   * or JSON null.
   *
   * @see #isUnknown()
   */
  public String getRawValue() {
    return rawValue;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof EnumValue)) {
      return false;
    }
    EnumValue<?> that = (EnumValue<?>) o;
    return Objects.equals(value, that.value) && Objects.equals(rawValue, that.rawValue);
  }

  @Override
  public int hashCode() {
    return Objects.hash(value, rawValue);
  }

  @Override
  public String toString() {
    if (isKnown()) {
      return "EnumValue[" + value.name() + "]";
    }
    if (isUnknown()) {
      return "EnumValue[unknown:" + rawValue + "]";
    }
    return "EnumValue[null]";
  }
}
