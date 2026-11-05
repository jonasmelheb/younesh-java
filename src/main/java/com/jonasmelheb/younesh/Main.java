package com.jonasmelheb.younesh;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Insets;
import java.util.Map;

public final class Main {
  public static void main(String[] args) {
    System.setProperty("apple.awt.application.name", "Younesh");
    // Sous Windows, Java lit le réglage ClearType du système (y compris l'ordre des sous-pixels de l'écran).
    if (!System.getProperty("os.name", "").toLowerCase().contains("win")) System.setProperty("awt.useSystemAAFontSettings", "lcd");
    // La console de Windows (ConPTY du système), comme la version Electron : la console intégrée à pty4j
    // (OpenConsole.exe copié dans le dossier temporaire) affichait l'invite PowerShell sur un fond blanc.
    System.setProperty("com.pty4j.windows.disable.bundled.conpty", "true");
    SwingUtilities.invokeLater(Younesh::new);
  }

  // FlatLaf aux couleurs du thème, pour les champs, listes et menus qui ne sont pas dessinés à la main.
  static void setupLaf(Theme t) {
    FlatLaf.setGlobalExtraDefaults(Map.of(
      "@background", Theme.hex(t.panel),
      "@foreground", Theme.hex(t.text),
      "@accentColor", Theme.hex(t.accent),
      "@selectionBackground", Theme.hex(t.active)));
    if (t.dark) FlatDarkLaf.setup(); else FlatLightLaf.setup();
    UIManager.put("defaultFont", Ui.font(13, false));
    UIManager.put("Component.arc", 8);
    UIManager.put("Button.arc", 8);
    UIManager.put("TextComponent.arc", 8);
    UIManager.put("Component.focusWidth", 0);
    UIManager.put("Component.innerFocusWidth", 0);
    UIManager.put("Component.borderColor", t.border);
    UIManager.put("Component.focusedBorderColor", t.accent);
    UIManager.put("TextField.background", t.bg);
    UIManager.put("TextField.placeholderForeground", t.muted2);
    UIManager.put("ComboBox.background", t.bg);
    UIManager.put("ComboBox.buttonBackground", t.bg);
    UIManager.put("ComboBox.buttonEditableBackground", t.bg);
    UIManager.put("ComboBox.popupBackground", t.panel2);
    UIManager.put("Spinner.background", t.bg);
    UIManager.put("Spinner.buttonBackground", t.bg);
    UIManager.put("CheckBox.icon.selectedBackground", t.accent);
    UIManager.put("CheckBox.icon.checkmarkColor", t.onAccent);
    UIManager.put("ScrollBar.width", 8);
    UIManager.put("ScrollBar.thumbArc", 999);
    UIManager.put("ScrollBar.thumbInsets", new Insets(1, 1, 1, 1));
    UIManager.put("ScrollBar.thumb", t.scroll);
    UIManager.put("ScrollBar.hoverThumbColor", t.borderHover);
    UIManager.put("ScrollBar.track", t.panel);
    UIManager.put("ScrollBar.showButtons", false);
    UIManager.put("PopupMenu.background", t.panel2);
    UIManager.put("PopupMenu.borderColor", t.borderStrong);
    UIManager.put("PopupMenu.borderCornerRadius", 10);
    UIManager.put("MenuItem.selectionBackground", t.hover2);
    UIManager.put("MenuItem.selectionForeground", t.text);
    UIManager.put("MenuItem.selectionArc", 6);
    UIManager.put("MenuItem.selectionInsets", new Insets(0, 0, 0, 0));
    UIManager.put("List.selectionBackground", t.active);
    UIManager.put("List.selectionForeground", t.text);
    UIManager.put("ToolTip.background", t.panel2);
    UIManager.put("ToolTip.foreground", t.text);
    UIManager.put("ToolTip.border", javax.swing.BorderFactory.createCompoundBorder(
      javax.swing.BorderFactory.createLineBorder(t.borderStrong), javax.swing.BorderFactory.createEmptyBorder(4, 8, 4, 8)));
    UIManager.put("SplitPane.background", t.border);
    UIManager.put("SplitPaneDivider.draggingColor", t.accent);
    UIManager.put("SplitPaneDivider.style", "plain");
    UIManager.put("SplitPaneDivider.gripDotCount", 0);
    UIManager.put("TitlePane.unifiedBackground", true);
    UIManager.put("TitlePane.background", t.panel);
    UIManager.put("TitlePane.inactiveBackground", t.panel);
    UIManager.put("TitlePane.foreground", t.muted);
  }
}
