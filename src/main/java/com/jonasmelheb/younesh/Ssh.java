package com.jonasmelheb.younesh;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import com.formdev.flatlaf.util.SystemFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Taskbar;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Connexions SSH et tunnels (renderer/ssh.js), avec le client OpenSSH livré avec Windows 10 et 11.
// Une discussion SSH lance ssh directement : les clés, l'agent, ~/.ssh/config et la vérification des
// serveurs (known_hosts) restent gérés par OpenSSH. Younesh n'enregistre aucun mot de passe.
final class Ssh {
  private Ssh() {
  }

  static String target(Store.SshRef c) {
    return blank(c.user) ? c.host : c.user + "@" + c.host;
  }

  static String label(Store.SshRef c) {
    return target(c) + (blank(c.port) ? "" : ":" + c.port);
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static final Pattern ARG = Pattern.compile("\"([^\"]*)\"|'([^']*)'|(\\S+)");

  // Découpe « -A -o "Clé=valeur avec espaces" » comme le ferait un shell simple.
  static List<String> splitArgs(String s) {
    List<String> out = new ArrayList<>();
    Matcher m = ARG.matcher(s == null ? "" : s);
    while (m.find()) out.add(m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3));
    return out;
  }

  // « -- » avant le serveur : une adresse qui commencerait par un tiret ne peut pas passer pour une option.
  static List<String> args(Store.SshRef c, List<String> extra) {
    List<String> a = new ArrayList<>();
    if (!blank(c.port)) a.addAll(List.of("-p", c.port));
    if (!blank(c.identity)) a.addAll(List.of("-i", c.identity));
    if (!blank(c.jump)) a.addAll(List.of("-J", c.jump));
    a.addAll(splitArgs(c.options));
    a.addAll(extra);
    a.add("--");
    a.add(target(c));
    return a;
  }

  static List<String> command(Map<String, String> env, Store.SshRef c, List<String> extra) {
    List<String> line = new ArrayList<>();
    line.add(Pty.which(env, "ssh"));
    line.addAll(args(c, extra));
    return line;
  }

  static String commandLine(Store.SshRef c) {
    List<String> parts = new ArrayList<>(List.of("ssh"));
    for (String a : args(c, List.of())) if (!a.equals("--")) parts.add(a.matches(".*[\\s\"'].*") ? "\"" + a + "\"" : a);
    return String.join(" ", parts);
  }

  // ---------- Connexions ----------

  // « moi@serveur » ou « serveur:2222 » collés dans le champ de l'adresse sont rangés dans les bons champs.
  static Store.SshRef read(String user, String host, String port, String identity, String jump, String options) {
    Store.SshRef c = new Store.SshRef();
    user = user.trim();
    host = host.trim();
    port = port.trim();
    int at = host.lastIndexOf('@');
    if (at > 0 && user.isEmpty()) {
      user = host.substring(0, at);
      host = host.substring(at + 1);
    }
    Matcher m = Pattern.compile("^([^:\\s]+):(\\d+)$").matcher(host);
    if (m.find() && port.isEmpty()) {
      host = m.group(1);
      port = m.group(2);
    }
    c.user = user;
    c.host = host;
    c.port = port;
    c.identity = identity.trim();
    c.jump = jump.trim();
    c.options = options.trim();
    return c;
  }

  static String problem(Store.SshRef c) {
    if (c.host.isEmpty()) return "Indique l'adresse du serveur.";
    if (c.host.matches("^-.*|.*\\s.*") || c.user.matches("^-.*|.*[\\s@].*")) return "L'adresse et l'utilisateur ne peuvent pas contenir d'espace ni commencer par un tiret.";
    if (!c.port.isEmpty() && !validPort(c.port)) return "Le port doit être un nombre entre 1 et 65535.";
    return null;
  }

  private static boolean validPort(String p) {
    if (!p.matches("\\d{1,5}")) return false;
    int n = Integer.parseInt(p);
    return n >= 1 && n <= 65535;
  }

  private static boolean same(Store.SshRef a, Store.SshRef b) {
    return Objects.equals(a.host, b.host) && Objects.equals(a.user, b.user) && Objects.equals(a.port, b.port)
      && Objects.equals(a.identity, b.identity) && Objects.equals(a.jump, b.jump) && Objects.equals(a.options, b.options);
  }

