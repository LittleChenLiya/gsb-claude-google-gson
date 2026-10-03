# EnumValue demo

Standalone example for the forward-compatible enum wrapper
`com.google.gson.enums.EnumValue` and its
`EnumValueTypeAdapterFactory`.

## What it shows

- Strings matching `@SerializedName` value/alternate (or the declared constant
  name) become known constants and are written back with the canonical name.
- Unknown strings sent by a newer producer (e.g. `"magenta"`) are preserved
  verbatim instead of silently becoming `null`.
- Explicit JSON `null` is represented separately and stays different from the
  literal string `"null"`.
- Numbers and booleans are rejected instead of being coerced into enum strings.
- The enum's user-defined `toString()` is never used for serialization.
- `List<EnumValue<Color>>` round-trips completely.

## Register the factory

The factory is opt-in; plain enum fields keep Gson's default behavior:

```java
Gson gson =
    new GsonBuilder()
        .registerTypeAdapterFactory(new EnumValueTypeAdapterFactory())
        .create();
```

Then declare properties as `EnumValue<MyEnum>`, also inside generic collections:

```java
class Palette {
  EnumValue<Color> primary;
  List<EnumValue<Color>> all;
}
```

## Compile and run

From the repository root, first compile the Gson module:

```bash
mvn -o -pl gson compile
```

Then compile and run this demo:

```bash
# Windows
javac -cp gson/target/classes -d examples/EnumValueDemo/out ^
    examples/EnumValueDemo/EnumValueDemo.java
java -cp "gson/target/classes;examples/EnumValueDemo/out" EnumValueDemo
```

```bash
# Linux / macOS
javac -cp gson/target/classes -d examples/EnumValueDemo/out \
    examples/EnumValueDemo/EnumValueDemo.java
java -cp "gson/target/classes:examples/EnumValueDemo/out" EnumValueDemo
```
