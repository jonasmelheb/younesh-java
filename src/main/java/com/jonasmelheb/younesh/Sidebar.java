package com.jonasmelheb.younesh;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// L'arbre des dossiers et des discussions, dessiné à la main d'après styles.css de la version Electron.
final class Sidebar extends JComponent {
  interface Actions {
    void activate(Store.Session s);
    void toggle(Store.Folder f);
    void menu(Store.Folder f, Store.Session s, Point p);
    void renamed();
    void close(Store.Session s);
    void moved();
    void launch(Store.Folder f);
    void newIn(Store.Folder f);
  }

  private enum Type { FOLDER, SESSION, LABEL }

  private record Row(Type type, Store.Folder folder, Store.Session session, int x, int y, int h) {
  }

  private record Block(Store.Folder folder, int top, int bottom, int bodyTop, int bodyBottom) {
  }

  private enum DropKind { INTO, BEFORE, AFTER, UNFILED }

  private record Drop(DropKind kind, int row) {
  }

  // Une discussion fait une ligne (le nom), ou deux quand le dossier est un dépôt git (la branche en dessous).
  private static final int PAD = 6, HEAD_H = 27, ONE_LINE = 30, TWO_LINES = 42, LABEL_H = 30, BODY_X = 11, NESTED_X = 23;

  private final Store store;
  private final Map<String, Term> terms;
  private final Map<String, String> branches;
  private final Actions actions;
  private String filter = "";
  private final List<Row> rows = new ArrayList<>();
  private final List<Block> blocks = new ArrayList<>();
  private int hover = -1, unfiledTop;
  private Point mouse;
  private boolean pulse;

  private int pressed = -1;
  private boolean dragging;
  private Drop drop;
  private Point pressPoint;

  private final JTextField editor = new JTextField();
  private Runnable commit;

  Sidebar(Store store, Map<String, Term> terms, Map<String, String> branches, Actions actions) {
    this.store = store;
    this.terms = terms;
    this.branches = branches;
    this.actions = actions;
    setLayout(null);
    setToolTipText("");
    setupEditor();
    MouseAdapter m = new MouseAdapter() {
      @Override
      public void mouseMoved(MouseEvent e) {
        mouse = e.getPoint();
        hover = rowAt(e.getY());
        repaint();
      }

      @Override
      public void mouseExited(MouseEvent e) {
        mouse = null;
        hover = -1;
        repaint();
      }

      @Override
      public void mousePressed(MouseEvent e) {
        int i = rowAt(e.getY());
        if (i < 0 || rows.get(i).type == Type.LABEL) return;
        Row r = rows.get(i);
        if (e.isPopupTrigger()) {
          actions.menu(r.folder, r.session, e.getPoint());
          return;
        }
        if (SwingUtilities.isMiddleMouseButton(e) && r.session != null) {
          actions.close(r.session);
          return;
        }
        if (!SwingUtilities.isLeftMouseButton(e)) return;
        pressed = i;
        pressPoint = e.getPoint();
        if (r.type == Type.FOLDER) {
          if (launchRect(r).contains(e.getPoint())) {
            actions.launch(r.folder);
          } else if (addRect(r).contains(e.getPoint())) {
            actions.newIn(r.folder);
          } else if (e.getClickCount() == 2) {
            // Le premier clic a replié le dossier : on revient en arrière, comme le dblclick d'Electron.
            actions.toggle(r.folder);
            startRename(r.folder, null);
          } else {
            actions.toggle(r.folder);
          }
        } else if (e.getClickCount() == 2) {
          startRename(null, r.session);
        } else {
          actions.activate(r.session);
        }
      }

      @Override
      public void mouseDragged(MouseEvent e) {
        if (pressed < 0 || !filter.isEmpty() || pressed >= rows.size()) return;
        if (!dragging && pressPoint.distance(e.getPoint()) < 5) return;
        dragging = true;
        drop = computeDrop(e.getY());
        repaint();
      }

      @Override
      public void mouseReleased(MouseEvent e) {
        if (e.isPopupTrigger()) {
          int i = rowAt(e.getY());
          if (i >= 0 && rows.get(i).type != Type.LABEL) actions.menu(rows.get(i).folder, rows.get(i).session, e.getPoint());
        }
        if (dragging && drop != null) applyDrop();
        dragging = false;
        drop = null;
        pressed = -1;
        repaint();
      }
    };
    addMouseListener(m);
    addMouseMotionListener(m);
    new Timer(800, e -> {
      pulse = !pulse;
      if (terms.values().stream().anyMatch(t -> t.attention)) repaint();
    }).start();
    rebuild();
  }

