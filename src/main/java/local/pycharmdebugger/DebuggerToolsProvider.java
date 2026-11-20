package local.pycharmdebugger;

import com.intellij.mcpserver.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.*;
import com.intellij.xdebugger.breakpoints.*;
import com.intellij.xdebugger.evaluation.EvaluationMode;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.frame.*;
import com.intellij.xdebugger.frame.presentation.XValuePresentation;
import com.intellij.xdebugger.impl.XDebugSessionImpl;
import com.intellij.xdebugger.impl.frame.XWatchesViewImpl;
import com.intellij.xdebugger.impl.ui.tree.nodes.*;
import com.intellij.util.Alarm;
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations;
import kotlin.coroutines.Continuation;
import kotlinx.serialization.json.*;

import javax.swing.Icon;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** Extiende el MCP existente con operaciones sobre la sesión real del IDE. */
public final class DebuggerToolsProvider implements McpToolsProvider {
    private static final String SESSION = "\"sessionId\":{\"type\":\"string\",\"description\":\"Session ID returned by debugger_status. May be omitted when exactly one session is active.\"}";
    private static final String EXPRESSION = "\"expression\":{\"type\":\"string\"}";

    @Override
    public List<McpTool> getTools() {
        return List.of(
            tool("debugger_status", "Lists the project's debug sessions, current position, Watches and breakpoints.", "", Set.of(), true,
                (c, out) -> out.complete(status(c.project))),
            tool("debugger_control", "Controls the session and waits for the next pause (with position and frame), for the session to end, or for the timeout. If the wait times out, do not send the control again: the action may still be running.",
                SESSION + ",\"action\":{\"type\":\"string\",\"enum\":[\"step_into\",\"step_over\",\"step_out\",\"resume\",\"pause\"]},\"timeoutMs\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":30000,\"default\":10000}", Set.of("action"), false,
                DebuggerToolsProvider::control),
            tool("debugger_stack", "Reads up to 20 frames of the selected thread without evaluating their variables.", SESSION, Set.of(), true,
                DebuggerToolsProvider::stack),
            tool("debugger_evaluate", "Evaluates an expression in the selected, paused frame. Returns the debugger's presentation of the value, truncated to 4096 characters. The expression can execute code.",
                SESSION + "," + EXPRESSION, Set.of("expression"), false, DebuggerToolsProvider::evaluate),
            tool("debugger_breakpoint", "Adds or removes a real line breakpoint. condition sets a condition: omitting it keeps the existing one and an empty string removes it. Lines are 1-based.",
                "\"action\":{\"type\":\"string\",\"enum\":[\"add\",\"remove\"]},\"filePath\":{\"type\":\"string\"},\"line\":{\"type\":\"integer\",\"minimum\":1},\"condition\":{\"type\":\"string\",\"description\":\"Expression valid in the frame. Only with add. Omit to keep the existing condition; an empty string removes it.\"}",
                Set.of("action", "filePath", "line"), false, (c, out) -> out.complete(breakpoint(c))),
            tool("debugger_watch", "Adds, removes or lists expressions in PyCharm's real Watches panel. A Watch that is added is re-evaluated by the debugger at every pause.",
                SESSION + "," + EXPRESSION + ",\"action\":{\"type\":\"string\",\"enum\":[\"add\",\"remove\",\"list\"]}", Set.of("action"), false,
                (c, out) -> out.complete(watch(c)))
        );
    }

    private record Context(Project project, JsonObject args) {}

