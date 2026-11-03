package com.jonasmelheb.younesh;

import com.jediterm.core.Color;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.ColorPalette;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Set;
import java.util.function.Supplier;

// Un terminal ouvert : le widget JediTerm, son processus et son état dans la barre latérale.
final class Term {
  final Store.Session session;
  final JediTermWidget widget;
  final JPanel view;   // le widget avec une marge, posé dans les cartes
  Pty pty;
  volatile boolean running, exited;
  volatile long lastOutput, lastInput;
  volatile boolean awaiting;   // Claude a répondu depuis la dernière frappe
  boolean unread, attention;

  Term(Store.Session session, Supplier<Theme> theme, Supplier<Store.Settings> settings) {
    this.session = session;
    this.widget = new JediTermWidget(120, 30, new Settings(theme, settings));
    this.view = new JPanel(new BorderLayout());
    view.setBorder(BorderFactory.createEmptyBorder(6, 10, 0, 0));
    view.add(widget, BorderLayout.CENTER);
  }

  static final class Settings extends DefaultSettingsProvider {
    private final Supplier<Theme> theme;
    private final Supplier<Store.Settings> settings;

    Settings(Supplier<Theme> theme, Supplier<Store.Settings> settings) {
      this.theme = theme;
      this.settings = settings;
    }

    private static TerminalColor tc(java.awt.Color c) {
      return TerminalColor.rgb(c.getRed(), c.getGreen(), c.getBlue());
    }

    @Override
    public ColorPalette getTerminalColorPalette() {
      Theme t = theme.get();
      return new ColorPalette() {
        @Override
        protected Color getForegroundByColorIndex(int i) {
          return new Color(t.ansi[i].getRGB() & 0xffffff);
        }

        @Override
        protected Color getBackgroundByColorIndex(int i) {
          return new Color(t.ansi[i].getRGB() & 0xffffff);
        }
      };
    }

    @Override
    public TerminalColor getDefaultForeground() {
      return tc(theme.get().termFg);
    }

    @Override
    public TerminalColor getDefaultBackground() {
      return tc(theme.get().termBg);
    }

    @Override
    public TextStyle getSelectionColor() {
      return new TextStyle(tc(theme.get().termFg), tc(theme.get().selection));
    }

    @Override
    public TextStyle getFoundPatternColor() {
      return new TextStyle(tc(theme.get().onAccent), tc(theme.get().accent));
    }

    @Override
    public TextStyle getHyperlinkColor() {
      return new TextStyle(tc(theme.get().accent), null);
    }

    private static final Set<String> FONTS = Set.of(
      GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());

    @Override
    public Font getTerminalFont() {
      String wanted = settings.get().fontFamily;
      for (String f : new String[]{wanted, "Cascadia Mono", "Cascadia Code", "JetBrains Mono", "Consolas", "Menlo"}) {
        if (f != null && !f.isBlank() && FONTS.contains(f)) return new Font(f, Font.PLAIN, (int) getTerminalFontSize());
      }
      return new Font(Font.MONOSPACED, Font.PLAIN, (int) getTerminalFontSize());
    }

    @Override
    public float getTerminalFontSize() {
      return settings.get().fontSize;
    }

    @Override
    public int getBufferMaxLinesCount() {
      return settings.get().scrollback;
    }

    @Override
    public boolean useInverseSelectionColor() {
      return false;
    }

    @Override
    public boolean audibleBell() {
      return false;
    }

    // Shift+Entrée = nouvelle ligne dans Claude.
    @Override
    public boolean shiftEnterSendsEscCR() {
      return true;
    }
  }
}
