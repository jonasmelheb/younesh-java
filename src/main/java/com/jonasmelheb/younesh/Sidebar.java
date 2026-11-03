package com.jonasmelheb.younesh;

import javax.swing.JComponent;
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
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// L'arbre des dossiers et des discussions, dessiné à la main : léger et entièrement sous contrôle.
final class Sidebar extends JComponent {
  interface Actions {
    void activate(Store.Session s);
    void toggle(Store.Folder f);
    void menu(Store.Folder f, Store.Session s, Point p);
    void rename(Store.Folder f, Store.Session s);
    void close(Store.Session s);
    void moved();
  }

  private record Row(Store.Folder folder, Store.Session session, boolean nested) {
  }

  private static final int FOLDER_H = 30, SESSION_H = 30, PAD = 8;
  static final Color ORANGE = new Color(0xff9f43);

  private final Store store;
  private final Map<String, Term> terms;
  private final Map<String, String> branches;
  private final Actions actions;
  private Theme theme;
  private String filter = "";
  private final List<Row> rows = new ArrayList<>();
  private final List<Integer> tops = new ArrayList<>();
  private int hover = -1;
  private boolean pulse;

  private int pressed = -1, dropIndex = -1;
  private boolean dragging, dropInside;
  private Point pressPoint;

  Sidebar(Store store, Map<String, Term> terms, Map<String, String> branches, Theme theme, Actions actions) {
    this.store = store;
    this.terms = terms;
    this.branches = branches;
    this.theme = theme;
    this.actions = actions;
    setToolTipText("");
    MouseAdapter mouse = new MouseAdapter() {
      @Override
      public void mouseMoved(MouseEvent e) {
        setHover(rowAt(e.getY()));
      }

      @Override
      public void mouseExited(MouseEvent e) {
        setHover(-1);
      }

      @Override
      public void mousePressed(MouseEvent e) {
        int i = rowAt(e.getY());
        if (i < 0) return;
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
        if (e.getClickCount() == 2) {
          actions.rename(r.folder, r.session);
          pressed = -1;
        } else if (r.session != null) {
          actions.activate(r.session);
        } else {
          actions.toggle(r.folder);
        }
      }

      @Override
      public void mouseDragged(MouseEvent e) {
        if (pressed < 0 || !filter.isEmpty()) return;
        if (!dragging && pressPoint.distance(e.getPoint()) < 5) return;
        dragging = true;
        computeDrop(e.getY());
        repaint();
      }

      @Override
      public void mouseReleased(MouseEvent e) {
        if (e.isPopupTrigger()) {
          int i = rowAt(e.getY());
          if (i >= 0) actions.menu(rows.get(i).folder, rows.get(i).session, e.getPoint());
        }
        if (dragging) drop();
        dragging = false;
        pressed = dropIndex = -1;
        repaint();
      }
    };
    addMouseListener(mouse);
    addMouseMotionListener(mouse);
    new Timer(700, e -> {
      pulse = !pulse;
      if (terms.values().stream().anyMatch(t -> t.attention)) repaint();
    }).start();
    rebuild();
  }

  void setTheme(Theme t) {
    theme = t;
    repaint();
  }

  void setFilter(String f) {
    filter = norm(f);
    rebuild();
  }

  static String norm(String s) {
    return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
  }

  private boolean matches(Store.Session s) {
    return filter.isEmpty() || norm(s.name).contains(filter);
  }

  void rebuild() {
    rows.clear();
    tops.clear();
    for (Store.Folder f : store.folders) {
      List<Store.Session> inside = store.sessions.stream().filter(s -> f.id.equals(s.folderId) && matches(s)).toList();
      if (!filter.isEmpty() && inside.isEmpty() && !norm(f.name).contains(filter)) continue;
      rows.add(new Row(f, null, false));
      if (!f.collapsed || !filter.isEmpty()) inside.forEach(s -> rows.add(new Row(f, s, true)));
    }
    store.sessions.stream().filter(s -> store.folder(s.folderId) == null && matches(s)).forEach(s -> rows.add(new Row(null, s, false)));
    int y = 4;
    for (Row r : rows) {
      tops.add(y);
      y += r.session == null ? FOLDER_H : SESSION_H;
    }
    setPreferredSize(new Dimension(10, y + 8));
    revalidate();
    repaint();
  }

  private int rowAt(int y) {
    for (int i = 0; i < rows.size(); i++) {
      int h = rows.get(i).session == null ? FOLDER_H : SESSION_H;
      if (y >= tops.get(i) && y < tops.get(i) + h) return i;
    }
    return -1;
  }

  private void setHover(int i) {
    if (i != hover) {
      hover = i;
      repaint();
    }
  }