  // Nouvelle connexion (s null) ou modification.
  static void edit(Younesh app, Store.Session s, String presetFolder) {
    boolean isNew = s == null;
    Store.SshRef c = isNew ? new Store.SshRef() : s.ssh;
    Ui.Modal m = new Ui.Modal(app, isNew ? "Nouvelle connexion SSH" : "Modifier la connexion SSH", isNew ? "Se connecter" : "Enregistrer");
    JTextField user = Ui.input(c.user, "utilisateur"), host = Ui.input(c.host, "adresse, ou nom dans ~/.ssh/config"), port = Ui.input(c.port, "22");
    user.setPreferredSize(new Dimension(130, user.getPreferredSize().height));
    port.setPreferredSize(new Dimension(70, port.getPreferredSize().height));
    JPanel left = new JPanel(new BorderLayout(6, 0));
    left.setOpaque(false);
    left.add(user, BorderLayout.CENTER);
    left.add(Ui.label("@", 13, null), BorderLayout.EAST);
    JPanel server = new JPanel(new BorderLayout(6, 0));
    server.setOpaque(false);
    server.add(left, BorderLayout.WEST);
    server.add(host, BorderLayout.CENTER);
    server.add(port, BorderLayout.EAST);
    JTextField name = Ui.input(isNew ? "" : s.name, "Automatique");
    JTextField identity = Ui.input(c.identity, "Clés par défaut et agent SSH");
    JTextField jump = Ui.input(c.jump, "ex. admin@bastion.exemple.fr");
    JTextField options = Ui.input(c.options, "ex. -A pour transférer l'agent");
    Store.Session active = app.store.session(app.store.activeId);
    String folderId = !isNew ? s.folderId : presetFolder != null ? presetFolder : active != null ? active.folderId : null;
    JComboBox<Dialogs.Choice> folder = Dialogs.folderChoice(app, folderId);

    m.field("Serveur", server);
    m.field("Nom", name);
    m.field("Clé privée (facultatif)", Ui.Modal.row(identity, browseKey(app, identity)));
    m.field("Serveur de rebond (facultatif)", jump);
    m.field("Options ssh en plus (facultatif)", options);
    m.field("Ranger dans", folder);
    m.field(null, Ui.label("<html><div style='width:400px'>Younesh n'enregistre aucun mot de passe : ssh le demande dans le terminal. "
      + "Avec une clé et l'agent SSH, plus besoin de le taper.</div></html>", 12, null));
    JLabel error = Ui.label(" ", 12, Theme.DANGER);
    m.field(null, error);
    Store.SshRef[] next = {null};
    m.validate = () -> {
      next[0] = read(user.getText(), host.getText(), port.getText(), identity.getText(), jump.getText(), options.getText());
      String p = problem(next[0]);
      error.setText(p == null ? " " : p);
      return p == null;
    };
    if (!m.open(app)) return;
    String n = name.getText().trim();
    if (isNew) {
      Store.Session ns = new Store.Session();
      ns.kind = "ssh";
      ns.ssh = next[0];
      ns.name = n.isEmpty() ? target(next[0]) : n;
      ns.folderId = Dialogs.folderOf(folder);
      ns.cwd = System.getProperty("user.home");
      app.createSession(ns);
      return;
    }
    boolean changed = !same(c, next[0]);
    s.ssh = next[0];
    if (!n.isEmpty()) s.name = n;
    s.folderId = Dialogs.folderOf(folder);
    app.moved();
    app.updateTopbar();
    if (changed && app.running(s) && app.confirm("Se reconnecter avec ces réglages ?")) app.restart(s);
  }

  private static Ui.Btn browseKey(Younesh app, JTextField target) {
    Ui.Btn b = new Ui.Btn("Parcourir…", Ui.Kind.NORMAL);
    b.addActionListener(e -> {
      String start = target.getText().isBlank() ? Path.of(System.getProperty("user.home"), ".ssh").toString() : target.getText();
      SystemFileChooser fc = new SystemFileChooser(start);
      fc.setFileHidingEnabled(false);
      if (fc.showOpenDialog(app) == SystemFileChooser.APPROVE_OPTION) target.setText(fc.getSelectedFile().getAbsolutePath());
    });
    return b;
  }

  record HostEntry(String alias, Map<String, String> props) {
  }

  private static final Pattern CONFIG_LINE = Pattern.compile("^\\s*(\\w+)\\s*(?:=\\s*|\\s)\\s*(.*?)\\s*$");

  // Les serveurs de ~/.ssh/config (sauf les motifs comme « * »). Plusieurs noms sur une ligne Host partagent ses réglages ;
  // comme dans ssh, la première valeur trouvée pour un réglage l'emporte.
  static List<HostEntry> parseConfig(String text) {
    List<HostEntry> hosts = new ArrayList<>();
    Map<String, String> props = null;
    for (String raw : text.split("\\r?\\n")) {
      Matcher m = CONFIG_LINE.matcher(raw);
      if (!m.find() || raw.trim().startsWith("#")) continue;
      String key = m.group(1).toLowerCase(java.util.Locale.ROOT);
      String value = m.group(2).replaceAll("^\"(.*)\"$", "$1");
      if (key.equals("host")) {
        props = new LinkedHashMap<>();
        for (String alias : value.split("\\s+")) if (!alias.isEmpty() && !alias.matches(".*[*?!].*")) hosts.add(new HostEntry(alias, props));
      } else if (key.equals("match")) {
        props = null;
      } else if (props != null) {
        props.putIfAbsent(key, value);
      }
    }
    return hosts;
  }

