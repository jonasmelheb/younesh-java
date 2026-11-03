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
    Map<String, String> env = cleanEnv(System.getenv());
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
