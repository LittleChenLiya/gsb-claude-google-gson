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

package com.google.gson.functional;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.internal.bind.FixedDecimalTypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.math.RoundingMode;
import org.junit.Test;

/**
 * Tests for {@link FixedDecimalTypeAdapter}.
 *
 * <h2>Protocol examples</h2>
 *
 * <pre>{@code
 * // Registration: scale=2, half-even rounding, nullable
 * Gson gson = new GsonBuilder()
 *     .registerTypeAdapter(
 *         BigInteger.class, new FixedDecimalTypeAdapter(2, RoundingMode.HALF_EVEN, true))
 *     .create();
 *
 * // Read (JSON string or number literal -> integer units):
 * //   "0.10"  -> 10
 * //   0.10    -> 10
 * //   "1.005" -> 100 (half-even)
 * // Write (integer units -> unified numeric string):
 * //   10      -> "0.10"
 * }</pre>
 */
public class FixedDecimalTypeAdapterTest {

  private static Gson gson(FixedDecimalTypeAdapter adapter) {
    return new GsonBuilder().registerTypeAdapter(BigInteger.class, adapter).create();
  }

  // ===== Acceptance: scale=2, 0.10 -> 10 =====

  @Test
  public void testScale2Exact() {
    Gson gson = gson(new FixedDecimalTypeAdapter(2, RoundingMode.UNNECESSARY, true));

    // JSON string input
    assertThat(gson.fromJson("\"0.10\"", BigInteger.class)).isEqualTo(BigInteger.TEN);
    // JSON number literal input
    assertThat(gson.fromJson("0.10", BigInteger.class)).isEqualTo(BigInteger.TEN);
    assertThat(gson.fromJson("0.1", BigInteger.class)).isEqualTo(BigInteger.TEN);
    assertThat(gson.fromJson("\"0.1\"", BigInteger.class)).isEqualTo(BigInteger.TEN);
    // integer values
    assertThat(gson.fromJson("100", BigInteger.class)).isEqualTo(new BigInteger("10000"));
    assertThat(gson.fromJson("\"100\"", BigInteger.class)).isEqualTo(new BigInteger("10000"));
    // zero
    assertThat(gson.fromJson("0", BigInteger.class)).isEqualTo(BigInteger.ZERO);
    assertThat(gson.fromJson("0.00", BigInteger.class)).isEqualTo(BigInteger.ZERO);
    // negative
    assertThat(gson.fromJson("-0.10", BigInteger.class)).isEqualTo(new BigInteger("-10"));
  }

  // ===== Acceptance: 1.005 in reject mode fails, half-even -> 100 =====

  @Test
  public void testRejectModeRejectsRounding() {
    Gson gson = gson(new FixedDecimalTypeAdapter(2, RoundingMode.UNNECESSARY, true));

    JsonSyntaxException e =
        assertThrows(JsonSyntaxException.class, () -> gson.fromJson("\"1.005\"", BigInteger.class));
    assertThat(e).hasMessageThat().contains("1.005");
    assertThat(e).hasCauseThat().isInstanceOf(ArithmeticException.class);
  }

  @Test
  public void testHalfEvenMode() {
    Gson gson = gson(new FixedDecimalTypeAdapter(2, RoundingMode.HALF_EVEN, true));

    // 1.005 * 100 = 100.5 -> half-even rounds to 100
    assertThat(gson.fromJson("\"1.005\"", BigInteger.class)).isEqualTo(new BigInteger("100"));
    assertThat(gson.fromJson("1.005", BigInteger.class)).isEqualTo(new BigInteger("100"));
    // 0.015 * 100 = 1.5 -> half-even rounds to 2
    assertThat(gson.fromJson("\"0.015\"", BigInteger.class)).isEqualTo(new BigInteger("2"));
    // exact values still work
    assertThat(gson.fromJson("\"0.10\"", BigInteger.class)).isEqualTo(BigInteger.TEN);
  }

