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

import com.google.gson.DuplicateMemberPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.google.gson.stream.MalformedJsonException;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.Test;

/** Tests for {@link DuplicateMemberPolicy}. */
@SuppressWarnings("resource")
public class DuplicateMemberPolicyTest {
  private static final String DUPLICATE_JSON = "{\"a\":1,\"a\":2}";

  private static final Gson REJECTING_GSON =
      new GsonBuilder().setDuplicateMemberPolicy(DuplicateMemberPolicy.REJECT).create();

  static class Point {
    int a;
  }

  static class WithAlternates {
    @SerializedName(value = "x", alternate = "y")
    int value;
  }

  private static JsonReader newRejectingReader(String json) {
    JsonReader reader = new JsonReader(new StringReader(json));
    reader.setDuplicateMemberPolicy(DuplicateMemberPolicy.REJECT);
    return reader;
  }

  private static void assertDuplicateRejected(Gson gson, String json, Type type) {
    JsonSyntaxException e =
        assertThrows(JsonSyntaxException.class, () -> gson.fromJson(json, type));
    assertThat(e).hasMessageThat().contains("Duplicate object member \"a\"");
  }

  @Test
  public void testDefaultPolicyIsAllow() {
    JsonReader reader = new JsonReader(new StringReader("{}"));
    assertThat(reader.getDuplicateMemberPolicy()).isEqualTo(DuplicateMemberPolicy.ALLOW);
    assertThat(new Gson().newJsonReader(new StringReader("{}")).getDuplicateMemberPolicy())
        .isEqualTo(DuplicateMemberPolicy.ALLOW);
  }

  @Test
  public void testNullPolicyRejected() {
    JsonReader reader = new JsonReader(new StringReader("{}"));
    assertThrows(NullPointerException.class, () -> reader.setDuplicateMemberPolicy(null));
    GsonBuilder builder = new GsonBuilder();
    assertThrows(NullPointerException.class, () -> builder.setDuplicateMemberPolicy(null));
  }

  /**
   * Without the policy, each target type keeps its historic behavior: most adapters let the last
   * occurrence win, whereas the {@code Map} adapter performs its own duplicate key check.
   */
  @Test
  public void testAllowPreservesExistingBehavior() {
    Gson gson = new Gson();
    assertThat(gson.fromJson(DUPLICATE_JSON, Point.class).a).isEqualTo(2);
    // The Map adapter has its own duplicate key check, independent of this policy
    Type mapType = new TypeToken<Map<String, Integer>>() {}.getType();
    JsonSyntaxException mapException =
        assertThrows(JsonSyntaxException.class, () -> gson.fromJson(DUPLICATE_JSON, mapType));
    assertThat(mapException).hasMessageThat().contains("Duplicate key");
    assertThat(gson.fromJson(DUPLICATE_JSON, Object.class).toString()).isEqualTo("{a=2.0}");
    assertThat(gson.fromJson(DUPLICATE_JSON, JsonElement.class).toString()).isEqualTo("{\"a\":2}");
  }

  /** With the policy, all target types reject the same duplicate input consistently. */
  @Test
  public void testRejectForAllTargetTypes() {
    assertDuplicateRejected(REJECTING_GSON, DUPLICATE_JSON, Point.class);
    assertDuplicateRejected(
        REJECTING_GSON, DUPLICATE_JSON, new TypeToken<Map<String, Integer>>() {}.getType());
    assertDuplicateRejected(REJECTING_GSON, DUPLICATE_JSON, Object.class);
    assertDuplicateRejected(REJECTING_GSON, DUPLICATE_JSON, JsonElement.class);
  }

  @Test
  public void testRejectOnReader() throws IOException {
    JsonReader reader = newRejectingReader(DUPLICATE_JSON);
    reader.beginObject();
    assertThat(reader.nextName()).isEqualTo("a");
    assertThat(reader.nextInt()).isEqualTo(1);
    MalformedJsonException e = assertThrows(MalformedJsonException.class, reader::nextName);
    // Error message contains member name, path and location
    assertThat(e).hasMessageThat().contains("Duplicate object member \"a\"");
    assertThat(e).hasMessageThat().contains("at line 1 column 11");
    assertThat(e).hasMessageThat().contains("path $.a");
    // Reader can still be closed after the exception
    reader.close();
  }

  /** Member names are compared after decoding, so escape sequences don't bypass the check. */
  @Test
  public void testEscapeEquivalentNames() throws IOException {
    JsonReader reader = newRejectingReader("{\"a\":1,\"\\u0061\":2}");
    reader.beginObject();
    assertThat(reader.nextName()).isEqualTo("a");
    reader.skipValue();
    MalformedJsonException e = assertThrows(MalformedJsonException.class, reader::nextName);
    assertThat(e).hasMessageThat().contains("Duplicate object member \"a\"");
  }

