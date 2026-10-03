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

package com.google.gson.functional;

import com.google.gson.EnumValue;
import com.google.gson.EnumValueTypeAdapterFactory;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import java.util.ArrayList;
import java.util.List;

/**
 * A single-file demo of {@link EnumValue} and {@link EnumValueTypeAdapterFactory}.
 *
 * <p>Run with:
 *
 * <pre>{@code
 * java EnumValueDemo
 * }</pre>
 *
 * <p>The demo shows how unknown enum values from a remote API are preserved instead of silently
 * becoming {@code null}, how known values are read via {@link SerializedName} and alternates and
 * written with their canonical name, and how JSON {@code null} is represented separately.
 */
public final class EnumValueDemo {

  private enum Color {
    @SerializedName("red")
    RED,
    @SerializedName(value = "blue", alternate = {"navy", "azure"})
    BLUE,
    GREEN
  }

  /** A remote payload that may contain a value this client does not know yet. */
  private static class RemotePayload {
    EnumValue<Color> color;
    EnumValue<Color> accent;
    EnumValue<Color> missing;
    List<EnumValue<Color>> palette;
  }

  public static void main(String[] args) {
    // 1. Register the factory. It is not registered by default, so plain enums keep their
    //    existing behavior.
    Gson gson =
        new GsonBuilder()
            .registerTypeAdapterFactory(new EnumValueTypeAdapterFactory())
            .serializeNulls()
            .create();

    // 2. Simulate a payload from a server that has added a new enum constant ("crimson").
    String json =
        "{\"color\":\"red\",\"accent\":\"crimson\",\"missing\":null,"
            + "\"palette\":[\"RED\",\"navy\",\"crimson\",null]}";

    // 3. Deserialize. Unknown values are preserved; null is represented separately.
    RemotePayload payload = gson.fromJson(json, RemotePayload.class);

    System.out.println("color (known):    " + describe(payload.color));
    System.out.println("accent (unknown): " + describe(payload.accent));
    System.out.println("missing (null):   " + describe(payload.missing));
    System.out.println("palette:");
    for (EnumValue<Color> c : payload.palette) {
      System.out.println("  " + describe(c));
    }

    // 4. Round trip. Known values are written with their canonical name; unknown strings are
    //    preserved; null stays null.
    String roundTripped = gson.toJson(payload);
    System.out.println("round-tripped:    " + roundTripped);

    // 5. Build a payload locally and serialize it.
    RemotePayload local = new RemotePayload();
    local.color = EnumValue.of(Color.GREEN);
    local.accent = EnumValue.unknown("future");
    local.missing = EnumValue.ofNull();
    local.palette = new ArrayList<>();
    local.palette.add(EnumValue.of(Color.RED));
    local.palette.add(EnumValue.unknown("future"));
    local.palette.add(null);
    String localJson = gson.toJson(local);
    System.out.println("local:            " + localJson);
  }

  private static String describe(EnumValue<Color> value) {
    if (value == null) {
      return "Java null field";
    }
    if (value.isKnown()) {
      return "known constant " + value.getValue().name();
    }
    if (value.isUnknown()) {
      return "unknown string \"" + value.getRawValue() + "\"";
    }
    return "JSON null (distinct from unknown)";
  }
}
