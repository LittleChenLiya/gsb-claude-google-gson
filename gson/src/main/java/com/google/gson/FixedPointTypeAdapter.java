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

import com.google.gson.internal.LazilyParsedNumber;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Explicit decimal fixed-point {@link TypeAdapter} for {@code long} / {@link Long} values.
 *
 * <p>The value is transported as an exact decimal and stored as a count of smallest units
 * ("minor units"): a registered {@code scale} of 2 means the JSON decimal {@code 0.10} is
 * represented as the long {@code 10} (ten units of 0.01).
 *
 * <h2>Reading</h2>
 *
 * <ul>
 *   <li>Only JSON <b>string</b> and <b>number</b> lexemes are accepted; any other token
 *       (arrays, objects, booleans, ...) is rejected.
 *   <li>The lexeme must be a strict JSON decimal number
 *       ({@code -?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?}); hex, {@code NaN}, leading zeros,
 *       a leading {@code +} etc. are rejected.
 *   <li>Parsing is exact decimal arithmetic on the lexeme digits; the value is never routed
 *       through {@code double}. The decimal exponent and the {@code long} range are checked
 *       <b>before</b> any scaling, so absurd exponents (e.g. {@code 1e999999999999}) are
 *       rejected immediately instead of being truncated or causing unbounded computation.
 *   <li>The registered {@link RoundingMode} is applied when the lexeme has more fractional
 *       digits than the scale. With {@link RoundingMode#UNNECESSARY} ("reject mode") any
 *       discarded non-zero digit is an explicit error, e.g. {@code 1.005} at scale 2.
 *   <li>Values whose scaled unit count does not fit into a {@code long} are rejected with an
 *       out-of-range error; they are never silently truncated.
 *   <li>Error messages quote the offending lexeme and the JSON path of that token only; no
 *       other part of the document (e.g. sibling fields) is inspected or leaked.
 * </ul>
 *
 * <h2>Writing</h2>
 *
 * <p>Values are always written as a single unified plain decimal JSON number string (no
 * scientific notation, no quotes), e.g. {@code 10} units at scale 2 is written as
 * {@code 0.10}.
 *
 * <h2>null handling</h2>
 *
 * <p>Whether JSON {@code null} (and a Java {@code null} value) is allowed is decided per
 * registration via the {@code allowNull} flag; it is not hard-coded by this adapter.
 *
 * <p>This adapter is fully independent: the built-in {@code BigDecimal} adapter is not
 * modified or replaced.
 *
 * <h2>Protocol example</h2>
 *
 * <p>Registered with {@code FixedPointTypeAdapter.factory(2, RoundingMode.HALF_EVEN, false)}
 * for a field {@code long amount}:
 *
 * <pre>{@code
 *   {"amount":"0.10"}   -> amount == 10L        (string lexeme, exact)
 *   {"amount":1.005}    -> amount == 100L       (number lexeme, half-even to 1.00)
 *   {"amount":"1.005"}  -> JsonSyntaxException  (if RoundingMode.UNNECESSARY was registered)
 *   {"amount":null}     -> JsonSyntaxException  (null not allowed by this registration)
 *   {"amount":"1e30"}   -> JsonSyntaxException  (out of long range, never truncated)
 *   amount = 10L        -> {"amount":0.10}      (unified plain decimal number)
 * }</pre>
 *
 * <h2>Targeted test command</h2>
 *
 * <pre>{@code
 *   mvn -pl gson test -Dtest=FixedPointTypeAdapterTest
 * }</pre>
 */
public final class FixedPointTypeAdapter extends TypeAdapter<Long> {
  /** Strict JSON number grammar (RFC 8259 section 6). */
  private static final Pattern JSON_NUMBER =
      Pattern.compile("-?(0|[1-9][0-9]*)([.][0-9]+)?([eE][+-]?[0-9]+)?");

  /**
   * Number of decimal digits in the largest magnitude a signed 64-bit value can hold
   * ({@code |Long.MIN_VALUE|} = 9223372036854775808 has 19 digits).
   */
  private static final int MAX_LONG_DIGITS = 19;

  /**
   * Clamp for the decimal exponent. Any exponent whose absolute value exceeds this bound is
   * decisively out of range (positive) or decisively rounds toward zero (negative), so the
   * clamped value leads to the same accept/reject decision without big-integer blowups.
   */
  private static final long EXPONENT_CLAMP = 1_000_000_000L;

  private final int scale;
  private final RoundingMode roundingMode;
  private final boolean allowNull;

  public FixedPointTypeAdapter(int scale, RoundingMode roundingMode, boolean allowNull) {
    if (scale < 0 || scale >= MAX_LONG_DIGITS) {
      throw new IllegalArgumentException(
          "scale must be in [0, " + (MAX_LONG_DIGITS - 1) + "]: " + scale);
    }
    this.scale = scale;
    this.roundingMode = Objects.requireNonNull(roundingMode, "roundingMode");
    this.allowNull = allowNull;
  }

  public int scale() {
    return scale;
  }

  public RoundingMode roundingMode() {
    return roundingMode;
  }

  public boolean allowsNull() {
    return allowNull;
  }

  /**
   * Creates a {@link TypeAdapterFactory} registering one fixed-point adapter for both
   * {@link Long} and {@code long}, e.g.
   *
   * <pre>{@code
   * Gson gson = new GsonBuilder()
   *     .registerTypeAdapterFactory(
   *         FixedPointTypeAdapter.factory(2, RoundingMode.HALF_EVEN, false))
   *     .create();
   * }</pre>
   */
  public static TypeAdapterFactory factory(
      int scale, RoundingMode roundingMode, boolean allowNull) {
    FixedPointTypeAdapter adapter = new FixedPointTypeAdapter(scale, roundingMode, allowNull);
    return new TypeAdapterFactory() {
      @Override
      @SuppressWarnings("unchecked")
      public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
        Class<? super T> raw = type.getRawType();
        return (raw == Long.class || raw == long.class) ? (TypeAdapter<T>) adapter : null;
      }
    };
  }

  @Override
  public Long read(JsonReader in) throws IOException {
    JsonToken token = in.peek();
    if (token == JsonToken.NULL) {
      in.nextNull();
      if (!allowNull) {
        throw new JsonSyntaxException(
            "Fixed-point value at path "
                + in.getPreviousPath()
                + " is null, but null is not allowed by this registration");
      }
      return null;
    }
    if (token != JsonToken.STRING && token != JsonToken.NUMBER) {
      throw new JsonSyntaxException(
          "Expected a JSON string or number for fixed-point value but was "
              + token
              + " at path "
              + in.getPath());
    }
    // Consume only this single lexeme; nothing else of the document is read or reported.
    String lexeme = in.nextString();
    String path = in.getPreviousPath();
    try {
      return parse(lexeme);
    } catch (FixedPointException e) {
      throw new JsonSyntaxException(
          "Invalid fixed-point lexeme '" + lexeme + "' at path " + path + ": " + e.getMessage(),
          e);
    }
  }

  @Override
  public void write(JsonWriter out, Long value) throws IOException {
    if (value == null) {
      if (!allowNull) {
        throw new JsonIOException(
            "Fixed-point value is null, but null is not allowed by this registration");
      }
      out.nullValue();
      return;
    }
    // Unified plain decimal number string, e.g. scale=2, units=10 -> "0.10".
    // LazilyParsedNumber makes JsonWriter emit it verbatim as a JSON number and also
    // works for tree writing (JsonTreeWriter / Gson.toJsonTree).
    out.value(new LazilyParsedNumber(BigDecimal.valueOf(value, scale).toPlainString()));
  }

  /**
   * Parses a strict JSON decimal lexeme into minor units using exact decimal arithmetic on
   * the digit sequence. Exponent and range are checked before scaling; no {@code double} and
   * no unbounded big-integer work is involved.
   */
  private long parse(String lexeme) throws FixedPointException {
    if (!JSON_NUMBER.matcher(lexeme).matches()) {
      throw new FixedPointException("not a strict JSON decimal number");
    }

    int i = 0;
    boolean negative = lexeme.charAt(0) == '-';
    if (negative) {
      i = 1;
    }
    int intStart = i;
    while (i < lexeme.length() && isDigit(lexeme.charAt(i))) {
      i++;
    }
    String intPart = lexeme.substring(intStart, i);
    String fracPart = "";
    if (i < lexeme.length() && lexeme.charAt(i) == '.') {
      int fracStart = ++i;
      while (i < lexeme.length() && isDigit(lexeme.charAt(i))) {
        i++;
      }
      fracPart = lexeme.substring(fracStart, i);
    }
    long exponent = 0;
    if (i < lexeme.length()) { // 'e' or 'E'
      exponent = parseExponentClamped(lexeme, i + 1);
    }

    // All significant digits; the decimal point sits after (intLen + exponent) digits.
    String digits = intPart + fracPart;
    long pointPos = intPart.length() + exponent;
    // Strip leading zeros (each one shifts the decimal point one position to the left).
    int leadingZeros = 0;
    while (leadingZeros < digits.length() && digits.charAt(leadingZeros) == '0') {
      leadingZeros++;
    }
    digits = digits.substring(leadingZeros);
    pointPos -= leadingZeros;
    if (digits.isEmpty()) {
      return 0L; // zero is exact at any scale, in any rounding mode
    }

    // units = round(digits * 10^(pointPos + scale - len(digits)))
    // keptDigits = number of integer digits of the unit count before fractional rounding.
    long keptDigits = pointPos + scale;

    // Range pre-check, before any scaling: more than 19 integer digits never fits a long.
    if (keptDigits > MAX_LONG_DIGITS) {
      throw new FixedPointException(
          "scaled value has more than "
              + MAX_LONG_DIGITS
              + " integer digits; exceeds the range of long at scale "
              + scale);
    }

    long shift = keptDigits - digits.length();
    long magnitude;
    if (shift >= 0) {
      // No fractional digits to discard: units = digits * 10^shift (exact, range-checked).
      magnitude = parseMagnitude(digits, negative);
      for (long z = 0; z < shift; z++) {
        try {
          magnitude = Math.multiplyExact(magnitude, 10L);
        } catch (ArithmeticException e) {
          throw outOfRange(e);
        }
      }
    } else {
      int keep = (int) Math.max(keptDigits, 0);
      String kept = digits.substring(0, keep);
      String dropped = digits.substring(keep);
      magnitude = kept.isEmpty() ? 0L : parseMagnitude(kept, negative);
      if (hasNonZeroDigit(dropped)) {
        if (roundingMode == RoundingMode.UNNECESSARY) {
          throw new FixedPointException(
              "has more fractional digits than scale "
                  + scale
                  + " and rounding mode UNNECESSARY forbids rounding");
        }
        if (shouldRoundUp(negative, magnitude, dropped)) {
          if (magnitude == Long.MIN_VALUE) {
            throw outOfRange(); // |Long.MIN_VALUE| + 1 has no long representation
          }
          try {
            magnitude = Math.addExact(magnitude, 1L);
          } catch (ArithmeticException e) {
            throw outOfRange(e);
          }
        }
      }
    }

    if (negative) {
      // magnitude == Long.MIN_VALUE is already the exact representation of -2^63.
      return magnitude == Long.MIN_VALUE ? Long.MIN_VALUE : -magnitude;
    }
    return magnitude;
  }

  /**
   * Parses a digit string of at most 19 digits as a positive magnitude. As a special case,
   * when the value is negative, the magnitude 2^63 is representable and is returned as
   * {@link Long#MIN_VALUE} (its own negation).
   */
  private static long parseMagnitude(String digits, boolean negative)
      throws FixedPointException {
    long value = 0;
    for (int i = 0; i < digits.length(); i++) {
      try {
        value = Math.addExact(Math.multiplyExact(value, 10L), digits.charAt(i) - '0');
      } catch (ArithmeticException e) {
        if (negative && digits.equals("9223372036854775808")) {
          return Long.MIN_VALUE; // exact magnitude of Long.MIN_VALUE
        }
        throw outOfRange(e);
      }
    }
    return value;
  }

  private boolean shouldRoundUp(boolean negative, long kept, String dropped) {
    switch (roundingMode) {
      case UP:
        return true;
      case DOWN:
        return false;
      case CEILING:
        return !negative;
      case FLOOR:
        return negative;
      case HALF_UP:
      case HALF_DOWN:
      case HALF_EVEN:
        break;
      default:
        throw new AssertionError("UNNECESSARY is handled before rounding");
    }
    int cmp = compareToHalf(dropped);
    if (cmp > 0) {
      return true;
    }
    if (cmp < 0) {
      return false;
    }
    // Exactly halfway.
    if (roundingMode == RoundingMode.HALF_UP) {
      return true;
    }
    if (roundingMode == RoundingMode.HALF_DOWN) {
      return false;
    }
    // HALF_EVEN: round up only if the last kept digit is odd (kept == 0 counts as even).
    return kept % 2L != 0L;
  }

  /** Compares the discarded fraction {@code 0.dropped} against one half of a unit. */
  private static int compareToHalf(String dropped) {
    char first = dropped.charAt(0);
    if (first != '5') {
      return first > '5' ? 1 : -1;
    }
    return hasNonZeroDigit(dropped.substring(1)) ? 1 : 0;
  }

  private static boolean hasNonZeroDigit(String s) {
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) != '0') {
        return true;
      }
    }
    return false;
  }

  /** Parses the exponent starting at {@code start} (after 'e'/'E'), clamped to a safe bound. */
  private static long parseExponentClamped(String lexeme, int start) {
    int i = start;
    boolean negative = false;
    if (i < lexeme.length() && (lexeme.charAt(i) == '+' || lexeme.charAt(i) == '-')) {
      negative = lexeme.charAt(i) == '-';
      i++;
    }
    long value = 0;
    for (; i < lexeme.length(); i++) {
      value = Math.min(value * 10 + (lexeme.charAt(i) - '0'), EXPONENT_CLAMP);
    }
    return negative ? -value : value;
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private static FixedPointException outOfRange() {
    return new FixedPointException(
        "scaled value exceeds the range of long; refusing to truncate");
  }

  private static FixedPointException outOfRange(Throwable cause) {
    return new FixedPointException(
        "scaled value exceeds the range of long; refusing to truncate", cause);
  }

  /** Internal parse failure; always wrapped into a {@link JsonSyntaxException} with lexeme+path. */
  private static final class FixedPointException extends Exception {
    private static final long serialVersionUID = 1L;

    FixedPointException(String message) {
      super(message);
    }

    FixedPointException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
