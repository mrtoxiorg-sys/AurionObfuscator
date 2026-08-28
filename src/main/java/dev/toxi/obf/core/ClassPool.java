package dev.toxi.obf.core;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Реестр всех загруженных классов (внутренние имена вида a/b/C).
 *
 * Ключевые задачи:
 *  1. Хранить input-классы (обфусцируемые) и library-классы (только для анализа).
 *  2. Строить граф наследования (супер-классы + интерфейсы) — критично для
 *     корректного переименования override-методов: если метод переопределяет
 *     метод из библиотеки (Minecraft/JDK), его нельзя переименовывать, а если
 *     переопределяет метод внутри нашей иерархии — переименовать надо
 *     СОГЛАСОВАННО во всех классах цепочки.
 */
public final class ClassPool {

    /** Все классы по internal name. */
    private final Map<String, ClassInfo> classes = new LinkedHashMap<>();

    // --- Загрузка ---

    /** Загрузить классы из входного jar (обфусцируемые). */
    public void loadInputJar(Path jar) throws IOException {
        load(jar, false);
    }

    /** Загрузить библиотечный jar (только анализ, не трогаем). */
    public void loadLibraryJar(Path jar) throws IOException {
        load(jar, true);
    }

    private void load(Path jar, boolean library) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            var entries = jf.entries();
            int count = 0;
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory() || !e.getName().endsWith(".class")) continue;
                try (InputStream in = jf.getInputStream(e)) {
                    ClassReader cr = new ClassReader(in);
                    ClassNode cn = new ClassNode();
                    // SKIP_FRAMES: фреймы пересчитаем при записи (COMPUTE_FRAMES),
                    // это устраняет проблемы после наших трансформаций.
                    cr.accept(cn, ClassReader.SKIP_FRAMES);
                    // library-классы, уже присутствующие как input, не перезатираем
                    if (library && classes.containsKey(cn.name)) continue;
                    classes.put(cn.name, new ClassInfo(cn, library));
                    count++;
                }
            }
            Log.debug("Загружено " + count + " классов из " + jar.getFileName()
                    + (library ? " (library)" : " (input)"));
        }
    }

    /** Добавить сгенерированный класс как input (например, класс-декриптор). */
    public void addInput(ClassNode cn) {
        classes.put(cn.name, new ClassInfo(cn, false));
    }

    // --- Доступ ---

    public ClassInfo get(String internalName) {
        return classes.get(internalName);
    }

    public boolean contains(String internalName) {
        return classes.containsKey(internalName);
    }

    public Collection<ClassInfo> all() {
        return classes.values();
    }

    /** Только input-классы (те, что будем трансформировать и писать в выход). */
    public List<ClassInfo> inputClasses() {
        List<ClassInfo> out = new ArrayList<>();
        for (ClassInfo ci : classes.values()) {
            if (!ci.isLibrary) out.add(ci);
        }
        return out;
    }

    public boolean isInput(String internalName) {
        ClassInfo ci = classes.get(internalName);
        return ci != null && !ci.isLibrary;
    }

    // --- Иерархия наследования ---

    /**
     * Все супер-типы класса (транзитивно): супер-классы + интерфейсы.
     * Возвращает internal-имена. Классы, которых нет в пуле (например, из
     * рантайм-JDK, если он не загружен как library), просто отсутствуют —
     * это нормально и обрабатывается вызывающим кодом.
     */
    public Set<String> allSuperTypes(String internalName) {
        Set<String> result = new HashSet<>();
        collectSupers(internalName, result);
        return result;
    }

    private void collectSupers(String name, Set<String> acc) {
        ClassInfo ci = classes.get(name);
        if (ci == null) return;
        String sup = ci.node.superName;
        if (sup != null && acc.add(sup)) {
            collectSupers(sup, acc);
        }
        if (ci.node.interfaces != null) {
            for (String itf : ci.node.interfaces) {
                if (acc.add(itf)) {
                    collectSupers(itf, acc);
                }
            }
        }
    }

    /**
     * Есть ли среди предков класса хоть один LIBRARY-класс, объявляющий
     * метод с данным именем+дескриптором. Если да — метод считается
     * "торчащим наружу" (override/implement библиотечного API) и переименовывать
     * его нельзя.
     */
    public boolean overridesLibraryMethod(String owner, String methodName, String desc) {
        Set<String> supers = allSuperTypes(owner);
        for (String s : supers) {
            ClassInfo ci = classes.get(s);
            if (ci == null) {
                // Супертип не в пуле => предполагаем, что это внешний
                // (JDK/MC) тип. Безопаснее считать метод внешним, если
                // это стандартные Object-методы либо неизвестный тип.
                continue;
            }
            if (ci.isLibrary) {
                for (var m : ci.node.methods) {
                    if (m.name.equals(methodName) && m.desc.equals(desc)) {
                        return true;
                    }
                }
            }
        }
        // Спец-случай: методы Object всегда внешние.
        return isObjectMethod(methodName, desc);
    }

    private static boolean isObjectMethod(String name, String desc) {
        switch (name + desc) {
            case "toString()Ljava/lang/String;":
            case "hashCode()I":
            case "equals(Ljava/lang/Object;)Z":
            case "clone()Ljava/lang/Object;":
            case "finalize()V":
                return true;
            default:
                return false;
        }
    }

    /**
     * Собрать ПОЛНЫЙ связный компонент иерархии, содержащий данный класс.
     *
     * Наивный вариант "супертипы + прямые наследники" НЕ ловит sibling-классы:
     * если A abstract, B extends A и C extends A, то начиная от B мы не увидим C
     * (C не супер и не наследник B). Но метод, объявленный в A и реализованный
     * в B и C, ОБЯЗАН получить одно имя во всех троих — иначе AbstractMethodError.
     *
     * Поэтому вычисляем транзитивное замыкание отношения "связан по наследованию":
     * многократно расширяем множество, добавляя всех супертипов и всех потомков
     * каждого члена, пока оно не перестанет расти. Это гарантирует, что A, B и C
     * окажутся в одной группе.
     */
    public Set<String> hierarchyGroup(String internalName) {
        Set<String> group = new HashSet<>();
        group.add(internalName);

        boolean changed = true;
        while (changed) {
            changed = false;
            // копия для итерации
            Set<String> snapshot = new HashSet<>(group);
            for (String member : snapshot) {
                // вверх: все супертипы (включая library — они нужны для проверки
                // override, но расширять ВНИЗ от них нельзя, см. ниже)
                Set<String> supers = new HashSet<>();
                collectSupers(member, supers);
                if (group.addAll(supers)) changed = true;

                // вниз: расширяемся только от "своих" точек — иначе через
                // общий предок (Object/Enum/Record или любой library-класс)
                // затянем весь мир. Расширение вниз выполняем только если member
                // сам является input-классом и не является универсальным предком.
                if (!isExpandableDown(member)) continue;
                for (ClassInfo ci : classes.values()) {
                    if (ci.isLibrary) continue; // вниз тянем только input-классы
                    if (group.contains(ci.node.name)) continue;
                    if (allSuperTypes(ci.node.name).contains(member)) {
                        if (group.add(ci.node.name)) changed = true;
                    }
                }
            }
        }
        // финально: из группы убираем универсальные/library-корни как "точки
        // связи" не нужно — они остаются в множестве, но переименование их
        // методов и так запрещено (library). Однако сам факт присутствия Object
        // безвреден: методы Object в карту не попадают.
        return group;
    }

    /**
     * Можно ли расширять группу ВНИЗ от данного класса. Нельзя от:
     *  - library-классов (не наши, у них тысячи наследников),
     *  - универсальных корней (Object/Enum/Record), которые связали бы
     *    несвязанные ветки в один гигантский компонент.
     */
    private boolean isExpandableDown(String internalName) {
        if (internalName == null) return false;
        switch (internalName) {
            case "java/lang/Object":
            case "java/lang/Enum":
            case "java/lang/Record":
                return false;
            default:
                break;
        }
        ClassInfo ci = classes.get(internalName);
        // если класса нет в пуле (внешний) или он library — не расширяем вниз
        return ci != null && !ci.isLibrary;
    }

    public int size() {
        return classes.size();
    }
}