  void setFilter(String f) {
    filter = norm(f);
    rebuild();
  }

  static String norm(String s) {
    return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
  }

  private boolean matches(Store.Session s) {
    if (filter.isEmpty()) return true;
    Store.Folder f = store.folder(s.folderId);
    return norm(s.name).contains(filter) || norm(s.cwd).contains(filter) || (f != null && norm(f.name).contains(filter));
  }

  private int heightOf(Store.Session s) {
    return branches.get(s.id) != null ? TWO_LINES : ONE_LINE;
  }

  void rebuild() {
    if (commit != null) return;   // ne pas casser un renommage en cours
    rows.clear();
    blocks.clear();
    int y = 2;
    for (Store.Folder f : store.folders) {
      List<Store.Session> items = store.sessions.stream().filter(s -> f.id.equals(s.folderId) && matches(s)).toList();
      if (!filter.isEmpty() && items.isEmpty() && !norm(f.name).contains(filter)) continue;
      y += 4;
      int top = y;
      rows.add(new Row(Type.FOLDER, f, null, PAD, y, HEAD_H));
      y += HEAD_H;
      int bodyTop = y;
      if (!f.collapsed || !filter.isEmpty()) {
        for (Store.Session s : items) {
          y += 1;
          rows.add(new Row(Type.SESSION, f, s, PAD + NESTED_X, y, heightOf(s)));
          y += heightOf(s) + 1;
        }
      }
      int bodyBottom = y;
      if (f.color != null) y += 2;
      blocks.add(new Block(f, top, y, bodyTop, bodyBottom));
    }
    unfiledTop = y;
    if (!store.folders.isEmpty()) {
      rows.add(new Row(Type.LABEL, null, null, PAD, y, LABEL_H));
      y += LABEL_H;
    }
    for (Store.Session s : store.sessions) {
      if (store.folder(s.folderId) != null || !matches(s)) continue;
      y += 1;
      rows.add(new Row(Type.SESSION, null, s, PAD, y, heightOf(s)));
      y += heightOf(s) + 1;
    }
    y = Math.max(y, unfiledTop + 48) + 12;
    setPreferredSize(new Dimension(10, y));
    revalidate();
    repaint();
  }

  private int rowAt(int y) {
    for (int i = 0; i < rows.size(); i++) {
      Row r = rows.get(i);
      if (y >= r.y && y < r.y + r.h) return i;
    }
    return -1;
  }

  @Override
  public String getToolTipText(MouseEvent e) {
    int i = rowAt(e.getY());
    if (i < 0) return null;
    Row r = rows.get(i);
    if (r.type == Type.FOLDER) {
      if (launchRect(r).contains(e.getPoint())) return "Tout lancer";
      if (addRect(r).contains(e.getPoint())) return "Nouvelle discussion dans ce dossier";
      return null;
    }
    if (r.session == null) return null;
    Store.Session s = r.session;
    String b = branches.get(s.id);
    return "<html>" + esc(s.name) + "<br>" + esc(s.cwd == null ? "" : s.cwd) + (b != null ? "<br>Branche : " + esc(b) : "") + "</html>";
  }

  private static String esc(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;");
  }

  // ---------- Couleurs ----------

  static Color colorOf(String hex, Color fallback) {
    try {
      return hex == null || hex.isEmpty() ? fallback : Theme.c(hex);
    } catch (RuntimeException e) {
      return fallback;
    }
  }

  private Color sessionColor(Store.Session s) {
    if (s.color != null) return colorOf(s.color, null);
    Store.Folder f = store.folder(s.folderId);
    return f != null ? colorOf(f.color, null) : null;
  }

  // Fond d'un dossier coloré : sa couleur à 9 %, la teinte pâle sur tout ce qu'il contient.
  private static Color tint(Color c, Theme t) {
    return Theme.mix(c, t.panel, 0.09f);
  }

  // ---------- Renommer sur place ----------

  private void setupEditor() {
    editor.setVisible(false);
    add(editor);
    editor.addActionListener(e -> finishRename(true));
    editor.registerKeyboardAction(e -> finishRename(false), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_FOCUSED);
    editor.addFocusListener(new FocusAdapter() {
      @Override
      public void focusLost(FocusEvent e) {
        finishRename(true);
      }
    });
  }

