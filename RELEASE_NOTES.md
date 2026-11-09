## SmartBlitz MCP Bridge for JMeter v1.0.0

First public release.

- **Works on the live plan.** Tools operate on whatever is open in the JMeter GUI right now, not on a file.
- **Ten tools.** `get_tree`, `get_element_properties`, `add_element`, `modify_element`, `clone_element`, `move_element`, `delete_element`, `get_arguments`, `modify_argument` and `analyze_dynamic_candidates` (a heuristic scan for likely session values).
- **Safety net.** Every write goes through JMeter's own undo transaction, so Ctrl+Z reverts it as one step.
- **Chat tab.** Talk to a local AI CLI from inside the plugin, with multiple conversations you can create and delete.
- **Independent.** Split out of Correlation Studio so it works on its own, with or without any other plugin.

### Install
Download `jmeter-mcpbridge.jar`, copy it into `<JMETER_HOME>/lib/ext/`, restart JMeter. Requires JMeter 5.6.3 and Java 11+.
