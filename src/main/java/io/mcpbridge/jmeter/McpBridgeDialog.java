package io.mcpbridge.jmeter;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import javax.swing.Timer;

/**
 * Start/stop control for this plugin's MCP server (Overview & Server tab),
 * plus a Chat tab that shells out to a locally installed AI CLI (Claude
 * Code / Gemini CLI / opencode) the same way Insight Dashboard's own AI
 * Chat tab does \u2014 same ProcessBuilder pattern, same Windows cmd.exe
 * wrapping, same stdin-delivered prompt and timeout, reused rather than
 * reinvented.
 *
 * <p><b>A real reliability caveat, not a hypothetical one.</b> Claude Code's
 * headless/print mode (-p) has multiple documented, recent bugs where
 * configured MCP servers fail to load in time \u2014 a race where the tool
 * list is frozen before the MCP connection finishes, and a separate
 * regression where stdio-configured servers stay stuck "pending." Since
 * each -p call is a fresh process reconnecting from scratch, this isn't a
 * one-time startup hiccup; it can recur on any given message. This tab
 * mitigates it as far as is practical \u2014 for Claude Code specifically, it
 * writes an explicit, minimal --mcp-config file pointing straight at this
 * server rather than relying on whatever's already configured globally \u2014
 * but cannot guarantee the race itself is gone, since that's a bug in the
 * CLI, not something fixable from here. The chat transcript area says so
 * directly, and the suggested first test ("list your available tools") is
 * there specifically to make a silent failure visible before anything else
 * is attempted.
 */
final class McpBridgeDialog extends JDialog {

    private static final long serialVersionUID = 1L;
    private static final int DEFAULT_PORT = 7332;
    private static final int CHAT_TIMEOUT_SECONDS = 90;

    /** Floor and ceiling for a bubble's width - the actual width used is a
     *  fraction of whatever the scroll pane's real viewport width turns out
     *  to be (see bubbleWidthPx below), not this fixed number directly. A
     *  previous version used a flat 480px regardless of the dialog's real
     *  size, which overflowed past the edge and got clipped instead of
     *  wrapping whenever the actual window was narrower than that. */
    private static final int BUBBLE_MIN_WIDTH = 260;
    /** Raised from an earlier 560px cap, which was throttling bubbles to far
     *  less than the actual available width on any reasonably wide dialog -
     *  exactly the large unused gap reported. AI responses in particular
     *  often contain wide tables that genuinely benefit from the room. */
    private static final int BUBBLE_MAX_WIDTH = 1400;

    /**
     * Theme colors derived from whatever Look & Feel JMeter is actually
     * running (including dark themes) rather than hardcoded light colors -
     * the exact uiColor/isDark/blend pattern already proven in Insight
     * Dashboard, reused here rather than reinvented, and the same
     * left-border-accent + muted-background style (not bright, saturated
     * "chat app" bubbles) that project's own .chat-user/.chat-ai CSS
     * already established, for a consistent look across both plugins.
     */
    private static final class ChatTheme {
        final boolean dark;
        final Color panelBg;
        final Color text;
        final Color userBubbleBg;
        final Color userAccent;
        final Color aiBubbleBg;
        final Color aiAccent;
        final Color borderColor;
        final Color caveatBg;
        final Color caveatText;
        final Color actionItemBg;
        final Color actionItemAccent;

        ChatTheme() {
            panelBg = uiColor("Panel.background", Color.WHITE);
            dark = isDark(panelBg);
            text = dark ? new Color(0xe6, 0xe6, 0xe6) : new Color(0x22, 0x22, 0x22);
            userAccent = dark ? new Color(0x7f, 0xb3, 0xff) : new Color(0x2c, 0x3e, 0x50);
            aiAccent = dark ? new Color(0x4c, 0xaf, 0x50) : new Color(0x2e, 0x7d, 0x32);
            aiBubbleBg = dark ? new Color(0x3a, 0x3a, 0x3a) : new Color(0xee, 0xee, 0xee);
            userBubbleBg = blend(panelBg, userAccent, dark ? 0.22 : 0.10);
            borderColor = dark ? new Color(0x55, 0x55, 0x55) : new Color(0xcc, 0xcc, 0xcc);
            caveatBg = dark ? new Color(0x4a, 0x3c, 0x1a) : new Color(0xff, 0xf6, 0xe0);
            caveatText = dark ? new Color(0xe0, 0xc0, 0x80) : new Color(0x99, 0x66, 0x00);
            actionItemBg = dark ? new Color(0x4a, 0x3c, 0x1a) : new Color(0xff, 0xf8, 0xe1);
            actionItemAccent = dark ? new Color(0xff, 0xc1, 0x07) : new Color(0xff, 0xa0, 0x00);
        }
    }

    private static Color uiColor(String key, Color fallback) {
        Color c = javax.swing.UIManager.getColor(key);
        return c != null ? c : fallback;
    }

    private static boolean isDark(Color c) {
        double luminance = (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) / 255.0;
        return luminance < 0.5;
    }

    private static Color blend(Color base, Color mixIn, double amount) {
        int r = clampColor((int) Math.round(base.getRed() * (1 - amount) + mixIn.getRed() * amount));
        int g = clampColor((int) Math.round(base.getGreen() * (1 - amount) + mixIn.getGreen() * amount));
        int b = clampColor((int) Math.round(base.getBlue() * (1 - amount) + mixIn.getBlue() * amount));
        return new Color(r, g, b);
    }

