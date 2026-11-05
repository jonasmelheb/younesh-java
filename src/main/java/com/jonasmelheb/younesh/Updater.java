package com.jonasmelheb.younesh;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

// Mises à jour depuis les versions GitHub : le nouveau .jar est téléchargé à côté de l'ancien, après contrôle
// de l'empreinte publiée par GitHub, et le remplace à la fermeture. Si c'est impossible, on signale juste la sortie.
final class Updater {
  private static final String LATEST = "https://api.github.com/repos/jonasmelheb/younesh-java/releases/latest";
  private static final String PAGE = "https://github.com/jonasmelheb/younesh-java/releases/latest";
  private static final int EVERY_MS = 6 * 3600 * 1000;

  private static volatile Path pending;
  private static volatile String pendingVersion;
  private static volatile boolean relaunch;
  private static Path jar;

  private Updater() {
  }

  static void start(Younesh app) {
    String current = Younesh.class.getPackage().getImplementationVersion();
    jar = runningJar();
    if (current == null || jar == null) return;
    Runtime.getRuntime().addShutdownHook(new Thread(Updater::apply, "younesh-update-apply"));
    Runnable check = () -> {
      if (!app.store.settings.autoUpdate) return;
      Thread t = new Thread(() -> check(app, current), "younesh-update");
      t.setDaemon(true);
      t.start();
    };
    check.run();
    new Timer(EVERY_MS, e -> check.run()).start();
  }

  private static Path runningJar() {
    try {
      Path p = Path.of(Updater.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      return Files.isRegularFile(p) && p.toString().endsWith(".jar") ? p : null;
    } catch (URISyntaxException | RuntimeException e) {
      return null;
    }
  }

  private static void check(Younesh app, String current) {
    HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
    String tag;
    JsonObject release;
    try {
      String body = http.send(HttpRequest.newBuilder(URI.create(LATEST)).header("User-Agent", "Younesh").build(), HttpResponse.BodyHandlers.ofString()).body();
      release = JsonParser.parseString(body).getAsJsonObject();
      tag = release.get("tag_name").getAsString().replaceFirst("^v", "");
    } catch (IOException | RuntimeException e) {
      return;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    }
    if (!newer(tag, current) || tag.equals(pendingVersion)) return;
    if (download(http, release, tag)) {
      SwingUtilities.invokeLater(() -> app.toast("Younesh " + tag + " est téléchargé : il s'installera à la fermeture.", 30000, "Redémarrer maintenant", () -> {
        relaunch = true;
        app.quit();
      }));
    } else {
      SwingUtilities.invokeLater(() -> app.toast("Younesh " + tag + " est sorti.", 30000, "Télécharger", Updater::openPage));
    }
  }

  private static boolean download(HttpClient http, JsonObject release, String tag) {
    Path file = jar.resolveSibling(jar.getFileName() + ".download");
    try {
      String name = "Younesh-" + tag + ".jar";
      for (JsonElement el : release.getAsJsonArray("assets")) {
        JsonObject asset = el.getAsJsonObject();
        if (!name.equals(asset.get("name").getAsString())) continue;
        String digest = asset.get("digest").getAsString();
        if (!digest.startsWith("sha256:")) return false;
        byte[] data = http.send(HttpRequest.newBuilder(URI.create(asset.get("browser_download_url").getAsString())).header("User-Agent", "Younesh").build(), HttpResponse.BodyHandlers.ofByteArray()).body();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        if (!hash.equals(digest.substring(7))) return false;
        Files.write(file, data);
        pending = file;
        pendingVersion = tag;
        return true;
      }
      return false;
    } catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  // Sous Windows le .jar est verrouillé tant que Java tourne : un petit script attend la fermeture pour le remplacer.
  private static void apply() {
    if (pending == null) return;
    String java = ProcessHandle.current().info().command().orElse("java");
    try {
      if (System.getProperty("os.name").startsWith("Windows")) {
        Path script = Files.createTempFile("younesh-update-", ".cmd");
        String run = relaunch ? "start \"\" " + quote(java.replaceFirst("java\\.exe$", "javaw.exe")) + " -jar " + quote(jar.toString()) : "";
        Files.writeString(script, String.join("\r\n", List.of(
          "@echo off",
          "chcp 65001 >nul",
          "set /a tries=0",
          ":retry",
          "move /y " + quote(pending.toString()) + " " + quote(jar.toString()) + " >nul 2>&1 && goto done",
          "set /a tries+=1",
          "if %tries% geq 60 goto end",
          "ping -n 2 127.0.0.1 >nul",
          "goto retry",
          ":done",
          run,
          ":end",
          "(goto) 2>nul & del \"%~f0\"")), StandardCharsets.UTF_8);
        new ProcessBuilder("cmd.exe", "/d", "/c", script.toString()).start();
      } else {
        Files.move(pending, jar, StandardCopyOption.REPLACE_EXISTING);
        if (relaunch) new ProcessBuilder(java, "-jar", jar.toString()).start();
      }
    } catch (IOException | RuntimeException ignored) {
    }
  }

  private static String quote(String path) {
    return "\"" + path.replace("%", "%%") + "\"";
  }

  private static void openPage() {
    try {
      Desktop.getDesktop().browse(URI.create(PAGE));
    } catch (IOException | RuntimeException ignored) {
    }
  }

  static boolean newer(String a, String b) {
    String[] pa = a.split("\\."), pb = b.split("\\.");
    for (int i = 0; i < 3; i++) {
      int x = part(pa, i), y = part(pb, i);
      if (x != y) return x > y;
    }
    return false;
  }

  private static int part(String[] parts, int i) {
    try {
      return i < parts.length ? Integer.parseInt(parts[i]) : 0;
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
