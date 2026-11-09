package io.mcpbridge.jmeter;

import java.awt.Color;
import java.awt.Component;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JToolBar;
import javax.swing.MenuElement;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.plugin.MenuCreator;

/**
 * Adds "SmartBlitz - MCP Bridge..." to JMeter's Tools menu, and a best-effort
 * matching icon on JMeter's own main toolbar \u2014 the "SmartBlitz -" prefix
 * follows the same convention this plugin's sibling (now "JMeter
 * Workbench", formerly Correlation Studio) uses, matching BlazeMeter's own
 * "bzm -" pattern, so every menu item from the same author's plugins stays
 * recognizable and sorted together. The menu item is registered via the
 * standard {@code META-INF/services/org.apache.jmeter.gui.plugin.MenuCreator}
 * SPI file, the same mechanism JMeter's own Tools-menu entries use.
 *
 * The toolbar icon has no equivalent official SPI - JMeter doesn't expose
 * a documented, stable extension point for third-party toolbar buttons -
 * so it uses the same workaround as the sibling plugin's own toolbar
 * button: {@code GuiPackage.getInstance().getMainToolbar()}, the actual,
 * live {@code JToolBar} instance, with a button appended directly. Wrapped
 * defensively throughout, since this reads JMeter's own internal GUI
 * object rather than a contract JMeter promises to keep stable - if it
 * ever breaks in a future JMeter release, the Tools menu item keeps
 * working regardless.
 */
public class McpBridgeMenuCreator implements MenuCreator {

    private static volatile boolean toolbarButtonAdded;

    @Override
    public JMenuItem[] getMenuItemsAtLocation(MENU_LOCATION location) {
        if (location != MENU_LOCATION.TOOLS) {
            return new JMenuItem[0];
        }
        JMenuItem item = new JMenuItem("SmartBlitz - MCP Bridge\u2026");
        item.addActionListener(e -> openDialog());

        addToolbarButtonOnce();

        return new JMenuItem[] {item};
    }

    private void openDialog() {
        Frame owner = GuiPackage.getInstance() != null ? GuiPackage.getInstance().getMainFrame() : null;
        new McpBridgeDialog(owner).setVisible(true);
    }

    /** Best-effort only, and guarded to run exactly once - getMenuItemsAtLocation() is
     *  called once per MENU_LOCATION value, and would otherwise append a duplicate button
     *  to the toolbar each time it's called. Tolerates GuiPackage or the toolbar not being
     *  available by simply not adding the button, rather than letting any of this
     *  interfere with menu construction itself. */
    private void addToolbarButtonOnce() {
        if (toolbarButtonAdded) {
            return;
        }
        toolbarButtonAdded = true; // claim it now regardless of outcome - this only ever gets one attempt
        javax.swing.SwingUtilities.invokeLater(() -> {
        try {
            GuiPackage guiPackage = GuiPackage.getInstance();
            if (guiPackage == null) {
                return;
            }
            JToolBar toolbar = guiPackage.getMainToolbar();
            if (toolbar == null) {
                return;
            }
            JButton button = new JButton(new McpBridgeToolbarIcon());
            button.setToolTipText("SmartBlitz - MCP Bridge\u2026");
            button.addActionListener(e -> openDialog());
            int insertIndex = findInsertionIndex(toolbar);
            toolbar.add(new JToolBar.Separator(), insertIndex);
            toolbar.add(button, insertIndex + 1);
            toolbar.revalidate();
            toolbar.repaint();
        } catch (Exception ignored) {
            // JMeter's internal toolbar structure didn't match what was expected here -
            // the Tools menu item is the guaranteed way in regardless.
        }
        });
    }

    /** Finds the index right after the last button-like component already in the toolbar
     *  (JMeter's own icons, or another of this author's plugins' toolbar button already
     *  inserted the same way) - so a new button lands grouped with the existing icons
     *  rather than appended past whatever flexible spacer JMeter places before its own
     *  far-right status indicators (elapsed time, warnings, thread count). Falls back to
     *  0 (the very start) if no button-like component is found at all, rather than
     *  throwing - an unusual toolbar layout shouldn't prevent the button from appearing
     *  somewhere. */
    private static int findInsertionIndex(JToolBar toolbar) {
        int lastButtonIndex = -1;
        for (int i = 0; i < toolbar.getComponentCount(); i++) {
            if (toolbar.getComponent(i) instanceof javax.swing.AbstractButton) {
                lastButtonIndex = i;
            }
        }
        return lastButtonIndex + 1;
    }

    /** A small, code-drawn toolbar icon (two linked circles - a "bridge"/connection
     *  motif) - no external image asset to bundle or load, which this environment has no
     *  way to actually compile-test, unlike a plain Java2D drawing routine that can be
     *  read and reasoned about directly. */
    private static final class McpBridgeToolbarIcon implements Icon {
        private static final Color ACCENT = new Color(0x43, 0xa0, 0x47);

        @Override
        public int getIconWidth() {
            return 16;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.translate(x, y);
            g2.setColor(ACCENT);
            g2.setStroke(new java.awt.BasicStroke(2f));
            g2.drawOval(1, 4, 8, 8);
            g2.drawOval(7, 4, 8, 8);
            g2.dispose();
        }
    }

    @Override
    public JMenu[] getTopLevelMenus() {
        return new JMenu[0];
    }

    @Override
    public boolean localeChanged(MenuElement menu) {
        return false;
    }

    @Override
    public void localeChanged() {
        // Static English labels only — nothing to update on locale change.
    }
}
