# Debugger MCP Extension for PyCharm

Launch and control PyCharm debug sessions through JetBrains' built-in MCP server. An MCP client can start a project file, launch a saved Debug configuration, or configure a Python Debug Server for a process running in a container.

It drives the same `XDebugSession` the UI drives. Breakpoints appear in the gutter, Watches appear
in the Watches panel, and a step taken over MCP moves the editor the same way clicking Step Over
does. There is no separate debug adapter and no desktop automation.

## Requirements

- PyCharm with the bundled debugger and MCP APIs. The extension handles the tool descriptor formats in 2026.2.2 and 2026.3.0.1.
- The bundled **MCP Server** plugin (`com.intellij.mcpServer`) enabled, with its server running.
- For local Python files, configure the project or module interpreter in PyCharm. For incoming pydevd connections, the IDE must offer **Python Debug Server** in **Run → Edit Configurations**.

## Repository layout

```
src/main/java/local/pycharmdebugger/DebuggerToolsProvider.java   debugger tools
src/main/resources/META-INF/plugin.xml                           plugin descriptor
build.gradle.kts  settings.gradle.kts  gradle/  gradlew          Gradle build
dist/pycharm-debugger-mcp-0.1.6.zip                              installable plugin
```

Tool and parameter descriptions are in English. Code comments and error messages are still in Spanish.

## Building

Needs JDK 25, not 21: PyCharm 2026.3 ships Java 25 class files and javac refuses to read them on
an older release. The wrapper and the foojay toolchain resolver fetch Gradle and the JDK if they
are missing, and the first build pulls down PyCharm 2026.3.0.1, a bit over a gigabyte. After that a
build is about ten seconds.

```sh
./gradlew buildPlugin
```

The build writes the installable ZIP to `dist/`. To compile against a local PyCharm 2026.3.0.1 installation on macOS, pass `-PpycharmPath=/Applications/PyCharm.app`.

## Install

Open **Settings → Plugins → ⚙ → Install Plugin from Disk…** and select [pycharm-debugger-mcp-0.1.6.zip](dist/pycharm-debugger-mcp-0.1.6.zip). Enable the bundled MCP Server and restart PyCharm if prompted.

After the restart the tools show up in the MCP server's catalog under the `Debugger` category.

## Tools

Every tool takes an optional `projectPath` (absolute path of an open project). Omit it and the call
uses whichever project the MCP server is serving. Session tools take an optional `sessionId`; omit
it when exactly one session is active, and the plugin resolves it. With two or more sessions open
an omitted `sessionId` is an error rather than a guess.

| Tool | Required | Does |
| --- | --- | --- |
| `debugger_configurations` | | Lists project configurations, Debug runner availability, pydevd settings and the last MCP launch outcome |
| `debugger_configure_pydevd` | `configurationName`, `port` on creation | Creates or updates a native Python Debug Server with optional `host` and `sourceMappings` |
| `debugger_launch` | `configurationName` or `filePath` | Launches with the Debug executor. A project file can include a 1-based `line` to select a runnable target |
| `debugger_status` | — | Sessions, current position, Watches, breakpoints, `breakpointsMuted` |
| `debugger_control` | `action` | Step, resume, pause, stop, or restart a session |
| `debugger_stack` | — | Up to 20 frames of the selected thread, positions only, no variable evaluation |
| `debugger_evaluate` | `expression` | Evaluates in the selected paused frame |
| `debugger_breakpoint` | `action`, `filePath`, `line` | Adds or removes a line breakpoint, with an optional `condition` |
| `debugger_watch` | `action` | `add` / `remove` / `list` against the real Watches panel |
| `debugger_origin` | `name`, `action` | One-shot change monitor, its status, or paged origin and history |

`debugger_configurations`, `debugger_status` and `debugger_stack` are annotated read-only. The other tools are annotated
destructive, because they change shared IDE state or run code in the debuggee.

### Start a container session with pydevd

If the project already has a Python Debug Server configuration, call `debugger_launch` with its exact name. This uses the IDE's Debug executor without UI automation.

```json
{"configurationName":"Wave pydevd (container)","executor":"Debug"}
```

For a new project, call `debugger_configure_pydevd` first. This example maps the project root on the current machine to `/app` in a Docker Desktop container. Use the port and source directory from your container launcher and a hostname the container can reach.

```json
{
  "configurationName": "Wave pydevd (container)",
  "backend": "pydevd",
  "host": "host.docker.internal",
  "port": 5679,
  "sourceMappings": [{"localRoot": ".", "remoteRoot": "/app"}]
}
```

Launch the configuration, then run your prepared pydevd client in the container. The container needs a PyCharm-compatible pydevd client. The host field supplies the IDE address for that client. PyCharm opens the listening socket. A `started` result confirms IDE startup. A breakpoint hit confirms the client connected and the mapped source can be debugged. If startup returns `starting`, look up `lastLaunch` with `debugger_configurations` before retrying.

Calling the configuration tool again updates only the supplied fields. Pass `"sourceMappings":[]` to clear the mappings. If `pydevdServerAvailable` is false, check that the Python plugin is enabled and the IDE edition includes Python Debug Server. An existing DAP configuration can also be launched by name when its own Debug runner is installed. For a pydevd client, select the Python Debug Server configuration.

