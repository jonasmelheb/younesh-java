package com.jonasmelheb.younesh;

import com.formdev.flatlaf.FlatClientProperties;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.SystemTray;
import java.awt.Taskbar;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// La fenêtre : barre latérale à gauche, terminal de la discussion active à droite (comme index.html).
final class Younesh extends JFrame implements Sidebar.Actions {
  final Store store = Store.load();
  private final Map<String, Term> terms = new HashMap<>();
  private final Map<String, String> branches = new HashMap<>();
  private Theme theme = Theme.of(store.settings.theme);

  private final CardLayout cards = new CardLayout();
  private final JPanel terminals = new JPanel(cards);
  private final JPanel sidebarPanel = new JPanel(new BorderLayout());
  private final JPanel main = new JPanel(new BorderLayout());
  private final JSplitPane split;
  private final Sidebar sidebar;
  private final JScrollPane sidebarScroll;
  private final JTextField search = Ui.input("", "Rechercher…");
  private final JPanel topbar = new JPanel();
  private final JComponent activeDot;
  private final JLabel title = Ui.label("", 13, null), path = Ui.label("", 12, null);
  private final JPanel empty = new JPanel(new BorderLayout());
  private final JLabel toast = new JLabel();
  private Timer toastTimer;
  private TrayIcon tray;
  private Image icon;

  Younesh() {
    super("Younesh");
    Ui.theme = theme;
    try {
      icon = ImageIO.read(Younesh.class.getResource("/icon.png"));
      setIconImage(icon);
    } catch (IOException | RuntimeException ignored) {
    }
    setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    setGlassPane(new Ui.Backdrop());

    sidebar = new Sidebar(store, terms, branches, this);
    sidebarScroll = new JScrollPane(sidebar);
    sidebarScroll.setBorder(null);
    sidebarScroll.getVerticalScrollBar().setUnitIncrement(16);
    sidebarScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    sidebarPanel.add(header(), BorderLayout.NORTH);
    sidebarPanel.add(sidebarScroll, BorderLayout.CENTER);
    sidebarPanel.add(footer(), BorderLayout.SOUTH);
    sidebarPanel.setMinimumSize(new Dimension(200, 0));

    activeDot = new JComponent() {
      @Override
      protected void paintComponent(Graphics g0) {
        Graphics2D g = Ui.smooth(g0);
        Ui.dot(g, getWidth() / 2, getHeight() / 2, terms.get(store.activeId), false);
        g.dispose();
      }
    };
    activeDot.setPreferredSize(new Dimension(13, 13));
    activeDot.setMinimumSize(new Dimension(13, 13));
    activeDot.setMaximumSize(new Dimension(13, 13));
    main.add(topbar(), BorderLayout.NORTH);
    main.add(terminals, BorderLayout.CENTER);
    empty.add(emptyMessage(), BorderLayout.CENTER);
    terminals.add(empty, "empty");

    split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebarPanel, main);
    split.setDividerSize(1);
    split.setContinuousLayout(true);
    split.setBorder(null);
    split.setDividerLocation(store.settings.sidebarWidth);
    add(split);

    toast.setVisible(false);
    toast.setOpaque(false);
    getLayeredPane().add(toast, JLayeredPane.POPUP_LAYER);

    if (store.bounds != null && store.bounds.length == 4) {
      setBounds(store.bounds[0], store.bounds[1], store.bounds[2], store.bounds[3]);
    } else {
      setSize(1200, 760);
      setLocationRelativeTo(null);
    }
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
    p.setOpaque(false);

    JPanel brandRow = new JPanel(new BorderLayout());
    brandRow.setOpaque(false);
    brandRow.setBorder(BorderFactory.createEmptyBorder(0, Shells.WIN ? 14 : 84, 0, 14));
    JLabel brand = Ui.label("Younesh", 13, null);
    brand.setFont(Ui.font(13, true));
    brandRow.add(brand, BorderLayout.CENTER);
    fix(brandRow, 38);

    JPanel actions = new JPanel(new GridLayout(1, 2, 6, 0));
    actions.setOpaque(false);
    actions.setBorder(BorderFactory.createEmptyBorder(4, 10, 8, 10));
    Ui.Btn add = new Ui.Btn("+ Discussion", Ui.Kind.PRIMARY);
    add.setToolTipText("Nouvelle discussion (Ctrl+Maj+T)");
    add.addActionListener(e -> openNew("shell", null));
    Ui.Btn claude = new Ui.Btn("✳ Claude", Ui.Kind.NORMAL);
    claude.setToolTipText("Nouvelle discussion Claude Code (Ctrl+Maj+N)");
    claude.addActionListener(e -> openNew("claude", null));
    actions.add(add);
    actions.add(claude);
    fix(actions, 30 + 12);

