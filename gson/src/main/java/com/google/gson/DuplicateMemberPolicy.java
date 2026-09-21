package com.google.gson;

import com.google.gson.stream.JsonReader;

/**
 * Policy that controls whether a {@linkplain JsonReader JSON reader} rejects JSON objects which
 * contain multiple members with the same name.
 *
 * <p>The JSON specification (<a href="https://www.ietf.org/rfc/rfc8259.txt">RFC 8259</a>) states
 * that the names within an object <i>should</i> be unique, but it does not require implementations
 * to enforce this. By default Gson keeps its historic behavior and accepts duplicate names; how
 * they are handled then depends on the type being deserialized (for example, for {@link
 * java.util.Map} and POJOs the last occurrence usually wins). Setting this policy to {@link
 * #REJECT} instead makes the reader fail with a {@link
 * com.google.gson.stream.MalformedJsonException} as soon as a duplicate member name is read.
 *
 * <p>The comparison is performed on the decoded member names, so for example the member name {@code
 * "a"} and its Unicode-escaped form {@code "\u0061"} are considered equal. The check is performed
 * per object scope: a nested object may reuse names of its enclosing object, and separate objects
 * (for example the elements of a JSON array) don't share member name state.
 *
 * <p>Note that this policy only applies to the member names appearing in the JSON document. It does
 * not detect when two distinct member names are mapped to the same Java field, for example through
 * {@link com.google.gson.annotations.SerializedName} aliases, and it does not change how {@code
 * TypeAdapter}s handle equal keys they have already read.
 *
 * @see GsonBuilder#setDuplicateMemberPolicy(DuplicateMemberPolicy)
 * @see JsonReader#setDuplicateMemberPolicy(DuplicateMemberPolicy)
 * @since 2.14.1
 */
public enum DuplicateMemberPolicy {
  /**
   * Accepts objects with duplicate member names. This is the default and matches the historic
   * behavior of Gson; how duplicates are handled depends on the target type being deserialized.
   */
  ALLOW,

  /**
   * Rejects objects with duplicate member names by throwing a {@link
   * com.google.gson.stream.MalformedJsonException} when the second occurrence of a member name is
   * read, before its value is consumed.
   */
  REJECT
}
