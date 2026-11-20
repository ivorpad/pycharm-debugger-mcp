package local.pycharmdebugger;

import com.intellij.mcpserver.McpTool;
import com.intellij.mcpserver.McpToolsProvider;
import java.util.List;

/** Loaded only while the Argument Origin plugin is enabled. */
public final class OriginToolsProvider implements McpToolsProvider {
    @Override public List<McpTool> getTools() {
        return List.of(DebuggerToolsProvider.originTool());
    }
}
