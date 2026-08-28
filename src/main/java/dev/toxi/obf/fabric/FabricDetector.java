package dev.toxi.obf.fabric;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.toxi.obf.core.ClassInfo;
import dev.toxi.obf.core.ClassPool;
import dev.toxi.obf.core.JarResource;
import dev.toxi.obf.core.Log;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Анализатор Fabric-мода.
 *
 * Разбирает ресурсы входного jar и извлекает имена классов, которые
 * Fabric loader / Mixin находят по строковому имени. Такие классы должны
 * сохранить свои имена, иначе мод не загрузится.
 *
 * Обрабатываемые файлы:
 *   - fabric.mod.json      — entrypoints (main/client/server + произвольные ключи)
 *   - *.mixins.json        — package + списки mixin (client/server/common/mixins)
 *   - quilt.mod.json       — на случай Quilt-модов
 */
public final class FabricDetector {

    private static final Gson GSON = new Gson();

    private FabricDetector() {}

    public static FabricMetadata analyze(List<JarResource> resources) {
        FabricMetadata meta = new FabricMetadata();

        for (JarResource r : resources) {
            String name = r.path;
            try {
                if (name.equals("fabric.mod.json") || name.equals("quilt.mod.json")) {
                    meta.isFabricMod = true;
                    parseModJson(new String(r.data, StandardCharsets.UTF_8), meta);
                } else if (name.endsWith(".mixins.json") || name.endsWith(".mixin.json")) {
                    meta.isFabricMod = true;
                    parseMixinJson(new String(r.data, StandardCharsets.UTF_8), meta);
                }
            } catch (Exception e) {
                Log.warn("Не удалось разобрать " + name + ": " + e.getMessage());
            }
        }

        if (meta.isFabricMod) {
            Log.ok("Обнаружен Fabric/Quilt мод. Защищено имён классов: "
                    + meta.keepClassNames.size()
                    + " (mixin: " + meta.mixinClasses.size()
                    + ", entrypoints: " + meta.entrypoints.size() + ")");
        }
        return meta;
    }

    /**
     * Пост-анализ с доступом к байткоду: защищает интерфейсы, которые
     * реализуют mixin-классы (duck-interfaces через @Implements / accessor).
     *
     * Mixin инъектит методы этих интерфейсов в целевые MC-классы, поэтому
     * если переименовать методы интерфейса, вызовы в остальном коде мода
     * разъедутся с реальными (не переименованными) методами в MC-классе.
     * Самый безопасный путь — не трогать такие интерфейсы целиком.
     */
    public static void protectMixinInterfaces(FabricMetadata meta, ClassPool pool) {
        if (!meta.isFabricMod) return;
        int before = meta.fullyKeptClasses.size();
        for (String mixin : meta.mixinClasses) {
            ClassInfo ci = pool.get(mixin);
            if (ci == null || ci.node.interfaces == null) continue;
            for (String itf : ci.node.interfaces) {
                if (pool.isInput(itf)) {
                    meta.keepClassNames.add(itf);
                    meta.fullyKeptClasses.add(itf);
                }
            }
        }
        int added = meta.fullyKeptClasses.size() - before;
        if (added > 0) {
            Log.ok("Дополнительно защищено mixin-интерфейсов: " + added);
        }
    }

    // ---- fabric.mod.json ----

    private static void parseModJson(String json, FabricMetadata meta) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        // entrypoints: { "main": ["a.b.C"], "client": [{ "value": "a.b.C" }], ... }
        if (root.has("entrypoints") && root.get("entrypoints").isJsonObject()) {
            JsonObject eps = root.getAsJsonObject("entrypoints");
            for (var entry : eps.entrySet()) {
                JsonElement val = entry.getValue();
                if (val.isJsonArray()) {
                    for (JsonElement el : val.getAsJsonArray()) {
                        String cls = extractEntrypointClass(el);
                        if (cls != null) {
                            String internal = cls.replace('.', '/');
                            meta.entrypoints.add(internal);
                            meta.keepClassNames.add(internal);
                            // entrypoint-класс реализует интерфейс loader'а
                            // (ModInitializer.onInitialize и т.п.), поэтому его
                            // члены тоже защищаем от переименования.
                            meta.fullyKeptClasses.add(internal);
                        }
                    }
                }
            }
        }

        // Quilt формат: entrypoints могут лежать в quilt_loader.entrypoints
        if (root.has("quilt_loader")) {
            JsonObject ql = root.getAsJsonObject("quilt_loader");
            if (ql.has("entrypoints") && ql.get("entrypoints").isJsonObject()) {
                for (var entry : ql.getAsJsonObject("entrypoints").entrySet()) {
                    if (entry.getValue().isJsonArray()) {
                        for (JsonElement el : entry.getValue().getAsJsonArray()) {
                            String cls = extractEntrypointClass(el);
                            if (cls != null) {
                                String internal = cls.replace('.', '/');
                                meta.entrypoints.add(internal);
                                meta.keepClassNames.add(internal);
                                meta.fullyKeptClasses.add(internal);
                            }
                        }
                    }
                }
            }
        }
    }

    /** entrypoint может быть строкой "a.b.C" или объектом { "value": "a.b.C" }. */
    private static String extractEntrypointClass(JsonElement el) {
        if (el.isJsonPrimitive()) {
            String s = el.getAsString();
            // формат "a.b.C::method" или "a.b.C" — берём часть до ::
            int idx = s.indexOf("::");
            return idx >= 0 ? s.substring(0, idx) : s;
        }
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            if (o.has("value")) {
                String s = o.get("value").getAsString();
                int idx = s.indexOf("::");
                return idx >= 0 ? s.substring(0, idx) : s;
            }
        }
        return null;
    }

    // ---- *.mixins.json ----

    private static void parseMixinJson(String json, FabricMetadata meta) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        String pkg = root.has("package") ? root.get("package").getAsString() : "";
        String pkgInternal = pkg.replace('.', '/');

        addMixinArray(root, "mixins", pkgInternal, meta);
        addMixinArray(root, "client", pkgInternal, meta);
        addMixinArray(root, "server", pkgInternal, meta);
        addMixinArray(root, "common", pkgInternal, meta);
    }

    private static void addMixinArray(JsonObject root, String key,
                                      String pkgInternal, FabricMetadata meta) {
        if (!root.has(key) || !root.get(key).isJsonArray()) return;
        JsonArray arr = root.getAsJsonArray(key);
        for (JsonElement el : arr) {
            String rel = el.getAsString().replace('.', '/');
            String full = pkgInternal.isEmpty() ? rel : pkgInternal + "/" + rel;
            meta.mixinClasses.add(full);
            meta.keepClassNames.add(full);
            meta.fullyKeptClasses.add(full);
        }
    }
}
