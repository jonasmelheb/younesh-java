package com.jonasmelheb.younesh;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.ProcessTtyConnector;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Relie un pseudo-terminal (ConPTY sous Windows) à JediTerm, et signale chaque sortie.
public final class Pty extends ProcessTtyConnector {
  public interface Listener {
    void onOutput(char[] buf, int offset, int length);
  }

  private final PtyProcess process;
  private final String name;
  private final Listener listener;

  private Pty(PtyProcess process, String name, Listener listener) {
    super(process, StandardCharsets.UTF_8, null);
    this.process = process;
    this.name = name;
    this.listener = listener;
  }

  public static Pty start(List<String> command, String cwd, String name, Listener listener) throws IOException {
    Map<String, String> env = freshEnv();
    env.put("TERM", "xterm-256color");
    env.put("COLORTERM", "truecolor");
    env.put("TERM_PROGRAM", "Younesh");
    PtyProcess p = new PtyProcessBuilder(command.toArray(String[]::new))
      .setDirectory(cwd)
      .setEnvironment(env)
      .setInitialColumns(120)
      .setInitialRows(30)
      .setUseWinConPty(true)
      .start();
    return new Pty(p, name, log(name, listener));
  }

  // Variables qu'une session Claude Code pose pour ses propres commandes. Si Younesh a été lancé depuis Claude,
  // les terminaux en héritent et le Claude qu'on y lance se croit « enfant » : il n'enregistre plus la
  // conversation (plus de reprise) et peut couper les couleurs. Les réglages de l'utilisateur (CLAUDE_CODE_GIT_BASH_PATH…) restent.
  private static final List<String> CLAUDE_MARKERS = List.of(
    "CLAUDECODE", "CLAUDE_PID", "CLAUDE_EFFORT", "CLAUDE_CODE_CHILD_SESSION", "CLAUDE_CODE_SESSION_ID",
    "CLAUDE_CODE_ENTRYPOINT", "CLAUDE_CODE_EXECPATH", "CLAUDE_CODE_SSE_PORT", "CLAUDE_CODE_SESSION_ATTENDED",
    "CLAUDE_CODE_MESSAGING_SOCKET", "CLAUDE_CODE_MESSAGING_TOKEN", "CLAUDE_CODE_BRIDGE_SESSION_ID");

  static Map<String, String> cleanEnv(Map<String, String> parent) {
    Map<String, String> env = new HashMap<>(parent);
    boolean fromClaude = env.containsKey("CLAUDECODE") || env.containsKey("CLAUDE_CODE_CHILD_SESSION");
    CLAUDE_MARKERS.forEach(env::remove);
    if (fromClaude) {
      // Posées par l'outil de commandes de Claude, pas par l'utilisateur : un vrai terminal a des couleurs.
      env.remove("NO_COLOR");
      env.remove("FORCE_COLOR");
    }
    return env;
  }

  // Environnement relu dans le registre (freshEnv de main.js), comme Windows Terminal pour chaque onglet :
  // un programme installé après l'ouverture de Younesh (claude, sbx…) est trouvé. PowerShell plutôt que
  // reg.exe : il rend les accents des chemins sans dépendre de la page de code de la console.
  private static final String ENV_SCRIPT = """
    [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false
    $keys = 'HKLM:\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment', 'HKCU:\\Volatile Environment', 'HKCU:\\Environment'
    $out = for ($i = 0; $i -lt 3; $i++) {
      $key = Get-Item -LiteralPath $keys[$i] -ErrorAction SilentlyContinue
      if ($key) { foreach ($n in $key.GetValueNames()) { [pscustomobject]@{ s = $i; n = $n; v = [string]$key.GetValue($n, '', 'DoNotExpandEnvironmentNames') } } }
    }
    ConvertTo-Json -Compress -InputObject @($out)""";
  private static Map<String, String> cachedEnv;
  private static long cachedAt;
  private static final java.util.concurrent.atomic.AtomicBoolean refreshing = new java.util.concurrent.atomic.AtomicBoolean();

  // Le premier appel attend la lecture ; ensuite on sert la dernière lecture et on la renouvelle en arrière-plan,
  // pour ne pas figer la fenêtre pendant que PowerShell démarre.
  static Map<String, String> freshEnv() {
    Map<String, String> env;
    synchronized (Pty.class) {
      env = cachedEnv;
    }
    if (env == null) return new HashMap<>(readEnv());
    if (System.currentTimeMillis() - cachedAt > 10000) refreshSoon();
    return new HashMap<>(env);
  }

