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

import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.MalformedJsonException;
import java.io.FilterReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.Test;

public final class DuplicateMemberPolicyTest {
  private static final String DUPLICATE_JSON = "{\"a\":1,\"a\":2}";

  private static final Gson GSON_ALLOW = new Gson();
  private static final Gson GSON_REJECT =
      new GsonBuilder().setDuplicateMemberPolicy(DuplicateMemberPolicy.REJECT).create();

  private static class Pojo {
    int a;
  }

  private static void assertDuplicateRejected(JsonReader reader) throws IOException {
    MalformedJsonException e = assertThrows(MalformedJsonException.class, reader::nextName);
    assertThat(e).hasMessageThat().contains("Duplicate member \"a\"");
    // Error has to contain the member path and the location in the JSON document
    assertThat(e).hasMessageThat().contains("path $.a");
    assertThat(e).hasMessageThat().contains("at line 1 column");
    // Closing the reader after the exception has to be possible
    reader.close();
  }

  @Test
  public void testReaderDefaultPolicyIsAllow() throws IOException {
    JsonReader reader = new JsonReader(new StringReader(DUPLICATE_JSON));
    assertThat(reader.getDuplicateMemberPolicy()).isEqualTo(DuplicateMemberPolicy.ALLOW);
    reader.beginObject();
    assertThat(reader.nextName()).isEqualTo("a");
    reader.nextInt();
    assertThat(reader.nextName()).isEqualTo("a");
    reader.nextInt();
    reader.endObject();
  }

  @Test
  public void testReaderRejectsDuplicate() throws IOException {
    JsonReader reader = new JsonReader(new StringReader(DUPLICATE_JSON));
    reader.setDuplicateMemberPolicy(DuplicateMemberPolicy.REJECT);
    assertThat(reader.getDuplicateMemberPolicy()).isEqualTo(DuplicateMemberPolicy.REJECT);
    reader.beginObject();
    assertThat(reader.nextName()).isEqualTo("a");
    reader.nextInt();
    assertDuplicateRejected(reader);
  }

  @Test
  public void testReaderPolicyCannotBeNull() {
    JsonReader reader = new JsonReader(new StringReader("{}"));
    assertThrows(NullPointerException.class, () -> reader.setDuplicateMemberPolicy(null));
  }

  @Test
  public void testUnicodeEscapedNameIsDuplicate() throws IOException {
    // The escaped name "\\u0061" decodes to "a" and is therefore a duplicate
    JsonReader reader = new JsonReader(new StringReader("{\"a\":1,\"\\u0061\":2}"));
    reader.setDuplicateMemberPolicy(DuplicateMemberPolicy.REJECT);
    reader.beginObject();
    assertThat(reader.nextName()).isEqualTo("a");
    reader.nextInt();
    assertDuplicateRejected(reader);
  }

  @Test
  public void testNestedObjectCanReuseNames() throws IOException {
    JsonReader reader = new JsonReader(new StringReader("{\"a\":{\"a\":1}}"));
    reader.setDuplicateMemberPolicy(DuplicateMemberPolicy.REJECT);
    reader.beginObject();
    assertThat(reader.nextName()).isEqualTo("a");
    reader.beginObject();
    assertThat(reader.nextName()).isEqualTo("a");
    reader.nextInt();
    reader.endObject();
    reader.endObject();
  }

  @Test
  public void testObjectsInArrayAreIndependent() throws IOException {
    JsonReader reader = new JsonReader(new StringReader("[{\"a\":1},{\"a\":2}]"));
    reader.setDuplicateMemberPolicy(DuplicateMemberPolicy.REJECT);
    reader.beginArray();
    reader.beginObject();
    assertThat(reader.nextName()).isEqualTo("a");
    reader.nextInt();
    reader.endObject();
    reader.beginObject();
    assertThat(reader.nextName()).isEqualTo("a");
    reader.nextInt();
    reader.endObject();
    reader.endArray();
  }

