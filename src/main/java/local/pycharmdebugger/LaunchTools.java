package local.pycharmdebugger;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.PsiLocation;
import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.ExecutionTargetManager;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunManagerListener;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.configurations.RuntimeConfigurationException;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.mcpserver.McpTool;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.JDOMExternalizerUtil;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.psi.PsiManager;
import com.intellij.util.PathMappingSettings;
import com.intellij.util.xmlb.XmlSerializer;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import kotlinx.serialization.json.JsonArray;
import kotlinx.serialization.json.JsonObject;
import org.jdom.Element;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static local.pycharmdebugger.DebuggerToolsProvider.*;

final class LaunchTools {
    private static final String PYDEVD = "PyRemoteDebugConfigurationType";
    private static final String NAME = "\"configurationName\":{\"type\":\"string\",\"description\":\"Exact project run configuration name.\"}";
    private static final String BACKEND = "\"backend\":{\"type\":\"string\",\"enum\":[\"pydevd\"]}";
    // Access the map on the IDE dispatch thread. Each attempt completes independently of its caller's timeout.
    private static final Key<Map<String, CompletableFuture<JsonObject>>> LAUNCHES = Key.create("pycharmdebugger.launches");

    static McpTool configurationsTool() {
        return tool("debugger_configurations", "Lists saved configurations, Debug runner availability, pydevd server settings and the last MCP launch outcome.", "", Set.of(), true,
                (c, out) -> {
                    var rows = RunManager.getInstance(c.project()).getAllSettings().stream()
                            .map(s -> describe(c.project(), s)).toList();
                    out.complete(object("pydevdServerAvailable", serverType() != null, "configurations", rows));
                });
    }

