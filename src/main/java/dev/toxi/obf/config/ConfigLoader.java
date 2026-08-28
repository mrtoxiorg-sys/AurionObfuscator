package dev.toxi.obf.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Загрузка/сохранение конфигурации обфускатора в JSON.
 *
 * Используем pretty-printing и сериализацию null'ов, чтобы сгенерированный
 * дефолтный конфиг был самодокументируемым шаблоном для пользователя.
 *
 * Профили: если в JSON задано поле "profile" (кроме "custom"), сначала
 * строится конфиг-пресет этого профиля, затем поверх него накладываются
 * поля, явно указанные пользователем в JSON (deep merge). Так профиль даёт
 * разумные дефолты, а пользователь может точечно перекрыть любое значение.
 */
public final class ConfigLoader {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create();

    private ConfigLoader() {}

    /** Прочитать конфиг из файла. */
    public static ObfConfig load(Path path) throws IOException {
        String json = Files.readString(path, StandardCharsets.UTF_8);
        return parse(json);
    }

    /** Разобрать конфиг из строки JSON с применением профиля. */
    public static ObfConfig parse(String json) {
        JsonObject userObj = GSON.fromJson(json, JsonObject.class);
        if (userObj == null) {
            return new ObfConfig();
        }
        String profile = userObj.has("profile") && !userObj.get("profile").isJsonNull()
                ? userObj.get("profile").getAsString()
                : "custom";

        if (profile == null || profile.equalsIgnoreCase("custom")) {
            ObfConfig cfg = GSON.fromJson(userObj, ObfConfig.class);
            return cfg != null ? cfg : new ObfConfig();
        }

        // База из профиля -> JsonObject, затем deep-merge пользовательского поверх.
        ObfConfig preset = Profiles.preset(profile);
        JsonObject base = GSON.toJsonTree(preset).getAsJsonObject();
        deepMerge(base, userObj);
        ObfConfig cfg = GSON.fromJson(base, ObfConfig.class);
        return cfg != null ? cfg : new ObfConfig();
    }

    /** Рекурсивно накладывает override на base (мутирует base). */
    private static void deepMerge(JsonObject base, JsonObject override) {
        for (Map.Entry<String, JsonElement> e : override.entrySet()) {
            String key = e.getKey();
            JsonElement ov = e.getValue();
            JsonElement bv = base.get(key);
            if (bv != null && bv.isJsonObject() && ov.isJsonObject()) {
                deepMerge(bv.getAsJsonObject(), ov.getAsJsonObject());
            } else {
                // примитивы, массивы, null — заменяем целиком
                base.add(key, ov);
            }
        }
    }

    /** Записать конфиг в файл (используется для генерации шаблона). */
    public static void save(ObfConfig cfg, Path path) throws IOException {
        Files.writeString(path, GSON.toJson(cfg), StandardCharsets.UTF_8);
    }

    /** Сериализовать в строку. */
    public static String toJson(ObfConfig cfg) {
        return GSON.toJson(cfg);
    }

    /** Дефолтная конфигурация. */
    public static ObfConfig defaults() {
        return new ObfConfig();
    }
}