  @Test
  public void testOtherRoundingModes() {
    // 1.005 * 100 = 100.5
    assertThat(gson(new FixedDecimalTypeAdapter(2, RoundingMode.HALF_UP, true))
            .fromJson("\"1.005\"", BigInteger.class))
        .isEqualTo(new BigInteger("101"));
    assertThat(gson(new FixedDecimalTypeAdapter(2, RoundingMode.HALF_DOWN, true))
            .fromJson("\"1.005\"", BigInteger.class))
        .isEqualTo(new BigInteger("100"));
    assertThat(gson(new FixedDecimalTypeAdapter(2, RoundingMode.CEILING, true))
            .fromJson("\"1.005\"", BigInteger.class))
        .isEqualTo(new BigInteger("101"));
    assertThat(gson(new FixedDecimalTypeAdapter(2, RoundingMode.FLOOR, true))
            .fromJson("\"1.005\"", BigInteger.class))
        .isEqualTo(new BigInteger("100"));
    assertThat(gson(new FixedDecimalTypeAdapter(2, RoundingMode.DOWN, true))
            .fromJson("\"1.005\"", BigInteger.class))
        .isEqualTo(new BigInteger("100"));
    assertThat(gson(new FixedDecimalTypeAdapter(2, RoundingMode.UP, true))
            .fromJson("\"1.005\"", BigInteger.class))
        .isEqualTo(new BigInteger("101"));
  }

  // ===== Acceptance: beyond long range, not truncated =====

  @Test
  public void testBeyondLongRange() {
    Gson gson = gson(new FixedDecimalTypeAdapter(2, RoundingMode.UNNECESSARY, true));

    // Long.MAX_VALUE is 9223372036854775807 (19 digits). This has 30 digits -> beyond long.
    String huge = "123456789012345678901234567890";
    BigInteger expected = new BigInteger(huge).multiply(BigInteger.TEN).multiply(BigInteger.TEN);
    assertThat(gson.fromJson(huge, BigInteger.class)).isEqualTo(expected);
    assertThat(gson.fromJson("\"" + huge + "\"", BigInteger.class)).isEqualTo(expected);

    // Also verify the adapter does not truncate to long.
    BigInteger units = gson.fromJson(huge, BigInteger.class);
    assertThat(units.toString()).hasLength(32);
    assertThat(units.longValue()).isNotEqualTo(units);
  }

  // ===== Write: unified numeric string =====

  @Test
  public void testWriteUnifiedNumericString() {
    Gson gson = gson(new FixedDecimalTypeAdapter(2, RoundingMode.HALF_EVEN, true));

    assertThat(gson.toJson(BigInteger.TEN, BigInteger.class)).isEqualTo("\"0.10\"");
    assertThat(gson.toJson(new BigInteger("100"), BigInteger.class)).isEqualTo("\"1.00\"");
    assertThat(gson.toJson(new BigInteger("12345"), BigInteger.class)).isEqualTo("\"123.45\"");
    assertThat(gson.toJson(BigInteger.ZERO, BigInteger.class)).isEqualTo("\"0.00\"");
    assertThat(gson.toJson(new BigInteger("-10"), BigInteger.class)).isEqualTo("\"-0.10\"");
    // beyond long range
    assertThat(gson.toJson(new BigInteger("12345678901234567890123456789000"), BigInteger.class))
        .isEqualTo("\"123456789012345678901234567890.00\"");
  }

  @Test
  public void testWriteScale0() {
    Gson gson = gson(new FixedDecimalTypeAdapter(0, RoundingMode.UNNECESSARY, true));
    assertThat(gson.toJson(new BigInteger("12345"), BigInteger.class)).isEqualTo("\"12345\"");
    assertThat(gson.toJson(BigInteger.ZERO, BigInteger.class)).isEqualTo("\"0\"");
  }

  @Test
  public void testWriteScale8() {
    Gson gson = gson(new FixedDecimalTypeAdapter(8, RoundingMode.HALF_EVEN, true));
    assertThat(gson.toJson(new BigInteger("1234567890"), BigInteger.class))
        .isEqualTo("\"12.34567890\"");
    assertThat(gson.toJson(new BigInteger("1"), BigInteger.class)).isEqualTo("\"0.00000001\"");
  }

