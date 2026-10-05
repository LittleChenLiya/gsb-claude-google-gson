/*
 * Copyright (C) 2011 Google Inc.
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

import com.google.gson.FieldNamingStrategy;
import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.ReflectionAccessFilter;
import com.google.gson.ReflectionAccessFilter.FilterResult;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.internal.ConstructorConstructor;
import com.google.gson.internal.Excluder;
import com.google.gson.internal.GsonTypes;
import com.google.gson.internal.ObjectConstructor;
import com.google.gson.internal.Primitives;
import com.google.gson.internal.ReflectionAccessFilterHelper;
import com.google.gson.internal.TroubleshootingGuide;
import com.google.gson.internal.reflect.ReflectionHelper;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Type adapter that reflects over the fields and methods of a class. */
public final class ReflectiveTypeAdapterFactory implements TypeAdapterFactory {
  private final ConstructorConstructor constructorConstructor;
  private final FieldNamingStrategy fieldNamingPolicy;
  private final Excluder excluder;
  private final JsonAdapterAnnotationTypeAdapterFactory jsonAdapterFactory;
  private final List<ReflectionAccessFilter> reflectionFilters;

  public ReflectiveTypeAdapterFactory(
      ConstructorConstructor constructorConstructor,
      FieldNamingStrategy fieldNamingPolicy,
      Excluder excluder,
      JsonAdapterAnnotationTypeAdapterFactory jsonAdapterFactory,
      List<ReflectionAccessFilter> reflectionFilters) {
    this.constructorConstructor = constructorConstructor;
    this.fieldNamingPolicy = fieldNamingPolicy;
    this.excluder = excluder;
    this.jsonAdapterFactory = jsonAdapterFactory;
    this.reflectionFilters = reflectionFilters;
  }

  private boolean includeField(Field f, boolean serialize) {
    return !excluder.excludeField(f, serialize);
  }

  /** first element holds the default name */
  @SuppressWarnings("MixedMutabilityReturnType")
  private List<String> getFieldNames(Field f) {

    String fieldName;
    List<String> alternates;
    SerializedName annotation = f.getAnnotation(SerializedName.class);
    if (annotation == null) {
      fieldName = fieldNamingPolicy.translateName(f);
      alternates = fieldNamingPolicy.alternateNames(f);
    } else {
      fieldName = annotation.value();
      alternates = Arrays.asList(annotation.alternate());
    }

    if (alternates.isEmpty()) {
      return Collections.singletonList(fieldName);
    }

    List<String> fieldNames = new ArrayList<>(alternates.size() + 1);
    fieldNames.add(fieldName);
    fieldNames.addAll(alternates);
    return fieldNames;
  }

