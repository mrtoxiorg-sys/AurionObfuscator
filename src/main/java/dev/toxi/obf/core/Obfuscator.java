package dev.toxi.obf.core;

import dev.toxi.obf.config.ObfConfig;
import dev.toxi.obf.fabric.FabricDetector;
import dev.toxi.obf.fabric.FabricMetadata;
import dev.toxi.obf.transform.AntiDecompileTransformer;
import dev.toxi.obf.transform.ControlFlowTransformer;
import dev.toxi.obf.transform.DebugStripTransformer;
import dev.toxi.obf.transform.NumberTransformer;
import dev.toxi.obf.transform.ObfRemapper;
import dev.toxi.obf.transform.RenameMapping;
import dev.toxi.obf.transform.StringDecryptorGenerator;
import dev.toxi.obf.transform.StringEncryptTransformer;
import dev.toxi.obf.transform.Transformer;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.commons.ClassRemapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Оркестратор всего процесса обфускации.
 *
 * Порядок этапов важен:
 *   1. Загрузка input + library jar'ов, чтение ресурсов.
 *   2. Анализ Fabric-метаданных -> keep-правила.
 *   3. Instruction-level трансформации (string/number/control-flow/debug),
 *      выполняются ДО переименования, чтобы работать с исходными именами
 *      и корректно вставлять ссылки.
 *   4. Построение карты переименований и применение remapper'а (ПОСЛЕДНИМ,
 *      т.к. он переписывает все ссылки, включая только что вставленные вызовы
 *      декриптора — поэтому декриптор добавляем ДО и он тоже переименуется
 *      согласованно).
 *   5. Запись jar + mappings.
 */
public final class Obfuscator {

    private final ObfConfig cfg;

    public Obfuscator(ObfConfig cfg) {
        this.cfg = cfg;
    }

    /** Пара «трансформер + его targeting-матчер». */
    private record Stage(Transformer tx, TargetMatcher matcher) {}

