# Changelog and design notes

This is the project's running development log, newest entries first, kept verbatim from the original README. For an overview, see [README.md](README.md).

---

# MCP Bridge for JMeter

A localhost-only [MCP](https://modelcontextprotocol.io) (Model Context
Protocol) server exposing generic JMeter test-plan operations — reading
and editing whatever's currently open in the GUI — to any MCP-capable
agent (Claude Code, Codex, opencode). Split out of
[Correlation Studio](https://github.com/ajinsudhir/jmeter-correlationstudio),
where the first eight of these tools started, so they're usable on their own,
independent of whether you use Correlation Studio or any other plugin —
based on your own use case, not bundled into a larger tool you may not
need.

## Conversations can now be deleted

A **Delete Selected** button under the conversation sidebar, alongside
the existing **+ New Conversation** button at the top - a symmetric
way to remove an old or unwanted conversation, which previously had no
way to go away once created. Confirms first, since deleting permanently
discards that conversation's chat history and its linked Claude Code
session with no undo.

If the deleted conversation was the one currently open, the view falls
back to whichever conversation is now first in the list - or, if that
was the last one, a fresh new conversation is started automatically,
the same way this dialog already handles having none to load on first
open. The active conversation is never allowed to become nothing at
all, since other parts of this dialog assume there's always one open.

## Fixed: the toolbar icon was appearing in the wrong place

Reported directly from a screenshot: the icon was landing at the far
right of the toolbar, after JMeter's own status indicators (elapsed
time, warnings, thread count), rather than near the other icons.
`toolbar.add()` appends to the absolute end of the toolbar's children -
past a flexible spacer JMeter uses to push those indicators to the far
right. Fixed by finding the index right after the last button-like
component already present and inserting there instead, landing grouped
with the other icons. Same fix applied across this plugin and its two
siblings.

## Fixed: the toolbar icon wasn't actually appearing

Two classic Swing gotchas, not anything JMeter-specific: `toolbar.add()`
alone has no visible effect on a container that's already visible and
already laid out (the main toolbar is, well before this code runs) -
fixed with an explicit `revalidate()`/`repaint()` right after adding
the button. As a defensive second layer, the whole attempt now runs via
`SwingUtilities.invokeLater(...)` rather than synchronously, in case
`getMenuItemsAtLocation()` is ever called before JMeter's own toolbar
is fully constructed. Same fix applied identically across this plugin
and its two siblings (Correlation Studio / JMeter Workbench and Insight
Dashboard), which all used the same pattern and had the same bug.

## A toolbar shortcut, alongside the Tools menu item

Opening the control dialog no longer requires going through the Tools
menu every time - a small icon now appears directly on JMeter's own
main toolbar too, the same way JMeter's own built-in actions (new,
open, save, run) work. JMeter has no official, documented extension
point for third-party toolbar buttons the way `MenuCreator` is one for
menu items, so this uses the same workaround a few other published
JMeter plugins already rely on: reaching into
`GuiPackage.getInstance().getMainToolbar()` directly and appending a
button to it. That's reading JMeter's own internal GUI object, not a
contract JMeter promises to keep stable, so it's wrapped defensively -
if it ever breaks in a future JMeter release, the Tools menu item keeps
working regardless, since that one is registered through the real,
guaranteed SPI. Same approach (and the same reasoning) as the sibling
plugin, Correlation Studio / JMeter Workbench, which added the
identical shortcut for the same reason.

## Install

Drop `jmeter-mcpbridge.jar` into `<JMeter home>/lib/ext/`, then restart
JMeter. **Tools → SmartBlitz - MCP Bridge…** (or the new toolbar icon) opens
the control dialog.

## What it exposes

Eight tools, all operating on JMeter's actual, live test-plan tree —
whatever's open in the GUI right now, not a file:

| Tool | What it does |
|---|---|
| `get_tree` | The whole live tree: every element's path, name, type, enabled state |
| `get_element_properties` | One element's actual property values, generic across every element type |
| `add_element` | Create + insert a new element under a given parent |
| `modify_element` | Update an existing element's properties in place |
| `clone_element` | Duplicate a subtree, via JMeter's own Copy action — the same code path its GUI Copy/Paste uses |
| `move_element` | Reposition an element elsewhere in the tree |
| `delete_element` | Remove a subtree |
| `get_arguments` | List the name/value pairs of an HTTP sampler's body or a Test Plan's user-defined variables — not shown by `get_element_properties`, which doesn't recurse into these nested, structured properties |
| `modify_argument` | Safely update one existing argument's value in place, by name — where `modify_element`'s generic property-setting would corrupt the whole structured collection if used on one of these |
| `analyze_dynamic_candidates` | A conservative heuristic scan for values that look session-specific and repeat across elements — explicitly **not** a confirmed correlation, since there's no real server response here to confirm one |

