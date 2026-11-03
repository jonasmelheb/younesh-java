package com.jonasmelheb.younesh;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;

// Mêmes thèmes que la version Electron (renderer/themes.js) : couleurs de l'interface et palette du terminal.
public final class Theme {
  public final String id, name;
  public final boolean dark;
  public final Color bg, panel, panel2, border, borderStrong, borderHover, hover, hover2, active;
  public final Color text, muted, muted2, accent, accentSoft, onAccent, scroll;
  public final Color termBg, termFg, selection;
  public final Color[] ansi;

  public static final Color DANGER = c("#ff6b6b"), OK = c("#4ade80"), ORANGE = c("#ff9f43"), CLAUDE = c("#e8a87c"), SANDBOX = c("#2dd4bf");

  // ui : bg, panel, panel-2, border, border-strong, border-hover, hover, hover-2, active, text, muted, muted-2, accent, accent-soft, on-accent, scroll
  private Theme(String id, String name, boolean dark, String[] ui, String[] term, String[] ansi) {
    this.id = id;
    this.name = name;
    this.dark = dark;
    bg = c(ui[0]); panel = c(ui[1]); panel2 = c(ui[2]); border = c(ui[3]); borderStrong = c(ui[4]); borderHover = c(ui[5]);
    hover = c(ui[6]); hover2 = c(ui[7]); active = c(ui[8]); text = c(ui[9]); muted = c(ui[10]); muted2 = c(ui[11]);
    accent = c(ui[12]); accentSoft = c(ui[13]); onAccent = c(ui[14]); scroll = c(ui[15]);
    termBg = c(term[0]); termFg = c(term[1]); selection = c(term[2]);
    this.ansi = new Color[16];
    for (int i = 0; i < 16; i++) this.ansi[i] = c(ansi[i]);
  }

  static Color c(String hex) {
    return new Color(Integer.parseInt(hex.substring(1), 16));
  }

  static String hex(Color c) {
    return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
  }

  // Équivalent de color-mix(in srgb, a t, b).
  static Color mix(Color a, Color b, float t) {
    return new Color(
      Math.round(a.getRed() * t + b.getRed() * (1 - t)),
      Math.round(a.getGreen() * t + b.getGreen() * (1 - t)),
      Math.round(a.getBlue() * t + b.getBlue() * (1 - t)));
  }

  static Color alpha(Color c, float a) {
    return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(a * 255));
  }

  private static final String[] ANSI_DARK = {
    "#1c1f27", "#ff6b6b", "#4ade80", "#feca57", "#7c9cff", "#c084fc", "#2dd4bf", "#d4d7e0",
    "#5b6070", "#ff8787", "#86efac", "#fde68a", "#a5b8ff", "#d8b4fe", "#5eead4", "#ffffff",
  };

  private static String[] ansi(String... overrides) {
    String[] a = ANSI_DARK.clone();
    for (int i = 0; i < overrides.length; i += 2) a[Integer.parseInt(overrides[i])] = overrides[i + 1];
    return a;
  }

  public static final Map<String, Theme> ALL = new LinkedHashMap<>();

  static {
    add(new Theme("sombre", "Sombre", true,
      new String[]{"#0e0f13", "#15171d", "#1c1f27", "#262a34", "#333846", "#3a3f4c", "#222631", "#2b303d", "#252a36", "#e6e8ee", "#8b90a0", "#5b6070", "#7c9cff", "#a5b8ff", "#0b1020", "#2a2e39"},
      new String[]{"#0e0f13", "#e6e8ee", "#3a4a7a"}, ANSI_DARK));
    add(new Theme("minuit", "Minuit", true,
      new String[]{"#0b1020", "#10172b", "#162036", "#1f2a44", "#2a3756", "#33426a", "#1a2540", "#22304f", "#1f2d4d", "#e3e9f7", "#8792b0", "#56607e", "#6ea8ff", "#9cc3ff", "#06101f", "#22304f"},
      new String[]{"#0b1020", "#e3e9f7", "#2a3f6e"}, ansi("0", "#162036", "4", "#6ea8ff", "12", "#9cc3ff")));
    add(new Theme("graphite", "Graphite", true,
      new String[]{"#18181b", "#1f1f23", "#27272b", "#303036", "#3c3c43", "#46464e", "#2c2c31", "#35353b", "#323238", "#ececef", "#9a9aa3", "#66666e", "#e8a87c", "#f2c4a3", "#1b120b", "#35353b"},
      new String[]{"#18181b", "#ececef", "#4a3a30"}, ansi("0", "#27272b")));
    add(new Theme("clair", "Clair", false,
      new String[]{"#fbfbfc", "#f1f2f5", "#ffffff", "#dfe1e7", "#cfd2da", "#bfc3cd", "#e7e9ee", "#dfe2e9", "#dde3f5", "#1d2130", "#646a7d", "#9aa0b0", "#3d63dd", "#3d63dd", "#ffffff", "#cfd2da"},
      new String[]{"#fbfbfc", "#1d2130", "#c9d6fb"},
      new String[]{"#1d2130", "#c4314b", "#1f8a4c", "#9a6700", "#3d63dd", "#8e44ad", "#0f7f8a", "#c9ccd6",
        "#646a7d", "#d9435f", "#2a9d5c", "#b07d00", "#5276e8", "#a35bc2", "#17909c", "#ffffff"}));
  }

  private static void add(Theme t) {
    ALL.put(t.id, t);
  }

  public static Theme of(String id) {
    return ALL.getOrDefault(id, ALL.get("sombre"));
  }

  // Couleurs proposées pour les dossiers et les discussions (COLORS dans app.js).
  public static final String[] SWATCHES = {
    "#ff6b6b", "#ff9f43", "#feca57", "#4ade80", "#2dd4bf", "#38bdf8", "#7c9cff", "#a78bfa", "#f472b6", "#e8a87c", "#94a3b8",
  };
}
