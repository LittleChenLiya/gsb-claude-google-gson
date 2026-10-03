import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.google.gson.enums.EnumValue;
import com.google.gson.enums.EnumValueTypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.List;

/**
 * Standalone demonstration for the forward-compatible {@link EnumValue} wrapper.
 *
 * <p>Compile and run (from the repository root, after {@code mvn -o -pl gson package -DskipTests}
 * or {@code mvn -o -pl gson compile}):
 *
 * <pre>{@code
 * javac -cp gson/target/classes -d examples/EnumValueDemo/out \
 *     examples/EnumValueDemo/EnumValueDemo.java
 * java -cp gson/target/classes;examples/EnumValueDemo/out EnumValueDemo
 * }</pre>
 *
 * <p>On Linux/macOS use ':' instead of ';' as the classpath separator.
 */
public class EnumValueDemo {
  enum Color {
    @SerializedName(value = "red", alternate = {"crimson", "rouge"})
    RED,
    GREEN,
    BLUE
  }

  public static void main(String[] args) {
    // Registration: the factory is opt-in and does not change plain enum behavior.
    Gson gson =
        new GsonBuilder()
            .registerTypeAdapterFactory(new EnumValueTypeAdapterFactory())
            .setPrettyPrinting()
            .create();

    Type listType = new TypeToken<List<EnumValue<Color>>>() {}.getType();

    // Simulated payload from a newer client which added "magenta";
    // it also uses the "crimson" alias and an explicit null.
    String incoming = "[\"red\",\"crimson\",\"magenta\",null,\"BLUE\"]";

    List<EnumValue<Color>> colors = gson.fromJson(incoming, listType);

    System.out.println("Parsed values:");
    for (EnumValue<Color> value : colors) {
      if (value.isKnown()) {
        System.out.println("  known constant : " + value.getConstant().name());
      } else if (value.isUnknown()) {
        System.out.println("  unknown string : " + value.getRawValue() + " (preserved)");
      } else {
        System.out.println("  explicit null  : JSON null");
      }
    }

    System.out.println();
    System.out.println("Re-serialized (alias -> canonical, unknown string kept):");
    System.out.println(gson.toJson(colors, listType));

    // Explicit JSON null and the literal string "null" are distinguishable.
    System.out.println();
    Type singleType = new TypeToken<EnumValue<Color>>() {}.getType();
    EnumValue<Color> jsonNull = gson.fromJson("null", singleType);
    EnumValue<Color> stringNull = gson.fromJson("\"null\"", singleType);
    System.out.println("fromJson(null).isNull()       = " + jsonNull.isNull());
    System.out.println("fromJson(\"null\").isUnknown() = " + stringNull.isUnknown());

    // Numbers are rejected instead of being silently coerced to enum strings.
    try {
      gson.fromJson("42", singleType);
      throw new AssertionError("expected JsonSyntaxException");
    } catch (com.google.gson.JsonSyntaxException e) {
      System.out.println();
      System.out.println("Number 42 rejected: " + e.getMessage());
    }
  }
}