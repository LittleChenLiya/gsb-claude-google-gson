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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.internal.bind.CheckedMapTypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small demo for {@link CheckedMapTypeAdapterFactory}.
 *
 * <p>Run with:
 *
 * <pre>{@code
 * java -cp <classpath> com.google.gson.functional.CheckedMapTypeAdapterDemo
 * }</pre>
 */
public final class CheckedMapTypeAdapterDemo {

  private CheckedMapTypeAdapterDemo() {}

  @SuppressWarnings("SystemOut")
  public static void main(String[] args) {
    Gson gson =
        new GsonBuilder().registerTypeAdapterFactory(new CheckedMapTypeAdapterFactory()).create();

    // 1. Non-colliding map: serializes and reads back normally.
    Map<String, String> ok = new LinkedHashMap<>();
    ok.put("a", "1");
    ok.put("b", "2");
    String json = gson.toJson(ok, new TypeToken<Map<String, String>>() {}.getType());
    System.out.println("Non-colliding map: " + json);
    Map<String, String> back =
        gson.fromJson(json, new TypeToken<Map<String, String>>() {}.getType());
    System.out.println("Read back: " + back);

    // 2. Colliding keys (integer 1 and string "1"): rejected before any output is written.
    Map<Object, String> collision = new LinkedHashMap<>();
    collision.put(1, "integer");
    collision.put("1", "string");
    try {
      String unused = gson.toJson(collision, new TypeToken<Map<Object, String>>() {}.getType());
      System.out.println("Unexpected: " + unused);
    } catch (JsonSyntaxException e) {
      System.out.println("Collision detected: " + e.getMessage());
    }

    // 3. Null key and literal "null": also rejected.
    Map<Object, String> nullCollision = new LinkedHashMap<>();
    nullCollision.put(null, "null-key");
    nullCollision.put("null", "string-key");
    try {
      String unused =
          gson.toJson(nullCollision, new TypeToken<Map<Object, String>>() {}.getType());
      System.out.println("Unexpected: " + unused);
    } catch (JsonSyntaxException e) {
      System.out.println("Null collision detected: " + e.getMessage());
    }
  }
}