  // Les discussions importées gardent juste le nom du serveur : ssh relit ~/.ssh/config à chaque connexion.
  static void importConfig(Younesh app, String presetFolder) {
    String text = "";
    try {
      text = Files.readString(Path.of(System.getProperty("user.home"), ".ssh", "config"), StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException ignored) {
    }
    List<HostEntry> hosts = parseConfig(text);
    Ui.Modal m = new Ui.Modal(app, "Importer depuis ~/.ssh/config", hosts.isEmpty() ? null : "Importer");
    if (hosts.isEmpty()) {
      m.field(null, Ui.label("Aucun serveur trouvé dans ~/.ssh/config.", 12, null));
      m.open(app);
      return;
    }
    JPanel list = new JPanel();
    list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
    list.setOpaque(false);
    List<JCheckBox> boxes = new ArrayList<>();
    for (HostEntry h : hosts) {
      boolean known = app.store.sessions.stream().anyMatch(x -> x.ssh != null && h.alias().equals(x.ssh.host) && blank(x.ssh.user) && blank(x.ssh.port));
      Map<String, String> p = h.props();
      String detail = (p.containsKey("user") ? p.get("user") + "@" : "") + p.getOrDefault("hostname", h.alias()) + (p.containsKey("port") ? ":" + p.get("port") : "");
      JCheckBox box = new JCheckBox("<html><b>" + esc(h.alias()) + "</b>&nbsp; <span style='color:" + Theme.hex(Ui.t().muted) + "'>" + esc(detail)
        + (known ? " · déjà dans Younesh" : "") + "</span></html>", !known);
      box.setOpaque(false);
      box.setFont(Ui.font(12.5f, false));
      box.setAlignmentX(JComponent.LEFT_ALIGNMENT);
      box.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
      boxes.add(box);
      list.add(box);
    }
    m.field(null, list);
    Store.Session active = app.store.session(app.store.activeId);
    JComboBox<Dialogs.Choice> folder = Dialogs.folderChoice(app, presetFolder != null ? presetFolder : active != null ? active.folderId : null);
    m.field("Ranger dans", folder);
    if (!m.open(app)) return;
    int n = 0;
    for (int i = 0; i < hosts.size(); i++) {
      if (!boxes.get(i).isSelected()) continue;
      Store.Session s = new Store.Session();
      s.kind = "ssh";
      s.name = hosts.get(i).alias();
      s.ssh = new Store.SshRef();
      s.ssh.host = hosts.get(i).alias();
      s.folderId = Dialogs.folderOf(folder);
      s.cwd = System.getProperty("user.home");
      app.addQuietly(s);
      n++;
    }
    app.toast(n > 0 ? n + " serveur(s) ajouté(s). Clique sur l'un d'eux pour t'y connecter." : "Aucun serveur choisi.", 4000);
  }

  // ---------- Tunnels ----------

  private static final Map<String, String> STATUS = Map.of("off", "Fermé", "connecting", "Connexion…", "prompt", "Attend ta réponse", "on", "Ouvert", "retry", "Coupé", "error", "Erreur");

  // L'état d'un tunnel pendant que Younesh tourne : rien de tout ça n'est enregistré.
  static final class Rt {
    String status = "off", message = "";
    final StringBuilder tail = new StringBuilder();
    boolean wanted, everOn, asking, closedByExit;
    int retries;
    PtyProcess process;
    Timer retry, onTimer, promptTimer;
    Ui.Modal prompt;
  }

  private static final Map<String, Rt> RT = new HashMap<>();

  static Rt rt(String id) {
    return RT.computeIfAbsent(id, k -> new Rt());
  }

  static List<Store.Tunnel> of(Store store, String sessionId) {
    return store.tunnels.stream().filter(t -> Objects.equals(t.sessionId, sessionId)).toList();
  }

  static long openCount(Store store, String sessionId) {
    return store.tunnels.stream().filter(t -> (sessionId == null || sessionId.equals(t.sessionId)) && "on".equals(rt(t.id).status)).count();
  }

  static boolean trouble(Store store) {
    return store.tunnels.stream().anyMatch(t -> List.of("error", "retry", "prompt").contains(rt(t.id).status));
  }

  static List<String> spec(Store.Tunnel t) {
    if ("D".equals(t.type)) return List.of("-D", t.port);
    return List.of("R".equals(t.type) ? "-R" : "-L", t.port + ":" + (blank(t.host) ? "localhost" : t.host) + ":" + t.hostPort);
  }

  // -N : pas de shell, juste les ports. ExitOnForwardFailure : s'arrêter si le port ne s'ouvre pas, au lieu de rester
  // connecté pour rien. LogLevel=VERBOSE : ssh écrit « Authenticated to … » une fois connecté.
  static List<String> extra(Store.Tunnel t) {
    List<String> e = new ArrayList<>(List.of("-N", "-T", "-o", "ExitOnForwardFailure=yes", "-o", "ServerAliveInterval=15",
      "-o", "ServerAliveCountMax=3", "-o", "LogLevel=VERBOSE"));
    e.addAll(spec(t));
    return e;
  }

  static String text(Store store, Store.Tunnel t) {
    Store.Session s = store.session(t.sessionId);
    String server = s != null ? s.name : "serveur supprimé";
    String dest = (blank(t.host) ? "localhost" : t.host) + ":" + t.hostPort;
    if ("D".equals(t.type)) return "Proxy SOCKS sur ce PC (localhost:" + t.port + "), via " + server;
    if ("R".equals(t.type)) return "Port " + t.port + " de " + server + " → " + dest + " vu depuis ce PC";
    return "localhost:" + t.port + " sur ce PC → " + dest + " vu depuis " + server;
  }

  static String name(Store.Tunnel t) {
    if (!blank(t.name)) return t.name;
    if ("D".equals(t.type)) return "SOCKS " + t.port;
    return ("R".equals(t.type) ? "Distant " : "Local ") + t.port + " → " + (blank(t.host) ? "localhost" : t.host) + ":" + t.hostPort;
  }

  private static void set(Younesh app, Store.Tunnel t, String status, String message) {
    Rt r = rt(t.id);
    r.status = status;
    r.message = message;
    if (status.equals("on")) {
      r.everOn = true;
      r.retries = 0;
    }
    app.applySshUi();
    app.refreshSidebar();
    refreshList(app);
  }

  // Ouverture demandée par l'utilisateur (ou au démarrage) : on repart de zéro pour les nouveaux essais.
  static void open(Younesh app, Store.Tunnel t) {
    Rt r = rt(t.id);
    r.everOn = false;
    r.retries = 0;
    start(app, t);
  }

  private static void start(Younesh app, Store.Tunnel t) {
    Rt r = rt(t.id);
    stopTimer(r.retry);
    Store.Session s = app.store.session(t.sessionId);
    if (s == null || s.ssh == null) {
      set(app, t, "error", "La connexion SSH de ce tunnel a été supprimée.");
      return;
    }
    r.wanted = true;
    r.tail.setLength(0);
    set(app, t, "connecting", "");
    Store.SshRef c = s.ssh;
    // Hors de la fenêtre : la première lecture de l'environnement peut prendre une seconde.
    Thread th = new Thread(() -> {
      PtyProcess p = null;
      String err = null;
      try {
        Map<String, String> env = Pty.freshEnv();
        p = new PtyProcessBuilder(command(env, c, extra(t)).toArray(String[]::new))
          .setEnvironment(env)
          .setDirectory(System.getProperty("user.home"))
          .setInitialColumns(200)
          .setInitialRows(24)
          .setUseWinConPty(true)
          .start();
      } catch (IOException | RuntimeException e) {
        err = e.getMessage();
      }
      PtyProcess proc = p;
      String error = err;
      SwingUtilities.invokeLater(() -> attach(app, t, proc, error));
    }, "younesh-tunnel");
    th.setDaemon(true);
    th.start();
  }

  private static void attach(Younesh app, Store.Tunnel t, PtyProcess proc, String error) {
    Rt r = rt(t.id);
    if (!r.wanted) {
      if (proc != null) proc.destroy();
      return;
    }
    if (proc == null) {
      r.wanted = false;
      set(app, t, "error", "ssh est introuvable sur ce PC : installe le « Client OpenSSH » (Paramètres › Système › Fonctionnalités facultatives).");
      return;
    }
    r.process = proc;
    Thread reader = new Thread(() -> {
      try (Reader in = new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8)) {
        char[] buf = new char[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
          String chunk = new String(buf, 0, n);
          SwingUtilities.invokeLater(() -> onData(app, t, proc, chunk));
        }
      } catch (IOException ignored) {
      }
    }, "younesh-tunnel-read");
    reader.setDaemon(true);
    reader.start();
    // Le message d'erreur de ssh doit être lu avant de traiter la fin du processus.
    Thread waiter = new Thread(() -> {
      int code = -1;
      try {
        code = proc.waitFor();
        reader.join(1500);
      } catch (InterruptedException ignored) {
      }
      int exit = code;
      SwingUtilities.invokeLater(() -> onExit(app, t, proc, exit));
    }, "younesh-tunnel-exit");
    waiter.setDaemon(true);
    waiter.start();
  }