  /**
   * Performs an explicit precheck of the JSON member names that the reflective adapter for the
   * given type would use, and reports all name conflicts without creating any adapter, without
   * creating instances of the inspected type, without invoking field getters and without reading
   * any JSON.
   *
   * <p>The check walks the class hierarchy the same way adapter creation does, applies the
   * configured {@link Excluder} separately for serialization and deserialization (fields excluded
   * for a direction do not participate in that direction's name set), resolves generic field types
   * along the given {@link TypeToken} and computes the effective JSON names using the configured
   * {@link FieldNamingStrategy} and {@link SerializedName} annotations. For deserialization all
   * names are considered, including {@link SerializedName#alternate() @SerializedName alternates}
   * and {@link FieldNamingStrategy#alternateNames(Field)}.
   *
   * <p>Conflicts caused by inherited fields, naming strategies or alternate names are reported
   * separately for each direction, so they can be detected up front (for example at application
   * startup) instead of when the first JSON document is read or written.
   *
   * <p>This method is purely diagnostic; the behavior of the adapters created by this factory is
   * unchanged.
   *
   * @param type the type to inspect, possibly parameterized; generic field types are resolved
   *     against it
   * @return a report with the effective serialized and deserialized name sets and all detected
   *     conflicts; never {@code null}
   */
  public FieldNamingConflictReport checkFieldNamingConflicts(TypeToken<?> type) {
    Class<?> raw = type.getRawType();
    Class<?> originalRaw = raw;

    // Mirror create(): for these types no fields are bound, so there is nothing to check
    if (!Object.class.isAssignableFrom(raw)
        || raw.isInterface()
        || ReflectionHelper.isAnonymousOrNonStaticLocal(raw)) {
      return FieldNamingConflictReport.empty(originalRaw);
    }

    FilterResult filterResult =
        ReflectionAccessFilterHelper.getFilterResult(reflectionFilters, raw);
    if (filterResult == FilterResult.BLOCK_ALL) {
      throw new JsonIOException(
          "ReflectionAccessFilter does not permit using reflection for "
              + raw
              + ". Register a TypeAdapter for this type or adjust the access filter.");
    }

    boolean isRecord = ReflectionHelper.isRecord(raw);
    // Maps from JSON member name to the fields using that name, in class-hierarchy order
    Map<String, List<Field>> serializedNames = new LinkedHashMap<>();
    Map<String, List<Field>> deserializedNames = new LinkedHashMap<>();
    Map<Field, Type> resolvedFieldTypes = new LinkedHashMap<>();

    while (raw != Object.class) {
      Field[] fields = raw.getDeclaredFields();

      // For inherited fields, check if access to their declaring class is allowed
      if (raw != originalRaw && fields.length > 0) {
        FilterResult superFilterResult =
            ReflectionAccessFilterHelper.getFilterResult(reflectionFilters, raw);
        if (superFilterResult == FilterResult.BLOCK_ALL) {
          throw new JsonIOException(
              "ReflectionAccessFilter does not permit using reflection for "
                  + raw
                  + " (supertype of "
                  + originalRaw
                  + "). Register a TypeAdapter for this type or adjust the access filter.");
        }
      }

      for (Field field : fields) {
        boolean serialize = includeField(field, true);
        boolean deserialize = includeField(field, false);
        if (isRecord && Modifier.isStatic(field.getModifiers())) {
          // Mirror getBoundFields: static record fields are not deserialized
          deserialize = false;
        }
        if (!serialize && !deserialize) {
          continue; // field does not participate in either direction
        }
        // Resolve the generic field type along the TypeToken, the same way adapter creation does
        Type fieldType = GsonTypes.resolve(type.getType(), raw, field.getGenericType());
        resolvedFieldTypes.put(field, fieldType);

        List<String> fieldNames = getFieldNames(field);
        if (serialize) {
          addFieldName(serializedNames, fieldNames.get(0), field);
        }
        if (deserialize) {
          // All names, including @SerializedName alternates, are matched when reading JSON
          for (String name : fieldNames) {
            addFieldName(deserializedNames, name, field);
          }
        }
      }
      type = TypeToken.get(GsonTypes.resolve(type.getType(), raw, raw.getGenericSuperclass()));
      raw = type.getRawType();
    }
    return new FieldNamingConflictReport(
        originalRaw, serializedNames, deserializedNames, resolvedFieldTypes);
  }

  private static void addFieldName(Map<String, List<Field>> names, String name, Field field) {
    names.computeIfAbsent(name, unused -> new ArrayList<>()).add(field);
  }

  @Override
  public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
    Class<? super T> raw = type.getRawType();

    if (!Object.class.isAssignableFrom(raw)) {
      return null; // it's a primitive!
    }

    // Don't allow using reflection on anonymous and local classes because synthetic fields for
    // captured enclosing values make this unreliable
    if (ReflectionHelper.isAnonymousOrNonStaticLocal(raw)) {
      // This adapter just serializes and deserializes null, ignoring the actual values
      // This is done for backward compatibility; troubleshooting-wise it might be better to throw
      // exceptions
      return new TypeAdapter<T>() {
        @Override
        public T read(JsonReader in) throws IOException {
          in.skipValue();
          return null;
        }

        @Override
        public void write(JsonWriter out, T value) throws IOException {
          out.nullValue();
        }

        @Override
        public String toString() {
          return "AnonymousOrNonStaticLocalClassAdapter";
        }
      };
    }

    FilterResult filterResult =
        ReflectionAccessFilterHelper.getFilterResult(reflectionFilters, raw);
    if (filterResult == FilterResult.BLOCK_ALL) {
      throw new JsonIOException(
          "ReflectionAccessFilter does not permit using reflection for "
              + raw
              + ". Register a TypeAdapter for this type or adjust the access filter.");
    }
    boolean blockInaccessible = filterResult == FilterResult.BLOCK_INACCESSIBLE;