    private static McpTool tool(String name, String description, String properties, Set<String> required,
                                boolean readOnly, BiConsumer<Context, CompletableFuture<JsonObject>> operation) {
        String projectProperty = "\"projectPath\":{\"type\":\"string\",\"description\":\"Absolute path of an open project. If omitted, uses the project the MCP server is serving.\"}";
        var schema = new McpToolSchema((JsonObject) Json.Default.parseToJsonElement(
            "{" + projectProperty + (properties.isEmpty() ? "" : "," + properties) + "}"), required, Map.of(), "$defs");
        var descriptor = new McpToolDescriptor(name, name, description, description,
                new McpToolCategory("Debugger", "local.pycharmdebugger", false, false, "Debugger", "Control the IDE debugger"),
            "local.pycharmdebugger." + name, schema, null,
            new ToolAnnotations(name, readOnly, !readOnly, readOnly, !readOnly));
        return new McpTool() {
            @Override public McpToolDescriptor getDescriptor() { return descriptor; }

            @Override
            public Object call(JsonObject args, Continuation<? super McpToolCallResult> continuation) {
                var app = ApplicationManager.getApplication();
                if (app.isDispatchThread()) return result(object("error", "La llamada MCP debe ejecutarse fuera del EDT."), true);
                int waitMillis = 10000;
                if (name.equals("debugger_control")) {
                    try {
                        waitMillis = controlTimeout(args) + 1000;
                    } catch (IllegalArgumentException error) {
                        return result(object("error", error.getMessage()), true);
                    }
                }
                var out = new CompletableFuture<JsonObject>();
                Project mcpProject = McpCallInfoKt.getProjectOrNull(continuation.getContext());
                // El EDT modifica los modelos del IDE; solo el worker MCP espera la respuesta.
                app.invokeLater(() -> {
                    if (out.isDone()) return;
                    try {
                        operation.accept(new Context(project(args, mcpProject), args), out);
                    } catch (RuntimeException error) {
                        out.completeExceptionally(error);
                    }
                }, ModalityState.any());
                try {
                    return result(out.get(waitMillis, TimeUnit.MILLISECONDS), false);
                } catch (TimeoutException error) {
                    out.cancel(false);
                    return result(object("error", "El IDE no respondió dentro del plazo. Una operación ya iniciada puede seguir en curso; consulta debugger_status antes de repetirla."), true);
                } catch (InterruptedException error) {
                    out.cancel(false);
                    Thread.currentThread().interrupt();
                    return result(object("error", "Llamada MCP interrumpida."), true);
                } catch (ExecutionException error) {
                    return result(object("error", error.getCause().getMessage()), true);
                }
            }
        };
    }

    private static Project project(JsonObject args, Project fallback) {
        String path = optionalString(args, "projectPath");
        if (path != null) {
            if (!Path.of(path).isAbsolute()) throw new IllegalArgumentException("projectPath debe ser absoluto.");
            for (Project candidate : ProjectManager.getInstance().getOpenProjects()) {
                if (!candidate.isDisposed() && candidate.getBasePath() != null
                    && Path.of(candidate.getBasePath()).normalize().equals(Path.of(path).normalize())) return candidate;
            }
            throw new IllegalArgumentException("El proyecto indicado no está abierto en este IDE.");
        }
        if (fallback == null || fallback.isDisposed()) throw new IllegalArgumentException("Indica projectPath de un proyecto abierto.");
        return fallback;
    }

    private static XDebugSession session(Context c) {
        String id = optionalString(c.args, "sessionId");
        var sessions = Arrays.stream(XDebuggerManager.getInstance(c.project).getDebugSessions())
            .filter(s -> !s.isStopped()).filter(s -> id == null || sessionId(s).equals(id)).toList();
        if (sessions.size() != 1) throw new IllegalArgumentException("No hay una sesión única activa. Consulta debugger_status e indica sessionId.");
        return sessions.getFirst();
    }

    private static String sessionId(XDebugSession session) {
        if (session instanceof XDebugSessionImpl impl) return impl.getId().toString();
        throw new IllegalStateException("Esta extensión requiere una sesión local XDebugSessionImpl de PyCharm 2026.2.2.");
    }

    private static void requirePaused(XDebugSession session) {
        if (!session.isSuspended() || session.isStopped()) throw new IllegalStateException("La sesión debe estar pausada en un breakpoint.");
    }

    private static JsonObject position(XSourcePosition position) {
        return position == null ? object() : object("filePath", position.getFile().getPath(), "line", position.getLine() + 1);
    }

    private static JsonObject status(Project project) {
        var manager = XDebuggerManager.getInstance(project);
        var sessions = new ArrayList<JsonObject>();
        for (XDebugSession s : manager.getDebugSessions()) {
            XWatchesViewImpl view = watchesViewOrNull(s);
            sessions.add(object("sessionId", sessionId(s), "name", s.getSessionName(), "paused", s.isSuspended(),
                "stopped", s.isStopped(), "breakpointsMuted", s.areBreakpointsMuted(), "position", position(s.getCurrentPosition()),
                "watchesAvailable", view != null, "watches", view == null ? List.of() : watchExpressions(view)));
        }
        var breakpoints = Arrays.stream(manager.getBreakpointManager().getAllBreakpoints())
            .filter(b -> b instanceof XLineBreakpoint<?>).map(b -> breakpointInfo((XLineBreakpoint<?>) b)).toList();
        return object("projectPath", project.getBasePath(), "sessions", sessions, "breakpoints", breakpoints);
    }

