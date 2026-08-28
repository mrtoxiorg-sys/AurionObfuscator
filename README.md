# Aurion Obfuscator

Универсальный обфускатор Java-байткода на базе **ASM**. Приоритет — моды
Minecraft (Fabric/Quilt), но работает с **любым** jar. Конфигурируемый,
с CLI и GUI, с автоматической защитой критичных для загрузки мода имён.

Поддерживает **профили** агрессивности (`light`/`medium`/`heavy`/`insane`) и
**точечный таргетинг** техник по классам (include/exclude).

> **Статус:** протестировано на реальном Fabric-моде (Minecraft 1.21.11, Java 21) —
> профиль `heavy` собирается, проходит верификацию и **успешно запускается в игре**
> (mixin'ы, entrypoints, шейдеры работают).

---

## Содержание
- [Возможности](#возможности)
- [Профили](#профили)
- [Таргетинг техник](#таргетинг-техник)
- [Безопасность для Minecraft/Fabric](#безопасность-для-minecraftfabric)
- [Требования](#требования)
- [Сборка](#сборка)
- [Быстрый старт](#быстрый-старт)
- [Использование (CLI)](#использование-cli)
- [GUI](#gui)
- [Конфигурация JSON](#конфигурация-json)
- [Keep-синтаксис](#keep-синтаксис)
- [Верификация результата](#верификация-результата)
- [Troubleshooting (типовые краши)](#troubleshooting-типовые-краши)
- [Архитектура](#архитектура)
- [Как это ломает декомпиляцию](#как-это-ломает-декомпиляцию)
- [Модель угроз и пределы](#модель-угроз-и-пределы)

---

## Возможности

| Техника | Что делает |
|---|---|
| **Rename** | Переименовывает классы/методы/поля в нечитаемые имена (`Il1l`, `jijL`). Учитывает наследование, override, sibling-классы, mixin, entrypoints. **Flat-namespace** (свод всех классов в один пакет — убивает семантику пакетов) и **overload-collapse** (много членов с одним именем, различаются только дескриптором → декомпилятор выдаёт кашу из `a(...)`). |
| **String encryption** | Шифрует строковые литералы (XOR). Три стратегии: `shared` (один общий дешифратор), `pool` (N дешифраторов), `perClass` (**уникальный встраиваемый дешифратор в каждом классе + контекстный ключ** — нет единой точки отказа). Опция `lazyArray` полностью убирает строковые литералы из тел методов в `static final String[]`. |
| **Control flow** | Opaque predicates + bogus jumps. Режим `runtimePredicates` использует значения времени выполнения (`System.nanoTime()`), которые **невозможно схлопнуть констант-фолдингом** декомпилятора. |
| **Number obfuscation** | Заменяет числовые константы на XOR-выражения. |
| **Anti-decompile** | Безопасные (JVM-валидные) трюки против Vineflower/CFR: фиктивные `try/catch` вокруг реального кода → декомпилятор плодит вложенные блоки. |
| **Debug strip** | Удаляет номера строк, имена локальных переменных, SourceFile. |

Все техники независимо включаются/отключаются и настраиваются.

---

## Профили

Профиль — это пресет флагов, который берётся за **базу** и затем перекрывается
любыми явно указанными полями в JSON (профиль = разумные дефолты).

| Профиль | Состав |
|---|---|
| **light** | Только косметика: rename (`alpha`, уникальные имена) + debug strip. Быстро и максимально безопасно. |
| **medium** | + шифрование строк (пул дешифраторов) + обфускация чисел + лёгкий control-flow. Разумный дефолт. |
| **heavy** | + per-class дешифраторы с lazy array, overload-collapse, flat-namespace, runtime-предикаты, анти-декомпилятор трюки. |
| **insane** | `heavy` на максимальной интенсивности control-flow и анти-декомпилятора. |

```bash
# через CLI
./obfuscate.sh mod.jar mod-obf.jar --profile heavy -v

# или в JSON
{ "profile": "heavy", "keep": ["com.example.Api"] }
```

> `--profile` из CLI игнорируется при `--config` — задавайте `"profile"` внутри JSON,
> чтобы работал deep-merge переопределений.

---

## Таргетинг техник

У каждой техники есть `include`/`exclude` — списки классов (синтаксис как в
[keep](#keep-синтаксис)). Это позволяет применять тяжёлые техники выборочно,
не задевая тик-критичный код.

- `exclude` имеет наивысший приоритет.
- Если `include` непуст — техника применяется **только** к этим классам.
- Если `include` пуст — применяется ко всем (кроме `exclude` и `excludePackages`).

```json
{
  "profile": "heavy",
  "antiDecompile": { "include": ["com.example.render.**"] },
  "controlFlow":   { "exclude": ["com.example.**.tick.**"] }
}
```

---

## Безопасность для Minecraft/Fabric

Обфускатор автоматически анализирует `fabric.mod.json`, `*.mixins.json` и
защищает то, что нельзя трогать, иначе мод не загрузится:

- **Entrypoints** — классы **и их методы** (`onInitialize`, `onInitializeClient`).
- **Mixin-классы** и **mixin-интерфейсы** (duck-интерфейсы, реализуемые mixin'ами).
- **Override Minecraft-методов** — методы, переопределяющие MC (`method_XXXX`,
  `render`, `tick`, ...), не переименовываются.
- **Согласованность иерархии** — метод, объявленный в супер/интерфейсе и
  реализованный в нескольких наследниках (в т.ч. sibling'ах), получает одно имя.
- Ссылки на классы MC/библиотек не трогаются (их нет в jar).

> **ВАЖНО:** для корректного анализа override MC-методов передайте
> **intermediary-mapped** Minecraft jar как `--library`. Скрипт `obfuscate.sh`
> находит его автоматически в кеше Fabric Loom. Без этого методы,
> переопределяющие MC, могут быть ошибочно переименованы, и мод крашнется.

---

## Требования

- **JDK 21** (проект и таргет — Java 21).
- Gradle не нужен ставить — используется wrapper (`./gradlew`).
- Для авто-поиска MC classpath: собранный ранее Fabric-проект (кеш
  `~/.gradle/caches/fabric-loom`).

---

## Сборка

```bash
./gradlew fatJar
# результат: build/libs/aurion-obfuscator-1.0.0-all.jar (self-contained fat-jar)
```

---

## Быстрый старт

Обфускация Fabric-мода с авто-поиском MC classpath и верификацией:

```bash
# 1) обфусцировать
./obfuscate.sh mod.jar mod-obf.jar --dictionary illegal -v

# 2) проверить, что мод не сломан (до запуска игры)
./tools/verify/verify.sh mod-obf.jar
```

---

## Использование (CLI)

```bash
java -jar build/libs/aurion-obfuscator-1.0.0-all.jar \
    -i mod.jar -o mod-obf.jar \
    -l /path/to/minecraft-intermediary.jar \
    -m mappings.txt \
    --dictionary illegal -v
```

Опции:

```
-i, --input <jar>       Входной jar
-o, --output <jar>      Выходной jar
-c, --config <json>     Конфигурация из JSON
-l, --library <jar>     Библиотека для анализа (можно несколько)
-k, --keep <pattern>    Keep-правило (можно несколько)
-m, --mappings <file>   Записать mappings (original -> obf)
-p, --profile <name>    Пресет: light | medium | heavy | insane
-v, --verbose           Подробный лог
--dictionary <style>    Стиль имён: alpha | illegal | dictionary
--flatten <package>     Свести все классы в один пакет ("" = корень)
--intensity <1-5>       Интенсивность control-flow
--no-rename / --no-strings / --no-flow / --no-numbers / --no-debug-strip
--gen-config <json>     Сгенерировать шаблон конфига
--gui                   Запустить GUI
-h, --help              Справка
```

---

## GUI

```bash
java -jar build/libs/aurion-obfuscator-1.0.0-all.jar --gui
```

Swing-интерфейс: выбор файлов, все опции чекбоксами, живой лог, загрузка/
сохранение конфигурации. Запускается без внешних зависимостей.

---

## Конфигурация JSON

Сгенерировать шаблон:

```bash
java -jar build/libs/aurion-obfuscator-1.0.0-all.jar --gen-config config.json
```

Все поля с дефолтами; можно задать только нужное. Полный пример —
[`examples/config.example.json`](examples/config.example.json), profile-based —
[`examples/config.heavy-profile.json`](examples/config.heavy-profile.json).

```json
{
  "profile": "custom",
  "input": null,
  "output": null,
  "libraries": [],
  "autoDetectFabric": true,
  "keep": [],
  "excludePackages": [],
  "verbose": false,
  "mappingsOutput": null,
  "rename":           { "enabled": true, "classes": true, "methods": true,
                        "fields": true, "dictionary": "illegal",
                        "flattenPackage": null, "prefix": "",
                        "overloadCollapse": false, "include": [], "exclude": [] },
  "stringEncryption": { "enabled": true, "minLength": 1, "skipPatterns": [],
                        "strategy": "shared", "poolSize": 8, "lazyArray": false,
                        "include": [], "exclude": [] },
  "controlFlow":      { "enabled": true, "intensity": 2,
                        "opaquePredicates": true, "bogusJumps": true,
                        "runtimePredicates": false, "include": [], "exclude": [] },
  "numbers":          { "enabled": true, "integers": true, "longs": true,
                        "include": [], "exclude": [] },
  "debugStrip":       { "enabled": true, "lineNumbers": true,
                        "localVariables": true, "sourceFile": true,
                        "include": [], "exclude": [] },
  "antiDecompile":    { "enabled": false, "fakeTryCatch": true, "deadCode": true,
                        "intensity": 2, "include": [], "exclude": [] }
}
```

**Ключевые поля v2:**
- `profile` — `custom` (использовать поля как есть) / `light` / `medium` / `heavy` / `insane`.
- `rename.overloadCollapse` — коллапс имён членов (много одноимённых, разный дескриптор).
- `rename.flattenPackage` — `""` свести все классы в корневой пакет.
- `stringEncryption.strategy` — `shared` / `pool` / `perClass`; `lazyArray` для `perClass`.
- `controlFlow.runtimePredicates` — предикаты на runtime-значениях (анти-констант-фолдинг).
- `antiDecompile` — безопасные анти-декомпилятор трюки.
- `include`/`exclude` у каждой техники — [таргетинг](#таргетинг-техник).

Запуск с конфигом:

```bash
java -jar build/libs/aurion-obfuscator-1.0.0-all.jar -c config.json
```

---

## Keep-синтаксис (ProGuard-lite)

```
com.example.Foo         класс (и его члены)
com.example.Foo.bar     конкретный член
com.example.*           классы прямо в пакете
com.example.**          классы в пакете и вложенных
com.example.Foo$*       внутренние классы
```

Если что-то ломается из-за рефлексии по именам (например, поля из конфигов) —
добавь класс/член в `keep`.

---

## Верификация результата

Три уровня проверок ловят проблемы **до запуска Minecraft**:

```bash
./tools/verify/verify.sh mod-obf.jar
```

- **LinkCheck** — битые ссылки метод/поле → `NoSuchMethodError` / `NoSuchFieldError`.
- **AbstractCheck** — нереализованные абстрактные методы → `AbstractMethodError`.
- **LoadVerify** — реальная загрузка классов с MC classpath →
  `VerifyError` / `ClassFormatError` (истинная верификация байткода JVM).

Каждую утилиту можно запускать отдельно (см. `tools/verify/*.java`).

Эталон на примере Aurion QOL (174 класса): **0 битых ссылок, 0 нереализованных
абстрактных, 0 ошибок верификации байткода**; поведение при загрузке идентично
оригиналу.

> `verify.sh` покрывает статику и загрузку классов, но **не** применяет mixin
> (это делает Fabric в рантайме). Для модов с mixin'ами обязателен тестовый
> запуск игры. Профили `medium`/`heavy`/`insane` на примере Aurion этот запуск
> проходят.

---

## Troubleshooting (типовые краши)

**`NoSuchMethodError: X.method(...)`** — вызов унаследованного метода не нашёл
цель. Причина обычно — обфускация без MC classpath. Убедись, что передаёшь
intermediary MC jar через `-l` (или используй `obfuscate.sh`, он делает это сам).

**`AbstractMethodError: ... does not define ... abstract method`** — несогласованное
переименование override у классов с общим абстрактным предком. Актуальная
версия это исправляет (транзитивный связный компонент иерархии). Пересобери
fat-jar и переобфусцируй.

**`ClassNotFoundException` / entrypoint не загружается** — проверь, что
`autoDetectFabric` включён (по умолчанию да). Если у мода нестандартные точки
входа — добавь их класс в `keep`.

**Что-то с рефлексией (GSON-конфиги, поля по имени)** — добавь соответствующие
классы/поля в `keep`.

**`Mixin transformation ... failed` / `StringIndexOutOfBoundsException` в
`MixinTargetContext.transformSingleDescriptor`** — mixin вызывает обфусцированный
метод, чей дескриптор ссылается на класс с именем, начинающимся с `L` или `I`
(например `LLjlj;`). Парсер дескрипторов Fabric Mixin такое имя не разбирает.
Актуальная версия это исправляет: имена классов никогда не начинаются с `L`/`I`
(`classSafe` в `NameGenerator`). Пересобери fat-jar и переобфусцируй.

> ⚠️ `verify.sh` **не** воспроизводит применение mixin (это делает Fabric в
> рантайме), поэтому такой краш статикой не ловится. После обфускации мода с
> mixin'ами всегда делай тестовый запуск игры.

Всегда прогоняй `tools/verify/verify.sh` — большинство проблем видно статически.

---

## Архитектура

```
config/     — модель конфига (ObfConfig), Gson-загрузчик с deep-merge профилей
              (ConfigLoader), пресеты профилей (Profiles)
core/       — ClassPool (иерархия наследования), JarIO, KeepRules,
              TargetMatcher (include/exclude), Obfuscator, Log
fabric/     — детектор Fabric-метаданных и защищённых имён
transform/  — Rename (mapping/remapper/namegen с overload-collapse),
              StringEncrypt (shared/pool/perClass + lazy array),
              ControlFlow (opaque + runtime-предикаты), Number,
              AntiDecompile, DebugStrip
cli/        — парсер аргументов (--profile и пр.)
gui/        — Swing-интерфейс
Main.java   — диспетчер CLI/GUI

tools/verify/ — статические/динамические верификаторы обфусцированного jar
obfuscate.sh  — обёртка с авто-поиском MC classpath
```

Обфускатор пишет валидный байткод (`COMPUTE_FRAMES` с кастомным
`getCommonSuperClass`, работающим по `ClassPool`, а не по рантайм-загрузчику —
это позволяет корректно обрабатывать классы, наследующие Minecraft).

---

## Как это ломает декомпиляцию

Реальный пример: класс `Utils` (профиль `heavy`), декомпиляция **Vineflower 1.12.0**.

**До** — исходный `Utils.java`:
```java
package dev.toxi.aurion.utils;

public class Utils {
   public static boolean rendering3D = true;

   public static boolean canUpdate() {
      return AurionQol.mc != null && AurionQol.mc.field_1687 != null
          && AurionQol.mc.field_1724 != null;
   }

   public static byte[] readBytes(InputStream in) {
      try { return in.readAllBytes(); }
      catch (IOException e) {
         AurionQol.LOGGER.error("Error reading from stream.", e);
         return new byte[0];
      } finally { IOUtils.closeQuietly(in); }
   }
}
```

**После** — тот же класс декомпилируется как `ljil` (в корневом пакете):
```java
public class ljil {                                          // flat-namespace
   public static boolean IIIIlllIII = (boolean)(-2078851370 ^ -2078851369); // число
   private static final String[] lIllIllIII;                 // lazy-массив строк

   public static boolean lIlIIIlIlII() {                     // overload-collapse
      try {                                                  // fake try/catch
         if ((System.nanoTime() | 1L) == 0L) {               // runtime-предикат
            throw new ArithmeticException();                 //  (не схлопывается!)
         } else {
            return AurionQol.mc != null && AurionQol.mc.field_1687 != null
                && AurionQol.mc.field_1724 != null ? true ^ true : true ^ true;
         }
      } catch (Throwable var2) { throw var2; }
   }

   public static byte[] IlIIIIlIlII(InputStream var0) {
      try {
         if ((System.nanoTime() | 1L) == 0L) throw new ArithmeticException();
         try { return var0.readAllBytes(); }
         catch (IOException var8) {
            AurionQol.LOGGER.error(lIllIllIII[442798646 ^ 442798646], var8); // строка → arr[0]
            return new byte[0];
         } finally { IOUtils.closeQuietly(var0); }
      } catch (Throwable var10) { throw var10; }
   }

   static {                                                  // per-class дешифратор
      String[] var10000 = new String[1];
      var10000[0] = lIIIIIlIlII("㘻䉗幨ךּ䂌\ue6fd➘礈霿䘑...", 0);
      lIllIllIII = var10000;
   }
}
```

Что видит реверсер: пакетная семантика уничтожена, строки исчезли из тел
методов (только `arr[idx]` + встроенный дешифратор с контекстным ключом),
числа вычисляемы, `System.nanoTime()`-предикаты не убираются констант-фолдингом,
всё обёрнуто в фиктивные `try/catch`. Единой точки отказа (одного дешифратора)
больше нет — каждый класс дешифрует по-своему.

---

## Модель угроз и пределы

Честно о том, что этот инструмент даёт и чего **не** даёт.

**Цель:** резко поднять стоимость реверса — с «пара часов» до «нужен кастомный
деобфускатор именно под эту схему + ручной труд». Основной противник —
скид-краддеры/реселлеры, работающие по шаблону «декомпилировал → прочитал
строки/пакеты → украл».

**Что это НЕ даёт:**
- Абсолютной защиты не существует. JVM-байткод верифицируем, а Fabric требует
  читаемых точек входа (entrypoints, mixin-классы/аннотации, refmap) — это
  всегда открытый шов.
- Против опытного реверсера с временем это замедление, а не стена.

**Пределы реализации (сознательные):**
- Все техники **JVM-валидны** и переживают верификатор (проверено: 0 verify-ошибок
  на реальном моде). Никаких хрупких трюков «на грани формата класса».
- Анти-декомпилятор — только безопасные приёмы (не крашат игру, не зависят от
  версии декомпилятора настолько, чтобы ломать рантайм).
- Тик-критичный код можно вывести из-под тяжёлых техник через `exclude`.

---

> ⚠️ Данный инструмент предоставлен исключительно в ознакомительных и
> образовательных целях для защиты собственного кода. Ответственность за
> использование лежит на пользователе.
