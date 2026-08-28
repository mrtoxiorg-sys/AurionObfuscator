package dev.toxi.obf.cli;

import dev.toxi.obf.config.ConfigLoader;
import dev.toxi.obf.config.ObfConfig;
import dev.toxi.obf.core.Log;
import dev.toxi.obf.core.Obfuscator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Простой, самодостаточный CLI-парсер (без внешних зависимостей).
 *
 * Использование:
 *   obf -i mod.jar -o mod-obf.jar [опции]
 *   obf -c config.json                       (всё из конфига)
 *   obf --gen-config config.json             (сгенерировать шаблон)
 *
 * Опции переопределяют значения из --config.
 */
public final class Cli {

    public static int run(String[] args) {
        if (args.length == 0) {
            printHelp();
            return 1;
        }

        ObfConfig cfg = null;
        String genConfigPath = null;
        List<String> libs = new ArrayList<>();
        List<String> keeps = new ArrayList<>();

        // временные переопределения
        String input = null, output = null, mappings = null;
        Boolean verbose = null;
        Boolean noRename = null, noStrings = null, noFlow = null, noNumbers = null, noDebug = null;
        String dict = null, flatten = null;
        Integer intensity = null;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h": case "--help":
                    printHelp();
                    return 0;
                case "-c": case "--config":
                    try {
                        cfg = ConfigLoader.load(Path.of(req(args, ++i, a)));
                    } catch (Exception e) {
                        Log.err("Не удалось прочитать конфиг: " + e.getMessage());
                        return 2;
                    }
                    break;
                case "--gen-config":
                    genConfigPath = req(args, ++i, a);
                    break;
                case "-i": case "--input":
                    input = req(args, ++i, a);
                    break;
                case "-o": case "--output":
                    output = req(args, ++i, a);
                    break;
                case "-l": case "--library":
                    libs.add(req(args, ++i, a));
                    break;
                case "-k": case "--keep":
                    keeps.add(req(args, ++i, a));
                    break;
                case "-m": case "--mappings":
                    mappings = req(args, ++i, a);
                    break;
                case "-v": case "--verbose":
                    verbose = true;
                    break;
                case "--dictionary":
                    dict = req(args, ++i, a);
                    break;
                case "--flatten":
                    flatten = req(args, ++i, a);
                    break;
                case "--intensity":
                    intensity = Integer.parseInt(req(args, ++i, a));
                    break;
                case "--no-rename":     noRename = true; break;
                case "--no-strings":    noStrings = true; break;
                case "--no-flow":       noFlow = true; break;
                case "--no-numbers":    noNumbers = true; break;
                case "--no-debug-strip":noDebug = true; break;
                default:
                    Log.err("Неизвестный аргумент: " + a);
                    printHelp();
                    return 2;
            }
        }

        // Генерация шаблона конфига
        if (genConfigPath != null) {
            try {
                ObfConfig def = ConfigLoader.defaults();
                ConfigLoader.save(def, Path.of(genConfigPath));
                Log.ok("Шаблон конфига записан: " + genConfigPath);
                return 0;
            } catch (Exception e) {
                Log.err("Ошибка записи конфига: " + e.getMessage());
                return 2;
            }
        }

        if (cfg == null) cfg = ConfigLoader.defaults();

        // применяем переопределения
        if (input != null) cfg.input = input;
        if (output != null) cfg.output = output;
        if (mappings != null) cfg.mappingsOutput = mappings;
        if (verbose != null) cfg.verbose = verbose;
        if (!libs.isEmpty()) cfg.libraries.addAll(libs);
        if (!keeps.isEmpty()) cfg.keep.addAll(keeps);
        if (dict != null) cfg.rename.dictionary = dict;
        if (flatten != null) cfg.rename.flattenPackage = flatten;
        if (intensity != null) cfg.controlFlow.intensity = intensity;
        if (Boolean.TRUE.equals(noRename)) cfg.rename.enabled = false;
        if (Boolean.TRUE.equals(noStrings)) cfg.stringEncryption.enabled = false;
        if (Boolean.TRUE.equals(noFlow)) cfg.controlFlow.enabled = false;
        if (Boolean.TRUE.equals(noNumbers)) cfg.numbers.enabled = false;
        if (Boolean.TRUE.equals(noDebug)) cfg.debugStrip.enabled = false;

        if (cfg.input == null || cfg.output == null) {
            Log.err("Нужно указать --input и --output (или задать их в конфиге).");
            printHelp();
            return 2;
        }

        // авто-имя mappings при выводе, если не задано, но verbose
        try {
            new Obfuscator(cfg).run();
            return 0;
        } catch (Exception e) {
            Log.err("Обфускация провалилась: " + e.getMessage());
            if (cfg.verbose) e.printStackTrace();
            return 3;
        }
    }

    private static String req(String[] args, int i, String flag) {
        if (i >= args.length) {
            throw new IllegalArgumentException("Флаг " + flag + " требует значение");
        }
        return args[i];
    }

    private static void printHelp() {
        String help = """
            Aurion Obfuscator — универсальный обфускатор Java (приоритет: моды Minecraft)

            ИСПОЛЬЗОВАНИЕ:
              obf -i <вход.jar> -o <выход.jar> [опции]
              obf -c <config.json>
              obf --gen-config <config.json>
              obf --gui                        (запустить графический интерфейс)

            ОСНОВНЫЕ:
              -i, --input <jar>       Входной jar
              -o, --output <jar>      Выходной jar
              -c, --config <json>     Загрузить конфигурацию из JSON
              -l, --library <jar>     Библиотека для анализа (можно несколько)
              -k, --keep <pattern>    Keep-правило (можно несколько)
              -m, --mappings <file>   Записать mappings (original -> obf)
              -v, --verbose           Подробный лог

            НАСТРОЙКА RENAME:
              --dictionary <style>    Стиль имён: alpha | illegal | dictionary
              --flatten <package>     Свести все классы в один пакет ("" = корень)

            CONTROL FLOW:
              --intensity <1-5>       Интенсивность запутывания потока

            ОТКЛЮЧЕНИЕ ЭТАПОВ:
              --no-rename             Не переименовывать
              --no-strings            Не шифровать строки
              --no-flow               Без control-flow обфускации
              --no-numbers            Без обфускации чисел
              --no-debug-strip        Не удалять debug-инфо

            KEEP-СИНТАКСИС:
              com.example.Foo         класс
              com.example.Foo.bar     член класса
              com.example.*           классы в пакете
              com.example.**          классы в пакете и вложенных

            ПРИМЕР (Fabric-мод):
              obf -i mod.jar -o mod-obf.jar --dictionary illegal -v
            """;
        System.out.println(help);
    }

    private Cli() {}
}