    private static int controlTimeout(JsonObject args) {
        JsonElement value = args.get("timeoutMs");
        if (value == null) return 10000;
        Integer timeout = value instanceof JsonPrimitive p && !p.isString() ? JsonElementKt.getIntOrNull(p) : null;
        if (timeout == null || timeout < 1 || timeout > 30000) throw new IllegalArgumentException("timeoutMs debe estar entre 1 y 30000.");
        return timeout;
    }

    private static JsonObject controlResult(XDebugSession s, String action, JsonObject before, String outcome, boolean requested) {
        return object("sessionId", sessionId(s), "action", action, "requested", requested, "outcome", outcome,
            "positionBefore", before, "position", position(s.getCurrentPosition()),
            "paused", s.isSuspended(), "stopped", s.isStopped(),
            "frameReady", s.isSuspended() && s.getCurrentStackFrame() != null && s.getCurrentPosition() != null);
    }

    private static void control(Context c, CompletableFuture<JsonObject> out) {
        XDebugSession s = session(c);
        String action = string(c.args, "action");
        if (!Set.of("step_into", "step_over", "step_out", "resume", "pause").contains(action)) throw new IllegalArgumentException("action no admitida.");
        JsonObject before = position(s.getCurrentPosition());
        if (!action.equals("pause")) requirePaused(s);
        if (action.equals("pause") && s.isSuspended()) {
            out.complete(controlResult(s, action, before, "already_paused", false));
            return;
        }
        int timeout = controlTimeout(c.args);
        var app = ApplicationManager.getApplication();
        var waiting = Disposer.newDisposable("Debugger MCP: espera de pausa");
        Disposer.register(c.project, waiting);
        out.whenComplete((ignored, error) -> {
            Runnable cleanup = () -> Disposer.dispose(waiting);
            if (app.isDispatchThread()) cleanup.run();
            else app.invokeLater(cleanup, ModalityState.any());
        });
        // Se escucha antes del control; una pausa en la misma línea también cuenta.
        s.addSessionListener(new XDebugSessionListener() {
            private boolean pauseObserved;

            private void completeIfReady() {
                if (pauseObserved && s.isSuspended() && s.getCurrentStackFrame() != null && s.getCurrentPosition() != null) {
                    out.complete(controlResult(s, action, before, "paused", true));
                }
            }

            @Override public void sessionPaused() { pauseObserved = true; completeIfReady(); }
            @Override public void stackFrameChanged() { completeIfReady(); }
            @Override public void stackFrameChanged(boolean isTopFrame) { completeIfReady(); }
            @Override public void sessionStopped() { out.complete(controlResult(s, action, before, "stopped", true)); }
        }, waiting);
        new Alarm(Alarm.ThreadToUse.SWING_THREAD, waiting).addRequest(
            () -> out.complete(controlResult(s, action, before, "timeout", true)), timeout, ModalityState.any());
        switch (action) {
            case "step_into" -> s.stepInto();
            case "step_over" -> s.stepOver(false);
            case "step_out" -> s.stepOut();
            case "resume" -> s.resume();
            case "pause" -> s.pause();
            default -> throw new IllegalArgumentException("action no admitida.");
        }
    }

    private static void stack(Context c, CompletableFuture<JsonObject> out) {
        XDebugSession s = session(c);
        requirePaused(s);
        var suspended = s.getSuspendContext();
        var executionStack = ((XDebugSessionImpl) s).getCurrentExecutionStack();
        if (executionStack == null) throw new IllegalStateException("No hay un thread seleccionado.");
        JsonObject selectedPosition = position(s.getCurrentPosition());
        var frames = new ArrayList<JsonObject>();
        executionStack.computeStackFrames(0, new XExecutionStack.XStackFrameContainer() {
            @Override public boolean isObsolete() { return out.isDone() || s.getSuspendContext() != suspended; }
            @Override public void errorOccurred(String message) { out.completeExceptionally(new IllegalStateException(message)); }
            @Override public void addStackFrames(List<? extends XStackFrame> batch, boolean last) {
                if (isObsolete()) return;
                int remaining = 20 - frames.size();
                for (XStackFrame frame : batch.subList(0, Math.min(remaining, batch.size()))) {
                    frames.add(object("index", frames.size(), "position", position(frame.getSourcePosition())));
                }
                if (last || frames.size() == 20) out.complete(object("sessionId", sessionId(s), "frames", frames,
                    "selectedPosition", selectedPosition, "moreFrames", !last || batch.size() > remaining));
            }
        });
    }

