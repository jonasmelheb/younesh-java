package com.jonasmelheb.younesh;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.model.TerminalLine;
import com.jediterm.terminal.model.TerminalTextBuffer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

// Le contenu des terminaux classiques et SSH, gardé d'une ouverture à l'autre (buffers.json de la version Electron).
final class Buffers {
  private static final int LINES = 1000;
  private static final Gson GSON = new Gson();

  private Buffers() {
  }

  private static Path file() {
    return Store.dir().resolve("buffers.json");
  }

  static Map<String, String> load() {
    try {
      Map<String, String> m = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), new TypeToken<Map<String, String>>() { }.getType());
      return m == null ? new HashMap<>() : new HashMap<>(m);
    } catch (IOException | RuntimeException e) {
      return new HashMap<>();
    }
  }

  static void save(Map<String, String> buffers) {
    try {
      Files.createDirectories(Store.dir());
      Files.writeString(file(), GSON.toJson(buffers), StandardCharsets.UTF_8);
    } catch (IOException ignored) {
    }
  }

  // Le texte et ses couleurs, en séquences que le terminal saura réafficher.
  static String serialize(TerminalTextBuffer buffer) {
    buffer.lock();
    try {
      int last = buffer.getScreenLinesCount() - 1;
      while (last >= 0 && buffer.getLine(last).getText().isBlank()) last--;
      int first = Math.max(-buffer.getHistoryLinesCount(), last + 1 - LINES);
      StringBuilder out = new StringBuilder();
      for (int i = first; i <= last; i++) {
        TerminalLine line = buffer.getLine(i);
        for (TerminalLine.TextEntry entry : line.getEntries()) {
          out.append(sgr(entry.getStyle())).append(entry.getText().toString().replace('\0', ' ')).append("\u001b[0m");
        }
        if (!line.isWrapped() && i < last) out.append("\r\n");
      }
      return out.toString();
    } finally {
      buffer.unlock();
    }
  }

  private static String sgr(TextStyle style) {
    StringBuilder codes = new StringBuilder();
    if (style.hasOption(TextStyle.Option.BOLD)) codes.append(";1");
    color(codes, style.getForeground(), 30, 90, 38);
    color(codes, style.getBackground(), 40, 100, 48);
    return codes.length() == 0 ? "" : "\u001b[" + codes.substring(1) + "m";
  }

  private static void color(StringBuilder codes, TerminalColor c, int base, int bright, int extended) {
    if (c == null) return;
    if (!c.isIndexed()) {
      com.jediterm.core.Color rgb = c.toColor();
      codes.append(';').append(extended).append(";2;").append(rgb.getRed()).append(';').append(rgb.getGreen()).append(';').append(rgb.getBlue());
    } else if (c.getColorIndex() < 8) {
      codes.append(';').append(base + c.getColorIndex());
    } else if (c.getColorIndex() < 16) {
      codes.append(';').append(bright + c.getColorIndex() - 8);
    } else {
      codes.append(';').append(extended).append(";5;").append(c.getColorIndex());
    }
  }
}