  // ===== Null handling by registration =====

  @Test
  public void testNullableAcceptsNull() {
    Gson gson = gson(new FixedDecimalTypeAdapter(2, RoundingMode.HALF_EVEN, true));
    assertThat(gson.fromJson("null", BigInteger.class)).isNull();
    assertThat(gson.toJson(null, BigInteger.class)).isEqualTo("null");
  }

  @Test
  public void testNonNullRejectsNull() {
    Gson gson = gson(new FixedDecimalTypeAdapter(2, RoundingMode.HALF_EVEN, false));
    JsonSyntaxException e =
        assertThrows(JsonSyntaxException.class, () -> gson.fromJson("null", BigInteger.class));
    assertThat(e).hasMessageThat().contains("null is not allowed");
  }

  // ===== Input type rejection =====

  @Test
  public void testRejectsNonStringNonNumber() {
    Gson gson = gson(new FixedDecimalTypeAdapter(2, RoundingMode.HALF_EVEN, true));

    assertThrows(JsonSyntaxException.class, () -> gson.fromJson("true", BigInteger.class));
    assertThrows(JsonSyntaxException.class, () -> gson.fromJson("false", BigInteger.class));
    assertThrows(JsonSyntaxException.class, () -> gson.fromJson("{}", BigInteger.class));
    assertThrows(JsonSyntaxException.class, () -> gson.fromJson("[]", BigInteger.class));
  }

  // ===== Error message uses lexical, not other fields =====

  /** A domain type with a fixed-point decimal field, used to test error positioning. */
  static class Order {
    public BigInteger amount;
    public String secret;
  }

  @Test
  public void testErrorMessageUsesLexicalNotOtherFields() {
    Gson gson = gson(new FixedDecimalTypeAdapter(2, RoundingMode.UNNECESSARY, true));

    // The JSON object has other fields; the error must reference the number's own lexical
    // (1.005), not leak the other field values (s3cret, nested).
    String json = "{\"amount\": 1.005, \"secret\": \"s3cret\", \"nested\": {\"x\": 1}}";
    JsonSyntaxException e =
        assertThrows(JsonSyntaxException.class, () -> gson.fromJson(json, Order.class));
    String message = e.getMessage();
    assertThat(message).contains("1.005");
    assertThat(message).doesNotContain("s3cret");
    assertThat(message).doesNotContain("nested");
  }

  @Test
  public void testInvalidLexical() {
    Gson gson = gson(new FixedDecimalTypeAdapter(2, RoundingMode.HALF_EVEN, true));
    JsonSyntaxException e =
        assertThrows(
            JsonSyntaxException.class, () -> gson.fromJson("\"not-a-number\"", BigInteger.class));
    assertThat(e).hasMessageThat().contains("not-a-number");
  }

  // ===== Exponent and range checks =====

  @Test
  public void testExponentOutOfRange() {
    Gson gson = gson(new FixedDecimalTypeAdapter(2, RoundingMode.HALF_EVEN, true));

    // Huge positive exponent
    assertThrows(JsonSyntaxException.class, () -> gson.fromJson("1E100001", BigInteger.class));
    // Huge negative exponent
    assertThrows(JsonSyntaxException.class, () -> gson.fromJson("1E-100001", BigInteger.class));
  }

  @Test
  public void testTooManyDigits() {
    Gson gson = gson(new FixedDecimalTypeAdapter(2, RoundingMode.HALF_EVEN, true));

    String manyDigits = "1".repeat(10001);
    assertThrows(JsonSyntaxException.class, () -> gson.fromJson(manyDigits, BigInteger.class));
  }

  @Test
  public void testExponentWithinRange() {
    Gson gson = gson(new FixedDecimalTypeAdapter(2, RoundingMode.HALF_EVEN, true));

    // 1E5 = 100000 -> * 100 = 10000000
    assertThat(gson.fromJson("1E5", BigInteger.class)).isEqualTo(new BigInteger("10000000"));
    // 1E-2 = 0.01 -> * 100 = 1
    assertThat(gson.fromJson("1E-2", BigInteger.class)).isEqualTo(BigInteger.ONE);
  }

