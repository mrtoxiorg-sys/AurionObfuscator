package dev.toxi.obf.gui;

import dev.toxi.obf.config.ConfigLoader;
import dev.toxi.obf.config.ObfConfig;
import dev.toxi.obf.core.Log;
import dev.toxi.obf.core.Obfuscator;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.File;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Графический интерфейс на Swing (нулевые внешние зависимости).
 *
 * Позволяет визуально настроить все опции обфускатора, выбрать файлы,
 * запустить процесс и увидеть лог в реальном времени. Логи маршрутизируются
 * через Log.setSink в текстовую панель.
 */
public final class ObfGui extends JFrame {

    private final JTextField inputField = new JTextField(30);
    private final JTextField outputField = new JTextField(30);
    private final JTextArea librariesArea = new JTextArea(3, 30);
    private final JTextArea keepArea = new JTextArea(3, 30);
    private final JTextField mappingsField = new JTextField(30);

    private final JCheckBox renameBox = new JCheckBox("Rename (классы/методы/поля)", true);
    private final JCheckBox stringsBox = new JCheckBox("Шифрование строк", true);
    private final JCheckBox flowBox = new JCheckBox("Control-flow обфускация", true);
    private final JCheckBox numbersBox = new JCheckBox("Обфускация чисел", true);
    private final JCheckBox debugBox = new JCheckBox("Удалять debug-инфо", true);
    private final JCheckBox fabricBox = new JCheckBox("Авто-детект Fabric (защита имён)", true);
    private final JCheckBox verboseBox = new JCheckBox("Подробный лог", false);

    private final JComboBox<String> dictBox =
            new JComboBox<>(new String[]{"illegal", "alpha", "dictionary"});
    private final JSpinner intensitySpinner =
            new JSpinner(new SpinnerNumberModel(2, 1, 5, 1));
    private final JTextField flattenField = new JTextField(15);

    private final JTextArea logArea = new JTextArea(14, 60);
    private final JButton runButton = new JButton("Обфусцировать");

    public ObfGui() {
        super("Aurion Obfuscator");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        add(buildTopPanel(), BorderLayout.NORTH);
        add(buildOptionsPanel(), BorderLayout.CENTER);
        add(buildLogPanel(), BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(null);

        // Маршрутизируем логи в текстовую панель
        Log.setSink(msg -> SwingUtilities.invokeLater(() -> {
            logArea.append(msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        }));
    }

    private JPanel buildTopPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new EmptyBorder(8, 8, 4, 8));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.WEST;

        int row = 0;
        addFileRow(p, c, row++, "Входной jar:", inputField, false);
        addFileRow(p, c, row++, "Выходной jar:", outputField, true);
        addFileRow(p, c, row++, "Mappings (опц.):", mappingsField, true);

        return p;
    }