    public void run() throws IOException {
        Log.setVerbose(cfg.verbose);

        if (cfg.input == null || cfg.output == null) {
            throw new IllegalArgumentException("Не заданы input и/или output пути");
        }
        Path in = Path.of(cfg.input);
        Path out = Path.of(cfg.output);
        if (!Files.exists(in)) {
            throw new IOException("Входной jar не найден: " + in);
        }

        long t0 = System.currentTimeMillis();
        Log.info("Вход:  " + in);
        Log.info("Выход: " + out);

        // ---- 1. Загрузка ----
        ClassPool pool = new ClassPool();
        pool.loadInputJar(in);
        for (String lib : cfg.libraries) {
            Path lp = Path.of(lib);
            if (Files.exists(lp)) {
                pool.loadLibraryJar(lp);
            } else {
                Log.warn("Библиотека не найдена, пропуск: " + lib);
            }
        }
        List<JarResource> resources = JarIO.readResources(in);
        Log.ok("Загружено классов: " + pool.size()
                + " (input: " + pool.inputClasses().size() + "), ресурсов: " + resources.size());

        // ---- 2. Fabric ----
        FabricMetadata fabric = new FabricMetadata();
        if (cfg.autoDetectFabric) {
            fabric = FabricDetector.analyze(resources);
            // защитить интерфейсы, реализуемые mixin-классами
            FabricDetector.protectMixinInterfaces(fabric, pool);
        }

        KeepRules keep = new KeepRules();
        keep.addAll(cfg.keep);

        // ---- 3. Генерация класса(ов)-декриптора строк (если нужен) ----
        String decryptorName = null;                 // shared
        List<String> poolNames = new ArrayList<>();  // pool
        StringEncryptTransformer stringTx = null;
        if (cfg.stringEncryption.enabled) {
            String strategy = cfg.stringEncryption.strategy == null
                    ? "shared" : cfg.stringEncryption.strategy;
            switch (strategy) {
                case "perClass" -> {
                    // дешифратор встраивается в каждый класс самим трансформером
                    stringTx = new StringEncryptTransformer(cfg, null, null);
                    Log.debug("String encryption: per-class встраиваемые дешифраторы"
                            + (cfg.stringEncryption.lazyArray ? " + lazy array" : ""));
                }
                case "pool" -> {
                    int n = Math.max(1, cfg.stringEncryption.poolSize);
                    for (int k = 0; k < n; k++) {
                        String nm = pickDecryptorName(pool);
                        while (poolNames.contains(nm)) nm = pickDecryptorName(pool);
                        poolNames.add(nm);
                        pool.addInput(StringDecryptorGenerator.generate(nm));
                    }
                    stringTx = new StringEncryptTransformer(cfg, null, poolNames);
                    Log.debug("String encryption: пул из " + n + " дешифраторов");
                }
                default -> {
                    decryptorName = pickDecryptorName(pool);
                    pool.addInput(StringDecryptorGenerator.generate(decryptorName));
                    stringTx = new StringEncryptTransformer(cfg, decryptorName, null);
                    Log.debug("String encryption: общий дешифратор " + decryptorName);
                }
            }
        }

        // ---- 3b. Instruction-level трансформеры ----
        // Каждый трансформер идёт в паре со своим TargetMatcher (include/exclude).
        List<Stage> stages = new ArrayList<>();
        if (stringTx != null) {
            stages.add(new Stage(stringTx,
                    new TargetMatcher(cfg.stringEncryption, cfg.excludePackages)));
        }
        NumberTransformer numberTx = new NumberTransformer(cfg);
        ControlFlowTransformer flowTx = new ControlFlowTransformer(cfg);
        DebugStripTransformer debugTx = new DebugStripTransformer(cfg);
        AntiDecompileTransformer antiTx = new AntiDecompileTransformer(cfg);
        if (numberTx.enabled()) {
            stages.add(new Stage(numberTx, new TargetMatcher(cfg.numbers, cfg.excludePackages)));
        }
        if (flowTx.enabled()) {
            stages.add(new Stage(flowTx, new TargetMatcher(cfg.controlFlow, cfg.excludePackages)));
        }
        if (antiTx.enabled()) {
            stages.add(new Stage(antiTx, new TargetMatcher(cfg.antiDecompile, cfg.excludePackages)));
        }
        if (debugTx.enabled()) {
            stages.add(new Stage(debugTx, new TargetMatcher(cfg.debugStrip, cfg.excludePackages)));
        }

        // множество сгенерированных декрипторов (не шифруем их строки — их там нет,
        // и не встраиваем в них per-class дешифратор)
        java.util.Set<String> decryptorClasses = new java.util.HashSet<>();
        if (decryptorName != null) decryptorClasses.add(decryptorName);
        decryptorClasses.addAll(poolNames);

        for (Stage st : stages) {
            Transformer tx = st.tx;
            if (!tx.enabled()) continue;
            int applied = 0;
            for (ClassInfo ci : pool.inputClasses()) {
                // не трансформируем сами декрипторы строковым шифрованием
                if (tx == stringTx && decryptorClasses.contains(ci.node.name)) continue;
                // per-class targeting
                if (!st.matcher.allows(ci.node.name)) continue;
                tx.transform(pool, ci.node);
                applied++;
            }
            Log.ok("Этап '" + tx.name() + "' применён к " + applied + " классам");
        }
        if (stringTx != null) Log.info("Зашифровано строк: " + stringTx.encryptedCount());
        if (numberTx.enabled()) Log.info("Обфусцировано чисел: " + numberTx.count());
        if (flowTx.enabled()) Log.info("Вставлено flow-конструкций: " + flowTx.injected());
        if (antiTx.enabled()) Log.info("Анти-декомпилятор вставок: " + antiTx.injected());

        // ---- 4. Rename ----
        List<ClassNode> outputNodes = new ArrayList<>();
        if (cfg.rename.enabled) {
            RenameMapping mapping = new RenameMapping(pool, cfg, keep, fabric);
            mapping.build();

            ObfRemapper remapper = new ObfRemapper(mapping, pool);
            for (ClassInfo ci : pool.inputClasses()) {
                ClassNode remapped = new ClassNode();
                ClassRemapper cr = new ClassRemapper(remapped, remapper);
                ci.node.accept(cr);
                outputNodes.add(remapped);
            }

            if (cfg.mappingsOutput != null) {
                writeMappings(Path.of(cfg.mappingsOutput), mapping);
            }
        } else {
            for (ClassInfo ci : pool.inputClasses()) {
                outputNodes.add(ci.node);
            }
        }

        // ---- 5. Запись ----
        JarIO.write(out, pool, outputNodes, resources);

        long dt = System.currentTimeMillis() - t0;
        Log.ok("Готово за " + dt + " мс. Классов записано: " + outputNodes.size());
    }

    /**
     * Выбирает нечитаемое имя для класса-декриптора, не конфликтующее с
     * существующими. Первый символ НЕ 'L'/'I' — иначе дескриптор L<name>;
     * (напр. LIl3f;) ломает парсер дескрипторов Fabric Mixin.
     */
    private String pickDecryptorName(ClassPool pool) {
        String[] pool2 = {"jl", "lj", "jj", "ll", "lI", "jI"};
        for (int i = 0; i < 10000; i++) {
            String candidate = pool2[ThreadLocalRandom.current().nextInt(pool2.length)]
                    + Integer.toHexString(ThreadLocalRandom.current().nextInt(0xFFFF));
            if (!pool.contains(candidate)) return candidate;
        }
        return "obf$strings";
    }

    private void writeMappings(Path path, RenameMapping mapping) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Aurion Obfuscator mappings (original -> obfuscated)\n\n");
        sb.append("# Classes\n");
        Map<String, String> sortedClasses = new TreeMap<>(mapping.classMap);
        for (var e : sortedClasses.entrySet()) {
            sb.append(e.getKey().replace('/', '.'))
              .append(" -> ")
              .append(e.getValue().replace('/', '.'))
              .append('\n');
        }
        sb.append("\n# Methods\n");
        Map<String, String> sortedMethods = new TreeMap<>(mapping.methodMap);
        for (var e : sortedMethods.entrySet()) {
            sb.append(e.getKey()).append(" -> ").append(e.getValue()).append('\n');
        }
        sb.append("\n# Fields\n");
        Map<String, String> sortedFields = new TreeMap<>(mapping.fieldMap);
        for (var e : sortedFields.entrySet()) {
            sb.append(e.getKey()).append(" -> ").append(e.getValue()).append('\n');
        }
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        Log.ok("Mappings записаны: " + path);
    }
}