## Node addressing

A node is addressed by a slash-separated path of child indices from the
root, e.g. `"0/2/1"` — simple and unambiguous, unlike addressing by name
(JMeter doesn't require unique element names, so two samplers both named
"HTTP Request" are common and genuinely ambiguous to address by name
alone). `get_tree` returns both the path and a human-readable name/type
for every node, so there's always a label next to the exact path to use
next.

A path can go stale once the tree changes — the same characteristic as
holding a plain list index across a mutation elsewhere, not a protocol
bug — so re-fetch the tree with `get_tree` after any write before
addressing more nodes from it.

## Safety net

Every write goes through `GuiPackage.beginUndoTransaction()`/
`endUndoTransaction()` — JMeter's own undo mechanism, the same boundary a
single GUI action gets. A bad edit from an agent is one Ctrl+Z away from
gone, not something to trust a rewind script for.

`move_element` also refuses to move a node into its own subtree, since
that would leave the tree in a structurally invalid state (a node as its
own ancestor) rather than just a logical mistake.

## `add_element`'s curated element types

A deliberately small, curated set — not every JMeter element — and
confidence varies across it, worth being direct about: HTTP Request,
Regular Expression Extractor, Response Assertion, JSR223 PostProcessor,
JSR223 Assertion, Loop Controller, and Transaction Controller are each
backed by an official Javadoc reference or something already proven
working in Correlation Studio's own code. **JSR223 PreProcessor's GUI
class is the one entry checked with less confidence** than the rest — if
adding one fails, that's the first place to look. Either way,
`add_element` reports a clear error naming the type and the underlying
exception, not a bare stack trace.

## Transport, security, and what this deliberately doesn't do

**Transport:** Streamable HTTP (a single POST endpoint), not stdio.
Stdio assumes the connecting client launches the server itself as a
fresh subprocess; that doesn't fit here, since this "server" is the
already-running JMeter GUI holding live test-plan state, not something
spawned on demand. Point an MCP client at
`http://127.0.0.1:<port>/mcp` — the port is shown once started (7332 by
default — deliberately different from Correlation Studio's 7331, so
both can run at once in the same JMeter process without a port
conflict — falling back to an OS-assigned port if that's taken).

**Security:** binds to 127.0.0.1 only, never reachable over the network,
with no authentication beyond that in this version. Any local process on
the same machine could call it while running. Stop it when done with a
session, the same as any local debug port.

**What this deliberately doesn't do:** drive JMeter's actual test engine
to rerun a request and read a real response back — that needs actually
executing samplers programmatically and capturing results, a materially
bigger and riskier piece than reading/editing the test plan, left out of
this first version on purpose. These tools read and edit a test plan;
they don't run it.

## Chat tab — talk to a local AI CLI from inside the dialog

Rather than opening a separate terminal, **SmartBlitz - MCP Bridge… → Chat** lets
you type a message and get a response from a locally installed AI CLI
(Claude Code, Gemini CLI, opencode), reusing the same ProcessBuilder
pattern Correlation Studio's sibling plugin (Insight Dashboard) already
uses for its own AI Chat tab — Windows `cmd.exe` wrapping for npm-
installed CLIs, stdin/stdout handling, a 90-second timeout.

**A real caveat, not a hypothetical one.** Claude Code's headless/print
mode (`-p`) has multiple documented, recent bugs where configured MCP
tools fail to load in time — a race where the tool list is frozen
before the MCP connection finishes establishing, and a separate
regression where stdio-configured servers stay stuck "pending." Since
every message here is a fresh CLI process reconnecting from scratch,
this can recur on any given message, not just the first one. For
Claude Code specifically, this tab mitigates it as far as practical: it
writes an explicit, minimal `--mcp-config` file pointing straight at
this server (with `--strict-mcp-config`, so nothing else loads) rather
than relying on whatever's configured globally — but it cannot
guarantee the underlying race is gone, since that's a bug in the CLI
itself, not something fixable from a config file. **First thing to try
in the chat box: "list your available tools."** If the tools show
up, the connection genuinely worked that time; if not, that's the
race surfacing, not a wrong question.

Gemini CLI and opencode rely on whatever MCP configuration you already
set up for them (`gemini mcp add`, etc.) — no equivalent per-invocation
config-injection flag was confirmed for those, so nothing is injected
for them the way it is for Claude Code.

## `get_arguments` / `modify_argument` — a real gap, found in actual use

An AI session correctly refused to edit a hardcoded username in an HTTP
sampler's login body, explaining that the body is "stored as a list"
and it wouldn't risk editing it with `modify_element`. That reasoning
was right, not overly cautious: an HTTP sampler's body/parameters (and
a Test Plan's user-defined variables, built the same way) are a
structured `Arguments` collection, not a plain string.
`modify_element`'s generic `setProperty()` always creates a flat
`StringProperty` — calling it on one of these would silently replace
the whole structured collection with an incompatible value, corrupting
the sampler rather than actually editing it.

`get_arguments` lists what's actually in one of these (name/value
pairs) — `get_element_properties` doesn't recurse into them, so this is
the only way to see what's there. `modify_argument` then updates one
existing argument's value in place via `Argument.setValue()`, leaving
the collection's structure untouched — confirmed via `Argument`/
`HTTPArgument`'s actual API (and a real diff from JMeter's own
codebase using this exact pattern) before writing either tool, not
assumed. Works generically for both plain `Argument` and `HTTPArgument`
(HTTP bodies specifically), since the latter extends the former — no
new dependency on JMeter's HTTP protocol module needed for either tool.

