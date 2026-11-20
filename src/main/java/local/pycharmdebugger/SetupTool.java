package local.pycharmdebugger;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.mcpserver.McpTool;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.LocalFileSystem;
import kotlinx.serialization.json.JsonArray;
import kotlinx.serialization.json.JsonElement;
import kotlinx.serialization.json.JsonObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static local.pycharmdebugger.DebuggerToolsProvider.*;

final class SetupTool {
    private static final String NAME = "debugger_setup";
    private static final String DEFAULT_SERVER = "Python Debug Server (MCP)";
    private static final String SCHEMA = """
            "mode":{"type":"string","enum":["pydevd","dap","client","status"],"description":"dap attaches to a listening debugpy endpoint. pydevd starts a Python Debug Server. For Argument Origin provenance, select pydevd in PyCharm and launch a local script with filePath. Both attach modes lack recording. client shows target-side instructions. status checks a named launch without starting it."},
            "configurationName":{"type":"string","description":"Exact saved configuration, or a name for a new pydevd server."},
            "filePath":{"type":"string","description":"Project file to debug, relative to the project or absolute."},
            "line":{"type":"integer","minimum":1,"description":"Optional runnable location in filePath (1-based)."},
            "host":{"type":"string","description":"For DAP, the debugpy host as seen by the IDE (default 127.0.0.1). For pydevd, the IDE hostname as seen by the target."},
            "start":{"type":"boolean","description":"With mode=dap, false saves the configuration without connecting. Defaults to true."},
            "installRunner":{"type":"boolean","description":"With mode=dap, install or enable the official Python DAP Debugger plugin if missing."},
            "port":{"type":"integer","minimum":1,"maximum":65535,"description":"Target listener port for DAP. For pydevd, the IDE listener defaults to 5678."},
            "remoteRoot":{"type":"string","description":"Absolute target directory corresponding to the project root."}
            """;

    static McpTool tool() {
        return DebuggerToolsProvider.tool(NAME, "Start here for debugger setup. Call with the open projectPath for choices ({} if the client supplies the project), give filePath or configurationName to launch directly, mode=dap to create or start an Attach to DAP configuration, or mode=pydevd to reuse or create and start a Python Debug Server. Follow the returned tool calls.", SCHEMA, Set.of(), false, SetupTool::setup);
    }

    static JsonObject with(JsonObject args, JsonObject changes) {
        var merged = new LinkedHashMap<String, JsonElement>(args);
        merged.putAll(changes);
        return new JsonObject(merged);
    }

    static JsonObject call(Project project, String tool, JsonObject args) {
        return object("tool", tool, "arguments", with(args, object("projectPath", project.getBasePath())));
    }

    static JsonObject chooseProject(JsonObject args) {
        var choices = Arrays.stream(ProjectManager.getInstance().getOpenProjects())
                .filter(p -> !p.isDisposed() && p.getBasePath() != null).map(p -> call(p, NAME, args)).toList();
        return object("outcome", "needs_project", "message", choices.isEmpty() ? "Open the project in PyCharm and repeat this call." : "Select the open project to debug.", "choices", choices);
    }

    private static void setup(Context context, CompletableFuture<JsonObject> out) {
        var p = context.project();
        var args = context.args();
        String mode = optionalString(args, "mode");
        String name = optionalString(args, "configurationName");
        boolean hasEndpoint = args.containsKey("host") || args.containsKey("port") || args.containsKey("remoteRoot");
        if ("dap".equals(mode)) {
            if (args.containsKey("filePath") || args.containsKey("line")) throw new IllegalArgumentException("DAP attach takes a listening endpoint. Use filePath separately for a local script.");
            DapSetup.setup(p, args, out);
            return;
        }
        if (args.containsKey("installRunner") || args.containsKey("start")) throw new IllegalArgumentException("installRunner and start require mode=dap.");
        if (hasEndpoint && !"pydevd".equals(mode)) throw new IllegalArgumentException("Use mode=pydevd or mode=dap to specify connection settings.");
        if (args.containsKey("line") && !args.containsKey("filePath")) throw new IllegalArgumentException("line requires filePath.");
        if (args.containsKey("filePath")) {
            if (name != null || mode != null) throw new IllegalArgumentException("Choose a file or a server/configuration.");
            launch(p, LaunchTools.fromFile(p, args), out);
        } else if ("status".equals(mode)) {
            var saved = LaunchTools.named(p, string(args, "configurationName"));
            if (saved == null) out.complete(object("outcome", "unknown_configuration", "next", call(p, NAME, object())));
            else {
                var sessions = LaunchTools.matching(p, saved);
                var last = LaunchTools.describe(p, saved).get("lastLaunch");
                JsonObject state;
                if (!sessions.isEmpty()) state = object("outcome", "already_active", "sessionIds", sessions.stream().map(DebuggerToolsProvider::sessionId).toList());
                else if (last instanceof JsonObject attempt && !"started".equals(string(attempt, "outcome"))) state = attempt;
                else state = object("outcome", "no_active_session", "lastLaunch", last);
                out.complete(guidance(p, saved, state));
            }
        } else if (mode != null) {
            if (!Set.of("pydevd", "client").contains(mode)) throw new IllegalArgumentException("mode must be dap, pydevd, client or status.");
            server(p, args, mode, out);
        } else if (name != null) {
            var saved = LaunchTools.named(p, string(args, "configurationName"));
            if (saved == null) {
                out.complete(object("outcome", "unknown_configuration", "message", "Choose a saved configuration or create a server with mode=pydevd.", "next", call(p, NAME, object())));
            } else launch(p, saved, out);
        } else out.complete(discover(p));
    }

