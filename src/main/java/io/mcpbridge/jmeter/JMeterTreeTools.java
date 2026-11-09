package io.mcpbridge.jmeter;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;

/**
 * Generic, live-tree JMeter operations \u2014 not specific to Correlation
 * Studio's own candidate workflow, which is what "making the MCP server more
 * generic to JMeter" asked for. Reads and writes JMeter's actual
 * {@code GuiPackage}/{@code JMeterTreeModel}, the same live tree backing
 * whatever's open in the GUI right now, rather than a disposable file.
 *
 * <p>Every API here reads or mutates live Swing/JMeter state and MUST only
 * ever be called already on the Event Dispatch Thread \u2014 same requirement
 * as {@link McpBridge}, enforced the same way by {@link McpServer}.
 *
 * <p><b>Node addressing.</b> A node is addressed by a slash-separated path of
 * child indices from the root, e.g. {@code "0/2/1"} \u2014 root's child 0, that
 * node's child 2, that node's child 1. Simple and unambiguous, unlike
 * addressing by name (JMeter doesn't require unique names, so two samplers
 * named "HTTP Request" are common and would be genuinely ambiguous to
 * address by name alone). {@link #getTree()} returns both the path and the
 * name/type for every node, so a caller always has a human-readable label
 * next to the exact path to use in a later call. A path can go stale if the
 * tree is mutated between reading it and using it \u2014 same characteristic as
 * holding a plain list index across a mutation elsewhere, not a protocol
 * bug \u2014 so a caller should re-fetch the tree after any write before
 * addressing more nodes from it.
 *
 * <p><b>Safety net.</b> Every write in here wraps its mutation in
 * {@code GuiPackage.beginUndoTransaction()}/{@code endUndoTransaction()},
 * JMeter's own undo mechanism \u2014 the same boundary a single GUI action gets.
 * A bad edit is one Ctrl+Z away from gone, not something to trust a rewind
 * script for.
 */
final class JMeterTreeTools {

    private JMeterTreeTools() {
    }

    /**
     * Curated, deliberately small set of element types add_element can
     * create \u2014 not every JMeter element, only ones checked with enough
     * confidence to ship. Each value is the exact string
     * {@code GuiPackage.createTestElement(String)} expects: a GUI class's
     * fully-qualified name for a traditional component, or the TestElement's
     * own fully-qualified class name for a TestBean-style component (like
     * JSR223 Assertion, which uses the generic TestBeanGUI wrapper rather
     * than a dedicated GUI class \u2014 already confirmed in this project's own
     * JmxRewriter, which writes guiclass="TestBeanGUI" testclass="JSR223Assertion"
     * directly for exactly this element).
     */
    private static final Map<String, String> ELEMENT_TYPES = buildElementTypes();

