package dev.toxi.obf.transform;

import dev.toxi.obf.config.ObfConfig;
import dev.toxi.obf.core.ClassInfo;
import dev.toxi.obf.core.ClassPool;
import dev.toxi.obf.core.KeepRules;
import dev.toxi.obf.core.Log;
import dev.toxi.obf.fabric.FabricMetadata;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Строит и хранит карту переименований: старое internal-имя -> новое.
 *
 * Три уровня:
 *   classMap  : "a/b/C"        -> "x/y/Z"
 *   methodMap : "a/b/C.m(desc)"-> "n"
 *   fieldMap  : "a/b/C.f"      -> "n"
 *
 * Метод/поле идентифицируются по OWNER + имя (+desc для методов).
 *
 * Правила безопасности (что НЕ переименовываем):
 *   1. Классы из keep-правил и Fabric keep (entrypoints, mixins).
 *   2. Методы, переопределяющие/имплементящие библиотечные (Minecraft/JDK) методы.
 *   3. Методы <init>, <clinit>, main (спец-семантика).
 *   4. Члены mixin-классов (они инъектятся в чужие классы, лучше не трогать).
 *   5. Enum-специфичные (values/valueOf) и synthetic bridge — оставляем.
 *   6. Поля/методы под keep-правилами по имени.
 */
public final class RenameMapping {

    public final Map<String, String> classMap = new HashMap<>();
    public final Map<String, String> methodMap = new HashMap<>();
    public final Map<String, String> fieldMap = new HashMap<>();

    private final ClassPool pool;
    private final ObfConfig cfg;
    private final KeepRules keep;
    private final FabricMetadata fabric;
    private final NameGenerator classNames;
    private final NameGenerator memberNames;

    public RenameMapping(ClassPool pool, ObfConfig cfg, KeepRules keep, FabricMetadata fabric) {
        this.pool = pool;
        this.cfg = cfg;
        this.keep = keep;
        this.fabric = fabric;
        this.classNames = new NameGenerator(cfg.rename.dictionary, cfg.rename.prefix);
        this.memberNames = new NameGenerator(cfg.rename.dictionary, "");
    }

    public void build() {
        // 1) Классы
        if (cfg.rename.classes) {
            buildClassMap();
        }
        // 2) Поля
        if (cfg.rename.fields) {
            buildFieldMap();
        }
        // 3) Методы (с учётом иерархии для override)
        if (cfg.rename.methods) {
            buildMethodMap();
        }
        Log.ok("Карта переименований: классов=" + classMap.size()
                + ", методов=" + methodMap.size()
                + ", полей=" + fieldMap.size());
    }

    // ---------------------------------------------------------
    //  Классы
    // ---------------------------------------------------------
    private void buildClassMap() {
        String flatten = cfg.rename.flattenPackage; // null = сохранить пакеты
        for (ClassInfo ci : pool.inputClasses()) {
            String name = ci.node.name;
            if (!canRenameClass(ci)) continue;

            String newSimple = classNames.next();
            String newName;
            if (flatten != null) {
                newName = flatten.isEmpty() ? newSimple : flatten.replace('.', '/') + "/" + newSimple;
            } else {
                // сохраняем пакет, меняем только simple-имя
                int slash = name.lastIndexOf('/');
                String pkg = slash >= 0 ? name.substring(0, slash + 1) : "";
                newName = pkg + newSimple;
            }
            classMap.put(name, newName);
        }
    }

    private boolean canRenameClass(ClassInfo ci) {
        String name = ci.node.name;
        if (ci.isLibrary) return false;
        if (keep.matchesClass(name)) return false;
        if (fabric.keepClassNames.contains(name)) return false;
        // package-info — не переименовываем
        if (name.endsWith("/package-info")) return false;
        // Классы в excludePackages
        String dotted = name.replace('/', '.');
        for (String ex : cfg.excludePackages) {
            String p = ex.replace('/', '.');
            if (dotted.startsWith(p + ".") || dotted.equals(p)) return false;
        }
        return true;
    }

    // ---------------------------------------------------------
    //  Поля
    // ---------------------------------------------------------
    private void buildFieldMap() {
        for (ClassInfo ci : pool.inputClasses()) {
            if (isFullyKept(ci)) continue;
            ClassNode cn = ci.node;
            boolean isEnum = (cn.access & Opcodes.ACC_ENUM) != 0;
            for (FieldNode f : cn.fields) {
                if (!canRenameField(cn, f, isEnum)) continue;
                fieldMap.put(cn.name + "." + f.name, memberNames.next());
            }
        }
    }

