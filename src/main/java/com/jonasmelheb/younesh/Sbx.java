package com.jonasmelheb.younesh;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

// Sandbox sbx (renderer/sbx.js), en trois couches pour qu'une nouvelle version de sbx ne touche que « cli » :
//  - cli : les commandes sbx elles-mêmes (créer, ouvrir, exécuter, supprimer) ;
//  - setup : la préparation du poste, une fois par PC ;
//  - installs : ce qu'on installe dans une sandbox, choisi projet par projet.
// La configuration vit dans les réglages locaux, jamais dans le dépôt, et s'importe ou s'exporte en JSON.
// Variables : {projet}, {dossier}, {script} (exec) et {secret:NOM}, demandé au lancement et jamais enregistré.
final class Sbx {
  private Sbx() {
  }

  static final class Config {
    String format = "younesh-sbx";
    int version = 1;
    Cli cli = new Cli();
    List<Step> setup = new ArrayList<>();
    List<Install> installs = new ArrayList<>();
  }

  static final class Cli {
    String create, open, exec, remove;
  }

  static final class Step {
    String id, label, cmd;
  }

  static final class Install {
    String id, label, script;
  }

  record Existing(String name, String status, String workspace) {
  }

  private static final String DEFAULT = """
    {
      "format": "younesh-sbx",
      "version": 1,
      "cli": {
        "create": "sbx run --name={projet} --clone claude .",
        "open": "sbx run --name={projet}",
        "exec": "sbx exec -it {projet} bash -lc \\"{script}\\"",
        "remove": "sbx rm {projet}"
      },
      "setup": [
        { "id": "version", "label": "Vérifier la version de sbx", "cmd": "sbx version" },
        { "id": "login", "label": "Se connecter à Docker", "cmd": "sbx login" },
        { "id": "network", "label": "Initialiser le réseau (choisir le mode)", "cmd": "sbx" }
      ],
      "installs": [
        { "id": "claude-update", "label": "Mettre à jour Claude", "script": "claude update" },
        { "id": "tz", "label": "Fuseau horaire Europe/Paris", "script": "echo 'export TZ=Europe/Paris' >> /etc/sandbox-persistent.sh" }
      ]
    }""";

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

  static Config defaults() {
    return GSON.fromJson(DEFAULT, Config.class);
  }

  static Config config(Store.Settings st) {
    Config cfg = st.sbx != null ? st.sbx : defaults();
    // « sbx run <nom> » est déprécié depuis sbx 0.33 : les configurations enregistrées ou importées passent à --name.
    if (cfg.cli != null && cfg.cli.open != null && cfg.cli.open.trim().equals("sbx run {projet}")) cfg.cli.open = "sbx run --name={projet}";
    return cfg;
  }

  static String slug(String name) {
    String s = Normalizer.normalize(name == null ? "" : name.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
      .replaceAll("\\p{M}", "").replaceAll("[^a-z0-9-]+", "-").replaceAll("^-+|-+$", "");
    return s.isEmpty() ? "projet" : s;
  }

  private static final Pattern VAR = Pattern.compile("\\{(\\w+)\\}");
  private static final Pattern SECRET = Pattern.compile("\\{secret:(\\w+)\\}");

  static String fill(String tpl, Map<String, String> vars) {
    Matcher m = VAR.matcher(tpl);
    StringBuilder out = new StringBuilder();
    while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(vars.getOrDefault(m.group(1), m.group())));
    m.appendTail(out);
    return out.toString();
  }

  private static String imageOf(String create) {
    Matcher m = Pattern.compile("\\s--template=(\\S+)").matcher(create);
    return m.find() ? m.group(1) : "";
  }

  private static String createWithImage(String create, String image) {
    String bare = create.replaceFirst("\\s+--template=\\S+", "");
    return image.isEmpty() ? bare : bare.replaceFirst("^\\s*\\S+\\s+\\S+", "$0 --template=" + Matcher.quoteReplacement(image));
  }

  static String startup(Config cfg, Store.Session s, Shells.Shell shell) {
    Map<String, String> vars = Map.of("projet", s.sbx.projet, "dossier", "\"" + s.cwd + "\"");
    if (!s.sbx.created) return fill(s.sbx.image == null ? cfg.cli.create : createWithImage(cfg.cli.create, s.sbx.image), vars);
    String open = fill(cfg.cli.open, vars);
    if (open.contains(" -- ") || !cfg.cli.create.matches(".*\\bclaude\\b.*")) return open;
    // Reprend la dernière conversation de la sandbox ; s'il n'y en a pas encore, Claude démarre normalement.
    String resume = open + " -- --continue";
    return "powershell".equals(shell.family()) ? resume + "; if (-not $?) { " + open + " }" : resume + " || " + open;
  }