  static void refreshSoon() {
    if (!refreshing.compareAndSet(false, true)) return;
    Thread t = new Thread(() -> {
      try {
        readEnv();
      } finally {
        refreshing.set(false);
      }
    }, "younesh-env");
    t.setDaemon(true);
    t.start();
  }

  private static Map<String, String> readEnv() {
    Map<String, String> env = new HashMap<>(System.getenv());
    if (Shells.WIN) {
      try {
        String encoded = java.util.Base64.getEncoder().encodeToString(ENV_SCRIPT.getBytes(StandardCharsets.UTF_16LE));
        Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded).redirectErrorStream(true).start();
        p.getOutputStream().close();
        java.util.concurrent.CompletableFuture<String> out = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
          try {
            return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
          } catch (IOException e) {
            return "";
          }
        });
        if (!p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly();
        else if (p.exitValue() == 0) merge(env, com.google.gson.JsonParser.parseString(out.join().replace("\uFEFF", "").trim()).getAsJsonArray());
      } catch (IOException | InterruptedException | RuntimeException ignored) {
      }
    }
    Map<String, String> clean = cleanEnv(env);
    synchronized (Pty.class) {
      cachedEnv = clean;
      cachedAt = System.currentTimeMillis();
    }
    return clean;
  }

  // Même ordre que Windows : système, session, puis utilisateur ; le Path de l'utilisateur s'ajoute à celui du système.
  // Les noms de variables ne tiennent pas compte de la casse sous Windows.
  static void merge(Map<String, String> env, com.google.gson.JsonArray entries) {
    String systemPath = "";
    for (com.google.gson.JsonElement el : entries) {
      com.google.gson.JsonObject o = el.getAsJsonObject();
      int scope = o.get("s").getAsInt();
      String name = o.get("n").getAsString(), value = expand(env, o.get("v").getAsString());
      if (!name.equalsIgnoreCase("path")) set(env, name, value);
      else if (scope == 0) set(env, "Path", systemPath = value);
      else if (scope == 2) set(env, "Path", systemPath.isEmpty() ? value : systemPath + ";" + value);
    }
  }

  static String get(Map<String, String> env, String name) {
    for (Map.Entry<String, String> e : env.entrySet()) if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
    return null;
  }

  private static void set(Map<String, String> env, String name, String value) {
    env.keySet().removeIf(k -> k.equalsIgnoreCase(name));
    env.put(name, value);
  }

  private static final java.util.regex.Pattern PERCENT = java.util.regex.Pattern.compile("%([^%]+)%");

  private static String expand(Map<String, String> env, String v) {
    java.util.regex.Matcher m = PERCENT.matcher(v);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String val = get(env, m.group(1));
      m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(val != null ? val : m.group()));
    }
    m.appendTail(out);
    return out.toString();
  }

  // Chemin complet d'un programme d'après le Path donné : Windows cherche sinon dans celui de Younesh, peut-être ancien.
  static String which(Map<String, String> env, String name) {
    String path = get(env, "Path");
    if (path != null) for (String dir : path.split(Shells.WIN ? ";" : ":")) {
      java.nio.file.Path exe = java.nio.file.Path.of(dir.trim().replace("\"", ""), Shells.WIN ? name + ".exe" : name);
      try {
        if (!dir.isBlank() && java.nio.file.Files.isRegularFile(exe)) return exe.toString();
      } catch (RuntimeException ignored) {
      }
    }
    return name;
  }

  // YOUNESH_PTY_LOG=1 : copie brute de ce que le terminal reçoit (256 Ko max), pour comprendre un affichage bizarre.
  private static Listener log(String name, Listener listener) {
    String env = System.getenv("YOUNESH_PTY_LOG");
    if (env == null || env.isBlank()) return listener;
    java.nio.file.Path file = Store.dir().resolve("pty-" + name.replaceAll("[^\\w.-]+", "_") + ".log");
    long[] written = {0};
    return (buf, off, len) -> {
      if (written[0] < 256 * 1024) {
        try {
          java.nio.file.Files.createDirectories(file.getParent());
          java.nio.file.Files.writeString(file, new String(buf, off, len), StandardCharsets.UTF_8,
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
          written[0] += len;
        } catch (IOException ignored) {
        }
      }
      listener.onOutput(buf, off, len);
    };
  }

  @Override
  public int read(char[] buf, int offset, int length) throws IOException {
    int n = super.read(buf, offset, length);
    if (n > 0 && listener != null) listener.onOutput(buf, offset, n);
    return n;
  }

  @Override
  public void resize(TermSize size) {
    if (process.isAlive()) process.setWinSize(new WinSize(size.getColumns(), size.getRows()));
  }

  @Override
  public String getName() {
    return name;
  }

  public PtyProcess process() {
    return process;
  }
}