  @Override
  public String getToolTipText(MouseEvent e) {
    int i = rowAt(e.getY());
    if (i < 0 || rows.get(i).session == null) return null;
    Store.Session s = rows.get(i).session;
    return s.cwd == null || s.cwd.isEmpty() ? null : s.cwd;
  }

  // Cible du glisser-déposer : sur un dossier = dedans, entre deux lignes = à cet endroit.
  private void computeDrop(int y) {
    int i = rowAt(y);
    if (i < 0) {
      dropIndex = rows.size();
      dropInside = false;
      return;
    }
    Row r = rows.get(i);
    int h = r.session == null ? FOLDER_H : SESSION_H;
    int rel = y - tops.get(i);
    Row dragged = rows.get(pressed);
    if (dragged.session != null && r.session == null && rel > h / 4 && rel < h * 3 / 4) {
      dropIndex = i;
      dropInside = true;
    } else {
      dropIndex = rel < h / 2 ? i : i + 1;
      dropInside = false;
    }
  }

  private void drop() {
    if (pressed < 0 || dropIndex < 0) return;
    Row dragged = rows.get(pressed);
    if (dragged.session != null) {
      Store.Session s = dragged.session;
      String folderId;
      Store.Session before = null;
      if (dropInside) {
        folderId = rows.get(dropIndex).folder.id;
      } else if (dropIndex < rows.size()) {
        Row target = rows.get(dropIndex);
        if (target.session != null) {
          folderId = target.nested ? target.folder.id : null;
          before = target.session;
        } else {
          // Juste au-dessus d'un dossier : fin du dossier précédent, ou hors dossier.
          Row prev = dropIndex > 0 ? rows.get(dropIndex - 1) : null;
          folderId = prev == null ? null : prev.folder != null ? prev.folder.id : null;
        }
      } else {
        folderId = null;
      }
      if (before == s) return;
      store.sessions.remove(s);
      s.folderId = folderId;
      int at = before == null ? -1 : store.sessions.indexOf(before);
      if (at < 0) store.sessions.add(s); else store.sessions.add(at, s);
    } else {
      Store.Folder f = dragged.folder;
      Store.Folder before = null;
      for (int i = dropIndex; i < rows.size(); i++) {
        if (rows.get(i).session == null) {
          before = rows.get(i).folder;
          break;
        }
      }
      if (before == f) return;
      store.folders.remove(f);
      int at = before == null ? -1 : store.folders.indexOf(before);
      if (at < 0) store.folders.add(f); else store.folders.add(at, f);
    }
    actions.moved();
  }

  static Color colorOf(String hex, Color fallback) {
    try {
      return hex == null || hex.isEmpty() ? fallback : Theme.c(hex);
    } catch (RuntimeException e) {
      return fallback;
    }
  }