  // Les étapes d'installation partent en un seul « exec ». Le script est encodé en base64 pour
  // traverser PowerShell, cmd ou bash sans souci de guillemets ni de « $ », puis lancé depuis un
  // fichier pour garder le clavier (sudo, questions éventuelles).
  private static final String INSTALL_DONE = "==> Installation terminée.";

  static String installCommand(Config cfg, String projet, List<Install> items) {
    List<String> lines = new ArrayList<>(List.of("set -e"));
    for (Install it : items) {
      lines.add("echo; echo \"==> " + it.label.replace("\"", "") + "\"");
      lines.add(it.script);
    }
    lines.add("echo; echo \"" + INSTALL_DONE + " Relance Claude : clic droit sur la sandbox, Rouvrir Claude.\"");
    String b64 = Base64.getEncoder().encodeToString(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    String script = "echo " + b64 + " | base64 -d > /tmp/younesh-install.sh && bash /tmp/younesh-install.sh";
    return fill(cfg.cli.exec, Map.of("projet", projet, "script", script));
  }

  // Sandboxes déjà créées. « sbx ls --json » renvoie une liste ; les anciennes versions l'emballent dans { sandboxes }.
  static List<Existing> list() throws IOException {
    Map<String, String> env = Pty.freshEnv();
    ProcessBuilder pb = new ProcessBuilder(Pty.which(env, "sbx"), "ls", "--json");
    pb.environment().clear();
    pb.environment().putAll(env);
    Process p;
    try {
      p = pb.start();
    } catch (IOException e) {
      throw new IOException("sbx est introuvable sur ce PC.");
    }
    CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> read(p.getInputStream()));
    CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> read(p.getErrorStream()));
    try {
      if (!p.waitFor(30, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new IOException("sbx ne répond pas.");
      }
    } catch (InterruptedException e) {
      throw new IOException("Recherche interrompue.");
    }
    if (p.exitValue() != 0) {
      String msg = err.join().trim();
      throw new IOException(msg.isEmpty() ? "sbx ls a échoué (code " + p.exitValue() + ")." : msg);
    }
    try {
      JsonElement data = JsonParser.parseString(out.join());
      JsonElement items = data.isJsonObject() ? data.getAsJsonObject().get("sandboxes") : data;
      List<Existing> found = new ArrayList<>();
      if (items != null && items.isJsonArray()) {
        for (JsonElement el : items.getAsJsonArray()) {
          if (!el.isJsonObject()) continue;
          JsonObject o = el.getAsJsonObject();
          String name = string(o.get("name"));
          if (name.isEmpty()) continue;
          JsonElement ws = o.get("workspaces");
          String workspace = ws != null && ws.isJsonArray() && !ws.getAsJsonArray().isEmpty() ? string(ws.getAsJsonArray().get(0)) : string(o.get("workspace"));
          found.add(new Existing(name, string(o.get("status")), workspace.replaceAll(":ro$", "")));
        }
      }
      return found;
    } catch (JsonParseException | IllegalStateException e) {
      throw new IOException("Réponse de « sbx ls » illisible.");
    }
  }

