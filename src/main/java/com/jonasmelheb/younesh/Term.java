package com.jonasmelheb.younesh;

import com.jediterm.core.Color;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.ColorPalette;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.text.StyleContext;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

// Un terminal ouvert : le widget JediTerm, son processus et son état dans la barre latérale.
final class Term {
  final Store.Session session;
  final JediTermWidget widget;
  final JPanel view;   // le widget avec sa marge (8 12 4 4 comme .term) et la barre « Processus terminé »
  private final JPanel exitBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
  private final JLabel exitText = Ui.label("", 13, null);
  private Runnable onRestart = () -> { }, onClose = () -> { };
  Pty pty;
  volatile boolean running, exited;
  volatile long lastOutput, lastInput;
  volatile boolean awaiting;   // Claude a répondu depuis la dernière frappe
  volatile boolean inClaude;   // Claude Code tourne dans ce terminal : son titre commence par « ✳ »
  boolean unread, attention;

  private static final Pattern CLAUDE_TITLE = Pattern.compile("^[^\\p{L}\\p{N}\\s]\\s+.+$");

  Term(Store.Session session, Supplier<Theme> theme, Supplier<Store.Settings> settings) {
    this.session = session;
    // Maj+Entrée = retour à la ligne dans Claude, même lancé à la main dans un shell.
    // Ailleurs, Échap+Entrée effacerait la ligne de PowerShell : la touche reste normale.
    this.widget = new JediTermWidget(120, 30, new Settings(theme, settings, () -> "claude".equals(session.kind) || inClaude));
    widget.getTerminal().addApplicationTitleListener(t -> inClaude = CLAUDE_TITLE.matcher(t.trim()).matches());
    this.view = new JPanel(new BorderLayout());
    view.setBorder(BorderFactory.createEmptyBorder(8, 12, 4, 4));
    view.add(widget, BorderLayout.CENTER);

    exitBar.setOpaque(false);
    exitBar.setBorder(BorderFactory.createEmptyBorder(6, 0, 14, 0));
    JPanel pill = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0)) {
      @Override
      protected void paintComponent(Graphics g0) {
        Graphics2D g = Ui.smooth(g0);
        Theme t = Ui.t();
        g.setColor(t.panel2);
        g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
        g.setColor(t.border);
        g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
        g.dispose();
      }
    };
    pill.setOpaque(false);
    pill.setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 6));
    Ui.Btn restart = new Ui.Btn("Relancer", Ui.Kind.PRIMARY);
    restart.addActionListener(e -> onRestart.run());
    Ui.Btn close = new Ui.Btn("Fermer", Ui.Kind.NORMAL);
    close.addActionListener(e -> onClose.run());
    pill.add(exitText);
    pill.add(restart);
    pill.add(close);
    exitBar.add(pill);
  }

  void onRestart(Runnable r) {
    onRestart = r;
  }

  void onClose(Runnable r) {
    onClose = r;
  }

  void showExit(int code) {
    exitText.setText("Processus terminé" + (code != 0 ? " (code " + code + ")" : ""));
    view.add(exitBar, BorderLayout.SOUTH);
    view.revalidate();
    view.repaint();
  }

  void hideExit() {
    view.remove(exitBar);
    view.revalidate();
  }

  void applyTheme(Theme t) {
    widget.setBackground(t.termBg);
    view.setBackground(t.termBg);
    widget.getTerminalPanel().repaint();
  }

  static final class Settings extends DefaultSettingsProvider {
    private final Supplier<Theme> theme;
    private final Supplier<Store.Settings> settings;
    private final BooleanSupplier shiftEnter;

    Settings(Supplier<Theme> theme, Supplier<Store.Settings> settings, BooleanSupplier shiftEnter) {
      this.theme = theme;
      this.settings = settings;
      this.shiftEnter = shiftEnter;
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

    // Police avec le repli de Windows : les symboles de Claude (✻ ⎿ ●) s'affichent même absents de la police.
    @Override
    public Font getTerminalFont() {
      String wanted = settings.get().fontFamily;
      String family = Ui.firstFont(wanted == null ? "" : wanted, "Cascadia Mono", "Cascadia Code", "Consolas", "Menlo");
      if (family.equals(Font.SANS_SERIF)) family = Font.MONOSPACED;
      return StyleContext.getDefaultStyleContext().getFont(family, Font.PLAIN, (int) getTerminalFontSize());
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

    @Override
    public boolean shiftEnterSendsEscCR() {
      return shiftEnter.getAsBoolean();
    }
  }
}
