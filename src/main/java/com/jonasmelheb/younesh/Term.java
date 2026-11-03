package com.jonasmelheb.younesh;

import com.jediterm.core.Color;
import com.jediterm.terminal.HyperlinkStyle;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TerminalDisplay;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.ColorPalette;
import com.jediterm.terminal.model.JediTerminal;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.model.hyperlinks.LinkInfo;
import com.jediterm.terminal.model.hyperlinks.LinkResult;
import com.jediterm.terminal.model.hyperlinks.LinkResultItem;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.TerminalActionPresentation;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.text.StyleContext;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
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
    this.widget = new JediTermWidget(120, 30, new Settings(theme, settings, () -> "claude".equals(session.kind) || inClaude)) {
      @Override
      protected StyleState createDefaultStyle() {
        return new ThemeStyle();
      }

      @Override
      protected JediTerminal createTerminal(TerminalDisplay display, TerminalTextBuffer buffer, StyleState style) {
        return new LinkTerminal(display, buffer, style);
      }
    };
    widget.getTerminal().addApplicationTitleListener(t -> inClaude = CLAUDE_TITLE.matcher(t.trim()).matches());
    widget.addHyperlinkFilter(Term::links);
    // Liens OSC 8 (texte cliquable qui cache l'adresse) : JediTerm ne les reconnaît qu'avec ce filtre.
    if (widget.getTerminal() instanceof JediTerminal jt) jt.setUrlHyperlinkFilter(Term::links);
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

  // Les adresses web (et les liens OSC 8 que Claude envoie) deviennent des liens : un clic les ouvre.
  private static final Pattern URL = Pattern.compile("(?:https?|file)://[^\\s<>\"'`«»]+");

  static LinkResult links(String line) {
    List<LinkResultItem> items = new ArrayList<>();
    Matcher m = URL.matcher(line);
    while (m.find()) {
      String url = m.group();
      // La ponctuation qui suit une adresse dans une phrase n'en fait pas partie.
      while (!url.isEmpty() && ".,;:!?)]}".indexOf(url.charAt(url.length() - 1)) >= 0
        && !(url.endsWith(")") && url.contains("("))) url = url.substring(0, url.length() - 1);
      String target = url;
      items.add(new LinkResultItem(m.start(), m.start() + url.length(), new LinkInfo(() -> open(target))));
    }
    return items.isEmpty() ? null : new LinkResult(items);
  }

  private static void open(String url) {
    try {
      Desktop.getDesktop().browse(new URI(url));
    } catch (Exception ignored) {
    }
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

  // Les liens OSC 8 (texte cliquable qui cache l'adresse) : JediTerm ne les souligne qu'au survol et garde leur
  // couleur après le lien. Ici, bleu souligné comme les autres liens, puis retour au style d'avant.
  private static final class LinkTerminal extends JediTerminal {
    private final StyleState style;
    private TextStyle beforeLink;

    LinkTerminal(TerminalDisplay display, TerminalTextBuffer buffer, StyleState style) {
      super(display, buffer, style);
      this.style = style;
    }

    @Override
    public void setLinkUriStarted(String uri) {
      TextStyle before = style.getCurrent();
      super.setLinkUriStarted(uri);
      if (style.getCurrent() instanceof HyperlinkStyle link) {
        beforeLink = before;
        Theme t = Ui.t();
        style.setCurrent(new HyperlinkStyle(TerminalColor.rgb(t.link.getRed(), t.link.getGreen(), t.link.getBlue()),
          before.getBackground(), link.getLinkInfo(), HyperlinkStyle.HighlightMode.ALWAYS));
      }
    }

    @Override
    public void setLinkUriFinished() {
      if (beforeLink != null && style.getCurrent() instanceof HyperlinkStyle) {
        style.setCurrent(beforeLink);
        beforeLink = null;
      } else {
        super.setLinkUriFinished();
      }
    }
  }

  // « Remettre les attributs par défaut » (ESC 8 sans position sauvegardée, RIS…) : JediTerm reprend son style
  // par défaut, noir sur blanc. La console de Windows en envoie au démarrage et tout passait sur fond blanc.
  // Ici, revenir au défaut veut dire : plus aucune couleur imposée, donc celles du thème.
  private static final class ThemeStyle extends StyleState {
    @Override
    public void reset() {
      setCurrent(TextStyle.EMPTY);
    }

    @Override
    public TerminalColor getDefaultBackground() {
      Theme t = Ui.t();
      return TerminalColor.rgb(t.termBg.getRed(), t.termBg.getGreen(), t.termBg.getBlue());
    }

    @Override
    public TerminalColor getDefaultForeground() {
      Theme t = Ui.t();
      return TerminalColor.rgb(t.termFg.getRed(), t.termFg.getGreen(), t.termFg.getBlue());
    }
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
    public TextStyle getDefaultStyle() {
      return new TextStyle(tc(theme.get().termFg), tc(theme.get().termBg));
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
      return new TextStyle(tc(theme.get().link), null);
    }

    // Toujours soulignés, pas seulement au survol.
    @Override
    public HyperlinkStyle.HighlightMode getHyperlinkHighlightingMode() {
      return HyperlinkStyle.HighlightMode.ALWAYS;
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

    // Copier / coller façon Windows Terminal, comme la version Electron : Ctrl+V colle, Ctrl+C copie s'il y a
    // une sélection (sinon c'est l'interruption habituelle). Les variantes avec Maj restent.
    @Override
    public TerminalActionPresentation getCopyActionPresentation() {
      if (!Shells.WIN) return super.getCopyActionPresentation();
      return new TerminalActionPresentation("Copier", List.of(
        KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK),
        KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)));
    }

    // Sans texte dans le presse-papiers (une image par exemple), Ctrl+V va au programme : Claude y colle l'image.
    @Override
    public TerminalActionPresentation getPasteActionPresentation() {
      if (!Shells.WIN) return super.getPasteActionPresentation();
      return new TerminalActionPresentation("Coller", List.of(
        KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK),
        KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
        KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.SHIFT_DOWN_MASK)));
    }

    @Override
    public boolean shiftEnterSendsEscCR() {
      return shiftEnter.getAsBoolean();
    }
  }
}