  void startRename(Store.Folder f, Store.Session s) {
    finishRename(true);
    rebuild();
    for (Row r : rows) {
      boolean match = s != null ? r.session == s : r.type == Type.FOLDER && r.folder == f;
      if (!match) continue;
      Theme t = Ui.t();
      Rectangle box = nameRect(r);
      editor.setText(s != null ? s.name : f.name);
      editor.setFont(Ui.font(13, s == null));
      editor.setBackground(t.bg);
      editor.setForeground(t.text);
      editor.setCaretColor(t.text);
      editor.setBorder(BorderFactory.createCompoundBorder(
        BorderFactory.createLineBorder(t.accent), BorderFactory.createEmptyBorder(0, 3, 0, 3)));
      editor.setBounds(box);
      editor.setVisible(true);
      commit = () -> {
        String v = editor.getText().trim();
        if (v.isEmpty()) return;
        if (s != null) s.name = v; else f.name = v;
      };
      editor.selectAll();
      editor.requestFocusInWindow();
      scrollRectToVisible(box);
      return;
    }
  }

  private void finishRename(boolean save) {
    if (commit == null) return;
    Runnable c = commit;
    commit = null;
    editor.setVisible(false);
    if (save) c.run();
    rebuild();
    actions.renamed();
  }

  private boolean editing(Row r) {
    return commit != null && editor.isVisible() && nameRect(r).intersects(editor.getBounds());
  }

  private Rectangle nameRect(Row r) {
    if (r.type == Type.FOLDER) {
      int x = r.x + 6 + 12 + 6 + 16 + 6;
      return new Rectangle(x - 4, r.y + 3, launchRect(r).x - 10 - x + 4, r.h - 6);
    }
    int x = r.x + 8 + 16 + 8;
    return new Rectangle(x - 4, r.y + 5, getWidth() - PAD - 8 - 7 - 8 - x + 4, 20);
  }

  // ---------- Glisser-déposer ----------

  private Drop computeDrop(int y) {
    Row dragged = rows.get(pressed);
    int i = rowAt(y);
    if (i < 0 || rows.get(i).type == Type.LABEL) {
      return y >= unfiledTop || i >= 0 ? new Drop(DropKind.UNFILED, -1) : null;
    }
    Row r = rows.get(i);
    boolean upper = y - r.y < r.h / 2;
    if (dragged.type == Type.SESSION) {
      if (r.type == Type.FOLDER) return new Drop(DropKind.INTO, i);
      return new Drop(upper ? DropKind.BEFORE : DropKind.AFTER, i);
    }
    // Un dossier se place avant ou après un autre dossier.
    if (r.type != Type.FOLDER) {
      if (r.folder == null) return new Drop(DropKind.UNFILED, -1);
      for (int k = 0; k < rows.size(); k++) {
        if (rows.get(k).type == Type.FOLDER && rows.get(k).folder == r.folder) return new Drop(DropKind.AFTER, k);
      }
    }
    return new Drop(upper ? DropKind.BEFORE : DropKind.AFTER, i);
  }

  private void applyDrop() {
    Row dragged = rows.get(pressed);
    Row target = drop.row >= 0 ? rows.get(drop.row) : null;
    if (dragged.type == Type.SESSION) {
      Store.Session s = dragged.session;
      if (target != null && target.session == s) return;
      store.sessions.remove(s);
      switch (drop.kind) {
        case INTO -> {
          s.folderId = target.folder.id;
          target.folder.collapsed = false;
          store.sessions.add(s);
        }
        case UNFILED -> {
          s.folderId = null;
          store.sessions.add(s);
        }
        default -> {
          s.folderId = target.folder == null ? null : target.folder.id;
          int at = store.sessions.indexOf(target.session) + (drop.kind == DropKind.AFTER ? 1 : 0);
          store.sessions.add(Math.max(0, at), s);
        }
      }
    } else {
      Store.Folder f = dragged.folder;
      if (drop.kind == DropKind.UNFILED) {
        store.folders.remove(f);
        store.folders.add(f);
      } else {
        if (target.folder == f) return;
        store.folders.remove(f);
        int at = store.folders.indexOf(target.folder) + (drop.kind == DropKind.AFTER ? 1 : 0);
        store.folders.add(Math.max(0, at), f);
      }
    }
    actions.moved();
  }

  // ---------- Dessin ----------

