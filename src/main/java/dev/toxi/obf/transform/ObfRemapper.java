package dev.toxi.obf.transform;

import dev.toxi.obf.core.ClassInfo;
import dev.toxi.obf.core.ClassPool;
import org.objectweb.asm.commons.Remapper;

/**
 * ASM Remapper, читающий из RenameMapping.
 *
 * ClassRemapper использует его для перезаписи ВСЕХ ссылок: типы в дескрипторах,
 * владельцы вызовов методов/полей, generic-сигнатуры, аннотации и т.д.
 *
 * ВАЖНО про иерархию: в байткоде вызов унаследованного метода часто имеет
 * owner = класс-наследник (например INVOKEVIRTUAL i1l1.addSetting), тогда как
 * сам метод и его маппинг объявлены в супер-классе (Module.addSetting). Поэтому
 * при resolve имени метода/поля мы обязаны искать маппинг ВВЕРХ по иерархии,
 * иначе вызов и определение разъедутся -> NoSuchMethodError в рантайме.
 */
public final class ObfRemapper extends Remapper {

    private final RenameMapping mapping;
    private final ClassPool pool;

    public ObfRemapper(RenameMapping mapping, ClassPool pool) {
        this.mapping = mapping;
        this.pool = pool;
    }

    @Override
    public String map(String internalName) {
        String mapped = mapping.classMap.get(internalName);
        return mapped != null ? mapped : internalName;
    }

    @Override
    public String mapMethodName(String owner, String name, String descriptor) {
        // спец-методы никогда не мапятся
        if (name.equals("<init>") || name.equals("<clinit>")) return name;

        // 1) прямое совпадение по owner
        String direct = mapping.methodMap.get(owner + "." + name + descriptor);
        if (direct != null) return direct;

        // 2) поиск вверх по иерархии: метод объявлен в супер-классе/интерфейсе,
        //    а вызов идёт через наследника.
        for (String sup : pool.allSuperTypes(owner)) {
            String mapped = mapping.methodMap.get(sup + "." + name + descriptor);
            if (mapped != null) return mapped;
        }
        return name;
    }

    @Override
    public String mapFieldName(String owner, String name, String descriptor) {
        // 1) прямое совпадение
        String direct = mapping.fieldMap.get(owner + "." + name);
        if (direct != null) return direct;

        // 2) вверх по иерархии (унаследованное поле)
        for (String sup : pool.allSuperTypes(owner)) {
            String mapped = mapping.fieldMap.get(sup + "." + name);
            if (mapped != null) return mapped;
        }
        return name;
    }

    @Override
    public String mapInvokeDynamicMethodName(String name, String descriptor) {
        // Лямбды: имя SAM-метода в bootstrap. Обычно ссылается на
        // функциональный интерфейс — если он в нашей карте, ClassRemapper
        // обработает через Handle. Само имя SAM оставляем, т.к. точный owner
        // здесь неизвестен; специфичные ссылки внутри bootstrap args мапятся
        // отдельно самим ClassRemapper.
        return name;
    }
}
