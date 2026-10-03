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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.internal.bind.CheckedMapTypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class CheckedMapTypeAdapterTest {

  /** A value type whose adapter counts how many times it is written. */
  static final class CountingValue {
    final String data;

    CountingValue(String data) {
      this.data = data;
    }
  }

  static final class CountingValueAdapter extends TypeAdapter<CountingValue> {
    int writeCount;

    @Override
    public void write(JsonWriter out, CountingValue value) throws IOException {
      writeCount++;
      out.value(value.data);
    }

    @Override
    public CountingValue read(JsonReader in) throws IOException {
      return new CountingValue(in.nextString());
    }
  }

  /** A complex key type that serializes to a JSON object. */
  static final class ComplexKey {
    final int x;
    final int y;

    ComplexKey(int x, int y) {
      this.x = x;
      this.y = y;
    }
  }

  private GsonBuilder checkedMapBuilder() {
    return new GsonBuilder().registerTypeAdapterFactory(new CheckedMapTypeAdapterFactory());
  }

  @Test
  public void testIntegerAndStringCollision() throws IOException {
    CountingValueAdapter valueAdapter = new CountingValueAdapter();
    Gson gson =
        checkedMapBuilder().registerTypeAdapter(CountingValue.class, valueAdapter).create();

    Map<Object, CountingValue> map = new LinkedHashMap<>();
    map.put(1, new CountingValue("a"));
    map.put("1", new CountingValue("b"));

    StringWriter stringWriter = new StringWriter();
    JsonWriter jsonWriter = new JsonWriter(stringWriter);
    TypeToken<Map<Object, CountingValue>> typeToken = new TypeToken<Map<Object, CountingValue>>() {};
    TypeAdapter<Map<Object, CountingValue>> adapter = gson.getAdapter(typeToken);

    JsonSyntaxException e = null;
    try {
      adapter.write(jsonWriter, map);
      fail("Expected JsonSyntaxException");
    } catch (JsonSyntaxException expected) {
      e = expected;
    }
    assertTrue(e.getMessage().contains("positions 0 and 1"));
    assertTrue(e.getMessage().contains("same field name '1'"));
    // No output was written and the value adapter was never called.
    assertEquals("", stringWriter.toString());
    assertEquals(0, valueAdapter.writeCount);
  }

  @Test
  public void testNonCollidingMapOutputsAndReadsBack() {
    Gson gson = checkedMapBuilder().create();
    Type type = new TypeToken<Map<String, String>>() {}.getType();

    Map<String, String> original = new LinkedHashMap<>();
    original.put("a", "1");
    original.put("b", "2");
    original.put("c", "3");

    String json = gson.toJson(original, type);
    assertEquals("{\"a\":\"1\",\"b\":\"2\",\"c\":\"3\"}", json);

    Map<String, String> readBack = gson.fromJson(json, type);
    assertEquals(original, readBack);
  }

  @Test
  public void testNullAndLiteralNullCollision() throws IOException {
    Gson gson = checkedMapBuilder().create();

    Map<Object, String> map = new LinkedHashMap<>();
    map.put(null, "a");
    map.put("null", "b");

    StringWriter stringWriter = new StringWriter();
    JsonWriter jsonWriter = new JsonWriter(stringWriter);
    TypeToken<Map<Object, String>> typeToken = new TypeToken<Map<Object, String>>() {};
    TypeAdapter<Map<Object, String>> adapter = gson.getAdapter(typeToken);

    JsonSyntaxException e = null;
    try {
      adapter.write(jsonWriter, map);
      fail("Expected JsonSyntaxException");
    } catch (JsonSyntaxException expected) {
      e = expected;
    }
    assertTrue(e.getMessage().contains("positions 0 and 1"));
    assertTrue(e.getMessage().contains("same field name 'null'"));
    assertEquals("", stringWriter.toString());
  }

  @Test
  public void testComplexKeyRejected() throws IOException {
    Gson gson = checkedMapBuilder().create();

    Map<Object, String> map = new LinkedHashMap<>();
    map.put(new ComplexKey(1, 2), "a");

    StringWriter stringWriter = new StringWriter();
    JsonWriter jsonWriter = new JsonWriter(stringWriter);
    TypeToken<Map<Object, String>> typeToken = new TypeToken<Map<Object, String>>() {};
    TypeAdapter<Map<Object, String>> adapter = gson.getAdapter(typeToken);

    JsonSyntaxException e = null;
    try {
      adapter.write(jsonWriter, map);
      fail("Expected JsonSyntaxException");
    } catch (JsonSyntaxException expected) {
      e = expected;
    }
    assertTrue(e.getMessage().contains("position 0"));
    assertTrue(e.getMessage().contains("complex key"));
    assertEquals("", stringWriter.toString());
  }

  @Test
  public void testGenericRegistration() {
    Gson gson = checkedMapBuilder().create();
    Type type = new TypeToken<Map<String, Integer>>() {}.getType();

    Map<String, Integer> map = new LinkedHashMap<>();
    map.put("a", 1);
    map.put("b", 2);

    String json = gson.toJson(map, type);
    assertEquals("{\"a\":1,\"b\":2}", json);

    Map<String, Integer> readBack = gson.fromJson(json, type);
    assertEquals(map, readBack);
  }

  @Test
  public void testDefaultMapSerializationUnchanged() {
    // Without the checked factory, the default map serialization silently overwrites.
    Gson gson = new GsonBuilder().create();
    Type type = new TypeToken<Map<Object, String>>() {}.getType();

    Map<Object, String> map = new LinkedHashMap<>();
    map.put(1, "a");
    map.put("1", "b");

    String json = gson.toJson(map, type);
    // Default behavior: both keys produce "1", emitting duplicate field names in the JSON.
    assertEquals("{\"1\":\"a\",\"1\":\"b\"}", json);
  }

  @Test
  public void testKeyAdapterCalledAtMostOnce() {
    // Use a key type with a custom adapter that counts invocations.
    CountingKeyAdapter keyAdapter = new CountingKeyAdapter();
    Gson gson = checkedMapBuilder().registerTypeAdapter(CountingKey.class, keyAdapter).create();

    Map<CountingKey, String> map = new LinkedHashMap<>();
    map.put(new CountingKey("a"), "1");
    map.put(new CountingKey("b"), "2");

    String json = gson.toJson(map, new TypeToken<Map<CountingKey, String>>() {}.getType());
    assertEquals("{\"a\":\"1\",\"b\":\"2\"}", json);
    // The key adapter must be called exactly once per key.
    assertEquals(2, keyAdapter.writeCount);
  }

  static final class CountingKey {
    final String value;

    CountingKey(String value) {
      this.value = value;
    }
  }

  static final class CountingKeyAdapter extends TypeAdapter<CountingKey> {
    int writeCount;

    @Override
    public void write(JsonWriter out, CountingKey key) throws IOException {
      writeCount++;
      out.value(key.value);
    }

    @Override
    public CountingKey read(JsonReader in) throws IOException {
      return new CountingKey(in.nextString());
    }
  }
}
