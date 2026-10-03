import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.internal.bind.CheckedMapTypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small demo of {@link CheckedMapTypeAdapterFactory}: run with the Gson classes on the class
 * path, e.g. {@code java -cp <gson-classes>;../demo CheckedMapDemo} after {@code javac}.
 */
public final class CheckedMapDemo {
  public static void main(String[] args) {
    Type mapType = new TypeToken<LinkedHashMap<Object, String>>() {}.getType();

    // 1. Default Gson: silently writes the duplicate field name "1" (data loss on parse)
    Gson defaultGson = new Gson();
    LinkedHashMap<Object, String> colliding = new LinkedHashMap<>();
    colliding.put(1, "integer-key");
    colliding.put("1", "string-key");
    System.out.println("[default gson] colliding map serialized silently:");
    System.out.println("  " + defaultGson.toJson(colliding, mapType));

    // 2. Checked adapter: rejects the same map before writing anything
    Gson checkedGson =
        new GsonBuilder()
            .registerTypeAdapterFactory(new CheckedMapTypeAdapterFactory())
            .create();
    System.out.println("[checked gson] same map is rejected up-front:");
    try {
      checkedGson.toJson(colliding, mapType);
      System.out.println("  UNEXPECTED: no exception");
    } catch (RuntimeException e) {
      System.out.println("  " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    // 3. Checked adapter: a collision-free map serializes and reads back fine
    LinkedHashMap<Object, String> fine = new LinkedHashMap<>();
    fine.put(2, "integer-key");
    fine.put("two", "string-key");
    fine.put(null, "null-key");
    String json = checkedGson.toJson(fine, mapType);
    System.out.println("[checked gson] collision-free map:");
    System.out.println("  " + json);
    Map<Object, String> readBack = checkedGson.fromJson(json, mapType);
    System.out.println("  read back: " + readBack);
  }
}