    private static void evaluate(Context c, CompletableFuture<JsonObject> out) {
        XDebugSession s = session(c);
        requirePaused(s);
        XStackFrame frame = s.getCurrentStackFrame();
        XDebuggerEvaluator evaluator = frame == null ? null : frame.getEvaluator();
        if (evaluator == null) throw new IllegalStateException("El frame seleccionado no permite evaluar expresiones.");
        String expression = string(c.args, "expression");
        var suspended = s.getSuspendContext();
        evaluator.evaluate(expression, new XDebuggerEvaluator.XEvaluationCallback() {
            @Override public void errorOccurred(String message) { out.completeExceptionally(new IllegalArgumentException(message)); }
            @Override public void evaluated(XValue value) {
                value.computePresentation(new XValueNode() {
                    @Override public boolean isObsolete() { return out.isDone() || s.getSuspendContext() != suspended; }
                    @Override public void setFullValueEvaluator(XFullValueEvaluator ignored) {}
                    @Override public void setPresentation(Icon icon, String type, String text, boolean hasChildren) {
                        if (isObsolete()) return;
                        out.complete(object("sessionId", sessionId(s), "expression", expression, "position", position(frame.getSourcePosition()),
                            "type", type, "value", text.substring(0, Math.min(text.length(), 4096)),
                            "hasChildren", hasChildren, "truncated", text.length() > 4096, "representation", "debugger preview"));
                    }
                    @Override public void setPresentation(Icon icon, XValuePresentation presentation, boolean hasChildren) {
                        if (!presentation.isAsync()) setPresentation(icon, presentation.getType(), XValuePresentationUtil.computeValueText(presentation), hasChildren);
                    }
                }, XValuePlace.TREE);
            }
        }, frame.getSourcePosition());
    }

    private static JsonObject breakpoint(Context c) {
        String action = string(c.args, "action");
        if (!Set.of("add", "remove").contains(action)) throw new IllegalArgumentException("action debe ser add o remove.");
        boolean changeCondition = c.args.containsKey("condition");
        if (changeCondition && action.equals("remove")) throw new IllegalArgumentException("condition solo se admite con add.");
        String condition = optionalString(c.args, "condition");
        Path path = Path.of(string(c.args, "filePath"));
        if (!path.isAbsolute()) path = Path.of(Objects.requireNonNull(c.project.getBasePath())).resolve(path);
        VirtualFile file = LocalFileSystem.getInstance().findFileByNioFile(path.normalize());
        if (file == null || file.isDirectory()) throw new IllegalArgumentException("No se encuentra el archivo en el IDE.");
        JsonElement lineValue = c.args.get("line");
        Integer oneBased = lineValue instanceof JsonPrimitive p && !p.isString() ? JsonElementKt.getIntOrNull(p) : null;
        var document = FileDocumentManager.getInstance().getDocument(file);
        if (oneBased == null || oneBased < 1 || document == null || oneBased > document.getLineCount()) throw new IllegalArgumentException("line debe estar dentro del archivo y empezar en 1.");
        int line = oneBased - 1;
        var manager = XDebuggerManager.getInstance(c.project).getBreakpointManager();
        var existing = Arrays.stream(manager.getAllBreakpoints()).filter(b -> b instanceof XLineBreakpoint<?> lb
            && lb.getFileUrl().equals(file.getUrl()) && lb.getLine() == line).map(b -> (XLineBreakpoint<?>) b).toList();
        if (existing.size() > 1) throw new IllegalArgumentException("Hay varios breakpoints en esa línea; resuelve la ambigüedad en el IDE.");
        if (action.equals("remove")) {
            WriteAction.run(() -> existing.forEach(manager::removeBreakpoint));
            return object("removed", !existing.isEmpty(), "filePath", file.getPath(), "line", oneBased);
        }
        if (!existing.isEmpty()) {
            WriteAction.run(() -> {
                if (changeCondition) setCondition(existing.getFirst(), condition);
                existing.getFirst().setEnabled(true);
            });
            return breakpointInfo(existing.getFirst());
        }
        var types = Arrays.stream(XDebuggerUtil.getInstance().getLineBreakpointTypes())
            .filter(t -> t.canPutAt(file, line, c.project)).toList();
        if (types.size() != 1) throw new IllegalArgumentException("El IDE no identifica un único tipo de breakpoint para esa línea ejecutable.");
        return addBreakpoint(manager, types.getFirst(), file, line, condition);
    }

