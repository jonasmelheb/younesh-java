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
import com.jediterm.terminal.ui.TerminalPanel;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import com.jediterm.terminal.ui.settings.SettingsProvider;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.text.StyleContext;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
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
  private final Ui.Btn restart = new Ui.Btn("Relancer", Ui.Kind.PRIMARY);
  private Runnable onRestart = () -> { }, onClose = () -> { };
  Pty pty;
  volatile boolean running, exited;
  volatile long lastOutput, lastInput;
  volatile boolean awaiting;   // Claude a répondu depuis la dernière frappe
  volatile boolean inClaude;   // Claude Code tourne dans ce terminal : son titre commence par « ✳ »
  boolean unread, attention;
  volatile String outTail = "";   // fin de la sortie, pour repérer « API Usage Billing »
  volatile String gitTail = "";   // fin de la sortie, pour repérer « Git remote … is not ready » (GitFix)
  volatile boolean loginOffered;
  String cwdTail = "";   // fin de la dernière sortie : une annonce de dossier peut être coupée en deux

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
      protected TerminalPanel createTerminalPanel(SettingsProvider provider, StyleState style, TerminalTextBuffer buffer) {
        return new TerminalPanel(provider, buffer, style) {
          // Lissage en gris, comme Windows Terminal : pas de franges colorées sur le texte du terminal.
          @Override
          protected void setupAntialiasing(Graphics g) {
            if (g instanceof Graphics2D g2) g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
          }
        };
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

  // Une sandbox existante met parfois une minute à démarrer, écran noir compris : une pastille l'indique
  // jusqu'à ce que Claude s'affiche, que le processus s'arrête, ou au bout de 5 minutes.
  private final JLabel loadText = Ui.label("", 13, null);
  private JPanel loadBar;
  private Timer loadTimer;
  private volatile boolean loading;
  private String loadTail = "";
  private static final Pattern ESCAPES = Pattern.compile("\u001b\\[[0-9;?<>=]*[A-Za-z]|\u001b\\][^\u0007\u001b]*(?:\u0007|\u001b\\\\)");

  void showLoading(String what) {
    if (loadBar == null) {
      loadBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
      loadBar.setOpaque(false);
      loadBar.setBorder(BorderFactory.createEmptyBorder(4, 0, 8, 0));
      JPanel pill = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0)) {
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
      pill.setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 14));
      pill.add(loadText);
      loadBar.add(pill);
    }
    hideLoading();
    loading = true;
    loadTail = "";
    long start = System.currentTimeMillis();
    String[] dots = {"·  ", "·· ", "···"};
    Runnable tick = () -> {
      long s = (System.currentTimeMillis() - start) / 1000;
      if (s >= 300) hideLoading();
      else loadText.setText(what + " " + dots[(int) (s % 3)] + "  " + s + " s");
    };
    tick.run();
    loadTimer = new Timer(1000, e -> tick.run());
    loadTimer.start();
    view.add(loadBar, BorderLayout.NORTH);
    view.revalidate();
    view.repaint();
  }

  void watchLoading(char[] buf, int off, int len) {
    if (!loading) return;
    String tail = loadTail + new String(buf, off, len);
    loadTail = tail.substring(Math.max(0, tail.length() - 300));
    // L'en-tête « Claude Code » n'arrive pas toujours d'un bloc (reprise d'une conversation, espaces remplacés par des
    // déplacements du curseur) : la zone de saisie de Claude (lignes ─── et ❯) suffit aussi.
    String text = ESCAPES.matcher(tail).replaceAll("");
    if (text.replace(" ", "").contains("ClaudeCode") || text.contains("───") || text.contains("❯"))
      javax.swing.SwingUtilities.invokeLater(this::hideLoading);
  }

  void hideLoading() {
    loading = false;
    if (loadTimer != null) loadTimer.stop();
    if (loadBar != null && loadBar.getParent() == view) {
      view.remove(loadBar);
      view.revalidate();
      view.repaint();
    }
  }

  void showExit(int code) {
    hideLoading();
    boolean ssh = "ssh".equals(session.kind);
    exitText.setText((ssh ? "Connexion fermée" : "Processus terminé") + (code != 0 ? " (code " + code + ")" : ""));
    restart.setText(ssh ? "Se reconnecter" : "Relancer");
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

    // Les couleurs exactes (RGB, 256 couleurs) ne passent pas par la palette, donc pas par Theme.readable :
    // le gris pâle de sbx restait peu lisible. On leur applique le même contraste minimum, comme la version Electron.
    @Override
    public void setCurrent(TextStyle style) {
      TerminalColor fg = style.getForeground();
      if (fg == null || fg.isIndexed() || style.hasOption(TextStyle.Option.INVERSE)) {
        super.setCurrent(style);
        return;
      }
      Theme t = Ui.t();
      TerminalColor bgc = style.getBackground();
      java.awt.Color bg = bgc == null ? t.termBg : bgc.isIndexed() ? t.ansi[bgc.getColorIndex()] : awt(bgc.toColor());
      com.jediterm.core.Color c = Theme.readable(awt(fg.toColor()), bg);
      super.setCurrent(style.toBuilder().setForeground(TerminalColor.rgb(c.getRed(), c.getGreen(), c.getBlue())).build());
    }

    private static java.awt.Color awt(com.jediterm.core.Color c) {
      return new java.awt.Color(c.getRed(), c.getGreen(), c.getBlue());
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
          return Theme.readable(t.ansi[i], t.bg);
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
