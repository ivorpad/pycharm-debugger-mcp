package local.pycharmdebugger;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.configurations.RuntimeConfigurationException;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.ide.plugins.PluginEnabler;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.PluginsAdvertiser;
import com.intellij.util.PathMappingSettings;
import kotlinx.serialization.json.JsonObject;
import kotlinx.serialization.json.JsonPrimitive;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static local.pycharmdebugger.DebuggerToolsProvider.*;

final class DapSetup {
    private static final String TYPE = "PythonDapAttachConfiguration";
    private static final PluginId PLUGIN = PluginId.getId("intellij.python.dap.plugin");
    private static final String TOOL = "debugger_setup";

    private static boolean flag(JsonObject args, String key, boolean fallback) {
        var value = args.get(key);
        if (value == null) return fallback;
        if (!(value instanceof JsonPrimitive flag) || flag.isString() || !Set.of("true", "false").contains(flag.getContent()))
            throw new IllegalArgumentException(key + " must be a boolean.");
        return Boolean.parseBoolean(flag.getContent());
    }

    static void setup(Project project, JsonObject args, CompletableFuture<JsonObject> out) {
        var type = ConfigurationType.CONFIGURATION_TYPE_EP.getExtensionList().stream()
                .filter(t -> TYPE.equals(t.getId())).findFirst().orElse(null);
        boolean install = flag(args, "installRunner", false);
        boolean start = flag(args, "start", true);
        if (type == null) {
            var descriptor = PluginManagerCore.getPlugin(PLUGIN);
            boolean disabled = PluginManagerCore.isDisabled(PLUGIN);
            var next = SetupTool.call(project, TOOL, SetupTool.with(args, object("installRunner", !install)));
            if (descriptor != null && !disabled) {
                out.complete(object("outcome", "runner_inactive", "pluginId", PLUGIN.getIdString(),
                        "message", "The plugin is installed but the attach configuration is unavailable. Restart PyCharm if prompted and check its Python plugin dependencies.", "next", SetupTool.call(project, TOOL, args)));
            } else if (install && descriptor != null) {
                PluginEnabler.getInstance().enable(Set.of(descriptor));
                out.complete(object("outcome", "enable_requested", "pluginId", PLUGIN.getIdString(),
                        "message", "PyCharm was asked to enable its installed Python DAP Debugger. Restart if prompted, then follow next.", "next", next));
            } else {
                if (install) PluginsAdvertiser.installAndEnable(project, Set.of(PLUGIN), () -> {});
                out.complete(object("outcome", install ? "installation_requested" : "runner_unavailable", "pluginId", PLUGIN.getIdString(), "disabled", disabled,
                        "message", install ? "Complete the PyCharm plugin installer and restart if prompted. Then follow next to check availability."
                                : "Follow next to install or enable JetBrains Python DAP Debugger for this IDE build.", "next", next));
            }
            return;
        }
        var manager = RunManager.getInstance(project);
        String name = optionalString(args, "configurationName");
        if (name == null) {
            var existing = manager.getAllSettings().stream().filter(s -> TYPE.equals(s.getType().getId())).toList();
            if (existing.size() > 1) {
                out.complete(object("outcome", "choose_configuration", "choices", existing.stream()
                        .map(s -> SetupTool.call(project, TOOL, SetupTool.with(args, object("configurationName", s.getName())))).toList()));
                return;
            }
            name = existing.isEmpty() ? "Python DAP attach (MCP)" : existing.getFirst().getName();
        } else name = string(args, "configurationName");
        var saved = LaunchTools.named(project, name);
        if (saved != null && !TYPE.equals(saved.getType().getId())) throw new IllegalArgumentException("Choose a different name. " + name + " is a " + saved.getType().getDisplayName() + " configuration.");
        if (saved == null && !args.containsKey("port")) {
            out.complete(object("outcome", "needs_input", "required", Set.of("port"),
                    "message", "Start the target with debugpy listening, then supply its port as reachable from the IDE. host defaults to 127.0.0.1. For a container, use its published port and remoteRoot for the source directory.",
                    "exampleTargetCommand", "python -m debugpy --listen 127.0.0.1:5678 --wait-for-client YOUR_SCRIPT.py",
                    "targetPackage", "debugpy", "next", SetupTool.call(project, TOOL, SetupTool.with(args, object("configurationName", name)))));
            return;
        }
        var settings = saved == null ? manager.createConfiguration(name, type.getConfigurationFactories()[0]) : saved;
        if (saved == null || args.containsKey("host") || args.containsKey("port") || args.containsKey("remoteRoot")) {
            if (!configure(project, args, settings, saved == null, out)) return;
            if (saved == null) manager.addConfiguration(settings);
        }
        if (start) SetupTool.launch(project, settings, out);
        else out.complete(object("outcome", saved == null ? "created" : "configured", "configurationName", name,
                "next", SetupTool.call(project, TOOL, object("mode", "dap", "configurationName", name))));
    }

    // The backend is optional. Use its public configuration accessors only after its type is registered.
    private static boolean configure(Project p, JsonObject args, RunnerAndConfigurationSettings settings, boolean create,
                                     CompletableFuture<JsonObject> out) {
        var config = settings.getConfiguration();
        var cls = config.getClass();
        try {
            String current = (String) cls.getMethod("getRemoteAddress").invoke(config);
            String host = "127.0.0.1";
            int port = 0;
            if (!create && current != null && current.lastIndexOf(':') > 0) {
                int split = current.lastIndexOf(':');
                host = current.substring(0, split);
                port = Integer.parseInt(current.substring(split + 1));
            }
            if (args.containsKey("host")) host = string(args, "host");
            port = integer(args, "port", port, 1, 65535);
            String address = host + ":" + port;
            var mappings = (PathMappingSettings) cls.getMethod("getPathMappingSettings").invoke(config);
            String remote = args.containsKey("remoteRoot") ? string(args, "remoteRoot") : null;
            if (remote != null && !remote.startsWith("/") && !remote.matches("^[A-Za-z]:[/\\\\].*") && !remote.startsWith("\\\\"))
                throw new IllegalArgumentException("remoteRoot must be an absolute directory in the debug target.");
            if (remote != null && p.getBasePath() == null) throw new IllegalArgumentException("A project directory is required for source mapping.");
            if (!create) {
                boolean matches = remote == null || mappings.getPathMappings().stream()
                        .anyMatch(m -> p.getBasePath().equals(m.getLocalRoot()) && remote.equals(m.getRemoteRoot()));
                if (!address.equals(current) || !matches) {
                    out.complete(object("outcome", "settings_differ", "currentAddress", current,
                            "message", "Use the saved settings below, edit this configuration in PyCharm, or supply a distinct configurationName to create another endpoint.",
                            "next", SetupTool.call(p, TOOL, object("mode", "dap", "configurationName", settings.getName()))));
                    return false;
                }
                return true;
            }
            cls.getMethod("setRemoteAddress", String.class).invoke(config, address);
            if (remote != null) {
                var roots = new PathMappingSettings();
                roots.addMapping(p.getBasePath(), remote);
                cls.getMethod("setPathMappingSettings", PathMappingSettings.class).invoke(config, roots);
            }
            config.checkConfiguration();
            return true;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("This Python DAP Debugger version does not expose the expected attach settings.", e);
        } catch (RuntimeConfigurationException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }
}
