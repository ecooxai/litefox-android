(function () {
  if (window.__firefoxLiteDevTools && window.__firefoxLiteDevTools.show) {
    window.__firefoxLiteDevTools.show();
    return;
  }

  const state = {
    logs: [],
    network: [],
    picking: false,
    previousOutline: null,
  };
  const root = document.createElement("div");
  root.id = "__firefoxlite_devtools";
  root.style.cssText = "position:fixed;z-index:2147483647;left:0;right:0;bottom:0;height:min(50vh,460px);min-height:260px;background:#171717;color:#eeeeee;font:13px monospace;display:flex;flex-direction:column;border-top:2px solid #3b82f6;box-shadow:0 -4px 20px rgba(0,0,0,.45);";

  const style = document.createElement("style");
  style.textContent = "#" + root.id + " *{box-sizing:border-box} #" + root.id + " button{font:inherit;color:#eee;background:#2b2b2b;border:1px solid #555;border-radius:3px;padding:5px 8px} #" + root.id + " button.active{background:#2563eb;border-color:#60a5fa} #" + root.id + " .dt-head{height:42px;display:flex;align-items:center;gap:5px;padding:5px 8px;background:#222;border-bottom:1px solid #444} #" + root.id + " .dt-title{font:600 14px sans-serif;margin-right:8px} #" + root.id + " .dt-spacer{flex:1} #" + root.id + " .dt-body{min-height:0;flex:1;overflow:auto;padding:8px} #" + root.id + " .dt-console{white-space:pre-wrap;word-break:break-word} #" + root.id + " .dt-line{padding:3px 5px;border-bottom:1px solid #292929} #" + root.id + " .dt-error{color:#ff8d8d} #" + root.id + " .dt-warn{color:#ffd166} #" + root.id + " .dt-input{display:flex;gap:5px;padding:7px 8px;background:#222;border-top:1px solid #444} #" + root.id + " input{min-width:0;flex:1;background:#101010;color:#eee;border:1px solid #555;padding:7px;font:13px monospace} #" + root.id + " table{width:100%;border-collapse:collapse} #" + root.id + " th,#" + root.id + " td{text-align:left;padding:5px;border-bottom:1px solid #333;vertical-align:top;word-break:break-all} #" + root.id + " th{color:#9cc7ff;position:sticky;top:0;background:#171717} #" + root.id + " .dt-node{padding:2px 4px;cursor:pointer;white-space:pre-wrap} #" + root.id + " .dt-node:hover{background:#263449} #" + root.id + " .dt-muted{color:#9ca3af}";
  root.appendChild(style);

  const head = document.createElement("div");
  head.className = "dt-head";
  const title = document.createElement("span");
  title.className = "dt-title";
  title.textContent = "FirefoxLite Dev Tools";
  head.appendChild(title);
  const consoleButton = document.createElement("button");
  consoleButton.textContent = "Console";
  const networkButton = document.createElement("button");
  networkButton.textContent = "Network";
  const elementsButton = document.createElement("button");
  elementsButton.textContent = "Elements";
  head.appendChild(consoleButton);
  head.appendChild(networkButton);
  head.appendChild(elementsButton);
  const spacer = document.createElement("span");
  spacer.className = "dt-spacer";
  head.appendChild(spacer);
  const close = document.createElement("button");
  close.textContent = "Close";
  head.appendChild(close);
  root.appendChild(head);

  const body = document.createElement("div");
  body.className = "dt-body";
  root.appendChild(body);
  const inputBar = document.createElement("div");
  inputBar.className = "dt-input";
  const input = document.createElement("input");
  input.placeholder = "JavaScript expression, for example document.title";
  const run = document.createElement("button");
  run.textContent = "Run";
  inputBar.appendChild(input);
  inputBar.appendChild(run);
  root.appendChild(inputBar);
  document.documentElement.appendChild(root);

  function addLine(value, kind) {
    const line = document.createElement("div");
    line.className = "dt-line" + (kind ? " dt-" + kind : "");
    line.textContent = String(value);
    consolePanel.appendChild(line);
    consolePanel.scrollTop = consolePanel.scrollHeight;
  }

  function format(value) {
    if (value === undefined) return "undefined";
    if (value === null) return "null";
    if (typeof value === "string") return value;
    try { return JSON.stringify(value, null, 2); } catch (_) { return String(value); }
  }

  const consolePanel = document.createElement("div");
  consolePanel.className = "dt-console";
  function showConsole() {
    body.replaceChildren(consolePanel);
    inputBar.style.display = "flex";
    consoleButton.classList.add("active");
    networkButton.classList.remove("active");
    elementsButton.classList.remove("active");
  }

  function runCommand() {
    const source = input.value.trim();
    if (!source) return;
    addLine("> " + source, "muted");
    try {
      const result = window.eval(source);
      addLine(format(result));
    } catch (error) {
      addLine(error && error.stack ? error.stack : error, "error");
    }
    input.value = "";
  }
  run.addEventListener("click", runCommand);
  input.addEventListener("keydown", function (event) {
    if (event.key === "Enter") runCommand();
  });

  ["log", "info", "debug", "warn", "error"].forEach(function (method) {
    const original = console[method];
    console[method] = function () {
      const message = Array.prototype.map.call(arguments, format).join(" ");
      state.logs.push({method: method, message: message});
      if (root.isConnected) addLine(message, method === "error" ? "error" : method === "warn" ? "warn" : "");
      if (original) original.apply(console, arguments);
    };
  });
  addLine("Console ready for " + location.href, "muted");

  function installNetworkProbe() {
    if (window.__firefoxLiteNetworkProbe) return;
    window.__firefoxLiteNetworkProbe = true;
    const originalFetch = window.fetch;
    if (originalFetch) {
      window.fetch = function () {
        const started = performance.now();
        const request = arguments[0];
        const url = typeof request === "string" ? request : request && request.url;
        return originalFetch.apply(this, arguments).then(function (response) {
          state.network.push({type:"fetch", url:url || "", status:response.status, duration:Math.round(performance.now() - started)});
          return response;
        }, function (error) {
          state.network.push({type:"fetch", url:url || "", status:"ERR", duration:Math.round(performance.now() - started)});
          throw error;
        });
      };
    }
  }
  installNetworkProbe();

  function showNetwork() {
    body.replaceChildren();
    inputBar.style.display = "none";
    networkButton.classList.add("active");
    consoleButton.classList.remove("active");
    elementsButton.classList.remove("active");
    const refresh = document.createElement("button");
    refresh.textContent = "Refresh network list";
    body.appendChild(refresh);
    const table = document.createElement("table");
    table.innerHTML = "<thead><tr><th>Type</th><th>URL</th><th>Status</th><th>Time</th></tr></thead>";
    const rows = document.createElement("tbody");
    const entries = performance.getEntriesByType("resource").map(function (entry) {
      return {type:"resource", url:entry.name, status:"—", duration:Math.round(entry.duration) + " ms"};
    }).concat(state.network);
    entries.slice(-300).forEach(function (entry) {
      const row = document.createElement("tr");
      [entry.type, entry.url, entry.status, typeof entry.duration === "number" ? entry.duration + " ms" : entry.duration].forEach(function (value) {
        const cell = document.createElement("td");
        cell.textContent = String(value || "");
        row.appendChild(cell);
      });
      rows.appendChild(row);
    });
    table.appendChild(rows);
    body.appendChild(table);
    refresh.addEventListener("click", showNetwork);
  }

  function describe(node) {
    if (node.nodeType !== 1) return node.nodeName;
    let value = node.tagName.toLowerCase();
    if (node.id) value += "#" + node.id;
    if (node.classList && node.classList.length) value += "." + Array.prototype.slice.call(node.classList, 0, 3).join(".");
    return value;
  }

  function createNode(node, depth) {
    const line = document.createElement("div");
    line.className = "dt-node";
    line.style.paddingLeft = (depth * 14 + 4) + "px";
    line.textContent = describe(node);
    if (node.nodeType === 1) {
      line.title = "Click to highlight this element";
      line.addEventListener("click", function () {
        if (state.previousOutline) state.previousOutline.style.outline = "";
        state.previousOutline = node;
        node.style.outline = "2px solid #3b82f6";
        node.scrollIntoView({block:"center", behavior:"smooth"});
      });
    }
    return line;
  }

  function showElements() {
    body.replaceChildren();
    inputBar.style.display = "none";
    elementsButton.classList.add("active");
    consoleButton.classList.remove("active");
    networkButton.classList.remove("active");
    const pick = document.createElement("button");
    pick.textContent = state.picking ? "Picking element…" : "Pick element from page";
    body.appendChild(pick);
    const hint = document.createElement("div");
    hint.className = "dt-muted";
    hint.textContent = "Click an element below to highlight it, or use Pick element.";
    body.appendChild(hint);
    function walk(node, depth) {
      if (!node || depth > 4 || node === root) return;
      body.appendChild(createNode(node, depth));
      Array.prototype.slice.call(node.children || [], 0, 80).forEach(function (child) { walk(child, depth + 1); });
    }
    walk(document.body, 0);
    pick.addEventListener("click", function () {
      state.picking = !state.picking;
      pick.textContent = state.picking ? "Picking element…" : "Pick element from page";
    });
  }

  document.addEventListener("click", function (event) {
    if (!state.picking || root.contains(event.target)) return;
    event.preventDefault();
    event.stopPropagation();
    state.picking = false;
    if (state.previousOutline) state.previousOutline.style.outline = "";
    state.previousOutline = event.target;
    event.target.style.outline = "2px solid #3b82f6";
    showElements();
  }, true);

  function hide() {
    if (state.previousOutline) state.previousOutline.style.outline = "";
    root.remove();
    delete window.__firefoxLiteDevTools;
  }
  close.addEventListener("click", hide);
  window.__firefoxLiteDevTools = {show: function () { root.style.display = "flex"; showConsole(); }, hide: hide};
  showConsole();
})();
