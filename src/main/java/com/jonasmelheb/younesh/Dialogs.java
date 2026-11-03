package com.jonasmelheb.younesh;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Les fenêtres : discussion (sessionForm d'app.js), réglages et palette rapide.
final class Dialogs {
  private Dialogs() {
  }

  private record Choice(String id, String label) {
    @Override
    public String toString() {
      return label;
    }
  }

  private static Ui.Btn browse(Younesh app, JTextField target) {
    Ui.Btn b = new Ui.Btn("Parcourir…", Ui.Kind.NORMAL);
    b.addActionListener(e -> {
      JFileChooser fc = new JFileChooser(target.getText().isBlank() ? System.getProperty("user.home") : target.getText());
      fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
      if (fc.showOpenDialog(app) == JFileChooser.APPROVE_OPTION) target.setText(fc.getSelectedFile().getAbsolutePath());
    });
    return b;
  }

  // Nouvelle discussion ou modification. Renvoie false si on annule. Un nom vide = automatique.
  static boolean editSession(Younesh app, Store.Session s, boolean isNew) {
    Ui.Modal m = new Ui.Modal(app, isNew ? "Nouvelle discussion" : "Modifier la discussion", isNew ? "Créer" : "Enregistrer");
    Ui.Segmented kind = new Ui.Segmented("›_ Terminal", "✳ Claude Code");
    JTextField name = Ui.input(s.name, "Automatique");
    JTextField cwd = Ui.input(s.cwd, System.getProperty("user.home"));
    JComboBox<Shells.Shell> shell = new JComboBox<>(Shells.detect().toArray(Shells.Shell[]::new));
    Shells.Shell current = Shells.find(s.shell != null ? s.shell : app.store.settings.defaultShell);
    if (current != null) shell.setSelectedItem(current);
    JComboBox<Choice> folder = new JComboBox<>();
    folder.addItem(new Choice(null, "Sans dossier"));
    for (Store.Folder f : app.store.folders) {
      Choice c = new Choice(f.id, f.name);
      folder.addItem(c);
      if (f.id.equals(s.folderId)) folder.setSelectedItem(c);
    }
    JTextField command = Ui.input(s.command, "ex. npm run dev");
    JTextField claudeArgs = Ui.input(s.claudeArgs, "ex. --model opus");

    m.field("Type", kind);
    m.field("Nom", name);
    m.field("Dossier de travail", Ui.Modal.row(cwd, browse(app, cwd)));
    m.field("Shell", shell);
    m.field("Ranger dans", folder);
    JComponent forShell = m.field("Commande au démarrage (facultatif)", command);
    JComponent forClaude = m.field("Options Claude (facultatif)", claudeArgs,
      "La conversation Claude est liée à cette discussion : elle reprend là où tu t'étais arrêté, même après avoir fermé Younesh.");
    kind.onChange(i -> {
      forShell.setVisible(i == 0);
      forClaude.setVisible(i == 1);
      m.pack();
      m.setSize(480, m.getHeight());
    });
    kind.select("claude".equals(s.kind) ? 1 : 0);
    if (!isNew) kind.lock();

    if (!m.open(app)) return false;
    s.kind = kind.selected() == 1 ? "claude" : "shell";
    String n = name.getText().trim();
    s.name = n.isEmpty() && !isNew ? s.name : n;
    s.cwd = cwd.getText().trim();
    Shells.Shell sh = (Shells.Shell) shell.getSelectedItem();
    s.shell = sh == null ? null : sh.id();
    s.folderId = ((Choice) folder.getSelectedItem()).id();
    s.command = command.getText().trim();
    s.claudeArgs = claudeArgs.getText().trim();
    return true;
  }

  static void settings(Younesh app) {
    Store.Settings st = app.store.settings;
    Ui.Modal m = new Ui.Modal(app, "Réglages", "Enregistrer");
    JComboBox<Shells.Shell> shell = new JComboBox<>(Shells.detect().toArray(Shells.Shell[]::new));
    Shells.Shell current = Shells.find(st.defaultShell);
    if (current != null) shell.setSelectedItem(current);
    JTextField cwd = Ui.input(st.defaultCwd, System.getProperty("user.home"));
    JComboBox<Choice> theme = new JComboBox<>();
    for (Theme t : Theme.ALL.values()) {
      Choice c = new Choice(t.id, t.name);
      theme.addItem(c);
      if (t.id.equals(Theme.of(st.theme).id)) theme.setSelectedItem(c);
    }
    JTextField font = Ui.input(st.fontFamily, "Cascadia Mono, Consolas…");
    JSpinner size = new JSpinner(new SpinnerNumberModel(st.fontSize, 9, 28, 1));
    JSpinner scrollback = new JSpinner(new SpinnerNumberModel(st.scrollback, 1000, 100000, 1000));
    JCheckBox notify = new JCheckBox("Notification Windows quand Claude attend ta réponse", st.notify);

    m.field("Shell par défaut", shell);
    m.field("Dossier de travail par défaut", Ui.Modal.row(cwd, browse(app, cwd)));
    m.field("Apparence", theme);
    m.check(notify);
    m.field("Police", font);
    m.field("Taille du texte", size);
    m.field("Historique (lignes gardées par terminal)", scrollback,
      "Police, taille et historique s'appliquent aux terminaux ouverts ensuite.");

    if (!m.open(app)) return;
    Shells.Shell sh = (Shells.Shell) shell.getSelectedItem();
    st.defaultShell = sh == null ? "" : sh.id();
    st.defaultCwd = cwd.getText().trim();
    st.theme = ((Choice) theme.getSelectedItem()).id();
    st.fontFamily = font.getText().trim();
    st.fontSize = (Integer) size.getValue();
    st.scrollback = (Integer) scrollback.getValue();
    st.notify = notify.isSelected();
    app.applyTheme();
    app.save();
  }

  // Palette rapide (Ctrl+Maj+P) : aller à une discussion ou lancer une action (#palette).
  static void palette(Younesh app) {
    record Item(String kind, String label, String detail, Runnable run) {
    }
    List<Item> all = new ArrayList<>();
    for (Store.Session s : app.sessions()) {
      Store.Folder f = app.store.folder(s.folderId);
      String b = app.branchOf(s);
      String detail = String.join(" · ", java.util.stream.Stream.of(f == null ? null : f.name, b, Sidebar.shortPath(s.cwd)).filter(x -> x != null && !x.isEmpty()).toList());
      all.add(new Item("claude".equals(s.kind) ? "✳" : "›_", s.name, detail, () -> app.activate(s)));
    }
    for (Map.Entry<String, Runnable> c : app.commands().entrySet()) all.add(new Item("›", c.getKey(), "", c.getValue()));

    Theme t = app.theme();
    JDialog d = new JDialog(app, false);
    d.setUndecorated(true);
    d.setBackground(new Color(0, 0, 0, 0));
    JTextField field = new JTextField();
    field.setFont(Ui.font(14, false));
    field.putClientProperty(com.formdev.flatlaf.FlatClientProperties.PLACEHOLDER_TEXT, "Aller à une discussion ou lancer une action…");
    field.setBorder(BorderFactory.createCompoundBorder(
      BorderFactory.createMatteBorder(0, 0, 1, 0, t.border), BorderFactory.createEmptyBorder(12, 14, 12, 14)));
    field.setOpaque(false);
    DefaultListModel<Item> model = new DefaultListModel<>();
    JList<Item> list = new JList<>(model);
    list.setOpaque(false);
    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    list.setCellRenderer(new DefaultListCellRenderer() {
      @Override
      public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean focus) {
        Item it = (Item) v;
        String sub = it.detail().isEmpty() ? "" : "<br><span style='font-size:9px;color:" + Theme.hex(t.muted) + "'>" + esc(it.detail()) + "</span>";
        JLabel c = (JLabel) super.getListCellRendererComponent(l, "<html><span style='color:" + Theme.hex(t.muted) + "'>" + esc(it.kind())
          + "</span>&nbsp;&nbsp;" + esc(it.label()) + sub + "</html>", i, sel, false);
        c.setFont(Ui.font(13, false));
        c.setForeground(t.text);
        c.setBackground(sel ? t.active : t.panel);
        c.setOpaque(sel);
        c.setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10));
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
        run.run();
      }
    });
    d.addWindowListener(new WindowAdapter() {
      @Override
      public void windowDeactivated(WindowEvent e) {
        d.dispose();
      }
    });
    JPanel root = new JPanel(new BorderLayout()) {
      @Override
      protected void paintComponent(Graphics g0) {
        Graphics2D g = Ui.smooth(g0);
        g.setColor(t.panel);
        g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 24, 24);
        g.setColor(t.borderStrong);
        g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 24, 24);
        g.dispose();
      }
    };
    root.setOpaque(false);
    root.add(field, BorderLayout.NORTH);
    JScrollPane sp = new JScrollPane(list);
    sp.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
    sp.setOpaque(false);
    sp.getViewport().setOpaque(false);
    root.add(sp, BorderLayout.CENTER);
    d.setContentPane(root);
    int w = Math.min(560, app.getWidth() - 32);
    d.setSize(new Dimension(w, 420));
    d.setLocation(app.getX() + (app.getWidth() - w) / 2, app.getY() + 64);
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
}