  // Les boutons ▶ et + d'un dossier gardent leur place même cachés, comme visibility: hidden en CSS.
  private Rectangle addRect(Row r) {
    int right = getWidth() - PAD - 6;
    return new Rectangle(right - 17, r.y + 3, 17, r.h - 6);
  }

  private Rectangle launchRect(Row r) {
    Rectangle a = addRect(r);
    return new Rectangle(a.x - 2 - 21, a.y, 21, a.height);
  }

  @Override
  protected void paintComponent(Graphics g0) {
    Theme t = Ui.t();
    Graphics2D g = Ui.smooth(g0);
    g.setColor(t.panel);
    g.fillRect(0, 0, getWidth(), getHeight());
    int rw = getWidth() - PAD * 2;

    for (Block b : blocks) {
      Color fc = colorOf(b.folder.color, null);
      if (fc != null) {
        g.setColor(tint(fc, t));
        g.fillRoundRect(PAD, b.top, rw, b.bottom - b.top, 16, 16);
      }
      if (b.bodyBottom > b.bodyTop) {
        g.setColor(fc != null ? fc : t.border);
        g.fillRect(PAD + BODY_X, b.bodyTop, 2, b.bodyBottom - b.bodyTop);
      }
    }

    if (drop != null && drop.kind == DropKind.UNFILED) {
      g.setColor(Theme.alpha(t.accent, 0.08f));
      g.fillRoundRect(PAD, unfiledTop, rw, Math.max(48, getHeight() - unfiledTop - 12), 12, 12);
    }

    for (int i = 0; i < rows.size(); i++) {
      Row r = rows.get(i);
      switch (r.type) {
        case LABEL -> {
          g.setFont(Ui.font(11, false).deriveFont(Map.of(TextAttribute.TRACKING, 0.055f)));
          g.setColor(t.muted);
          g.drawString("SANS DOSSIER", r.x + 8, r.y + 12 + g.getFontMetrics().getAscent() - 1);
        }
        case FOLDER -> paintFolder(g, r, i, t, rw);
        case SESSION -> paintSession(g, r, i, t);
      }
    }

    if (dragging && drop != null && (drop.kind == DropKind.BEFORE || drop.kind == DropKind.AFTER)) {
      Row r = rows.get(drop.row);
      int y = drop.kind == DropKind.BEFORE ? r.y : r.y + r.h - 2;
      g.setColor(t.accent);
      g.fillRect(r.x, y, getWidth() - PAD - r.x, 2);
    }
    g.dispose();
  }

