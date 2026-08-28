package dev.toxi.obf.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Полная конфигурация обфускатора.
 *
 * Это POJO, сериализуемое/десериализуемое через Gson. Все поля имеют
 * дефолтные значения, поэтому даже пустой JSON даст рабочую конфигурацию.
 *
 * Философия конфигурируемости: каждая техника обфускации — отдельный
 * подобъект со своим флагом {@code enabled} и параметрами. Пользователь
 * может тонко настроить агрессивность или полностью отключить любой этап.
 */
public class ObfConfig {

    /** Путь до входного jar (может быть переопределён из CLI/GUI). */
    public String input = null;

    /** Путь до выходного jar. */
    public String output = null;

    /**
     * Дополнительные библиотеки/classpath (jar-файлы), которые нужны для
     * корректного построения иерархии наследования, но которые НЕ должны
     * обфусцироваться (например, minecraft.jar, fabric-api). Без них
     * resolver не сможет корректно определить override-методы.
     */
    public List<String> libraries = new ArrayList<>();

    /**
     * Автоматически определять и подхватывать Fabric-метаданные
     * (fabric.mod.json, *.mixins.json, *.accesswidener) для защиты
     * критичных имён от переименования.
     */
    public boolean autoDetectFabric = true;

    /**
     * Список правил "keep" — имена, которые нельзя трогать.
     * Синтаксис похож на ProGuard-lite:
     *   - "com.example.MyClass"            — весь класс (и его члены)
     *   - "com.example.MyClass.method"     — конкретный метод/поле
     *   - "com.example.**"                 — все классы в пакете и вложенных
     *   - "com.example.*"                  — все классы прямо в пакете
     */
    public List<String> keep = new ArrayList<>();

    /**
     * Пакеты, которые вообще не трогаем (даже для string/flow обфускации).
     * Полезно для сторонних shaded-библиотек внутри jar.
     */
    public List<String> excludePackages = new ArrayList<>();

    /** Выводить подробный лог трансформаций. */
    public boolean verbose = false;

    /** Куда писать mappings-файл (original -> obfuscated). null = не писать. */
    public String mappingsOutput = null;

    // --- Техники обфускации ---

    public RenameOptions rename = new RenameOptions();
    public StringEncryptOptions stringEncryption = new StringEncryptOptions();
    public ControlFlowOptions controlFlow = new ControlFlowOptions();
    public NumberOptions numbers = new NumberOptions();
    public DebugStripOptions debugStrip = new DebugStripOptions();

    // ============================================================
    //  Rename
    // ============================================================
    public static class RenameOptions {
        public boolean enabled = true;
        public boolean classes = true;
        public boolean methods = true;
        public boolean fields = true;

        /**
         * Стиль генерации имён:
         *   "alpha"    — короткие a, b, c... (компактно, читаемо для JVM)
         *   "illegal"  — визуально-путающие Unicode/подобные символы
         *   "dictionary" — из словаря confusing слов (Il1O0...)
         */
        public String dictionary = "illegal";

        /**
         * Переносить все переименованные классы в единый плоский пакет.
         * null = сохранять исходную пакетную структуру (но с обф-именами).
         * Пример: "" даст корневой пакет, "obf" — пакет obf.
         */
        public String flattenPackage = null;

        /** Префикс для новых имён (может быть пустым). */
        public String prefix = "";
    }

    // ============================================================
    //  String encryption
    // ============================================================
    public static class StringEncryptOptions {
        public boolean enabled = true;

        /**
         * Минимальная длина строки для шифрования (короткие строки типа "" 
         * шифровать смысла мало, а оверхед растёт).
         */
        public int minLength = 1;

        /** Не шифровать строки, попадающие под эти регэкспы (например, ключи конфигов). */
        public List<String> skipPatterns = new ArrayList<>();
    }

    // ============================================================
    //  Control flow
    // ============================================================
    public static class ControlFlowOptions {
        public boolean enabled = true;

        /**
         * Интенсивность: сколько (примерно) fake-переходов/обёрток
         * вставлять. 1 = лёгкая, 3 = средняя, 5 = агрессивная.
         */
        public int intensity = 2;

        /** Вставлять непрозрачные предикаты (opaque predicates). */
        public boolean opaquePredicates = true;

        /** Оборачивать линейный код в switch-диспетчер (flattening-lite). */
        public boolean bogusJumps = true;
    }

    // ============================================================
    //  Numbers / constants
    // ============================================================
    public static class NumberOptions {
        public boolean enabled = true;
        /** Заменять целочисленные константы на XOR/арифметические выражения. */
        public boolean integers = true;
        /** Заменять long-константы. */
        public boolean longs = true;
    }

    // ============================================================
    //  Debug info stripping
    // ============================================================
    public static class DebugStripOptions {
        public boolean enabled = true;
        /** Удалять номера строк (LineNumberTable). */
        public boolean lineNumbers = true;
        /** Удалять имена локальных переменных (LocalVariableTable). */
        public boolean localVariables = true;
        /** Удалять SourceFile / SourceDebug атрибуты. */
        public boolean sourceFile = true;
    }
}