Use `debugger_status` to get the session ID. Call `debugger_control` with `{"action":"stop","sessionId":"…"}` to release the session or `{"action":"restart","sessionId":"…"}` to relaunch the IDE configuration. For a server restart, reconnect the container client.

### Start a local file

Call `debugger_launch` with `{"filePath":"src/main.py"}`, even if the project has no saved run configuration. PyCharm chooses the run configuration and interpreter for that file. Select the intended virtualenv, Poetry or uv interpreter in the project first. The MCP tool does not invoke a package manager or install a runner.

### Things worth knowing before you call them

**Lines are 1-based**, on the way in and on the way out, matching what the editor shows. A line
outside the file is rejected rather than clamped.

**Step, resume and pause wait for a debugger event.** The tool installs a session listener, issues the action, and blocks until
the session pauses again, terminates, or `timeoutMs` expires (1–30000, default 10000). The result
carries `outcome` (`paused`, `stopped`, `timeout`, `already_paused`, `requested`), `positionBefore`,
`position` and `frameReady`. A `timeout` does not cancel anything: the step may still be running.
Call `debugger_status` before sending another control, or you will queue a second resume onto a
process that was about to stop anyway. The MCP call itself waits `timeoutMs + 1000`, so keep your
client's own timeout comfortably above that.

**`filePath` may be relative.** An absolute path is used as-is; anything else resolves against the
project's base path.

**`condition` is three-valued on `add`.** Omitted keeps the existing condition, an empty string
clears it, and a non-empty string replaces it. Adding a breakpoint that already exists enables it
instead of duplicating it. If a line has several breakpoints, or the IDE cannot pick a single
breakpoint type for it, the call fails and asks you to resolve it in the UI.

**`debugger_evaluate` returns the debugger's preview**, not `repr()`. The response has `value`
(truncated at 4096 characters, with `truncated` telling you whether it was), `type`, `hasChildren`,
and `representation: "debugger preview"` as a reminder of which rendering you are reading. The
expression runs in the debuggee, so it can have side effects.

**Watches need an initialised debug tab.** The plugin reaches the panel through
`XDebugSessionTab.getWatchesView()`; until the session has drawn its tab, `watchesAvailable` is
false and `debugger_watch` fails. A Watch added here is a real Watch, re-evaluated by the debugger
at every pause and visible to whoever is sitting in front of the IDE. `add` is idempotent: an
expression already in the panel is left alone rather than duplicated.

### Argument Origin

The `debugger_origin` tool is registered only when the Argument Origin plugin is enabled. Install Argument Origin 0.4.3 or later and start a recorded local Python debug session. In a paused frame, call `debugger_origin` with the local `name` and `action=watch_once`, then resume using `debugger_control`. The monitor disarms after the first change. Call `watch_status` after pausing to inspect `armed` and `last_hit`, or `unwatch` to cancel. Another breakpoint may pause first. One frame can monitor one local at a time, and its monitor ends when the frame exits.

Use `action=trace` or `action=history` for recorded events. Results are paged using `offset` and `limit` (1–50, default 20), with `total` and `nextOffset` in the response. Set `detail=true` to retrieve full event information for a page. For history, `changesOnly=true` omits equal assignments and initial arguments. Each history row has an `eventId`, assignment location and recognized producer. Pass that ID to `action=trace` to expand that retained assignment’s producer chain. Events may expire. The recorder’s retention and redaction settings apply.

## Example

Using [mcporter](https://github.com/steipete/mcporter) against a configured server alias:

```sh
server='pycharm'
project='/absolute/path/to/project'

mcporter call "$server.debugger_status" "projectPath=$project" --output json --no-oauth

mcporter call "$server.debugger_breakpoint" "projectPath=$project" \
  action=add "filePath=$project/app/pipeline.py" line=142 \
  'condition=item.get("id") == "target-id"' --output json --no-oauth

mcporter call "$server.debugger_control" "projectPath=$project" \
  action=resume timeoutMs=10000 --timeout 15000 --output json --no-oauth

# only once the previous call reported outcome=paused and frameReady=true
mcporter call "$server.debugger_evaluate" "projectPath=$project" \
  'expression=("children" in item, len(item.get("children") or []))' \
  --timeout 15000 --output json --no-oauth
```

Pass expressions as a single quoted argument, or as JSON via `--args`. Never build them by
interpolating shell text.

## Limitations

- **Internal APIs.** `XDebugSessionImpl`, `XWatchesViewImpl`, `WatchesRootNode` and
  `XValuePresentationUtil` are implementation classes, not platform API. The session id comes from
  `XDebugSessionImpl.getId()`, so a remote or split-mode session that is not a local
  `XDebugSessionImpl` is rejected outright.
- **Spanish error messages.** Tool and parameter descriptions are in English, but error messages
  returned by the tools are still Spanish, and MCP clients show them to the model as-is.
- **No disable, no mute, no exception breakpoints.** `debugger_breakpoint` only adds and removes
  line breakpoints. `debugger_status` reports `breakpointsMuted` but nothing toggles it.
- **Shared state.** Everything here mutates the IDE a person may be using. Record the breakpoints,
  conditions and Watches you found before changing them, and put them back afterwards.

## License

MIT. See [LICENSE](LICENSE).