    private boolean canRenameField(ClassNode cn, FieldNode f, boolean isEnum) {
        // enum-константы: их имена завязаны на valueOf(String) и сериализацию
        if (isEnum && (f.access & Opcodes.ACC_ENUM) != 0) return false;
        // $VALUES synthetic для enum
        if (f.name.equals("$VALUES")) return false;
        // serialVersionUID
        if (f.name.equals("serialVersionUID")) return false;
        if (keep.matchesMember(cn.name, f.name)) return false;
        if (fabric.fullyKeptClasses.contains(cn.name)) return false;
        return true;
    }

    // ---------------------------------------------------------
    //  Методы
    // ---------------------------------------------------------
    private void buildMethodMap() {
        // Множество уже обработанных (owner.name+desc) чтобы не переименовать дважды
        Set<String> processed = new HashSet<>();

        for (ClassInfo ci : pool.inputClasses()) {
            if (isFullyKept(ci)) continue;
            ClassNode cn = ci.node;
            for (MethodNode m : cn.methods) {
                String key = cn.name + "." + m.name + m.desc;
                if (processed.contains(key)) continue;
                if (!canRenameMethod(cn, m)) continue;

                // Группа классов в иерархии, где этот метод должен иметь то же имя
                Set<String> group = pool.hierarchyGroup(cn.name);

                // Проверяем: если метод в ЛЮБОМ классе группы является override
                // библиотечного метода — не переименовываем во всей группе.
                boolean overridesLib = false;
                for (String g : group) {
                    if (pool.overridesLibraryMethod(g, m.name, m.desc)) {
                        overridesLib = true;
                        break;
                    }
                }
                if (overridesLib) continue;

                // Проверяем keep для любого класса группы
                boolean keptSomewhere = false;
                for (String g : group) {
                    ClassInfo gi = pool.get(g);
                    if (gi != null && gi.isLibrary) { keptSomewhere = true; break; }
                    if (keep.matchesMember(g, m.name)) { keptSomewhere = true; break; }
                    if (fabric.fullyKeptClasses.contains(g)) { keptSomewhere = true; break; }
                }
                if (keptSomewhere) continue;

                String newName = memberNames.next();
                // Применяем ко всем классам группы, у кого есть метод с таким name+desc
                for (String g : group) {
                    ClassInfo gi = pool.get(g);
                    if (gi == null || gi.isLibrary) continue;
                    for (MethodNode gm : gi.node.methods) {
                        if (gm.name.equals(m.name) && gm.desc.equals(m.desc)) {
                            methodMap.put(g + "." + m.name + m.desc, newName);
                            processed.add(g + "." + m.name + m.desc);
                        }
                    }
                }
            }
        }
    }

    private boolean canRenameMethod(ClassNode cn, MethodNode m) {
        // Конструкторы / статический инициализатор
        if (m.name.equals("<init>") || m.name.equals("<clinit>")) return false;
        // main — точка входа JVM
        if (m.name.equals("main") && m.desc.equals("([Ljava/lang/String;)V")
                && (m.access & Opcodes.ACC_STATIC) != 0) return false;
        // enum values/valueOf
        if (m.name.equals("values") || m.name.equals("valueOf")) {
            if ((cn.access & Opcodes.ACC_ENUM) != 0) return false;
        }
        // entrypoint/mixin/mixin-интерфейсы — члены не трогаем
        if (fabric.fullyKeptClasses.contains(cn.name)) return false;
        // keep по имени
        if (keep.matchesMember(cn.name, m.name)) return false;
        return true;
    }

    // ---------------------------------------------------------
    //  Вспомогательное
    // ---------------------------------------------------------
    /** Класс полностью защищён (не трогаем ни методы, ни поля). */
    private boolean isFullyKept(ClassInfo ci) {
        if (ci.isLibrary) return true;
        // entrypoint-классы (loader зовёт onInitialize и пр.), mixin-классы
        // и mixin-интерфейсы — их члены переименовывать нельзя.
        return fabric.fullyKeptClasses.contains(ci.node.name);
    }
}
