package io.mcpbridge.jmeter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deliberately minimal Markdown-to-HTML converter, based on the one
 * already proven in Insight Dashboard's own chat/observations rendering,
 * extended here with two things that project's didn't need: pipe-table
 * support (Claude Code's analysis responses use these heavily, as seen in
 * real chat output) and a highlighted callout treatment for numbered list
 * items specifically, since those are consistently where an AI response's
 * actual action items live ("1. Fix the Logout assertions", "2. Read
 * credentials from a CSV", ...), not just incidental enumeration.
 *
 * Output is kept to HTML that Swing's JEditorPane (HTMLEditorKit, roughly
 * an HTML 3.2 / CSS1-level renderer) displays correctly \u2014 no border-radius,
 * no flexbox, nothing past basic CSS1 properties (background-color, border,
 * padding, margin, width) which this renderer does support.
 */
final class MarkdownLite {

    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern NUMBERED_ITEM = Pattern.compile("^\\d+\\.\\s+.*");
    /** A pipe-table separator row, e.g. "|---|:---:|---:|" - cells of only
     *  dashes/colons/whitespace, used to confirm the row above it was a
     *  header rather than a regular data row. */
    private static final Pattern TABLE_SEPARATOR_ROW = Pattern.compile("^\\|?[\\s:|-]+\\|?$");

    private MarkdownLite() {
    }

    static String toHtml(String markdown) {
        if (markdown == null || markdown.trim().isEmpty()) {
            return "<p><i>(no response)</i></p>";
        }

        StringBuilder html = new StringBuilder();
        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);

        boolean inUl = false;
        boolean inOl = false;
        boolean inTable = false;
        List<String> pendingHeaderCells = null; // buffered first table row, until we
                                                 // know whether the next line is a separator
        StringBuilder paragraph = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String rawLine = lines[i];
            String trimmed = rawLine.trim();

            // Must start with "|" AND have at least 2 pipes total (leading +
            // at least one column separator) - deliberately includes
            // separator rows ("|---|---|"), since the state machine below
            // needs to see and consume those itself, right after a buffered
            // header row, rather than have them excluded here and fall
            // through to incorrectly close the table one line too early.
            boolean isTableRow = trimmed.startsWith("|") && looksLikeTableRow(trimmed);

            if (isTableRow) {
                flushParagraph(html, paragraph);
                inUl = closeIf(html, inUl, "</ul>\n");
                inOl = closeIf(html, inOl, "</ol>\n");

                if (!inTable) {
                    // Starting a new table: buffer this row as a possible header
                    // until we see whether the next line is a separator row.
                    inTable = true;
                    pendingHeaderCells = splitTableRow(trimmed);
                    continue;
                }

                if (pendingHeaderCells != null) {
                    // This is the line right after the buffered first row.
                    if (TABLE_SEPARATOR_ROW.matcher(trimmed).matches()) {
                        html.append("<table border=\"1\" cellpadding=\"4\" cellspacing=\"0\">\n<tr>");
                        for (String cell : pendingHeaderCells) {
                            html.append("<th>").append(inline(escapeHtml(cell))).append("</th>");
                        }
                        html.append("</tr>\n");
                        pendingHeaderCells = null;
                        continue;
                    }
                    // No separator followed - not actually a header table structure;
                    // emit the buffered row as a plain data row, then fall through
                    // to handle the current line as a plain data row too.
                    html.append("<table border=\"1\" cellpadding=\"4\" cellspacing=\"0\">\n");
                    appendTableDataRow(html, pendingHeaderCells);
                    pendingHeaderCells = null;
                }
                appendTableDataRow(html, splitTableRow(trimmed));
                continue;
            } else if (inTable) {
                // Table ended - close it (flushing any still-buffered single row
                // first, for a one-row "table" with no separator at all).
                if (pendingHeaderCells != null) {
                    html.append("<table border=\"1\" cellpadding=\"4\" cellspacing=\"0\">\n");
                    appendTableDataRow(html, pendingHeaderCells);
                    pendingHeaderCells = null;
                }
                html.append("</table>\n");
                inTable = false;
            }

            if (trimmed.isEmpty()) {
                flushParagraph(html, paragraph);
                continue;
            }

            if (trimmed.equals("---") || trimmed.equals("***")) {
                flushParagraph(html, paragraph);
                inUl = closeIf(html, inUl, "</ul>\n");
                inOl = closeIf(html, inOl, "</ol>\n");
                html.append("<hr/>\n");
                continue;
            }