  private void paintFolder(Graphics2D g, Row r, int i, Theme t, int rw) {
    Store.Folder f = r.folder;
    Color fc = colorOf(f.color, null);
    boolean hovered = i == hover && !dragging;
    if (dragging && drop != null && drop.kind == DropKind.INTO && drop.row == i) {
      g.setColor(Theme.alpha(t.accent, 0.15f));
      g.fillRoundRect(r.x, r.y, rw, r.h, 12, 12);
      g.setColor(t.accent);
      g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1, new float[]{3, 3}, 0));
      g.drawRoundRect(r.x, r.y, rw - 1, r.h - 1, 12, 12);
      g.setStroke(new BasicStroke(1));
    } else if (hovered) {
      g.setColor(fc != null ? Theme.mix(fc, tint(fc, t), 0.15f) : t.panel2);
      g.fillRoundRect(r.x, r.y, rw, r.h, 12, 12);
    }
    int cy = r.y + r.h / 2;
    int x = r.x + 6;
    Ui.chevron(g, x, cy - 6, f.collapsed && filter.isEmpty(), t.muted);
    x += 12 + 6;
    Ui.folderIcon(g, x, cy - 8, fc != null ? fc : t.muted2);
    x += 16 + 6;

    Rectangle launch = launchRect(r), add = addRect(r);
    long count = store.sessions.stream().filter(s -> f.id.equals(s.folderId) && matches(s)).count();
    String cnt = String.valueOf(count);
    g.setFont(Ui.font(11, false));
    FontMetrics sm = g.getFontMetrics();
    int cntX = launch.x - 6 - sm.stringWidth(cnt);
    g.setColor(t.muted);
    g.drawString(cnt, cntX, cy + sm.getAscent() / 2 - 1);
    int nameRight = cntX - 6;
    Term waiting = store.sessions.stream().filter(s -> f.id.equals(s.folderId)).map(s -> terms.get(s.id))
      .filter(tt -> tt != null && tt.attention).findFirst().orElse(null);
    if (waiting != null) {
      Ui.dot(g, nameRight - 4, cy, waiting, pulse);
      nameRight -= 13;
    }
    g.setFont(Ui.font(13, true));
    g.setColor(t.text);
    FontMetrics fm = g.getFontMetrics();
    if (!editing(r)) g.drawString(ellipsize(fm, f.name, nameRight - x), x, cy + fm.getAscent() / 2 - 1);
    if (hovered) {
      Point m = mouse;
      g.setFont(Ui.font(15, false));
      FontMetrics bm = g.getFontMetrics();
      g.setColor(m != null && launch.contains(m) ? t.text : t.muted);
      g.drawString("▶", launch.x + (launch.width - bm.stringWidth("▶")) / 2, cy + bm.getAscent() / 2 - 2);
      g.setColor(m != null && add.contains(m) ? t.text : t.muted);
      g.drawString("+", add.x + (add.width - bm.stringWidth("+")) / 2, cy + bm.getAscent() / 2 - 2);
    }
  }

  private void paintSession(Graphics2D g, Row r, int i, Theme t) {
    Store.Session s = r.session;
    Term term = terms.get(s.id);
    boolean active = s.id.equals(store.activeId);
    boolean attention = term != null && term.attention;
    Color fc = r.folder != null ? colorOf(r.folder.color, null) : null;
    Color under = fc != null ? tint(fc, t) : t.panel;
    int rw = getWidth() - PAD - r.x;

    Color bg = null;
    if (attention) bg = Theme.mix(Theme.ORANGE, under, fc != null ? 0.14f : 0.08f);
    else if (active) bg = fc != null ? Theme.mix(fc, under, 0.24f) : t.active;
    else if (i == hover && !dragging) bg = fc != null ? Theme.mix(fc, under, 0.15f) : t.panel2;
    if (bg != null) {
      g.setColor(bg);
      g.fillRoundRect(r.x, r.y, rw, r.h, 12, 12);
    }
    if (active) {
      Color own = sessionColor(s);
      g.setColor(own != null ? own : t.accent);
      g.fillRoundRect(r.x - 2, r.y + 6, 3, r.h - 12, 4, 4);
    }

    // Icône du type : ›_ terminal, ✳ Claude.
    String kind = "claude".equals(s.kind) ? "✳" : "›_";
    Color kc;
    if (s.color != null) kc = colorOf(s.color, t.muted);
    else if ("claude".equals(s.kind)) kc = Theme.CLAUDE;
    else if (fc != null) kc = Theme.mix(fc, t.text, 0.8f);
    else kc = t.muted;
    g.setFont(Ui.font(12, false));
    FontMetrics km = g.getFontMetrics();
    int cy = r.y + r.h / 2;
    g.setColor(kc);
    g.drawString(kind, r.x + 8 + (16 - km.stringWidth(kind)) / 2, cy + km.getAscent() / 2 - 2);

    // Le nom, et la branche en dessous. Le chemin est dans la barre du haut et l'infobulle.
    int x = r.x + 8 + 16 + 8;
    int right = r.x + rw - 8 - 7 - 8;
    g.setFont(Ui.font(13, attention));
    FontMetrics fm = g.getFontMetrics();
    g.setColor(t.text);
    int nameY = r.y + 6 + fm.getAscent();
    if (!editing(r)) g.drawString(ellipsize(fm, s.name, right - x), x, nameY);
    String b = branches.get(s.id);
    if (b != null) {
      g.setFont(Ui.font(11, false));
      FontMetrics sm = g.getFontMetrics();
      g.setColor(t.accentSoft);
      g.drawString(ellipsize(sm, "⎇ " + b, right - x), x, r.y + 6 + 17 + sm.getAscent() - 1);
    }

    Ui.dot(g, r.x + rw - 8 - 4, cy, term, pulse);
  }

  static String shortPath(String p) {
    String home = System.getProperty("user.home");
    if (p == null || p.isBlank()) return "~";
    return p.startsWith(home) ? "~" + p.substring(home.length()) : p;
  }

  static String ellipsize(FontMetrics fm, String s, int maxW) {
    if (s == null) return "";
    if (fm.stringWidth(s) <= maxW) return s;
    String e = "…";
    int n = s.length();
    while (n > 0 && fm.stringWidth(s.substring(0, n) + e) > maxW) n--;
    return s.substring(0, n) + e;
  }
}