    // If the type is actually a Java Record, we need to use the RecordAdapter instead. This will
    // always be false on JVMs that do not support records.
    if (ReflectionHelper.isRecord(raw)) {
      @SuppressWarnings("unchecked")
      TypeAdapter<T> adapter =
          (TypeAdapter<T>)
              new RecordAdapter<>(
                  raw, getBoundFields(gson, type, raw, blockInaccessible, true), blockInaccessible);
      return adapter;
    }

    ObjectConstructor<T> constructor = constructorConstructor.get(type, true);
    return new FieldReflectionAdapter<>(
        constructor, getBoundFields(gson, type, raw, blockInaccessible, false));
  }

  private static <M extends AccessibleObject & Member> void checkAccessible(
      Object object, M member) {
    if (!ReflectionAccessFilterHelper.canAccess(
        member, Modifier.isStatic(member.getModifiers()) ? null : object)) {
      String memberDescription = ReflectionHelper.getAccessibleObjectDescription(member, true);
      throw new JsonIOException(
          memberDescription
              + " is not accessible and ReflectionAccessFilter does not permit making it"
              + " accessible. Register a TypeAdapter for the declaring type, adjust the access"
              + " filter or increase the visibility of the element and its declaring type.");
    }
  }

  private BoundField createBoundField(
      Gson context,
      Field field,
      Method accessor,
      String serializedName,
      TypeToken<?> fieldType,
      boolean serialize,
      boolean blockInaccessible) {

    boolean isPrimitive = Primitives.isPrimitive(fieldType.getRawType());

    int modifiers = field.getModifiers();
    boolean isStaticFinalField = Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers);

    JsonAdapter annotation = field.getAnnotation(JsonAdapter.class);
    TypeAdapter<?> mapped = null;
    if (annotation != null) {
      // This is not safe; requires that user has specified correct adapter class for @JsonAdapter
      mapped =
          jsonAdapterFactory.getTypeAdapter(
              constructorConstructor, context, fieldType, annotation, false);
    }
    boolean jsonAdapterPresent = mapped != null;
    if (mapped == null) {
      mapped = context.getAdapter(fieldType);
    }

    @SuppressWarnings("unchecked")
    TypeAdapter<Object> typeAdapter = (TypeAdapter<Object>) mapped;
    TypeAdapter<Object> writeTypeAdapter;
    if (serialize) {
      writeTypeAdapter =
          jsonAdapterPresent
              ? typeAdapter
              : new TypeAdapterRuntimeTypeWrapper<>(context, typeAdapter, fieldType.getType());
    } else {
      // Will never actually be used, but we set it to avoid confusing nullness-analysis tools
      writeTypeAdapter = typeAdapter;
    }
    return new BoundField(serializedName, field) {
      /**
       * Whether the accessibility check has already passed. The result depends only on the member
       * and the {@code ReflectionAccessFilter}s of the {@code Gson} instance, not on the object
       * being serialized or deserialized, so once the check passes it will pass for every later
       * call and the reflective check can be skipped. A single field suffices because {@code
       * accessor} is fixed for this instance, and it is non-null only for records, which are
       * deserialized via {@link #readIntoArray} rather than {@link #readIntoField}.
       *
       * <p>Deliberately not {@code volatile}: under concurrent use a thread may observe a stale
       * {@code false} and redo the check, but the check is idempotent and always produces the same
       * result for a given member, so the race is benign. Do not cache a negative result here; that
       * would make the race unsafe.
       */
      private boolean knownAccessible;

      @Override
      void write(JsonWriter writer, Object source) throws IOException, IllegalAccessException {
        if (blockInaccessible) {
          if (!knownAccessible) {
            if (accessor == null) {
              checkAccessible(source, field);
            } else {
              // Note: This check might actually be redundant because access check for canonical
              // constructor should have failed already
              checkAccessible(source, accessor);
            }
            knownAccessible = true;
          }
        }

        Object fieldValue;
        if (accessor != null) {
          try {
            fieldValue = accessor.invoke(source);
          } catch (InvocationTargetException e) {
            String accessorDescription =
                ReflectionHelper.getAccessibleObjectDescription(accessor, false);
            throw new JsonIOException(
                "Accessor " + accessorDescription + " threw exception", e.getCause());
          }
        } else {
          fieldValue = field.get(source);
        }

        @SuppressWarnings("ReferenceEquality")
        boolean isSameObject = fieldValue == source;
        if (isSameObject) {
          // avoid direct recursion
          return;
        }
        writer.name(serializedName);
        writeTypeAdapter.write(writer, fieldValue);
      }

      @Override
      void readIntoArray(JsonReader reader, int index, Object[] target)
          throws IOException, JsonParseException {
        Object fieldValue = typeAdapter.read(reader);
        if (fieldValue == null && isPrimitive) {
          throw new JsonParseException(
              "null is not allowed as value for record component '"
                  + fieldName
                  + "' of primitive type; at path "
                  + reader.getPath());
        }
        target[index] = fieldValue;
      }

      @Override
      void readIntoField(JsonReader reader, Object target)
          throws IOException, IllegalAccessException {
        Object fieldValue = typeAdapter.read(reader);
        if (fieldValue != null || !isPrimitive) {
          if (blockInaccessible) {
            if (!knownAccessible) {
              checkAccessible(target, field);
              knownAccessible = true;
            }
          } else if (isStaticFinalField) {
            // Reflection does not permit setting value of `static final` field, even after calling
            // `setAccessible`
            // Handle this here to avoid causing IllegalAccessException when calling `Field.set`
            String fieldDescription = ReflectionHelper.getAccessibleObjectDescription(field, false);
            throw new JsonIOException("Cannot set value of 'static final' " + fieldDescription);
          }
          field.set(target, fieldValue);
        }
      }
    };
  }

  private static class FieldsData {
    static final FieldsData EMPTY = new FieldsData(Collections.emptyMap(), Collections.emptyList());

    /** Maps from JSON member name to field */
    final Map<String, BoundField> deserializedFields;

    final List<BoundField> serializedFields;

    FieldsData(Map<String, BoundField> deserializedFields, List<BoundField> serializedFields) {
      this.deserializedFields = deserializedFields;
      this.serializedFields = serializedFields;
    }
  }

  private static IllegalArgumentException createDuplicateFieldException(
      Class<?> declaringType, String duplicateName, Field field1, Field field2) {
    throw new IllegalArgumentException(
        "Class "
            + declaringType.getName()
            + " declares multiple JSON fields named '"
            + duplicateName
            + "'; conflict is caused by fields "
            + ReflectionHelper.fieldToString(field1)
            + " and "
            + ReflectionHelper.fieldToString(field2)
            + "\nSee "
            + TroubleshootingGuide.createUrl("duplicate-fields"));
  }

  /**
   * Result of {@link #checkFieldNamingConflicts(TypeToken)}: the effective JSON member names of a
   * type for serialization and deserialization, and all conflicting names, reported separately per
   * direction.
   */
  public static final class FieldNamingConflictReport {
    /** Direction in which a JSON member name is used by the reflective adapter. */
    public enum Direction {
      /** Names written to the JSON output during serialization. */
      SERIALIZE,
      /**
       * Names matched against the JSON input during deserialization, including {@link
       * SerializedName#alternate() @SerializedName alternates}.
       */
      DESERIALIZE
    }

    /** A JSON member name that more than one field maps to for the same direction. */
    public static final class Conflict {
      private final Class<?> inspectedType;
      private final Direction direction;
      private final String jsonName;
      private final List<Field> fields;

      private Conflict(
          Class<?> inspectedType, Direction direction, String jsonName, List<Field> fields) {
        this.inspectedType = inspectedType;
        this.direction = direction;
        this.jsonName = jsonName;
        this.fields = Collections.unmodifiableList(new ArrayList<>(fields));
      }

      /** The type that was inspected when this conflict was detected. */
      public Class<?> inspectedType() {
        return inspectedType;
      }

      /** The direction for which the name collides. */
      public Direction direction() {
        return direction;
      }

      /** The colliding JSON member name. */
      public String jsonName() {
        return jsonName;
      }

      /**
       * All fields (including inherited ones) mapping to {@link #jsonName()}, in class-hierarchy
       * order, starting with the most specific class.
       */
      public List<Field> fields() {
        return fields;
      }

      @Override
      public String toString() {
        StringBuilder message = new StringBuilder();
        message
            .append("Class ")
            .append(inspectedType.getName())
            .append(" declares multiple JSON fields named '")
            .append(jsonName)
            .append("' for ")
            .append(direction == Direction.SERIALIZE ? "serialization" : "deserialization")
            .append("; conflict is caused by fields ");
        for (int i = 0; i < fields.size(); i++) {
          if (i > 0) {
            message.append(" and ");
          }
          message.append(ReflectionHelper.fieldToString(fields.get(i)));
        }
        return message.toString();
      }
    }

    private final Class<?> inspectedType;
    private final Set<String> serializedNames;
    private final Set<String> deserializedNames;
    private final Map<Field, Type> resolvedFieldTypes;
    private final List<Conflict> conflicts;

    private FieldNamingConflictReport(
        Class<?> inspectedType,
        Map<String, List<Field>> serializedNames,
        Map<String, List<Field>> deserializedNames,
        Map<Field, Type> resolvedFieldTypes) {
      this.inspectedType = inspectedType;
      this.serializedNames = Collections.unmodifiableSet(toNameSet(serializedNames));
      this.deserializedNames = Collections.unmodifiableSet(toNameSet(deserializedNames));
      this.resolvedFieldTypes =
          Collections.unmodifiableMap(new LinkedHashMap<>(resolvedFieldTypes));
      List<Conflict> conflicts = new ArrayList<>();
      collectConflicts(conflicts, inspectedType, Direction.SERIALIZE, serializedNames);
      collectConflicts(conflicts, inspectedType, Direction.DESERIALIZE, deserializedNames);
      this.conflicts = Collections.unmodifiableList(conflicts);
    }

    private static FieldNamingConflictReport empty(Class<?> inspectedType) {
      return new FieldNamingConflictReport(
          inspectedType, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
    }

    private static Set<String> toNameSet(Map<String, List<Field>> names) {
      return new LinkedHashSet<>(names.keySet());
    }

    private static void collectConflicts(
        List<Conflict> conflicts,
        Class<?> inspectedType,
        Direction direction,
        Map<String, List<Field>> names) {
      for (Map.Entry<String, List<Field>> entry : names.entrySet()) {
        List<Field> fields = entry.getValue();
        if (fields.size() > 1) {
          conflicts.add(new Conflict(inspectedType, direction, entry.getKey(), fields));
        }
      }
    }

    /** The type that was inspected. */
    public Class<?> inspectedType() {
      return inspectedType;
    }

    /** Whether at least one name conflict was detected, in either direction. */
    public boolean hasConflicts() {
      return !conflicts.isEmpty();
    }

    /** All detected conflicts, for both directions; empty if there are none. */
    public List<Conflict> conflicts() {
      return conflicts;
    }

    /** All detected conflicts for the given direction; empty if there are none. */
    public List<Conflict> conflicts(Direction direction) {
      List<Conflict> result = new ArrayList<>();
      for (Conflict conflict : conflicts) {
        if (conflict.direction() == direction) {
          result.add(conflict);
        }
      }
      return Collections.unmodifiableList(result);
    }

    /** The JSON member names actually written during serialization. */
    public Set<String> serializedNames() {
      return serializedNames;
    }

    /**
     * The JSON member names actually matched during deserialization, including {@code alternate}
     * names.
     */
    public Set<String> deserializedNames() {
      return deserializedNames;
    }

    /**
     * The participating fields and their types resolved along the inspected {@link TypeToken}; for
     * example a field declared as {@code T} in a generic superclass maps to the actual type
     * argument.
     */
    public Map<Field, Type> resolvedFieldTypes() {
      return resolvedFieldTypes;
    }

    /**
     * Throws an {@link IllegalArgumentException} describing all detected conflicts, or does nothing
     * if there are none. Intended to fail fast (for example at application startup) before any JSON
     * is read or written.
     */
    public void throwIfAnyConflict() {
      if (conflicts.isEmpty()) {
        return;
      }
      StringBuilder message = new StringBuilder();
      for (Conflict conflict : conflicts) {
        if (message.length() > 0) {
          message.append('\n');
        }
        message.append(conflict);
      }
      message.append("\nSee ").append(TroubleshootingGuide.createUrl("duplicate-fields"));
      throw new IllegalArgumentException(message.toString());
    }
  }

  private FieldsData getBoundFields(
      Gson context, TypeToken<?> type, Class<?> raw, boolean blockInaccessible, boolean isRecord) {
    if (raw.isInterface()) {
      return FieldsData.EMPTY;
    }

    Map<String, BoundField> deserializedFields = new LinkedHashMap<>();
    // For serialized fields use a Map to track duplicate field names; otherwise this could be a
    // List<BoundField> instead
    Map<String, BoundField> serializedFields = new LinkedHashMap<>();

    Class<?> originalRaw = raw;
    while (raw != Object.class) {
      Field[] fields = raw.getDeclaredFields();

      // For inherited fields, check if access to their declaring class is allowed
      if (raw != originalRaw && fields.length > 0) {
        FilterResult filterResult =
            ReflectionAccessFilterHelper.getFilterResult(reflectionFilters, raw);
        if (filterResult == FilterResult.BLOCK_ALL) {
          throw new JsonIOException(
              "ReflectionAccessFilter does not permit using reflection for "
                  + raw
                  + " (supertype of "
                  + originalRaw
                  + "). Register a TypeAdapter for this type or adjust the access filter.");
        }
        blockInaccessible = filterResult == FilterResult.BLOCK_INACCESSIBLE;
      }

      for (Field field : fields) {
        boolean serialize = includeField(field, true);
        boolean deserialize = includeField(field, false);
        if (!serialize && !deserialize) {
          continue;
        }
        // The accessor method is only used for records. If the type is a record, we will read out
        // values via its accessor method instead of via reflection. This way we will bypass the
        // accessible restrictions
        Method accessor = null;
        if (isRecord) {
          // If there is a static field on a record, there will not be an accessor. Instead we will
          // use the default field serialization logic, but for deserialization the field is
          // excluded for simplicity.
          // Note that Gson ignores static fields by default, but
          // GsonBuilder.excludeFieldsWithModifiers can overwrite this.
          if (Modifier.isStatic(field.getModifiers())) {
            deserialize = false;
          } else {
            accessor = ReflectionHelper.getAccessor(raw, field);
            // If blockInaccessible, skip and perform access check later
            if (!blockInaccessible) {
              ReflectionHelper.makeAccessible(accessor);
            }

            // @SerializedName can be placed on accessor method, but it is not supported there
            // If field and method have annotation it is not easily possible to determine if
            // accessor method is implicit and has inherited annotation, or if it is explicitly
            // declared with custom annotation
            if (accessor.getAnnotation(SerializedName.class) != null
                && field.getAnnotation(SerializedName.class) == null) {
              String methodDescription =
                  ReflectionHelper.getAccessibleObjectDescription(accessor, false);
              throw new JsonIOException(
                  "@SerializedName on " + methodDescription + " is not supported");
            }
          }
        }

        // If blockInaccessible, skip and perform access check later
        // For Records if the accessor method is used the field does not have to be made accessible
        if (!blockInaccessible && accessor == null) {
          ReflectionHelper.makeAccessible(field);
        }

        Type fieldType = GsonTypes.resolve(type.getType(), raw, field.getGenericType());
        List<String> fieldNames = getFieldNames(field);
        String serializedName = fieldNames.get(0);
        BoundField boundField =
            createBoundField(
                context,
                field,
                accessor,
                serializedName,
                TypeToken.get(fieldType),
                serialize,
                blockInaccessible);

        if (deserialize) {
          for (String name : fieldNames) {
            BoundField replaced = deserializedFields.put(name, boundField);

            if (replaced != null) {
              throw createDuplicateFieldException(originalRaw, name, replaced.field, field);
            }
          }
        }

        if (serialize) {
          BoundField replaced = serializedFields.put(serializedName, boundField);
          if (replaced != null) {
            throw createDuplicateFieldException(originalRaw, serializedName, replaced.field, field);
          }
        }
      }
      type = TypeToken.get(GsonTypes.resolve(type.getType(), raw, raw.getGenericSuperclass()));
      raw = type.getRawType();
    }
    return new FieldsData(deserializedFields, new ArrayList<>(serializedFields.values()));
  }

  abstract static class BoundField {
    /** Name used for serialization (but not for deserialization) */
    final String serializedName;

    final Field field;

    /** Name of the underlying field */
    final String fieldName;

    protected BoundField(String serializedName, Field field) {
      this.serializedName = serializedName;
      this.field = field;
      this.fieldName = field.getName();
    }

    /** Read this field value from the source, and append its JSON value to the writer */
    abstract void write(JsonWriter writer, Object source)
        throws IOException, IllegalAccessException;

    /** Read the value into the target array, used to provide constructor arguments for records */
    abstract void readIntoArray(JsonReader reader, int index, Object[] target)
        throws IOException, JsonParseException;

    /**
     * Read the value from the reader, and set it on the corresponding field on target via
     * reflection
     */
    abstract void readIntoField(JsonReader reader, Object target)
        throws IOException, IllegalAccessException;
  }

  /**
   * Base class for Adapters produced by this factory.
   *
   * <p>The {@link RecordAdapter} is a special case to handle records for JVMs that support it, for
   * all other types we use the {@link FieldReflectionAdapter}. This class encapsulates the common
   * logic for serialization and deserialization. During deserialization, we construct an
   * accumulator A, which we use to accumulate values from the source JSON. After the object has
   * been read in full, the {@link #finalize(Object)} method is used to convert the accumulator to
   * an instance of T.
   *
   * @param <T> type of objects that this Adapter creates.
   * @param <A> type of accumulator used to build the deserialization result.
   */
  // This class is public because external projects check for this class with `instanceof` (even
  // though it is internal)
  public abstract static class Adapter<T, A> extends TypeAdapter<T> {
    private final FieldsData fieldsData;

    private Adapter(FieldsData fieldsData) {
      this.fieldsData = fieldsData;
    }

    @Override
    public void write(JsonWriter out, T value) throws IOException {
      if (value == null) {
        out.nullValue();
        return;
      }

      out.beginObject();
      try {
        for (BoundField boundField : fieldsData.serializedFields) {
          boundField.write(out, value);
        }
      } catch (IllegalAccessException e) {
        throw ReflectionHelper.createExceptionForUnexpectedIllegalAccess(e);
      }
      out.endObject();
    }

    @Override
    public T read(JsonReader in) throws IOException {
      if (in.peek() == JsonToken.NULL) {
        in.nextNull();
        return null;
      }

      A accumulator = createAccumulator();
      Map<String, BoundField> deserializedFields = fieldsData.deserializedFields;

      try {
        in.beginObject();
        while (in.hasNext()) {
          String name = in.nextName();
          BoundField field = deserializedFields.get(name);
          if (field == null) {
            in.skipValue();
          } else {
            readField(accumulator, in, field);
          }
        }
      } catch (IllegalStateException e) {
        throw new JsonSyntaxException(e);
      } catch (IllegalAccessException e) {
        throw ReflectionHelper.createExceptionForUnexpectedIllegalAccess(e);
      }
      in.endObject();
      return finalize(accumulator);
    }

    /** Create the Object that will be used to collect each field value */
    abstract A createAccumulator();

    /**
     * Read a single BoundField into the accumulator. The JsonReader will be pointed at the start of
     * the value for the BoundField to read from.
     */
    abstract void readField(A accumulator, JsonReader in, BoundField field)
        throws IllegalAccessException, IOException;

    /** Convert the accumulator to a final instance of T. */
    abstract T finalize(A accumulator);
  }

  private static final class FieldReflectionAdapter<T> extends Adapter<T, T> {
    private final ObjectConstructor<T> constructor;

    FieldReflectionAdapter(ObjectConstructor<T> constructor, FieldsData fieldsData) {
      super(fieldsData);
      this.constructor = constructor;
    }

    @Override
    T createAccumulator() {
      return constructor.construct();
    }

    @Override
    void readField(T accumulator, JsonReader in, BoundField field)
        throws IllegalAccessException, IOException {
      field.readIntoField(in, accumulator);
    }

    @Override
    T finalize(T accumulator) {
      return accumulator;
    }
  }

  private static final class RecordAdapter<T> extends Adapter<T, Object[]> {
    static final Map<Class<?>, Object> PRIMITIVE_DEFAULTS = primitiveDefaults();

    // The canonical constructor of the record
    private final Constructor<T> constructor;
    // Array of arguments to the constructor, initialized with default values for primitives
    private final Object[] constructorArgsDefaults;
    // Map from component names to index into the constructors arguments.
    private final Map<String, Integer> componentIndices = new HashMap<>();

    RecordAdapter(Class<T> raw, FieldsData fieldsData, boolean blockInaccessible) {
      super(fieldsData);
      constructor = ReflectionHelper.getCanonicalRecordConstructor(raw);

      if (blockInaccessible) {
        checkAccessible(null, constructor);
      } else {
        // Ensure the constructor is accessible
        ReflectionHelper.makeAccessible(constructor);
      }

      String[] componentNames = ReflectionHelper.getRecordComponentNames(raw);
      for (int i = 0; i < componentNames.length; i++) {
        componentIndices.put(componentNames[i], i);
      }
      Class<?>[] parameterTypes = constructor.getParameterTypes();

      // We need to ensure that we are passing non-null values to primitive fields in the
      // constructor. To do this, we create an Object[] where all primitives are initialized to
      // non-null values.
      constructorArgsDefaults = new Object[parameterTypes.length];
      for (int i = 0; i < parameterTypes.length; i++) {
        // This will correctly be null for non-primitive types:
        constructorArgsDefaults[i] = PRIMITIVE_DEFAULTS.get(parameterTypes[i]);
      }
    }

    private static Map<Class<?>, Object> primitiveDefaults() {
      Map<Class<?>, Object> zeroes = new HashMap<>();
      zeroes.put(byte.class, (byte) 0);
      zeroes.put(short.class, (short) 0);
      zeroes.put(int.class, 0);
      zeroes.put(long.class, 0L);
      zeroes.put(float.class, 0F);
      zeroes.put(double.class, 0D);
      zeroes.put(char.class, '\0');
      zeroes.put(boolean.class, false);
      return zeroes;
    }

    @Override
    Object[] createAccumulator() {
      return constructorArgsDefaults.clone();
    }

    @Override
    void readField(Object[] accumulator, JsonReader in, BoundField field) throws IOException {
      // Obtain the component index from the name of the field backing it
      Integer componentIndex = componentIndices.get(field.fieldName);
      if (componentIndex == null) {
        throw new IllegalStateException(
            "Could not find the index in the constructor '"
                + ReflectionHelper.constructorToString(constructor)
                + "' for field with name '"
                + field.fieldName
                + "', unable to determine which argument in the constructor the field corresponds"
                + " to. This is unexpected behavior, as we expect the RecordComponents to have the"
                + " same names as the fields in the Java class, and that the order of the"
                + " RecordComponents is the same as the order of the canonical constructor"
                + " parameters.");
      }
      field.readIntoArray(in, componentIndex, accumulator);
    }

    @Override
    T finalize(Object[] accumulator) {
      try {
        return constructor.newInstance(accumulator);
      } catch (IllegalAccessException e) {
        throw ReflectionHelper.createExceptionForUnexpectedIllegalAccess(e);
      }
      // Note: InstantiationException should be impossible because record class is not abstract;
      //  IllegalArgumentException should not be possible unless a bad adapter returns objects of
      //  the wrong type
      catch (InstantiationException | IllegalArgumentException e) {
        throw new RuntimeException(
            "Failed to invoke constructor '"
                + ReflectionHelper.constructorToString(constructor)
                + "' with args "
                + Arrays.toString(accumulator),
            e);
      } catch (InvocationTargetException e) {
        // TODO: JsonParseException ?
        throw new RuntimeException(
            "Failed to invoke constructor '"
                + ReflectionHelper.constructorToString(constructor)
                + "' with args "
                + Arrays.toString(accumulator),
            e.getCause());
      }
    }
  }
}