  // ===== Round-trip =====

  @Test
  public void testRoundTrip() {
    Gson gson = gson(new FixedDecimalTypeAdapter(2, RoundingMode.HALF_EVEN, true));

    String[] inputs = {"0.10", "1.00", "123.45", "0.00", "-0.10", "1000000.00"};
    for (String input : inputs) {
      BigInteger units = gson.fromJson("\"" + input + "\"", BigInteger.class);
      String written = gson.toJson(units, BigInteger.class);
      assertThat(written).isEqualTo("\"" + input + "\"");
    }
  }

  // ===== Direct adapter usage =====

  @Test
  public void testDirectAdapterUsage() throws IOException {
    FixedDecimalTypeAdapter adapter = new FixedDecimalTypeAdapter(2, RoundingMode.HALF_EVEN, true);

    // Read
    JsonReader reader = new JsonReader(new StringReader("\"0.10\""));
    assertThat(adapter.read(reader)).isEqualTo(BigInteger.TEN);

    // Write
    StringWriter sw = new StringWriter();
    adapter.write(new JsonWriter(sw), BigInteger.TEN);
    assertThat(sw.toString()).isEqualTo("\"0.10\"");
  }

  @Test
  public void testToPlainStringDirectly() {
    assertThat(FixedDecimalTypeAdapter.toPlainString(BigInteger.TEN, 2)).isEqualTo("0.10");
    assertThat(FixedDecimalTypeAdapter.toPlainString(new BigInteger("100"), 2)).isEqualTo("1.00");
    assertThat(FixedDecimalTypeAdapter.toPlainString(new BigInteger("12345"), 2))
        .isEqualTo("123.45");
    assertThat(FixedDecimalTypeAdapter.toPlainString(BigInteger.ZERO, 2)).isEqualTo("0.00");
    assertThat(FixedDecimalTypeAdapter.toPlainString(new BigInteger("-10"), 2))
        .isEqualTo("-0.10");
    assertThat(FixedDecimalTypeAdapter.toPlainString(new BigInteger("1"), 2)).isEqualTo("0.01");
    assertThat(FixedDecimalTypeAdapter.toPlainString(new BigInteger("1234567890"), 8))
        .isEqualTo("12.34567890");
    assertThat(FixedDecimalTypeAdapter.toPlainString(new BigInteger("12345"), 0))
        .isEqualTo("12345");
  }

  // ===== Accessors and factories =====

  @Test
  public void testAccessors() {
    FixedDecimalTypeAdapter adapter = new FixedDecimalTypeAdapter(2, RoundingMode.HALF_EVEN, true);
    assertThat(adapter.getScale()).isEqualTo(2);
    assertThat(adapter.getRoundingMode()).isEqualTo(RoundingMode.HALF_EVEN);
    assertThat(adapter.isNullable()).isTrue();
    assertThat(adapter.toString()).contains("scale=2");
  }

  @Test
  public void testRejectingFactory() {
    FixedDecimalTypeAdapter adapter = FixedDecimalTypeAdapter.rejecting(2);
    assertThat(adapter.getScale()).isEqualTo(2);
    assertThat(adapter.getRoundingMode()).isEqualTo(RoundingMode.UNNECESSARY);
    assertThat(adapter.isNullable()).isFalse();
  }

  @Test
  public void testNullableFactory() {
    FixedDecimalTypeAdapter adapter = FixedDecimalTypeAdapter.nullable(3, RoundingMode.DOWN);
    assertThat(adapter.getScale()).isEqualTo(3);
    assertThat(adapter.getRoundingMode()).isEqualTo(RoundingMode.DOWN);
    assertThat(adapter.isNullable()).isTrue();
  }

  @Test
  public void testNegativeScaleRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new FixedDecimalTypeAdapter(-1, RoundingMode.HALF_EVEN, true));
  }
}