    private static Map<String, String> buildElementTypes() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("HTTP Request", "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui");
        m.put("Regular Expression Extractor", "org.apache.jmeter.extractor.gui.RegexExtractorGui");
        m.put("Response Assertion", "org.apache.jmeter.assertions.gui.AssertionGui");
        m.put("JSR223 PostProcessor", "org.apache.jmeter.extractor.gui.JSR223PostProcessorGui");
        m.put("JSR223 PreProcessor", "org.apache.jmeter.modifiers.gui.JSR223PreProcessorGui");
        // TestBean-style - the TestElement's own class name, not a GUI class.
        m.put("JSR223 Assertion", "org.apache.jmeter.assertions.JSR223Assertion");
        m.put("Loop Controller", "org.apache.jmeter.control.gui.LoopControlPanel");
        m.put("Transaction Controller", "org.apache.jmeter.control.gui.TransactionControllerGui");
        return m;
    }

    static List<String> supportedElementTypes() {
        return new ArrayList<>(ELEMENT_TYPES.keySet());
    }

    /** The whole live tree, as a nested structure ready for MiniJson.write:
     *  each node is a map with path/name/type/enabled/children. */
    static Map<String, Object> getTree() throws McpToolException {
        JMeterTreeNode root = rootNode();
        return nodeToMap(root, "");
    }

    private static Map<String, Object> nodeToMap(JMeterTreeNode node, String path) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        Object userObject = node.getUserObject();
        if (userObject instanceof TestElement) {
            TestElement te = (TestElement) userObject;
            m.put("name", te.getName());
            m.put("type", te.getClass().getSimpleName());
            m.put("enabled", te.isEnabled());
        } else {
            m.put("name", String.valueOf(userObject));
            m.put("type", "unknown");
            m.put("enabled", true);
        }
        List<Object> children = new ArrayList<>();
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            Object child = node.getChildAt(i);
            if (child instanceof JMeterTreeNode) {
                String childPath = path.isEmpty() ? String.valueOf(i) : path + "/" + i;
                children.add(nodeToMap((JMeterTreeNode) child, childPath));
            }
        }
        m.put("children", children);
        return m;
    }

    /** A given node's own TestElement properties, flattened to name/value
     *  pairs via TestElement.propertyIterator() \u2014 generic across every
     *  element type, not hand-written per type. */
    static Map<String, Object> getElementProperties(String path) throws McpToolException {
        JMeterTreeNode node = resolvePath(path);
        Object userObject = node.getUserObject();
        if (!(userObject instanceof TestElement)) {
            throw new McpToolException(-32602, "Node at path \"" + path + "\" has no TestElement");
        }
        TestElement te = (TestElement) userObject;
        Map<String, Object> props = new LinkedHashMap<>();
        PropertyIterator it = te.propertyIterator();
        while (it.hasNext()) {
            JMeterProperty p = it.next();
            props.put(p.getName(), p.getStringValue());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path);
        out.put("type", te.getClass().getSimpleName());
        out.put("properties", props);
        return out;
    }

    /**
     * Creates a new element under parentPath and inserts it as the last
     * child, setting any given properties afterward. Wrapped in an undo
     * transaction \u2014 Ctrl+Z in the GUI reverts the whole thing as one step.
     */
    static Map<String, Object> addElement(String parentPath, String elementType, String name,
                                          Map<String, Object> properties) throws McpToolException {
        String guiOrBeanClass = ELEMENT_TYPES.get(elementType);
        if (guiOrBeanClass == null) {
            throw new McpToolException(-32602, "Unknown element type \"" + elementType
                    + "\". Supported: " + String.join(", ", ELEMENT_TYPES.keySet()));
        }
        JMeterTreeNode parent = resolvePath(parentPath);

        GuiPackage guiPackage = guiPackageOrThrow();
        TestElement element;
        try {
            element = guiPackage.createTestElement(guiOrBeanClass);
        } catch (Exception ex) {
            throw new McpToolException(-32603, "Could not create a \"" + elementType
                    + "\" (" + guiOrBeanClass + "): " + ex.getMessage());
        }
        if (name != null && !name.isEmpty()) {
            element.setName(name);
        }
        if (properties != null) {
            for (Map.Entry<String, Object> e : properties.entrySet()) {
                element.setProperty(e.getKey(), String.valueOf(e.getValue()));
            }
        }

        JMeterTreeNode newNode;
        guiPackage.beginUndoTransaction();
        try {
            newNode = guiPackage.getTreeModel().addComponent(element, parent);
        } catch (Exception ex) {
            throw new McpToolException(-32603, "Could not add element to the tree: " + ex.getMessage());
        } finally {
            guiPackage.endUndoTransaction();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", pathOf(newNode));
        out.put("name", element.getName());
        out.put("type", element.getClass().getSimpleName());
        return out;
    }

    /** Updates an existing element's properties in place. Wrapped in an undo
     *  transaction, same as addElement. Note: if this exact node is currently
     *  shown in JMeter's own right-hand edit panel, that panel's visible
     *  fields won't auto-refresh from this \u2014 the underlying data and the
     *  tree label are correct immediately, but the open editor may show
     *  stale values until clicked away from and back to. A known, accepted
     *  limitation for this version, not a correctness issue in the data. */
    static Map<String, Object> modifyElement(String path, Map<String, Object> properties)
            throws McpToolException {
        JMeterTreeNode node = resolvePath(path);
        Object userObject = node.getUserObject();
        if (!(userObject instanceof TestElement)) {
            throw new McpToolException(-32602, "Node at path \"" + path + "\" has no TestElement");
        }
        if (properties == null || properties.isEmpty()) {
            throw new McpToolException(-32602, "No properties given to modify");
        }
        TestElement te = (TestElement) userObject;
        GuiPackage gp = guiPackageOrThrow();
        gp.beginUndoTransaction();
        try {
            for (Map.Entry<String, Object> e : properties.entrySet()) {
                te.setProperty(e.getKey(), String.valueOf(e.getValue()));
            }
            gp.getTreeModel().nodeChanged(node);
        } finally {
            gp.endUndoTransaction();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path);
        out.put("name", te.getName());
        out.put("updatedProperties", new ArrayList<>(properties.keySet()));
        return out;
    }

    /**
     * Lists the name/value pairs of an Arguments-backed property on this
     * element - an HTTP sampler's body/parameters, a Test Plan's
     * user-defined variables, or anything else built on the same generic
     * org.apache.jmeter.config.Arguments/Argument structure (not HTTP-
     * specific; HTTPArgument extends the same base Argument class, so this
     * works for both). get_element_properties doesn't show these, since it
     * doesn't recurse into nested collection properties - confirmed as a
     * real gap, not a hypothetical, after an AI session correctly refused
     * to blindly modify one of these via modify_element's plain
     * setProperty(), which would have replaced the whole structured
     * collection with an incompatible flat string rather than actually
     * editing it.
     */
    static Map<String, Object> getArguments(String path) throws McpToolException {
        JMeterTreeNode node = resolvePath(path);
        Object userObject = node.getUserObject();
        if (!(userObject instanceof TestElement)) {
            throw new McpToolException(-32602, "Node at path \"" + path + "\" has no TestElement");
        }
        TestElement te = (TestElement) userObject;
        List<Object> found = new ArrayList<>();
        PropertyIterator it = te.propertyIterator();
        while (it.hasNext()) {
            JMeterProperty p = it.next();
            if (!(p instanceof org.apache.jmeter.testelement.property.MultiProperty)) {
                continue;
            }
            PropertyIterator argIt = ((org.apache.jmeter.testelement.property.MultiProperty) p).iterator();
            while (argIt.hasNext()) {
                Object obj = argIt.next().getObjectValue();
                if (obj instanceof org.apache.jmeter.config.Argument) {
                    org.apache.jmeter.config.Argument arg = (org.apache.jmeter.config.Argument) obj;
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("propertyName", p.getName());
                    entry.put("argumentName", arg.getName());
                    entry.put("value", arg.getValue());
                    found.add(entry);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path);
        out.put("arguments", found);
        return out;
    }

    /**
     * Finds an existing argument by name (as returned by getArguments, in
     * any Arguments-backed property on this element) and updates its value
     * in place via Argument.setValue() - the structured collection itself
     * is untouched, only the one matching entry's value changes. This is
     * what makes it safe where modify_element's plain setProperty would
     * not be: it never replaces the collection with an incompatible flat
     * string, so the sampler/element's shape in the GUI is unaffected.
     */
    static Map<String, Object> modifyArgument(String path, String argumentName, String newValue)
            throws McpToolException {
        JMeterTreeNode node = resolvePath(path);
        Object userObject = node.getUserObject();
        if (!(userObject instanceof TestElement)) {
            throw new McpToolException(-32602, "Node at path \"" + path + "\" has no TestElement");
        }
        TestElement te = (TestElement) userObject;

        GuiPackage gp = guiPackageOrThrow();
        boolean found = false;
        gp.beginUndoTransaction();
        try {
            PropertyIterator it = te.propertyIterator();
            while (it.hasNext()) {
                JMeterProperty p = it.next();
                if (!(p instanceof org.apache.jmeter.testelement.property.MultiProperty)) {
                    continue;
                }
                PropertyIterator argIt = ((org.apache.jmeter.testelement.property.MultiProperty) p).iterator();
                while (argIt.hasNext()) {
                    Object obj = argIt.next().getObjectValue();
                    if (obj instanceof org.apache.jmeter.config.Argument) {
                        org.apache.jmeter.config.Argument arg = (org.apache.jmeter.config.Argument) obj;
                        if (argumentName.equals(arg.getName())) {
                            arg.setValue(newValue);
                            found = true;
                        }
                    }
                }
            }
            if (found) {
                gp.getTreeModel().nodeChanged(node);
            }
        } finally {
            gp.endUndoTransaction();
        }
        if (!found) {
            throw new McpToolException(-32602, "No argument named \"" + argumentName
                    + "\" found on this element \u2014 call get_arguments first to see what's there");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path);
        out.put("argumentName", argumentName);
        out.put("newValue", newValue);
        return out;
    }

    /** Clones sourcePath's whole subtree (via Copy.cloneTreeNode - JMeter's
     *  own Copy action's public static method, the same code path the GUI's
     *  Copy/Paste uses) and inserts the clone as targetParentPath's last
     *  child. Deliberately uses insertNodeInto, not addComponent - see this
     *  method's own comment above for why addComponent would silently drop
     *  the cloned children. */
    static Map<String, Object> cloneElement(String sourcePath, String targetParentPath)
            throws McpToolException {
        JMeterTreeNode source = resolvePath(sourcePath);
        JMeterTreeNode targetParent = resolvePath(targetParentPath);
        GuiPackage gp = guiPackageOrThrow();

        JMeterTreeNode cloned;
        gp.beginUndoTransaction();
        try {
            cloned = org.apache.jmeter.gui.action.Copy.cloneTreeNode(source);
            gp.getTreeModel().insertNodeInto(cloned, targetParent, targetParent.getChildCount());
        } catch (Exception ex) {
            throw new McpToolException(-32603, "Could not clone element: " + ex.getMessage());
        } finally {
            gp.endUndoTransaction();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", pathOf(cloned));
        Object clonedUserObject = cloned.getUserObject();
        out.put("name", clonedUserObject instanceof TestElement
                ? ((TestElement) clonedUserObject).getName() : null);
        return out;
    }

    /** Moves sourcePath (the same node, not a copy) to become
     *  targetParentPath's last child. Guards against moving a node into its
     *  own subtree first - that would leave the tree in a structurally
     *  invalid state (a node as its own ancestor), not just a logical error. */
    static Map<String, Object> moveElement(String sourcePath, String targetParentPath)
            throws McpToolException {
        JMeterTreeNode source = resolvePath(sourcePath);
        JMeterTreeNode targetParent = resolvePath(targetParentPath);

        JMeterTreeNode check = targetParent;
        while (check != null) {
            if (check == source) {
                throw new McpToolException(-32602, "Cannot move an element into its own subtree");
            }
            check = check.getParent() instanceof JMeterTreeNode ? (JMeterTreeNode) check.getParent() : null;
        }

        GuiPackage gp = guiPackageOrThrow();
        gp.beginUndoTransaction();
        try {
            gp.getTreeModel().removeNodeFromParent(source);
            gp.getTreeModel().insertNodeInto(source, targetParent, targetParent.getChildCount());
        } finally {
            gp.endUndoTransaction();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", pathOf(source));
        return out;
    }

    /** Removes path's whole subtree. Swing's tree removal is inherently
     *  recursive, so this takes every descendant with it - there is no
     *  separate "delete just this node, keep its children" operation, same
     *  as JMeter's own Remove menu command. */
    static Map<String, Object> deleteElement(String path) throws McpToolException {
        if (path == null || path.isEmpty()) {
            throw new McpToolException(-32602, "Cannot delete the tree root");
        }
        JMeterTreeNode node = resolvePath(path);
        Object userObject = node.getUserObject();
        String name = userObject instanceof TestElement ? ((TestElement) userObject).getName() : null;

        GuiPackage gp = guiPackageOrThrow();
        gp.beginUndoTransaction();
        try {
            gp.getTreeModel().removeNodeFromParent(node);
        } finally {
            gp.endUndoTransaction();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deletedPath", path);
        out.put("deletedName", name);
        return out;
    }

    /**
     * A deliberately conservative, HEURISTIC scan for values that look
     * session-specific and appear identically in two or more different
     * elements \u2014 NOT a confirmed producer/consumer correlation the way
     * Correlation Studio's own recording-based engine establishes, since
     * there's no real HTTP response here to confirm a value was actually
     * produced by one request and reused by another. Every result is marked
     * as a heuristic guess for exactly that reason; treat this as "worth a
     * look," not "confirmed." Walks the whole live tree's properties
     * generically (not HTTP-specific), so it works across protocols.
     */
    static List<Map<String, Object>> analyzeDynamicCandidates() throws McpToolException {
        JMeterTreeNode root = rootNode();
        Map<String, List<String>> valueToOccurrences = new LinkedHashMap<>();
        collectPropertyValues(root, valueToOccurrences);

        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : valueToOccurrences.entrySet()) {
            String value = e.getKey();
            List<String> occurrences = e.getValue();
            java.util.Set<String> distinctElements = new java.util.LinkedHashSet<>();
            for (String occ : occurrences) {
                distinctElements.add(occ.substring(0, occ.indexOf(':')));
            }
            if (distinctElements.size() >= 2 && looksSessionLike(value)) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("value", value.length() > 60 ? value.substring(0, 57) + "..." : value);
                c.put("occurrences", occurrences);
                c.put("confidence", "heuristic guess, not a confirmed correlation \u2014 verify "
                        + "(e.g. with Correlation Studio's own recording-based analysis) before acting");
                candidates.add(c);
            }
        }
        return candidates;
    }

    private static final java.util.Set<String> EXCLUDED_PROPERTY_NAMES = buildExcludedPropertyNames();

    private static java.util.Set<String> buildExcludedPropertyNames() {
        java.util.Set<String> s = new java.util.HashSet<>();
        s.add(TestElement.NAME);
        s.add(TestElement.GUI_CLASS);
        s.add(TestElement.TEST_CLASS);
        s.add(TestElement.ENABLED);
        s.add(TestElement.COMMENTS);
        return s;
    }

    private static void collectPropertyValues(JMeterTreeNode node, Map<String, List<String>> out) {
        Object userObject = node.getUserObject();
        if (userObject instanceof TestElement) {
            TestElement te = (TestElement) userObject;
            String label = te.getName();
            collectStringProps(te.propertyIterator(), label, out);
        }
        Enumeration<?> children = node.children();
        while (children.hasMoreElements()) {
            Object child = children.nextElement();
            if (child instanceof JMeterTreeNode) {
                collectPropertyValues((JMeterTreeNode) child, out);
            }
        }
    }

    private static void collectStringProps(PropertyIterator it, String label, Map<String, List<String>> out) {
        while (it.hasNext()) {
            JMeterProperty p = it.next();
            if (EXCLUDED_PROPERTY_NAMES.contains(p.getName())) {
                continue;
            }
            if (p instanceof org.apache.jmeter.testelement.property.MultiProperty) {
                collectStringProps(((org.apache.jmeter.testelement.property.MultiProperty) p).iterator(),
                        label, out);
            } else {
                String val = p.getStringValue();
                if (val != null && !val.isEmpty()) {
                    out.computeIfAbsent(val, k -> new ArrayList<>()).add(label + ":" + p.getName());
                }
            }
        }
    }

    /** Conservative on purpose: requires a mix of letters AND digits (so
     *  plain words and plain numbers are excluded) and a minimum length,
     *  favoring missed candidates over false leads that waste time chasing. */
    private static boolean looksSessionLike(String value) {
        if (value.length() < 8) {
            return false;
        }
        boolean hasDigit = false;
        boolean hasLetter = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isDigit(c)) {
                hasDigit = true;
            } else if (Character.isLetter(c)) {
                hasLetter = true;
            }
        }
        return hasDigit && hasLetter;
    }

    // ---- shared helpers ----

    private static GuiPackage guiPackageOrThrow() throws McpToolException {
        GuiPackage gp = GuiPackage.getInstance();
        if (gp == null) {
            throw new McpToolException(-32603, "JMeter's GuiPackage isn't available yet "
                    + "(too early in startup, or running headless)");
        }
        return gp;
    }

    private static JMeterTreeNode rootNode() throws McpToolException {
        GuiPackage gp = guiPackageOrThrow();
        JMeterTreeModel treeModel = gp.getTreeModel();
        Object root = treeModel.getRoot();
        if (!(root instanceof JMeterTreeNode)) {
            throw new McpToolException(-32603, "Unexpected tree root type: "
                    + (root == null ? "null" : root.getClass().getName()));
        }
        return (JMeterTreeNode) root;
    }

    /** Resolves a "0/2/1"-style path (see class comment) to a node, starting
     *  from the tree root. Empty string resolves to the root itself. */
    private static JMeterTreeNode resolvePath(String path) throws McpToolException {
        JMeterTreeNode node = rootNode();
        if (path == null || path.isEmpty()) {
            return node;
        }
        for (String part : path.split("/")) {
            int index;
            try {
                index = Integer.parseInt(part);
            } catch (NumberFormatException nfe) {
                throw new McpToolException(-32602, "Invalid path \"" + path + "\" \u2014 not all "
                        + "segments are integers");
            }
            if (index < 0 || index >= node.getChildCount()) {
                throw new McpToolException(-32602, "Invalid path \"" + path + "\" \u2014 index " + index
                        + " out of range at that depth (has " + node.getChildCount() + " children). "
                        + "The tree may have changed since this path was read \u2014 call get_tree again.");
            }
            Object child = node.getChildAt(index);
            if (!(child instanceof JMeterTreeNode)) {
                throw new McpToolException(-32603, "Unexpected child node type at \"" + path + "\"");
            }
            node = (JMeterTreeNode) child;
        }
        return node;
    }

    /** The inverse of resolvePath - walks up via getParent()/getIndex() to
     *  rebuild the path string for a node just inserted, so addElement can
     *  report exactly the path a caller would need to address it next. */
    private static String pathOf(JMeterTreeNode node) {
        List<Integer> indices = new ArrayList<>();
        JMeterTreeNode current = node;
        while (current.getParent() instanceof JMeterTreeNode) {
            JMeterTreeNode parent = (JMeterTreeNode) current.getParent();
            indices.add(0, parent.getIndex(current));
            current = parent;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < indices.size(); i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(indices.get(i));
        }
        return sb.toString();
    }
}
