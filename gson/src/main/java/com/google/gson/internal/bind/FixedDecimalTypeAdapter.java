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

import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A type adapter for fixed-point decimal numbers.
 *
 * <p>This adapter converts between JSON strings/numbers and {@link BigInteger} "integer units" of
 * a decimal value with a fixed {@linkplain #getScale() scale}. For example, with scale 2, the JSON
 * value {@code 0.10} is read as {@link BigInteger#TEN} (10 units), and {@link BigInteger#TEN} is
 * written as the unified numeric string {@code "0.10"}.
 *
 * <p>Conversion is exact: the JSON lexical is parsed with {@link BigDecimal} (no {@code double} is
 * used), the exponent and range are checked first, and the value is then scaled to an integer.
 * Values that would require rounding are handled according to the configured {@link RoundingMode};
 * {@link RoundingMode#UNNECESSARY} explicitly rejects any value that would lose precision. Values
 * beyond {@code long} range are kept as {@link BigInteger} and are never truncated.
 *
 * <p>This adapter is <b>explicit</b>: it must be registered for the type that uses fixed-point
 * decimals. The registration determines the scale, rounding mode, and whether {@code null} is
 * accepted. The default {@link BigDecimal} adapter is unaffected.
 *
 * <h2>Protocol example</h2>
 *
 * <pre>{@code
 * // Register for the type that holds fixed-point decimal units:
 * Gson gson = new GsonBuilder()
 *     .registerTypeAdapter(
 *         BigInteger.class, new FixedDecimalTypeAdapter(2, RoundingMode.HALF_EVEN, true))
 *     .create();
 *
 * // Read: JSON string or number literal -> integer units
 * BigInteger units = gson.fromJson("\"0.10\"", BigInteger.class); // 10
 * units = gson.fromJson("0.10", BigInteger.class); // 10
 * units = gson.fromJson("1.005", BigInteger.class); // 100 (half-even)
 *
 * // Write: integer units -> unified numeric string
 * String json = gson.toJson(new BigInteger("10"), BigInteger.class); // "0.10"
 * }</pre>
 */
public final class FixedDecimalTypeAdapter extends TypeAdapter<BigInteger> {
  /** The maximum absolute exponent (scale) accepted from a JSON literal. */
  private static final int MAX_EXPONENT = 100_000;

  /** The maximum number of significant digits accepted from a JSON literal. */
  private static final int MAX_DIGITS = 10_000;

  private final int scale;
  private final RoundingMode roundingMode;
  private final boolean nullable;

  /**
   * Creates a new fixed-point decimal type adapter.
   *
   * @param scale the fixed scale (number of fractional digits); must be non-negative
   * @param roundingMode the rounding strategy used when a value cannot be represented exactly
   * @param nullable whether {@code null} is accepted on read and write
   */
  public FixedDecimalTypeAdapter(int scale, RoundingMode roundingMode, boolean nullable) {
    if (scale < 0) {
      throw new IllegalArgumentException("scale must be non-negative: " + scale);
    }
    this.scale = scale;
    this.roundingMode = Objects.requireNonNull(roundingMode, "roundingMode must not be null");
    this.nullable = nullable;
  }

  /**
   * Creates an adapter that rejects any value requiring rounding (using {@link
   * RoundingMode#UNNECESSARY}) and rejects {@code null}.
   */
  public static FixedDecimalTypeAdapter rejecting(int scale) {
    return new FixedDecimalTypeAdapter(scale, RoundingMode.UNNECESSARY, false);
  }

  /** Creates an adapter with the given rounding mode that accepts {@code null}. */
  public static FixedDecimalTypeAdapter nullable(int scale, RoundingMode roundingMode) {
    return new FixedDecimalTypeAdapter(scale, roundingMode, true);
  }

  /** Returns the scale (number of fractional digits) used by this adapter. */
  public int getScale() {
    return scale;
  }

  /** Returns the rounding mode used by this adapter. */
  public RoundingMode getRoundingMode() {
    return roundingMode;
  }

  /** Returns whether {@code null} is accepted by this adapter. */
  public boolean isNullable() {
    return nullable;
  }

  @Override
  public BigInteger read(JsonReader in) throws IOException {
    JsonToken peek = in.peek();
    if (peek == JsonToken.NULL) {
      in.nextNull();
      if (nullable) {
        return null;
      }
      throw new JsonSyntaxException(
          "null is not allowed for fixed-point decimal (scale="
              + scale
              + ") at path "
              + in.getPreviousPath());
    }
    if (peek != JsonToken.STRING && peek != JsonToken.NUMBER) {
      throw new JsonSyntaxException(
          "Expected a string or number for fixed-point decimal (scale="
              + scale
              + ") but was "
              + peek
              + " at path "
              + in.getPreviousPath());
    }
    String lexical = in.nextString();
    try {
      return parse(lexical);
    } catch (NumberFormatException | ArithmeticException e) {
      throw new JsonSyntaxException(
          "Failed parsing fixed-point decimal '"
              + lexical
              + "' (scale="
              + scale
              + ", rounding="
              + roundingMode
              + ") at path "
              + in.getPreviousPath(),
          e);
    }
  }

  private BigInteger parse(String lexical) {
    BigDecimal decimal = new BigDecimal(lexical);
    // Check exponent and range first, before any conversion that could allocate a huge integer.
    checkExponentAndRange(decimal, lexical);
    // Convert to integer units: value * 10^scale, rounded to an integer.
    BigDecimal scaled = decimal.movePointRight(scale);
    try {
      return scaled.setScale(0, roundingMode).unscaledValue();
    } catch (ArithmeticException e) {
      // RoundingMode.UNNECESSARY and the value has a fractional part -> reject.
      ArithmeticException ex =
          new ArithmeticException(
              "Value '"
                  + lexical
                  + "' cannot be represented as fixed-point decimal with scale "
                  + scale
                  + " without rounding (rounding mode "
                  + roundingMode
                  + ")");
      ex.initCause(e);
      throw ex;
    }
  }

  private void checkExponentAndRange(BigDecimal decimal, String lexical) {
    int decimalScale = decimal.scale();
    if (decimalScale < -MAX_EXPONENT || decimalScale > MAX_EXPONENT) {
      throw new NumberFormatException(
          "Exponent out of range for fixed-point decimal: '"
              + lexical
              + "' (scale exponent "
              + decimalScale
              + ", max "
              + MAX_EXPONENT
              + ")");
    }
    if (decimal.precision() > MAX_DIGITS) {
      throw new NumberFormatException(
          "Number has too many digits for fixed-point decimal: '"
              + lexical
              + "' ("
              + decimal.precision()
              + " digits, max "
              + MAX_DIGITS
              + ")");
    }
  }

  @Override
  public void write(JsonWriter out, BigInteger value) throws IOException {
    if (value == null) {
      if (nullable) {
        out.nullValue();
        return;
      }
      throw new JsonSyntaxException(
          "null is not allowed for fixed-point decimal (scale=" + scale + ")");
    }
    out.value(toPlainString(value, scale));
  }

  /**
   * Returns the unified plain decimal string for the given integer units and scale, without
   * exponent notation.
   */
  public static String toPlainString(BigInteger units, int scale) {
    if (scale == 0) {
      return units.toString();
    }
    boolean negative = units.signum() < 0;
    String digits = units.abs().toString();
    StringBuilder sb = new StringBuilder(digits.length() + scale + 1);
    if (negative) {
      sb.append('-');
    }
    if (digits.length() <= scale) {
      sb.append("0.");
      for (int i = digits.length(); i < scale; i++) {
        sb.append('0');
      }
      sb.append(digits);
    } else {
      int split = digits.length() - scale;
      sb.append(digits, 0, split);
      sb.append('.');
      sb.append(digits, split, digits.length());
    }
    return sb.toString();
  }

  @Override
  public String toString() {
    return "FixedDecimalTypeAdapter(scale="
        + scale
        + ", rounding="
        + roundingMode
        + ", nullable="
        + nullable
        + ")";
  }
}
