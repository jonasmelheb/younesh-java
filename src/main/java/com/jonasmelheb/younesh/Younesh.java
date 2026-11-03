package com.jonasmelheb.younesh;

import com.formdev.flatlaf.FlatClientProperties;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.Taskbar;
import java.awt.TrayIcon;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// La fenêtre : barre latérale à gauche, terminal de la discussion active à droite.
final class Younesh extends JFrame implements Sidebar.Actions {
  final Store store = Store.load();
  private final Map<String, Term> terms = new HashMap<>();
  private final Map<String, String> branches = new HashMap<>();
  private Theme theme = Theme.of(store.settings.theme);

  private final CardLayout cards = new CardLayout();
  private final JPanel terminals = new JPanel(cards);
  private final JPanel sidebarPanel = new JPanel(new BorderLayout());
  private final JSplitPane split;
  private final Sidebar sidebar;
  private final JScrollPane sidebarScroll;
  private final JTextField search = new JTextField();
  private final JPanel topbar = new JPanel(new BorderLayout());
  private final JLabel title = new JLabel(), subtitle = new JLabel();
  private final JButton restart = new JButton("Relancer");
  private final JPanel empty = new JPanel(new BorderLayout());
  private final JLabel emptyLabel = new JLabel("<html><center>Aucune discussion ouverte<br><br>Ctrl+Shift+T : nouvelle discussion<br>Ctrl+Shift+N : nouvelle session Claude</center></html>", SwingConstants.CENTER);
  private TrayIcon tray;
  private Image icon;

  Younesh() {
    super("Younesh");
    try {
      icon = ImageIO.read(Younesh.class.getResource("/icon.png"));
      setIconImage(icon);
    } catch (IOException | RuntimeException ignored) {
    }
    setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

    sidebar = new Sidebar(store, terms, branches, theme, this);
    sidebarScroll = new JScrollPane(sidebar);
    sidebarScroll.setBorder(null);
    sidebarScroll.getVerticalScrollBar().setUnitIncrement(16);
    sidebarScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    sidebarPanel.add(header(), BorderLayout.NORTH);
    sidebarPanel.add(sidebarScroll, BorderLayout.CENTER);
    sidebarPanel.add(footer(), BorderLayout.SOUTH);

    JPanel main = new JPanel(new BorderLayout());
    main.add(topbar(), BorderLayout.NORTH);
    main.add(terminals, BorderLayout.CENTER);
    empty.add(emptyLabel, BorderLayout.CENTER);
    terminals.add(empty, "empty");

    split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebarPanel, main);
    split.setDividerSize(1);
    split.setContinuousLayout(true);
    split.setBorder(null);
    split.setDividerLocation(store.settings.sidebarWidth);
    add(split);

