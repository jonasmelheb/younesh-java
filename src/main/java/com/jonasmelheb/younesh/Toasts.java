package com.jonasmelheb.younesh;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

// Notifications en bas de la fenêtre, empilées : chacune garde son texte, son bouton, sa durée et sa croix.
// La plus récente est en bas ; un nouveau message ne recouvre plus les autres.
final class Toasts {
  static final int MAX = 5;          // au-delà, la plus ancienne s'en va
  private static final int GAP = 8, MARGIN = 22, WRAP = 640;

  private final JLayeredPane layer;
  private final List<Toast> shown = new ArrayList<>();   // de la plus ancienne à la plus récente

  Toasts(JLayeredPane layer) {
    this.layer = layer;
    layer.addComponentListener(new ComponentAdapter() {
      @Override
      public void componentResized(ComponentEvent e) {
        layout();
      }
    });
  }

  // label et action peuvent être null : simple message.
  Toast show(String text, int ms, String label, Runnable action) {
    // Le même message déjà affiché est remplacé (sa durée repart) plutôt que d'être empilé deux fois.
    for (Toast t : List.copyOf(shown)) if (t.text.equals(text) && java.util.Objects.equals(t.label, label)) close(t);
    Toast t = new Toast(text, label, action);
    shown.add(t);
    layer.add(t, JLayeredPane.POPUP_LAYER);
    while (shown.size() > MAX) close(shown.get(0));
    t.timer = new Timer(ms, e -> close(t));
    t.timer.setRepeats(false);
    t.timer.start();
    layout();
    return t;
  }

  void close(Toast t) {
    if (!shown.remove(t)) return;
    if (t.timer != null) t.timer.stop();
    layer.remove(t);
    layout();
    layer.repaint();
  }

  List<Toast> shown() {
    return List.copyOf(shown);
  }

  // Après un changement de thème.
  void restyle() {
    for (Toast t : shown) t.style();
    layout();
  }

  private void layout() {
    int width = layer.getWidth(), y = layer.getHeight() - MARGIN;
    for (int i = shown.size() - 1; i >= 0; i--) {
      Toast t = shown.get(i);
      Dimension d = t.getPreferredSize();
      int w = Math.min(d.width, width - 32);
      y -= d.height;
      t.setBounds((width - w) / 2, y, w, d.height);
      t.revalidate();
      y -= GAP;
    }
  }

  final class Toast extends JPanel {
    final String text, label;
    private final Runnable action;
    private final JLabel message = new JLabel();
    private final Ui.Btn button, closeBtn = new Ui.Btn("✕", Ui.Kind.GHOST);
    Timer timer;

    Toast(String text, String label, Runnable action) {
      super(new BorderLayout(10, 0));
      this.text = text;
      this.label = label;
      this.action = action;
      message.setVerticalAlignment(SwingConstants.CENTER);
      add(message, BorderLayout.CENTER);

      JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
      buttons.setOpaque(false);
      if (label != null && action != null) {
        button = new Ui.Btn(label, Ui.Kind.NORMAL);
        button.addActionListener(e -> run());
        buttons.add(button);
        // Comme avant, un clic sur le texte lance aussi l'action.
        message.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        message.addMouseListener(new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            run();
          }
        });
      } else {
        button = null;
      }
      closeBtn.setToolTipText("Fermer");
      closeBtn.setBorder(BorderFactory.createEmptyBorder(4, 7, 4, 7));
      closeBtn.addActionListener(e -> close(this));
      buttons.add(closeBtn);
      add(buttons, BorderLayout.EAST);
      style();
    }

    void run() {
      close(this);
      action.run();
    }

    // Couleurs et police lues dans le thème courant.
    void style() {
      Theme theme = Ui.theme;
      String html = text.replace("&", "&amp;").replace("<", "&lt;");
      message.setFont(Ui.font(13, false));
      message.setForeground(theme.text);
      message.setText("<html><div style='padding:2px 4px'>" + html + "</div></html>");
      // Un long message passe sur plusieurs lignes plutôt que de dépasser de la fenêtre.
      if (message.getPreferredSize().width > WRAP - 160) message.setText("<html><div style='padding:2px 4px;width:" + (WRAP - 200) + "px'>" + html + "</div></html>");
      setOpaque(true);
      setBackground(theme.panel2);
      setBorder(BorderFactory.createCompoundBorder(
        BorderFactory.createMatteBorder(1, 3, 1, 1, theme.accent),
        BorderFactory.createEmptyBorder(6, 12, 6, 6)));
    }
  }
}
