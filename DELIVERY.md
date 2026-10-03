# Checked Map Type Adapter — 交付说明

## 背景

`Map` 序列化为 JSON 对象时，不同类型的键可能折叠成同一个字段名：整数键 `1` 与字符串键
`"1"` 都变成字段 `"1"`，`null` 键与字符串键 `"null"` 都变成字段 `"null"`。默认
`MapTypeAdapterFactory` 会静默写出重复字段名，解析方读回时丢失数据，且只能在写完后扫描
JSON 才能发现。

## 改动内容

新增显式 opt-in 的 checked-map adapter，**默认 Map 序列化行为完全不改**（未注册时一切照旧，
由测试 `defaultMapSerialization_isUnchanged` 锁定）。

- `gson/src/main/java/com/google/gson/internal/bind/CheckedMapTypeAdapterFactory.java`
  新工厂类，通过 `new GsonBuilder().registerTypeAdapterFactory(new CheckedMapTypeAdapterFactory())`
  显式注册，也可按泛型 `TypeToken` 精确注册。
- `gson/src/test/java/com/google/gson/internal/bind/CheckedMapTypeAdapterFactoryTest.java`
  10 个测试（见下）。
- `demo/CheckedMapDemo.java` — 小 Java 输出演示（输出见文末）。

## 行为契约

1. **预检先行**：`write()` 在往 `JsonWriter` 写任何字节之前，先对全部键各调用**恰好一次**
   key adapter（`toJsonTree`），一次性算出所有字段名并检测文本碰撞。预检不通过时：
   - writer 已写长度为 0（连 `{` 都没写）；
   - value adapter 调用计数为 0；
   - 抛出 `JsonIOException`，消息含两个碰撞键的迭代位置与键值，
     如 `Map keys at positions 0 and 1 both serialize to the JSON field name "1" (keys: 1 and "1")`。
2. **碰撞判定**：整数 `1` vs 字符串 `"1"`、`null` vs 字符串 `"null"`（以及布尔/数字的
   文本化形式）一律拒绝。
3. **复杂键不支持**：键序列化为 JSON 对象或数组（POJO、集合、数组、Map 键）时在预检阶段
   明确拒绝，提示 complex map keys are not supported，不会退化成数组形式。
4. **成功路径**：按 Map 原有迭代顺序写字段与值；不修改输入 Map；不需要写完再扫描 JSON。
5. **key adapter 每键至多 1 次**：预检算出的字段名直接复用于写出，没有"先测一遍再写一遍"
   的第二次 key adapter 调用（测试 `keyAdapter_isCalledExactlyOncePerKey` 断言计数 ==
   键数）。
6. **read**：支持 JSON 对象形式读回（含重复键检测）；遇到数组形式（complex map key 的
   写法）明确抛 `JsonSyntaxException`。

## 测试清单（全部通过，OK (10 tests)）

| 测试 | 覆盖点 |
| --- | --- |
| `integerAndStringKeyCollision_reportsBothPositions_andWritesNothing` | 1 vs "1" 碰撞；消息含 positions 0 and 1；writer 长度 0；value adapter 计数 0；输入 Map 未变 |
| `nullKeyAndLiteralNullStringCollision_isRejected` | null vs "null" 碰撞；writer 0；计数 0 |
| `complexArrayKey_isRejectedBeforeAnythingIsWritten` | 数组键拒绝；writer 0；计数 0 |
| `complexPojoKey_isRejectedBeforeAnythingIsWritten` | 对象键拒绝；writer 0；计数 0 |
| `noCollision_writesValuesInOriginalOrder_andRoundTrips` | 无碰撞按原顺序输出并读回；输入 Map 未变 |
| `nullAndEmptyMap_areWrittenNormally` | null map → `null`，空 map → `{}` |
| `keyAdapter_isCalledExactlyOncePerKey` | key adapter 每键恰好 1 次 |
| `genericMapType_registeredViaFactory_roundTrips` | 泛型 `Map<String, List<Integer>>` 工厂注册往返；非 Map 类型不受影响 |
| `genericMapType_registeredViaTypeToken_usesCheckedAdapter` | 按 TypeToken 泛型精确注册生效 |
| `defaultMapSerialization_isUnchanged` | 默认 Gson 行为不变（仍静默写重复名） |

## 本地验收方式（无 Docker，缓存均在 D 盘）

```bash
# 编译主源码（GsonBuildConfig 由模板生成；排除 module-info）
javac -cp error_prone_annotations.jar -d classes @main-sources.txt GsonBuildConfig.java
# 编译并运行测试（JUnit 4.13.2 + Truth）
javac -cp "classes;junit-4.13.2.jar;hamcrest-core-1.3.jar;truth-1.4.5.jar;guava-33.7.0-jre.jar" \
  -d test-classes gson/src/test/java/com/google/gson/internal/bind/CheckedMapTypeAdapterFactoryTest.java
java -cp "test-classes;classes;..." org.junit.runner.JUnitCore \
  com.google.gson.internal.bind.CheckedMapTypeAdapterFactoryTest
# => OK (10 tests)
```

注：JDK 25 的 javac 编译该测试时触发编译器自身 NPE（JDK bug），验收使用 D 盘
JDK 11（Temurin 11.0.32.1）完成。

## 演示输出（`demo/CheckedMapDemo.java` 实际运行结果）

```
[default gson] colliding map serialized silently:
  {"1":"integer-key","1":"string-key"}
[checked gson] same map is rejected up-front:
  JsonIOException: Map keys at positions 0 and 1 both serialize to the JSON field name "1" (keys: 1 and "1"); refusing to serialize because data would be lost
[checked gson] collision-free map:
  {"2":"integer-key","two":"string-key","null":"null-key"}
  read back: {2=integer-key, two=string-key, null=null-key}
```
