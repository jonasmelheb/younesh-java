package com.jonasmelheb.younesh;

import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRootPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.StyleContext;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

// Les briques d'interface dessinées à la main pour reprendre le rendu de la version Electron (styles.css).
final class Ui {
  private Ui() {
  }

  static volatile Theme theme = Theme.of("sombre");

  static Theme t() {
    return theme;
  }

  private static final Set<String> FONTS = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());

  static String firstFont(String... names) {
    for (String n : names) if (FONTS.contains(n)) return n;
    return Font.SANS_SERIF;
  }

  // « Segoe UI Variable », « Segoe UI », system-ui : 13 px, comme :root dans styles.css.
  static final String UI_FONT = firstFont("Segoe UI Variable Text", "Segoe UI", ".AppleSystemUIFont", "SF Pro Text", "Helvetica Neue", "Dialog");

  static Font font(float size, boolean bold) {
    // StyleContext donne une police avec le repli du système (symboles ✳ ⎇ ▶ absents de Segoe UI).
    return StyleContext.getDefaultStyleContext().getFont(UI_FONT, bold ? Font.BOLD : Font.PLAIN, 13).deriveFont(size);
  }

  // Lissage du texte : ClearType sous Windows, bien plus net sur un écran externe que le lissage en gris.
  static void textHints(Graphics2D g) {
    Object desktop = Shells.WIN ? Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints") : null;
    if (desktop instanceof Map<?, ?> hints) g.addRenderingHints(hints);
    else g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
  }

  static Graphics2D smooth(Graphics g0) {
    Graphics2D g = (Graphics2D) g0.create();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    textHints(g);
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    return g;
  }

  // ---------- Boutons ----------

  enum Kind { NORMAL, PRIMARY, GHOST, ICON }

  static final class Btn extends JButton {
    final Kind kind;
    boolean on;   // segment choisi

    Btn(String text, Kind kind) {
      super(text);
      this.kind = kind;
      setContentAreaFilled(false);
      setBorderPainted(false);
      setFocusPainted(false);
      setRolloverEnabled(true);
      setOpaque(false);
      setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      setFont(font(13, kind == Kind.PRIMARY));
      setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
      setIconTextGap(6);
      if (kind == Kind.GHOST) setHorizontalAlignment(LEFT);
      if (kind == Kind.ICON) {
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        setPreferredSize(new Dimension(32, 30));
      }
    }

    Btn(Icon icon) {
      this("", Kind.ICON);
      setIcon(icon);
    }

    @Override
    public Color getForeground() {
      Theme t = theme;
      if (t == null || kind == null) return super.getForeground();
      if (kind == Kind.PRIMARY) return t.onAccent;
      if (kind == Kind.GHOST && !getModel().isRollover()) return t.muted;
      return isEnabled() ? t.text : t.muted2;
    }

    @Override
    protected void paintComponent(Graphics g0) {
      Theme t = theme;
      Graphics2D g = smooth(g0);
      boolean h = getModel().isRollover() && isEnabled();
      int w = getWidth(), ht = getHeight();
      switch (kind) {
        case PRIMARY -> {
          g.setColor(h ? Theme.mix(Color.WHITE, t.accent, 0.08f) : t.accent);
          g.fillRoundRect(0, 0, w, ht, 16, 16);
        }
        case GHOST -> {
          if (h) {
            g.setColor(t.panel2);
            g.fillRoundRect(0, 0, w, ht, 16, 16);
          }
        }
        default -> {
          if (on) {
            g.setColor(t.panel2);
            g.fillRoundRect(0, 0, w, ht, 12, 12);
            g.setColor(t.border);
            g.drawRoundRect(0, 0, w - 1, ht - 1, 12, 12);
          } else if (getParent() instanceof Segmented) {
            if (h) {
              g.setColor(t.hover);
              g.fillRoundRect(0, 0, w, ht, 12, 12);
            }
          } else {
            g.setColor(h ? t.hover : t.panel2);
            g.fillRoundRect(0, 0, w, ht, 16, 16);
            g.setColor(h ? t.borderHover : t.border);
            g.drawRoundRect(0, 0, w - 1, ht - 1, 16, 16);
          }
        }
      }
      g.dispose();
      super.paintComponent(g0);
    }
  }

  // Choix exclusif dans un cadre (le « Type » de la fenêtre de discussion).
  static final class Segmented extends JPanel {
    private final List<Btn> buttons = new ArrayList<>();
    private int selected;
    private Consumer<Integer> onChange = i -> { };

    Segmented(String... labels) {
      super(new GridLayout(1, labels.length, 0, 0));
      setOpaque(false);
      setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
      for (int i = 0; i < labels.length; i++) {
        Btn b = new Btn(labels[i], Kind.NORMAL);
        b.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        int idx = i;
        b.addActionListener(e -> select(idx));
        buttons.add(b);
        add(b);
      }
      select(0);
    }

    void select(int i) {
      selected = i;
      for (int k = 0; k < buttons.size(); k++) buttons.get(k).on = k == i;
      repaint();
      onChange.accept(i);
    }

    int selected() {
      return selected;
    }

    void lock() {
      for (int k = 0; k < buttons.size(); k++) buttons.get(k).setEnabled(k == selected);
    }

    void onChange(Consumer<Integer> c) {
      onChange = c;
    }

    @Override
    protected void paintComponent(Graphics g0) {
      Graphics2D g = smooth(g0);
      g.setColor(theme.bg);
      g.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
      g.setColor(theme.border);
      g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
      g.dispose();
    }
  }

  // ---------- Icônes (mêmes tracés SVG que index.html) ----------

  static final class SvgIcon implements Icon {
    private final Path2D path;

    private SvgIcon(Path2D path) {
      this.path = path;
    }

    static SvgIcon cube() {
      Path2D p = new Path2D.Float();
      p.moveTo(8, 1.8); p.lineTo(13.6, 4.9); p.lineTo(13.6, 11.1); p.lineTo(8, 14.2); p.lineTo(2.4, 11.1); p.lineTo(2.4, 4.9); p.closePath();
      p.moveTo(2.4, 4.9); p.lineTo(8, 8); p.lineTo(13.6, 4.9);
      p.moveTo(8, 8); p.lineTo(8, 14.2);
      return new SvgIcon(p);
    }

    // Le serveur du bouton SSH (deux tiroirs et leurs voyants).
    static SvgIcon server() {
      Path2D p = new Path2D.Float();
      p.append(new java.awt.geom.RoundRectangle2D.Float(2, 2.5f, 12, 4.5f, 2.4f, 2.4f), false);
      p.append(new java.awt.geom.RoundRectangle2D.Float(2, 9, 12, 4.5f, 2.4f, 2.4f), false);
      p.append(new java.awt.geom.Ellipse2D.Float(4.4f, 4.35f, .8f, .8f), false);
      p.append(new java.awt.geom.Ellipse2D.Float(4.4f, 10.85f, .8f, .8f), false);
      return new SvgIcon(p);
    }

    // Dessine l'icône ailleurs que sur un bouton (barre latérale), à la taille size.
    void paint(Graphics2D g0, int x, int y, Color c, float size) {
      Graphics2D g = (Graphics2D) g0.create();
      g.translate(x, y);
      g.scale(size / 16, size / 16);
      g.setColor(c);
      g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
      g.draw(path);
      g.dispose();
    }

    static SvgIcon folderPlus() {
      Path2D p = new Path2D.Float();
      p.moveTo(1.5, 3.5); p.lineTo(5.5, 3.5); p.lineTo(7, 5); p.lineTo(14.5, 5); p.lineTo(14.5, 13); p.lineTo(1.5, 13); p.closePath();
      p.moveTo(8, 7.5); p.lineTo(8, 11.5);
      p.moveTo(6, 9.5); p.lineTo(10, 9.5);
      return new SvgIcon(p);
    }

    public void paintIcon(Component c, Graphics g0, int x, int y) {
      Graphics2D g = smooth(g0);
      g.translate(x, y);
      g.setColor(c.getForeground());
      g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
      g.draw(path);
      g.dispose();
    }

    public int getIconWidth() {
      return 16;
    }

    public int getIconHeight() {
      return 16;
    }
  }

  // L'icône de dossier de la barre latérale, remplie de la couleur du dossier.
  static void folderIcon(Graphics2D g, int x, int y, Color c) {
    Path2D p = new Path2D.Float();
    p.moveTo(1.5, 3.2);
    p.quadTo(1.5, 2.5, 2.2, 2.5);
    p.lineTo(5.8, 2.5);
    p.lineTo(7.3, 4);
    p.lineTo(13.8, 4);
    p.quadTo(14.5, 4, 14.5, 4.7);
    p.lineTo(14.5, 12.8);
    p.quadTo(14.5, 13.5, 13.8, 13.5);
    p.lineTo(2.2, 13.5);
    p.quadTo(1.5, 13.5, 1.5, 12.8);
    p.closePath();
    Graphics2D g2 = (Graphics2D) g.create();
    g2.translate(x, y);
    g2.setColor(Theme.alpha(c, 0.9f));
    g2.fill(p);
    g2.dispose();
  }

  static void chevron(Graphics2D g, int x, int y, boolean collapsed, Color c) {
    Graphics2D g2 = (Graphics2D) g.create();
    g2.translate(x, y);
    if (collapsed) g2.rotate(-Math.PI / 2, 6, 6);
    Path2D p = new Path2D.Float();
    p.moveTo(3, 4.5);
    p.lineTo(6, 7.5);
    p.lineTo(9, 4.5);
    g2.setColor(c);
    g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    g2.draw(p);
    g2.dispose();
  }

  // Petit carré de couleur dans les menus (« Ranger dans »).
  static final class Square implements Icon {
    private final Color color;
    private final boolean outline;

    Square(Color color, boolean outline) {
      this.color = color;
      this.outline = outline;
    }

    public void paintIcon(Component c, Graphics g0, int x, int y) {
      Graphics2D g = smooth(g0);
      g.setColor(color);
      if (outline) g.drawRoundRect(x, y, 9, 9, 6, 6); else g.fillRoundRect(x, y, 10, 10, 6, 6);
      g.dispose();
    }

    public int getIconWidth() {
      return 10;
    }

    public int getIconHeight() {
      return 10;
    }
  }

  // Le « Y » de la signature : écriture manuscrite, dégradé bleu → rose.
  // Dessiné, pas écrit : aucune police manuscrite n'existe à la fois sur Windows et sur macOS.
  static final class Signature extends JComponent {
    private static final double[][] CURVES = {
      {2.6, 10.5, 3.6, 9.6, 5, 8.4}, {7, 6.6, 9, 5, 10.6, 4.8}, {11.6, 4.7, 11.9, 5.6, 11.5, 6.6}, {10.2, 9.5, 7.2, 13.2, 6.6, 16},
      {6.4, 17, 6.9, 17.2, 7.6, 16.6}, {10, 14.4, 13, 10.6, 15.1, 7.6}, {15.4, 7.1, 16, 7.2, 16.2, 7.8}, {16.4, 8.4, 15.6, 9.4, 15, 10.4},
      {13.6, 12.8, 12.2, 15, 11.3, 16.9}, {12.6, 17.4, 14, 16.9, 15.3, 16.8}, {14, 18.4, 12, 19.2, 10.9, 19.6}, {10.2, 21, 9.6, 22.6, 9, 24},
      {8.3, 24, 8.3, 24, 8, 24}, {8.3, 22, 9, 19.8, 9.6, 18.2}, {8.4, 18.8, 6.6, 19.6, 5.6, 19.2}, {4.4, 18.8, 4.4, 17, 5.2, 15.2},
      {6.4, 12.6, 8.6, 9.6, 9.4, 7.6}, {7.4, 8.8, 5, 10.6, 2.6, 12.2},
    };
    private static final Path2D SHAPE = new Path2D.Double();

    static {
      SHAPE.moveTo(2.6, 12.2);
      for (double[] c : CURVES) SHAPE.curveTo(c[0], c[1], c[2], c[3], c[4], c[5]);
      SHAPE.closePath();
    }

    Signature() {
      setPreferredSize(new Dimension(36, 28));
      setToolTipText("Fait par Y");
    }

    @Override
    protected void paintComponent(Graphics g0) {
      Graphics2D g = smooth(g0);
      g.translate((getWidth() - 15) / 2.0 - 2, (getHeight() - 21) / 2.0 - 4.5);
      g.setPaint(new GradientPaint(2, 4, Theme.c("#7c9cff"), 17, 24, Theme.c("#f472b6")));
      g.fill(SHAPE);
      g.dispose();
    }
  }

  // Point d'état : vert en cours, bleu nouvelle sortie, rouge terminé, orange attend, gris en veille.
  static void dot(Graphics2D g, int cx, int cy, Term t, boolean pulse) {
    Theme th = theme;
    Color c = th.borderHover;
    if (t != null) {
      if (t.attention) c = Theme.ORANGE;
      else if (t.unread) c = th.accent;
      else if (t.running) c = Theme.OK;
      else if (t.exited) c = Theme.DANGER;
    }
    if (t != null && t.attention && pulse) {
      g.setColor(Theme.alpha(Theme.ORANGE, 0.3f));
      g.fillOval(cx - 6, cy - 6, 13, 13);
    } else if (t != null && t.unread && !t.attention) {
      g.setColor(Theme.alpha(th.accent, 0.2f));
      g.fillOval(cx - 6, cy - 6, 13, 13);
    }
    g.setColor(c);
    g.fillOval(cx - 3, cy - 3, 7, 7);
  }

  static JLabel label(String text, float size, Color color) {
    JLabel l = new JLabel(text) {
      @Override
      public Color getForeground() {
        return color == null ? (theme == null ? super.getForeground() : theme.muted) : color;
      }
    };
    l.setFont(font(size, false));
    return l;
  }

  // ---------- Fenêtres (le #modal de la version Electron) ----------

  static final class Modal extends JDialog {
    private final JPanel body = new JPanel();
    private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    private final JPanel extra = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    private boolean ok;
    int width = 480;
    // Vérifie le formulaire avant de fermer : false garde la fenêtre ouverte.
    java.util.function.BooleanSupplier validate = () -> true;

    Modal(JFrame owner, String title, String submit) {
      super(owner, true);
      setUndecorated(true);
      setBackground(new Color(0, 0, 0, 0));
      JPanel card = new JPanel(new BorderLayout()) {
        @Override
        protected void paintComponent(Graphics g0) {
          Graphics2D g = smooth(g0);
          g.setColor(theme.panel);
          g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 28, 28);
          g.setColor(theme.border);
          g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 28, 28);
          g.dispose();
        }
      };
      card.setOpaque(false);
      card.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
      JLabel heading = new JLabel(title) {
        @Override
        public Color getForeground() {
          return theme.text;
        }
      };
      heading.setFont(font(16, true));
      heading.setBorder(BorderFactory.createEmptyBorder(0, 0, 16, 0));
      card.add(heading, BorderLayout.NORTH);
      body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
      body.setOpaque(false);
      card.add(body, BorderLayout.CENTER);
      actions.setOpaque(false);
      // Sans bouton principal (submit null), un seul bouton « Fermer ».
      Btn cancel = new Btn(submit == null ? "Fermer" : "Annuler", submit == null ? Kind.PRIMARY : Kind.NORMAL);
      cancel.addActionListener(e -> dispose());
      actions.add(cancel);
      if (submit != null) {
        Btn go = new Btn(submit, Kind.PRIMARY);
        go.addActionListener(e -> {
          if (!validate.getAsBoolean()) return;
          ok = true;
          dispose();
        });
        actions.add(go);
        getRootPane().setDefaultButton(go);
      }
      JPanel bottom = new JPanel(new BorderLayout());
      bottom.setOpaque(false);
      bottom.setBorder(BorderFactory.createEmptyBorder(18, 0, 0, 0));
      extra.setOpaque(false);
      ((FlowLayout) extra.getLayout()).setHgap(6);
      bottom.add(extra, BorderLayout.WEST);
      bottom.add(actions, BorderLayout.EAST);
      card.add(bottom, BorderLayout.SOUTH);
      setContentPane(card);
      getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
      getRootPane().setWindowDecorationStyle(JRootPane.NONE);
    }

    // Un champ : libellé gris au-dessus, 12 px d'espace en dessous.
    JComponent field(String label, JComponent input) {
      JPanel f = new JPanel(new BorderLayout(0, 5));
      f.setOpaque(false);
      f.setAlignmentX(LEFT_ALIGNMENT);
      f.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
      if (label != null) f.add(label(label, 12, null), BorderLayout.NORTH);
      f.add(input, BorderLayout.CENTER);
      body.add(f);
      return f;
    }

    JComponent field(String label, JComponent input, String hint) {
      JPanel box = new JPanel(new BorderLayout(0, 5));
      box.setOpaque(false);
      box.add(input, BorderLayout.CENTER);
      JLabel h = label("<html><div style='width:" + (width - 80) + "px'>" + hint + "</div></html>", 11, null);
      box.add(h, BorderLayout.SOUTH);
      return field(label, box);
    }

    void check(JCheckBox box) {
      box.setOpaque(false);
      box.setFont(font(12.5f, false));
      box.setAlignmentX(LEFT_ALIGNMENT);
      box.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
      body.add(box);
    }

    // Champ texte + bouton à droite (« Parcourir… »).
    static JPanel row(JComponent main, JComponent side) {
      JPanel r = new JPanel(new BorderLayout(6, 0));
      r.setOpaque(false);
      r.add(main, BorderLayout.CENTER);
      r.add(side, BorderLayout.EAST);
      return r;
    }

    // Boutons supplémentaires à gauche des actions (« Importer… », « Préparer le poste… »).
    void action(Btn b) {
      extra.add(b);
    }

    JPanel body() {
      return body;
    }

    // Après un changement du contenu : nouvelle hauteur, même largeur.
    void refit() {
      pack();
      setSize(width, getHeight());
    }

    boolean open(JFrame owner) {
      setSize(new Dimension(width, 10));
      refit();
      setLocation(owner.getX() + (owner.getWidth() - width) / 2, owner.getY() + Math.max(40, (owner.getHeight() - getHeight()) / 2));
      Component glass = owner.getGlassPane();
      boolean veiled = glass.isVisible();   // une fenêtre ouverte par-dessus une autre garde son voile
      glass.setVisible(true);
      SwingUtilities.invokeLater(() -> {
        Component first = firstInput(body);
        if (first != null) first.requestFocusInWindow();
      });
      setVisible(true);
      glass.setVisible(veiled);
      return ok;
    }

    private static Component firstInput(Component c) {
      if (c instanceof JTextField || c instanceof javax.swing.JComboBox) return c;
      if (c instanceof java.awt.Container k) {
        for (Component child : k.getComponents()) {
          Component f = firstInput(child);
          if (f != null) return f;
        }
      }
      return null;
    }
  }

  // Le voile sombre derrière les fenêtres (#modal-backdrop).
  static final class Backdrop extends JComponent {
    Backdrop() {
      addMouseListener(new MouseAdapter() { });
    }

    @Override
    protected void paintComponent(Graphics g) {
      g.setColor(new Color(0, 0, 0, 140));
      g.fillRect(0, 0, getWidth(), getHeight());
    }
  }

  static JTextField input(String value, String placeholder) {
    JTextField f = new JTextField(value == null ? "" : value);
    f.setFont(font(13, false));
    if (placeholder != null) f.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholder);
    f.putClientProperty(FlatClientProperties.TEXT_FIELD_PADDING, new Insets(3, 4, 3, 4));
    return f;
  }

  // ---------- Menus contextuels (#menu) ----------

  static JPopupMenu popup() {
    JPopupMenu m = new JPopupMenu();
    m.setBorder(BorderFactory.createCompoundBorder(
      BorderFactory.createLineBorder(theme.borderStrong), BorderFactory.createEmptyBorder(5, 5, 5, 5)));
    m.setBackground(theme.panel2);
    return m;
  }

  static JMenuItem item(JPopupMenu m, String label, Runnable run) {
    return item(m, label, null, run, false);
  }

  static JMenuItem item(JPopupMenu m, String label, Icon icon, Runnable run, boolean danger) {
    JMenuItem it = new JMenuItem(label, icon) {
      @Override
      public Color getForeground() {
        return danger ? Theme.DANGER : theme.text;
      }
    };
    it.setFont(font(13, false));
    it.setIconTextGap(8);
    it.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
    it.setOpaque(false);
    it.addActionListener(e -> run.run());
    m.add(it);
    return it;
  }

  static void menuLabel(JPopupMenu m, String text) {
    JLabel l = label(text, 11, null);
    l.setBorder(BorderFactory.createEmptyBorder(6, 10, 4, 10));
    m.add(fullWidth(l));
  }

  static void separator(JPopupMenu m) {
    JComponent sep = new JComponent() {
      @Override
      protected void paintComponent(Graphics g) {
        g.setColor(theme.border);
        g.fillRect(2, 4, getWidth() - 4, 1);
      }
    };
    sep.setPreferredSize(new Dimension(10, 9));
    m.add(fullWidth(sep));
  }

  // La grille de couleurs : « Aucune », les couleurs proposées, puis une couleur personnalisée.
  static void swatches(JPopupMenu m, String current, Consumer<String> pick, boolean allowNone) {
    menuLabel(m, "Couleur");
    JPanel grid = new JPanel(new GridLayout(0, 6, 6, 6));
    grid.setOpaque(false);
    grid.setBorder(BorderFactory.createEmptyBorder(4, 10, 8, 10));
    List<String> list = new ArrayList<>();
    if (allowNone) list.add(null);
    list.addAll(List.of(Theme.SWATCHES));
    for (String c : list) grid.add(new Swatch(c, c != null && c.equalsIgnoreCase(current) || c == null && current == null, false, () -> {
      m.setVisible(false);
      pick.accept(c);
    }));
    grid.add(new Swatch(current, false, true, () -> {
      m.setVisible(false);
      Color chosen = JColorChooser.showDialog(m.getInvoker(), "Couleur personnalisée", current == null ? theme.accent : Sidebar.colorOf(current, theme.accent));
      if (chosen != null) pick.accept(Theme.hex(chosen));
    }));
    JPanel wrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    wrap.setOpaque(false);
    wrap.add(grid);
    m.add(fullWidth(wrap));
  }

  // Dans un menu, les éléments qui ne sont pas des JMenuItem prennent toute la largeur, alignés à gauche.
  private static JComponent fullWidth(JComponent c) {
    c.setAlignmentX(Component.CENTER_ALIGNMENT);
    c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
    return c;
  }

  private static final class Swatch extends JComponent {
    private final String color;
    private final boolean selected, custom;
    private boolean hover;

    Swatch(String color, boolean selected, boolean custom, Runnable click) {
      this.color = color;
      this.selected = selected;
      this.custom = custom;
      setPreferredSize(new Dimension(22, 22));
      setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      setToolTipText(custom ? "Couleur personnalisée" : color == null ? "Aucune" : color);
      addMouseListener(new MouseAdapter() {
        @Override
        public void mouseEntered(MouseEvent e) {
          hover = true;
          repaint();
        }

        @Override
        public void mouseExited(MouseEvent e) {
          hover = false;
          repaint();
        }

        @Override
        public void mouseReleased(MouseEvent e) {
          if (contains(e.getPoint())) click.run();
        }
      });
    }

    @Override
    protected void paintComponent(Graphics g0) {
      Graphics2D g = smooth(g0);
      int s = hover ? 22 : 20, o = (22 - s) / 2;
      if (custom) {
        g.setColor(color == null ? theme.accent : Sidebar.colorOf(color, theme.accent));
        g.fillRoundRect(o + 3, o + 3, s - 6, s - 6, 8, 8);
        g.setColor(theme.muted2);
        g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[]{2, 2}, 0));
        g.drawRoundRect(o, o, s - 1, s - 1, 12, 12);
      } else if (color == null) {
        g.setClip(new java.awt.geom.RoundRectangle2D.Float(o, o, s, s, 12, 12));
        g.setColor(Theme.c("#222222"));
        g.fillRect(o, o, s, s);
        g.setColor(Theme.c("#333333"));
        g.setStroke(new BasicStroke(3));
        for (int k = -s; k < s * 2; k += 8) g.drawLine(o + k, o + s, o + k + s, o);
      } else {
        g.setColor(Theme.c(color));
        g.fillRoundRect(o, o, s, s, 12, 12);
      }
      if (selected) {
        g.setClip(null);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2));
        g.drawRoundRect(o + 1, o + 1, s - 2, s - 2, 12, 12);
      }
      g.dispose();
    }
  }
}