  /** Nested objects may reuse names of enclosing objects; array elements are independent. */
  @Test
  public void testScopeIsPerObject() {
    // Nested object reuses the name of the enclosing object
    assertThat(REJECTING_GSON.fromJson("{\"a\":{\"a\":1}}", JsonElement.class).toString())
        .isEqualTo("{\"a\":{\"a\":1}}");
    // Separate objects in the same array don't affect each other
    assertThat(REJECTING_GSON.fromJson("[{\"a\":1},{\"a\":2}]", JsonElement.class).toString())
        .isEqualTo("[{\"a\":1},{\"a\":2}]");
    // Sibling objects in the same object don't affect each other
    assertThat(
            REJECTING_GSON
                .fromJson("{\"x\":{\"a\":1},\"y\":{\"a\":2}}", JsonElement.class)
                .toString())
        .isEqualTo("{\"x\":{\"a\":1},\"y\":{\"a\":2}}");
  }

  /** Duplicates inside JSON objects skipped with {@link JsonReader#skipValue()} are rejected. */
  @Test
  public void testSkipValueChecksSkippedObjects() throws IOException {
    JsonReader reader = newRejectingReader("{\"x\":{\"a\":1,\"a\":2}}");
    reader.beginObject();
    assertThat(reader.nextName()).isEqualTo("x");
    MalformedJsonException e = assertThrows(MalformedJsonException.class, reader::skipValue);
    assertThat(e).hasMessageThat().contains("Duplicate object member \"a\"");

    // Skipping a whole top-level document is checked as well
    JsonReader topLevel = newRejectingReader(DUPLICATE_JSON);
    assertThrows(MalformedJsonException.class, topLevel::skipValue);
  }

  /** Names skipped individually with {@link JsonReader#skipValue()} are checked as well. */
  @Test
  public void testSkipValueOnName() throws IOException {
    JsonReader reader = newRejectingReader(DUPLICATE_JSON);
    reader.beginObject();
    assertThat(reader.nextName()).isEqualTo("a");
    reader.skipValue(); // skips the value 1
    MalformedJsonException e = assertThrows(MalformedJsonException.class, reader::skipValue);
    assertThat(e).hasMessageThat().contains("Duplicate object member \"a\"");
  }

  /** Duplicate detection works for names which span a buffer refill. */
  @Test
  public void testNameCrossingBufferBoundary() throws IOException {
    StringBuilder longName = new StringBuilder();
    for (int i = 0; i < 500; i++) {
      longName.append("abcdefghij");
    }
    String json = "{\"" + longName + "\":1,\"" + longName + "\":2}";
    JsonReader reader = newRejectingReader(json);
    reader.beginObject();
    assertThat(reader.nextName()).isEqualTo(longName.toString());
    reader.skipValue();
    MalformedJsonException e = assertThrows(MalformedJsonException.class, reader::nextName);
    assertThat(e).hasMessageThat().contains("Duplicate object member");
  }

  /** A custom {@link TypeAdapter} calling {@link JsonReader#nextName()} is checked as well. */
  @Test
  public void testCustomTypeAdapter() {
    TypeAdapter<List<String>> namesAdapter =
        new TypeAdapter<List<String>>() {
          @Override
          public void write(JsonWriter out, List<String> value) {
            throw new UnsupportedOperationException();
          }

          @Override
          public List<String> read(JsonReader in) throws IOException {
            List<String> names = new ArrayList<>();
            in.beginObject();
            while (in.hasNext()) {
              names.add(in.nextName());
              in.skipValue();
            }
            in.endObject();
            return names;
          }
        };
    Gson gson =
        new GsonBuilder()
            .setDuplicateMemberPolicy(DuplicateMemberPolicy.REJECT)
            .registerTypeAdapter(new TypeToken<List<String>>() {}.getType(), namesAdapter)
            .create();
    assertDuplicateRejected(gson, DUPLICATE_JSON, new TypeToken<List<String>>() {}.getType());
  }

  /** A duplicate is detected even when the first occurrence has a {@code null} value. */
  @Test
  public void testFirstValueNull() throws IOException {
    JsonReader reader = newRejectingReader("{\"a\":null,\"a\":1}");
    reader.beginObject();
    assertThat(reader.nextName()).isEqualTo("a");
    reader.nextNull();
    MalformedJsonException e = assertThrows(MalformedJsonException.class, reader::nextName);
    assertThat(e).hasMessageThat().contains("Duplicate object member \"a\"");
  }

  /**
   * The duplicate is reported when the second name is read, before its value is consumed. The
   * second value here is malformed, so any implementation consuming the value first would fail with
   * a different error.
   */
  @Test
  public void testErrorBeforeSecondValueConsumed() throws IOException {
    JsonReader reader = newRejectingReader("{\"a\":1,\"a\":]}");
    reader.beginObject();
    assertThat(reader.nextName()).isEqualTo("a");
    assertThat(reader.nextInt()).isEqualTo(1);
    MalformedJsonException e = assertThrows(MalformedJsonException.class, reader::nextName);
    assertThat(e).hasMessageThat().contains("Duplicate object member \"a\"");
  }

