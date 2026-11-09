package io.mcpbridge.jmeter;

/**
 * Carries a JSON-RPC error code alongside a user-facing message, so a tool
 * implementation can report something specific (invalid params, a bad index)
 * rather than every failure collapsing into a generic -32603 Internal error.
 * Standard JSON-RPC codes used here: -32602 (Invalid params) for bad tool
 * arguments, -32603 (Internal error) for anything else a tool throws.
 */
final class McpToolException extends Exception {
    private static final long serialVersionUID = 1L;

    final int code;

    McpToolException(int code, String message) {
        super(message);
        this.code = code;
    }
}
