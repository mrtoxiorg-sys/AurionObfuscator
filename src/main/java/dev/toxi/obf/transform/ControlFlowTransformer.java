package dev.toxi.obf.transform;

import dev.toxi.obf.config.ObfConfig;
import dev.toxi.obf.core.ClassPool;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.objectweb.asm.Opcodes.*;

/**
 * Обфускация потока управления (control-flow).
 *
 * Техники (среднего уровня):
 *  1. Opaque predicates: вставка условных переходов, которые ВСЕГДА
 *     вычисляются одинаково, но декомпилятору это неочевидно. Используем
 *     статическое int-поле "seed", инициализируемое в <clinit>, и предикат
 *     вида (seed * seed >= 0) — всегда true для любого int из-за переполнения?
 *     Нет — используем математически строгий предикат: (x & 1) на известной
 *     чётности, либо ветвление к недостижимому "bogus"-блоку через переход,
 *     который реально всегда пропускается.
 *
 *  2. Bogus jumps: разбавление линейного кода фиктивными GOTO и мёртвыми
 *     блоками, которые сбивают реконструкцию управляющего графа.
 *
 * Реализация намеренно консервативна: мы не переупорядочиваем реальные
 * инструкции и не ломаем стек — только вставляем предикаты, которые
 * гарантированно проходят по "истинной" ветке. Это сохраняет корректность.
 */
public final class ControlFlowTransformer implements Transformer {

    private final ObfConfig cfg;
    private int injected = 0;

    /** Имя статического поля-семени, добавляемого в каждый обрабатываемый класс. */
    private static final String SEED_FIELD = "$cfseed";

    public ControlFlowTransformer(ObfConfig cfg) {
        this.cfg = cfg;
    }

    @Override public String name() { return "control-flow"; }
    @Override public boolean enabled() { return cfg.controlFlow.enabled; }
    public int injected() { return injected; }

    @Override
    public void transform(ClassPool pool, ClassNode cn) {
        // интерфейсы/аннотации/модули пропускаем — там нет исполняемого кода
        if ((cn.access & ACC_INTERFACE) != 0 || (cn.access & ACC_ANNOTATION) != 0
                || (cn.access & ACC_MODULE) != 0) {
            return;
        }

        boolean needSeed = false;
        int intensity = Math.max(1, cfg.controlFlow.intensity);

        for (MethodNode m : cn.methods) {
            if (m.instructions == null || m.instructions.size() == 0) continue;
            if (m.name.equals("<clinit>")) continue; // не трогаем статик-инициализатор
            // abstract/native не имеют тела
            if ((m.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0) continue;

            if (cfg.controlFlow.opaquePredicates) {
                if (insertOpaquePredicates(cn, m, intensity)) needSeed = true;
            }
            if (cfg.controlFlow.bogusJumps) {
                insertBogusJumps(m, intensity);
            }
        }

        if (needSeed) {
            ensureSeedField(cn);
        }
    }

    /**
     * Вставляет opaque predicate в начало метода: условный переход, который
     * ВСЕГДА идёт по реальной ветке, а фиктивная ведёт в мёртвый блок с throw.
     *
     * Предикат: {@code if ((seed & 1) == expectedParity) goto real; else bogus;}
     * где seed инициализируется чётным значением, а мы сравниваем с 0 — всегда
     * истина. Декомпилятор видит "неизвестное" поле и не может упростить.
     */
    private boolean insertOpaquePredicates(ClassNode cn, MethodNode m, int intensity) {
        InsnList list = m.instructions;
        AbstractInsnNode first = list.getFirst();
        if (first == null) return false;

        // Один предикат в начале (умножение intensity даёт больше, но начнём с 1
        // на метод + вероятностные внутри — безопаснее для валидности стека).
        LabelNode realStart = new LabelNode(new Label());
        LabelNode bogus = new LabelNode(new Label());

        InsnList pre = new InsnList();
        // GETSTATIC cn.$cfseed : I     (значение всегда чётное => &1 == 0)
        pre.add(new FieldInsnNode(GETSTATIC, cn.name, SEED_FIELD, "I"));
        pre.add(new InsnNode(ICONST_1));
        pre.add(new InsnNode(IAND));
        // if ((seed & 1) == 0) goto realStart   (всегда true)
        pre.add(new JumpInsnNode(IFEQ, realStart));

        // bogus-блок (мёртвый): throw new RuntimeException? Нет — просто
        // положим что-то и уйдём. Чтобы не заботиться о стеке метода, кидаем
        // исключение (ATHROW завершает поток управления, стек-независимо).
        pre.add(bogus);
        pre.add(new TypeInsnNode(NEW, "java/lang/ArithmeticException"));
        pre.add(new InsnNode(DUP));
        pre.add(new org.objectweb.asm.tree.MethodInsnNode(INVOKESPECIAL,
                "java/lang/ArithmeticException", "<init>", "()V", false));
        pre.add(new InsnNode(ATHROW));

        // realStart: <оригинальный код продолжается>
        pre.add(realStart);

        list.insertBefore(first, pre);
        injected++;
        return true;
    }

    /**
     * Вставляет фиктивные GOTO-цепочки: перед случайными точками добавляем
     * GOTO next; deadLabel: <ничего>; next: — это "рвёт" линейность в глазах
     * реконструкторов CFG, не меняя реального исполнения.
     */
    private void insertBogusJumps(MethodNode m, int intensity) {
        InsnList list = m.instructions;
        List<AbstractInsnNode> points = new ArrayList<>();
        for (AbstractInsnNode insn = list.getFirst(); insn != null; insn = insn.getNext()) {
            // только "безопасные" точки: после обычных инструкций, не в середине
            // фреймов/лейблов. Ограничим количество через intensity.
            if (insn.getType() == AbstractInsnNode.INSN
                    || insn.getType() == AbstractInsnNode.VAR_INSN) {
                points.add(insn);
            }
        }
        if (points.isEmpty()) return;

        int maxInserts = Math.min(intensity, points.size());
        for (int k = 0; k < maxInserts; k++) {
            AbstractInsnNode at = points.get(ThreadLocalRandom.current().nextInt(points.size()));
            LabelNode target = new LabelNode(new Label());
            InsnList repl = new InsnList();
            repl.add(new JumpInsnNode(GOTO, target)); // безусловный переход через "ничего"
            repl.add(target);
            list.insert(at, repl);
            injected++;
        }
    }

    /**
     * Добавляет static int $cfseed и инициализацию его чётным значением
     * в <clinit>. Если <clinit> нет — создаём.
     */
    private void ensureSeedField(ClassNode cn) {
        // поле уже есть?
        boolean has = cn.fields.stream().anyMatch(f -> f.name.equals(SEED_FIELD));
        if (!has) {
            cn.fields.add(new org.objectweb.asm.tree.FieldNode(
                    ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC | ACC_FINAL,
                    SEED_FIELD, "I", null, null));
        }

        MethodNode clinit = cn.methods.stream()
                .filter(m -> m.name.equals("<clinit>"))
                .findFirst().orElse(null);

        // чётное значение (случайное, но *2 гарантирует &1==0)
        int seedVal = ThreadLocalRandom.current().nextInt(1, 100000) * 2;

        InsnList init = new InsnList();
        init.add(NumberTransformer.pushInt(seedVal));
        init.add(new FieldInsnNode(PUTSTATIC, cn.name, SEED_FIELD, "I"));

        if (clinit == null) {
            clinit = new MethodNode(ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.instructions.add(init);
            clinit.instructions.add(new InsnNode(RETURN));
            cn.methods.add(clinit);
        } else {
            clinit.instructions.insert(init);
        }
    }
}
