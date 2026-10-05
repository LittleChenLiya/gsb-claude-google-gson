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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.Test;

/**
 * Targeted tests for {@link FixedPointTypeAdapter}.
 *
 * <p>Run only this class with:
 *
 * <pre>{@code
 *   mvn -pl gson test -Dtest=FixedPointTypeAdapterTest
 * }</pre>
 *
 * <p>Protocol examples covered here (registration: scale=2, HALF_EVEN, null rejected):
 *
 * <pre>{@code
 *   {"amount":"0.10"}   -> amount == 10L
 *   {"amount":1.005}    -> amount == 100L   (half-even to 1.00)
 *   {"amount":"1.005"}  -> JsonSyntaxException when UNNECESSARY is registered
 *   {"amount":null}     -> JsonSyntaxException (null not allowed by registration)
 *   {"amount":"1e30"}   -> JsonSyntaxException (out of long range, never truncated)
 *   amount = 10L        -> {"amount":0.10}
 * }</pre>
 */
public class FixedPointTypeAdapterTest {
  private static Gson gson(int scale, RoundingMode mode, boolean allowNull) {
    return new GsonBuilder()
        .registerTypeAdapterFactory(FixedPointTypeAdapter.factory(scale, mode, allowNull))
        .create();
  }

  private static final Gson HALF_EVEN_2 = gson(2, RoundingMode.HALF_EVEN, false);
  private static final Gson REJECT_2 = gson(2, RoundingMode.UNNECESSARY, false);

  @Test
  public void stringLexeme_scale2_exact() {
    assertThat(HALF_EVEN_2.fromJson("\"0.10\"", long.class)).isEqualTo(10L);
    assertThat(HALF_EVEN_2.fromJson("\"0.10\"", Long.class)).isEqualTo(10L);
    assertThat(HALF_EVEN_2.fromJson("\"-1.23\"", long.class)).isEqualTo(-123L);
    assertThat(HALF_EVEN_2.fromJson("\"1.00\"", long.class)).isEqualTo(100L);
    assertThat(HALF_EVEN_2.fromJson("\"0\"", long.class)).isEqualTo(0L);
  }

  @Test
  public void numberLexeme_scale2_exact() {
    assertThat(HALF_EVEN_2.fromJson("0.10", long.class)).isEqualTo(10L);
    assertThat(HALF_EVEN_2.fromJson("123", long.class)).isEqualTo(12300L);
    assertThat(HALF_EVEN_2.fromJson("-0.05", long.class)).isEqualTo(-5L);
  }

  @Test
  public void halfEven_rounding() {
    // 1.005 is exactly halfway between 1.00 and 1.01; half-even picks the even last digit.
    assertThat(HALF_EVEN_2.fromJson("\"1.005\"", long.class)).isEqualTo(100L);
    assertThat(HALF_EVEN_2.fromJson("1.005", long.class)).isEqualTo(100L);
    // 1.015 -> 1.02 (last kept digit 1 is odd -> rounds up).
    assertThat(HALF_EVEN_2.fromJson("\"1.015\"", long.class)).isEqualTo(102L);
    // More than half / less than half.
    assertThat(HALF_EVEN_2.fromJson("\"1.006\"", long.class)).isEqualTo(101L);
    assertThat(HALF_EVEN_2.fromJson("\"1.004\"", long.class)).isEqualTo(100L);
    // Negative halfway: -1.005 -> -1.00 (even).
    assertThat(HALF_EVEN_2.fromJson("\"-1.005\"", long.class)).isEqualTo(-100L);
    // Half-even at scale 0: 2.5 -> 2, 3.5 -> 4.
    Gson scale0 = gson(0, RoundingMode.HALF_EVEN, false);
    assertThat(scale0.fromJson("\"2.5\"", long.class)).isEqualTo(2L);
    assertThat(scale0.fromJson("\"3.5\"", long.class)).isEqualTo(4L);
  }

