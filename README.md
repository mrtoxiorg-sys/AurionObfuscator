# Aurion Obfuscator

Универсальный обфускатор Java-байткода на базе **ASM**. Приоритет — моды
Minecraft (Fabric/Quilt), но работает с **любым** jar. Конфигурируемый,
с CLI и GUI, с автоматической защитой критичных для загрузки мода имён.

---

## Содержание
- [Возможности](#возможности)
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

---

## Возможности

| Техника | Что делает |
|---|---|
| **Rename** | Переименовывает классы/методы/поля в нечитаемые имена (`Il1l`, `jijL`). Учитывает наследование, override, sibling-классы, mixin, entrypoints. |
| **String encryption** | Шифрует строковые литералы (XOR), расшифровка в рантайме через сгенерированный класс-декриптор. |
| **Control flow** | Opaque predicates (всегда-истинные условия с мёртвыми ветками) + bogus jumps. Ломает реконструкцию логики в декомпиляторах. |
| **Number obfuscation** | Заменяет числовые константы на XOR-выражения. |
| **Debug strip** | Удаляет номера строк, имена локальных переменных, SourceFile. |

Все техники независимо включаются/отключаются и настраиваются.

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

Все поля с дефолтами; можно задать только нужное:

```json
{
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
                        "flattenPackage": null, "prefix": "" },
  "stringEncryption": { "enabled": true, "minLength": 1, "skipPatterns": [] },
  "controlFlow":      { "enabled": true, "intensity": 2,
                        "opaquePredicates": true, "bogusJumps": true },
  "numbers":          { "enabled": true, "integers": true, "longs": true },
  "debugStrip":       { "enabled": true, "lineNumbers": true,
                        "localVariables": true, "sourceFile": true }
}
```

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

Всегда прогоняй `tools/verify/verify.sh` — большинство проблем видно статически.

---

## Архитектура

```
config/     — модель конфига + Gson-загрузчик
core/       — ClassPool (иерархия наследования), JarIO, KeepRules, Obfuscator, Log
fabric/     — детектор Fabric-метаданных и защищённых имён
transform/  — Rename (mapping/remapper/namegen), StringEncrypt, ControlFlow,
              Number, DebugStrip
cli/        — парсер аргументов
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

Пример метода после обфускации (`javap -c`):

```
getstatic  #15   // Field lll1:I         <- opaque predicate
iconst_1
iand
ifeq  19                                  <- всегда true, мёртвая ветка ниже
new  java/lang/ArithmeticException        <- недостижимый bogus-блок
...
ldc  "Ꭶᎈᑠᑛ"                              <- зашифрованная строка
ldc  1057408704
ldc  1057412366
ixor                                      <- обфусцированное число
invokestatic  lIjl.i1lLl:(...)            <- рантайм-расшифровка
```

Имена классов/методов/полей — `jijL`, `lll1`, `i1lLl`; строки нечитаемы;
константы вычисляемы; поток управления запутан. Достаточно, чтобы реверсер
«тупил», а декомпилятор выдавал кашу.

---

> ⚠️ Данный инструмент предоставлен исключительно в ознакомительных и
> образовательных целях для защиты собственного кода. Ответственность за
> использование лежит на пользователе.