    private static void setCondition(XLineBreakpoint<?> breakpoint, String condition) {
        breakpoint.setConditionExpression(condition == null || condition.isBlank() ? null
            : XDebuggerUtil.getInstance().createExpression(condition, null, null, EvaluationMode.EXPRESSION));
    }

    private static <P extends XBreakpointProperties> JsonObject addBreakpoint(XBreakpointManager manager, XLineBreakpointType<P> type, VirtualFile file, int line, String condition) {
        return WriteAction.compute(() -> {
            var breakpoint = manager.addLineBreakpoint(type, file.getUrl(), line, type.createBreakpointProperties(file, line));
            setCondition(breakpoint, condition);
            return breakpointInfo(breakpoint);
        });
    }

    private static JsonObject breakpointInfo(XLineBreakpoint<?> b) {
        return object("position", position(b.getSourcePosition()), "enabled", b.isEnabled(), "type", b.getType().getId(),
            "condition", b.getConditionExpression() == null ? null : b.getConditionExpression().getExpression());
    }

    private static XWatchesViewImpl watchesViewOrNull(XDebugSession session) {
        if (!(session instanceof XDebugSessionImpl impl)) return null;
        var tab = impl.getSessionTabIfInitialized();
        return tab != null && tab.getWatchesView() instanceof XWatchesViewImpl view
            && view.getTree().getRoot() instanceof WatchesRootNode ? view : null;
    }

    private static WatchesRootNode watchesRoot(XWatchesViewImpl view) {
        if (view.getTree().getRoot() instanceof WatchesRootNode root) return root;
        throw new IllegalStateException("El panel Watches todavía no está disponible.");
    }

    private static List<String> watchExpressions(XWatchesViewImpl view) {
        return watchesRoot(view).getWatchChildren().stream().map(w -> w.getExpression().getExpression()).toList();
    }

    private static JsonObject watch(Context c) {
        XDebugSession s = session(c);
        XWatchesViewImpl view = watchesViewOrNull(s);
        if (view == null) throw new IllegalStateException("La sesión todavía no tiene un panel Watches local.");
        String action = string(c.args, "action");
        if (!action.equals("list")) {
            String expression = string(c.args, "expression");
            switch (action) {
                case "add" -> {
                    if (!watchExpressions(view).contains(expression)) {
                        // Modifica el mismo modelo que muestra PyCharm, no una lista externa.
                        view.addWatchExpression(XDebuggerUtil.getInstance().createExpression(expression, null, null, EvaluationMode.EXPRESSION), -1, false);
                    }
                }
                case "remove" -> {
                    var nodes = watchesRoot(view).getWatchChildren().stream()
                        .filter(w -> w.getExpression().getExpression().equals(expression))
                        .map(w -> (XDebuggerTreeNode) w).toList();
                    view.removeWatches(nodes);
                }
                default -> throw new IllegalArgumentException("action debe ser add, remove o list.");
            }
        }
        return object("sessionId", sessionId(s), "watches", watchExpressions(view));
    }

    private static String optionalString(JsonObject args, String key) {
        JsonElement value = args.get(key);
        if (value == null || value == JsonNull.INSTANCE) return null;
        if (!(value instanceof JsonPrimitive p) || !p.isString()) throw new IllegalArgumentException(key + " debe ser un string.");
        return p.getContent();
    }

    private static String string(JsonObject args, String key) {
        String value = optionalString(args, key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Falta " + key + ".");
        return value;
    }

    private static JsonObject object(Object... fields) {
        var map = new LinkedHashMap<String, JsonElement>();
        for (int i = 0; i < fields.length; i += 2) map.put((String) fields[i], json(fields[i + 1]));
        return new JsonObject(map);
    }

    private static JsonElement json(Object value) {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof JsonElement element) return element;
        if (value instanceof String text) return JsonElementKt.JsonPrimitive(text);
        if (value instanceof Boolean flag) return JsonElementKt.JsonPrimitive(flag);
        if (value instanceof Number number) return JsonElementKt.JsonPrimitive(number);
        if (value instanceof Collection<?> values) return new JsonArray(values.stream().map(DebuggerToolsProvider::json).toList());
        throw new IllegalArgumentException("Tipo JSON no admitido.");
    }

    private static McpToolCallResult result(JsonObject value, boolean error) {
        return new McpToolCallResult(new McpToolCallResultContent[]{new McpToolCallResultContent.Text(value.toString())}, value, error);
    }
}
