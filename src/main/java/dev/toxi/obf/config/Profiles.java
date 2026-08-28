package dev.toxi.obf.config;

/**
 * Пресеты профилей обфускации.
 *
 * Каждый профиль возвращает {@link ObfConfig} с выставленными флагами техник.
 * Профиль задаёт лишь БАЗУ — {@link ConfigLoader} затем накладывает поверх
 * поля, явно указанные пользователем в JSON.
 *
 * Философия уровней:
 *   light  — быстро, безопасно, только косметика (rename unique + strip debug).
 *   medium — разумный дефолт: + шифрование строк (пул), числа, лёгкий flow.
 *   heavy  — серьёзно: per-class дешифраторы + overload-collapse +
 *            runtime-предикаты + flat-namespace + безопасные анти-декомпилятор.
 *   insane — heavy на максимуме интенсивности, применяется ко всему.
 */
public final class Profiles {

    private Profiles() {}

    public static ObfConfig preset(String name) {
        return switch (name == null ? "" : name.toLowerCase()) {
            case "light"  -> light();
            case "medium" -> medium();
            case "heavy"  -> heavy();
            case "insane" -> insane();
            default       -> new ObfConfig(); // неизвестный профиль -> дефолты
        };
    }

    // ---- light: косметика ----
    public static ObfConfig light() {
        ObfConfig c = new ObfConfig();
        c.profile = "light";

        c.rename.enabled = true;
        c.rename.classes = true;
        c.rename.methods = true;
        c.rename.fields = true;
        c.rename.dictionary = "alpha";
        c.rename.overloadCollapse = false;
        c.rename.flattenPackage = null;

        c.stringEncryption.enabled = false;
        c.numbers.enabled = false;
        c.controlFlow.enabled = false;
        c.antiDecompile.enabled = false;

        c.debugStrip.enabled = true;
        return c;
    }

    // ---- medium: разумный дефолт ----
    public static ObfConfig medium() {
        ObfConfig c = new ObfConfig();
        c.profile = "medium";

        c.rename.enabled = true;
        c.rename.dictionary = "illegal";
        c.rename.overloadCollapse = false;
        c.rename.flattenPackage = null;

        c.stringEncryption.enabled = true;
        c.stringEncryption.strategy = "pool";
        c.stringEncryption.poolSize = 8;
        c.stringEncryption.lazyArray = false;

        c.numbers.enabled = true;

        c.controlFlow.enabled = true;
        c.controlFlow.intensity = 2;
        c.controlFlow.opaquePredicates = true;
        c.controlFlow.bogusJumps = true;
        c.controlFlow.runtimePredicates = false;

        c.antiDecompile.enabled = false;

        c.debugStrip.enabled = true;
        return c;
    }

    // ---- heavy: серьёзная защита ----
    public static ObfConfig heavy() {
        ObfConfig c = new ObfConfig();
        c.profile = "heavy";

        c.rename.enabled = true;
        c.rename.dictionary = "illegal";
        c.rename.overloadCollapse = true;
        c.rename.flattenPackage = ""; // единый корневой пакет — убить семантику

        c.stringEncryption.enabled = true;
        c.stringEncryption.strategy = "perClass";
        c.stringEncryption.lazyArray = true;

        c.numbers.enabled = true;

        c.controlFlow.enabled = true;
        c.controlFlow.intensity = 3;
        c.controlFlow.opaquePredicates = true;
        c.controlFlow.bogusJumps = true;
        c.controlFlow.runtimePredicates = true;

        c.antiDecompile.enabled = true;
        c.antiDecompile.fakeTryCatch = true;
        c.antiDecompile.deadCode = true;
        c.antiDecompile.intensity = 2;

        c.debugStrip.enabled = true;
        return c;
    }

    // ---- insane: максимум ----
    public static ObfConfig insane() {
        ObfConfig c = heavy();
        c.profile = "insane";

        c.controlFlow.intensity = 5;
        c.antiDecompile.intensity = 4;
        return c;
    }
}