    JPanel row = new JPanel(new BorderLayout(6, 0));
    row.setOpaque(false);
    row.setBorder(BorderFactory.createEmptyBorder(0, 10, 8, 10));
    search.getDocument().addDocumentListener(new DocumentListener() {
      public void insertUpdate(DocumentEvent e) { sidebar.setFilter(search.getText()); }
      public void removeUpdate(DocumentEvent e) { sidebar.setFilter(search.getText()); }
      public void changedUpdate(DocumentEvent e) { sidebar.setFilter(search.getText()); }
    });
    JPanel icons = new JPanel(new GridLayout(1, 3, 6, 0));
    icons.setOpaque(false);
    Ui.Btn quick = new Ui.Btn("›_", Ui.Kind.ICON);
    quick.setFont(Ui.font(13, true));
    quick.setToolTipText("Terminal rapide dans ton dossier personnel (Ctrl+Maj+Q)");
    quick.addActionListener(e -> quickTerminal());
    Ui.Btn sbx = new Ui.Btn(Ui.SvgIcon.cube());
    sbx.setToolTipText("Sandbox sbx");
    sbx.addActionListener(e -> toast("Les sandbox sbx ne sont pas encore dans la version Java : elles sont dans la version Electron."));
    Ui.Btn folder = new Ui.Btn(Ui.SvgIcon.folderPlus());
    folder.setToolTipText("Nouveau dossier (Ctrl+Maj+D)");
    folder.addActionListener(e -> createFolder());
    icons.add(quick);
    icons.add(sbx);
    icons.add(folder);
    row.add(search, BorderLayout.CENTER);
    row.add(icons, BorderLayout.EAST);
    fix(row, 30 + 8);