  @Override
  protected void paintComponent(Graphics g0) {
    Graphics2D g = (Graphics2D) g0.create();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
    g.setColor(theme.panel);
    g.fillRect(0, 0, getWidth(), getHeight());
    Font base = getFont().deriveFont(13f);
    Font small = base.deriveFont(11f);
    Font bold = base.deriveFont(Font.BOLD);
    int w = getWidth();

    for (int i = 0; i < rows.size(); i++) {
      Row r = rows.get(i);
      int y = tops.get(i);
      int h = r.session == null ? FOLDER_H : SESSION_H;
      int x0 = PAD;
      int rw = w - PAD * 2;

      if (r.session == null) {
        Store.Folder f = r.folder;
        Color fc = colorOf(f.color, theme.accent);
        if (i == hover && !dragging) fill(g, theme.hover, x0, y + 1, rw, h - 2);
        if (dragging && dropInside && dropIndex == i) {
          fill(g, mix(fc, theme.panel, 0.22f), x0, y + 1, rw, h - 2);
        }
        int cx = x0 + 10, cy = y + h / 2;
        chevron(g, cx, cy, f.collapsed && filter.isEmpty(), theme.muted);
        folderIcon(g, x0 + 22, cy - 6, fc);
        g.setFont(bold);
        g.setColor(theme.text);
        long count = store.sessions.stream().filter(s -> f.id.equals(s.folderId)).count();
        String cnt = String.valueOf(count);
        FontMetrics fm = g.getFontMetrics();
        int textX = x0 + 44;
        int cntW = g.getFontMetrics(small).stringWidth(cnt);
        text(g, f.name, textX, cy, w - PAD - 14 - cntW - textX);
        g.setFont(small);
        g.setColor(theme.muted2);
        g.drawString(cnt, x0 + rw - 8 - cntW, cy + fm.getAscent() / 2 - 2);
      } else {
        Store.Session s = r.session;
        Term t = terms.get(s.id);
        boolean active = s.id.equals(store.activeId);
        int x = r.nested ? x0 + 16 : x0;
        int sw = x0 + rw - x;
        if (t != null && t.attention) {
          fill(g, mix(ORANGE, theme.panel, pulse ? 0.26f : 0.14f), x, y + 1, sw, h - 2);
        } else if (active) {
          fill(g, theme.active, x, y + 1, sw, h - 2);
        } else if (i == hover && !dragging) {
          fill(g, theme.hover, x, y + 1, sw, h - 2);
        }
        if (s.color != null && !s.color.isEmpty()) {
          g.setColor(colorOf(s.color, theme.accent));
          g.fillRoundRect(x + 2, y + 8, 3, h - 16, 3, 3);
        }
        int cy = y + h / 2;
        g.setColor(dotColor(t));
        g.fillOval(x + 11, cy - 3, 7, 7);
        int textX = x + 26;
        boolean claude = "claude".equals(s.kind);
        if (claude) {
          g.setFont(base);
          g.setColor(new Color(0xd97757));
          g.drawString("✳", textX, cy + g.getFontMetrics().getAscent() / 2 - 1);
          textX += g.getFontMetrics().stringWidth("✳") + 6;
        }
        String branch = branches.get(s.id);
        int branchW = 0;
        if (branch != null) {
          g.setFont(small);
          branch = ellipsize(g.getFontMetrics(), branch, Math.max(40, sw / 3));
          branchW = g.getFontMetrics().stringWidth(branch) + 10;
        }
        g.setFont(active ? base.deriveFont(Font.BOLD) : base);
        g.setColor(active ? theme.text : mix(theme.text, theme.panel, 0.85f));
        text(g, s.name, textX, cy, x + sw - 8 - branchW - textX);
        if (branch != null) {
          g.setFont(small);
          g.setColor(theme.muted2);
          g.drawString(branch, x + sw - branchW, cy + g.getFontMetrics().getAscent() / 2 - 1);
        }
      }
    }

    if (dragging && !dropInside && dropIndex >= 0) {
      int y = dropIndex < rows.size() ? tops.get(dropIndex) : rows.isEmpty() ? 4 : tops.get(rows.size() - 1) + SESSION_H;
      g.setColor(theme.accent);
      g.setStroke(new BasicStroke(2f));
      g.drawLine(PAD + 4, y, w - PAD - 4, y);
    }
    g.dispose();
  }

  private Color dotColor(Term t) {
    if (t == null) return theme.muted2;
    if (t.attention) return ORANGE;
    if (t.exited) return new Color(0xff6b6b);
    if (t.unread) return theme.accent;
    if (t.running) return new Color(0x4ade80);
    return theme.muted2;
  }

  private static void fill(Graphics2D g, Color c, int x, int y, int w, int h) {
    g.setColor(c);
    g.fillRoundRect(x, y, w, h, 10, 10);
  }

  private void text(Graphics2D g, String s, int x, int cy, int maxW) {
    FontMetrics fm = g.getFontMetrics();
    g.drawString(ellipsize(fm, s, maxW), x, cy + fm.getAscent() / 2 - 1);
  }

  static String ellipsize(FontMetrics fm, String s, int maxW) {
    if (s == null) return "";
    if (fm.stringWidth(s) <= maxW) return s;
    String e = "…";
    int n = s.length();
    while (n > 0 && fm.stringWidth(s.substring(0, n) + e) > maxW) n--;
    return s.substring(0, n) + e;
  }

  private static void chevron(Graphics2D g, int cx, int cy, boolean collapsed, Color c) {
    Path2D p = new Path2D.Float();
    if (collapsed) {
      p.moveTo(cx - 2, cy - 4);
      p.lineTo(cx + 2, cy);
      p.lineTo(cx - 2, cy + 4);
    } else {
      p.moveTo(cx - 4, cy - 2);
      p.lineTo(cx, cy + 2);
      p.lineTo(cx + 4, cy - 2);
    }
    g.setColor(c);
    g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    g.draw(p);
  }

  private static void folderIcon(Graphics2D g, int x, int y, Color c) {
    g.setColor(c);
    g.fillRoundRect(x, y, 7, 4, 2, 2);
    g.fillRoundRect(x, y + 2, 16, 11, 4, 4);
  }

  static Color mix(Color a, Color b, float t) {
    return new Color(
      Math.round(a.getRed() * t + b.getRed() * (1 - t)),
      Math.round(a.getGreen() * t + b.getGreen() * (1 - t)),
      Math.round(a.getBlue() * t + b.getBlue() * (1 - t)));
  }
}