    private static int clampColor(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static String toCssColor(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private final ChatTheme theme = new ChatTheme();

    private final McpServer mcpServer = new McpServer();
    /**
     * One conversation's worth of state - what used to be two standalone
     * fields (chatHistory, claudeSessionId), now one per conversation since
     * there can be several. claudeSessionId follows the same meaning as
     * before: null means "no Claude Code session established for this
     * conversation yet"; once set, every later message in this specific
     * conversation passes --resume with it to restore full context.
     */
    private static final class Conversation {
        final String id;
        String title;
        final List<Map.Entry<String, String>> history = new ArrayList<>();
        String claudeSessionId;
        long lastUpdatedMillis;

        Conversation(String id) {
            this.id = id;
            this.title = "New conversation";
            this.lastUpdatedMillis = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return title; // JList's default renderer calls this directly
        }
    }

    /** Persisted at <user.home>/.jmeter-mcpbridge/conversations.json -
     *  without this, even closing and reopening this same dialog would lose
     *  everything, since a fresh McpBridgeDialog instance is created each
     *  time the menu item is clicked. */
    private static final Path CONVERSATIONS_FILE = java.nio.file.Paths.get(
            System.getProperty("user.home"), ".jmeter-mcpbridge", "conversations.json");

    private final List<Conversation> conversations = new ArrayList<>();
    private Conversation activeConversation;

    /** Best-effort: a corrupt or unreadable file starts with an empty list
     *  rather than failing to open the dialog at all. */
    private void loadConversations() {
        try {
            if (!Files.exists(CONVERSATIONS_FILE)) {
                return;
            }
            String json = new String(Files.readAllBytes(CONVERSATIONS_FILE), StandardCharsets.UTF_8);
            Object parsed = MiniJson.parse(json);
            if (!(parsed instanceof Map)) {
                return;
            }
            Object convosObj = ((Map<?, ?>) parsed).get("conversations");
            if (!(convosObj instanceof List)) {
                return;
            }
            for (Object convoObj : (List<?>) convosObj) {
                if (!(convoObj instanceof Map)) {
                    continue;
                }
                Map<?, ?> convoMap = (Map<?, ?>) convoObj;
                Object idObj = convoMap.get("id");
                Conversation c = new Conversation(idObj == null
                        ? java.util.UUID.randomUUID().toString() : String.valueOf(idObj));
                Object titleObj = convoMap.get("title");
                if (titleObj != null) {
                    c.title = String.valueOf(titleObj);
                }
                Object sessionIdObj = convoMap.get("claudeSessionId");
                c.claudeSessionId = sessionIdObj == null ? null : String.valueOf(sessionIdObj);
                Object lastUpdatedObj = convoMap.get("lastUpdatedMillis");
                c.lastUpdatedMillis = lastUpdatedObj instanceof Number
                        ? ((Number) lastUpdatedObj).longValue() : System.currentTimeMillis();
                Object historyObj = convoMap.get("history");
                if (historyObj instanceof List) {
                    for (Object msgObj : (List<?>) historyObj) {
                        if (msgObj instanceof Map) {
                            Map<?, ?> msgMap = (Map<?, ?>) msgObj;
                            c.history.add(new AbstractMap.SimpleEntry<>(
                                    String.valueOf(msgMap.get("sender")), String.valueOf(msgMap.get("text"))));
                        }
                    }
                }
                conversations.add(c);
            }
            conversations.sort((a, b) -> Long.compare(b.lastUpdatedMillis, a.lastUpdatedMillis));
        } catch (Exception ex) {
            conversations.clear();
        }
    }

    /** Best-effort: a failed save shouldn't break the chat itself, just
     *  means this particular exchange won't survive closing the dialog. */
    private void saveConversations() {
        try {
            Files.createDirectories(CONVERSATIONS_FILE.getParent());
            List<Object> convosJson = new ArrayList<>();
            for (Conversation c : conversations) {
                Map<String, Object> convoMap = new LinkedHashMap<>();
                convoMap.put("id", c.id);
                convoMap.put("title", c.title);
                convoMap.put("claudeSessionId", c.claudeSessionId);
                convoMap.put("lastUpdatedMillis", c.lastUpdatedMillis);
                List<Object> historyJson = new ArrayList<>();
                for (Map.Entry<String, String> msg : c.history) {
                    Map<String, Object> msgMap = new LinkedHashMap<>();
                    msgMap.put("sender", msg.getKey());
                    msgMap.put("text", msg.getValue());
                    historyJson.add(msgMap);
                }
                convoMap.put("history", historyJson);
                convosJson.add(convoMap);
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("conversations", convosJson);
            Files.write(CONVERSATIONS_FILE, MiniJson.write(root).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // Best-effort only, see javadoc above.
        }
    }

    /** First ~40 characters of the first user message, single-lined -
     *  matches the "title derived from the opening message" convention
     *  Gemini/ChatGPT's own sidebars use. */
    private static String titleFromMessage(String text) {
        String oneLine = text.replaceAll("\\s+", " ").trim();
        if (oneLine.isEmpty()) {
            return "New conversation";
        }
        return oneLine.length() > 40 ? oneLine.substring(0, 40) + "\u2026" : oneLine;
    }
    private final JPanel chatMessagesPanel = new JPanel();
    private final JScrollPane chatMessagesScroll = new JScrollPane(chatMessagesPanel);
    private final JTextArea chatInputArea = new JTextArea(3, 40);
    /** Defaults to checked: without this, every MCP Bridge write tool
     *  (modify_element, add_element, etc.) is permanently non-functional in
     *  this chat, since -p/headless mode can never interactively approve a
     *  permission prompt - there's no one there to click "allow". Scoped
     *  narrowly to this server's own tools only (mcp__mcp-bridge__*), not a
     *  blanket --dangerously-skip-permissions, which would also auto-approve
     *  arbitrary Bash commands and file writes elsewhere on this machine. */
    private final javax.swing.JCheckBox allowMcpBridgeWritesCheckbox =
            new javax.swing.JCheckBox("Allow MCP Bridge tools without prompting", true);
    private final JComboBox<String> aiCliCombo = new JComboBox<>(
            new String[] {"claude -p", "gemini --yolo -p", "opencode run"});
    private final JLabel chatStatusLabel = new JLabel("Idle");
    private final JButton chatSendButton = new JButton("Send");
    private final JProgressBar chatProgressBar = new JProgressBar();

    McpBridgeDialog(Frame owner) {
        super(owner, "JMeter MCP Bridge", false);

        // Same reasoning as Correlation Studio's dialog: a JDialog's window
        // X button only hides it by default, not dispose() - relying on the
        // Close button alone would leave a running server orphaned behind a
        // hidden window. windowClosing fires either way.
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                mcpServer.stop();
            }
        });

        loadConversations();
        if (conversations.isEmpty()) {
            conversations.add(new Conversation(java.util.UUID.randomUUID().toString()));
        }
        activeConversation = conversations.get(0); // most recent, already sorted by loadConversations()

        setSize(720, 620);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout());

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Overview & Server", buildOverviewTab());
        tabs.addTab("Chat", buildChatTab());
        add(tabs, BorderLayout.CENTER);

        // Bubbles size themselves off the viewport's width at the moment
        // each one is built - rebuilding them on resize is what makes that
        // genuinely adaptive rather than correct once and stale afterward.
        // Debounced (restarts a short timer on every event) since
        // componentResized fires continuously while a resize is being
        // dragged, not once at the end.
        Timer resizeDebounce = new Timer(150, e -> renderChatTranscript());
        resizeDebounce.setRepeats(false);
        chatMessagesScroll.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                resizeDebounce.restart();
            }
        });
    }

    // ---- Overview & Server tab ----

    private JPanel buildOverviewTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));

        JEditorPane overview = new JEditorPane();
        overview.setContentType("text/html");
        overview.setEditable(false);
        overview.setOpaque(true);
        overview.setBackground(theme.panelBg);
        overview.setText(buildOverviewHtml());
        overview.setCaretPosition(0); // open scrolled to the top, not wherever the HTML cursor lands
        JScrollPane scroll = new JScrollPane(overview);
        scroll.getViewport().setBackground(theme.panelBg);
        panel.add(scroll, BorderLayout.CENTER);

        JButton toggleButton = new JButton("Start MCP Server");
        JLabel statusLabel = new JLabel("Not running");
        statusLabel.setForeground(new Color(0x80, 0x80, 0x80));
        toggleButton.addActionListener(e -> {
            if (mcpServer.isRunning()) {
                mcpServer.stop();
                toggleButton.setText("Start MCP Server");
                statusLabel.setForeground(new Color(0x80, 0x80, 0x80));
                statusLabel.setText("Not running");
            } else {
                try {
                    mcpServer.start(DEFAULT_PORT);
                    toggleButton.setText("Stop MCP Server");
                    statusLabel.setForeground(new Color(0x2e, 0x7d, 0x32));
                    statusLabel.setText("Running on http://127.0.0.1:" + mcpServer.boundPort()
                            + "/mcp \u2014 point your MCP client here");
                } catch (IOException ioe) {
                    statusLabel.setForeground(new Color(0xc6, 0x28, 0x28));
                    statusLabel.setText("Could not start: " + ioe.getMessage());
                }
            }
        });

        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> dispose());

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        bottom.add(toggleButton);
        bottom.add(statusLabel);
        bottom.add(closeButton);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    private String buildOverviewHtml() {
        String text = toCssColor(theme.text);
        String heading = toCssColor(theme.userAccent);
        String border = toCssColor(theme.borderColor);
        String codeBg = toCssColor(theme.aiBubbleBg);
        String okColor = toCssColor(theme.aiAccent);

        return "<html><head><style>"
                + "body { font-family: sans-serif; font-size: 13px; color:" + text + "; "
                + "background-color:" + toCssColor(theme.panelBg) + "; padding: 10px 14px; }"
                + "h1 { font-size: 17px; color:" + heading + "; margin-bottom: 2px; }"
                + "h2 { font-size: 13px; color:" + heading + "; margin-top: 20px; margin-bottom: 4px; "
                + "border-top: 1px solid " + border + "; padding-top: 10px; }"
                + ".tagline { color:" + text + "; margin-top: 0; margin-bottom: 14px; }"
                + "table { border-collapse: collapse; width: 100%; margin: 6px 0 4px 0; }"
                + "th { background-color:" + heading + "; color: #ffffff; text-align: left; "
                + "padding: 4px 8px; }"
                + "td { border: 1px solid " + border + "; padding: 4px 8px; vertical-align: top; }"
                + "td.tool { white-space: nowrap; font-family: monospace; background-color:" + codeBg + "; }"
                + "code { background-color:" + codeBg + "; padding: 1px 4px; }"
                + "</style></head><body>"
                + "<h1>MCP Bridge for JMeter</h1>"
                + "<p class=\"tagline\">Exposes generic JMeter tools to an MCP-capable agent "
                + "(Claude Code, Codex, opencode) over local HTTP \u2014 operating on whatever test "
                + "plan is actually open in this GUI <b>right now, not a file.</b></p>"

                + "<h2>AVAILABLE TOOLS</h2>"
                + "<table>"
                + "<tr><th>Tool</th><th>What it does</th></tr>"
                + "<tr><td class=\"tool\">get_tree</td><td>The whole live tree: paths, names, types</td></tr>"
                + "<tr><td class=\"tool\">get_element_properties</td><td>One element's actual property values</td></tr>"
                + "<tr><td class=\"tool\">add_element</td><td>Create + insert a new element</td></tr>"
                + "<tr><td class=\"tool\">modify_element</td><td>Update an existing element's properties</td></tr>"
                + "<tr><td class=\"tool\">clone_element</td><td>Duplicate a subtree (JMeter's own Copy action)</td></tr>"
                + "<tr><td class=\"tool\">move_element</td><td>Reposition an element elsewhere in the tree</td></tr>"
                + "<tr><td class=\"tool\">delete_element</td><td>Remove a subtree</td></tr>"
                + "<tr><td class=\"tool\">get_arguments</td><td>List an HTTP body/Test Plan variables' "
                + "name-value pairs (not shown by get_element_properties)</td></tr>"
                + "<tr><td class=\"tool\">modify_argument</td><td>Safely update one existing argument's "
                + "value in place, by name</td></tr>"
                + "<tr><td class=\"tool\">analyze_dynamic_candidates</td><td>Heuristic scan for likely session values</td></tr>"
                + "</table>"

                + "<h2>SAFETY NET</h2>"
                + "<p>Every write goes through JMeter's own <b>undo transaction</b> \u2014 Ctrl+Z in the "
                + "GUI reverts it as one step.</p>"

                + "<h2>SECURITY</h2>"
                + "<p>Binds to <code>127.0.0.1</code> only \u2014 never reachable over the network \u2014 "
                + "with no authentication beyond that. Any local process on this machine could call "
                + "it while running. <b>Stop it when done</b> with a session, the same as any local "
                + "debug port.</p>"

                + "<h2>WHERE THIS CAME FROM</h2>"
                + "<p>Split out from Correlation Studio's own MCP server (where these tools started) "
                + "so they're usable on their own, independent of whether you use Correlation Studio "
                + "at all.</p>"

                + "<h2>CHAT TAB</h2>"
                + "<p>Talk to a local AI CLI directly from here, instead of a separate terminal \u2014 "
                + "see that tab for an important caveat about MCP tool reliability in headless CLI "
                + "invocations before relying on it.</p>"
                + "</body></html>";
    }

    // ---- Chat tab ----

    private final JList<Conversation> conversationList = new JList<>();

    /** Rebuilds the sidebar's visible list from the conversations data list
     *  and re-selects whatever activeConversation currently is - called
     *  after anything that adds, reorders, or renames a conversation. */
    private void refreshConversationList() {
        conversationList.setListData(conversations.toArray(new Conversation[0]));
        conversationList.setSelectedValue(activeConversation, true);
    }

    private JPanel buildConversationSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout(0, 4));
        sidebar.setPreferredSize(new Dimension(200, 10));

        JButton newConvoButton = new JButton("+ New Conversation");
        newConvoButton.addActionListener(e -> {
            Conversation fresh = new Conversation(java.util.UUID.randomUUID().toString());
            conversations.add(0, fresh);
            activeConversation = fresh;
            refreshConversationList();
            renderChatTranscript();
        });
        sidebar.add(newConvoButton, BorderLayout.NORTH);

        conversationList.setBackground(theme.panelBg);
        conversationList.setForeground(theme.text);
        conversationList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            Conversation selected = conversationList.getSelectedValue();
            if (selected != null && selected != activeConversation) {
                activeConversation = selected;
                renderChatTranscript();
            }
        });
        refreshConversationList();
        JScrollPane listScroll = new JScrollPane(conversationList);
        sidebar.add(listScroll, BorderLayout.CENTER);

        JButton deleteConvoButton = new JButton("Delete Selected");
        deleteConvoButton.addActionListener(e -> deleteSelectedConversation());
        sidebar.add(deleteConvoButton, BorderLayout.SOUTH);

        return sidebar;
    }

    /** Removes the sidebar's currently selected conversation, after confirming - this
     *  permanently discards that conversation's chat history and Claude Code session
     *  link, with no undo, so it's worth confirming before persisting the change via
     *  saveConversations(). If the deleted conversation was the active one, falls back to
     *  whichever conversation is now first in the list - or, if that was the only one
     *  left, starts a fresh conversation the same way the dialog's own constructor does
     *  when there are none to load, since activeConversation is never allowed to be null
     *  (renderChatTranscript() and others dereference it directly without a null check). */
    private void deleteSelectedConversation() {
        Conversation selected = conversationList.getSelectedValue();
        if (selected == null) {
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "Delete \"" + selected.title + "\"? This can't be undone.",
                "Delete Conversation", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }
        conversations.remove(selected);
        if (selected == activeConversation) {
            if (conversations.isEmpty()) {
                conversations.add(new Conversation(java.util.UUID.randomUUID().toString()));
            }
            activeConversation = conversations.get(0);
        }
        refreshConversationList();
        saveConversations();
        renderChatTranscript();
    }

    private JPanel buildChatTab() {
        JPanel outer = new JPanel(new BorderLayout());
        javax.swing.JSplitPane split = new javax.swing.JSplitPane(
                javax.swing.JSplitPane.HORIZONTAL_SPLIT, buildConversationSidebar(), buildChatViewPanel());
        split.setDividerLocation(200);
        split.setResizeWeight(0); // sidebar stays a fixed width; the chat view gets the extra space on resize
        outer.add(split, BorderLayout.CENTER);
        return outer;
    }

    private JPanel buildChatViewPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JTextArea caveat = new JTextArea(
                "Caveat worth reading before relying on this: Claude Code's headless/print mode "
                + "(-p) has documented, recent bugs where configured MCP tools fail to load in time "
                + "\u2014 a timing race, not something this dialog can fix. Each message below is a "
                + "fresh CLI process reconnecting from scratch, so this can recur on any given "
                + "message, not just the first.\n"
                + "First test before anything else: ask \"list your available tools\" \u2014 if get_tree "
                + "and friends show up, the connection genuinely worked this time; if not, that's "
                + "the race, not a wrong question.");
        caveat.setEditable(false);
        caveat.setLineWrap(true);
        caveat.setWrapStyleWord(true);
        caveat.setFont(caveat.getFont().deriveFont(Font.ITALIC, 11f));
        caveat.setForeground(theme.caveatText);
        caveat.setBackground(theme.caveatBg);
        caveat.setOpaque(true);
        caveat.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        panel.add(caveat, BorderLayout.NORTH);

        chatMessagesPanel.setLayout(new BoxLayout(chatMessagesPanel, BoxLayout.Y_AXIS));
        chatMessagesPanel.setBackground(theme.panelBg); // matches JMeter's actual current theme
        chatMessagesPanel.setOpaque(true);
        chatMessagesScroll.setPreferredSize(new Dimension(680, 360));
        chatMessagesScroll.getVerticalScrollBar().setUnitIncrement(16);
        panel.add(chatMessagesScroll, BorderLayout.CENTER);
        renderChatTranscript(); // shows the initial placeholder hint bubble

        JPanel south = new JPanel(new BorderLayout(0, 4));

        JPanel cliRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        aiCliCombo.setEditable(true);
        aiCliCombo.setPreferredSize(new Dimension(220, aiCliCombo.getPreferredSize().height));
        cliRow.add(new JLabel("AI CLI command:"));
        cliRow.add(aiCliCombo);
        allowMcpBridgeWritesCheckbox.setToolTipText("<html>Pre-approves only this server's own tools "
                + "(mcp__mcp-bridge__*) so the AI can actually make edits in<br>headless mode, where "
                + "there's no one to click \"allow\" on a permission prompt. Everything else "
                + "(Bash,<br>file writes elsewhere) is still denied outright, not silently skipped "
                + "\u2014 unchecking this makes every<br>write tool here permanently non-functional, "
                + "since it would otherwise just hang waiting for an approval<br>that can never "
                + "come.</html>");
        cliRow.add(allowMcpBridgeWritesCheckbox);
        south.add(cliRow, BorderLayout.NORTH);

        chatInputArea.setLineWrap(true);
        chatInputArea.setWrapStyleWord(true);
        chatInputArea.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "send");
        chatInputArea.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("shift ENTER"), "insert-break");
        chatInputArea.getActionMap().put("send", new javax.swing.AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                sendChatMessage();
            }
        });
        JPanel inputBar = new JPanel(new BorderLayout(6, 0));
        inputBar.add(new JScrollPane(chatInputArea), BorderLayout.CENTER);
        chatSendButton.addActionListener(e -> sendChatMessage());
        inputBar.add(chatSendButton, BorderLayout.EAST);
        south.add(inputBar, BorderLayout.CENTER);

        chatProgressBar.setIndeterminate(true);
        chatProgressBar.setVisible(false);
        JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        statusRow.add(chatStatusLabel);
        statusRow.add(chatProgressBar);
        south.add(statusRow, BorderLayout.SOUTH);

        panel.add(south, BorderLayout.SOUTH);
        return panel;
    }

    private void sendChatMessage() {
        String question = chatInputArea.getText().trim();
        if (question.isEmpty()) {
            return;
        }
        String commandLine = String.valueOf(aiCliCombo.getEditor().getItem()).trim();
        if (commandLine.isEmpty()) {
            chatStatusLabel.setText("Set an AI CLI command first");
            return;
        }
        if (!mcpServer.isRunning()) {
            chatStatusLabel.setForeground(new Color(0xc6, 0x28, 0x28));
            chatStatusLabel.setText("Start the MCP server first (Overview & Server tab)");
            return;
        }

        // Captured by reference, not re-read from the activeConversation
        // field later - the response in done() (which runs after a real
        // async gap) must land in the conversation this message was
        // actually sent from, even if the sidebar gets switched away from
        // it in the meantime. The sidebar is also disabled below for the
        // duration, so this is belt-and-suspenders rather than the only
        // protection.
        final Conversation targetConversation = activeConversation;
        if (targetConversation.history.isEmpty()) {
            targetConversation.title = titleFromMessage(question);
        }

        chatInputArea.setText("");
        targetConversation.history.add(new AbstractMap.SimpleEntry<>("user", question));
        renderChatTranscript();
        refreshConversationList();

        chatSendButton.setEnabled(false);
        chatInputArea.setEnabled(false);
        conversationList.setEnabled(false);
        chatStatusLabel.setForeground(new Color(0x1a, 0x73, 0xe8));
        chatProgressBar.setVisible(true);

        long startMillis = System.currentTimeMillis();
        Timer thinkingTimer = new Timer(500, null);
        thinkingTimer.addActionListener(e ->
                chatStatusLabel.setText("Thinking\u2026 (" + (System.currentTimeMillis() - startMillis) / 1000 + "s)"));
        thinkingTimer.start();

        int port = mcpServer.boundPort();
        boolean allowMcpBridgeWrites = allowMcpBridgeWritesCheckbox.isSelected();

        new SwingWorker<String, Void>() {
            private Exception error;

            @Override
            protected String doInBackground() {
                try {
                    return invokeAiCli(commandLine, question, port, allowMcpBridgeWrites, targetConversation);
                } catch (Exception ex) {
                    error = ex;
                    return null;
                }
            }

            @Override
            protected void done() {
                thinkingTimer.stop();
                chatProgressBar.setVisible(false);
                chatSendButton.setEnabled(true);
                chatInputArea.setEnabled(true);
                conversationList.setEnabled(true);
                if (error != null) {
                    chatStatusLabel.setForeground(new Color(0xc6, 0x28, 0x28));
                    chatStatusLabel.setText("Error");
                    String msg = error.getMessage() == null ? "(no details)" : error.getMessage();
                    targetConversation.history.add(
                            new AbstractMap.SimpleEntry<>("ai", "Failed to get a response:\n" + msg));
                } else {
                    chatStatusLabel.setForeground(null);
                    chatStatusLabel.setText("Idle");
                    String response;
                    try {
                        response = get();
                    } catch (Exception ignored) {
                        response = "(no response)";
                    }
                    targetConversation.history.add(new AbstractMap.SimpleEntry<>(
                            "ai", response == null ? "(empty response)" : response));
                }
                targetConversation.lastUpdatedMillis = System.currentTimeMillis();
                conversations.sort((a, b) -> Long.compare(b.lastUpdatedMillis, a.lastUpdatedMillis));
                refreshConversationList();
                saveConversations();
                if (targetConversation == activeConversation) {
                    renderChatTranscript();
                }
            }
        }.execute();
    }

    /**
     * Rebuilds every message bubble from activeConversation.history \u2014 simplest correct
     * approach given how infrequently this fires (once per sent message,
     * not per keystroke), so there's no real cost to just redrawing
     * everything rather than appending incrementally.
     */
    private void renderChatTranscript() {
        chatMessagesPanel.removeAll();

        if (activeConversation.history.isEmpty()) {
            chatMessagesPanel.add(alignedBubble("ai", "Ask something below \u2014 e.g. "
                    + "\"list your available tools\", \"show me the test plan structure\", "
                    + "\"add a Response Assertion under 02_Login checking for 'Cockpit'\"."));
        } else {
            for (Map.Entry<String, String> entry : activeConversation.history) {
                chatMessagesPanel.add(alignedBubble(entry.getKey(), entry.getValue()));
                if (entry.getKey().equals("ai") && hasNumberedList(entry.getValue())) {
                    chatMessagesPanel.add(buildApplyFixesRow());
                }
            }
        }
        chatMessagesPanel.add(javax.swing.Box.createVerticalGlue());

        chatMessagesPanel.revalidate();
        // Repainting just the child panel isn't always enough to clear a
        // stale paint from the scroll pane's own viewport buffer after
        // removeAll() rebuilds the children - repainting the viewport and
        // the scroll pane itself too is the standard defensive fix for that
        // class of leftover-paint artifact.
        chatMessagesPanel.repaint();
        chatMessagesScroll.getViewport().repaint();
        chatMessagesScroll.repaint();
        // Scroll to the bottom after layout catches up - invokeLater, not
        // immediate, since the scrollbar's new maximum isn't established
        // until after this revalidate actually runs.
        javax.swing.SwingUtilities.invokeLater(() -> {
            javax.swing.JScrollBar vbar = chatMessagesScroll.getVerticalScrollBar();
            vbar.setValue(vbar.getMaximum());
            chatMessagesScroll.repaint();
        });
    }

    /** Roughly 92% of whatever the scroll pane's real viewport width
     *  currently is, clamped between BUBBLE_MIN_WIDTH and BUBBLE_MAX_WIDTH -
     *  adapts to the dialog's actual size instead of assuming a fixed one.
     *  Falls back to a reasonable default before the viewport has been laid
     *  out yet (width still 0 the very first time this runs). */
    private int bubbleWidthPx() {
        int viewportWidth = chatMessagesScroll.getViewport().getWidth();
        if (viewportWidth <= 0) {
            viewportWidth = 600; // not yet laid out - a reasonable guess for this dialog's own setSize
        }
        int target = (int) (viewportWidth * 0.92);
        return Math.max(BUBBLE_MIN_WIDTH, Math.min(BUBBLE_MAX_WIDTH, target));
    }

    /** One message, right-aligned for "user", left-aligned for "ai" - the
     *  left/right differentiation that was actually asked for. Colors are a
     *  muted, theme-aware left-border-accent style (matching Insight
     *  Dashboard's own established .chat-user/.chat-ai look) rather than
     *  bright, saturated "consumer chat app" colors, and are computed from
     *  JMeter's actual current Look & Feel so they never clash against a
     *  dark theme the way a hardcoded light palette would. Swing's HTML
     *  renderer has no border-radius, so these are plain rectangular
     *  bubbles with color/padding/alignment/left-border, not rounded
     *  corners. */
    private JPanel alignedBubble(String sender, String text) {
        boolean isUser = "user".equals(sender);
        Color bubbleBg = isUser ? theme.userBubbleBg : theme.aiBubbleBg;
        Color accent = isUser ? theme.userAccent : theme.aiAccent;

        int widthPx = bubbleWidthPx();

        WrappingHtmlPane bubble = new WrappingHtmlPane(widthPx);
        bubble.setOpaque(true);
        bubble.setBackground(bubbleBg);
        bubble.setText("<html><head><style>"
                + "body { margin:0; padding:8px 10px; "
                + "font-family:sans-serif; font-size:12px; color:" + toCssColor(theme.text) + "; "
                + "border-left: 4px solid " + toCssColor(accent) + "; "
                + "word-wrap: break-word; overflow-wrap: break-word; }"
                + "a, code { color:" + toCssColor(theme.text) + "; }"
                + "table { border-collapse: collapse; max-width: 100%; }"
                + "th, td { border: 1px solid " + toCssColor(theme.borderColor) + "; padding: 3px 6px; }"
                + "th { background-color: " + toCssColor(accent) + "; color: #ffffff; }"
                + ".action-item { background-color: " + toCssColor(theme.actionItemBg) + "; "
                + "border-left: 3px solid " + toCssColor(theme.actionItemAccent) + "; "
                + "padding: 4px 8px; margin: 2px 0; }"
                + "</style></head><body>" + MarkdownLite.toHtml(text) + "</body></html>");

        JPanel row = new JPanel(new FlowLayout(isUser ? FlowLayout.RIGHT : FlowLayout.LEFT, 8, 4));
        row.setOpaque(false);
        row.add(bubble);
        row.setAlignmentX(0f);
        return row;
    }

    /**
     * The actual fix for the clipped-text bug: the previous version read
     * getPreferredSize() ONCE, immediately after setSize(), while the
     * dialog was still being constructed and not yet shown - at that point
     * Swing's HTML layout engine hadn't performed a real layout pass yet,
     * so the cached preferred size was wrong (premature), and FlowLayout
     * then gave the component that wrong, too-small size permanently,
     * clipping the wrapped text rather than giving it room to wrap
     * correctly.
     *
     * This subclass instead recomputes its preferred size FRESH every time
     * Swing's layout system actually asks for one (getPreferredSize() is
     * called repeatedly during real revalidate/layout passes, which happen
     * after the dialog is actually visible) - fixing the width first, then
     * asking the HTML view for its size at that width, every single call,
     * rather than trusting a value computed once at the wrong moment.
     */
    private static final class WrappingHtmlPane extends JEditorPane {
        private static final long serialVersionUID = 1L;
        private final int fixedWidth;

        WrappingHtmlPane(int fixedWidth) {
            this.fixedWidth = fixedWidth;
            setContentType("text/html");
            setEditable(false);
        }

        /**
         * JComponent.getPreferredSize() for multi-line HTML content is a
         * documented regression since Java 9: it returns a single-line
         * height regardless of how much the content actually wraps, no
         * matter what an override around it tries to do with setSize()
         * first - confirmed independently before writing this, not assumed,
         * after two earlier attempts built on top of that broken layer both
         * failed the same way. The fix is to bypass JComponent entirely and
         * ask the text rendering engine's own View object directly - the
         * same technique used for the equivalent, older JLabel HTML-sizing
         * problem (via BasicHTML's root view) and confirmed still in active
         * use in at least one real, shipped Swing chat UI for this exact
         * JEditorPane case.
         */
        @Override
        public Dimension getPreferredSize() {
            setSize(fixedWidth, Integer.MAX_VALUE);
            javax.swing.text.View rootView = getUI().getRootView(this);
            rootView.setSize(fixedWidth, 0);
            float height = rootView.getPreferredSpan(javax.swing.text.View.Y_AXIS);
            return new Dimension(fixedWidth, (int) Math.ceil(height) + 2);
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }
    }

    private JPanel buildApplyFixesRow() {
        JButton applyButton = new JButton("\u2713 Approve & Apply");
        applyButton.setToolTipText("<html>Fills the message box with a follow-up request asking the "
                + "AI to apply what it just suggested, using its<br>already-available tools \u2014 "
                + "review it and press Send yourself, nothing is sent automatically.</html>");
        applyButton.addActionListener(e -> {
            chatInputArea.setText("Go ahead and apply the fixes you just suggested, one at a time, "
                    + "using your available tools, and confirm what actually changed afterward.");
            chatInputArea.requestFocusInWindow();
        });

        JButton rejectButton = new JButton("\u2717 Reject");
        rejectButton.setToolTipText("<html>Fills the message box with a decline, so you can add your "
                + "own reason before sending \u2014 nothing is sent automatically.</html>");
        rejectButton.addActionListener(e -> {
            chatInputArea.setText("Don't apply those changes \u2014 ");
            chatInputArea.requestFocusInWindow();
            chatInputArea.setCaretPosition(chatInputArea.getText().length());
        });

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        row.setOpaque(false);
        row.add(applyButton);
        row.add(rejectButton);
        row.setAlignmentX(0f);
        return row;
    }

    /** Signal for "this AI message has actionable, numbered suggestions" -
     *  reuses the same pattern MarkdownLite itself uses to recognize a
     *  numbered list line, checked against the raw (pre-HTML) message text. */
    private static boolean hasNumberedList(String text) {
        for (String line : text.split("\n")) {
            if (line.trim().matches("^\\d+\\.\\s+.*")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs the AI CLI the same way Insight Dashboard's own AI Chat tab does
     * (ProcessBuilder, Windows cmd.exe wrapping, stdin-delivered prompt,
     * timeout) with two additions for Claude Code specifically:
     *
     * <p>1. Writes an explicit, minimal --mcp-config temp file pointing
     * straight at this server (confirmed schema: {"mcpServers":{"name":
     * {"type":"http","url":"..."}}} \u2014 the "type":"http" field is
     * required; Claude Code silently drops a server missing it, no tools,
     * no error shown) rather than relying on whatever's already configured
     * globally, which is both more explicit and, per --strict-mcp-config,
     * avoids loading an entire interactive setup (hooks, skills, CLAUDE.md)
     * on every single message.
     *
     * <p>2. Session continuity: a -p call is otherwise a fresh process with
     * zero memory of anything said in an earlier message \u2014 confirmed as
     * the actual cause of a reported "forgets everything between messages"
     * bug, not a hypothetical. The first message in a conversation passes
     * --session-id with a freshly generated UUID (a caller-chosen session
     * id, confirmed supported directly rather than needing to parse a
     * generated one out of --output-format json); every later message
     * passes --resume with that same UUID, which restores the full prior
     * conversation \u2014 confirmed this also works under -p specifically, not
     * only in interactive mode.
     *
     * <p>3. Permission pre-approval (when allowMcpBridgeWrites is true):
     * headless mode can never interactively approve a permission prompt \u2014
     * there's no one there to click "allow" \u2014 so without this, every
     * write tool (modify_element, add_element, etc.) is permanently
     * non-functional, confirmed as the actual cause of a reported "write
     * access hasn't been granted" block, not a hypothetical. Fixed with
     * {@code --allowedTools "mcp__mcp-bridge__*"} (the glob is only valid
     * directly after a literal {@code mcp__<server>__} prefix, confirmed
     * against official docs) paired with {@code --permission-mode dontAsk},
     * which denies anything NOT on that list outright rather than letting
     * it hang waiting for an approval that can never come. Scoped narrowly
     * to this server's own tools only \u2014 not a blanket
     * --dangerously-skip-permissions, which would also auto-approve
     * arbitrary Bash commands and file writes elsewhere on this machine.
     *
     * <p>Other CLIs (Gemini, opencode) rely on whatever persistent MCP
     * config was set up earlier (gemini mcp add, etc.) \u2014 no equivalent
     * per-invocation --mcp-config flag confirmed for them, so nothing
     * injected here for those. Session continuity and permission
     * pre-approval above are Claude Code only too; no equivalent mechanism
     * confirmed for the others, so their conversations remain stateless
     * and read-only-in-practice for now.
     */
    private String invokeAiCli(String commandLine, String question, int port,
                                boolean allowMcpBridgeWrites, Conversation conversation) throws Exception {
        List<String> userTokens = new ArrayList<>(Arrays.asList(commandLine.trim().split("\\s+")));
        String executableName = userTokens.get(0);

        Path tempMcpConfig = null;
        if ("claude".equals(executableName)) {
            tempMcpConfig = Files.createTempFile("mcpbridge-", ".mcp.json");
            String json = "{\"mcpServers\":{\"mcp-bridge\":{\"type\":\"http\",\"url\":"
                    + "\"http://127.0.0.1:" + port + "/mcp\"}}}";
            Files.write(tempMcpConfig, json.getBytes(StandardCharsets.UTF_8));
            userTokens.add("--mcp-config");
            userTokens.add(tempMcpConfig.toString());
            userTokens.add("--strict-mcp-config");

            if (conversation.claudeSessionId == null) {
                conversation.claudeSessionId = java.util.UUID.randomUUID().toString();
                userTokens.add("--session-id");
                userTokens.add(conversation.claudeSessionId);
            } else {
                userTokens.add("--resume");
                userTokens.add(conversation.claudeSessionId);
            }

            if (allowMcpBridgeWrites) {
                userTokens.add("--allowedTools");
                userTokens.add("mcp__mcp-bridge__*");
                userTokens.add("--permission-mode");
                userTokens.add("dontAsk");
            }
        }
        userTokens.add(question);

        List<String> command = new ArrayList<>();
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("win")) {
            // Same reasoning as Insight Dashboard's own AI Chat: npm-installed
            // CLIs are a .cmd shim on Windows, which ProcessBuilder won't
            // resolve the way a shell typing the bare name would.
            command.add("cmd.exe");
            command.add("/c");
            command.addAll(userTokens);
        } else {
            command.addAll(userTokens);
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<String> future = executor.submit(() -> {
                    try (OutputStream stdin = process.getOutputStream()) {
                        // Nothing to write to stdin - the question is already
                        // passed as a CLI argument above, unlike Insight
                        // Dashboard's report-plus-question pattern which needs
                        // stdin for the (much larger) report text.
                    }
                    StringBuilder output = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            output.append(line).append('\n');
                        }
                    }
                    int exit = process.waitFor();
                    if (exit != 0) {
                        throw new IOException("Exited with code " + exit + ". Output:\n" + output);
                    }
                    return output.toString().trim();
                });

                try {
                    return future.get(CHAT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (TimeoutException te) {
                    process.destroyForcibly();
                    throw new IOException("Timed out after " + CHAT_TIMEOUT_SECONDS + "s. If this is "
                            + "the first run, try running \"" + commandLine + "\" directly in a "
                            + "terminal first to complete any one-time login/setup.");
                }
            } finally {
                executor.shutdownNow();
            }
        } finally {
            if (tempMcpConfig != null) {
                try {
                    Files.deleteIfExists(tempMcpConfig);
                } catch (IOException ignored) {
                    // Best-effort cleanup only - a leftover temp file isn't harmful.
                }
            }
        }
    }
}
