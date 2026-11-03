package com.jonasmelheb.younesh;

import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.font.FontRenderContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Fenêtres de dialogue : saisie, discussion, réglages et palette rapide.
final class Dialogs {
  private Dialogs() {
  }

  static String input(Component parent, String title, String label, String initial) {
    JTextField field = new JTextField(initial, 24);
    JPanel p = form();
    row(p, 0, label, field);
    SwingUtilities.invokeLater(() -> {
      field.requestFocusInWindow();
      field.selectAll();
    });
    int r = JOptionPane.showConfirmDialog(parent, p, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
    return r == JOptionPane.OK_OPTION ? field.getText() : null;
  }

  private record Choice(String id, String label) {
    @Override
    public String toString() {
      return label;
    }
  }

  // Création ou modification d'une discussion. Renvoie false si on annule.
  static boolean editSession(Younesh app, Store.Session s, boolean isNew) {
    boolean claude = "claude".equals(s.kind);
    JTextField name = new JTextField(s.name, 26);
    JComboBox<Choice> folder = new JComboBox<>();
    folder.addItem(new Choice(null, "Hors dossier"));
    for (Store.Folder f : app.store.folders) {
      Choice c = new Choice(f.id, f.name);
      folder.addItem(c);
      if (f.id.equals(s.folderId)) folder.setSelectedItem(c);
    }
    JTextField cwd = new JTextField(s.cwd == null ? "" : s.cwd, 26);
    cwd.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Dossier personnel");
    JButton browse = new JButton("Parcourir…");
    browse.addActionListener(e -> {
      JFileChooser fc = new JFileChooser(cwd.getText().isBlank() ? System.getProperty("user.home") : cwd.getText());
      fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
      if (fc.showOpenDialog(app) == JFileChooser.APPROVE_OPTION) {
        cwd.setText(fc.getSelectedFile().getAbsolutePath());
        if (name.getText().equals("Terminal") || name.getText().equals("Claude")) {
          name.setText(fc.getSelectedFile().getName());
        }
      }
    });
    JPanel dir = new JPanel(new BorderLayout(6, 0));
    dir.add(cwd, BorderLayout.CENTER);
    dir.add(browse, BorderLayout.EAST);
    JComboBox<Shells.Shell> shell = new JComboBox<>(Shells.detect().toArray(Shells.Shell[]::new));
    Shells.Shell current = Shells.find(s.shell != null ? s.shell : app.store.settings.defaultShell);
    if (current != null) shell.setSelectedItem(current);
    JTextField command = new JTextField(s.command == null ? "" : s.command, 26);
    command.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "ex. npm run dev");

    JPanel p = form();
    int y = 0;
    row(p, y++, "Nom", name);
    row(p, y++, "Dossier", folder);
    row(p, y++, "Répertoire", dir);
    row(p, y++, "Shell", shell);
    if (!claude) row(p, y++, "Commande au démarrage", command);
    if (claude) row(p, y, "", hint("Lance « claude » dans ce répertoire. La conversation reprend au prochain lancement."));

    SwingUtilities.invokeLater(() -> {
      name.requestFocusInWindow();
      name.selectAll();
    });
    String title = isNew ? (claude ? "Nouvelle session Claude" : "Nouvelle discussion") : "Modifier la discussion";
    int r = JOptionPane.showConfirmDialog(app, p, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
    if (r != JOptionPane.OK_OPTION) return false;
    s.name = name.getText().isBlank() ? (claude ? "Claude" : "Terminal") : name.getText().trim();
    s.folderId = ((Choice) folder.getSelectedItem()).id();
    s.cwd = cwd.getText().trim();
    Shells.Shell sh = (Shells.Shell) shell.getSelectedItem();
    s.shell = sh == null ? null : sh.id();
    if (!claude) s.command = command.getText().trim();
    return true;
  }

  static void settings(Younesh app) {
    Store.Settings st = app.store.settings;
    JComboBox<String> theme = new JComboBox<>();
    Theme.ALL.values().forEach(t -> theme.addItem(t.name));
    theme.setSelectedItem(Theme.of(st.theme).name);

    JComboBox<String> font = new JComboBox<>();
    font.addItem("(automatique)");
    monospaceFonts().forEach(font::addItem);
    font.setSelectedItem(st.fontFamily == null || st.fontFamily.isBlank() ? "(automatique)" : st.fontFamily);
    JSpinner size = new JSpinner(new SpinnerNumberModel(st.fontSize, 8, 32, 1));
    JSpinner scrollback = new JSpinner(new SpinnerNumberModel(st.scrollback, 500, 100000, 500));

    JComboBox<Shells.Shell> shell = new JComboBox<>(Shells.detect().toArray(Shells.Shell[]::new));
    Shells.Shell current = Shells.find(st.defaultShell);
    if (current != null) shell.setSelectedItem(current);
    JTextField cwd = new JTextField(st.defaultCwd == null ? "" : st.defaultCwd, 24);
    cwd.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Dossier personnel");
    JCheckBox notify = new JCheckBox("Notifier quand Claude attend une réponse", st.notify);

    JPanel p = form();
    int y = 0;
    row(p, y++, "Thème", theme);
    row(p, y++, "Police", font);
    row(p, y++, "Taille", size);
    row(p, y++, "Historique (lignes)", scrollback);
    row(p, y++, "Shell par défaut", shell);
    row(p, y++, "Répertoire par défaut", cwd);
    row(p, y++, "", notify);
    row(p, y, "", hint("Police, taille et historique s'appliquent aux terminaux ouverts ensuite."));

    int r = JOptionPane.showConfirmDialog(app, p, "Réglages", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
    if (r != JOptionPane.OK_OPTION) return;
    String themeName = (String) theme.getSelectedItem();
    Theme.ALL.values().stream().filter(t -> t.name.equals(themeName)).findFirst().ifPresent(t -> st.theme = t.id);
    String f = (String) font.getSelectedItem();
    st.fontFamily = f == null || f.startsWith("(") ? "" : f;
    st.fontSize = (Integer) size.getValue();
    st.scrollback = (Integer) scrollback.getValue();
    Shells.Shell sh = (Shells.Shell) shell.getSelectedItem();
    st.defaultShell = sh == null ? "" : sh.id();
    st.defaultCwd = cwd.getText().trim();
    st.notify = notify.isSelected();
    app.applyTheme();
    app.save();
  }

  private static List<String> monospaceFonts() {
    List<String> out = new ArrayList<>();
    FontRenderContext frc = new FontRenderContext(null, false, false);
    for (String name : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
      Font f = new Font(name, Font.PLAIN, 12);
      if (!f.canDisplay('a')) continue;
      double i = f.getStringBounds("i", frc).getWidth(), m = f.getStringBounds("M", frc).getWidth();
      if (Math.abs(i - m) < 0.01 && !name.startsWith(".")) out.add(name);
    }
    return out;
  }

  // Palette rapide (Ctrl+Shift+P) : aller à une discussion ou lancer une action.
  static void palette(Younesh app) {
    record Item(String label, String detail, Runnable run) {
    }
    List<Item> all = new ArrayList<>();
    for (Store.Session s : app.sessions()) {
      Store.Folder f = app.store.folder(s.folderId);
      all.add(new Item(("claude".equals(s.kind) ? "✳ " : "") + s.name, f == null ? "" : f.name, () -> app.activate(s)));
    }
    for (Map.Entry<String, Runnable> c : app.commands().entrySet()) all.add(new Item(c.getKey(), "action", c.getValue()));

    JDialog d = new JDialog(app, false);
    d.setUndecorated(true);
    JTextField field = new JTextField();
    field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Aller à une discussion ou une action…");
    DefaultListModel<Item> model = new DefaultListModel<>();
    JList<Item> list = new JList<>(model);
    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    list.setCellRenderer(new DefaultListCellRenderer() {
      @Override
      public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean focus) {
        Item it = (Item) v;
        String detail = it.detail().isEmpty() ? "" : "&nbsp;&nbsp;<span style='color:" + Theme.hex(app.theme().muted) + "'>" + esc(it.detail()) + "</span>";
        JLabel c = (JLabel) super.getListCellRendererComponent(l, "<html>" + esc(it.label()) + detail + "</html>", i, sel, false);
        c.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        return c;
      }
    });
    Runnable filter = () -> {
      String q = Sidebar.norm(field.getText());
      model.clear();
      for (Item it : all) if (fuzzy(Sidebar.norm(it.label() + " " + it.detail()), q)) model.addElement(it);
      if (!model.isEmpty()) list.setSelectedIndex(0);
    };
    Runnable run = () -> {
      Item it = list.getSelectedValue();
      d.dispose();
      if (it != null) SwingUtilities.invokeLater(it.run());
    };
    field.getDocument().addDocumentListener(new DocumentListener() {
      public void insertUpdate(DocumentEvent e) { filter.run(); }
      public void removeUpdate(DocumentEvent e) { filter.run(); }
      public void changedUpdate(DocumentEvent e) { filter.run(); }
    });
    field.addKeyListener(new KeyAdapter() {
      @Override
      public void keyPressed(KeyEvent e) {
        int n = model.getSize();
        switch (e.getKeyCode()) {
          case KeyEvent.VK_DOWN -> list.setSelectedIndex(Math.min(n - 1, list.getSelectedIndex() + 1));
          case KeyEvent.VK_UP -> list.setSelectedIndex(Math.max(0, list.getSelectedIndex() - 1));
          case KeyEvent.VK_ENTER -> run.run();
          case KeyEvent.VK_ESCAPE -> d.dispose();
          default -> {
            return;
          }
        }
        list.ensureIndexIsVisible(list.getSelectedIndex());
        e.consume();
      }
    });
    list.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        if (e.getClickCount() == 2) run.run();
      }
    });
    d.addWindowListener(new WindowAdapter() {
      @Override
      public void windowDeactivated(WindowEvent e) {
        d.dispose();
      }
    });
    JPanel root = new JPanel(new BorderLayout(0, 6));
    root.setBorder(BorderFactory.createCompoundBorder(
      BorderFactory.createLineBorder(app.theme().border), BorderFactory.createEmptyBorder(8, 8, 8, 8)));
    root.add(field, BorderLayout.NORTH);
    JScrollPane sp = new JScrollPane(list);
    sp.setBorder(null);
    root.add(sp, BorderLayout.CENTER);
    root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "close");
    d.setContentPane(root);
    d.setSize(new Dimension(520, 360));
    d.setLocation(app.getX() + (app.getWidth() - 520) / 2, app.getY() + 80);
    filter.run();
    d.setVisible(true);
    field.requestFocusInWindow();
  }

  // Les lettres de la recherche apparaissent dans l'ordre.
  static boolean fuzzy(String text, String q) {
    int j = 0;
    for (int i = 0; i < text.length() && j < q.length(); i++) if (text.charAt(i) == q.charAt(j)) j++;
    return j == q.length();
  }

  private static String esc(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static JPanel form() {
    JPanel p = new JPanel(new GridBagLayout());
    p.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
    return p;
  }

  private static void row(JPanel p, int y, String label, JComponent field) {
    GridBagConstraints c = new GridBagConstraints();
    c.gridy = y;
    c.insets = new Insets(4, 0, 4, 10);
    c.anchor = GridBagConstraints.LINE_START;
    if (!label.isEmpty()) p.add(new JLabel(label), c);
    c.gridx = 1;
    c.weightx = 1;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(4, 0, 4, 0);
    p.add(field, c);
  }

  private static JComponent hint(String text) {
    JLabel l = new JLabel("<html><div style='width:280px'>" + esc(text) + "</div></html>");
    l.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground; font: -1");
    return l;
  }
}