  static void stop(Younesh app, Store.Tunnel t) {
    Rt r = rt(t.id);
    r.wanted = false;
    stopTimer(r.retry);
    stopTimer(r.onTimer);
    r.onTimer = null;
    PtyProcess p = r.process;
    r.process = null;
    if (p != null) p.destroy();
    closePrompt(r);
    set(app, t, "off", "");
  }

  static void startAuto(Younesh app) {
    for (Store.Tunnel t : app.store.tunnels) if (t.auto && !rt(t.id).wanted) open(app, t);
  }

  static void stopAll(Younesh app) {
    for (Store.Tunnel t : app.store.tunnels) if (rt(t.id).wanted) stop(app, t);
  }

  // À la fermeture de Younesh : les processus seulement, sans toucher à l'interface.
  static void killAll() {
    for (Rt r : RT.values()) {
      r.wanted = false;
      if (r.process != null) r.process.destroy();
    }
  }

  private static void stopTimer(Timer timer) {
    if (timer != null) timer.stop();
  }

  private static Timer later(int ms, Runnable run) {
    Timer timer = new Timer(ms, e -> run.run());
    timer.setRepeats(false);
    timer.start();
    return timer;
  }

  private static final Pattern ANSI = Pattern.compile("\u001b\\[[0-?]*[ -/]*[@-~]|\u001b\\][^\u0007\u001b]*(?:\u0007|\u001b\\\\)|\u001b[()][0-9A-Za-z]|\u001b[=>78]");

  // Sous Windows, ConPTY peut passer à la ligne en déplaçant le curseur (CSI H) et sauter des espaces (CSI C) :
  // on les remplace par un retour à la ligne et une espace avant d'enlever le reste des séquences.
  static String plainText(String data) {
    String s = data.replaceAll("\u001b\\[[\\d;]*[Hf]", "\n").replaceAll("\u001b\\[\\d*C", " ");
    return ANSI.matcher(s).replaceAll("");
  }

