package dev.toxi.obf;

import dev.toxi.obf.cli.Cli;
import dev.toxi.obf.gui.ObfGui;

/**
 * Точка входа. Диспетчеризует между GUI и CLI.
 *
 * Правила:
 *   - Аргументов нет -> пробуем запустить GUI (если есть дисплей), иначе help.
 *   - Есть флаг --gui -> GUI.
 *   - Иначе -> CLI.
 */
public final class Main {

    public static void main(String[] args) {
        boolean guiFlag = false;
        for (String a : args) {
            if (a.equals("--gui")) { guiFlag = true; break; }
        }

        boolean headless = java.awt.GraphicsEnvironment.isHeadless();

        if (guiFlag || (args.length == 0 && !headless)) {
            ObfGui.launch();
            return;
        }

        int code = Cli.run(args);
        if (code != 0) {
            System.exit(code);
        }
    }

    private Main() {}
}