    p.add(brandRow);
    p.add(actions);
    p.add(row);
    return p;
  }

  private static void fix(JComponent c, int h) {
    c.setAlignmentX(LEFT_ALIGNMENT);
    c.setPreferredSize(new Dimension(10, h));
    c.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
  }

  private JComponent footer() {
    JPanel p = new JPanel(new BorderLayout(4, 0)) {
      @Override
      protected void paintComponent(Graphics g) {
        g.setColor(theme.border);
        g.fillRect(0, 0, getWidth(), 1);
      }
    };
    p.setOpaque(false);
    p.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
    Ui.Btn settings = new Ui.Btn("⚙ Réglages", Ui.Kind.GHOST);
    settings.addActionListener(e -> Dialogs.settings(this));
    p.add(settings, BorderLayout.CENTER);
    p.add(new Ui.Signature(), BorderLayout.EAST);
    return p;
  }

  private JComponent topbar() {
    topbar.setLayout(new BoxLayout(topbar, BoxLayout.X_AXIS));
    topbar.setPreferredSize(new Dimension(10, 38));
    title.setFont(Ui.font(13, true));
    topbar.add(activeDot);
    topbar.add(Box.createHorizontalStrut(8));
    topbar.add(title);
    topbar.add(Box.createHorizontalStrut(8));
    path.setMinimumSize(new Dimension(0, 0));
    topbar.add(path);
    topbar.add(Box.createHorizontalGlue());
    return topbar;
  }

  private JComponent emptyMessage() {
    JPanel p = new JPanel();
    p.setOpaque(false);
    p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
    JLabel h1 = new JLabel("Aucune discussion ouverte") {
      @Override
      public Color getForeground() {
        return theme.text;
      }
    };
    h1.setFont(Ui.font(18, true));
    JLabel hint = Ui.label("Nouvelle discussion : Ctrl+Maj+T · Claude : Ctrl+Maj+N · Dossier : Ctrl+Maj+D", 13, null);
    h1.setAlignmentX(CENTER_ALIGNMENT);
    hint.setAlignmentX(CENTER_ALIGNMENT);
    p.add(Box.createVerticalGlue());
    p.add(h1);
    p.add(Box.createVerticalStrut(8));
    p.add(hint);
    p.add(Box.createVerticalGlue());
    return p;
  }

  // ---------- Thème ----------

  Theme theme() {
    return theme;
  }

  void applyTheme() {
    theme = Theme.of(store.settings.theme);
    Ui.theme = theme;
    Main.setupLaf(theme);
    SwingUtilities.updateComponentTreeUI(this);
    sidebarScroll.setBorder(null);
    split.setBorder(null);
    sidebarPanel.setBackground(theme.panel);
    sidebarScroll.getViewport().setBackground(theme.panel);
    main.setBackground(theme.bg);
    topbar.setBackground(theme.bg);
    topbar.setOpaque(true);
    topbar.setBorder(BorderFactory.createCompoundBorder(
      BorderFactory.createMatteBorder(0, 0, 1, 0, theme.border), BorderFactory.createEmptyBorder(0, 14, 0, 14)));
    terminals.setBackground(theme.termBg);
    empty.setBackground(theme.bg);
    split.setBackground(theme.border);
    getRootPane().putClientProperty(FlatClientProperties.TITLE_BAR_BACKGROUND, theme.panel);
    getRootPane().putClientProperty(FlatClientProperties.TITLE_BAR_FOREGROUND, theme.muted);
    for (Term t : terms.values()) t.applyTheme(theme);
    sidebar.rebuild();
    repaint();
  }

  // ---------- Discussions ----------

  private Term ensureTerm(Store.Session s) {
    return terms.computeIfAbsent(s.id, id -> {
      Term t = new Term(s, () -> theme, () -> store.settings);
      t.applyTheme(theme);
      t.widget.getTerminal().addApplicationTitleListener(title -> SwingUtilities.invokeLater(() -> applyClaudeTitle(s, title)));
      t.onRestart(() -> start(t));
      t.onClose(() -> close(s, true));
      terminals.add(t.view, s.id);
      return t;
    });
  }

  @Override
  public void activate(Store.Session s) {
    store.activeId = s.id;
    Term t = ensureTerm(s);
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
    if (t.pty != null) {
      Pty old = t.pty;
      t.pty = null;
      old.close();
    }
    t.hideExit();
    String cwd = firstDir(s.cwd, store.settings.defaultCwd, System.getProperty("user.home"));
    String cmd = Shells.startupCommand(s, shell);
    Pty pty;
    try {
      pty = Pty.start(Shells.commandLine(shell, cmd), cwd, s.name, (buf, off, len) -> onOutput(t, buf, off, len));
    } catch (IOException | RuntimeException e) {
      error("Impossible de lancer " + shell.name() + " :\n" + e.getMessage());
      t.exited = true;
      return;
    }
    t.pty = pty;
    t.widget.setTtyConnector(pty);
    t.widget.start();
    t.running = true;
    t.exited = false;
    if ("claude".equals(s.kind) && !s.claudeStarted) {
      s.claudeStarted = true;
      save();
    }
    Thread watcher = new Thread(() -> {
      int code = 0;
      try {
        code = pty.process().waitFor();
      } catch (InterruptedException ignored) {
      }
      int exit = code;
      SwingUtilities.invokeLater(() -> {
        if (t.pty != pty) return;
        t.running = false;
        t.exited = true;
        t.attention = false;
        t.showExit(exit);
        sidebar.repaint();
        activeDot.repaint();
      });
    }, "younesh-exit-" + s.name);
    watcher.setDaemon(true);
    watcher.start();
    sidebar.repaint();
    activeDot.repaint();
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
    sidebar.rebuild();
    try {
      if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.USER_ATTENTION_WINDOW)) {
        Taskbar.getTaskbar().requestWindowUserAttention(this);
      }
    } catch (RuntimeException ignored) {
    }
    if (store.settings.notify) notifyUser(t.session.name, "claude".equals(t.session.kind) ? "Claude attend ta réponse" : "Nouvelle activité");
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
      if (!Objects.equals(branches.get(s.id), b)) {
        if (b == null) branches.remove(s.id); else branches.put(s.id, b);
        changed = true;
      }
    }
    if (changed) {
      sidebar.rebuild();
      updateTopbar();
    }
  }

  // Claude Code écrit le nom de la session dans le titre du terminal : « ✳ Nom », le symbole tourne
  // pendant qu'il travaille. On reprend ce nom (après /rename par exemple). Les titres des shells
  // (chemins, noms de programmes) commencent par une lettre et sont ignorés.
  private static final Pattern CLAUDE_TITLE = Pattern.compile("^[^\\p{L}\\p{N}\\s]\\s+(.+)$");

  static String claudeTitle(String title) {
    Matcher m = CLAUDE_TITLE.matcher(title.trim());
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
    Term t = ensureTerm(s);
    activate(s);
    start(t);
  }

  void updateTopbar() {
    Store.Session s = store.session(store.activeId);
    activeDot.setVisible(s != null);
    if (s == null) {
      title.setText("");
      path.setText("");
      setTitle("Younesh");
      return;
    }
    title.setText(s.name);
    String b = branches.get(s.id);
    path.setText(Sidebar.shortPath(s.cwd) + (b != null ? "  ⎇ " + b : ""));
    setTitle(s.name + " — Younesh");
    activeDot.repaint();
  }

  private void showEmpty() {
    store.activeId = null;
    cards.show(terminals, "empty");
    updateTopbar();
    sidebar.rebuild();
  }

  void openNew(String kind, String folderId) {
    Store.Session s = new Store.Session();
    s.kind = kind;
    s.name = "";
    s.shell = store.settings.defaultShell;
    s.cwd = store.settings.defaultCwd;
    Store.Session active = store.session(store.activeId);
    s.folderId = folderId != null ? folderId : active != null ? active.folderId : null;
    if (Dialogs.editSession(this, s, true)) createSession(s);
  }

  // Le nom automatique : « Windows PowerShell · projet », « Claude · projet ».
  void createSession(Store.Session s) {
    if (s.cwd == null || s.cwd.isBlank()) s.cwd = firstDir(store.settings.defaultCwd, System.getProperty("user.home"));
    if (s.shell == null || s.shell.isBlank()) {
      Shells.Shell def = Shells.find(store.settings.defaultShell);
      s.shell = def == null ? null : def.id();
    }
    if (s.name == null || s.name.isBlank()) {
      Shells.Shell sh = Shells.find(s.shell);
      String label = "claude".equals(s.kind) ? "Claude" : sh != null ? sh.name() : "Terminal";
      s.name = label + " · " + basename(s.cwd);
    }
    if ("claude".equals(s.kind) && s.claudeId == null) s.claudeId = UUID.randomUUID().toString();
    store.sessions.add(s);
    Store.Folder f = store.folder(s.folderId);
    if (f != null) f.collapsed = false;
    refreshBranches();
    activate(s);
  }

  // Ctrl+Maj+T / N : sans fenêtre, dans le dossier et le répertoire de la discussion active.
  void quickNew(String kind) {
    Store.Session active = store.session(store.activeId);
    Store.Session s = new Store.Session();
    s.kind = kind;
    if (active != null) {
      s.folderId = active.folderId;
      s.cwd = active.cwd;
    }
    createSession(s);
  }

  // Le bouton ›_ : un terminal tout de suite, shell par défaut, dans le dossier personnel.
  void quickTerminal() {
    Store.Session s = new Store.Session();
    s.cwd = System.getProperty("user.home");
    createSession(s);
  }

  static String basename(String p) {
    if (p == null || p.isBlank()) return "";
    String[] parts = p.split("[\\\\/]");
    for (int i = parts.length - 1; i >= 0; i--) if (!parts[i].isEmpty()) return parts[i];
    return p;
  }

  void createFolder() {
    Store.Folder f = new Store.Folder();
    f.name = "Nouveau dossier";
    f.color = Theme.SWATCHES[(store.folders.size() * 3) % Theme.SWATCHES.length];
    store.folders.add(f);
    sidebar.rebuild();
    save();
    sidebar.startRename(f, null);
  }

  @Override
  public void toggle(Store.Folder f) {
    f.collapsed = !f.collapsed;
    sidebar.rebuild();
    save();
  }

  @Override
  public void renamed() {
    updateTopbar();
    save();
  }

  @Override
  public void newIn(Store.Folder f) {
    openNew("shell", f.id);
  }

  // ▶ d'un dossier : démarre toutes ses discussions sans changer de discussion active.
  @Override
  public void launch(Store.Folder f) {
    List<Store.Session> items = store.sessions.stream()
      .filter(s -> f.id.equals(s.folderId) && (terms.get(s.id) == null || !terms.get(s.id).running)).toList();
    for (Store.Session s : items) start(ensureTerm(s));
    int n = items.size();
    toast(n > 0 ? n + " discussion" + (n > 1 ? "s lancées" : " lancée") + " dans « " + f.name + " »." : "Tout tourne déjà dans « " + f.name + " ».");
    sidebar.repaint();
  }

  private void stopFolder(Store.Folder f) {
    List<Term> running = store.sessions.stream().filter(s -> f.id.equals(s.folderId))
      .map(s -> terms.get(s.id)).filter(t -> t != null && t.running).toList();
    if (running.isEmpty()) return;
    if (!confirm("Arrêter les " + running.size() + " discussion(s) en cours dans « " + f.name + " » ?")) return;
    for (Term t : running) if (t.pty != null) t.pty.close();
  }

  @Override
  public void close(Store.Session s) {
    close(s, false);
  }

  void close(Store.Session s, boolean force) {
    Term t = terms.get(s.id);
    if (!force && t != null && t.running && !confirm("Fermer « " + s.name + " » ? Le processus en cours sera arrêté.")) return;
    terms.remove(s.id);
    if (t != null) {
      Pty pty = t.pty;
      t.pty = null;
      if (pty != null) pty.close();
      terminals.remove(t.view);
    }
    List<Store.Session> order = visibleOrder();
    int i = order.indexOf(s);
    store.sessions.remove(s);
    if (s.id.equals(store.activeId)) {
      order.remove(s);
      if (order.isEmpty()) showEmpty();
      else activate(order.get(Math.max(0, Math.min(i, order.size() - 1))));
    }
    sidebar.rebuild();
    save();
  }

  // Ordre d'affichage dans la barre latérale : les dossiers puis « Sans dossier ».
  private List<Store.Session> visibleOrder() {
    List<Store.Session> out = new java.util.ArrayList<>();
    for (Store.Folder f : store.folders) {
      if (f.collapsed) continue;
      for (Store.Session s : store.sessions) if (f.id.equals(s.folderId)) out.add(s);
    }
    for (Store.Session s : store.sessions) if (store.folder(s.folderId) == null) out.add(s);
    return out;
  }

  private void duplicate(Store.Session s) {
    Store.Session copy = new Store.Session();
    copy.name = s.name + " (copie)";
    copy.kind = s.kind;
    copy.folderId = s.folderId;
    copy.cwd = s.cwd;
    copy.shell = s.shell;
    copy.command = s.command;
    copy.claudeArgs = s.claudeArgs;
    copy.color = s.color;
    createSession(copy);
    store.sessions.remove(copy);
    store.sessions.add(store.sessions.indexOf(s) + 1, copy);
    sidebar.rebuild();
    save();
  }

  private void edit(Store.Session s) {
    String shell = s.shell, cwd = s.cwd, command = s.command, args = s.claudeArgs;
    if (!Dialogs.editSession(this, s, false)) return;
    boolean relaunch = !Objects.equals(shell, s.shell) || !Objects.equals(cwd, s.cwd)
      || !Objects.equals(command, s.command) || !Objects.equals(args, s.claudeArgs);
    refreshBranches();
    sidebar.rebuild();
    updateTopbar();
    save();
    Term t = terms.get(s.id);
    if (relaunch && t != null && t.running && confirm("Relancer la discussion avec ces réglages ?")) start(t);
  }

  @Override
  public void moved() {
    sidebar.rebuild();
    save();
  }

  // ---------- Menus contextuels (folderMenu et sessionMenu d'app.js) ----------

  @Override
  public void menu(Store.Folder f, Store.Session s, Point p) {
    JPopupMenu m = Ui.popup();
    if (s != null) {
      Term t = terms.get(s.id);
      Ui.item(m, "Renommer", () -> sidebar.startRename(null, s));
      Ui.item(m, "Dupliquer", () -> duplicate(s));
      Ui.item(m, t != null && t.running ? "Redémarrer" : "Démarrer", () -> restart(s));
      Ui.item(m, "Modifier…", () -> edit(s));
      Ui.item(m, "Copier le chemin", () -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s.cwd == null ? "" : s.cwd), null));
      Ui.separator(m);
      Ui.menuLabel(m, "Ranger dans");
      for (Store.Folder target : store.folders) {
        if (target.id.equals(s.folderId)) continue;
        Ui.item(m, target.name, new Ui.Square(Sidebar.colorOf(target.color, theme.muted2), false), () -> {
          s.folderId = target.id;
          moved();
        }, false);
      }
      if (s.folderId != null) Ui.item(m, "Sans dossier", new Ui.Square(theme.muted2, true), () -> {
        s.folderId = null;
        moved();
      }, false);
      Ui.item(m, "+ Nouveau dossier…", () -> {
        Store.Folder nf = new Store.Folder();
        nf.name = "Nouveau dossier";
        nf.color = Theme.SWATCHES[(store.folders.size() * 3) % Theme.SWATCHES.length];
        store.folders.add(nf);
        s.folderId = nf.id;
        moved();
        sidebar.startRename(nf, null);
      });
      Ui.swatches(m, s.color, c -> {
        s.color = c;
        moved();
      }, true);
      Ui.separator(m);
      Ui.item(m, "Fermer la discussion", null, () -> close(s), true);
    } else {
      Ui.item(m, "+ Nouvelle discussion ici", () -> openNew("shell", f.id));
      Ui.item(m, "✳ Nouvelle discussion Claude ici", () -> openNew("claude", f.id));
      Ui.separator(m);
      Ui.item(m, "▶ Tout lancer", () -> launch(f));
      Ui.item(m, "■ Tout arrêter", () -> stopFolder(f));
      Ui.separator(m);
      Ui.item(m, "Renommer", () -> sidebar.startRename(f, null));
      Ui.item(m, f.collapsed ? "Déplier" : "Replier", () -> toggle(f));
      Ui.swatches(m, f.color, c -> {
        f.color = c;
        moved();
      }, true);
      Ui.separator(m);
      Ui.item(m, "Supprimer le dossier", null, () -> deleteFolder(f), true);
    }
    m.show(sidebar, p.x, p.y);
  }

  private void deleteFolder(Store.Folder f) {
    long n = store.sessions.stream().filter(x -> f.id.equals(x.folderId)).count();
    if (n > 0 && !confirm("Supprimer le dossier « " + f.name + " » ? Ses " + n + " discussion(s) passeront dans « Sans dossier ».")) return;
    store.sessions.stream().filter(x -> f.id.equals(x.folderId)).forEach(x -> x.folderId = null);
    store.folders.remove(f);
    moved();
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
    else if (k == KeyEvent.VK_F2 && !(e.getComponent() instanceof JTextField)) action = () -> {
      Store.Session s = store.session(store.activeId);
      if (s != null) sidebar.startRename(null, s);
    };
    else if (ctrl && shift) {
      switch (k) {
        case KeyEvent.VK_T -> action = () -> quickNew("shell");
        case KeyEvent.VK_N -> action = () -> quickNew("claude");
        case KeyEvent.VK_Q -> action = this::quickTerminal;
        case KeyEvent.VK_D -> action = this::createFolder;
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
    List<Store.Session> list = visibleOrder();
    if (list.isEmpty()) return;
    int i = list.indexOf(store.session(store.activeId));
    activate(list.get(Math.floorMod(i + dir, list.size())));
  }

  // ---------- Divers ----------

  // Message en bas de la fenêtre, quelques secondes (#toast).
  void toast(String text) {
    toast.setText("<html><div style='padding:2px 4px'>" + text.replace("&", "&amp;").replace("<", "&lt;") + "</div></html>");
    toast.setFont(Ui.font(13, false));
    toast.setForeground(theme.text);
    toast.setBorder(BorderFactory.createCompoundBorder(
      BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 3, 1, 1, theme.accent), BorderFactory.createLineBorder(theme.borderStrong, 0)),
      BorderFactory.createEmptyBorder(8, 12, 8, 12)));
    toast.setOpaque(true);
    toast.setBackground(theme.panel2);
    Dimension d = toast.getPreferredSize();
    int w = Math.min(d.width, getLayeredPane().getWidth() - 32);
    toast.setBounds((getLayeredPane().getWidth() - w) / 2, getLayeredPane().getHeight() - d.height - 22, w, d.height);
    toast.setVisible(true);
    if (toastTimer != null) toastTimer.stop();
    toastTimer = new Timer(3500, e -> toast.setVisible(false));
    toastTimer.setRepeats(false);
    toastTimer.start();
  }

  boolean confirm(String msg) {
    return JOptionPane.showConfirmDialog(this, msg, "Younesh", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION;
  }

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

  String branchOf(Store.Session s) {
    return branches.get(s.id);
  }

  // Les actions de la palette rapide.
  Map<String, Runnable> commands() {
    Map<String, Runnable> m = new LinkedHashMap<>();
    m.put("Nouvelle discussion", () -> openNew("shell", null));
    m.put("Nouvelle discussion Claude", () -> openNew("claude", null));
    m.put("Terminal rapide dans le dossier personnel", this::quickTerminal);
    m.put("Nouveau dossier", this::createFolder);
    m.put("Réglages", () -> Dialogs.settings(this));
    m.put("Aller à la discussion qui attend", this::jumpToAttention);
    return m;
  }
}