    private static JsonObject discover(Project p) {
        var choices = new ArrayList<JsonObject>();
        var configs = RunManager.getInstance(p).getAllSettings().stream()
                .filter(s -> ProgramRunner.getRunner("Debug", s.getConfiguration()) != null).toList();
        for (var s : configs.stream().limit(5).toList()) choices.add(call(p, NAME, object("configurationName", s.getName())));
        var file = Arrays.stream(FileEditorManager.getInstance(p).getSelectedFiles())
                .filter(f -> "py".equalsIgnoreCase(f.getExtension()) && ProjectFileIndex.getInstance(p).isInContent(f)).findFirst();
        file.ifPresent(f -> choices.add(call(p, NAME, object("filePath", f.getPath()))));
        if (file.isEmpty() && configs.isEmpty() && p.getBasePath() != null) {
            var root = LocalFileSystem.getInstance().findFileByPath(p.getBasePath());
            if (root != null) {
                for (String path : List.of("main.py", "app.py", "src/main.py", "__main__.py", "manage.py")) {
                    var candidate = root.findFileByRelativePath(path);
                    if (candidate != null && !candidate.isDirectory() && ProjectFileIndex.getInstance(p).isInContent(candidate))
                        choices.add(call(p, NAME, object("filePath", path)));
                }
            }
        }
        boolean available = LaunchTools.serverType() != null;
        if (available) choices.add(call(p, NAME, object("mode", "pydevd")));
        choices.add(call(p, NAME, object("mode", "dap")));
        if (configs.size() > 5) choices.add(call(p, "debugger_configurations", object()));
        return object("outcome", "choose_target", "message", "Choose a call below, or supply filePath for a project script or test.",
                "pydevdServerAvailable", available, "choices", choices);
    }

    private static void server(Project p, JsonObject args, String mode, CompletableFuture<JsonObject> out) {
        if (LaunchTools.serverType() == null) {
            out.complete(object("outcome", "server_unavailable", "message", "This IDE does not offer Python Debug Server. Enable the Python plugin in a PyCharm edition with remote debugging, or choose a local target.", "next", call(p, NAME, object())));
            return;
        }
        String name = optionalString(args, "configurationName");
        var servers = RunManager.getInstance(p).getAllSettings().stream().filter(s -> LaunchTools.PYDEVD.equals(s.getType().getId())).toList();
        if (name == null && servers.size() > 1) {
            out.complete(object("outcome", "choose_server", "choices", servers.stream().map(s -> call(p, NAME, with(args, object("configurationName", s.getName())))).toList()));
            return;
        }
        if (name == null) name = servers.isEmpty() ? DEFAULT_SERVER : servers.getFirst().getName();
        var saved = LaunchTools.named(p, name);
        if (saved != null && !LaunchTools.PYDEVD.equals(saved.getType().getId()))
            throw new IllegalArgumentException(name + " is a " + saved.getType().getDisplayName() + ". Choose a distinct name for the pydevd server.");
        if ("client".equals(mode)) {
            if (saved == null) out.complete(object("outcome", "needs_server", "next", call(p, NAME, object("mode", "pydevd", "configurationName", name))));
            else out.complete(client(p, saved));
            return;
        }
        var patch = object("configurationName", name);
        var missing = new ArrayList<String>();
        for (String key : List.of("host", "remoteRoot")) {
            if (args.containsKey(key)) {
                String value = string(args, key);
                patch = key.equals("host") ? with(patch, object(key, value))
                        : with(patch, object("sourceMappings", List.of(object("localRoot", ".", "remoteRoot", value))));
            } else if (saved == null) missing.add(key);
        }
        if (args.containsKey("port") || saved == null) patch = with(patch, object("port", integer(args, "port", 5678, 1, 65535)));
        if (!missing.isEmpty()) {
            out.complete(object("outcome", "needs_input", "required", missing,
                    "message", "Add the IDE hostname reachable from the target and its absolute project directory. Set port if your launcher uses a port other than 5678.",
                    "next", call(p, NAME, with(args, object("configurationName", name)))));
            return;
        }
        if (saved == null) {
            LaunchTools.configure(p, patch);
            saved = LaunchTools.named(p, name);
        } else if (patch.size() > 1) {
            var current = (JsonObject) LaunchTools.describe(p, saved).get("server");
            boolean differs = patch.containsKey("port") && !patch.get("port").equals(current.get("port"))
                    || patch.containsKey("host") && !patch.get("host").equals(current.get("host"));
            if (patch.containsKey("sourceMappings")) {
                var expected = object("localRoot", Path.of(p.getBasePath()).normalize().toString(), "remoteRoot", string(args, "remoteRoot"));
                differs |= !((JsonArray) current.get("sourceMappings")).contains(expected);
            }
            if (differs) {
                out.complete(object("outcome", "settings_differ", "server", current,
                        "message", "The saved settings differ. To change them, stop any active session, apply the update below, then repeat setup. sourceMappings replaces the full mapping list.",
                        "update", call(p, "debugger_configure_pydevd", patch), "sessions", call(p, "debugger_status", object())));
                return;
            }
        }
        launch(p, saved, out);
    }

