package com.jonasmelheb.younesh;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Sur certains PC Windows, des git restent suspendus dès leur lancement quand leur parent disparaît.
// Celui de sbx garde alors .git/config.lock : le remote de la sandbox n'est pas posé et sbx attend, écran noir.
final class GitFix {
  private GitFix() {
  }

  static final Duration LOCK_AGE = Duration.ofSeconds(30);

  // Appelé toutes les 15 s, seulement pour les dépôts des sandbox ouvertes. Sans verrou, ou avec un verrou
  // récent (un git peut travailler normalement), la liste des processus n'est même pas lue.
  static int cleanLocks(List<String> dirs) {
    int killed = 0;
    for (String dir : dirs) killed += unlock(Path.of(dir, ".git", "config.lock"));
    return killed;
  }

  // Arrête le git qui garde ce verrou : orphelin, qui n'a presque pas tourné, et lancé au moment où le verrou
  // a été posé. Les autres git du PC, même orphelins, ne sont pas touchés.
  static int unlock(Path lock) {
    Instant locked;
    try {
      locked = Files.getLastModifiedTime(lock).toInstant();
    } catch (IOException e) {
      return 0;
    }
    if (locked.isAfter(Instant.now().minus(LOCK_AGE))) return 0;
    int killed = 0;
    for (ProcessHandle p : ProcessHandle.allProcesses().toList()) {
      ProcessHandle.Info info = p.info();
      boolean git = info.command().map(c -> c.toLowerCase().endsWith("\\git.exe") || c.equalsIgnoreCase("git.exe")).orElse(false);
      if (!git || p.parent().isPresent()) continue;
      Duration cpu = info.totalCpuDuration().orElse(null);
      Instant start = info.startInstant().orElse(null);
      if (holdsLock(cpu, start, locked) && p.destroyForcibly()) killed++;
    }
    return killed;
  }

  // Le git bloqué pose le verrou juste après son lancement, puis ne tourne plus.
  static boolean holdsLock(Duration cpu, Instant start, Instant locked) {
    if (cpu == null || start == null) return false;
    return cpu.compareTo(Duration.ofMillis(20)) < 0
      && !start.isBefore(locked.minusSeconds(30))
      && !start.isAfter(locked.plusSeconds(5));
  }

  // « Git remote sandbox-NOM is not ready … git://127.0.0.1:PORT/DEPOT » : on repose le remote nous-mêmes.
  // La commande affichée n'est jamais exécutée telle quelle : seuls le nom et le port, vérifiés, sont repris.
  private static final Pattern NOT_READY = Pattern.compile("Git remote (sandbox-[\\w.-]+) is not ready");
  private static final Pattern URL = Pattern.compile("git://127\\.0\\.0\\.1:(\\d{2,5})/([\\w.-]+)");

  static void watch(Younesh app, Term t, char[] buf, int off, int len) {
    Store.Session s = t.session;
    if (!Shells.WIN || s.sbx == null || s.cwd == null) return;
    String tail = t.gitTail + new String(buf, off, len);
    t.gitTail = tail.substring(Math.max(0, tail.length() - 2000));
    Matcher nr = NOT_READY.matcher(tail), url = URL.matcher(tail);
    if (!nr.find() || !url.find()) return;
    t.gitTail = "";
    String remote = nr.group(1), port = url.group(1), repo = url.group(2);
    Thread th = new Thread(() -> repair(app, s.cwd, remote, port, repo), "younesh-git-fix");
    th.setDaemon(true);
    th.start();
  }

  private static void repair(Younesh app, String dir, String remote, String port, String repo) {
    Path lock = Path.of(dir, ".git", "config.lock");
    for (int i = 0; i < 24 && Files.exists(lock); i++) {
      unlock(lock);
      sleep(5000);
    }
    if (Files.exists(lock)) {
      toast(app, "Le remote git de la sandbox n'a pas pu être posé : .git\\config.lock reste bloqué.");
      return;
    }
    boolean ok = git(dir, "config", "--replace-all", "remote." + remote + ".url", "git://127.0.0.1:" + port + "/" + repo)
      && git(dir, "config", "--replace-all", "remote." + remote + ".fetch", "+refs/heads/*:refs/remotes/" + remote + "/*")
      && git(dir, "config", "--add", "remote." + remote + ".fetch", "+refs/heads/*:refs/sandboxes/" + remote.substring("sandbox-".length()) + "/*");
    toast(app, ok ? "Remote git « " + remote + " » réparé." : "La réparation du remote git « " + remote + " » a échoué.");
  }

  private static boolean git(String dir, String... args) {
    Map<String, String> env = Pty.freshEnv();
    List<String> cmd = new ArrayList<>(List.of(Pty.which(env, "git"), "-C", dir));
    cmd.addAll(List.of(args));
    try {
      Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
      p.getOutputStream().close();
      if (!p.waitFor(30, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        return false;
      }
      return p.exitValue() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  private static void toast(Younesh app, String text) {
    SwingUtilities.invokeLater(() -> app.toast(text, 8000));
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
