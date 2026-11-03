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
    public String kind = "shell";   // shell ou claude
    public String claudeId;          // conversation Claude reprise au redémarrage
    public boolean claudeStarted;
    public String titleSeen;         // dernier nom lu dans le titre posé par Claude
  }

  public static final class Settings {
    public String theme = "sombre", fontFamily = "", defaultShell = "", defaultCwd = "";
    public int fontSize = 14, scrollback = 5000, sidebarWidth = 260;
    public boolean notify;   // notification Windows quand Claude attend : désactivée par défaut
  }

  public List<Folder> folders = new ArrayList<>();
  public List<Session> sessions = new ArrayList<>();
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
