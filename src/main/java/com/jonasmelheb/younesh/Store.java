package com.jonasmelheb.younesh;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Tout ce qui est sauvegardé : dossiers, discussions, réglages et taille de la fenêtre.
public final class Store {
  public static final class Folder {
    public String id = uid(), name, color;
    public boolean collapsed;
  }

  public static final class Session {
    public String id = uid(), name, folderId, cwd, shell, color, command, claudeArgs;
    public String kind = "shell";   // shell, claude, sandbox ou ssh
    public String claudeId;          // conversation Claude reprise au redémarrage
    public boolean claudeStarted;
    public String titleSeen;         // dernier nom lu dans le titre posé par Claude
    public SbxRef sbx;               // sandbox sbx ouverte par cette discussion
    public String helper;            // discussion d'appoint sbx (préparation, installation), réutilisée par son nom
    public Prefill prefill;          // commande à taper au prochain démarrage
    public SshRef ssh;               // serveur d'une discussion SSH
  }

  // Une connexion SSH : les champs vides sont laissés à ssh (~/.ssh/config, port 22, clés par défaut).
  public static final class SshRef {
    public String host = "", user = "", port = "", identity = "", jump = "", options = "";
  }

  // Un tunnel SSH : type L (local), R (distant) ou D (proxy SOCKS), par la connexion sessionId.
  public static final class Tunnel {
    public String id = uid(), name = "", sessionId, type = "L", port = "", host = "localhost", hostPort = "";
    public boolean auto;   // ouvert au démarrage de Younesh
  }

  public static final class SbxRef {
    public String projet;
    public boolean created;

    SbxRef(String projet, boolean created) {
      this.projet = projet;
      this.created = created;
    }
  }

  public static final class Prefill {
    public String text;
    public boolean enter;

    Prefill(String text, boolean enter) {
      this.text = text;
      this.enter = enter;
    }
  }

  public static final class Settings {
    public String theme = "sombre", fontFamily = "", defaultShell = "", defaultCwd = "";
    public int fontSize = 14, scrollback = 5000, sidebarWidth = 260;
    public boolean notify;   // notification Windows quand Claude attend : désactivée par défaut
    public boolean ssh;      // connexions SSH et tunnels : désactivés par défaut
    public Sbx.Config sbx;   // configuration sbx importée ou modifiée (null : celle par défaut)
    public List<String> sbxLastInstalls = new ArrayList<>();
    public java.util.Map<String, Boolean> sbxDone = new java.util.HashMap<>();
  }

  public List<Folder> folders = new ArrayList<>();
  public List<Session> sessions = new ArrayList<>();
  public List<Tunnel> tunnels = new ArrayList<>();
  public Settings settings = new Settings();
  public String activeId;
  public int[] bounds;

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  static String uid() {
    return UUID.randomUUID().toString();
  }

  // %APPDATA%\younesh-java sous Windows, ~/.younesh-java ailleurs.
  static Path dir() {
    String appData = System.getenv("APPDATA");
    return appData != null ? Path.of(appData, "younesh-java") : Path.of(System.getProperty("user.home"), ".younesh-java");
  }

  static Path file() {
    return dir().resolve("younesh.json");
  }

  public static Store load() {
    try {
      Store s = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), Store.class);
      if (s != null) {
        if (s.settings == null) s.settings = new Settings();
        if (s.folders == null) s.folders = new ArrayList<>();
        if (s.sessions == null) s.sessions = new ArrayList<>();
        if (s.tunnels == null) s.tunnels = new ArrayList<>();
        if (s.settings.sbxLastInstalls == null) s.settings.sbxLastInstalls = new ArrayList<>();
        if (s.settings.sbxDone == null) s.settings.sbxDone = new java.util.HashMap<>();
        return s;
      }
    } catch (IOException | RuntimeException ignored) {
    }
    return seed();
  }

  // Premier lancement : un dossier d'exemple pour voir tout de suite le rendu.
  private static Store seed() {
    Store s = new Store();
    Folder f = new Folder();
    f.name = "Projets";
    s.folders.add(f);
    Session t = new Session();
    t.name = "Terminal";
    t.folderId = f.id;
    s.sessions.add(t);
    s.activeId = t.id;
    return s;
  }

  public void save() {
    try {
      Files.createDirectories(dir());
      Path tmp = dir().resolve("younesh.json.tmp");
      Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
      Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException ignored) {
    }
  }

  public Folder folder(String id) {
    return folders.stream().filter(f -> f.id.equals(id)).findFirst().orElse(null);
  }

  public Session session(String id) {
    return sessions.stream().filter(s -> s.id.equals(id)).findFirst().orElse(null);
  }
}
