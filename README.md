# Debugger MCP Extension for PyCharm

A small PyCharm plugin that exposes the IDE's real debugger over MCP. It registers six tools on
JetBrains' built-in MCP server, so an MCP client can set breakpoints, step, read the stack,
evaluate expressions and edit the Watches panel in a running PyCharm debug session.

It drives the same `XDebugSession` the UI drives. Breakpoints appear in the gutter, Watches appear
in the Watches panel, and a step taken over MCP moves the editor the same way clicking Step Over
does. There is no separate debug adapter and no desktop automation.

## Requirements

- PyCharm 2026.3, build `263.7307.329` or later in the 263 series. Other builds need separate compatibility testing.
- The bundled **MCP Server** plugin (`com.intellij.mcpServer`) enabled, with its server running.
- A Python debug session started from the IDE. The plugin attaches to sessions; it cannot start one.

## Repository layout

```
src/main/java/local/pycharmdebugger/DebuggerToolsProvider.java   all six tools, 401 lines
src/main/resources/META-INF/plugin.xml                           plugin descriptor
build.gradle.kts  settings.gradle.kts  gradle/  gradlew          Gradle build
pycharm-debugger-mcp/lib/debugger-mcp.jar                        the 263-series build
```

The whole plugin is one Java class. Tool and parameter descriptions are in English. Code comments and error messages are still in Spanish.

## Building

Needs JDK 25, not 21: PyCharm 2026.3 ships Java 25 class files and javac refuses to read them on
an older release. The wrapper and the foojay toolchain resolver fetch Gradle and the JDK if they
are missing, and the first build pulls down PyCharm 2026.3.0.1, a bit over a gigabyte. After that a
build is about ten seconds.

```sh
./gradlew buildPlugin
```

The installable zip lands in `build/distributions/`, laid out exactly like the one in
`pycharm-debugger-mcp/`.

The bundled JAR was compiled against PyCharm 2026.3.0.1. The IDE loaded it and `debugger_status` reported a paused Python session.

## Install


The archive layout is what *Settings > Plugins > ⚙ > Install Plugin from Disk…* expects:

```
pycharm-debugger-mcp/
└── lib/
    └── debugger-mcp.jar
```

Zip the `pycharm-debugger-mcp` directory and install that zip, or drop the directory straight into
the IDE's `plugins/` folder and restart:

```sh
zip -r pycharm-debugger-mcp.zip pycharm-debugger-mcp
```

After the restart the tools show up in the MCP server's catalog under the `Debugger` category.

## Tools

Every tool takes an optional `projectPath` (absolute path of an open project). Omit it and the call
uses whichever project the MCP server is serving. Session tools take an optional `sessionId`; omit
it when exactly one session is active, and the plugin resolves it. With two or more sessions open
an omitted `sessionId` is an error rather than a guess.

| Tool | Required | Does |
| --- | --- | --- |
| `debugger_status` | — | Sessions, current position, Watches, breakpoints, `breakpointsMuted` |
| `debugger_control` | `action` | `step_into` / `step_over` / `step_out` / `resume` / `pause`, then waits for the next pause |
| `debugger_stack` | — | Up to 20 frames of the selected thread, positions only, no variable evaluation |
| `debugger_evaluate` | `expression` | Evaluates in the selected paused frame |
| `debugger_breakpoint` | `action`, `filePath`, `line` | Adds or removes a line breakpoint, with an optional `condition` |
| `debugger_watch` | `action` | `add` / `remove` / `list` against the real Watches panel |

`debugger_status` and `debugger_stack` are annotated read-only. The other four are annotated
destructive, because they change shared IDE state or run code in the debuggee.

### Things worth knowing before you call them

**Lines are 1-based**, on the way in and on the way out, matching what the editor shows. A line
outside the file is rejected rather than clamped.

**`debugger_control` waits.** It installs a session listener, issues the action, and blocks until
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

- **Version compatibility.** The plugin targets the 263 series and uses internal APIs. Later IDE builds need testing before extending the range.
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
