package com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.ui.EndpointInventoryRow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

data class DebugProjectState(
    val name: String,
    val basePath: String
)

object KtorDebugPage {
    const val DEBUG_PATH: String = "${McpConstants.MCP_ENDPOINT_PATH}/debug"
    const val STATE_PATH: String = "$DEBUG_PATH/state"

    fun buildState(
        serverName: String,
        serverVersion: String,
        protocolVersion: String,
        host: String,
        port: Int,
        serverRunning: Boolean,
        endpointRows: List<EndpointInventoryRow>,
        projects: List<DebugProjectState>
    ): JsonObject {
        val streamableHttpUrl = "http://$host:$port${McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH}"
        val legacySseUrl = "http://$host:$port${McpConstants.SSE_ENDPOINT_PATH}"

        return buildJsonObject {
            putJsonObject("server") {
                put("name", serverName)
                put("version", serverVersion)
                put("protocolVersion", protocolVersion)
                put("host", host)
                put("port", port)
                put("running", serverRunning)
                put("streamableHttpUrl", streamableHttpUrl)
                put("legacySseUrl", legacySseUrl)
                put("debugUrl", "http://$host:$port$DEBUG_PATH")
                put("debugStateUrl", "http://$host:$port$STATE_PATH")
            }
            put("projects", buildJsonArray {
                projects.forEach { project ->
                    add(buildJsonObject {
                        put("name", project.name)
                        put("basePath", project.basePath)
                    })
                }
            })
            put("endpoints", buildJsonArray {
                endpointRows.forEach { row ->
                    add(buildJsonObject {
                        put("id", row.id)
                        put("scopeKind", row.scopeKind.name)
                        put("scopeName", row.scopeName)
                        put("url", row.url)
                        put("rootPath", row.rootPath)
                        put("state", row.state.name)
                        put("copyText", row.copyText)
                    })
                }
            })
        }
    }

