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
    Map<String, String> env = new HashMap<>(System.getenv());
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
    return new Pty(p, name, listener);
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
