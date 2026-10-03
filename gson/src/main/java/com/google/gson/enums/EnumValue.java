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

import com.google.gson.annotations.SerializedName;
import java.lang.reflect.Field;
import java.util.Objects;

/**
 * Wrapper around an enum value which distinguishes the three situations JSON can express for an
 * enum property:
 *
 * <ol>
 *   <li>a known enum constant ({@link #isKnown()}),
 *   <li>a JSON string which does not match any enum constant (an "unknown" / forward-compatible
 *       value, {@link #isUnknown()}), the original string is preserved,
 *   <li>an explicit JSON {@code null} ({@link #isNull()}).
 * </ol>
 *
 * <p>This wrapper has to be used together with {@link EnumValueTypeAdapterFactory}, which has to be
 * registered explicitly on the {@link com.google.gson.GsonBuilder}. Regular (non-wrapped) enum
 * fields keep Gson's default behavior.
 *
 * <p>Instances of this class are immutable and value-based; {@code equals}, {@code hashCode} and
 * {@code toString} never invoke a user-defined {@code toString()} implementation of the wrapped
 * enum.
 *
 * @param <E> the enum type
 */
public final class EnumValue<E extends Enum<E>> {
  // Exactly one of the two fields is non-null for the known and unknown states; both are null for
  // the explicit JSON null state.
  private final E constant;
  private final String rawValue;

  private EnumValue(E constant, String rawValue) {
    this.constant = constant;
    this.rawValue = rawValue;
  }

  /** Wraps a known enum constant. */
  public static <E extends Enum<E>> EnumValue<E> of(E constant) {
    Objects.requireNonNull(constant);
    return new EnumValue<>(constant, null);
  }

  /**
   * Wraps an unknown string value, i.e. a JSON string which did not match any enum constant. The
   * provided string is preserved verbatim.
   */
  public static <E extends Enum<E>> EnumValue<E> unknown(String rawValue) {
    Objects.requireNonNull(rawValue);
    return new EnumValue<>(null, rawValue);
  }

  /** Represents an explicit JSON {@code null}. */
  public static <E extends Enum<E>> EnumValue<E> ofNull() {
    return new EnumValue<>(null, null);
  }

  /** Returns the known enum constant, or {@code null} if this is not a known value. */
  public E getConstant() {
    return constant;
  }

  /**
   * Returns the JSON string representation of this value: the preserved original string for unknown
   * values, and the canonical serialized name for known values (the {@link
   * com.google.gson.annotations.SerializedName#value()} if present, otherwise the enum constant
   * name). Returns {@code null} for the explicit JSON {@code null} state.
   */
  public String getRawValue() {
    if (constant != null) {
      // Enum constants with a constant-specific body are instances of an anonymous subclass of the
      // enum class; the SerializedName annotation is declared on the enum class field.
      Class<?> enumClass = constant.getDeclaringClass();
      try {
        Field field = enumClass.getDeclaredField(constant.name());
        SerializedName annotation = field.getAnnotation(SerializedName.class);
        if (annotation != null) {
          return annotation.value();
        }
      } catch (NoSuchFieldException e) {
        throw new AssertionError(e);
      }
      return constant.name();
    }
    return rawValue;
  }

  /** Returns whether this wraps a known enum constant. */
  public boolean isKnown() {
    return constant != null;
  }

  /** Returns whether this wraps an unknown string, i.e. a JSON string matching no enum constant. */
  public boolean isUnknown() {
    return rawValue != null && constant == null;
  }

  /** Returns whether this represents an explicit JSON {@code null}. */
  public boolean isNull() {
    return constant == null && rawValue == null;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof EnumValue)) {
      return false;
    }
    EnumValue<?> other = (EnumValue<?>) obj;
    // Compare enum constants by identity/equals and raw strings literally; never delegates to a
    // user-defined enum toString().
    return Objects.equals(constant, other.constant) && Objects.equals(rawValue, other.rawValue);
  }

  @Override
  public int hashCode() {
    return Objects.hash(constant, rawValue);
  }

  /**
   * Returns a debug representation which never invokes a user-defined {@code toString()} of the
   * wrapped enum constant; the constant is rendered by its {@link Enum#name()}.
   */
  @Override
  public String toString() {
    if (constant != null) {
      return "EnumValue{" + constant.name() + "}";
    }
    if (rawValue != null) {
      return "EnumValue{unknown=" + rawValue + "}";
    }
    return "EnumValue{null}";
  }
}
