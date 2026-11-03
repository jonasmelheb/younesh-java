package com.jonasmelheb.younesh;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.util.Map;

public final class Main {
  public static void main(String[] args) {
    System.setProperty("apple.awt.application.name", "Younesh");
    System.setProperty("awt.useSystemAAFontSettings", "lcd");
    SwingUtilities.invokeLater(Younesh::new);
  }

  // FlatLaf aux couleurs du thème : fond, texte et accent.
  static void setupLaf(Theme t) {
    FlatLaf.setGlobalExtraDefaults(Map.of(
      "@background", Theme.hex(t.panel),
      "@foreground", Theme.hex(t.text),
      "@accentColor", Theme.hex(t.accent),
      "@selectionBackground", Theme.hex(t.active)));
    if (t.dark) FlatDarkLaf.setup(); else FlatLightLaf.setup();
    UIManager.put("Component.arc", 8);
    UIManager.put("Button.arc", 8);
    UIManager.put("TextComponent.arc", 8);
    UIManager.put("Component.focusWidth", 1);
    UIManager.put("Button.background", t.panel2);
    UIManager.put("Button.borderColor", t.border);
    UIManager.put("Button.hoverBackground", t.hover);
    UIManager.put("TextField.background", t.panel2);
    UIManager.put("Component.borderColor", t.border);
    UIManager.put("ScrollBar.width", 8);
    UIManager.put("ScrollBar.thumbArc", 999);
    UIManager.put("ScrollBar.thumbInsets", new java.awt.Insets(2, 2, 2, 2));
    UIManager.put("ScrollBar.track", t.panel);
    UIManager.put("PopupMenu.borderCornerRadius", 8);
    UIManager.put("MenuItem.selectionBackground", t.hover);
    UIManager.put("MenuItem.selectionForeground", t.text);
    UIManager.put("Menu.selectionBackground", t.hover);
    UIManager.put("Menu.selectionForeground", t.text);
    UIManager.put("List.selectionBackground", t.active);
    UIManager.put("List.selectionForeground", t.text);
    UIManager.put("TitlePane.unifiedBackground", true);
    UIManager.put("SplitPaneDivider.style", "plain");
  }
}