    fun html(): String = """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>Index MCP Debug</title>
          <style>
            :root {
              color-scheme: light dark;
              --bg: #f5f7f8;
              --panel: #ffffff;
              --text: #182027;
              --muted: #617080;
              --border: #cfd7df;
              --accent: #0f766e;
              --accent-strong: #0b5f59;
              --code: #101820;
              --code-text: #f3f7fa;
            }

            @media (prefers-color-scheme: dark) {
              :root {
                --bg: #11161b;
                --panel: #171e24;
                --text: #edf2f5;
                --muted: #a7b4bf;
                --border: #34414c;
                --accent: #2dd4bf;
                --accent-strong: #5eead4;
                --code: #090d10;
                --code-text: #e5edf2;
              }
            }

            * {
              box-sizing: border-box;
            }

            body {
              margin: 0;
              background: var(--bg);
              color: var(--text);
              font-family: Inter, "Segoe UI", system-ui, -apple-system, sans-serif;
              font-size: 14px;
              line-height: 1.45;
            }

            header,
            main {
              width: min(1280px, calc(100vw - 32px));
              margin: 0 auto;
            }

            header {
              padding: 18px 0 12px;
              display: grid;
              grid-template-columns: 1fr auto;
              gap: 16px;
              align-items: end;
              border-bottom: 1px solid var(--border);
            }

            h1 {
              margin: 0;
              font-size: 20px;
              font-weight: 650;
            }

            .meta {
              margin-top: 4px;
              color: var(--muted);
              font-size: 12px;
            }

            main {
              padding: 16px 0 24px;
              display: grid;
              grid-template-columns: minmax(320px, 420px) minmax(0, 1fr);
              gap: 16px;
            }

            section {
              background: var(--panel);
              border: 1px solid var(--border);
              border-radius: 8px;
              padding: 14px;
              min-width: 0;
            }

            h2 {
              margin: 0 0 10px;
              font-size: 13px;
              font-weight: 650;
              text-transform: uppercase;
              letter-spacing: 0;
              color: var(--muted);
            }

            label {
              display: block;
              margin: 12px 0 6px;
              color: var(--muted);
              font-size: 12px;
              font-weight: 600;
            }

            select,
            textarea,
            input,
            button {
              font: inherit;
            }

            select,
            textarea,
            input {
              width: 100%;
              border: 1px solid var(--border);
              border-radius: 6px;
              background: transparent;
              color: var(--text);
              padding: 8px 9px;
            }

            textarea {
              min-height: 230px;
              resize: vertical;
              font-family: "JetBrains Mono", Consolas, monospace;
              font-size: 12px;
              line-height: 1.5;
            }

            button {
              border: 1px solid var(--accent);
              border-radius: 6px;
              background: var(--accent);
              color: #ffffff;
              padding: 8px 10px;
              cursor: pointer;
              white-space: nowrap;
            }

            button.secondary {
              background: transparent;
              color: var(--accent-strong);
            }

            button:disabled {
              opacity: 0.55;
              cursor: default;
            }

            .button-row {
              display: flex;
              flex-wrap: wrap;
              gap: 8px;
              margin-top: 12px;
            }

            .status-grid {
              display: grid;
              grid-template-columns: repeat(2, minmax(0, 1fr));
              gap: 8px;
              margin-bottom: 10px;
            }

            .stat {
              border: 1px solid var(--border);
              border-radius: 6px;
              padding: 8px;
              min-width: 0;
            }

            .stat span {
              display: block;
              color: var(--muted);
              font-size: 11px;
              margin-bottom: 3px;
            }

            .stat strong {
              display: block;
              overflow-wrap: anywhere;
              font-size: 13px;
            }

            .endpoint-list {
              display: grid;
              gap: 6px;
              margin-top: 10px;
            }

            .endpoint {
              border: 1px solid var(--border);
              border-radius: 6px;
              padding: 8px;
            }

            .endpoint b,
            .endpoint code {
              display: block;
              overflow-wrap: anywhere;
            }

            .endpoint code {
              margin-top: 4px;
              color: var(--muted);
              font-size: 12px;
            }

            pre {
              margin: 0;
              min-height: 300px;
              max-height: 58vh;
              overflow: auto;
              border-radius: 8px;
              background: var(--code);
              color: var(--code-text);
              padding: 12px;
              font-family: "JetBrains Mono", Consolas, monospace;
              font-size: 12px;
              line-height: 1.5;
              white-space: pre-wrap;
              overflow-wrap: anywhere;
            }

            @media (max-width: 860px) {
              header {
                grid-template-columns: 1fr;
              }

              main {
                grid-template-columns: 1fr;
              }
            }
          </style>
        </head>
        <body>
          <header>
            <div>
              <h1>Index MCP Debug</h1>
              <div class="meta" id="serverMeta">Loading server state...</div>
            </div>
            <button class="secondary" id="refreshButton" type="button">Refresh</button>
          </header>
          <main>
            <section>
              <h2>Server</h2>
              <div class="status-grid">
                <div class="stat"><span>Status</span><strong id="statusValue">-</strong></div>
                <div class="stat"><span>Version</span><strong id="versionValue">-</strong></div>
                <div class="stat"><span>Protocol</span><strong id="protocolValue">-</strong></div>
                <div class="stat"><span>Projects</span><strong id="projectCountValue">-</strong></div>
              </div>
              <label for="endpointSelect">Endpoint</label>
              <select id="endpointSelect"></select>
              <label for="projectSelect">Project path</label>
              <select id="projectSelect"></select>
              <div class="endpoint-list" id="endpointList"></div>
            </section>
            <section>
              <h2>Request</h2>
              <div class="button-row">
                <button type="button" data-action="initialize">Initialize</button>
                <button type="button" data-action="toolsList">Tools List</button>
                <button type="button" data-action="indexStatus">Index Status</button>
                <button class="secondary" type="button" data-action="send">Send JSON</button>
              </div>
              <label for="requestBody">JSON-RPC body</label>
              <textarea id="requestBody" spellcheck="false"></textarea>
              <label for="responseBody">Response</label>
              <pre id="responseBody">No request sent.</pre>
            </section>
          </main>
          <script>
            var state = null;
            var nextId = 1;

            function pretty(value) {
              return JSON.stringify(value, null, 2);
            }

            function selectedProjectPath() {
              var select = document.getElementById("projectSelect");
              return select.value || "";
            }

            function selectedEndpointUrl() {
              var select = document.getElementById("endpointSelect");
              return select.value || new URL("/index-mcp/streamable-http", window.location.origin).toString();
            }

            function renderState(nextState) {
              state = nextState;
              var server = state.server || {};
              document.getElementById("serverMeta").textContent = server.name + " on " + server.host + ":" + server.port;
              document.getElementById("statusValue").textContent = server.running ? "RUNNING" : "OFFLINE";
              document.getElementById("versionValue").textContent = server.version || "-";
              document.getElementById("protocolValue").textContent = server.protocolVersion || "-";
              document.getElementById("projectCountValue").textContent = String((state.projects || []).length);

              var endpointSelect = document.getElementById("endpointSelect");
              endpointSelect.innerHTML = "";
              (state.endpoints || []).forEach(function(endpoint) {
                var option = document.createElement("option");
                option.value = endpoint.url;
                option.textContent = endpoint.scopeKind + " - " + endpoint.scopeName;
                endpointSelect.appendChild(option);
              });

              var projectSelect = document.getElementById("projectSelect");
              projectSelect.innerHTML = "";
              var blank = document.createElement("option");
              blank.value = "";
              blank.textContent = "No project_path override";
              projectSelect.appendChild(blank);
              (state.projects || []).forEach(function(project) {
                if (!project.basePath) {
                  return;
                }
                var option = document.createElement("option");
                option.value = project.basePath;
                option.textContent = project.name + " - " + project.basePath;
                projectSelect.appendChild(option);
              });
              (state.endpoints || []).forEach(function(endpoint) {
                if (!endpoint.rootPath) {
                  return;
                }
                var option = document.createElement("option");
                option.value = endpoint.rootPath;
                option.textContent = endpoint.scopeName + " - " + endpoint.rootPath;
                projectSelect.appendChild(option);
              });

              var endpointList = document.getElementById("endpointList");
              endpointList.innerHTML = "";
              (state.endpoints || []).forEach(function(endpoint) {
                var item = document.createElement("div");
                item.className = "endpoint";
                var title = document.createElement("b");
                title.textContent = endpoint.scopeKind + " - " + endpoint.scopeName + " (" + endpoint.state + ")";
                var url = document.createElement("code");
                url.textContent = endpoint.url || "No URL";
                var path = document.createElement("code");
                path.textContent = endpoint.rootPath || "No root path";
                item.appendChild(title);
                item.appendChild(url);
                item.appendChild(path);
                endpointList.appendChild(item);
              });
            }

            async function refreshState() {
              var response = await fetch("/index-mcp/debug/state", { headers: { "Accept": "application/json" } });
              var payload = await response.json();
              renderState(payload);
              buildRequest("initialize");
            }

            function buildRequest(action) {
              var projectPath = selectedProjectPath();
              var id = nextId++;
              var body;
              if (action === "initialize") {
                body = {
                  jsonrpc: "2.0",
                  id: id,
                  method: "initialize",
                  params: {
                    protocolVersion: "2025-03-26",
                    capabilities: {},
                    clientInfo: {
                      name: "index-mcp-debug-page",
                      version: state && state.server ? state.server.version : "dev"
                    }
                  }
                };
              } else if (action === "toolsList") {
                body = { jsonrpc: "2.0", id: id, method: "tools/list", params: {} };
              } else {
                body = {
                  jsonrpc: "2.0",
                  id: id,
                  method: "tools/call",
                  params: {
                    name: "ide_index_status",
                    arguments: projectPath ? { project_path: projectPath } : {}
                  }
                };
              }
              document.getElementById("requestBody").value = pretty(body);
            }

            async function sendCurrentRequest() {
              var responseBox = document.getElementById("responseBody");
              responseBox.textContent = "Sending...";
              try {
                var raw = document.getElementById("requestBody").value;
                var parsed = JSON.parse(raw);
                var response = await fetch(selectedEndpointUrl(), {
                  method: "POST",
                  headers: {
                    "Accept": "application/json, text/event-stream",
                    "Content-Type": "application/json"
                  },
                  body: JSON.stringify(parsed)
                });
                var text = await response.text();
                var parsedText = text ? JSON.parse(text) : { status: response.status, body: "" };
                responseBox.textContent = "HTTP " + response.status + "\n" + pretty(parsedText);
              } catch (error) {
                responseBox.textContent = String(error && error.stack ? error.stack : error);
              }
            }

            document.getElementById("refreshButton").addEventListener("click", refreshState);
            document.querySelectorAll("[data-action]").forEach(function(button) {
              button.addEventListener("click", function() {
                var action = button.getAttribute("data-action");
                if (action === "send") {
                  sendCurrentRequest();
                } else {
                  buildRequest(action);
                }
              });
            });
            document.getElementById("projectSelect").addEventListener("change", function() {
              buildRequest("indexStatus");
            });
            refreshState().catch(function(error) {
              document.getElementById("responseBody").textContent = String(error && error.stack ? error.stack : error);
            });
          </script>
        </body>
        </html>
    """.trimIndent()
}