    static void launch(Project p, RunnerAndConfigurationSettings settings, CompletableFuture<JsonObject> out) {
        boolean server = LaunchTools.PYDEVD.equals(settings.getType().getId());
        var started = new CompletableFuture<JsonObject>();
        // Capture IDE settings on the dispatch thread. A runner can complete its callback on a worker.
        var endpoint = server ? LaunchTools.describe(p, settings).get("server") : null;
        String name = settings.getName();
        started.thenAccept(result -> out.complete(guidance(p, name, endpoint, result)))
                .exceptionally(error -> { out.completeExceptionally(error); return null; });
        try {
            LaunchTools.start(p, settings, started);
        } catch (IllegalArgumentException | IllegalStateException error) {
            String recovery = server
                    ? "Check that this IDE offers the Python Debug Server runner and the configured port is available."
                    : "Check the configuration’s Debug runner and interpreter in PyCharm. For a Python file, select the virtualenv, uv or Poetry environment used by its module.";
            out.complete(object("outcome", "blocked", "configurationName", settings.getName(), "error", error.getMessage(),
                    "message", recovery, "next", call(p, "debugger_configurations", object())));
        }
    }

    private static JsonObject guidance(Project p, RunnerAndConfigurationSettings settings, JsonObject result) {
        var endpoint = LaunchTools.PYDEVD.equals(settings.getType().getId()) ? LaunchTools.describe(p, settings).get("server") : null;
        return guidance(p, settings.getName(), endpoint, result);
    }

    private static JsonObject guidance(Project p, String name, JsonElement endpoint, JsonObject result) {
        var status = call(p, "debugger_status", object());
        var details = switch (string(result, "outcome")) {
            case "starting" -> object("message", "PyCharm is starting the runner. Follow next to check this launch without starting it again.",
                    "next", call(p, NAME, object("mode", "status", "configurationName", name)));
            case "started", "already_active" -> endpoint != null
                    ? object("server", endpoint, "message", "Run the prepared pydevd client in the target. To prepare one, follow clientSetup.",
                            "clientSetup", call(p, NAME, object("mode", "client", "configurationName", name)), "next", status)
                    : object("next", status);
            case "no_active_session" -> object("message", "No active session for this configuration. Check the Run console for an early exit.", "next", status);
            default -> object("message", "Inspect the IDE launch result and Run console before retrying.", "next", status);
        };
        return with(result, details);
    }

    private static JsonObject client(Project p, RunnerAndConfigurationSettings saved) {
        var server = (JsonObject) LaunchTools.describe(p, saved).get("server");
        String build = ApplicationInfo.getInstance().getBuild().asStringWithoutProductCode();
        String requirement = "pydevd-pycharm~=" + build;
        return object("outcome", "client_instructions", "server", server,
                "message", "In the target environment, use its Python interpreter to check the import. Install the client only if needed. After the IDE server starts, run the snippet in the process to debug. It pauses on connection.",
                "check", "python -c \"import pydevd_pycharm\"",
                "install", object("pip", "python -m pip install '" + requirement + "'", "uv", "uv pip install '" + requirement + "'",
                        "poetry", "poetry run python -m pip install '" + requirement + "'"),
                "offline", "If the package is unavailable, copy debugger-eggs-output/pydevd-pycharm.egg from this IDE’s Python plugin to the target and add the egg file to the target process’s PYTHONPATH.",
                "connectivity", "The target connects to the IDE. Inside a container, localhost refers to the container. Use an IDE hostname reachable from that container and allow the configured port.",
                "python", "import pydevd_pycharm\npydevd_pycharm.settrace(" + server.get("host") + ", port=" + server.get("port") + ", suspend=True)",
                "verify", call(p, "debugger_status", object()));
    }
}