    if (store.bounds != null && store.bounds.length == 4) {
      setBounds(store.bounds[0], store.bounds[1], store.bounds[2], store.bounds[3]);
    } else {
      setSize(1200, 760);
      setLocationRelativeTo(null);
    }
    if (Shells.WIN) getRootPane().putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_ICON, true);
    if (!Shells.WIN) {
      getRootPane().putClientProperty("apple.awt.transparentTitleBar", true);
      getRootPane().putClientProperty("apple.awt.fullWindowContent", true);
      getRootPane().putClientProperty("apple.awt.windowTitleVisible", false);
    }

    addWindowListener(new WindowAdapter() {
      @Override
      public void windowClosing(WindowEvent e) {
        quit();
      }

      @Override
      public void windowActivated(WindowEvent e) {
        Term t = terms.get(store.activeId);
        if (t != null && t.attention) {
          t.attention = false;
          sidebar.repaint();
        }
      }
    });
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this::shortcut);
    new Timer(500, e -> watchAttention()).start();
    new Timer(4000, e -> refreshBranches()).start();

    applyTheme();
    refreshBranches();
    Store.Session active = store.session(store.activeId);
    if (active != null) activate(active); else showEmpty();
    setVisible(true);
  }

  // ---------- Construction de l'interface ----------

  private JComponent header() {
    JPanel p = new JPanel();
    p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
    p.setBorder(BorderFactory.createEmptyBorder(Shells.WIN ? 10 : 34, 10, 6, 10));
    p.setOpaque(false);

    JPanel buttons = new JPanel(new java.awt.GridLayout(1, 2, 6, 0));
    buttons.setOpaque(false);
    JButton add = new JButton("+ Discussion");
    add.addActionListener(e -> newSession("shell", null));
    JButton claude = new JButton("✳ Claude");
    claude.addActionListener(e -> newSession("claude", null));
    add.setToolTipText("Nouvelle discussion (Ctrl+Shift+T)");
    claude.setToolTipText("Nouvelle session Claude Code (Ctrl+Shift+N)");
    buttons.add(add);
    buttons.add(claude);
    buttons.setAlignmentX(LEFT_ALIGNMENT);
    buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

    JPanel row = new JPanel(new BorderLayout(6, 0));
    row.setOpaque(false);
    search.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Rechercher…");
    search.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
    search.getDocument().addDocumentListener(new DocumentListener() {
      public void insertUpdate(DocumentEvent e) { sidebar.setFilter(search.getText()); }
      public void removeUpdate(DocumentEvent e) { sidebar.setFilter(search.getText()); }
      public void changedUpdate(DocumentEvent e) { sidebar.setFilter(search.getText()); }
    });
    JButton folder = new JButton(new FolderPlusIcon());
    folder.setToolTipText("Nouveau dossier (Ctrl+Shift+D)");
    folder.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
    folder.addActionListener(e -> newFolder());
    row.add(search, BorderLayout.CENTER);
    row.add(folder, BorderLayout.EAST);
    row.setAlignmentX(LEFT_ALIGNMENT);
    row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

    p.add(buttons);
    p.add(Box.createVerticalStrut(8));
    p.add(row);
    return p;
  }

  private JComponent footer() {
    JPanel p = new JPanel(new BorderLayout());
    p.setOpaque(false);
    p.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 12));
    JButton settings = new JButton("⚙  Réglages");
    settings.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
    settings.addActionListener(e -> Dialogs.settings(this));
    p.add(settings, BorderLayout.WEST);
    p.add(new Signature(), BorderLayout.EAST);
    return p;
  }

  private JComponent topbar() {
    topbar.setBorder(BorderFactory.createEmptyBorder(Shells.WIN ? 8 : 10, 14, 8, 10));
    JPanel texts = new JPanel();
    texts.setLayout(new BoxLayout(texts, BoxLayout.X_AXIS));
    texts.setOpaque(false);
    title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
    subtitle.setFont(subtitle.getFont().deriveFont(12f));
    texts.add(title);
    texts.add(Box.createHorizontalStrut(12));
    texts.add(subtitle);
    restart.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
    restart.addActionListener(e -> {
      Store.Session s = store.session(store.activeId);
      if (s != null) restart(s);
    });
    topbar.add(texts, BorderLayout.CENTER);
    topbar.add(restart, BorderLayout.EAST);
    return topbar;
  }

  // ---------- Thème ----------

  Theme theme() {
    return theme;
  }

  void applyTheme() {
    theme = Theme.of(store.settings.theme);
    Main.setupLaf(theme);
    SwingUtilities.updateComponentTreeUI(this);
    sidebar.setTheme(theme);
    sidebarScroll.setBorder(null);
    split.setBorder(null);
    sidebarPanel.setBackground(theme.panel);
    sidebarScroll.getViewport().setBackground(theme.panel);
    topbar.setBackground(theme.bg);
    topbar.setBorder(BorderFactory.createCompoundBorder(
      BorderFactory.createMatteBorder(0, 0, 1, 0, theme.border), topbar.getBorder() instanceof javax.swing.border.CompoundBorder cb ? cb.getInsideBorder() : topbar.getBorder()));
    title.setForeground(theme.text);
    subtitle.setForeground(theme.muted);
    terminals.setBackground(theme.termBg);
    empty.setBackground(theme.bg);
    emptyLabel.setForeground(theme.muted);
    split.setBackground(theme.border);
    getRootPane().putClientProperty(FlatClientProperties.TITLE_BAR_BACKGROUND, theme.panel);
    getRootPane().putClientProperty(FlatClientProperties.TITLE_BAR_FOREGROUND, theme.muted);
    for (Term t : terms.values()) {
      t.widget.setBackground(theme.termBg);
      t.view.setBackground(theme.termBg);
      t.widget.getTerminalPanel().repaint();
    }
    repaint();
  }

  // ---------- Discussions ----------

  @Override
  public void activate(Store.Session s) {
    store.activeId = s.id;
    Term t = terms.computeIfAbsent(s.id, id -> {
      Term nt = new Term(s, () -> theme, () -> store.settings);
      nt.widget.setBackground(theme.termBg);
      nt.view.setBackground(theme.termBg);
      nt.widget.getTerminal().addApplicationTitleListener(title -> SwingUtilities.invokeLater(() -> applyClaudeTitle(s, title)));
      terminals.add(nt.view, s.id);
      return nt;
    });
    if (!t.running && !t.exited) start(t);
    t.unread = false;
    t.attention = false;
    cards.show(terminals, s.id);
    updateTopbar();
    sidebar.rebuild();
    save();
    SwingUtilities.invokeLater(() -> t.widget.getTerminalPanel().requestFocusInWindow());
  }

  private void start(Term t) {
    Store.Session s = t.session;
    Shells.Shell shell = Shells.find(s.shell != null ? s.shell : store.settings.defaultShell);
    if (shell == null) {
      error("Aucun shell trouvé sur ce PC.");
      return;
    }
    String cwd = firstDir(s.cwd, store.settings.defaultCwd, System.getProperty("user.home"));
    String cmd = Shells.startupCommand(s, shell);
    try {
      t.pty = Pty.start(Shells.commandLine(shell, cmd), cwd, s.name, (buf, off, len) -> onOutput(t, buf, off, len));
    } catch (IOException | RuntimeException e) {
      error("Impossible de lancer " + shell.name() + " :\n" + e.getMessage());
      t.exited = true;
      return;
    }
    t.widget.setTtyConnector(t.pty);
    t.widget.start();
    t.running = true;
    t.exited = false;
    if ("claude".equals(s.kind) && !s.claudeStarted) {
      s.claudeStarted = true;
      save();
    }
    Pty pty = t.pty;
    Thread watcher = new Thread(() -> {
      try {
        pty.process().waitFor();
      } catch (InterruptedException ignored) {
      }
      SwingUtilities.invokeLater(() -> {
        if (t.pty != pty) return;
        t.running = false;
        t.exited = true;
        t.attention = false;
        sidebar.repaint();
        updateTopbar();
      });
    }, "younesh-exit-" + s.name);
    watcher.setDaemon(true);
    watcher.start();
  }

  private static String firstDir(String... dirs) {
    for (String d : dirs) if (d != null && !d.isBlank() && Files.isDirectory(Path.of(d))) return d;
    return System.getProperty("user.home");
  }

  // Appelé par le fil de lecture du terminal.
  private void onOutput(Term t, char[] buf, int off, int len) {
    long now = System.currentTimeMillis();
    t.lastOutput = now;
    boolean bell = false;
    for (int i = off; i < off + len; i++) if (buf[i] == 7) bell = true;
    if ("claude".equals(t.session.kind) && now - t.lastInput > 400) t.awaiting = true;
    boolean ring = bell;
    SwingUtilities.invokeLater(() -> {
      boolean visible = t.session.id.equals(store.activeId);
      if (ring && !(visible && isActive())) raise(t);
      if (!visible && !t.unread) {
        t.unread = true;
        sidebar.repaint();
      }
    });
  }

  // Claude se tait 3 s après avoir écrit : il attend une réponse.
  private void watchAttention() {
    long now = System.currentTimeMillis();
    for (Term t : terms.values()) {
      if (!t.running || !t.awaiting || now - t.lastOutput < 3000) continue;
      t.awaiting = false;
      if (!(isActive() && t.session.id.equals(store.activeId))) raise(t);
    }
  }

  private void raise(Term t) {
    if (t.attention) return;
    t.attention = true;
    sidebar.repaint();
    try {
      if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.USER_ATTENTION_WINDOW)) {
        Taskbar.getTaskbar().requestWindowUserAttention(this);
      }
    } catch (RuntimeException ignored) {
    }
    if (store.settings.notify && !isActive()) notifyUser(t.session.name, "claude".equals(t.session.kind) ? "Claude attend ta réponse" : "Le terminal demande ton attention");
  }

  private void notifyUser(String title, String text) {
    if (!SystemTray.isSupported() || icon == null) return;
    try {
      if (tray == null) {
        tray = new TrayIcon(icon, "Younesh");
        tray.setImageAutoSize(true);
        tray.addActionListener(e -> toFront());
        SystemTray.getSystemTray().add(tray);
      }
      tray.displayMessage(title, text, TrayIcon.MessageType.INFO);
    } catch (AWTException | RuntimeException ignored) {
    }
  }

  private void jumpToAttention() {
    terms.values().stream().filter(t -> t.attention).findFirst().ifPresent(t -> activate(t.session));
  }

  private void refreshBranches() {
    boolean changed = false;
    for (Store.Session s : store.sessions) {
      String b = Shells.gitBranch(s.cwd);
      if (!java.util.Objects.equals(branches.get(s.id), b)) {
        if (b == null) branches.remove(s.id); else branches.put(s.id, b);
        changed = true;
      }
    }
    if (changed) {
      sidebar.repaint();
      updateTopbar();
    }
  }

  // Claude Code écrit le nom de la session dans le titre du terminal : « ✳ Nom », le symbole tourne
  // pendant qu'il travaille. On reprend ce nom (après /rename par exemple). Les titres des shells
  // (chemins, noms de programmes) commencent par une lettre et sont ignorés.
  private static final java.util.regex.Pattern CLAUDE_TITLE = java.util.regex.Pattern.compile("^[^\\p{L}\\p{N}\\s]\\s+(.+)$");

  static String claudeTitle(String title) {
    java.util.regex.Matcher m = CLAUDE_TITLE.matcher(title.trim());
    if (!m.find()) return null;
    String name = m.group(1).trim();
    return name.isEmpty() || name.equals("Claude Code") ? null : name;
  }

  private void applyClaudeTitle(Store.Session s, String title) {
    String name = claudeTitle(title);
    // titleSeen évite de réécraser un nom choisi dans Younesh quand Claude renvoie le même titre à la reprise.
    if (name == null || name.equals(s.titleSeen) || store.session(s.id) == null) return;
    s.titleSeen = name;
    s.name = name;
    sidebar.rebuild();
    updateTopbar();
    save();
  }

  void restart(Store.Session s) {
    Term old = terms.remove(s.id);
    if (old != null) {
      old.pty = null;
      try {
        old.widget.close();
      } catch (RuntimeException ignored) {
      }
      terminals.remove(old.view);
    }
    activate(s);
  }

  private void updateTopbar() {
    Store.Session s = store.session(store.activeId);
    if (s == null) {
      title.setText("");
      subtitle.setText("");
      restart.setVisible(false);
      return;
    }
    Term t = terms.get(s.id);
    title.setText(s.name);
    String b = branches.get(s.id);
    String home = System.getProperty("user.home");
    String cwd = s.cwd == null || s.cwd.isBlank() ? "~" : s.cwd.startsWith(home) ? "~" + s.cwd.substring(home.length()) : s.cwd;
    String branch = b != null ? "  ·  " + b : "";
    subtitle.setText(cwd + branch);
    restart.setVisible(t != null && t.exited);
  }

  private void showEmpty() {
    store.activeId = null;
    cards.show(terminals, "empty");
    updateTopbar();
    sidebar.rebuild();
  }

  void newSession(String kind, String folderId) {
    Store.Session s = new Store.Session();
    s.kind = kind;
    s.folderId = folderId != null ? folderId : currentFolderId();
    s.name = "claude".equals(kind) ? "Claude" : "Terminal";
    s.cwd = store.settings.defaultCwd;
    if (!Dialogs.editSession(this, s, true)) return;
    if ("claude".equals(kind)) s.claudeId = UUID.randomUUID().toString();
    store.sessions.add(s);
    refreshBranches();
    activate(s);
  }

  private String currentFolderId() {
    Store.Session a = store.session(store.activeId);
    return a != null ? a.folderId : null;
  }

  void newFolder() {
    String name = Dialogs.input(this, "Nouveau dossier", "Nom du dossier", "");
    if (name == null || name.isBlank()) return;
    Store.Folder f = new Store.Folder();
    f.name = name.trim();
    f.color = Theme.SWATCHES[store.folders.size() % Theme.SWATCHES.length];
    store.folders.add(f);
    sidebar.rebuild();
    save();
  }

  @Override
  public void toggle(Store.Folder f) {
    f.collapsed = !f.collapsed;
    sidebar.rebuild();
    save();
  }

  @Override
  public void rename(Store.Folder f, Store.Session s) {
    String current = s != null ? s.name : f.name;
    String name = Dialogs.input(this, "Renommer", s != null ? "Nom de la discussion" : "Nom du dossier", current);
    if (name == null || name.isBlank()) return;
    if (s != null) s.name = name.trim(); else f.name = name.trim();
    sidebar.rebuild();
    updateTopbar();
    save();
  }

  @Override
  public void close(Store.Session s) {
    Term t = terms.remove(s.id);
    if (t != null) {
      t.pty = null;
      try {
        t.widget.close();
      } catch (RuntimeException ignored) {
      }
      terminals.remove(t.view);
    }
    int i = store.sessions.indexOf(s);
    store.sessions.remove(s);
    if (s.id.equals(store.activeId)) {
      if (store.sessions.isEmpty()) showEmpty();
      else activate(store.sessions.get(Math.max(0, Math.min(i, store.sessions.size() - 1))));
    }
    sidebar.rebuild();
    save();
  }

  @Override
  public void moved() {
    sidebar.rebuild();
    save();
  }

  @Override
  public void menu(Store.Folder f, Store.Session s, Point p) {
    JPopupMenu m = new JPopupMenu();
    if (s != null) {
      m.add(item("Renommer", () -> rename(null, s)));
      m.add(item("Modifier…", () -> {
        if (Dialogs.editSession(this, s, false)) {
          refreshBranches();
          sidebar.rebuild();
          updateTopbar();
          save();
        }
      }));
      m.add(colorMenu(s.color, c -> s.color = c, true));
      JMenu move = new JMenu("Déplacer vers");
      for (Store.Folder target : store.folders) {
        JMenuItem it = item(target.name, () -> {
          s.folderId = target.id;
          moved();
        });
        it.setIcon(new Swatch(Sidebar.colorOf(target.color, theme.accent)));
        it.setEnabled(!target.id.equals(s.folderId));
        move.add(it);
      }
      move.addSeparator();
      JMenuItem out = item("Hors dossier", () -> {
        s.folderId = null;
        moved();
      });
      out.setEnabled(s.folderId != null);
      move.add(out);
      m.add(move);
      m.add(item("Relancer", () -> restart(s)));
      m.addSeparator();
      m.add(item("Fermer", () -> close(s)));
    } else {
      m.add(item("Nouvelle discussion ici", () -> newSession("shell", f.id)));
      m.add(item("Nouvelle session Claude ici", () -> newSession("claude", f.id)));
      m.addSeparator();
      m.add(item("Renommer", () -> rename(f, null)));
      m.add(colorMenu(f.color, c -> f.color = c, false));
      m.add(item(f.collapsed ? "Déplier" : "Replier", () -> toggle(f)));
      m.addSeparator();
      m.add(item("Supprimer le dossier", () -> {
        long n = store.sessions.stream().filter(x -> f.id.equals(x.folderId)).count();
        if (n > 0 && JOptionPane.showConfirmDialog(this, "Les " + n + " discussions du dossier sortent du dossier. Continuer ?", "Supprimer le dossier", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        store.sessions.stream().filter(x -> f.id.equals(x.folderId)).forEach(x -> x.folderId = null);
        store.folders.remove(f);
        moved();
      }));
    }
    m.show(sidebar, p.x, p.y);
  }

  private JMenu colorMenu(String current, java.util.function.Consumer<String> set, boolean allowNone) {
    JMenu menu = new JMenu("Couleur");
    for (String hex : Theme.SWATCHES) {
      JMenuItem it = item(hex.equalsIgnoreCase(current) ? "✓" : " ", () -> {
        set.accept(hex);
        moved();
      });
      it.setIcon(new Swatch(Theme.c(hex)));
      menu.add(it);
    }
    menu.addSeparator();
    menu.add(item("Personnalisée…", () -> {
      Color c = JColorChooser.showDialog(this, "Couleur", Sidebar.colorOf(current, theme.accent));
      if (c != null) {
        set.accept(Theme.hex(c));
        moved();
      }
    }));
    if (allowNone) menu.add(item("Aucune", () -> {
      set.accept(null);
      moved();
    }));
    return menu;
  }

  private static JMenuItem item(String label, Runnable r) {
    JMenuItem it = new JMenuItem(label);
    it.addActionListener(e -> r.run());
    return it;
  }

  // ---------- Raccourcis ----------

  private boolean shortcut(KeyEvent e) {
    if (e.getID() != KeyEvent.KEY_PRESSED) return false;
    Term active = terms.get(store.activeId);
    if (active != null && SwingUtilities.isDescendingFrom(e.getComponent(), active.widget) && !e.isControlDown()) {
      active.lastInput = System.currentTimeMillis();
      active.awaiting = false;
      if (active.attention) {
        active.attention = false;
        sidebar.repaint();
      }
    }
    if (!isActive()) return false;
    boolean ctrl = e.isControlDown(), shift = e.isShiftDown();
    int k = e.getKeyCode();
    Runnable action = null;
    if (ctrl && k == KeyEvent.VK_TAB) action = () -> cycle(shift ? -1 : 1);
    else if (k == KeyEvent.VK_F2) action = () -> {
      Store.Session s = store.session(store.activeId);
      if (s != null) rename(null, s);
    };
    else if (ctrl && shift) {
      switch (k) {
        case KeyEvent.VK_T -> action = () -> newSession("shell", null);
        case KeyEvent.VK_N -> action = () -> newSession("claude", null);
        case KeyEvent.VK_D -> action = this::newFolder;
        case KeyEvent.VK_W -> action = () -> {
          Store.Session s = store.session(store.activeId);
          if (s != null) close(s);
        };
        case KeyEvent.VK_B -> action = () -> {
          sidebarPanel.setVisible(!sidebarPanel.isVisible());
          if (sidebarPanel.isVisible()) split.setDividerLocation(store.settings.sidebarWidth);
        };
        case KeyEvent.VK_U -> action = this::jumpToAttention;
        case KeyEvent.VK_P -> action = () -> Dialogs.palette(this);
        default -> {
        }
      }
    }
    if (action == null) return false;
    e.consume();
    SwingUtilities.invokeLater(action);
    return true;
  }

  private void cycle(int dir) {
    List<Store.Session> list = store.sessions;
    if (list.isEmpty()) return;
    int i = list.indexOf(store.session(store.activeId));
    activate(list.get(Math.floorMod(i + dir, list.size())));
  }

  // ---------- Divers ----------

  void save() {
    if (sidebarPanel.isVisible() && split.getDividerLocation() > 80) store.settings.sidebarWidth = split.getDividerLocation();
    if ((getExtendedState() & MAXIMIZED_BOTH) == 0) store.bounds = new int[]{getX(), getY(), getWidth(), getHeight()};
    store.save();
  }

  private void quit() {
    save();
    for (Term t : terms.values()) {
      if (t.pty != null) t.pty.close();
    }
    if (tray != null) SystemTray.getSystemTray().remove(tray);
    dispose();
    System.exit(0);
  }

  void error(String msg) {
    JOptionPane.showMessageDialog(this, msg, "Younesh", JOptionPane.ERROR_MESSAGE);
  }

  List<Store.Session> sessions() {
    return store.sessions;
  }

  // Les actions de la palette rapide.
  Map<String, Runnable> commands() {
    Map<String, Runnable> m = new java.util.LinkedHashMap<>();
    m.put("Nouvelle discussion", () -> newSession("shell", null));
    m.put("Nouvelle session Claude", () -> newSession("claude", null));
    m.put("Nouveau dossier", this::newFolder);
    m.put("Réglages", () -> Dialogs.settings(this));
    m.put("Aller à la discussion qui attend", this::jumpToAttention);
    return m;
  }

  // Le « Y » en dégradé, comme dans la version Electron.
  private static final class Signature extends JComponent {
    Signature() {
      setPreferredSize(new Dimension(22, 26));
      setToolTipText("Made by Y");
      setCursor(Cursor.getDefaultCursor());
    }

    @Override
    protected void paintComponent(Graphics g0) {
      Graphics2D g = (Graphics2D) g0.create();
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g.setFont(getFont().deriveFont(Font.BOLD | Font.ITALIC, 18f));
      int w = g.getFontMetrics().stringWidth("Y");
      int x = (getWidth() - w) / 2, y = getHeight() / 2 + g.getFontMetrics().getAscent() / 2 - 3;
      g.setPaint(new GradientPaint(x, 0, new Color(0x7c9cff), x + w, getHeight(), new Color(0xf472b6)));
      g.drawString("Y", x, y);
      g.dispose();
    }
  }

  static final class Swatch implements Icon {
    private final Color color;

    Swatch(Color color) {
      this.color = color;
    }

    public void paintIcon(Component c, Graphics g0, int x, int y) {
      Graphics2D g = (Graphics2D) g0.create();
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setColor(color);
      g.fillRoundRect(x, y + 1, 14, 12, 4, 4);
      g.dispose();
    }

    public int getIconWidth() {
      return 14;
    }

    public int getIconHeight() {
      return 14;
    }
  }

  private static final class FolderPlusIcon implements Icon {
    public void paintIcon(Component c, Graphics g0, int x, int y) {
      Graphics2D g = (Graphics2D) g0.create();
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setColor(c.getForeground());
      g.setStroke(new java.awt.BasicStroke(1.4f));
      g.drawRoundRect(x + 1, y + 4, 15, 11, 4, 4);
      g.drawLine(x + 2, y + 4, x + 3, y + 2);
      g.drawLine(x + 3, y + 2, x + 7, y + 2);
      g.drawLine(x + 7, y + 2, x + 8, y + 4);
      g.drawLine(x + 8, y + 7, x + 8, y + 12);
      g.drawLine(x + 5, y + 9, x + 11, y + 9);
      g.dispose();
    }

    public int getIconWidth() {
      return 18;
    }

    public int getIconHeight() {
      return 17;
    }
  }
}
