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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class CheckedMapTypeAdapterFactoryTest {
  private static final TypeToken<LinkedHashMap<Object, Payload>> OBJECT_TO_PAYLOAD_TOKEN =
      new TypeToken<LinkedHashMap<Object, Payload>>() {};

  /** Value type whose adapter counts how often it is invoked. */
  static final class Payload {
    final String text;

    Payload(String text) {
      this.text = text;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Payload && text.equals(((Payload) o).text);
    }

    @Override
    public int hashCode() {
      return text.hashCode();
    }
  }

  static final class CountingPayloadAdapter extends TypeAdapter<Payload> {
    int writeCount;

    @Override
    public void write(JsonWriter out, Payload value) throws IOException {
      writeCount++;
      if (value == null) {
        out.nullValue();
      } else {
        out.value(value.text);
      }
    }

    @Override
    public Payload read(JsonReader in) throws IOException {
      return new Payload(in.nextString());
    }
  }

  /** Key adapter that counts how often it serializes a key. */
  static final class CountingIntegerAdapter extends TypeAdapter<Integer> {
    int writeCount;

    @Override
    public void write(JsonWriter out, Integer value) throws IOException {
      writeCount++;
      if (value == null) {
        out.nullValue();
      } else {
        out.value(value);
      }
    }

    @Override
    public Integer read(JsonReader in) throws IOException {
      return in.nextInt();
    }
  }

  private static Gson checkedGson(CountingPayloadAdapter valueAdapter) {
    return new GsonBuilder()
        .registerTypeAdapterFactory(new CheckedMapTypeAdapterFactory())
        .registerTypeAdapter(Payload.class, valueAdapter)
        .create();
  }

  @Test
  public void integerAndStringKeyCollision_reportsBothPositions_andWritesNothing()
      throws IOException {
    CountingPayloadAdapter valueAdapter = new CountingPayloadAdapter();
    Gson gson = checkedGson(valueAdapter);
    LinkedHashMap<Object, Payload> map = new LinkedHashMap<>();
    map.put(1, new Payload("a"));
    map.put("1", new Payload("b"));

    StringWriter stringWriter = new StringWriter();
    JsonWriter jsonWriter = new JsonWriter(stringWriter);
    TypeAdapter<LinkedHashMap<Object, Payload>> adapter =
        gson.getAdapter(OBJECT_TO_PAYLOAD_TOKEN);
    JsonIOException e =
        assertThrows(JsonIOException.class, () -> adapter.write(jsonWriter, map));

    // Reports the iteration positions of both colliding keys
    assertThat(e.getMessage()).contains("positions 0 and 1");
    assertThat(e.getMessage()).contains("field name \"1\"");
    // Nothing was written and the value adapter was never invoked
    assertThat(stringWriter.toString()).isEmpty();
    assertThat(valueAdapter.writeCount).isEqualTo(0);
    // Input map was not modified
    assertThat(map).containsExactly(1, new Payload("a"), "1", new Payload("b")).inOrder();
  }

  @Test
  public void nullKeyAndLiteralNullStringCollision_isRejected() throws IOException {
    CountingPayloadAdapter valueAdapter = new CountingPayloadAdapter();
    Gson gson = checkedGson(valueAdapter);
    LinkedHashMap<Object, Payload> map = new LinkedHashMap<>();
    map.put(null, new Payload("a"));
    map.put("null", new Payload("b"));

    StringWriter stringWriter = new StringWriter();
    JsonWriter jsonWriter = new JsonWriter(stringWriter);
    TypeAdapter<LinkedHashMap<Object, Payload>> adapter =
        gson.getAdapter(OBJECT_TO_PAYLOAD_TOKEN);
    JsonIOException e =
        assertThrows(JsonIOException.class, () -> adapter.write(jsonWriter, map));

    assertThat(e.getMessage()).contains("positions 0 and 1");
    assertThat(e.getMessage()).contains("field name \"null\"");
    assertThat(stringWriter.toString()).isEmpty();
    assertThat(valueAdapter.writeCount).isEqualTo(0);
  }

  @Test
  public void complexArrayKey_isRejectedBeforeAnythingIsWritten() throws IOException {
    CountingPayloadAdapter valueAdapter = new CountingPayloadAdapter();
    Gson gson = checkedGson(valueAdapter);
    LinkedHashMap<Object, Payload> map = new LinkedHashMap<>();
    map.put(Arrays.asList(1, 2), new Payload("a"));

    StringWriter stringWriter = new StringWriter();
    JsonWriter jsonWriter = new JsonWriter(stringWriter);
    TypeAdapter<LinkedHashMap<Object, Payload>> adapter =
        gson.getAdapter(OBJECT_TO_PAYLOAD_TOKEN);
    JsonIOException e =
        assertThrows(JsonIOException.class, () -> adapter.write(jsonWriter, map));

    assertThat(e.getMessage()).contains("position 0");
    assertThat(e.getMessage()).contains("complex map keys are not supported");
    assertThat(stringWriter.toString()).isEmpty();
    assertThat(valueAdapter.writeCount).isEqualTo(0);
  }

  /** POJO key which the reflective adapter serializes as a JSON object. */
  static final class Point {
    @SuppressWarnings("unused")
    final int x;

    @SuppressWarnings("unused")
    final int y;

    Point(int x, int y) {
      this.x = x;
      this.y = y;
    }
  }

  @Test
  public void complexPojoKey_isRejectedBeforeAnythingIsWritten() throws IOException {
    CountingPayloadAdapter valueAdapter = new CountingPayloadAdapter();
    Gson gson = checkedGson(valueAdapter);
    LinkedHashMap<Object, Payload> map = new LinkedHashMap<>();
    map.put(new Point(1, 2), new Payload("a"));

    StringWriter stringWriter = new StringWriter();
    JsonWriter jsonWriter = new JsonWriter(stringWriter);
    TypeAdapter<LinkedHashMap<Object, Payload>> adapter =
        gson.getAdapter(OBJECT_TO_PAYLOAD_TOKEN);
    JsonIOException e =
        assertThrows(JsonIOException.class, () -> adapter.write(jsonWriter, map));

    assertThat(e.getMessage()).contains("complex map keys are not supported");
    assertThat(stringWriter.toString()).isEmpty();
    assertThat(valueAdapter.writeCount).isEqualTo(0);
  }

  @Test
  public void noCollision_writesValuesInOriginalOrder_andRoundTrips() {
    Gson gson = checkedGson(new CountingPayloadAdapter());
    LinkedHashMap<String, Payload> map = new LinkedHashMap<>();
    map.put("b", new Payload("first"));
    map.put("a", new Payload("second"));
    map.put("10", new Payload("third"));
    Type type = new TypeToken<LinkedHashMap<String, Payload>>() {}.getType();

    String json = gson.toJson(map, type);
    assertThat(json).isEqualTo("{\"b\":\"first\",\"a\":\"second\",\"10\":\"third\"}");

    Map<String, Payload> readBack = gson.fromJson(json, type);
    assertThat(readBack).containsExactlyEntriesIn(map).inOrder();
    // Input map was not modified by serialization
    assertThat(map)
        .containsExactly(
            "b", new Payload("first"), "a", new Payload("second"), "10", new Payload("third"))
        .inOrder();
  }

  @Test
  public void nullAndEmptyMap_areWrittenNormally() {
    Gson gson = checkedGson(new CountingPayloadAdapter());
    Type type = new TypeToken<LinkedHashMap<String, Payload>>() {}.getType();

    assertThat(gson.toJson(null, type)).isEqualTo("null");
    assertThat(gson.toJson(new LinkedHashMap<String, Payload>(), type)).isEqualTo("{}");
  }

  @Test
  public void keyAdapter_isCalledExactlyOncePerKey() {
    CountingIntegerAdapter keyAdapter = new CountingIntegerAdapter();
    Gson gson =
        new GsonBuilder()
            .registerTypeAdapterFactory(new CheckedMapTypeAdapterFactory())
            .registerTypeAdapter(Integer.class, keyAdapter)
            .create();
    LinkedHashMap<Integer, String> map = new LinkedHashMap<>();
    map.put(1, "a");
    map.put(2, "b");
    map.put(3, "c");
    Type type = new TypeToken<LinkedHashMap<Integer, String>>() {}.getType();

    String json = gson.toJson(map, type);

    assertThat(json).isEqualTo("{\"1\":\"a\",\"2\":\"b\",\"3\":\"c\"}");
    // Pre-flight computes the names once and reuses them for writing; no second key pass
    assertThat(keyAdapter.writeCount).isEqualTo(3);
    assertThat(gson.<LinkedHashMap<Integer, String>>fromJson(json, type))
        .containsExactlyEntriesIn(map)
        .inOrder();
  }

  @Test
  public void genericMapType_registeredViaFactory_roundTrips() {
    Gson gson =
        new GsonBuilder()
            .registerTypeAdapterFactory(new CheckedMapTypeAdapterFactory())
            .create();
    Type type = new TypeToken<Map<String, List<Integer>>>() {}.getType();
    Map<String, List<Integer>> map = new LinkedHashMap<>();
    map.put("evens", Arrays.asList(2, 4));
    map.put("odds", Arrays.asList(1, 3));

    String json = gson.toJson(map, type);
    assertThat(json).isEqualTo("{\"evens\":[2,4],\"odds\":[1,3]}");
    assertThat(gson.<Map<String, List<Integer>>>fromJson(json, type))
        .containsExactlyEntriesIn(map)
        .inOrder();

    // Non-map types are not affected by the factory
    assertThat(gson.toJson(Arrays.asList("x", "y"))).isEqualTo("[\"x\",\"y\"]");
  }

  @Test
  public void genericMapType_registeredViaTypeToken_usesCheckedAdapter() {
    Type type = new TypeToken<LinkedHashMap<Object, String>>() {}.getType();
    Gson gson =
        new GsonBuilder()
            .registerTypeAdapter(
                type,
                new CheckedMapTypeAdapterFactory()
                    .create(new Gson(), TypeToken.get(type)))
            .create();
    LinkedHashMap<Object, String> colliding = new LinkedHashMap<>();
    colliding.put(1, "a");
    colliding.put("1", "b");

    JsonIOException e =
        assertThrows(JsonIOException.class, () -> gson.toJson(colliding, type));
    assertThat(e.getMessage()).contains("positions 0 and 1");

    LinkedHashMap<Object, String> fine = new LinkedHashMap<>();
    fine.put(2, "a");
    fine.put("two", "b");
    assertThat(gson.toJson(fine, type)).isEqualTo("{\"2\":\"a\",\"two\":\"b\"}");
  }

  @Test
  public void defaultMapSerialization_isUnchanged() {
    // Without explicit registration the default adapter still silently emits duplicate names
    Gson gson = new Gson();
    LinkedHashMap<Object, String> map = new LinkedHashMap<>();
    map.put(1, "a");
    map.put("1", "b");
    Type type = new TypeToken<LinkedHashMap<Object, String>>() {}.getType();

    assertThat(gson.toJson(map, type)).isEqualTo("{\"1\":\"a\",\"1\":\"b\"}");
  }
}
