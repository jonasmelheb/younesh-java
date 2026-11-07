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
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
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
import java.util.ArrayList;
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
  private final Map<String, String> buffers = store.settings.restore ? Buffers.load() : new HashMap<>();
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
  // Les boutons d'icônes à côté de la recherche, et le compteur de tunnels en bas : visibles seulement si SSH est activé.
  private final JPanel icons = new JPanel(new GridLayout(1, 0, 6, 0));
  private Ui.Btn quickBtn, sbxBtn, sshBtn, folderBtn;
  private final Ui.Btn tunnelsBtn = new Ui.Btn("", Ui.Kind.GHOST);
  private Timer toastTimer;
  private String screens;
  private Runnable toastAction;
  private TrayIcon tray;
  private Image icon;

  Younesh() {
    super("Younesh");
    Ui.theme = theme;
    try {
      icon = ImageIO.read(Younesh.class.getResource("/icon.png"));
      // Toutes les tailles : Windows prend la bonne au lieu de réduire la grande, ce qui la rendait floue.
      List<Image> sizes = new java.util.ArrayList<>();
      for (int size : new int[]{16, 20, 24, 32, 40, 48, 64, 128, 256}) sizes.add(ImageIO.read(Younesh.class.getResource("/icon-" + size + ".png")));
      setIconImages(sizes);
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
    toast.addMouseListener(new java.awt.event.MouseAdapter() {
      @Override
      public void mouseClicked(java.awt.event.MouseEvent e) {
        if (toastAction == null) return;
        Runnable run = toastAction;
        toast.setVisible(false);
        run.run();
      }
    });
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
        Pty.refreshSoon();
        Term t = terms.get(store.activeId);
        if (t != null && t.attention) {
          t.attention = false;
          sidebar.repaint();
        }
      }
    });
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this::shortcut);
    new Timer(500, e -> watchAttention()).start();
    // git suspendus (voir GitFix) : seulement pour les sandbox ouvertes, en arrière-plan.
    if (Shells.WIN) new Timer(15000, e -> cleanSandboxLocks()).start();
    new Timer(4000, e -> refreshBranches()).start();
    if (!Shells.WIN) new Timer(5000, e -> pollCwds()).start();
    new Timer(60000, e -> saveBuffers()).start();
    screens = screenSignature();
    new Timer(2000, e -> watchScreens()).start();

    applyTheme();
    refreshBranches();
    Store.Session active = store.session(store.activeId);
    if (active != null) activate(active); else showEmpty();
    applySshUi();
    if (store.settings.ssh) Ssh.startAuto(this);
    setVisible(true);
    Updater.start(this);
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
    icons.setOpaque(false);
    quickBtn = new Ui.Btn("›_", Ui.Kind.ICON);
    quickBtn.setFont(Ui.font(13, true));
    quickBtn.setToolTipText("Terminal rapide dans ton dossier personnel (Ctrl+Maj+Q)");
    quickBtn.addActionListener(e -> quickTerminal());
    sbxBtn = new Ui.Btn(Ui.SvgIcon.cube());
    sbxBtn.setToolTipText("Sandbox sbx : nouvelle ou existante, préparer le poste, configurer");
    sbxBtn.addActionListener(e -> sbxMenu(sbxBtn));
    sshBtn = new Ui.Btn(Ui.SvgIcon.server());
    sshBtn.setToolTipText("SSH : nouvelle connexion, importer ~/.ssh/config, tunnels");
    sshBtn.addActionListener(e -> sshMenu(sshBtn));
    folderBtn = new Ui.Btn(Ui.SvgIcon.folderPlus());
    folderBtn.setToolTipText("Nouveau dossier (Ctrl+Maj+D)");
    folderBtn.addActionListener(e -> createFolder());
    fillIcons();
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
    tunnelsBtn.addActionListener(e -> Ssh.tunnels(this, null));
    JPanel east = new JPanel(new BorderLayout(4, 0));
    east.setOpaque(false);
    String version = Younesh.class.getPackage().getImplementationVersion();
    if (version != null) east.add(Ui.label("v" + version, 11, null), BorderLayout.WEST);
    east.add(tunnelsBtn, BorderLayout.CENTER);
    east.add(new Ui.Signature(), BorderLayout.EAST);
    p.add(east, BorderLayout.EAST);
    return p;
  }

  private void fillIcons() {
    icons.removeAll();
    icons.add(quickBtn);
    icons.add(sbxBtn);
    if (store.settings.ssh) icons.add(sshBtn);
    icons.add(folderBtn);
    icons.revalidate();
    icons.repaint();
  }

  // Bouton serveur et compteur « ⇄ n » des tunnels ouverts (orange si l'un d'eux a un problème).
  void applySshUi() {
    fillIcons();
    boolean show = store.settings.ssh && !store.tunnels.isEmpty();
    tunnelsBtn.setVisible(show);
    if (!show) return;
    long open = Ssh.openCount(store, null);
    boolean trouble = Ssh.trouble(store);
    Color c = trouble ? Theme.ORANGE : open > 0 ? Theme.OK : theme.muted;
    tunnelsBtn.setText("<html><span style='color:" + Theme.hex(c) + "'>⇄ " + open + "</span></html>");
    tunnelsBtn.setToolTipText("Tunnels SSH : " + open + " ouvert(s) sur " + store.tunnels.size() + (trouble ? " · un tunnel a un problème" : ""));
  }

  void refreshSidebar() {
    sidebar.rebuild();
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

  // Écran branché ou débranché, fenêtre passée sur un autre écran. L'attente laisse Windows finir de la déplacer.
  private void watchScreens() {
    String now = screenSignature();
    if (now.equals(screens)) return;
    screens = now;
    Timer refresh = new Timer(1000, e -> {
      validate();
      repaint();
      for (Term t : terms.values()) t.refreshDisplay();
    });
    refresh.setRepeats(false);
    refresh.start();
  }

  private String screenSignature() {
    StringBuilder sig = new StringBuilder(getGraphicsConfiguration().getDevice().getIDstring());
    for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
      GraphicsConfiguration c = d.getDefaultConfiguration();
      sig.append('|').append(d.getIDstring()).append(c.getBounds()).append(c.getDefaultTransform().getScaleX());
    }
    return sig.toString();
  }

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
    if (shell == null && s.ssh == null) {
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
    // Une connexion SSH lance ssh sans shell autour : quand elle se ferme, la barre propose de se reconnecter.
    List<String> line;
    if (s.ssh != null) {
      line = Ssh.command(Pty.freshEnv(), s.ssh, List.of());
    } else {
      String cmd = "sandbox".equals(s.kind) && s.sbx != null ? Sbx.startup(Sbx.config(store.settings), s, shell) : Shells.startupCommand(s, shell);
      line = followsCwd(s) ? Shells.announcingLine(shell) : Shells.commandLine(shell, cmd);
    }
    Pty pty;
    try {
      Map<String, String> env = s.ssh == null && followsCwd(s) ? Shells.announcingEnv(shell) : Map.of();
      pty = Pty.start(line, cwd, s.name, env, (buf, off, len) -> onOutput(t, buf, off, len));
    } catch (IOException | RuntimeException e) {
      error(s.ssh != null ? "ssh est introuvable sur ce PC. Installe le « Client OpenSSH » : Paramètres › Système › Fonctionnalités facultatives."
        : "Impossible de lancer " + shell.name() + " :\n" + e.getMessage());
      t.exited = true;
      return;
    }
    String saved = buffers.remove(s.id);
    if (saved != null) pty.replay(saved + "\r\n\u001b[2m──── session précédente ────\u001b[0m\r\n");
    t.pty = pty;
    if ("sandbox".equals(s.kind) && s.sbx != null) t.showLoading(s.sbx.created ? "Démarrage de la sandbox" : "Création de la sandbox");
    t.widget.setTtyConnector(pty);
    t.widget.start();
    t.running = true;
    t.exited = false;
    if ("claude".equals(s.kind) && !s.claudeStarted) {
      s.claudeStarted = true;
      save();
    }
    if (s.sbx != null && !s.sbx.created) {
      s.sbx.created = true;
      save();
    }
    // Commande tapée pour l'utilisateur (sans Entrée quand il doit d'abord attendre quelque chose).
    if (s.prefill != null) {
      Store.Prefill pf = s.prefill;
      s.prefill = null;
      save();
      Timer typer = new Timer(1500, e -> {
        if (t.pty == pty) type(s, pf.text + (pf.enter ? "\r" : ""));
      });
      typer.setRepeats(false);
      typer.start();
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
  // Un terminal classique rouvre là où il en était. Claude reste dans son dossier d'origine, dont dépend la reprise.
  private static boolean followsCwd(Store.Session s) {
    return "shell".equals(s.kind) && s.helper == null && (s.command == null || s.command.isBlank());
  }

  private static final Pattern CWD_ANNOUNCE = Pattern.compile("\u001b\\]9;9;\"?([^\u0007\u001b\"]*)\"?(?:\u0007|\u001b\\\\)");

  private void watchCwd(Term t, char[] buf, int off, int len) {
    if (!followsCwd(t.session)) return;
    String text = t.cwdTail + new String(buf, off, len);
    Matcher m = CWD_ANNOUNCE.matcher(text);
    String dir = null;
    int end = 0;
    while (m.find()) {
      dir = m.group(1);
      end = m.end();
    }
    int open = text.lastIndexOf("\u001b]9;9;");
    int keep = open >= end && text.length() - open < 4096 ? open : Math.max(end, text.length() - 6);
    t.cwdTail = text.substring(keep);
    if (dir != null) setCwd(t.session, dir);
  }

  private void setCwd(Store.Session s, String dir) {
    SwingUtilities.invokeLater(() -> {
      if (dir.isBlank() || dir.equals(s.cwd)) return;
      s.cwd = dir;
      save();
      if (s.id.equals(store.activeId)) updateTopbar();
    });
  }

  // Mac et Linux : le dossier se lit sur le processus du shell.
  private void pollCwds() {
    for (Term t : terms.values()) {
      Pty pty = t.pty;
      if (pty == null || !t.running || !followsCwd(t.session)) continue;
      Thread reader = new Thread(() -> {
        String dir = readCwd(pty.process().pid());
        if (dir != null) setCwd(t.session, dir);
      }, "younesh-cwd");
      reader.setDaemon(true);
      reader.start();
    }
  }

  private static String readCwd(long pid) {
    try {
      java.nio.file.Path proc = java.nio.file.Path.of("/proc", String.valueOf(pid), "cwd");
      if (java.nio.file.Files.exists(proc)) return java.nio.file.Files.readSymbolicLink(proc).toString();
      Process p = new ProcessBuilder("lsof", "-a", "-p", String.valueOf(pid), "-d", "cwd", "-Fn").redirectErrorStream(true).start();
      p.getOutputStream().close();
      String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      return out.lines().filter(l -> l.startsWith("n")).map(l -> l.substring(1)).findFirst().orElse(null);
    } catch (java.io.IOException | RuntimeException e) {
      return null;
    }
  }

  private static boolean keepsBuffer(Store.Session s) {
    return "shell".equals(s.kind) || "ssh".equals(s.kind);
  }

  private void saveBuffers() {
    if (!store.settings.restore) return;
    for (Term t : terms.values()) {
      if (keepsBuffer(t.session) && (t.running || t.exited)) buffers.put(t.session.id, Buffers.serialize(t.widget.getTerminalTextBuffer()));
    }
    buffers.keySet().removeIf(id -> store.session(id) == null && store.closed.stream().noneMatch(c -> c.id.equals(id)));
    Buffers.save(buffers);
  }

  private static final int CLOSED_MAX = 10;

  private void rememberClosed(Store.Session s, Term t) {
    if (t != null && store.settings.restore && keepsBuffer(s)) buffers.put(s.id, Buffers.serialize(t.widget.getTerminalTextBuffer()));
    store.closed.add(s);
    while (store.closed.size() > CLOSED_MAX) buffers.remove(store.closed.remove(0).id);
    toast("« " + s.name + " » est fermée.", 8000, "Rouvrir", this::reopenClosed);
  }

  // Rouvre le dernier onglet fermé : son dossier, sa conversation Claude ou sa sandbox.
  void reopenClosed() {
    if (store.closed.isEmpty()) {
      toast("Aucune discussion fermée à rouvrir.");
      return;
    }
    Store.Session s = store.closed.remove(store.closed.size() - 1);
    store.sessions.add(s);
    activate(s);
  }

  // Rien à faire sans sandbox ouverte. Sinon, le test du verrou et la liste des processus partent en arrière-plan.
  private void cleanSandboxLocks() {
    List<String> dirs = new ArrayList<>();
    for (Term t : terms.values()) if (t.running && t.session.sbx != null && t.session.cwd != null) dirs.add(t.session.cwd);
    if (dirs.isEmpty()) return;
    Thread th = new Thread(() -> GitFix.cleanLocks(dirs), "younesh-git-clean");
    th.setDaemon(true);
    th.start();
  }

  private void onOutput(Term t, char[] buf, int off, int len) {
    Sbx.watchInstall(this, t.session, buf, off, len);
    t.watchLoading(buf, off, len);
    GitFix.watch(this, t, buf, off, len);
    watchApiBilling(t, buf, off, len);
    watchCwd(t, buf, off, len);
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

  // Claude connecté par clé d'API et non par l'abonnement : on propose une fois de taper /login dans cet onglet.
  // Les anciennes versions affichent « API Usage Billing », les récentes « … · Claude API » dans l'en-tête
  // (« Opus 5 with medium effort · Claude API »), là où un abonnement affiche « · Claude Pro » ou « · Claude Max ».
  private static final Pattern ANSI = Pattern.compile("\u001b\\[[0-9;?]*[A-Za-z]");
  private static final Pattern CURSOR_RIGHT = Pattern.compile("\u001b\\[\\d*C");
  private static final Pattern API_BILLING = Pattern.compile("API Usage Billing|\\u00b7\\s*Claude API(?![\\w-])");

  static boolean apiBilling(String output) {
    // Le terminal avance parfois le curseur au lieu d'écrire des espaces : on les remet avant de chercher.
    String text = ANSI.matcher(CURSOR_RIGHT.matcher(output).replaceAll(" ")).replaceAll("");
    return API_BILLING.matcher(text).find();
  }

  private void watchApiBilling(Term t, char[] buf, int off, int len) {
    Store.Session s = t.session;
    if (t.loginOffered || !("claude".equals(s.kind) || "sandbox".equals(s.kind) || t.inClaude)) return;
    String tail = t.outTail + new String(buf, off, len);
    t.outTail = tail.substring(Math.max(0, tail.length() - 300));
    if (!apiBilling(tail)) return;
    t.loginOffered = true;
    SwingUtilities.invokeLater(() -> toast("« " + s.name + " » : Claude est facturé à l'API, pas sur ton abonnement.", 60000,
      "Taper /login", () -> {
        activate(s);
        type(s, "/login\r");
      }));
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
      String b = s.ssh != null ? null : Shells.gitBranch(s.cwd);
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
    boolean fresh = !t.running && !t.exited;
    activate(s);
    if (!fresh) start(t);
  }

  // Affiche la discussion et la relance si elle ne tourne plus.
  void wake(Store.Session s) {
    Term t = ensureTerm(s);
    activate(s);
    if (!t.running) start(t);
  }

  boolean running(Store.Session s) {
    Term t = terms.get(s.id);
    return t != null && t.running;
  }

  void type(Store.Session s, String text) {
    Term t = terms.get(s.id);
    if (t == null || t.pty == null) return;
    try {
      t.pty.write(text);
    } catch (IOException ignored) {
    }
  }

  void stop(Store.Session s) {
    Term t = terms.get(s.id);
    if (t != null && t.pty != null) t.pty.close();
  }

  // Range h juste après s, dans le même dossier.
  void placeAfter(Store.Session h, Store.Session s) {
    h.folderId = s.folderId;
    store.sessions.remove(h);
    store.sessions.add(store.sessions.indexOf(s) + 1, h);
    sidebar.rebuild();
    save();
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
    String image = Sbx.image(s);
    path.setText(s.ssh != null ? Ssh.label(s.ssh) : Sidebar.shortPath(s.cwd) + (b != null ? "  ⎇ " + b : "") + (image != null ? "  ·  sbx : " + s.sbx.projet + "  ·  image : " + image : ""));
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
      String label = "claude".equals(s.kind) ? "Claude" : "sandbox".equals(s.kind) ? "Sandbox" : sh != null ? sh.name() : "Terminal";
      s.name = s.ssh != null ? Ssh.target(s.ssh) : label + " · " + basename(s.cwd);
    }
    if ("claude".equals(s.kind) && s.claudeId == null) s.claudeId = UUID.randomUUID().toString();
    store.sessions.add(s);
    Store.Folder f = store.folder(s.folderId);
    if (f != null) f.collapsed = false;
    refreshBranches();
    activate(s);
  }

  // Ajoutée sans l'ouvrir (import de plusieurs serveurs SSH).
  void addQuietly(Store.Session s) {
    store.sessions.add(s);
    Store.Folder f = store.folder(s.folderId);
    if (f != null) f.collapsed = false;
    sidebar.rebuild();
    save();
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
    boolean running = !force && t != null && t.running;
    List<Store.Tunnel> linked = Ssh.of(store, s.id);
    if ((running || !linked.isEmpty()) && !confirm("Fermer « " + s.name + " » ?" + (running ? " Le processus en cours sera arrêté." : "")
      + (linked.isEmpty() ? "" : " Ses " + linked.size() + " tunnel(s) seront supprimés."))) return;
    for (Store.Tunnel tn : linked) Ssh.delete(this, tn);
    if (s.helper == null && (s.sbx == null || !s.sbx.removed)) rememberClosed(s, t);
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
    if (s.sbx != null) copy.sbx = new Store.SbxRef(s.sbx.projet, s.sbx.created);
    if (s.ssh != null) {
      copy.ssh = new Store.SshRef();
      copy.ssh.host = s.ssh.host;
      copy.ssh.user = s.ssh.user;
      copy.ssh.port = s.ssh.port;
      copy.ssh.identity = s.ssh.identity;
      copy.ssh.jump = s.ssh.jump;
      copy.ssh.options = s.ssh.options;
    }
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
      if (s.sbx != null) {
        Ui.item(m, "⧉ Rouvrir Claude dans la sandbox", () -> restart(s));
        Ui.item(m, "⧉ Installer dans la sandbox…", () -> Sbx.install(this, s));
        Ui.item(m, "⧉ Supprimer la sandbox…", null, () -> Sbx.remove(this, s), true);
        Ui.separator(m);
      }
      if (s.ssh != null) {
        if (store.settings.ssh) {
          int n = Ssh.of(store, s.id).size();
          Ui.item(m, "⇄ Tunnels de ce serveur" + (n > 0 ? " (" + n + ")" : "") + "…", () -> Ssh.tunnels(this, s.id));
          Ui.item(m, "⇄ Nouveau tunnel sur ce serveur…", () -> Ssh.form(this, null, s.id));
        }
        Ui.item(m, "Copier la commande ssh", () -> copy(Ssh.commandLine(s.ssh)));
        Ui.separator(m);
      }
      Ui.item(m, "Renommer", () -> sidebar.startRename(null, s));
      Ui.item(m, "Dupliquer", () -> duplicate(s));
      Ui.item(m, s.ssh != null ? (t != null && t.running ? "Se reconnecter" : "Se connecter") : t != null && t.running ? "Redémarrer" : "Démarrer", () -> restart(s));
      Ui.item(m, "Modifier…", () -> {
        if (s.ssh != null) Ssh.edit(this, s, null); else edit(s);
      });
      if (s.ssh == null) Ui.item(m, "Copier le chemin", () -> copy(s.cwd == null ? "" : s.cwd));
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
      Ui.item(m, "⧉ Nouvelle sandbox ici", () -> Sbx.newSandbox(this, f.id));
      Ui.item(m, "⧉ Ouvrir une sandbox existante ici", () -> Sbx.existing(this, f.id));
      if (store.settings.ssh) Ui.item(m, "Nouvelle connexion SSH ici", Ui.SvgIcon.server(), () -> Ssh.edit(this, null, f.id), false);
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

  // Le bouton ⧉ (sbxButtonMenu d'app.js).
  private void sbxMenu(JComponent anchor) {
    JPopupMenu m = Ui.popup();
    Ui.item(m, "⧉ Nouvelle sandbox…", () -> Sbx.newSandbox(this, null));
    Ui.item(m, "⧉ Ouvrir une sandbox existante…", () -> Sbx.existing(this, null));
    Ui.separator(m);
    Ui.item(m, "Préparer le poste…", () -> Sbx.setup(this));
    Ui.item(m, "Configurer sbx…", () -> Sbx.configure(this));
    m.show(anchor, 0, anchor.getHeight() + 4);
  }

  // Le bouton serveur (sshButtonMenu d'app.js).
  private void sshMenu(JComponent anchor) {
    JPopupMenu m = Ui.popup();
    Ui.item(m, "Nouvelle connexion SSH…", () -> Ssh.edit(this, null, null));
    Ui.item(m, "Importer depuis ~/.ssh/config…", () -> Ssh.importConfig(this, null));
    Ui.separator(m);
    Ui.item(m, "⇄ Tunnels…", () -> Ssh.tunnels(this, null));
    Ui.item(m, "⇄ Nouveau tunnel…", () -> Ssh.form(this, null, null));
    m.show(anchor, 0, anchor.getHeight() + 4);
  }

  private static void copy(String text) {
    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
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
      Store.Session as = active.session;
      if (e.getKeyCode() == KeyEvent.VK_ENTER && as.sbx != null && Boolean.FALSE.equals(as.sbx.talked)) {
        as.sbx.talked = true;
        save();
      }
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
        case KeyEvent.VK_R -> action = this::reopenClosed;
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
    toast(text, 3500);
  }

  // Un message avec action : un clic dessus la lance.
  void toast(String text, int ms, String label, Runnable action) {
    toast(text + "  ▸ " + label, ms);
    toastAction = action;
    toast.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
  }

  void toast(String text, int ms) {
    toastAction = null;
    toast.setCursor(java.awt.Cursor.getDefaultCursor());
    String html = text.replace("&", "&amp;").replace("<", "&lt;");
    toast.setText("<html><div style='padding:2px 4px'>" + html + "</div></html>");
    toast.setFont(Ui.font(13, false));
    // Un long message passe sur plusieurs lignes plutôt que de dépasser de la fenêtre.
    if (toast.getPreferredSize().width > 640) toast.setText("<html><div style='padding:2px 4px;width:560px'>" + html + "</div></html>");
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
    toastTimer = new Timer(ms, e -> toast.setVisible(false));
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

  void quit() {
    save();
    saveBuffers();
    Ssh.killAll();
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
    m.put("Nouvelle sandbox", () -> Sbx.newSandbox(this, null));
    m.put("Ouvrir une sandbox existante", () -> Sbx.existing(this, null));
    m.put("Préparer le poste pour sbx", () -> Sbx.setup(this));
    m.put("Configurer sbx", () -> Sbx.configure(this));
    if (store.settings.ssh) {
      m.put("Nouvelle connexion SSH", () -> Ssh.edit(this, null, null));
      m.put("Importer les serveurs de ~/.ssh/config", () -> Ssh.importConfig(this, null));
      m.put("Tunnels SSH", () -> Ssh.tunnels(this, null));
      m.put("Nouveau tunnel SSH", () -> Ssh.form(this, null, null));
    }
    m.put("Nouveau dossier", this::createFolder);
    m.put("Rouvrir la dernière discussion fermée", this::reopenClosed);
    m.put("Réglages", () -> Dialogs.settings(this));
    m.put("Aller à la discussion qui attend", this::jumpToAttention);
    return m;
  }
}