  @Test
  public void testNamesAreReleasedWhenScopeIsLeft() throws IOException {
    JsonReader reader = new JsonReader(new StringReader("{\"a\":1}{\"a\":2}"));
    reader.setStrictness(Strictness.LENIENT);
    reader.setDuplicateMemberPolicy(DuplicateMemberPolicy.REJECT);
    reader.beginObject();
    assertThat(reader.nextName()).isEqualTo("a");
    reader.nextInt();
    reader.endObject();
    // Same name in the next top-level object must not be considered a duplicate
    reader.beginObject();
    assertThat(reader.nextName()).isEqualTo("a");
    reader.nextInt();
    reader.endObject();
  }

  @Test
  public void testDuplicateWithNullFirstValue() throws IOException {
    JsonReader reader = new JsonReader(new StringReader("{\"a\":null,\"a\":1}"));
    reader.setDuplicateMemberPolicy(DuplicateMemberPolicy.REJECT);
    reader.beginObject();
    assertThat(reader.nextName()).isEqualTo("a");
    reader.nextNull();
    assertDuplicateRejected(reader);
  }

  @Test
  public void testDuplicateNameSpanningBufferRefill() throws IOException {
    // Use a name which is longer than the internal buffer of JsonReader (1024 chars)
    StringBuilder name = new StringBuilder();
    for (int i = 0; i < 2000; i++) {
      name.append('x');
    }
    String json = "{\"" + name + "\":1,\"" + name + "\":2}";
    JsonReader reader = new JsonReader(new StringReader(json));
    reader.setDuplicateMemberPolicy(DuplicateMemberPolicy.REJECT);
    reader.beginObject();
    assertThat(reader.nextName()).isEqualTo(name.toString());
    reader.nextInt();
    MalformedJsonException e = assertThrows(MalformedJsonException.class, reader::nextName);
    assertThat(e).hasMessageThat().contains("Duplicate member");
  }

  @Test
  public void testSkipValueChecksSkippedObjects() throws IOException {
    JsonReader reader = new JsonReader(new StringReader(DUPLICATE_JSON));
    reader.setDuplicateMemberPolicy(DuplicateMemberPolicy.REJECT);
    MalformedJsonException e = assertThrows(MalformedJsonException.class, reader::skipValue);
    assertThat(e).hasMessageThat().contains("Duplicate member \"a\"");
  }

  @Test
  public void testSkipValueChecksNestedSkippedObjects() throws IOException {
    JsonReader reader = new JsonReader(new StringReader("{\"outer\":{\"a\":1,\"a\":2}}"));
    reader.setDuplicateMemberPolicy(DuplicateMemberPolicy.REJECT);
    reader.beginObject();
    assertThat(reader.nextName()).isEqualTo("outer");
    MalformedJsonException e = assertThrows(MalformedJsonException.class, reader::skipValue);
    assertThat(e).hasMessageThat().contains("Duplicate member \"a\"");
  }

  @Test
  public void testPojoRejected() {
    JsonSyntaxException e =
        assertThrows(
            JsonSyntaxException.class, () -> GSON_REJECT.fromJson(DUPLICATE_JSON, Pojo.class));
    assertThat(e).hasCauseThat().isInstanceOf(MalformedJsonException.class);
    assertThat(e).hasMessageThat().contains("Duplicate member \"a\"");
  }

  @Test
  public void testMapRejected() {
    JsonSyntaxException e =
        assertThrows(
            JsonSyntaxException.class,
            () -> GSON_REJECT.fromJson(DUPLICATE_JSON, new TypeToken<Map<String, Integer>>() {}));
    assertThat(e).hasCauseThat().isInstanceOf(MalformedJsonException.class);
  }

  @Test
  public void testObjectRejected() {
    JsonSyntaxException e =
        assertThrows(
            JsonSyntaxException.class, () -> GSON_REJECT.fromJson(DUPLICATE_JSON, Object.class));
    assertThat(e).hasCauseThat().isInstanceOf(MalformedJsonException.class);
  }