  @Test
  public void unnecessaryMode_rejectsExcessPrecision() {
    JsonSyntaxException e =
        assertThrows(JsonSyntaxException.class, () -> REJECT_2.fromJson("\"1.005\"", long.class));
    assertThat(e).hasMessageThat().contains("'1.005'");
    assertThat(e).hasMessageThat().contains("scale 2");
    // Exact representations are still accepted, including zero with extra zeros.
    assertThat(REJECT_2.fromJson("\"1.00\"", long.class)).isEqualTo(100L);
    assertThat(REJECT_2.fromJson("\"0.000\"", long.class)).isEqualTo(0L);
    assertThat(REJECT_2.fromJson("\"1.0050e2\"", long.class)).isEqualTo(10050L);
  }

  @Test
  public void outOfRange_isRejectedNotTruncated() {
    Gson scale0 = gson(0, RoundingMode.HALF_EVEN, false);
    assertThat(scale0.fromJson("\"9223372036854775807\"", long.class))
        .isEqualTo(Long.MAX_VALUE);
    assertThat(scale0.fromJson("\"-9223372036854775808\"", long.class))
        .isEqualTo(Long.MIN_VALUE);
    assertThrows(
        JsonSyntaxException.class, () -> scale0.fromJson("\"9223372036854775808\"", long.class));
    assertThrows(
        JsonSyntaxException.class, () -> scale0.fromJson("\"-9223372036854775809\"", long.class));
    // Huge exponents are rejected by the pre-check, without truncation and without
    // unbounded computation.
    assertThrows(JsonSyntaxException.class, () -> scale0.fromJson("\"1e19\"", long.class));
    assertThrows(JsonSyntaxException.class, () -> scale0.fromJson("\"1e100\"", long.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> scale0.fromJson("\"1e999999999999999999999\"", long.class));
    // Scale multiplies the magnitude: Long.MAX_VALUE at scale 2 does not fit.
    assertThrows(
        JsonSyntaxException.class,
        () -> HALF_EVEN_2.fromJson("\"922337203685477580.70\"", long.class));
    // Rounding up across the boundary is also out of range.
    Gson halfUp0 = gson(0, RoundingMode.HALF_UP, false);
    assertThrows(
        JsonSyntaxException.class,
        () -> halfUp0.fromJson("\"9223372036854775807.5\"", long.class));
  }

  @Test
  public void tinyValues_roundToZeroOrReject() {
    assertThat(HALF_EVEN_2.fromJson("\"1e-100\"", long.class)).isEqualTo(0L);
    assertThat(HALF_EVEN_2.fromJson("\"0.004\"", long.class)).isEqualTo(0L);
    // Half-even at exactly half a unit: 0 is even, so 0.005 -> 0.00.
    assertThat(HALF_EVEN_2.fromJson("\"0.005\"", long.class)).isEqualTo(0L);
    assertThat(HALF_EVEN_2.fromJson("\"0.015\"", long.class)).isEqualTo(2L);
    // Reject mode: a tiny non-zero value is precision loss, not zero.
    assertThrows(JsonSyntaxException.class, () -> REJECT_2.fromJson("\"1e-100\"", long.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> REJECT_2.fromJson("\"1e-999999999999999999999\"", long.class));
  }

  @Test
  public void exponentLexemes() {
    assertThat(HALF_EVEN_2.fromJson("\"1.5e1\"", long.class)).isEqualTo(1500L);
    assertThat(HALF_EVEN_2.fromJson("\"25e-1\"", long.class)).isEqualTo(250L);
    assertThat(HALF_EVEN_2.fromJson("\"1E2\"", long.class)).isEqualTo(10000L);
    assertThat(HALF_EVEN_2.fromJson("\"-2.5e+1\"", long.class)).isEqualTo(-2500L);
  }

  @Test
  public void invalidLexemes_areRejected() {
    String[] invalid = {
      "\"\"", "\"abc\"", "\"1.2.3\"", "\"+1\"", "\"01\"", "\".5\"", "\"1.\"", "\"1e\"",
      "\"0x10\"", "\"NaN\"", "\"Infinity\"", "\"1,000\"", "\" 1\"", "\"1 \"", "\"--1\""
    };
    for (String json : invalid) {
      assertThrows(json, JsonSyntaxException.class, () -> HALF_EVEN_2.fromJson(json, long.class));
    }
  }

  @Test
  public void nonStringOrNumberTokens_areRejected() {
    assertThrows(JsonSyntaxException.class, () -> HALF_EVEN_2.fromJson("[1]", long.class));
    assertThrows(JsonSyntaxException.class, () -> HALF_EVEN_2.fromJson("true", long.class));
    assertThrows(JsonSyntaxException.class, () -> HALF_EVEN_2.fromJson("{\"a\":1}", long.class));
  }

  @Test
  public void nullHandling_isDecidedByRegistration() {
    // null rejected by registration.
    assertThrows(JsonSyntaxException.class, () -> HALF_EVEN_2.fromJson("null", Long.class));
    assertThrows(JsonIOException.class, () -> HALF_EVEN_2.toJson(null, Long.class));
    // null allowed by registration.
    Gson allowNull = gson(2, RoundingMode.HALF_EVEN, true);
    assertThat(allowNull.fromJson("null", Long.class)).isNull();
    assertThat(allowNull.toJson(null, Long.class)).isEqualTo("null");
  }

  @Test
  public void write_unifiedPlainDecimalNumber() {
    assertThat(HALF_EVEN_2.toJson(10L, long.class)).isEqualTo("0.10");
    assertThat(HALF_EVEN_2.toJson(100L, Long.class)).isEqualTo("1.00");
    assertThat(HALF_EVEN_2.toJson(-5L, long.class)).isEqualTo("-0.05");
    assertThat(HALF_EVEN_2.toJson(0L, long.class)).isEqualTo("0.00");
    assertThat(gson(0, RoundingMode.HALF_EVEN, false).toJson(10L, long.class)).isEqualTo("10");
    // Unified plain string even for tiny scales (no scientific notation).
    assertThat(gson(8, RoundingMode.HALF_EVEN, false).toJson(1L, long.class))
        .isEqualTo("0.00000001");
    // Round trip.
    assertThat(HALF_EVEN_2.fromJson(HALF_EVEN_2.toJson(10L, long.class), long.class))
        .isEqualTo(10L);
  }

  /** Protocol fixture: a record-like bean with a fixed-point {@code long} field. */
  static class Wallet {
    long amount;
    String secret;
  }

  @Test
  public void protocol_objectField() {
    Wallet w = HALF_EVEN_2.fromJson("{\"amount\":\"0.10\",\"secret\":\"s3cr3t\"}", Wallet.class);
    assertThat(w.amount).isEqualTo(10L);
    Wallet w2 = HALF_EVEN_2.fromJson("{\"amount\":1.005}", Wallet.class);
    assertThat(w2.amount).isEqualTo(100L);
    // Serialization writes the unified plain decimal number.
    Wallet out = new Wallet();
    out.amount = 10L;
    assertThat(HALF_EVEN_2.toJson(out)).isEqualTo("{\"amount\":0.10}");
  }

  @Test
  public void errorMessage_usesLexemeAndPath_butLeaksNoOtherFields() {
    JsonSyntaxException e =
        assertThrows(
            JsonSyntaxException.class,
            () -> REJECT_2.fromJson("{\"secret\":\"s3cr3t\",\"amount\":\"1.005\"}", Wallet.class));
    // The offending lexeme and its location are reported...
    assertThat(e).hasMessageThat().contains("'1.005'");
    assertThat(e).hasMessageThat().contains("$.amount");
    // ...but no other field of the document leaks into the error.
    assertThat(e).hasMessageThat().doesNotContain("s3cr3t");
    assertThat(e).hasMessageThat().doesNotContain("secret");
  }

  @Test
  public void defaultBigDecimalAdapter_isUnaffected() {
    Gson plain = new Gson();
    assertThat(plain.fromJson("1.005", BigDecimal.class)).isEqualTo(new BigDecimal("1.005"));
    assertThat(plain.toJson(new BigDecimal("0.10"))).isEqualTo("0.10");
    // Registering the fixed-point factory does not change BigDecimal handling either.
    assertThat(HALF_EVEN_2.fromJson("1.005", BigDecimal.class))
        .isEqualTo(new BigDecimal("1.005"));
  }

  @Test
  public void constructorValidation() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new FixedPointTypeAdapter(-1, RoundingMode.HALF_EVEN, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FixedPointTypeAdapter(19, RoundingMode.HALF_EVEN, false));
    assertThrows(NullPointerException.class, () -> new FixedPointTypeAdapter(2, null, false));
  }
}
