package io.mcpbridge.jmeter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic JMeter tool definitions for {@code tools/list} and dispatch for
 * {@code tools/call}. Every tool here operates on JMeter's actual, live
 * {@code GuiPackage}/tree model \u2014 whatever's open in the GUI right now \u2014
 * via {@link JMeterTreeTools}, with no bridge interface in between, since
 * none of these depend on another dialog's own state.
 *
 * Split out from Correlation Studio's own MCP server, where the first eight
 * of these tools started: get_tree, get_element_properties, add_element,
 * modify_element, clone_element, move_element, delete_element,
 * analyze_dynamic_candidates \u2014 generic to any JMeter test plan, not
 * specific to correlation work, split into their own plugin so they're
 * usable on their own, independent of whether someone uses Correlation
 * Studio at all. get_arguments and modify_argument were added afterward,
 * here only, once a real AI session surfaced a genuine gap: an HTTP
 * sampler's body/Test Plan variables are a structured Arguments collection,
 * not a plain string, so modify_element's generic setProperty() would have
 * corrupted one if used on it \u2014 these two exist to handle that case safely
 * instead.
 */
final class McpTools {

    private McpTools() {
    }

    static List<Map<String, Object>> definitions() {
        List<Map<String, Object>> tools = new ArrayList<>();

        tools.add(tool("get_tree",
                "Returns JMeter's ENTIRE currently open test plan as a live tree \u2014 every element, "
                        + "with a path (e.g. \"0/2/1\"), name, type, enabled state, and children. This "
                        + "operates on whatever's actually open in the JMeter GUI right now, not a "
                        + "file \u2014 call this first before any write below, and call it again after a "
                        + "write before addressing more nodes, since a path can go stale once the tree "
                        + "changes.",
                emptyObjectSchema()));

        Map<String, Object> getPropsProps = new LinkedHashMap<>();
        getPropsProps.put("path", pathSchema());
        List<String> getPropsRequired = new ArrayList<>();
        getPropsRequired.add("path");
        tools.add(tool("get_element_properties",
                "Returns one element's actual property values (e.g. an HTTP Request's domain/path/"
                        + "method, a Regular Expression Extractor's regex/template/variable name) by "
                        + "its path, as returned by get_tree. Generic across every element type.",
                objectSchema(getPropsProps, getPropsRequired)));

        Map<String, Object> addElProps = new LinkedHashMap<>();
        addElProps.put("parent_path", pathSchema());
        Map<String, Object> elementTypeSchema = new LinkedHashMap<>();
        elementTypeSchema.put("type", "string");
        elementTypeSchema.put("description", "One of: "
                + String.join(", ", JMeterTreeTools.supportedElementTypes())
                + ". A deliberately small, curated set, not every JMeter element.");
        addElProps.put("element_type", elementTypeSchema);
        Map<String, Object> nameSchema = new LinkedHashMap<>();
        nameSchema.put("type", "string");
        nameSchema.put("description", "The new element's display name in the tree. Optional.");
        addElProps.put("name", nameSchema);
        Map<String, Object> propsSchema = new LinkedHashMap<>();
        propsSchema.put("type", "object");
        propsSchema.put("description", "Property name/value pairs to set on the new element right "
                + "after creating it (e.g. {\"Path\": \"/login\", \"DOMAIN\": \"example.com\"} for an "
                + "HTTP Request). Optional \u2014 omit to use whatever defaults the element type assigns "
                + "itself.");
        addElProps.put("properties", propsSchema);
        List<String> addElRequired = new ArrayList<>();
        addElRequired.add("parent_path");
        addElRequired.add("element_type");
        tools.add(tool("add_element",
                "Creates a new element under parent_path and inserts it as that parent's last "
                        + "child \u2014 directly into JMeter's live, currently-open test plan, visible in "
                        + "the GUI immediately. Wrapped in JMeter's own undo transaction, so Ctrl+Z in "
                        + "the GUI reverts the whole thing as one step if it's not what you wanted. "
                        + "Call get_tree first to find the right parent_path, and again afterward "
                        + "since this changes the tree.",
                objectSchema(addElProps, addElRequired)));

        Map<String, Object> modifyProps = new LinkedHashMap<>();
        modifyProps.put("path", pathSchema());
        Map<String, Object> modifyPropsProps = new LinkedHashMap<>();
        modifyPropsProps.put("type", "object");
        modifyPropsProps.put("description", "Property name/value pairs to set on the existing element.");
        modifyProps.put("properties", modifyPropsProps);
        List<String> modifyRequired = new ArrayList<>();
        modifyRequired.add("path");
        modifyRequired.add("properties");
        tools.add(tool("modify_element",
                "Updates an existing element's properties in place, directly in JMeter's live, "
                        + "currently-open test plan. Wrapped in an undo transaction. Note: if this "
                        + "exact element is currently shown in JMeter's own right-hand edit panel, "
                        + "that panel's visible fields may not refresh until clicked away from and "
                        + "back to \u2014 the underlying data is correct immediately regardless.",
                objectSchema(modifyProps, modifyRequired)));

        Map<String, Object> cloneProps = new LinkedHashMap<>();
        cloneProps.put("source_path", pathSchema());
        cloneProps.put("target_parent_path", pathSchema());
        List<String> cloneRequired = new ArrayList<>();
        cloneRequired.add("source_path");
        cloneRequired.add("target_parent_path");
        tools.add(tool("clone_element",
                "Clones source_path's whole subtree (via JMeter's own Copy action, the same code "
                        + "path its GUI Copy/Paste uses, so properties are preserved rather than "
                        + "reconstructed) and inserts the clone as target_parent_path's last child. "
                        + "Wrapped in an undo transaction.",
                objectSchema(cloneProps, cloneRequired)));

        Map<String, Object> moveProps = new LinkedHashMap<>();
        moveProps.put("source_path", pathSchema());
        moveProps.put("target_parent_path", pathSchema());
        List<String> moveRequired = new ArrayList<>();
        moveRequired.add("source_path");
        moveRequired.add("target_parent_path");
        tools.add(tool("move_element",
                "Moves source_path (the same element, not a copy) to become target_parent_path's "
                        + "last child. Refuses to move an element into its own subtree, since that "
                        + "would leave the tree structurally invalid. Wrapped in an undo transaction.",
                objectSchema(moveProps, moveRequired)));

        Map<String, Object> deleteProps = new LinkedHashMap<>();
        deleteProps.put("path", pathSchema());
        List<String> deleteRequired = new ArrayList<>();
        deleteRequired.add("path");
        tools.add(tool("delete_element",
                "Removes path's whole subtree from JMeter's live, currently-open test plan \u2014 "
                        + "every descendant goes with it, same as JMeter's own Remove menu command. "
                        + "Wrapped in an undo transaction; cannot target the tree root.",
                objectSchema(deleteProps, deleteRequired)));

        Map<String, Object> getArgsProps = new LinkedHashMap<>();
        getArgsProps.put("path", pathSchema());
        List<String> getArgsRequired = new ArrayList<>();
        getArgsRequired.add("path");
        tools.add(tool("get_arguments",
                "Lists the name/value pairs of any Arguments-backed property on this element \u2014 "
                        + "an HTTP sampler's body/parameters, a Test Plan's user-defined variables, or "
                        + "anything else built the same way. get_element_properties does NOT show "
                        + "these (it doesn't recurse into nested collection properties) \u2014 use this "
                        + "instead for that kind of data, and before modify_argument so you know what "
                        + "argument names actually exist.",
                objectSchema(getArgsProps, getArgsRequired)));

        Map<String, Object> modifyArgProps = new LinkedHashMap<>();
        modifyArgProps.put("path", pathSchema());
        Map<String, Object> argNameSchema = new LinkedHashMap<>();
        argNameSchema.put("type", "string");
        argNameSchema.put("description", "The argument's name, as returned by get_arguments.");
        modifyArgProps.put("argument_name", argNameSchema);
        Map<String, Object> newValueSchema = new LinkedHashMap<>();
        newValueSchema.put("type", "string");
        newValueSchema.put("description", "The new value for this argument.");
        modifyArgProps.put("new_value", newValueSchema);
        List<String> modifyArgRequired = new ArrayList<>();
        modifyArgRequired.add("path");
        modifyArgRequired.add("argument_name");
        modifyArgRequired.add("new_value");
        tools.add(tool("modify_argument",
                "Updates ONE existing argument's value in place, by name, in an Arguments-backed "
                        + "property (an HTTP sampler's body, a Test Plan's user-defined variables, "
                        + "etc.) \u2014 safe where modify_element's plain property-setting is NOT: it "
                        + "edits the one matching entry directly rather than replacing the whole "
                        + "structured collection with an incompatible flat string, so the element's "
                        + "shape in the GUI is unaffected. Call get_arguments first to see what "
                        + "argument names exist. Wrapped in an undo transaction.",
                objectSchema(modifyArgProps, modifyArgRequired)));

        tools.add(tool("analyze_dynamic_candidates",
                "Scans the WHOLE live test plan for values that look session-specific and appear "
                        + "identically in two or more different elements \u2014 a deliberately "
                        + "conservative HEURISTIC, not a confirmed correlation. There's no real server "
                        + "response here to confirm a value was actually produced by one request and "
                        + "consumed by another \u2014 every result is marked as a guess worth checking, "
                        + "not a confirmed finding. Read-only; suggests, does not change anything.",
                emptyObjectSchema()));

        return tools;
    }