    static McpTool configureTool() {
        return tool("debugger_configure_pydevd", "Creates or updates a Python Debug Server configuration. Port is required for creation. Omitted fields preserve existing values. host is the IDE address as seen by the client.",
                NAME + "," + BACKEND + ",\"port\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":65535},\"host\":{\"type\":\"string\"},"
                + "\"sourceMappings\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"localRoot\":{\"type\":\"string\"},\"remoteRoot\":{\"type\":\"string\"}},\"required\":[\"localRoot\",\"remoteRoot\"]}}",
                Set.of("configurationName"), false, (c, out) -> out.complete(configure(c.project(), c.args())));
    }

    static McpTool launchTool() {
        return tool("debugger_launch", "Starts a named configuration or a project file and optional 1-based line with PyCharm's Debug executor. Reuses an active matching session. A starting result means the IDE has not confirmed startup yet; check debugger_configurations.",
                NAME + ",\"filePath\":{\"type\":\"string\"},\"line\":{\"type\":\"integer\",\"minimum\":1},\"executor\":{\"type\":\"string\",\"enum\":[\"Debug\"]}", Set.of(), false,
                (c, out) -> {
                    if (c.args().containsKey("executor") && !"Debug".equals(string(c.args(), "executor")))
                        throw new IllegalArgumentException("executor must be Debug");
                    boolean byName = c.args().containsKey("configurationName");
                    if (byName == c.args().containsKey("filePath") || (byName && c.args().containsKey("line")))
                        throw new IllegalArgumentException("Use configurationName or filePath with an optional line.");
                    var settings = byName ? named(c.project(), string(c.args(), "configurationName")) : fromFile(c.project(), c.args());
                    if (settings == null) throw new IllegalArgumentException("Configuration not found in this project. See debugger_configurations.");
                    start(c.project(), settings, out);
                });
    }

    private static RunnerAndConfigurationSettings fromFile(Project project, JsonObject args) {
        Path path = Path.of(string(args, "filePath"));
        if (!path.isAbsolute()) {
            if (project.getBasePath() == null) throw new IllegalArgumentException("Supply an absolute project file path.");
            path = Path.of(project.getBasePath()).resolve(path);
        }
        var file = LocalFileSystem.getInstance().findFileByNioFile(path.normalize());
        if (file == null || file.isDirectory() || !ProjectFileIndex.getInstance(project).isInContent(file))
            throw new IllegalArgumentException("File is outside this project's content or does not exist: " + path);
        var psi = PsiManager.getInstance(project).findFile(file);
        if (psi == null) throw new IllegalArgumentException("PyCharm cannot resolve a run target in " + path);
        com.intellij.psi.PsiElement element = psi;
        if (args.containsKey("line")) {
            var doc = FileDocumentManager.getInstance().getDocument(file);
            if (doc == null) throw new IllegalArgumentException("The target has no text document");
            int index = integer(args, "line", 1, 1, doc.getLineCount()) - 1;
            int offset = doc.getLineStartOffset(index);
            while (offset < doc.getLineEndOffset(index) && Character.isWhitespace(doc.getCharsSequence().charAt(offset))) offset++;
            if (offset == doc.getLineEndOffset(index)) throw new IllegalArgumentException("Select a runnable line in the file");
            element = psi.findElementAt(offset);
            if (element == null) throw new IllegalArgumentException("No code at the selected line");
        }
        var context = ConfigurationContext.createEmptyContextForLocation(new PsiLocation<>(project, element));
        var existing = context.findExisting();
        if (existing != null) return existing;
        var settings = context.getConfiguration();
        if (settings == null) throw new IllegalArgumentException("No Debug configuration producer for this location. Select a runnable line or a saved configuration.");
        var manager = RunManager.getInstance(project);
        manager.setUniqueNameIfNeeded(settings);
        manager.setTemporaryConfiguration(settings);
        return settings;
    }

    private static ConfigurationType serverType() {
        return ConfigurationType.CONFIGURATION_TYPE_EP.getExtensionList().stream()
                .filter(type -> PYDEVD.equals(type.getId())).findFirst().orElse(null);
    }

    private static RunnerAndConfigurationSettings named(Project project, String name) {
        var matches = RunManager.getInstance(project).getAllSettings().stream().filter(s -> name.equals(s.getName())).toList();
        if (matches.size() > 1) throw new IllegalArgumentException("Multiple configurations named " + name + ". Give them distinct names in PyCharm.");
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private static Element snapshot(RunnerAndConfigurationSettings settings) {
        var xml = new Element("configuration");
        settings.getConfiguration().writeExternal(xml);
        return xml;
    }

    private static JsonObject describe(Project project, RunnerAndConfigurationSettings settings) {
        var attempts = project.getUserData(LAUNCHES);
        var last = attempts == null ? null : attempts.get(settings.getUniqueID());
        JsonObject server = null;
        if (PYDEVD.equals(settings.getType().getId())) {
            var xml = snapshot(settings);
            var mapping = PathMappingSettings.readExternal(xml);
            var roots = mapping == null ? List.of() : mapping.getPathMappings().stream()
                    .map(m -> object("localRoot", m.getLocalRoot(), "remoteRoot", m.getRemoteRoot())).toList();
            server = object("backend", "pydevd", "host", JDOMExternalizerUtil.readField(xml, "HOST"),
                    "port", Integer.valueOf(JDOMExternalizerUtil.readField(xml, "PORT")), "sourceMappings", roots);
        }
        return object("configurationName", settings.getName(), "typeId", settings.getType().getId(),
                "canDebug", ProgramRunner.getRunner("Debug", settings.getConfiguration()) != null,
                "server", server, "lastLaunch", last == null ? null : last.getNow(object("outcome", "starting")));
    }

    private static JsonObject configure(Project project, JsonObject args) {
        if (args.containsKey("backend") && !"pydevd".equals(string(args, "backend"))) throw new IllegalArgumentException("backend must be pydevd");
        var type = serverType();
        if (type == null) throw new IllegalStateException("Python Debug Server is unavailable in this IDE. Check the Python plugin and PyCharm edition.");
        String name = string(args, "configurationName");
        var manager = RunManager.getInstance(project);
        var settings = named(project, name);
        boolean create = settings == null;
        if (create) {
            if (!args.containsKey("port")) throw new IllegalArgumentException("Specify the container launcher's port for the new configuration.");
            settings = manager.createConfiguration(name, type.getConfigurationFactories()[0]);
        } else if (!PYDEVD.equals(settings.getType().getId())) {
            throw new IllegalArgumentException(name + " is a " + settings.getType().getDisplayName() + " configuration. Choose a distinct pydevd configuration name.");
        }
        var xml = snapshot(settings);
        if (args.containsKey("port")) field(xml, "PORT", String.valueOf(integer(args, "port", -1, 1, 65535)));
        if (args.containsKey("host")) field(xml, "HOST", string(args, "host"));
        if (args.containsKey("sourceMappings")) {
            if (!(args.get("sourceMappings") instanceof JsonArray rows)) throw new IllegalArgumentException("sourceMappings must be an array");
            var mapping = new PathMappingSettings();
            var remoteRoots = new HashSet<String>();
            for (var row : rows) {
                if (!(row instanceof JsonObject paths)) throw new IllegalArgumentException("Each source mapping needs localRoot and remoteRoot");
                var local = Path.of(string(paths, "localRoot"));
                if (!local.isAbsolute()) {
                    if (project.getBasePath() == null) throw new IllegalArgumentException("A project directory is required for relative localRoot");
                    local = Path.of(project.getBasePath()).resolve(local);
                }
                String remote = string(paths, "remoteRoot");
                if (!remote.startsWith("/") && !remote.matches("^[A-Za-z]:[/\\\\].*") && !remote.startsWith("\\\\"))
                    throw new IllegalArgumentException("remoteRoot must be absolute in the target environment");
                if (!remoteRoots.add(remote)) throw new IllegalArgumentException("Duplicate remoteRoot: " + remote);
                mapping.addMapping(local.normalize().toString(), remote);
            }
            xml.removeChild("PathMappingSettings");
            xml.addContent(XmlSerializer.serialize(mapping));
        }
        // Validate a detached copy before changing the live settings.
        var trial = settings.getConfiguration().clone();
        trial.readExternal(xml);
        try { trial.checkConfiguration(); }
        catch (RuntimeConfigurationException e) { throw new IllegalArgumentException(e.getMessage(), e); }
        settings.getConfiguration().readExternal(xml);
        if (create) manager.addConfiguration(settings);
        else project.getMessageBus().syncPublisher(RunManagerListener.TOPIC).runConfigurationChanged(settings);
        return describe(project, settings);
    }

    private static void field(Element xml, String name, String value) {
        xml.getChildren("option").removeIf(option -> name.equals(option.getAttributeValue("name")));
        JDOMExternalizerUtil.writeField(xml, name, value);
    }

    private static List<XDebugSession> matching(Project project, RunnerAndConfigurationSettings settings) {
        return Arrays.stream(XDebuggerManager.getInstance(project).getDebugSessions()).filter(s -> !s.isStopped())
                .filter(s -> s.getExecutionEnvironment() != null && s.getExecutionEnvironment().getRunnerAndConfigurationSettings() == settings).toList();
    }

    private static void start(Project project, RunnerAndConfigurationSettings settings, CompletableFuture<JsonObject> out) {
        var sessions = matching(project, settings);
        if (!sessions.isEmpty()) {
            out.complete(object("outcome", "already_active", "sessionIds", sessions.stream().map(DebuggerToolsProvider::sessionId).toList()));
            return;
        }
        var attempts = project.getUserData(LAUNCHES);
        if (attempts == null) { attempts = new HashMap<>(); project.putUserData(LAUNCHES, attempts); }
        String id = settings.getUniqueID();
        var previous = attempts.get(id);
        if (previous != null && !previous.isDone()) {
            out.complete(object("outcome", "starting", "configurationName", settings.getName()));
            return;
        }
        var debug = DefaultDebugExecutor.getDebugExecutorInstance();
        if (debug == null || ProgramRunner.getRunner("Debug", settings.getConfiguration()) == null)
            throw new IllegalStateException("No Debug runner for " + settings.getName() + " (" + settings.getType().getDisplayName() + "). Check the IDE plugin for this configuration type.");
        try {
            settings.checkSettings(debug);
            var env = ExecutionEnvironmentBuilder.create(debug, settings).activeTarget().build();
            if (!ExecutionTargetManager.canRun(env)) throw new IllegalStateException("The active execution target cannot debug " + settings.getName());
            if (ExecutionManager.getInstance(project).isStarting(env)) {
                out.complete(object("outcome", "starting", "configurationName", settings.getName()));
                return;
            }
            var attempt = new CompletableFuture<JsonObject>();
            attempts.put(id, attempt);
            env.assignNewExecutionId();
            run(settings, env, attempt);
            attempt.copy().completeOnTimeout(object("outcome", "starting", "executionId", env.getExecutionId(), "configurationName", settings.getName()),
                    5, TimeUnit.SECONDS).thenAccept(out::complete);
        } catch (ExecutionException | RuntimeConfigurationException e) {
            throw new IllegalArgumentException("Cannot debug " + settings.getName() + ": " + e.getMessage(), e);
        }
    }

    private static void run(RunnerAndConfigurationSettings settings, ExecutionEnvironment env,
                            CompletableFuture<JsonObject> attempt) {
        var callback = new ProgramRunner.Callback() {
            @Override public void processStarted(RunContentDescriptor descriptor) {
                var handler = descriptor == null ? null : descriptor.getProcessHandler();
                String state = handler == null ? "failed" : handler.isProcessTerminated() ? "exited" : "started";
                attempt.complete(object("outcome", state, "configurationName", settings.getName(), "executionId", env.getExecutionId(),
                        "exitCode", handler == null ? null : handler.getExitCode()));
            }
            @Override public void processNotStarted() { processNotStarted(null); }
            @Override public void processNotStarted(Throwable error) {
                attempt.complete(object("outcome", "failed", "configurationName", settings.getName(), "executionId", env.getExecutionId(),
                        "error", error == null ? "IDE launch cancelled or failed" : error.toString()));
            }
        };
        env.setCallback(callback);
        try { env.getRunner().execute(env); }
        catch (ExecutionException | RuntimeException e) { callback.processNotStarted(e); }
    }

    static void stopOrRestart(XDebugSession session, boolean restart, int timeout, CompletableFuture<JsonObject> out) {
        var env = session.getExecutionEnvironment();
        var settings = env == null ? null : env.getRunnerAndConfigurationSettings();
        var project = session.getProject();
        if (restart && (settings == null || !RunManager.getInstance(project).getAllSettings().contains(settings)))
            throw new IllegalArgumentException("Restart requires a saved configuration in the open project.");
        if (restart && matching(project, settings).size() > 1) throw new IllegalArgumentException("Other sessions use this configuration. Stop them or launch separately.");
        var handler = session.getDebugProcess().getProcessHandler();
        String id = sessionId(session);
        if (handler.isProcessTerminating()) throw new IllegalStateException("Session is already stopping. Check debugger_status.");
        session.stop();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (!handler.waitFor(timeout)) {
                out.complete(object("outcome", "stopping", "sessionId", id, "restartScheduled", false));
                return;
            }
            if (!restart) { out.complete(object("outcome", "stopped", "sessionId", id)); return; }
            ApplicationManager.getApplication().invokeLater(() -> {
                if (out.isDone()) return;
                try { start(project, settings, out); }
                catch (RuntimeException e) { out.completeExceptionally(e); }
            }, ModalityState.any());
        });
    }
}