## Overview tab layout

Restructured from one long, undifferentiated paragraph into a real HTML
layout: a title, a bold one-line summary, a proper table for the eight
tools (instead of manually-aligned text columns), and clearly separated,
headed sections (Safety net, Security, Where this came from, Chat tab) —
matching the same theme-aware color scheme as the Chat tab, so it
doesn't clash with JMeter's actual current theme either.

## Chat tab fixes

**Bubble text was getting clipped** rather than wrapping — this took
three attempts to actually fix, and the first two were addressing
symptoms rather than the real cause, worth being direct about. The
actual root cause: `JComponent.getPreferredSize()` for multi-line HTML
content is a **documented regression in Java 9 and later** — it returns
a single-line height regardless of how much the content really wraps,
no matter what an override around it tries to do with `setSize()`
first. Confirmed independently (not assumed) after the first two
attempts, both built on top of that broken layer, failed the same way.

The actual fix bypasses `JComponent` entirely and asks the text
rendering engine's own `View` object directly — `getUI().getRootView()`,
then `View.setSize()` and `View.getPreferredSpan()` — the same technique
long used for the equivalent, older JLabel HTML-sizing problem, and
confirmed still in active use in at least one real, shipped Swing chat
UI for this exact `JEditorPane` case. Bubbles also still rebuild on
resize, so width continues to track the dialog's actual current size.

**Bubbles were also using far less width than was actually available** —
a hard cap of 560px regardless of how wide the real dialog was, leaving
a large unused gap on any reasonably sized window. Raised to a 1400px
cap at 92% of the viewport's real width (up from 70%), so bubbles — AI
responses with tables especially — use close to the full available
space instead of an arbitrarily narrow slice of it.

## Conversation history sidebar

A left-side panel, matching the Gemini/ChatGPT convention, lists every
past conversation and lets you switch between them — **+ New
Conversation** at the top starts a fresh one without losing whatever
came before it (unlike the earlier version of this feature, which just
wiped the single chat it had). Each conversation gets a title from its
first message, automatically, the same way those tools derive one.

**Persisted to disk** at `~/.jmeter-mcpbridge/conversations.json` —
necessary, not just nice to have, since this dialog is recreated fresh
every time the Tools menu item is clicked. Without persistence, closing
and reopening this same dialog within the same JMeter session would
already lose everything, let alone surviving a JMeter restart.

Each conversation tracks its own Claude Code session independently, so
switching between them resumes the right context, not whichever one was
open last. Sent while a reply is still coming back, the conversation
list is disabled for the duration — switching away mid-reply and back
is still safe (the response is captured against the exact conversation
it was sent from, not wherever the sidebar happens to be pointed when
it arrives), but disabling it avoids the confusing moment of watching a
different conversation's "Thinking…" indicator than the one actually
visible.

## Conversation memory (Claude Code)

**Every message was forgetting everything said before it** — a real,
reported bug, not a hypothetical. Each chat message launches a fresh
`claude -p` process, and that process has no memory of anything from an
earlier message unless explicitly told to resume it, which this plugin
never did until now. Saying "go ahead" after a suggested plan got a
response with no idea what "go ahead" referred to, because nothing ever
told the new process there *was* a prior plan.