  private static void onData(Younesh app, Store.Tunnel t, PtyProcess proc, String chunk) {
    Rt r = rt(t.id);
    if (r.process != proc || !r.wanted) return;
    r.tail.append(plainText(chunk));
    if (r.tail.length() > 6000) r.tail.delete(0, r.tail.length() - 6000);
    // Connecté. On attend encore un peu : un port déjà pris fait quitter ssh juste après.
    if (!r.status.equals("on") && r.onTimer == null && r.tail.indexOf("Authenticated to ") >= 0) {
      r.onTimer = later(1200, () -> {
        r.onTimer = null;
        if (r.wanted && r.process == proc && !r.status.equals("on")) set(app, t, "on", "");
      });
    }
    stopTimer(r.promptTimer);
    if (!r.status.equals("on")) r.promptTimer = later(350, () -> checkPrompt(app, t, proc));
  }

  // ssh pose une question (mot de passe, phrase de passe, code, nouveau serveur) : sa dernière ligne, sans retour
  // à la ligne, finit par « : » ou « ? ».
  private static void checkPrompt(Younesh app, Store.Tunnel t, PtyProcess proc) {
    Rt r = rt(t.id);
    if (!r.wanted || r.process != proc || r.status.equals("on") || r.asking || r.onTimer != null) return;
    List<String> lines = new ArrayList<>(Arrays.asList(r.tail.toString().split("\\r?\\n", -1)));
    String last = lines.remove(lines.size() - 1);
    String question = last.substring(last.lastIndexOf('\r') + 1).trim();
    if (!question.endsWith(":") && !question.endsWith("?")) return;
    List<String> context = lines.stream().map(String::trim).filter(l -> !l.isEmpty()).toList();
    ask(app, t, question, String.join("\n", context.subList(Math.max(0, context.size() - 4), context.size())));
  }

  private static final Deque<Runnable> PROMPTS = new ArrayDeque<>();
  private static boolean promptOpen;