  /**
   * The policy compares raw JSON member names only; distinct names mapped to the same Java field
   * through {@link SerializedName} alternates are not duplicates.
   */
  @Test
  public void testSerializedNameAlternatesAreNotDuplicates() {
    WithAlternates result = REJECTING_GSON.fromJson("{\"x\":1,\"y\":2}", WithAlternates.class);
    assertThat(result.value).isEqualTo(2);
  }

  /**
   * The {@code Map} key adapter's own equivalence check is unaffected by this policy: distinct
   * member names which deserialize to equal keys are still rejected by the {@code Map} adapter
   * itself, not by the duplicate member check.
   */
  @Test
  public void testMapKeyEquivalenceUnchanged() {
    Type mapType = new TypeToken<Map<Double, String>>() {}.getType();
    JsonSyntaxException e =
        assertThrows(
            JsonSyntaxException.class,
            () -> REJECTING_GSON.fromJson("{\"1\":\"a\",\"1.0\":\"b\"}", mapType));
    assertThat(e).hasMessageThat().contains("Duplicate key");
  }

  /** An already constructed {@link JsonElement} has no duplicate member history. */
  @Test
  public void testJsonTreeReaderNotAffected() {
    JsonObject jsonObject = new JsonObject();
    jsonObject.addProperty("a", 1);
    jsonObject.addProperty("a", 2); // replaces the previous entry
    Map<?, ?> map =
        REJECTING_GSON.fromJson(jsonObject, new TypeToken<Map<String, Integer>>() {}.getType());
    assertThat(map).hasSize(1);
  }

  @Test
  public void testGsonNewBuilderRetainsPolicy() {
    Gson gson = REJECTING_GSON.newBuilder().create();
    assertDuplicateRejected(gson, DUPLICATE_JSON, JsonElement.class);
  }

  @Test
  public void testNewJsonReaderUsesPolicy() throws IOException {
    JsonReader reader = REJECTING_GSON.newJsonReader(new StringReader(DUPLICATE_JSON));
    assertThat(reader.getDuplicateMemberPolicy()).isEqualTo(DuplicateMemberPolicy.REJECT);
    reader.beginObject();
    assertThat(reader.nextName()).isEqualTo("a");
    reader.skipValue();
    assertThrows(MalformedJsonException.class, reader::nextName);
  }

  /** Every reader tracks member names independently, so readers can be used concurrently. */
  @Test
  public void testConcurrentReaders() throws Exception {
    int threadCount = 8;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    try {
      List<Future<Boolean>> futures = new ArrayList<>();
      for (int i = 0; i < threadCount; i++) {
        boolean expectDuplicate = i % 2 == 0;
        Callable<Boolean> task =
            () -> {
              for (int j = 0; j < 100; j++) {
                String json =
                    expectDuplicate
                        ? "{\"a\":1,\"b\":{\"a\":2,\"a\":3}}"
                        : "{\"a\":1,\"b\":{\"a\":2},\"c\":[{\"a\":3},{\"a\":4}]}";
                JsonReader reader = newRejectingReader(json);
                boolean rejected;
                try {
                  JsonElement parsed = REJECTING_GSON.fromJson(reader, JsonElement.class);
                  if (parsed == null) {
                    return false;
                  }
                  rejected = false;
                } catch (JsonSyntaxException e) {
                  rejected = String.valueOf(e.getMessage()).contains("Duplicate object member");
                } finally {
                  reader.close();
                }
                if (rejected != expectDuplicate) {
                  return false;
                }
              }
              return true;
            };
        futures.add(executor.submit(task));
      }
      for (Future<Boolean> future : futures) {
        assertThat(future.get()).isTrue();
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void testReaderStateIndependent() throws IOException {
    // A duplicate in one reader must not affect another reader
    JsonReader reader1 = newRejectingReader(DUPLICATE_JSON);
    JsonReader reader2 = newRejectingReader("{\"a\":1}");
    reader1.beginObject();
    reader2.beginObject();
    assertThat(reader1.nextName()).isEqualTo("a");
    assertThat(reader2.nextName()).isEqualTo("a");
    reader1.skipValue();
    reader2.skipValue();
    assertThrows(MalformedJsonException.class, reader1::nextName);
    assertThat(reader2.peek()).isEqualTo(JsonToken.END_OBJECT);
  }

  /**
   * The policy can be changed on a reader at any time, also back to {@link
   * DuplicateMemberPolicy#ALLOW}.
   */
  @Test
  public void testPolicyCanBeDisabledAgain() throws IOException {
    JsonReader reader = newRejectingReader(DUPLICATE_JSON);
    reader.setDuplicateMemberPolicy(DuplicateMemberPolicy.ALLOW);
    reader.beginObject();
    assertThat(reader.nextName()).isEqualTo("a");
    reader.skipValue();
    assertThat(reader.nextName()).isEqualTo("a"); // no duplicate error
    reader.skipValue();
    reader.endObject();
  }
}