Fixed with Claude Code's own session continuity, confirmed against
several independent sources before implementing: the first message in a
conversation passes `--session-id` with a freshly generated UUID,
establishing a session Claude Code tracks on its own side; every later
message passes `--resume` with that same UUID, which restores the full
prior conversation — confirmed to work under `-p` print mode
specifically, not only interactive mode. A **New Conversation** button
next to the CLI command field clears both the visible chat and the
underlying session, for intentionally starting fresh.

This is Claude Code specific — no equivalent mechanism confirmed for
Gemini CLI or opencode, so those remain stateless between messages for
now.

## Write tools being blocked ("write access hasn't been granted")

**Every write tool (`modify_element`, `add_element`, etc.) was
permanently non-functional** — a real, reported block, not a
hypothetical. Headless/print mode can never interactively approve a
permission prompt, since there's no one there to click "allow" — so
without explicit pre-approval, Claude Code correctly refuses to run a
write tool rather than silently hanging forever waiting for a response
that can never come.

Fixed with a checkbox next to the CLI command field, **"Allow MCP
Bridge tools without prompting,"** checked by default. When checked, it
adds `--allowedTools "mcp__mcp-bridge__*"` paired with
`--permission-mode dontAsk` — confirmed exact syntax against official
Claude Code docs and multiple independent sources before implementing.
This pre-approves only this server's own tools; it denies everything
else (Bash, file writes elsewhere) outright rather than prompting or
silently skipping it. Deliberately not a blanket
`--dangerously-skip-permissions`, which would also auto-approve
arbitrary shell commands and file edits elsewhere on your machine —
this only ever touches the test plan through the same undo-wrapped
tools documented above.

## Approve / Reject

Any AI message with a numbered list (the same signal Apply Suggested
Fixes already used) now shows two buttons: **✓ Approve & Apply** fills
the message box with a go-ahead; **✗ Reject** fills it with a decline
you can add your own reason to. Neither sends automatically — both just
prefill the box so you can review or edit before pressing Send
yourself, consistent with every other write-affecting action in this
plugin.

## Chat tab look and feel

Messages render as individual bubbles rather than one plain-text block:
your own messages right-aligned, the AI's left-aligned, each with its own
muted background tint and a colored left-border accent — left/right
differentiation, same idea as a typical chat app, but colors chosen for
a professional tool rather than bright, saturated "consumer chat app"
bubbles. Markdown in the AI's response (tables, bold, inline code,
headings, lists) renders as actual formatted HTML via a small bundled
Markdown-to-HTML converter, not raw pipe/asterisk characters.

**Every chat color is computed from JMeter's actual current Look & Feel**,
not hardcoded — the same `uiColor`/`isDark`/`blend` pattern already
proven in Insight Dashboard's own theme-aware rendering, reused here
rather than reinvented. A dark JMeter theme gets a dark-appropriate
palette automatically; a light theme gets a light one. The first version
of this hardcoded light WhatsApp-green colors regardless of JMeter's own
theme, which looked badly out of place against a dark Look & Feel — fixed
by detecting the theme and deriving colors from it instead.

**Numbered lists get a highlighted callout treatment** — a tinted
background with a colored left border (also theme-aware) — since in
practice a numbered list in these responses is consistently where the
actual suggested fixes live, not incidental enumeration. Any AI message
containing one also gets an **Apply Suggested Fixes** button underneath,
which fills the message box with a follow-up request asking the AI to
act on what it just suggested using its already-available tools —
reviewed and sent by you, never sent automatically.

One real rendering constraint worth knowing: Swing's built-in HTML
renderer is roughly HTML 3.2/CSS1-level, so bubbles are rectangular —
background color, border, padding, and alignment all work, but there's
no `border-radius` for actually rounded corners.

## Connecting a client

**Claude Code:**
```bash
claude mcp add --transport http mcp-bridge http://127.0.0.1:7332/mcp
```
Then `/mcp` inside a session to confirm it's connected.

**Gemini CLI:**
```bash
gemini mcp add --transport http mcp-bridge http://127.0.0.1:7332/mcp
```
or edit `~/.gemini/settings.json`:
```json
{
  "mcpServers": {
    "mcp-bridge": {
      "httpUrl": "http://127.0.0.1:7332/mcp"
    }
  }
}
```
Then `/mcp` to check status.

The server has to already be running (via the dialog's Start MCP Server
button) before either client connects.

## A limitation worth being direct about

The message shapes here (`initialize`, `tools/list`, `tools/call`,
JSON-RPC error codes) were checked carefully against the current MCP
specification and several independent server implementations before
writing any of this. What couldn't be verified the same way is an
actual, live round-trip against a real MCP client — that needs an actual
agent actually connecting. If something in the handshake doesn't work on
the first try with your particular client, that's the most likely place
to look.
