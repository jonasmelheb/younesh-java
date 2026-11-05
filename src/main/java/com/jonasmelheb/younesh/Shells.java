package com.jonasmelheb.younesh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

// Shells installés sur le PC, et la ligne de commande pour lancer une discussion.
public final class Shells {
  public static final boolean WIN = System.getProperty("os.name").toLowerCase().contains("win");

  public record Shell(String id, String name, String path, List<String> args, String family) {
    @Override
    public String toString() {
      return name;
    }
  }

  private static List<Shell> cache;

  public static synchronized List<Shell> detect() {
    if (cache != null) return cache;
    List<Shell> shells = new ArrayList<>();
    if (WIN) {
      String sys = env("SystemRoot", "C:\\Windows");
      String pf = env("ProgramFiles", "C:\\Program Files");
      first(Path.of(pf, "PowerShell", "7", "pwsh.exe"), Path.of(pf, "PowerShell", "7-preview", "pwsh.exe"))
        .ifPresent(p -> shells.add(new Shell("pwsh", "PowerShell 7", p, List.of("-NoLogo"), "powershell")));
      shells.add(new Shell("powershell", "Windows PowerShell", Path.of(sys, "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString(), List.of("-NoLogo"), "powershell"));
      shells.add(new Shell("cmd", "Invite de commandes", Path.of(sys, "System32", "cmd.exe").toString(), List.of(), "cmd"));
      first(Path.of(pf, "Git", "bin", "bash.exe"), Path.of(env("LOCALAPPDATA", ""), "Programs", "Git", "bin", "bash.exe"))
        .ifPresent(p -> shells.add(new Shell("gitbash", "Git Bash", p, List.of("--login", "-i"), "posix")));
      Path wsl = Path.of(sys, "System32", "wsl.exe");
      if (Files.exists(wsl)) shells.add(new Shell("wsl", "WSL", wsl.toString(), List.of(), "posix"));
    } else {
      Set<String> seen = new LinkedHashSet<>();
      for (String p : new String[]{System.getenv("SHELL"), "/bin/zsh", "/bin/bash", "/opt/homebrew/bin/fish"}) {
        if (p != null && Files.exists(Path.of(p)) && seen.add(p)) {
          String name = Path.of(p).getFileName().toString();
          shells.add(new Shell(name, name, p, List.of("-l"), "posix"));
        }
      }
    }
    return cache = shells;
  }

  public static Shell find(String id) {
    List<Shell> all = detect();
    return all.stream().filter(s -> s.id().equals(id)).findFirst().orElse(all.isEmpty() ? null : all.get(0));
  }

  private static String env(String name, String fallback) {
    String v = System.getenv(name);
    return v == null || v.isEmpty() ? fallback : v;
  }

  private static java.util.Optional<String> first(Path... paths) {
    return Stream.of(paths).filter(Files::exists).map(Path::toString).findFirst();
  }

  // Commande lancée à l'ouverture : claude (repris si déjà démarré) ou la commande de démarrage.
  public static String startupCommand(Store.Session s, Shell shell) {
    if ("claude".equals(s.kind)) {
      String extra = s.claudeArgs == null || s.claudeArgs.isBlank() ? "" : " " + s.claudeArgs.trim();
      String fresh = "claude --session-id " + s.claudeId + extra;
      if (!s.claudeStarted) return fresh;
      String resume = "claude --resume " + s.claudeId + extra;
      return "powershell".equals(shell.family()) ? resume + "; if (-not $?) { " + fresh + " }" : resume + " || " + fresh;
    }
    return s.command == null || s.command.isBlank() ? null : s.command.trim();
  }

  public static List<String> commandLine(Shell shell, String cmd) {
    List<String> line = new ArrayList<>();
    line.add(shell.path());
    if (cmd == null) {
      line.addAll(shell.args());
    } else if ("powershell".equals(shell.family())) {
      line.addAll(shell.args());
      line.addAll(List.of("-NoExit", "-Command", cmd));
    } else if ("cmd".equals(shell.family())) {
      line.addAll(List.of("/K", cmd));
    } else if ("wsl".equals(shell.id())) {
      line.addAll(List.of("-e", "bash", "-lic", cmd + "; exec bash -li"));
    } else {
      line.addAll(shell.args());
      line.addAll(List.of("-i", "-c", cmd + "; exec '" + shell.path() + "' " + String.join(" ", shell.args())));
    }
    return line;
  }

  // À chaque invite, le shell annonce son dossier (OSC 9;9), ce que PowerShell, cmd et Git Bash ne font pas seuls.
  // Hors de Windows, zsh ne peut pas être configuré de l'extérieur : Younesh lit le dossier sur son processus.
  private static final String PS_ANNOUNCE_CWD = "$global:__younesh_prompt=$function:prompt;function global:prompt{try{if($PWD.Provider.Name -eq 'FileSystem'){[Console]::Write('{0}]9;9;{1}{0}\\' -f [char]27,$PWD.ProviderPath)}}catch{};& $global:__younesh_prompt}";

  public static List<String> announcingLine(Shell shell) {
    List<String> line = new ArrayList<>();
    line.add(shell.path());
    line.addAll(shell.args());
    if ("powershell".equals(shell.family())) line.addAll(List.of("-NoExit", "-Command", PS_ANNOUNCE_CWD));
    return line;
  }

  public static java.util.Map<String, String> announcingEnv(Shell shell) {
    if ("cmd".equals(shell.family())) return java.util.Map.of("PROMPT", "$e]9;9;$P$e\\$P$G");
    if ("gitbash".equals(shell.id())) return java.util.Map.of("PROMPT_COMMAND", "printf '\\033]9;9;%s\\033\\\\' \"$(pwd -W)\"");
    return java.util.Map.of();
  }

  private static final Pattern GITDIR = Pattern.compile("gitdir:\\s*(.+)");
  private static final Pattern REF = Pattern.compile("^ref: refs/heads/(.+)$");

  // Branche git lue directement dans .git/HEAD : pas de processus git lancé.
  public static String gitBranch(String dir) {
    if (dir == null || dir.isEmpty()) return null;
    Path d = Path.of(dir).toAbsolutePath();
    for (int i = 0; d != null && i < 40; i++, d = d.getParent()) {
      Path dotGit = d.resolve(".git");
      try {
        Path gitDir = dotGit;
        if (Files.isRegularFile(dotGit)) {
          Matcher m = GITDIR.matcher(Files.readString(dotGit));
          if (!m.find()) return null;
          gitDir = d.resolve(m.group(1).trim());
        } else if (!Files.isDirectory(dotGit)) {
          continue;
        }
        String head = Files.readString(gitDir.resolve("HEAD")).trim();
        Matcher ref = REF.matcher(head);
        return ref.find() ? ref.group(1) : head.substring(0, Math.min(7, head.length()));
      } catch (IOException | RuntimeException ignored) {
      }
    }
    return null;
  }
}