            int headingLevel = 0;
            while (headingLevel < trimmed.length() && trimmed.charAt(headingLevel) == '#') {
                headingLevel++;
            }
            if (headingLevel > 0 && headingLevel <= 6 && trimmed.length() > headingLevel
                    && trimmed.charAt(headingLevel) == ' ') {
                flushParagraph(html, paragraph);
                inUl = closeIf(html, inUl, "</ul>\n");
                inOl = closeIf(html, inOl, "</ol>\n");
                String text = inline(escapeHtml(trimmed.substring(headingLevel + 1).trim()));
                html.append('<').append('h').append(headingLevel).append('>').append(text)
                        .append("</h").append(headingLevel).append(">\n");
                continue;
            }

            if (trimmed.startsWith("* ") || trimmed.startsWith("- ")) {
                flushParagraph(html, paragraph);
                inOl = closeIf(html, inOl, "</ol>\n");
                if (!inUl) {
                    html.append("<ul>\n");
                    inUl = true;
                }
                html.append("<li>").append(inline(escapeHtml(trimmed.substring(2).trim()))).append("</li>\n");
                continue;
            }

            if (NUMBERED_ITEM.matcher(trimmed).matches()) {
                flushParagraph(html, paragraph);
                inUl = closeIf(html, inUl, "</ul>\n");
                if (!inOl) {
                    html.append("<ol>\n");
                    inOl = true;
                }
                String text = trimmed.replaceFirst("^\\d+\\.\\s+", "");
                // Action-item callout: numbered items in these responses
                // consistently are the actual suggested fixes, not incidental
                // enumeration - a highlighted div makes them visually distinct
                // from surrounding prose. Uses a CSS class rather than inline
                // colors, since this converter has no idea what theme (dark
                // or light JMeter Look & Feel) the caller is rendering into -
                // the caller defines ".action-item" in its own <style> block
                // with colors that actually fit.
                html.append("<li><div class=\"action-item\">")
                        .append(inline(escapeHtml(text)))
                        .append("</div></li>\n");
                continue;
            }

            // Plain text line: close any open list, accumulate into the current paragraph.
            inUl = closeIf(html, inUl, "</ul>\n");
            inOl = closeIf(html, inOl, "</ol>\n");
            if (paragraph.length() > 0) {
                paragraph.append(' ');
            }
            paragraph.append(trimmed);
        }
        flushParagraph(html, paragraph);
        closeIf(html, inUl, "</ul>\n");
        closeIf(html, inOl, "</ol>\n");
        if (inTable) {
            if (pendingHeaderCells != null) {
                html.append("<table border=\"1\" cellpadding=\"4\" cellspacing=\"0\">\n");
                appendTableDataRow(html, pendingHeaderCells);
            }
            html.append("</table>\n");
        }

        return html.toString();
    }

    /** A line is only treated as a table row if it has at least one internal
     *  "|" (not just a literal pipe character used for some other reason in
     *  prose) - requiring the line to both start AND end with "|", OR
     *  contain at least two "|" characters, keeps this from misfiring on a
     *  stray "a | b" in ordinary text. */
    private static boolean looksLikeTableRow(String trimmed) {
        int count = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) == '|') {
                count++;
            }
        }
        return count >= 2;
    }

    private static List<String> splitTableRow(String trimmed) {
        String body = trimmed;
        if (body.startsWith("|")) {
            body = body.substring(1);
        }
        if (body.endsWith("|")) {
            body = body.substring(0, body.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String cell : body.split("\\|", -1)) {
            cells.add(cell.trim());
        }
        return cells;
    }

    private static void appendTableDataRow(StringBuilder html, List<String> cells) {
        html.append("<tr>");
        for (String cell : cells) {
            html.append("<td>").append(inline(escapeHtml(cell))).append("</td>");
        }
        html.append("</tr>\n");
    }

    private static boolean closeIf(StringBuilder html, boolean open, String closingTag) {
        if (open) {
            html.append(closingTag);
        }
        return false;
    }

    private static void flushParagraph(StringBuilder html, StringBuilder paragraph) {
        if (paragraph.length() > 0) {
            html.append("<p>").append(inline(escapeHtml(paragraph.toString()))).append("</p>\n");
            paragraph.setLength(0);
        }
    }

    private static String inline(String escapedText) {
        String withBold = BOLD.matcher(escapedText).replaceAll("<b>$1</b>");
        return INLINE_CODE.matcher(withBold).replaceAll("<code>$1</code>");
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
