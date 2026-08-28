package dev.toxi.obf.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Загрузка/сохранение конфигурации обфускатора в JSON.
 *
 * Используем pretty-printing и сериализацию null'ов, чтобы сгенерированный
 * дефолтный конфиг был самодокументируемым шаблоном для пользователя.
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
        ObfConfig cfg = GSON.fromJson(json, ObfConfig.class);
        if (cfg == null) {
            // пустой/битый файл -> дефолты
            cfg = new ObfConfig();
        }
        return cfg;
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