  @Test
  public void testJsonElementRejected() {
    JsonSyntaxException e =
        assertThrows(
            JsonSyntaxException.class,
            () -> GSON_REJECT.fromJson(DUPLICATE_JSON, JsonElement.class));
    assertThat(e).hasCauseThat().isInstanceOf(MalformedJsonException.class);
  }

  @Test
  public void testAllowKeepsExistingBehavior() {
    // POJO, Object and JsonElement: last occurrence wins
    assertThat(GSON_ALLOW.fromJson(DUPLICATE_JSON, Pojo.class).a).isEqualTo(2);
    assertThat(((Map<?, ?>) GSON_ALLOW.fromJson(DUPLICATE_JSON, Object.class)))
        .containsExactly("a", 2.0);
    assertThat(GSON_ALLOW.fromJson(DUPLICATE_JSON, JsonElement.class).toString())
        .isEqualTo("{\"a\":2}");
    // The Map adapter has its own duplicate key check which is not changed by this policy
    JsonSyntaxException e =
        assertThrows(
            JsonSyntaxException.class,
            () -> GSON_ALLOW.fromJson(DUPLICATE_JSON, new TypeToken<Map<String, Integer>>() {}));
    assertThat(e).hasMessageThat().contains("Duplicate key 'a'");
  }

  private static class AliasedPojo {
    @SerializedName(value = "x", alternate = "y")
    int field;
  }

  @Test
  public void testDistinctNamesMappingToSameFieldAreNotDuplicates() {
    // The policy only looks at the JSON member names, not at the Java fields they are mapped to
    AliasedPojo pojo = GSON_REJECT.fromJson("{\"x\":1,\"y\":2}", AliasedPojo.class);
    assertThat(pojo.field).isEqualTo(2);
  }

  @Test
  public void testCustomTypeAdapterUsingNextName() {
    TypeAdapter<Pojo> adapter =
        new TypeAdapter<Pojo>() {
          @Override
          public void write(com.google.gson.stream.JsonWriter out, Pojo value) {
            throw new UnsupportedOperationException();
          }

          @Override
          public Pojo read(JsonReader in) throws IOException {
            Pojo pojo = new Pojo();
            in.beginObject();
            while (in.hasNext()) {
              if (in.nextName().equals("a")) {
                pojo.a = in.nextInt();
              } else {
                in.skipValue();
              }
            }
            in.endObject();
            return pojo;
          }
        };
    Gson gson =
        new GsonBuilder()
            .setDuplicateMemberPolicy(DuplicateMemberPolicy.REJECT)
            .registerTypeAdapter(Pojo.class, adapter)
            .create();
    JsonSyntaxException e =
        assertThrows(JsonSyntaxException.class, () -> gson.fromJson(DUPLICATE_JSON, Pojo.class));
    assertThat(e).hasCauseThat().isInstanceOf(MalformedJsonException.class);
  }

  @Test
  public void testPojoUnknownFieldWithDuplicateInSkippedValue() {
    // The reflective adapter skips the unknown member "unknown" with skipValue(); the duplicate
    // inside the skipped object has to be detected as well
    JsonSyntaxException e =
        assertThrows(
            JsonSyntaxException.class,
            () -> GSON_REJECT.fromJson("{\"unknown\":{\"a\":1,\"a\":2}}", Pojo.class));
    assertThat(e).hasCauseThat().isInstanceOf(MalformedJsonException.class);
  }

  /** Reader which fails with a sentinel exception once more than {@code limit} chars are read. */
  private static final class LimitedReader extends FilterReader {
    private final int limit;
    private int read;

    LimitedReader(String s, int limit) {
      super(new StringReader(s));
      this.limit = limit;
    }

    @Override
    public int read(char[] cbuf, int off, int len) throws IOException {
      if (read >= limit) {
        throw new IOException("sentinel: read past limit");
      }
      int result = super.read(cbuf, off, Math.min(len, limit - read));
      if (result != -1) {
        read += result;
      }
      return result;
    }
  }