  private static void ask(Younesh app, Store.Tunnel t, String question, String context) {
    Rt r = rt(t.id);
    r.asking = true;
    set(app, t, "prompt", "");
    try {
      if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.USER_ATTENTION_WINDOW)) Taskbar.getTaskbar().requestWindowUserAttention(app);
    } catch (RuntimeException ignored) {
    }
    PROMPTS.add(() -> showPrompt(app, t, question, context));
    SwingUtilities.invokeLater(Ssh::drain);
  }

  // Une question à la fois : les autres tunnels attendent leur tour.
  private static void drain() {
    if (promptOpen || PROMPTS.isEmpty()) return;
    promptOpen = true;
    try {
      PROMPTS.poll().run();
    } finally {
      promptOpen = false;
    }
    SwingUtilities.invokeLater(Ssh::drain);
  }

  private static void showPrompt(Younesh app, Store.Tunnel t, String question, String context) {
    Rt r = rt(t.id);
    if (!r.wanted || !app.store.tunnels.contains(t)) {
      r.asking = false;
      return;
    }
    boolean yesNo = question.matches("(?is).*\\(yes/no.*");
    Ui.Modal m = new Ui.Modal(app, "Tunnel « " + name(t) + " »", yesNo ? "Faire confiance" : "Envoyer");
    m.width = 480;
    JPasswordField answer = new JPasswordField();
    if (yesNo) {
      m.field(null, Ui.label("<html><div style='width:400px'>Premier passage sur ce serveur : ssh demande de confirmer son empreinte. "
        + "Vérifie-la auprès de son administrateur si tu as un doute.</div></html>", 12, null));
      JTextArea area = new JTextArea((context.isEmpty() ? "" : context + "\n") + question);
      area.setEditable(false);
      area.setLineWrap(true);
      area.setWrapStyleWord(false);
      area.setFont(Ui.mono(11));
      area.setBackground(Ui.t().bg);
      area.setForeground(Ui.t().text);
      area.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Ui.t().border), BorderFactory.createEmptyBorder(8, 10, 8, 10)));
      area.setColumns(52);
      m.field(null, area);
    } else {
      answer.setFont(Ui.font(13, false));
      m.field("<html><div style='width:400px'>" + esc(question) + "</div></html>", answer);
      m.field(null, Ui.label("Envoyé à ssh pour cette connexion uniquement, jamais enregistré par Younesh.", 11, null));
    }
    r.prompt = m;
    boolean ok = m.open(app);
    r.prompt = null;
    r.asking = false;
    if (r.closedByExit) {
      r.closedByExit = false;
      return;
    }
    if (!r.wanted) return;
    if (!ok) {
      stop(app, t);
      return;
    }
    try {
      if (r.process != null) {
        r.process.getOutputStream().write(((yesNo ? "yes" : new String(answer.getPassword())) + "\r").getBytes(StandardCharsets.UTF_8));
        r.process.getOutputStream().flush();
      }
    } catch (IOException ignored) {
    }
    r.tail.append('\n');
    if (r.status.equals("prompt")) set(app, t, "connecting", "");
  }

  // La question d'un tunnel qui s'arrête n'a plus d'objet.
  private static void closePrompt(Rt r) {
    if (r.prompt == null) return;
    r.closedByExit = true;
    r.prompt.dispose();
  }

  record Failure(boolean fatal, String message) {
  }

  // fatal : inutile de réessayer tout seul (refus, port pris, serveur qui a changé d'empreinte).
  static Failure failure(String tail, Store.Tunnel t, int code) {
    if (has(tail, "REMOTE HOST IDENTIFICATION HAS CHANGED")) return new Failure(true, "L'empreinte du serveur a changé depuis la dernière connexion : réinstallation, ou tentative d'interception. "
      + "Vérifie auprès de l'administrateur avant de corriger ~/.ssh/known_hosts.");
    if (has(tail, "Host key verification failed")) return new Failure(true, "Serveur non confirmé : connexion annulée.");
    if (has(tail, "cannot listen to port|Address already in use|Could not request local forwarding|bind \\[?[^\\]\\s]*\\]?:\\d+: "))
      return new Failure(true, "Le port " + t.port + " est déjà pris sur ce PC (ou réservé par Windows) : choisis-en un autre.");
    if (has(tail, "remote port forwarding failed")) return new Failure(true, "Le serveur refuse d'ouvrir son port " + t.port + " : déjà pris, ou redirection interdite (AllowTcpForwarding).");
    if (has(tail, "Permission denied|Too many authentication failures")) return new Failure(true, "Connexion refusée : mot de passe ou clé incorrects.");
    if (has(tail, "Could not resolve hostname")) return new Failure(false, "Adresse du serveur inconnue (ou pas de réseau).");
    if (has(tail, "Connection refused|timed out|No route to host|Network is unreachable|Connection reset|Broken pipe|not responding|Connection closed|Connection to .* closed"))
      return new Failure(false, "Serveur injoignable ou connexion perdue.");
    String last = null;
    for (String l : tail.split("\\r?\\n")) {
      String x = l.trim();
      if (!x.isEmpty() && !x.matches("^(Authenticated to|Transferred:|Warning: Permanently added|debug).*")) last = x;
    }
    return new Failure(false, last != null ? last.substring(0, Math.min(200, last.length())) : "ssh s'est arrêté (code " + code + ").");
  }

  private static boolean has(String tail, String regex) {
    return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(tail).find();
  }

  // Un tunnel qui s'était ouvert puis coupe (réseau, mise en veille) se relance tout seul, de plus en plus espacé.
  private static void onExit(Younesh app, Store.Tunnel t, PtyProcess proc, int code) {
    Rt r = rt(t.id);
    if (r.process != proc) return;
    r.process = null;
    closePrompt(r);
    stopTimer(r.promptTimer);
    stopTimer(r.onTimer);
    r.onTimer = null;
    if (!app.store.tunnels.contains(t)) return;
    if (!r.wanted) {
      set(app, t, "off", "");
      return;
    }
    Failure f = failure(r.tail.toString(), t, code);
    if (r.everOn && !f.fatal()) {
      int delay = new int[]{5, 15, 30, 60}[Math.min(r.retries, 3)];
      r.retries++;
      set(app, t, "retry", f.message() + " Nouvel essai dans " + delay + " s.");
      r.retry = later(delay * 1000, () -> {
        if (r.wanted) start(app, t);
      });
    } else {
      r.wanted = false;
      set(app, t, "error", f.message());
    }
  }

  static void delete(Younesh app, Store.Tunnel t) {
    stop(app, t);
    app.store.tunnels.remove(t);
    RT.remove(t.id);
    app.save();
    app.applySshUi();
    app.refreshSidebar();
    refreshList(app);
  }

  // ---------- Fenêtre des tunnels ----------

  private static JPanel listPanel;
  private static String listSession;
  private static Ui.Modal listModal;

  static void tunnels(Younesh app, String sessionId) {
    Store.Session s = sessionId == null ? null : app.store.session(sessionId);
    Ui.Modal m = new Ui.Modal(app, s != null ? "Tunnels de « " + s.name + " »" : "Tunnels SSH", null);
    m.width = 660;
    m.field(null, Ui.label("<html><div style='width:580px'>Chaque tunnel ouvre sa propre connexion ssh, en arrière-plan. "
      + "S'il coupe après s'être ouvert (réseau, mise en veille), il se relance tout seul.</div></html>", 12, null));
    JPanel list = new JPanel();
    list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
    list.setOpaque(false);
    m.field(null, list);
    Ui.Btn add = new Ui.Btn("+ Nouveau tunnel", Ui.Kind.NORMAL);
    add.addActionListener(e -> form(app, null, sessionId));
    m.action(add);
    listPanel = list;
    listSession = sessionId;
    listModal = m;
    fillList(app);
    m.open(app);
    listPanel = null;
    listModal = null;
  }

  static void refreshList(Younesh app) {
    if (listPanel != null) fillList(app);
  }

  private static void fillList(Younesh app) {
    listPanel.removeAll();
    List<Store.Tunnel> items = app.store.tunnels.stream().filter(t -> listSession == null || listSession.equals(t.sessionId)).toList();
    if (items.isEmpty()) listPanel.add(Ui.label("<html><div style='width:580px'>Aucun tunnel. Un tunnel rend un port du serveur accessible sur ce PC "
      + "(une base de données, une appli web…), ou l'inverse.</div></html>", 12, null));
    for (Store.Tunnel t : items) {
      if (listPanel.getComponentCount() > 0) listPanel.add(Box.createVerticalStrut(6));
      listPanel.add(row(app, t));
    }
    listPanel.revalidate();
    listModal.refit();
  }

  private static Color statusColor(String status) {
    return switch (status) {
      case "on" -> Theme.OK;
      case "connecting" -> Ui.t().accent;
      case "prompt", "retry" -> Theme.ORANGE;
      case "error" -> Theme.DANGER;
      default -> Ui.t().muted;
    };
  }

  private static JComponent row(Younesh app, Store.Tunnel t) {
    Rt r = rt(t.id);
    JPanel row = new JPanel(new BorderLayout(12, 0)) {
      @Override
      protected void paintComponent(Graphics g0) {
        Theme th = Ui.t();
        Graphics2D g = Ui.smooth(g0);
        g.setColor(th.bg);
        g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
        g.setColor(th.border);
        g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
        g.dispose();
      }
    };
    row.setOpaque(false);
    row.setBorder(BorderFactory.createEmptyBorder(9, 10, 9, 10));
    row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
    Switch sw = new Switch(r.wanted, on -> {
      if (on) open(app, t); else stop(app, t);
    });
    JPanel swBox = new JPanel(new java.awt.GridBagLayout());
    swBox.setOpaque(false);
    swBox.add(sw);
    JPanel info = new JPanel();
    info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
    info.setOpaque(false);
    JLabel title = Ui.label(name(t) + (t.auto ? "  · au démarrage" : ""), 13, Ui.t().text);
    title.setFont(Ui.font(13, true));
    info.add(title);
    info.add(Ui.label(text(app.store, t), 11.5f, null));
    String status = STATUS.getOrDefault(r.status, r.status) + (r.message.isEmpty() ? "" : " · " + r.message);
    info.add(Ui.label("<html><div style='width:360px'>● " + esc(status) + "</div></html>", 11.5f, statusColor(r.status)));
    JPanel side = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
    side.setOpaque(false);
    Ui.Btn edit = new Ui.Btn("Modifier", Ui.Kind.NORMAL);
    edit.addActionListener(e -> form(app, t, listSession));
    Ui.Btn del = new Ui.Btn("✕", Ui.Kind.ICON);
    del.setToolTipText("Supprimer le tunnel");
    del.addActionListener(e -> {
      if (app.confirm("Supprimer le tunnel « " + name(t) + " » ?")) delete(app, t);
    });
    side.add(edit);
    side.add(del);
    JPanel sideBox = new JPanel(new java.awt.GridBagLayout());
    sideBox.setOpaque(false);
    sideBox.add(side);
    row.add(swBox, BorderLayout.WEST);
    row.add(info, BorderLayout.CENTER);
    row.add(sideBox, BorderLayout.EAST);
    row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
    return row;
  }

  // Interrupteur on/off (input.switch dans styles.css).
  static final class Switch extends JComponent {
    private boolean on;

    Switch(boolean on, java.util.function.Consumer<Boolean> change) {
      this.on = on;
      setPreferredSize(new Dimension(32, 18));
      setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      setToolTipText("Ouvrir ou fermer le tunnel");
      addMouseListener(new MouseAdapter() {
        @Override
        public void mouseClicked(MouseEvent e) {
          Switch.this.on = !Switch.this.on;
          repaint();
          change.accept(Switch.this.on);
        }
      });
    }

    @Override
    protected void paintComponent(Graphics g0) {
      Graphics2D g = Ui.smooth(g0);
      Theme th = Ui.t();
      g.setColor(on ? Theme.OK : th.borderStrong);
      g.fillRoundRect(0, 0, 32, 18, 18, 18);
      g.setColor(on ? Theme.c("#0e0f13") : th.text);
      g.fillOval(on ? 16 : 2, 2, 14, 14);
      g.dispose();
    }
  }

  private static JPanel labeled(JLabel label, JComponent input) {
    JPanel p = new JPanel(new BorderLayout(0, 5));
    p.setOpaque(false);
    p.add(label, BorderLayout.NORTH);
    p.add(input, BorderLayout.CENTER);
    return p;
  }

  static String tunnelProblem(Store.Tunnel v) {
    if (!validPort(v.port)) return "Le port doit être un nombre entre 1 et 65535.";
    if (!"D".equals(v.type) && !validPort(v.hostPort)) return "Indique le port de destination (entre 1 et 65535).";
    if (!"D".equals(v.type) && v.host.matches(".*[\\s:].*")) return "La destination doit être un nom ou une adresse, sans espace ni « : ».";
    return null;
  }

  private static final String[] TYPES = {"L", "R", "D"};

  // Nouveau tunnel (t null) ou modification ; sessionId : la connexion proposée d'office.
  static void form(Younesh app, Store.Tunnel t, String sessionId) {
    List<Store.Session> servers = app.store.sessions.stream().filter(x -> x.ssh != null).toList();
    if (servers.isEmpty()) {
      Ui.Modal m = new Ui.Modal(app, "Nouveau tunnel", "Nouvelle connexion SSH…");
      m.field(null, Ui.label("Un tunnel passe par une connexion SSH : crée-la d'abord.", 12, null));
      if (m.open(app)) edit(app, null, null);
      return;
    }
    Store.Session active = app.store.session(app.store.activeId);
    String server0 = t != null ? t.sessionId : sessionId != null ? sessionId : active != null && active.ssh != null ? active.id : servers.get(0).id;
    Ui.Modal m = new Ui.Modal(app, t != null ? "Modifier le tunnel" : "Nouveau tunnel", t != null ? "Enregistrer" : "Créer et ouvrir");
    m.width = 520;
    JComboBox<Dialogs.Choice> server = new JComboBox<>();
    for (Store.Session s : servers) {
      Dialogs.Choice c = new Dialogs.Choice(s.id, s.name + " (" + label(s.ssh) + ")");
      server.addItem(c);
      if (s.id.equals(server0)) server.setSelectedItem(c);
    }
    Ui.Segmented dir = new Ui.Segmented("Ce PC → serveur", "Serveur → ce PC", "Proxy SOCKS");
    JTextField port = Ui.input(t != null ? t.port : "", "ex. 5432");
    JTextField host = Ui.input(t != null && !blank(t.host) ? t.host : "localhost", null);
    JTextField hostPort = Ui.input(t != null ? t.hostPort : "", "port");
    hostPort.setPreferredSize(new Dimension(90, hostPort.getPreferredSize().height));
    JTextField name = Ui.input(t != null ? t.name : "", "Automatique");
    JCheckBox auto = new JCheckBox("Ouvrir au démarrage de Younesh", t != null && t.auto);
    JLabel portLabel = Ui.label("", 12, null), destLabel = Ui.label("", 12, null);
    JLabel preview = Ui.label(" ", 12, null), error = Ui.label(" ", 12, Theme.DANGER);

    m.field("Connexion SSH", server);
    m.field("Sens", dir);
    m.field(null, labeled(portLabel, port));
    JComponent dest = m.field(null, labeled(destLabel, Ui.Modal.row(host, hostPort)));
    m.field("Nom (facultatif)", name);
    m.check(auto);
    m.field(null, preview);
    m.field(null, error);

    // Même numéro de port des deux côtés par défaut : c'est le cas le plus courant.
    boolean[] destEdited = {t != null && !Objects.equals(t.hostPort, t.port)};
    java.util.function.Supplier<Store.Tunnel> read = () -> {
      Store.Tunnel v = new Store.Tunnel();
      v.sessionId = ((Dialogs.Choice) server.getSelectedItem()).id();
      v.type = TYPES[dir.selected()];
      v.port = port.getText().trim();
      v.host = host.getText().trim().isEmpty() ? "localhost" : host.getText().trim();
      v.hostPort = hostPort.getText().trim();
      v.name = name.getText().trim();
      v.auto = auto.isSelected();
      return v;
    };
    Runnable update = () -> {
      String type = TYPES[dir.selected()];
      portLabel.setText(switch (type) {
        case "R" -> "Port sur le serveur";
        case "D" -> "Port du proxy sur ce PC";
        default -> "Port sur ce PC";
      });
      destLabel.setText("R".equals(type) ? "Destination, vue depuis ce PC" : "Destination, vue depuis le serveur");
      dest.setVisible(!"D".equals(type));
      Store.Tunnel v = read.get();
      preview.setText(tunnelProblem(v) != null ? " " : "<html><div style='width:440px'>" + esc(text(app.store, v)) + " · ssh " + esc(String.join(" ", spec(v))) + "</div></html>");
      m.refit();
    };
    dir.onChange(i -> update.run());
    dir.select(Math.max(0, Arrays.asList(TYPES).indexOf(t != null ? t.type : "L")));
    onChange(port, () -> {
      if (!destEdited[0]) hostPort.setText(port.getText());
      update.run();
    });
    hostPort.addKeyListener(new java.awt.event.KeyAdapter() {
      @Override
      public void keyTyped(java.awt.event.KeyEvent e) {
        destEdited[0] = true;
      }
    });
    onChange(hostPort, update);
    onChange(host, update);
    server.addActionListener(e -> update.run());
    m.validate = () -> {
      Store.Tunnel v = read.get();
      String p = tunnelProblem(v);
      error.setText(p == null ? " " : p);
      return p == null;
    };
    if (!m.open(app)) return;
    Store.Tunnel v = read.get();
    if ("D".equals(v.type)) {
      v.host = "";
      v.hostPort = "";
    }
    if (t != null) {
      boolean wasOpen = rt(t.id).wanted;
      t.sessionId = v.sessionId;
      t.type = v.type;
      t.port = v.port;
      t.host = v.host;
      t.hostPort = v.hostPort;
      t.name = v.name;
      t.auto = v.auto;
      if (wasOpen) {
        stop(app, t);
        open(app, t);
      }
    } else {
      app.store.tunnels.add(v);
      open(app, v);
    }
    app.save();
    app.applySshUi();
    refreshList(app);
    if (listModal == null) tunnels(app, sessionId);
  }

  private static void onChange(JTextField f, Runnable run) {
    f.getDocument().addDocumentListener(new DocumentListener() {
      public void insertUpdate(DocumentEvent e) { run.run(); }
      public void removeUpdate(DocumentEvent e) { run.run(); }
      public void changedUpdate(DocumentEvent e) { run.run(); }
    });
  }

  private static String esc(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
