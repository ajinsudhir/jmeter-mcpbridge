package io.mcpbridge.jmeter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

import javax.swing.SwingUtilities;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A minimal, localhost-only MCP (Model Context Protocol) server exposing
 * generic JMeter test-plan operations \u2014 reading and editing whatever's
 * currently open in the GUI \u2014 to any MCP-capable agent (Claude Code, Codex,
 * opencode). Not tied to any other plugin's own workflow; this is the
 * generic layer split out on its own so it's usable independent of anything
 * else, per its own use case rather than bundled into a larger tool.
 *
 * <p>Protocol layer, threading model, transport choice, and security posture
 * are identical to Correlation Studio's own MCP server, from which this was
 * split \u2014 see that project's McpServer for the full reasoning behind each
 * of those choices. The one structural difference: every tool here calls
 * {@link JMeterTreeTools} (and so {@code GuiPackage}) directly, with no
 * bridge interface in between, since none of these tools depend on another
 * dialog's own state the way Correlation Studio's candidate-table tools did.
 *
 * <p>Message shapes (initialize, tools/list, tools/call, JSON-RPC error
 * codes) were verified against the current MCP specification and multiple
 * independent server implementations before writing this, not assumed.
 */
final class McpServer {

    /** The spec revision these message shapes were verified against. */
    static final String PROTOCOL_VERSION = "2025-06-18";

    private HttpServer httpServer;
    private int boundPort = -1;

    /**
     * Starts listening on 127.0.0.1. Tries preferredPort first; if that's
     * already in use, falls back to an OS-assigned ephemeral port rather than
     * failing outright \u2014 either way, {@link #boundPort()} reports whichever
     * port actually ended up in use.
     */
    synchronized void start(int preferredPort) throws IOException {
        if (httpServer != null) {
            throw new IllegalStateException("Already running on port " + boundPort);
        }
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", preferredPort), 0);
        } catch (BindException portTaken) {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        }
        server.createContext("/mcp", this::handle);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "jmeter-mcpbridge");
            t.setDaemon(true); // never keep the JMeter JVM alive on its own
            return t;
        }));
        server.start();
        this.httpServer = server;
        this.boundPort = server.getAddress().getPort();
    }

    synchronized void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
            boundPort = -1;
        }
    }

    synchronized boolean isRunning() {
        return httpServer != null;
    }

    synchronized int boundPort() {
        return boundPort;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendPlain(exchange, 405, "POST only");
                return;
            }
            String body = readBody(exchange.getRequestBody());

            Object parsed;
            try {
                parsed = MiniJson.parse(body);
            } catch (IllegalArgumentException badJson) {
                writeRpcError(exchange, null, -32700, "Parse error: " + badJson.getMessage());
                return;
            }
            if (!(parsed instanceof Map)) {
                writeRpcError(exchange, null, -32600, "Invalid Request: expected a JSON object");
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> request = (Map<String, Object>) parsed;
            Object id = request.get("id");
            Object methodObj = request.get("method");
            if (!(methodObj instanceof String)) {
                writeRpcError(exchange, id, -32600, "Invalid Request: missing \"method\"");
                return;
            }
            String method = (String) methodObj;
            Object paramsObj = request.get("params");
            @SuppressWarnings("unchecked")
            Map<String, Object> params = paramsObj instanceof Map
                    ? (Map<String, Object>) paramsObj : new LinkedHashMap<>();

            // A notification (the "id" key genuinely absent, not merely null)
            // gets no response at all, per JSON-RPC - only
            // "notifications/initialized" is expected here in practice.
            boolean isNotification = !request.containsKey("id");
            if (isNotification) {
                exchange.sendResponseHeaders(202, -1);
                return;
            }

            dispatchOnEdt(exchange, id, method, params);
        } finally {
            exchange.close();
        }
    }

    /**
     * Runs the actual tool/method dispatch on the EDT via invokeAndWait,
     * since every handler in McpTools reads or mutates JMeter's live tree
     * state, then writes the HTTP response back on this (non-EDT) handler
     * thread.
     */
    private void dispatchOnEdt(HttpExchange exchange, Object id, String method, Map<String, Object> params)
            throws IOException {
        final Object[] resultHolder = new Object[1];
        final Exception[] errorHolder = new Exception[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    resultHolder[0] = dispatch(method, params);
                } catch (Exception ex) {
                    errorHolder[0] = ex;
                }
            });
        } catch (Exception invokeFailure) {
            writeRpcError(exchange, id, -32603, "Internal error: " + invokeFailure.getMessage());
            return;
        }
        if (errorHolder[0] instanceof McpToolException) {
            McpToolException te = (McpToolException) errorHolder[0];
            writeRpcError(exchange, id, te.code, te.getMessage());
        } else if (errorHolder[0] != null) {
            writeRpcError(exchange, id, -32603, "Internal error: " + errorHolder[0].getMessage());
        } else {
            writeRpcResult(exchange, id, resultHolder[0]);
        }
    }

    /** Must only run on the EDT - called only from inside invokeAndWait above. */
    private Object dispatch(String method, Map<String, Object> params) throws Exception {
        switch (method) {
            case "initialize": {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("protocolVersion", PROTOCOL_VERSION);
                Map<String, Object> capabilities = new LinkedHashMap<>();
                capabilities.put("tools", new LinkedHashMap<>());
                result.put("capabilities", capabilities);
                String version = McpServer.class.getPackage().getImplementationVersion();
                Map<String, Object> serverInfo = new LinkedHashMap<>();
                serverInfo.put("name", "jmeter-mcpbridge");
                serverInfo.put("version", version == null ? "dev" : version);
                result.put("serverInfo", serverInfo);
                return result;
            }
            case "notifications/initialized":
                return new LinkedHashMap<>(); // harmless if a client does expect some reply
            case "tools/list": {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("tools", McpTools.definitions());
                return result;
            }
            case "tools/call": {
                Object nameObj = params.get("name");
                if (!(nameObj instanceof String)) {
                    throw new McpToolException(-32602, "Invalid params: \"name\" is required");
                }
                Object argsObj = params.get("arguments");
                @SuppressWarnings("unchecked")
                Map<String, Object> args = argsObj instanceof Map
                        ? (Map<String, Object>) argsObj : new LinkedHashMap<>();
                return McpTools.call((String) nameObj, args);
            }
            default:
                throw new McpToolException(-32601, "Method not found: " + method);
        }
    }

    private void writeRpcResult(HttpExchange exchange, Object id, Object result) throws IOException {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", normalizeId(id));
        envelope.put("result", result == null ? new LinkedHashMap<>() : result);
        sendJson(exchange, 200, MiniJson.write(envelope));
    }

    private void writeRpcError(HttpExchange exchange, Object id, int code, String message) throws IOException {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", normalizeId(id));
        envelope.put("error", error);
        // JSON-RPC errors still ride on an HTTP 200 - the error is in the
        // JSON-RPC envelope, not the HTTP status line, per the spec examples
        // this was checked against.
        sendJson(exchange, 200, MiniJson.write(envelope));
    }

    /** MiniJson parses every JSON number as a Double, so an incoming numeric
     *  id like 1 would otherwise echo back as 1.0 \u2014 some strict JSON-RPC
     *  clients match request/response ids by value and could reject that.
     *  Collapses a whole-number Double back to a Long before it's echoed. */
    private static Object normalizeId(Object id) {
        if (id instanceof Double) {
            double d = (Double) id;
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return (long) d;
            }
        }
        return id;
    }

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendPlain(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String readBody(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, n);
        }
        return buffer.toString(StandardCharsets.UTF_8.name());
    }
}
