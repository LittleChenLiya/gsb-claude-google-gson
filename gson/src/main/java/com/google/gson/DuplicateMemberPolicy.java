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

import com.google.gson.stream.JsonReader;

/**
 * Policy for handling duplicate member names within a JSON object while reading.
 *
 * <p>By default Gson does not reject duplicate member names; instead every {@link
 * com.google.gson.TypeAdapter} decides on its own how to handle them. For example, when
 * deserializing to a {@link java.util.Map} or a {@link JsonObject} the last occurrence of a member
 * silently overwrites previous occurrences. Setting this policy to {@link #REJECT} provides a
 * consistent syntax-level rejection instead, independent of the target type being deserialized.
 *
 * <p>The check is performed by {@link JsonReader} at the syntax level, based on the decoded member
 * names. For example the member name {@code "a"} and its Unicode escape form are considered equal.
 * check is scoped to a single JSON object: nested objects may reuse member names of enclosing
 * objects, and separate objects within the same array do not affect each other.
 *
 * <p>This policy only compares the raw JSON member names. It does not consider how member names are
 * mapped to Java fields, so two distinct member names which are both mapped to the same Java field
 * (for example through {@link com.google.gson.annotations.SerializedName} alternates) are not
 * considered duplicates. Likewise it does not change the equivalence checks performed by {@code
 * Map} key adapters on the deserialized key values.
 *
 * @see GsonBuilder#setDuplicateMemberPolicy(DuplicateMemberPolicy)
 * @see JsonReader#setDuplicateMemberPolicy(DuplicateMemberPolicy)
 */
public enum DuplicateMemberPolicy {
  /**
   * Duplicate member names within a JSON object are allowed. How they are handled depends on the
   * {@link com.google.gson.TypeAdapter} which processes the JSON data; for most adapters a later
   * occurrence of a member overwrites the previous one.
   *
   * <p>This is the default and preserves the historic behavior of Gson.
   */
  ALLOW,

  /**
   * Duplicate member names within a JSON object are rejected. When {@link JsonReader} reads a
   * member name which already occurred in the current JSON object, it immediately throws a {@link
   * com.google.gson.stream.MalformedJsonException} whose message contains the member path and the
   * location in the JSON document, before the corresponding member value is consumed.
   *
   * <p>The check applies to all member names read by the reader, including names consumed through
   * {@link JsonReader#nextName()}, names consumed as {@code Map} keys, and names inside JSON
   * objects skipped with {@link JsonReader#skipValue()}.
   *
   * <p>After this exception has been thrown the reader can still be {@linkplain JsonReader#close()
   * closed}, but no guarantees are made about continuing to read from it.
   */
  REJECT
}