  private static String read(InputStream in) {
    try (in) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return "";
    }
  }

  private static String string(JsonElement e) {
    return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : "";
  }

  // ---------- Actions ----------

  static void create(Younesh app, String projet, String dir, String folderId, List<Install> items, String image) {
    Store.Session s = new Store.Session();
    s.kind = "sandbox";
    s.name = projet;
    s.cwd = dir;
    s.folderId = folderId;
    s.sbx = new Store.SbxRef(projet, false);
    s.sbx.image = image;
    app.createSession(s);
    if (!items.isEmpty()) {
      Store.Session h = helper(app, "Installation · " + projet, dir, folderId, installCommand(config(app.store.settings), projet, items), false);
      app.placeAfter(h, s);
      app.activate(s);
      runWhenReady(app, projet, h);
      app.toast("Sandbox « " + projet + " » : connecte-toi dans Claude (/login). Les commandes se lanceront seules dans « Installation · " + projet + " » dès que la sandbox tourne.", 15000);
    } else {
      app.toast("Sandbox « " + projet + " » : connecte-toi dans Claude avec /login la première fois.", 9000);
    }
  }

  // Les discussions d'installation en cours : id de la discussion → sandbox et fin de la sortie reçue.
  private static final class Watch {
    final String projet;
    String tail = "";

    Watch(String projet) {
      this.projet = projet;
    }
  }

  private static final Map<String, Watch> INSTALLING = new java.util.concurrent.ConcurrentHashMap<>();
  private static final int READY_TRIES = 200;

  // La commande est déjà tapée dans la discussion d'installation : on la valide dès que « sbx ls » montre
  // la sandbox en marche. Après 10 minutes sans la voir, on rend la main : Entrée la lance toujours.
  static void runWhenReady(Younesh app, String projet, Store.Session helper) {
    INSTALLING.put(helper.id, new Watch(projet));
    Thread t = new Thread(() -> {
      for (int i = 0; i < READY_TRIES; i++) {
        boolean up = false;
        try {
          up = list().stream().anyMatch(x -> x.name().equals(projet) && x.status().toLowerCase(Locale.ROOT).startsWith("running"));
        } catch (IOException ignored) {
        }
        boolean ready = up && app.running(helper) && helper.prefill == null;
        try {
          Thread.sleep(ready ? 2000 : 3000);
        } catch (InterruptedException e) {
          return;
        }
        if (ready) {
          SwingUtilities.invokeLater(() -> app.type(helper, "\r"));
          return;
        }
      }
      INSTALLING.remove(helper.id);
      SwingUtilities.invokeLater(() -> app.toast("La sandbox « " + projet + " » n'a pas démarré. Quand elle tourne, va dans « Installation · " + projet + " » et appuie sur Entrée.", 30000));
    }, "younesh-sbx-ready");
    t.setDaemon(true);
    t.start();
  }

  // Claude doit être rouvert pour voir ce qu'on vient d'installer. On le propose sans le faire : un /login peut être en cours.
  static void watchInstall(Younesh app, Store.Session s, char[] buf, int off, int len) {
    Watch w = INSTALLING.get(s.id);
    if (w == null) return;
    String tail = w.tail + new String(buf, off, len);
    w.tail = tail.substring(Math.max(0, tail.length() - 200));
    if (!w.tail.contains(INSTALL_DONE) || INSTALLING.remove(s.id) == null) return;
    SwingUtilities.invokeLater(() -> {
      Store.Session sandbox = app.store.sessions.stream().filter(x -> x.sbx != null && w.projet.equals(x.sbx.projet)).findFirst().orElse(null);
      if (sandbox == null) app.toast("Installation terminée dans « " + w.projet + " ».", 15000);
      else app.toast("Installation terminée dans « " + w.projet + " ».", 120000, "Rouvrir Claude", () -> app.restart(sandbox));
    });
  }

  // Rouvre dans Younesh une sandbox créée ailleurs (autre terminal, tableau de bord sbx) ou dont la discussion a été fermée.
  static void openExisting(Younesh app, Existing x, String folderId) {
    Store.Session known = app.store.sessions.stream().filter(s -> s.sbx != null && x.name().equals(s.sbx.projet)).findFirst().orElse(null);
    if (known != null) {
      app.wake(known);
      return;
    }
    Store.Session active = app.store.session(app.store.activeId);
    Store.Session s = new Store.Session();
    s.kind = "sandbox";
    s.name = x.name();
    s.cwd = !x.workspace().isEmpty() && Files.isDirectory(Path.of(x.workspace())) ? x.workspace() : System.getProperty("user.home");
    s.folderId = folderId != null ? folderId : active != null ? active.folderId : null;
    s.sbx = new Store.SbxRef(x.name(), true);
    app.createSession(s);
  }

  // Discussion d'appoint (préparation du poste, installation…) : réutilisée si elle existe déjà.
  // La commande est tapée sans Entrée quand il faut attendre que la sandbox soit prête.
  static Store.Session helper(Younesh app, String name, String cwd, String folderId, String text, boolean enter) {
    Store.Session h = app.store.sessions.stream().filter(x -> name.equals(x.helper)).findFirst().orElse(null);
    if (h != null && app.running(h)) {
      app.activate(h);
      app.type(h, text + (enter ? "\r" : ""));
      return h;
    }
    if (h == null) {
      h = new Store.Session();
      h.name = name;
      h.cwd = cwd;
      h.folderId = folderId;
      h.helper = name;
      h.prefill = new Store.Prefill(text, enter);
      app.createSession(h);
    } else {
      h.prefill = new Store.Prefill(text, enter);
      app.wake(h);
    }
    app.save();
    return h;
  }

  static void remove(Younesh app, Store.Session s) {
    String projet = s.sbx.projet;
    if (!app.confirm("Supprimer la sandbox « " + projet + " » ?\n\nAvant, récupère les commits non poussés : git fetch sandbox-" + projet
      + "\n\nLa commande de suppression sera tapée dans une discussion, à valider avec Entrée.")) return;
    app.stop(s);
    s.sbx.removed = true;
    app.save();
    helper(app, "Commandes sbx · " + projet, s.cwd, s.folderId,
      fill(config(app.store.settings).cli.remove, Map.of("projet", projet, "dossier", "\"" + s.cwd + "\"")), false);
  }

  // ---------- Fenêtres ----------

  static void newSandbox(Younesh app, String presetFolder) {
    Store.Settings st = app.store.settings;
    Config cfg = config(st);
    Store.Session active = app.store.session(app.store.activeId);
    String home = System.getProperty("user.home");
    String cwd0 = active != null && active.cwd != null ? active.cwd : st.defaultCwd.isBlank() ? home : st.defaultCwd;
    String folder0 = presetFolder != null ? presetFolder : active != null ? active.folderId : null;

    Ui.Modal m = new Ui.Modal(app, "Nouvelle sandbox", "Créer la sandbox");
    JTextField cwd = Ui.input(cwd0, home);
    JTextField name = Ui.input(slug(Younesh.basename(cwd0)), null);
    boolean[] edited = {false};
    name.addKeyListener(new KeyAdapter() {
      @Override
      public void keyTyped(KeyEvent e) {
        edited[0] = true;
      }
    });
    onChange(cwd, () -> {
      if (!edited[0]) name.setText(slug(Younesh.basename(cwd.getText())));
    });
    JComboBox<Dialogs.Choice> folder = Dialogs.folderChoice(app, folder0);
    JTextField image = Ui.input(st.sbxImage != null ? st.sbxImage : imageOf(cfg.cli.create), null);
    Set<String> selected = new LinkedHashSet<>(st.sbxLastInstalls);
    m.field("Dossier du projet", Ui.Modal.row(cwd, Dialogs.browse(app, cwd)));
    m.field("Nom de la sandbox", name);
    m.field("Ranger dans", folder);
    m.field("Image de la sandbox (facultatif)", image);
    m.field("Installer dans la sandbox", installs(app, m, selected));
    m.field(null, links(m, app));
    m.validate = () -> !cwd.getText().isBlank();
    if (!m.open(app)) return;

    List<Install> items = config(st).installs.stream().filter(it -> selected.contains(it.id)).toList();
    st.sbxLastInstalls = new ArrayList<>(items.stream().map(it -> it.id).toList());
    st.sbxImage = image.getText().replaceAll("\\s+", "");
    create(app, slug(name.getText()), cwd.getText().trim(), Dialogs.folderOf(folder), items, st.sbxImage);
  }

  // « Préparer le poste » et « Configurer sbx » sous le formulaire, comme les liens de la version Electron.
  private static JComponent links(Ui.Modal m, Younesh app) {
    JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    p.setOpaque(false);
    Ui.Btn setup = new Ui.Btn("Préparer le poste (une fois par PC)", Ui.Kind.GHOST);
    setup.addActionListener(e -> setup(app));
    Ui.Btn config = new Ui.Btn("Configurer sbx", Ui.Kind.GHOST);
    config.addActionListener(e -> configure(app));
    p.add(setup);
    p.add(Box.createHorizontalStrut(6));
    p.add(config);
    return p;
  }

  // Les commandes enregistrées : cases à cocher, plus ajouter, modifier et supprimer sur place.
  private static JComponent installs(Younesh app, Ui.Modal m, Set<String> selected) {
    JPanel p = new JPanel();
    p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
    p.setOpaque(false);
    drawInstalls(app, m, p, selected);
    return p;
  }

  private static void drawInstalls(Younesh app, Ui.Modal m, JPanel p, Set<String> selected) {
    Runnable redraw = () -> {
      drawInstalls(app, m, p, selected);
      m.refit();
    };
    Config cfg = config(app.store.settings);
    p.removeAll();
    if (cfg.installs.isEmpty()) p.add(Ui.label("Aucune commande enregistrée.", 12, null));
    for (Install it : cfg.installs) {
      JCheckBox b = new JCheckBox(it.label, selected.contains(it.id));
      b.setOpaque(false);
      b.setFont(Ui.font(12.5f, false));
      b.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
      b.addActionListener(e -> {
        if (b.isSelected()) selected.add(it.id); else selected.remove(it.id);
      });
      Ui.Btn edit = new Ui.Btn("✎", Ui.Kind.ICON);
      edit.setToolTipText("Modifier");
      edit.addActionListener(e -> {
        if (editInstall(app, it, selected)) redraw.run();
      });
      Ui.Btn del = new Ui.Btn("✕", Ui.Kind.ICON);
      del.setToolTipText("Supprimer");
      del.addActionListener(e -> {
        if (!app.confirm("Supprimer la commande « " + it.label + " » ?")) return;
        cfg.installs.remove(it);
        selected.remove(it.id);
        app.store.settings.sbx = cfg;
        app.save();
        redraw.run();
      });
      JPanel side = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
      side.setOpaque(false);
      side.add(edit);
      side.add(del);
      JPanel row = new JPanel(new BorderLayout(6, 0));
      row.setOpaque(false);
      row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
      row.add(b, BorderLayout.CENTER);
      row.add(side, BorderLayout.EAST);
      row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
      p.add(row);
    }
    Ui.Btn add = new Ui.Btn("+ Ajouter une commande", Ui.Kind.GHOST);
    add.setAlignmentX(JComponent.LEFT_ALIGNMENT);
    add.addActionListener(e -> {
      if (editInstall(app, null, selected)) redraw.run();
    });
    p.add(Box.createVerticalStrut(4));
    p.add(add);
    p.revalidate();
    p.repaint();
  }

  // Ajoute (item null) ou modifie une commande enregistrée. Une nouvelle commande est cochée d'office.
  private static boolean editInstall(Younesh app, Install item, Set<String> selected) {
    Ui.Modal m = new Ui.Modal(app, item == null ? "Ajouter une commande" : "Modifier la commande", "Enregistrer");
    JTextField label = Ui.input(item == null ? "" : item.label, "Plugin de l'équipe");
    JTextArea script = new JTextArea(item == null ? "" : item.script, 4, 40);
    script.setLineWrap(true);
    script.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
    script.setBackground(Ui.t().bg);
    script.setForeground(Ui.t().text);
    script.setCaretColor(Ui.t().text);
    script.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
    JScrollPane sp = new JScrollPane(script);
    sp.setBorder(BorderFactory.createLineBorder(Ui.t().border));
    m.field("Nom", label);
    m.field("Commande, lancée dans la sandbox", sp);
    m.validate = () -> !label.getText().isBlank() && !script.getText().isBlank();
    if (!m.open(app)) return false;
    Store.Settings st = app.store.settings;
    Config cfg = config(st);
    if (item != null) {
      item.label = label.getText().trim();
      item.script = script.getText().trim();
    } else {
      Install it = new Install();
      it.id = slug(label.getText());
      while (true) {
        String id = it.id;
        if (cfg.installs.stream().noneMatch(x -> x.id.equals(id))) break;
        it.id += "-2";
      }
      it.label = label.getText().trim();
      it.script = script.getText().trim();
      cfg.installs.add(it);
      selected.add(it.id);
    }
    st.sbx = cfg;
    app.save();
    return true;
  }

  static void existing(Younesh app, String folderId) {
    Ui.Modal m = new Ui.Modal(app, "Ouvrir une sandbox existante", null);
    JTextField filter = Ui.input("", "Filtrer par nom ou dossier…");
    m.field(null, filter);
    JPanel list = new JPanel();
    list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
    list.setOpaque(false);
    list.add(Ui.label("Recherche des sandboxes (sbx ls)…", 12, null));
    JPanel top = new JPanel(new BorderLayout());
    top.setOpaque(false);
    top.add(list, BorderLayout.NORTH);
    JScrollPane scroll = new JScrollPane(top);
    scroll.setBorder(null);
    scroll.setOpaque(false);
    scroll.getViewport().setOpaque(false);
    scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    scroll.setPreferredSize(new Dimension(440, 320));
    m.field(null, scroll);
    Ui.Btn nw = new Ui.Btn("Nouvelle sandbox…", Ui.Kind.NORMAL);
    nw.addActionListener(e -> {
      m.dispose();
      SwingUtilities.invokeLater(() -> newSandbox(app, folderId));
    });
    m.action(nw);
    Thread worker = new Thread(() -> {
      List<Existing> found = List.of();
      String error = null;
      try {
        found = list();
      } catch (IOException e) {
        error = e.getMessage();
      }
      List<Existing> result = found;
      String problem = error;
      SwingUtilities.invokeLater(() -> {
        // Les sandboxes déjà ouvertes dans Younesh passent en bas de la liste, avec un badge : un clic y ramène.
        List<Existing> sorted = new ArrayList<>(result);
        sorted.sort(java.util.Comparator.comparing(x -> isOpen(app, x)));
        Runnable draw = () -> {
          list.removeAll();
          String q = filter.getText().trim().toLowerCase(Locale.ROOT);
          List<Existing> shown = sorted.stream().filter(x -> (x.name() + " " + x.workspace()).toLowerCase(Locale.ROOT).contains(q)).toList();
          if (problem != null) list.add(Ui.label("<html><div style='width:380px'>" + esc(problem) + "</div></html>", 12, null));
          else if (result.isEmpty()) list.add(Ui.label("Aucune sandbox sur ce PC.", 12, null));
          else if (shown.isEmpty()) list.add(Ui.label("Aucune sandbox ne correspond.", 12, null));
          for (Existing x : shown) {
            boolean known = app.store.sessions.stream().anyMatch(s -> s.sbx != null && x.name().equals(s.sbx.projet));
            if (list.getComponentCount() > 0) list.add(Box.createVerticalStrut(4));
            list.add(existingRow(x, known, isOpen(app, x), () -> {
              m.dispose();
              SwingUtilities.invokeLater(() -> openExisting(app, x, folderId));
            }));
          }
          list.revalidate();
          list.repaint();
        };
        onChange(filter, draw);
        draw.run();
      });
    }, "younesh-sbx-ls");
    worker.setDaemon(true);
    worker.start();
    m.open(app);
  }

  private static boolean isOpen(Younesh app, Existing x) {
    return app.store.sessions.stream().anyMatch(s -> s.sbx != null && x.name().equals(s.sbx.projet) && app.running(s));
  }

  private static JComponent existingRow(Existing x, boolean known, boolean opened, Runnable open) {
    String sub = String.join(" · ", Stream.of(x.status(), x.workspace().isEmpty() ? null : Sidebar.shortPath(x.workspace()), known && !opened ? "déjà dans Younesh" : null)
      .filter(v -> v != null && !v.isEmpty()).toList());
    boolean[] hover = {false};
    JPanel r = new JPanel(new BorderLayout(10, 0)) {
      @Override
      protected void paintComponent(Graphics g0) {
        Theme t = Ui.t();
        Graphics2D g = Ui.smooth(g0);
        g.setColor(hover[0] ? t.hover : t.bg);
        g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
        g.setColor(hover[0] ? t.borderHover : t.border);
        g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
        g.dispose();
      }
    };
    r.setOpaque(false);
    r.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
    r.setAlignmentX(JComponent.LEFT_ALIGNMENT);
    r.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    JLabel icon = Ui.label("⧉", 13, Theme.SANDBOX);
    JPanel text = new JPanel();
    text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
    text.setOpaque(false);
    text.add(textLabel(x.name(), 13));
    if (!sub.isEmpty()) text.add(Ui.label(sub, 11, null));
    r.add(icon, BorderLayout.WEST);
    r.add(text, BorderLayout.CENTER);
    if (opened) r.add(Ui.label("Déjà ouverte", 11, Theme.SANDBOX), BorderLayout.EAST);
    r.setMaximumSize(new Dimension(Integer.MAX_VALUE, r.getPreferredSize().height));
    r.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseEntered(MouseEvent e) {
        hover[0] = true;
        r.repaint();
      }

      @Override
      public void mouseExited(MouseEvent e) {
        hover[0] = false;
        r.repaint();
      }

      @Override
      public void mouseClicked(MouseEvent e) {
        open.run();
      }
    });
    return r;
  }

  static void install(Younesh app, Store.Session s) {
    Ui.Modal m = new Ui.Modal(app, "Installer dans « " + s.sbx.projet + " »", "Préparer");
    Set<String> selected = new LinkedHashSet<>();
    m.field("À installer", installs(app, m, selected),
      "Les commandes se lancent seules dans une discussion d'installation dès que la sandbox tourne.");
    if (!m.open(app)) return;
    Config cfg = config(app.store.settings);
    List<Install> items = cfg.installs.stream().filter(it -> selected.contains(it.id)).toList();
    if (!items.isEmpty()) runWhenReady(app, s.sbx.projet, helper(app, "Installation · " + s.sbx.projet, s.cwd, s.folderId, installCommand(cfg, s.sbx.projet, items), false));
  }

  // ---------- Préparer le poste ----------

  static void setup(Younesh app) {
    Store.Settings st = app.store.settings;
    Config cfg = config(st);
    Ui.Modal m = new Ui.Modal(app, "Préparer le poste", null);
    m.width = 560;
    m.field(null, Ui.label("<html><div style='width:450px'>À faire une fois par PC. Chaque étape est lancée dans la discussion « Préparation du poste (sbx) ». "
      + "Les secrets sont demandés au lancement et ne sont jamais enregistrés.</div></html>", 12, null));
    JPanel list = new JPanel();
    list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
    list.setOpaque(false);
    if (cfg.setup.isEmpty()) list.add(Ui.label("Aucune étape configurée.", 12, null));
    for (Step step : cfg.setup) {
      if (list.getComponentCount() > 0) list.add(Box.createVerticalStrut(6));
      list.add(setupRow(app, step));
    }
    m.field(null, list);
    Ui.Btn config = new Ui.Btn("Configurer sbx", Ui.Kind.NORMAL);
    config.addActionListener(e -> configure(app));
    m.action(config);
    m.open(app);
  }

  private static JComponent setupRow(Younesh app, Step step) {
    Store.Settings st = app.store.settings;
    JPanel r = new JPanel(new BorderLayout(10, 0)) {
      @Override
      protected void paintComponent(Graphics g0) {
        Theme t = Ui.t();
        Graphics2D g = Ui.smooth(g0);
        g.setColor(t.bg);
        g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
        g.setColor(t.border);
        g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
        g.dispose();
      }
    };
    r.setOpaque(false);
    r.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
    r.setAlignmentX(JComponent.LEFT_ALIGNMENT);
    JCheckBox done = new JCheckBox("", Boolean.TRUE.equals(st.sbxDone.get(step.id)));
    done.setOpaque(false);
    done.setToolTipText("Fait");
    done.addActionListener(e -> {
      st.sbxDone.put(step.id, done.isSelected());
      app.save();
    });
    JPanel info = new JPanel();
    info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
    info.setOpaque(false);
    info.add(textLabel(step.label, 13));
    JLabel code = Ui.label(step.cmd, 11, null);
    code.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
    info.add(code);
    Ui.Btn run = new Ui.Btn("Lancer", Ui.Kind.NORMAL);
    run.addActionListener(e -> {
      String cmd = askSecrets(app, step.cmd);
      if (cmd == null) return;
      done.setSelected(true);
      st.sbxDone.put(step.id, true);
      helper(app, "Préparation du poste (sbx)", System.getProperty("user.home"), null, cmd, true);
      run.setText("Relancer");
    });
    JPanel side = new JPanel(new BorderLayout());
    side.setOpaque(false);
    side.add(run, BorderLayout.CENTER);
    r.add(done, BorderLayout.WEST);
    r.add(info, BorderLayout.CENTER);
    r.add(side, BorderLayout.EAST);
    r.setMaximumSize(new Dimension(Integer.MAX_VALUE, r.getPreferredSize().height));
    return r;
  }

  // Demande chaque {secret:NOM}, sans rien enregistrer. Null si on annule.
  static String askSecrets(Younesh app, String cmd) {
    Set<String> names = new LinkedHashSet<>();
    Matcher mm = SECRET.matcher(cmd);
    while (mm.find()) names.add(mm.group(1));
    if (names.isEmpty()) return cmd;
    Ui.Modal m = new Ui.Modal(app, "Secret requis", "Lancer");
    m.width = 420;
    Map<String, JPasswordField> fields = new LinkedHashMap<>();
    for (String n : names) {
      JPasswordField f = new JPasswordField();
      f.setFont(Ui.font(13, false));
      fields.put(n, f);
      m.field(n, f);
    }
    m.field(null, Ui.label("Utilisé pour cette commande uniquement, jamais enregistré par Younesh.", 11, null));
    m.validate = () -> fields.values().stream().allMatch(f -> f.getPassword().length > 0);
    if (!m.open(app)) return null;
    Matcher r = SECRET.matcher(cmd);
    StringBuilder out = new StringBuilder();
    while (r.find()) r.appendReplacement(out, Matcher.quoteReplacement(new String(fields.get(r.group(1)).getPassword())));
    r.appendTail(out);
    return out.toString();
  }

  // ---------- Configuration ----------

  static String problem(JsonElement el) {
    if (el == null || !el.isJsonObject()) return "Le fichier doit contenir un objet JSON.";
    JsonObject o = el.getAsJsonObject();
    JsonObject cli = o.get("cli") != null && o.get("cli").isJsonObject() ? o.getAsJsonObject("cli") : new JsonObject();
    for (String k : List.of("create", "open", "exec", "remove")) if (string(cli.get(k)).isEmpty()) return "Il manque la commande cli." + k + ".";
    if (!string(cli.get("exec")).contains("{script}")) return "cli.exec doit contenir {script}.";
    for (String[] lf : new String[][]{{"setup", "cmd"}, {"installs", "script"}}) {
      JsonElement items = o.get(lf[0]);
      if (items == null || !items.isJsonArray()) return "« " + lf[0] + " » doit être une liste.";
      for (JsonElement it : items.getAsJsonArray()) {
        JsonObject x = it.isJsonObject() ? it.getAsJsonObject() : new JsonObject();
        if (string(x.get("id")).isEmpty() || string(x.get("label")).isEmpty() || !(x.get(lf[1]) != null && x.get(lf[1]).isJsonPrimitive()))
          return "Chaque élément de « " + lf[0] + " » doit avoir id, label et " + lf[1] + ".";
      }
    }
    return null;
  }

  private static Config parse(String text, JLabel error) {
    try {
      JsonElement el = JsonParser.parseString(text);
      String p = problem(el);
      error.setText(p == null ? " " : p);
      return p == null ? GSON.fromJson(el, Config.class) : null;
    } catch (JsonParseException e) {
      error.setText("JSON invalide : " + e.getMessage());
      return null;
    }
  }

  static void configure(Younesh app) {
    Store.Settings st = app.store.settings;
    Ui.Modal m = new Ui.Modal(app, "Configurer sbx", "Enregistrer");
    m.width = 760;
    m.field(null, Ui.label("<html><div style='width:620px'><b>cli</b> : les commandes sbx (à changer seulement si sbx évolue). <b>setup</b> : la préparation du poste. "
      + "<b>installs</b> : ce qu'on peut installer dans une sandbox.<br>Variables : {projet}, {dossier}, {script}, {secret:NOM}.</div></html>", 12, null));
    JTextArea area = new JTextArea(GSON.toJson(config(st)));
    area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
    area.setTabSize(2);
    area.setBackground(Ui.t().bg);
    area.setForeground(Ui.t().text);
    area.setCaretColor(Ui.t().text);
    area.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
    area.setCaretPosition(0);
    JScrollPane sp = new JScrollPane(area);
    sp.setBorder(BorderFactory.createLineBorder(Ui.t().border));
    sp.setPreferredSize(new Dimension(700, 400));
    m.field(null, sp);
    JLabel error = Ui.label(" ", 12, Theme.DANGER);
    m.field(null, error);

    Ui.Btn imp = new Ui.Btn("Importer…", Ui.Kind.NORMAL);
    imp.addActionListener(e -> {
      JFileChooser fc = new JFileChooser();
      fc.setFileFilter(new FileNameExtensionFilter("JSON", "json"));
      if (fc.showOpenDialog(m) != JFileChooser.APPROVE_OPTION) return;
      try {
        String text = Files.readString(fc.getSelectedFile().toPath(), StandardCharsets.UTF_8);
        Config c = parse(text, error);
        area.setText(c != null ? GSON.toJson(c) : text);
        area.setCaretPosition(0);
      } catch (IOException ex) {
        error.setText("Lecture impossible : " + ex.getMessage());
      }
    });
    Ui.Btn exp = new Ui.Btn("Exporter…", Ui.Kind.NORMAL);
    exp.addActionListener(e -> {
      if (parse(area.getText(), error) == null) return;
      JFileChooser fc = new JFileChooser();
      fc.setSelectedFile(new java.io.File("younesh-sbx.json"));
      if (fc.showSaveDialog(m) != JFileChooser.APPROVE_OPTION) return;
      try {
        Files.writeString(fc.getSelectedFile().toPath(), area.getText(), StandardCharsets.UTF_8);
      } catch (IOException ex) {
        error.setText("Écriture impossible : " + ex.getMessage());
      }
    });
    Ui.Btn reset = new Ui.Btn("Par défaut", Ui.Kind.NORMAL);
    reset.addActionListener(e -> {
      area.setText(GSON.toJson(defaults()));
      area.setCaretPosition(0);
      parse(area.getText(), error);
    });
    m.action(imp);
    m.action(exp);
    m.action(reset);
    m.validate = () -> parse(area.getText(), error) != null;
    if (!m.open(app)) return;
    st.sbx = parse(area.getText(), error);
    app.save();
    app.toast("Configuration sbx enregistrée.", 3000);
  }

  // ---------- Petits outils ----------

  private static JLabel textLabel(String text, float size) {
    JLabel l = new JLabel(text) {
      @Override
      public Color getForeground() {
        return Ui.t().text;
      }
    };
    l.setFont(Ui.font(size, false));
    return l;
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