    static Map<String, Object> call(String name, Map<String, Object> args) throws McpToolException {
        Object result;
        switch (name) {
            case "get_tree":
                result = JMeterTreeTools.getTree();
                break;
            case "get_element_properties": {
                Object pathObj = args.get("path");
                if (!(pathObj instanceof String)) {
                    throw new McpToolException(-32602, "Invalid params: \"path\" is required");
                }
                result = JMeterTreeTools.getElementProperties((String) pathObj);
                break;
            }
            case "add_element": {
                Object parentPathObj = args.get("parent_path");
                Object elementTypeObj = args.get("element_type");
                if (!(parentPathObj instanceof String) || !(elementTypeObj instanceof String)) {
                    throw new McpToolException(-32602,
                            "Invalid params: \"parent_path\" and \"element_type\" are required");
                }
                Object nameObj = args.get("name");
                String name2 = nameObj instanceof String ? (String) nameObj : null;
                Object propsObj = args.get("properties");
                @SuppressWarnings("unchecked")
                Map<String, Object> properties = propsObj instanceof Map
                        ? (Map<String, Object>) propsObj : null;
                result = JMeterTreeTools.addElement((String) parentPathObj, (String) elementTypeObj,
                        name2, properties);
                break;
            }
            case "modify_element": {
                Object pathObj = args.get("path");
                Object propsObj = args.get("properties");
                if (!(pathObj instanceof String) || !(propsObj instanceof Map)) {
                    throw new McpToolException(-32602,
                            "Invalid params: \"path\" and \"properties\" are required");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> properties = (Map<String, Object>) propsObj;
                result = JMeterTreeTools.modifyElement((String) pathObj, properties);
                break;
            }
            case "clone_element": {
                Object sourceObj = args.get("source_path");
                Object targetObj = args.get("target_parent_path");
                if (!(sourceObj instanceof String) || !(targetObj instanceof String)) {
                    throw new McpToolException(-32602,
                            "Invalid params: \"source_path\" and \"target_parent_path\" are required");
                }
                result = JMeterTreeTools.cloneElement((String) sourceObj, (String) targetObj);
                break;
            }
            case "move_element": {
                Object sourceObj = args.get("source_path");
                Object targetObj = args.get("target_parent_path");
                if (!(sourceObj instanceof String) || !(targetObj instanceof String)) {
                    throw new McpToolException(-32602,
                            "Invalid params: \"source_path\" and \"target_parent_path\" are required");
                }
                result = JMeterTreeTools.moveElement((String) sourceObj, (String) targetObj);
                break;
            }
            case "delete_element": {
                Object pathObj = args.get("path");
                if (!(pathObj instanceof String)) {
                    throw new McpToolException(-32602, "Invalid params: \"path\" is required");
                }
                result = JMeterTreeTools.deleteElement((String) pathObj);
                break;
            }
            case "get_arguments": {
                Object pathObj = args.get("path");
                if (!(pathObj instanceof String)) {
                    throw new McpToolException(-32602, "Invalid params: \"path\" is required");
                }
                result = JMeterTreeTools.getArguments((String) pathObj);
                break;
            }
            case "modify_argument": {
                Object pathObj = args.get("path");
                Object argNameObj = args.get("argument_name");
                Object newValueObj = args.get("new_value");
                if (!(pathObj instanceof String) || !(argNameObj instanceof String)
                        || !(newValueObj instanceof String)) {
                    throw new McpToolException(-32602, "Invalid params: \"path\", \"argument_name\", "
                            + "and \"new_value\" are all required");
                }
                result = JMeterTreeTools.modifyArgument((String) pathObj, (String) argNameObj,
                        (String) newValueObj);
                break;
            }
            case "analyze_dynamic_candidates":
                result = JMeterTreeTools.analyzeDynamicCandidates();
                break;
            default:
                throw new McpToolException(-32601, "Unknown tool: " + name);
        }
        return toolTextResult(MiniJson.write(result));
    }

    /** Wraps a result as the standard tools/call content shape: a single text
     *  block carrying the JSON payload. */
    private static Map<String, Object> toolTextResult(String jsonText) {
        Map<String, Object> textBlock = new LinkedHashMap<>();
        textBlock.put("type", "text");
        textBlock.put("text", jsonText);
        List<Object> content = new ArrayList<>();
        content.add(textBlock);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("isError", false);
        return result;
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> inputSchema) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name);
        t.put("description", description);
        t.put("inputSchema", inputSchema);
        return t;
    }

    private static Map<String, Object> emptyObjectSchema() {
        return objectSchema(new LinkedHashMap<>(), new ArrayList<>());
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private static Map<String, Object> pathSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("description", "A node path as returned by get_tree, e.g. \"0/2/1\". "
                + "Empty string addresses the tree root.");
        return schema;
    }
}