  @Test
  public void testErrorOccursBeforeSecondValueIsConsumed() throws IOException {
    // Only provide the input up to and including the second member name; any attempt to read
    // the value of the duplicate member fails with the sentinel exception
    int openingQuote = DUPLICATE_JSON.indexOf('"', DUPLICATE_JSON.indexOf(','));
    int limit = DUPLICATE_JSON.indexOf('"', openingQuote + 1) + 1;
    assertThat(DUPLICATE_JSON.substring(0, limit)).isEqualTo("{\"a\":1,\"a\"");

    JsonReader reader = new JsonReader(new LimitedReader(DUPLICATE_JSON, limit));
    reader.setDuplicateMemberPolicy(DuplicateMemberPolicy.REJECT);
    reader.beginObject();
    assertThat(reader.nextName()).isEqualTo("a");
    reader.nextInt();
    // Must fail with the duplicate member error, not with the sentinel IOException
    assertDuplicateRejected(reader);
  }

  @Test
  public void testGsonFromJsonRestoresReaderPolicy() {
    JsonReader reader = new JsonReader(new StringReader(DUPLICATE_JSON));
    assertThat(reader.getDuplicateMemberPolicy()).isEqualTo(DuplicateMemberPolicy.ALLOW);
    assertThrows(JsonSyntaxException.class, () -> GSON_REJECT.fromJson(reader, Pojo.class));
    assertThat(reader.getDuplicateMemberPolicy()).isEqualTo(DuplicateMemberPolicy.ALLOW);
  }

  @Test
  public void testGsonWithAllowPolicyDoesNotOverrideReaderPolicy() {
    // A policy set directly on the reader is kept when the Gson instance uses the default policy
    JsonReader reader = new JsonReader(new StringReader(DUPLICATE_JSON));
    reader.setDuplicateMemberPolicy(DuplicateMemberPolicy.REJECT);
    assertThrows(JsonSyntaxException.class, () -> GSON_ALLOW.fromJson(reader, Pojo.class));
  }

  @Test
  public void testConstructedJsonElementHasNoDuplicateHistory() {
    // A JsonElement which has already been constructed cannot have duplicate members anymore;
    // deserializing it with the REJECT policy must not fail
    JsonElement element = GSON_ALLOW.fromJson(DUPLICATE_JSON, JsonElement.class);
    Pojo pojo = GSON_REJECT.fromJson(element, Pojo.class);
    assertThat(pojo.a).isEqualTo(2);
  }

  @Test
  public void testConcurrentIndependentReaders() throws Exception {
    int threadCount = 8;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    try {
      List<Callable<Boolean>> tasks = new ArrayList<>();
      for (int i = 0; i < threadCount; i++) {
        tasks.add(
            () -> {
              // Each thread uses its own reader; duplicate detection state must not be shared
              JsonReader reader = new JsonReader(new StringReader(DUPLICATE_JSON));
              reader.setDuplicateMemberPolicy(DuplicateMemberPolicy.REJECT);
              reader.beginObject();
              reader.nextName();
              reader.nextInt();
              try {
                reader.nextName();
                return false;
              } catch (MalformedJsonException expected) {
                return true;
              }
            });
      }
      List<Future<Boolean>> results = executor.invokeAll(tasks);
      for (Future<Boolean> result : results) {
        assertThat(result.get()).isTrue();
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void testPeekIsNotAffected() throws IOException {
    JsonReader reader = new JsonReader(new StringReader(DUPLICATE_JSON));
    reader.setDuplicateMemberPolicy(DuplicateMemberPolicy.REJECT);
    reader.beginObject();
    assertThat(reader.peek()).isEqualTo(JsonToken.NAME);
    assertThat(reader.nextName()).isEqualTo("a");
    reader.nextInt();
    // peek() must not consume the name, so it must not trigger the check
    assertThat(reader.peek()).isEqualTo(JsonToken.NAME);
    assertDuplicateRejected(reader);
  }
}