    private void addFileRow(JPanel p, GridBagConstraints c, int row,
                            String label, JTextField field, boolean save) {
        c.gridx = 0; c.gridy = row; c.fill = GridBagConstraints.NONE;
        p.add(new JLabel(label), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
        p.add(field, c);
        c.gridx = 2; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        JButton browse = new JButton("...");
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            int r = save ? fc.showSaveDialog(this) : fc.showOpenDialog(this);
            if (r == JFileChooser.APPROVE_OPTION) {
                field.setText(fc.getSelectedFile().getAbsolutePath());
                // авто-подстановка выходного имени
                if (field == inputField && outputField.getText().isBlank()) {
                    File in = fc.getSelectedFile();
                    String name = in.getName().replaceFirst("\\.jar$", "") + "-obf.jar";
                    outputField.setText(new File(in.getParentFile(), name).getAbsolutePath());
                }
            }
        });
        p.add(browse, c);
    }

    private JPanel buildOptionsPanel() {
        JPanel wrap = new JPanel(new GridLayout(1, 2, 8, 8));
        wrap.setBorder(new EmptyBorder(4, 8, 4, 8));

        // Левая колонка: чекбоксы этапов
        JPanel stages = new JPanel();
        stages.setLayout(new BoxLayout(stages, BoxLayout.Y_AXIS));
        stages.setBorder(new TitledBorder("Этапы обфускации"));
        stages.add(renameBox);
        stages.add(stringsBox);
        stages.add(flowBox);
        stages.add(numbersBox);
        stages.add(debugBox);
        stages.add(Box.createVerticalStrut(6));
        stages.add(fabricBox);
        stages.add(verboseBox);

        // Правая колонка: параметры + keep/libs
        JPanel params = new JPanel(new GridBagLayout());
        params.setBorder(new TitledBorder("Параметры"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.WEST;

        int row = 0;
        c.gridx = 0; c.gridy = row; params.add(new JLabel("Стиль имён:"), c);
        c.gridx = 1; params.add(dictBox, c);
        row++;
        c.gridx = 0; c.gridy = row; params.add(new JLabel("Flow intensity:"), c);
        c.gridx = 1; params.add(intensitySpinner, c);
        row++;
        c.gridx = 0; c.gridy = row; params.add(new JLabel("Flatten package:"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        params.add(flattenField, c);
        c.fill = GridBagConstraints.NONE;
        row++;
        c.gridx = 0; c.gridy = row; c.anchor = GridBagConstraints.NORTHWEST;
        params.add(new JLabel("Libraries:"), c);
        c.gridx = 1; c.fill = GridBagConstraints.BOTH;
        params.add(new JScrollPane(librariesArea), c);
        row++;
        c.gridx = 0; c.gridy = row; c.fill = GridBagConstraints.NONE;
        params.add(new JLabel("Keep-правила:"), c);
        c.gridx = 1; c.fill = GridBagConstraints.BOTH;
        params.add(new JScrollPane(keepArea), c);

        wrap.add(stages);
        wrap.add(params);
        return wrap;
    }

    private JPanel buildLogPanel() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(new EmptyBorder(4, 8, 8, 8));

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        p.add(new JScrollPane(logArea), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton genConfig = new JButton("Сохранить конфиг…");
        genConfig.addActionListener(e -> saveConfig());
        JButton loadConfig = new JButton("Загрузить конфиг…");
        loadConfig.addActionListener(e -> loadConfig());
        runButton.addActionListener(e -> runObfuscation());
        buttons.add(loadConfig);
        buttons.add(genConfig);
        buttons.add(runButton);
        p.add(buttons, BorderLayout.SOUTH);
        return p;
    }

    // ---- Сборка конфига из полей ----
    private ObfConfig collectConfig() {
        ObfConfig cfg = new ObfConfig();
        cfg.input = blankToNull(inputField.getText());
        cfg.output = blankToNull(outputField.getText());
        cfg.mappingsOutput = blankToNull(mappingsField.getText());
        cfg.libraries = splitLines(librariesArea.getText());
        cfg.keep = splitLines(keepArea.getText());
        cfg.autoDetectFabric = fabricBox.isSelected();
        cfg.verbose = verboseBox.isSelected();

        cfg.rename.enabled = renameBox.isSelected();
        cfg.rename.dictionary = (String) dictBox.getSelectedItem();
        cfg.rename.flattenPackage = blankToNull(flattenField.getText());

        cfg.stringEncryption.enabled = stringsBox.isSelected();
        cfg.controlFlow.enabled = flowBox.isSelected();
        cfg.controlFlow.intensity = (Integer) intensitySpinner.getValue();
        cfg.numbers.enabled = numbersBox.isSelected();
        cfg.debugStrip.enabled = debugBox.isSelected();
        return cfg;
    }

    private void applyConfig(ObfConfig cfg) {
        inputField.setText(cfg.input == null ? "" : cfg.input);
        outputField.setText(cfg.output == null ? "" : cfg.output);
        mappingsField.setText(cfg.mappingsOutput == null ? "" : cfg.mappingsOutput);
        librariesArea.setText(String.join("\n", cfg.libraries));
        keepArea.setText(String.join("\n", cfg.keep));
        fabricBox.setSelected(cfg.autoDetectFabric);
        verboseBox.setSelected(cfg.verbose);
        renameBox.setSelected(cfg.rename.enabled);
        dictBox.setSelectedItem(cfg.rename.dictionary);
        flattenField.setText(cfg.rename.flattenPackage == null ? "" : cfg.rename.flattenPackage);
        stringsBox.setSelected(cfg.stringEncryption.enabled);
        flowBox.setSelected(cfg.controlFlow.enabled);
        intensitySpinner.setValue(cfg.controlFlow.intensity);
        numbersBox.setSelected(cfg.numbers.enabled);
        debugBox.setSelected(cfg.debugStrip.enabled);
    }

    private void runObfuscation() {
        ObfConfig cfg = collectConfig();
        if (cfg.input == null || cfg.output == null) {
            JOptionPane.showMessageDialog(this,
                    "Укажите входной и выходной jar.", "Ошибка",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        logArea.setText("");
        runButton.setEnabled(false);

        // фоновый поток, чтобы не блокировать UI
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                try {
                    new Obfuscator(cfg).run();
                } catch (Exception ex) {
                    Log.err("Ошибка: " + ex.getMessage());
                }
                return null;
            }
            @Override protected void done() {
                runButton.setEnabled(true);
            }
        }.execute();
    }

    private void saveConfig() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("obf-config.json"));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                ConfigLoader.save(collectConfig(), fc.getSelectedFile().toPath());
                Log.ok("Конфиг сохранён: " + fc.getSelectedFile());
            } catch (Exception ex) {
                Log.err("Не удалось сохранить: " + ex.getMessage());
            }
        }
    }

    private void loadConfig() {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                applyConfig(ConfigLoader.load(fc.getSelectedFile().toPath()));
                Log.ok("Конфиг загружен: " + fc.getSelectedFile());
            } catch (Exception ex) {
                Log.err("Не удалось загрузить: " + ex.getMessage());
            }
        }
    }

    // ---- helpers ----
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static java.util.List<String> splitLines(String s) {
        if (s == null || s.isBlank()) return new java.util.ArrayList<>();
        return Arrays.stream(s.split("\\R"))
                .map(String::trim)
                .filter(x -> !x.isEmpty())
                .collect(Collectors.toCollection(java.util.ArrayList::new));
    }

    public static void launch() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new ObfGui().setVisible(true));
    }
}
