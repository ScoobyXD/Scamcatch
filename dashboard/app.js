"use strict";

const byId = (id) => document.getElementById(id);
const activeStatuses = new Set(["starting", "active", "stopping"]);
let model = { state: {}, run: null, connection: "disconnected", devices: [], ocr: [], activity: [] };
let token = "";
let stream;
let commandBusy = false;
let keySending = false;
let keyProvider = "jev";
let streamReady = false;
const keyProviders = {
  jev: { label: "Jev", endpoint: "/api/jev-key" },
  openai: { label: "OpenAI", endpoint: "/api/openai-key" },
};
const expandedCaptures = new Set();
const jevCards = new Map();
const textResizeObserver = new ResizeObserver((entries) => {
  for (const { target } of entries) updateTextToggle(target);
});
const clock = new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit", second: "2-digit", fractionalSecondDigits: 3, hour12: false });
const labels = {run_started:"Run started",run_stopped:"Run stopped",app_changed:"App changed",interaction:"Interaction",typing_state:"Typing changed",capture_waiting:"Capture waiting",capture_requested:"Capture requested",screenshot_received:"Screenshot received",capture_failed:"Capture failed",capture_discarded:"Capture discarded",capture_skipped:"Capture skipped",ocr_started:"OCR started",ocr_completed:"OCR completed",ocr_failed:"OCR failed",jev_started:"Jev started",jev_completed:"Jev completed",jev_failed:"Jev failed",jev_skipped:"Jev skipped",jev_canceled:"Jev canceled",pending_canceled:"Pending work canceled",command_error:"Command rejected"};

function title(value, fallback = "Idle") {
  if (value == null || value === "") return fallback;
  if (typeof value === "object") return title(value.type ?? value.action ?? value.label ?? value.name, fallback);
  const text = String(value).replaceAll("_", " ");
  return text.charAt(0).toUpperCase() + text.slice(1);
}

function timestamp(value) {
  return typeof value === "number" && Number.isFinite(value) ? clock.format(new Date(value)) : "—";
}

function setCheck(id, ready) {
  const element = byId(id);
  element.textContent = ready ? "●" : "○";
  element.classList.toggle("ready", Boolean(ready));
  element.setAttribute("role", "img");
  element.setAttribute("aria-label", ready ? "Ready:" : "Not ready:");
}

function notice(text, tone = "") {
  byId("notice").textContent = text;
  byId("notice").className = `notice ${tone}`;
  byId("notice").hidden = !text;
}

function renderElapsed() {
  const run = model.run;
  const started = Date.parse(run?.startedAt);
  const ended = run?.endedAt ? Date.parse(run.endedAt) : Date.now();
  const seconds = Number.isFinite(started) ? Math.max(0, Math.floor((ended - started) / 1000)) : 0;
  const minutes = Math.floor(seconds / 60);
  byId("run-elapsed").textContent = `${String(minutes).padStart(2, "0")}:${String(seconds % 60).padStart(2, "0")}`;
}

function renderStatus() {
  const state = model.state ?? {};
  const connected = model.connection === "connected" && streamReady;
  const run = model.run;
  const active = run && activeStatuses.has(run.status);
  const changingDevice = ["connecting", "switching"].includes(model.connection);
  const selectedDevice = model.devices.find((device) => device.serial === byId("device").value);
  const differentDevice = Boolean(selectedDevice) && selectedDevice.serial !== model.selectedDevice;
  const deviceRunActive = connected && Boolean(state.runId);
  const connection = byId("connection");
  connection.className = `badge ${connected ? "good" : "neutral"}`;
  connection.replaceChildren();
  const dot = document.createElement("span");
  dot.className = "dot";
  connection.append(dot, document.createTextNode(connected ? "Device connected" : title(model.connection, "Disconnected")));
  byId("start").disabled = commandBusy || !connected || differentDevice || Boolean(active) || Boolean(state.runId) || !state.consentGranted || !state.monitoringEnabled || !state.serviceConnected;
  byId("stop").disabled = commandBusy || !connected || !active || run.status === "stopping";
  byId("start").textContent = run?.status === "starting" ? "Starting…" : "Start run";
  byId("stop").textContent = run?.status === "stopping" ? "Stopping…" : "Stop";
  byId("connect").disabled = commandBusy || Boolean(active) || deviceRunActive || changingDevice || selectedDevice?.status !== "device" || (connected && !differentDevice);
  byId("connect").textContent = connected ? "Switch device" : "Connect";
  byId("jev-key-open").disabled = commandBusy || !connected || differentDevice;
  byId("openai-key-open").disabled = commandBusy || !connected || differentDevice;
  byId("api-key-input").disabled = commandBusy || !connected || differentDevice;
  byId("api-key-send").disabled = commandBusy || !connected || differentDevice;
  byId("api-key-clear").disabled = commandBusy || !connected || differentDevice;
  byId("api-key-send").textContent = keySending ? "Sending…" : "Send to phone";
  const keyStorage = model.keyStorage;
  const savedKey = !differentDevice && Boolean(keyStorage?.saved?.[keyProvider]);
  byId("api-key-remember").disabled = commandBusy || !connected || differentDevice || !keyStorage?.available;
  byId("api-key-use-saved").hidden = !savedKey;
  byId("api-key-use-saved").disabled = commandBusy || !connected || differentDevice || !keyStorage?.available;
  byId("api-key-clear").textContent = savedKey ? "Forget saved key" : "Clear key";
  byId("api-key-clear").title = savedKey ? "Remove the saved key from this PC and clear the phone key" : "Clear the phone key";
  byId("api-key-saved-status").textContent = !connected || differentDevice
    ? "Connect the selected phone to check its saved key."
    : keyStorage?.error || (!keyStorage?.available
      ? "Saving keys is unavailable on this PC. You can still send a key to phone memory."
      : `${keyProviders[keyProvider].label} key ${savedKey ? "saved" : "not saved"} on this PC for this phone.`);
  if ((!connected || differentDevice) && byId("api-key-dialog").open) {
    byId("api-key-input").value = "";
    byId("api-key-message").textContent = "Connect the selected phone before sending a key.";
  }
  byId("refresh").disabled = commandBusy || changingDevice;
  byId("device").disabled = commandBusy || Boolean(active) || deviceRunActive || changingDevice;
  byId("device").title = active ? "Stop the run before changing devices." : "Choose a device, then connect.";
  byId("pause").disabled = commandBusy || !connected || !state.consentGranted;
  byId("pause").textContent = state.monitoringEnabled ? "Pause" : "Resume";
  byId("pause").title = state.monitoringEnabled ? "Stop the run and pause capture" : "Enable capture for a new run";
  byId("current-app").textContent = title(state.currentApp, connected ? "Waiting for activity" : "Waiting for device");
  byId("current-package").textContent = state.currentPackage || state.lastExternalApp || "No activity yet";
  byId("interaction").textContent = title(state.lastInteraction);
  byId("typing").textContent = state.typing === true || state.typing === "typing" ? "Typing now" : "Not typing";
  byId("capture-status").textContent = state.warningActive ? "Paused for warning" : title(state.captureStatus, "Ready");
  byId("ocr-status").textContent = state.warningActive ? "Paused for warning" : title(state.ocrStatus, "Ready");
  byId("jev-status").textContent = state.jevFlagged === true ? "Probably scam" : title(state.jevStatus, state.jevReady ? "Ready" : "Not configured");
  byId("voice-status").textContent = !state.warningActive && (!state.voiceStatus || state.voiceStatus === "idle")
    ? state.voiceReady ? "Key loaded" : "Key not set"
    : title(state.voiceStatus);
  const counters = active ? (state.counters ?? {}) : (run?.counters ?? {});
  for (const name of ["requests", "screenshots", "ocrCompleted", "failed", "ocrFailed", "skipped"]) {
    byId(`count-${name}`).textContent = String(counters[name] ?? 0);
  }
  const runBadge = byId("run-status");
  runBadge.textContent = run ? title(run.status) : "No run";
  runBadge.className = `badge ${run?.status === "active" ? "good" : ["failed", "interrupted"].includes(run?.status) ? "warning" : "neutral"}`;
  byId("run-id").textContent = run?.id || "Any foreground app";
  renderElapsed();
  setCheck("check-bridge", connected);
  setCheck("check-consent", state.consentGranted);
  setCheck("check-service", state.serviceConnected);
  setCheck("check-monitoring", state.monitoringEnabled && !state.warningActive);
  setCheck("check-jev", state.jevReady);
  setCheck("check-voice", state.voiceReady);
  byId("export").classList.toggle("disabled", !run);
  byId("export").setAttribute("aria-disabled", String(!run));
  byId("save-status").textContent = run ? active ? "Recording locally" : run.status === "interrupted" ? "Partial run saved" : "Run saved" : "Runs save locally.";
  byId("save-folder").textContent = run?.folder || "";
  if (!streamReady) notice("Dashboard connection lost. Reconnecting…", "warning");
  else if (!connected) notice(model.message || "Connect a phone or emulator to begin.", "warning");
  else if (differentDevice) notice("Select Switch device to connect to the selected device.");
  else if (run?.status === "interrupted") notice("Run interrupted. Received events are saved. Start a new run to continue.", "warning");
  else if (run?.status === "failed") notice(run.reason || "The run could not start.", "error");
  else if (!state.consentGranted) notice("Open Antiscam on the selected device and review capture and cloud consent.", "warning");
  else if (!state.serviceConnected) notice("Enable Antiscam interaction capture in Android Accessibility settings.", "warning");
  else if (state.warningActive) notice("Scam warning open on the phone. Screenshots and OCR are paused. Close it with the red X to resume.", "warning");
  else if (!state.monitoringEnabled) notice("Capture paused. Select Resume, then Start run.", "warning");
  else notice(run?.status === "stopping" ? "Waiting for Stop confirmation…" : "");
}

function renderDevices(selectConnected = false) {
  const select = byId("device");
  const previous = select.value;
  select.replaceChildren();
  if (!model.devices.length || model.devices.length > 1) {
    const option = document.createElement("option");
    option.value = "";
    option.textContent = model.devices.length ? "Choose a device" : "No device";
    select.append(option);
  }
  for (const device of model.devices) {
    const option = document.createElement("option");
    option.value = device.serial;
    option.textContent = `${device.serial}${device.status !== "device" ? ` · ${device.status}` : ""}`;
    option.disabled = device.status !== "device";
    select.append(option);
  }
  select.value = (selectConnected ? model.selectedDevice : previous) || model.selectedDevice || (model.devices.length === 1 && model.devices[0].status === "device" ? model.devices[0].serial : "");
}

function scrollPosition(pane) {
  const paneTop = pane.getBoundingClientRect().top;
  const anchor = [...pane.querySelectorAll("[data-scroll-key]")].find((item) => item.getBoundingClientRect().bottom > paneTop);
  return { top: pane.scrollTop, height: pane.scrollHeight, follow: pane.scrollTop < 8,
    anchorKey: anchor?.dataset.scrollKey, anchorOffset: anchor ? anchor.getBoundingClientRect().top - paneTop : 0 };
}

function restoreScroll(pane, position) {
  // Keep the visible capture in place when records arrive or a Jev card changes height.
  // CSS disables native scroll anchoring, so this adjustment happens only once.
  const anchor = position.anchorKey == null ? null : [...pane.querySelectorAll("[data-scroll-key]")].find((item) => item.dataset.scrollKey === position.anchorKey);
  if (position.follow) pane.scrollTop = 0;
  else if (anchor) pane.scrollTop += anchor.getBoundingClientRect().top - pane.getBoundingClientRect().top - position.anchorOffset;
  else pane.scrollTop = position.top + pane.scrollHeight - position.height;
}

function updateTextToggle(text) {
  const result = text.closest(".result");
  const expanded = result.classList.contains("result-expanded");
  result.querySelector(".result-toggle").hidden = !expanded && text.scrollHeight <= text.clientHeight;
}

function regionSourceLabel(region) {
  if (region.sourceAttribution !== "window_bounds") return "Source uncertain";
  const source = region.appLabel || region.packageName;
  if (!source) return "Source uncertain";
  const kind = region.sourceKind === "overlay" ? "Overlay · " : region.sourceKind === "system" ? "System · " : "";
  return `${kind}${source} (estimated)`;
}

function prependOcr(item, index, preserveScroll = true) {
  const pane = byId("text-scroll");
  const position = preserveScroll ? scrollPosition(pane) : null;
  const result = document.createElement("li");
  result.className = "result";
  result.dataset.scrollKey = String(item.captureId);
  result.classList.toggle("result-expanded", expandedCaptures.has(item.captureId));
  const heading = document.createElement("div");
  heading.className = "result-heading";
  const label = document.createElement("span");
  label.className = "result-title";
  label.textContent = `Capture ${item.captureId}`;
  const app = document.createElement("span");
  app.className = "result-app";
  const displayCapture = item.captureScope === "display";
  app.textContent = displayCapture ? item.sourceLabel || "Visible screen · source uncertain" : item.appLabel || item.packageName || item.targetPackage || "Unknown app";
  const meta = document.createElement("span");
  meta.className = "result-meta";
  meta.textContent = `${timestamp(item.timestampMs)}${item.durationMs != null ? ` · ${item.durationMs} ms OCR` : ""}`;
  heading.append(label, app, meta);
  const text = document.createElement("pre");
  text.id = `capture-text-${index}`;
  // Captured text is untrusted. It is never interpreted as HTML.
  const completeRegions = Array.isArray(item.textRegions) && item.textRegions.length &&
    item.textRegions.map((region) => region.text).join("\n") === item.text;
  if (displayCapture && completeRegions) {
    let previousSource = "";
    for (const region of item.textRegions) {
      const source = regionSourceLabel(region);
      if (source !== previousSource) {
        const attribution = document.createElement("span");
        attribution.className = "result-source";
        attribution.textContent = source;
        text.append(attribution);
        previousSource = source;
      }
      text.append(document.createTextNode(`${region.text || ""}\n`));
    }
  } else {
    text.textContent = item.text || "No text found in this capture.";
  }
  const footer = document.createElement("div");
  footer.className = "result-footer";
  const detail = document.createElement("span");
  const context = displayCapture ? `Trigger: ${item.appLabel || item.packageName || "Unknown app"}` : item.packageName || item.targetPackage || "Unknown package";
  const maskCount = Array.isArray(item.maskedRegions) ? item.maskedRegions.length : 0;
  detail.textContent = `${item.characters ?? item.text.length} characters · ${context}${maskCount ? ` · ${maskCount} regions excluded` : ""}`;
  const actions = document.createElement("div");
  actions.className = "result-actions";
  const toggle = document.createElement("button");
  toggle.type = "button";
  toggle.className = "result-toggle";
  toggle.setAttribute("aria-controls", text.id);
  const updateExpanded = () => {
    const expanded = result.classList.contains("result-expanded");
    toggle.textContent = expanded ? "Collapse text" : "Expand text";
    toggle.setAttribute("aria-expanded", String(expanded));
    toggle.setAttribute("aria-label", `${toggle.textContent} for capture ${item.captureId}`);
  };
  updateExpanded();
  toggle.addEventListener("click", () => {
    const expanded = result.classList.toggle("result-expanded");
    if (expanded) expandedCaptures.add(item.captureId);
    else expandedCaptures.delete(item.captureId);
    updateExpanded();
    updateTextToggle(text);
    if (!expanded) result.scrollIntoView({ block: "nearest" });
  });
  const copy = document.createElement("button");
  copy.type = "button";
  copy.textContent = "Copy text";
  copy.setAttribute("aria-label", `Copy text from capture ${item.captureId}`);
  copy.addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText(item.text);
      copy.textContent = "Copied";
    } catch {
      copy.textContent = "Select text to copy";
    }
  });
  actions.append(toggle, copy);
  footer.append(detail, actions);
  result.append(heading, text, footer);
  byId("results").prepend(result);
  byId("empty").hidden = true;
  byId("result-count").textContent = String(index + 1);
  updateTextToggle(text);
  textResizeObserver.observe(text);
  if (position) restoreScroll(pane, position);
}

function renderOcr() {
  textResizeObserver.disconnect();
  byId("results").replaceChildren();
  byId("empty").hidden = model.ocr.length > 0;
  byId("result-count").textContent = String(model.ocr.length);
  model.ocr.forEach((item, index) => prependOcr(item, index, false));
}

function renderJevCard(card, item) {
  const jev = item.jev;
  const status = jev?.status || "unavailable";
  const completed = status === "completed";
  const hasCutoff = Number.isFinite(jev?.threshold) && jev.threshold >= 0 && jev.threshold <= 1;
  card.className = "jev-card";
  card.classList.toggle("jev-flagged", completed && jev.flagged === true);
  card.replaceChildren();
  const heading = document.createElement("div");
  heading.className = "result-heading";
  const label = document.createElement("span");
  label.className = "result-title";
  label.textContent = `Capture ${item.captureId}`;
  const meta = document.createElement("span");
  meta.className = "result-meta";
  meta.textContent = `${timestamp(item.timestampMs)}${jev?.durationMs != null ? ` · ${jev.durationMs} ms Jev` : ""}`;
  heading.append(label, meta);
  const summary = document.createElement("div");
  summary.className = "jev-summary";
  const badge = document.createElement("span");
  badge.className = `badge ${completed && jev.flagged === true ? "warning" : status === "failed" ? "error" : "neutral"}`;
  badge.textContent = completed ? jev.flagged === true ? "Probably scam" : jev.flagged === false ? "No question reached cutoff" : "Flag unavailable" : title(status);
  summary.append(badge);
  if (hasCutoff) {
    const cutoff = document.createElement("span");
    cutoff.className = "jev-cutoff";
    cutoff.textContent = `Yes at ≥ ${jev.threshold}`;
    summary.append(cutoff);
  }
  card.append(heading, summary);
  const configuration = [jev?.model, jev?.questionSetVersion].filter(Boolean);
  if (configuration.length) {
    const detail = document.createElement("p");
    detail.className = "jev-model";
    detail.textContent = configuration.join(" · ");
    card.append(detail);
  }
  const questions = Object.entries(jev?.questions || {});
  if (questions.length) {
    const list = document.createElement("ol");
    list.className = "jev-questions";
    list.setAttribute("role", "list");
    for (const [id, question] of questions) {
      const row = document.createElement("li");
      row.className = "jev-question";
      const text = document.createElement("span");
      text.className = "jev-question-text";
      text.textContent = question.instructions;
      const result = document.createElement("span");
      result.className = "jev-answer";
      const answer = jev.answers?.[id];
      const valid = completed && answer?.type === "noul" && Number.isFinite(answer.noul) && answer.noul >= 0 && answer.noul <= 1;
      if (valid) {
        const yes = hasCutoff && answer.noul >= jev.threshold;
        result.textContent = hasCutoff ? `${yes ? "Yes" : "No"} · ${answer.noul}` : `${answer.noul} · No cutoff`;
        result.classList.toggle("yes", yes);
      } else result.textContent = status === "pending" ? "Waiting" : "Unavailable";
      row.append(text, result);
      list.append(row);
    }
    card.append(list);
  }
  if (jev?.reason || !questions.length) {
    const reason = document.createElement("p");
    reason.className = "jev-reason";
    reason.textContent = jev?.reason ? title(jev.reason) : "No Jev question details received for this capture.";
    card.append(reason);
  }
}

function prependJev(item, preserveScroll = true) {
  const pane = byId("jev-scroll");
  const position = preserveScroll ? scrollPosition(pane) : null;
  const card = document.createElement("li");
  card.dataset.scrollKey = String(item.captureId);
  jevCards.set(String(item.captureId), card);
  renderJevCard(card, item);
  byId("jev-results").prepend(card);
  byId("jev-empty").hidden = true;
  byId("jev-count").textContent = String(model.ocr.length);
  if (position) restoreScroll(pane, position);
}

function renderJev() {
  jevCards.clear();
  byId("jev-results").replaceChildren();
  byId("jev-empty").hidden = model.ocr.length > 0;
  byId("jev-count").textContent = String(model.ocr.length);
  model.ocr.forEach((item) => prependJev(item, false));
}

function applyJev(update) {
  const item = model.ocr.find((capture) => String(capture.captureId) === String(update.captureId));
  if (!item) return;
  item.jev = update.jev;
  const card = jevCards.get(String(update.captureId));
  if (!card) return;
  const pane = byId("jev-scroll");
  const position = scrollPosition(pane);
  renderJevCard(card, item);
  restoreScroll(pane, position);
}

function prependActivity(entry, preserveScroll = true) {
  const pane = byId("activity-scroll");
  const position = preserveScroll ? scrollPosition(pane) : null;
  const item = document.createElement("li");
  if (entry.type.endsWith("failed") || entry.type === "command_error") item.className = "event-error";
  else if (["capture_skipped", "capture_discarded", "jev_skipped", "jev_canceled"].includes(entry.type)) item.className = "event-warning";
  const time = document.createElement("time");
  time.textContent = timestamp(entry.timestampMs);
  if (Number.isFinite(entry.timestampMs)) time.dateTime = new Date(entry.timestampMs).toISOString();
  const description = document.createElement("div");
  description.className = "activity-description";
  const eventName = document.createElement("span");
  eventName.className = "activity-event";
  const interaction = entry.interaction || entry.value;
  eventName.textContent = `${labels[entry.type] || title(entry.type)}${interaction ? ` · ${title(interaction)}` : ""}${entry.captureId != null ? ` #${entry.captureId}` : ""}`;
  const detail = document.createElement("span");
  detail.className = "activity-detail";
  const source = entry.captureScope === "display" ? entry.sourceLabel || "Visible screen" : entry.appLabel || entry.packageName;
  detail.textContent = [source, entry.trigger && title(entry.trigger), entry.reason && title(entry.reason), entry.phase && title(entry.phase), entry.durationMs != null && `${entry.durationMs} ms`].filter(Boolean).join(" · ");
  description.append(eventName, detail);
  item.append(time, description);
  byId("activity").prepend(item);
  byId("activity-empty").hidden = true;
  if (position) restoreScroll(pane, position);
}

function renderActivity() {
  byId("activity").replaceChildren();
  byId("activity-empty").hidden = model.activity.length > 0;
  byId("activity-count").textContent = String(model.activity.length);
  model.activity.forEach((entry) => prependActivity(entry, false));
}

function applySnapshot(snapshot) {
  const sameRun = model.run?.id === snapshot.run?.id;
  const deviceChanged = model.selectedDevice !== snapshot.selectedDevice;
  if (!sameRun) expandedCaptures.clear();
  const positions = ["jev-scroll", "text-scroll", "activity-scroll"].map((id) => ({ id, ...scrollPosition(byId(id)) }));
  if (snapshot.token) token = snapshot.token;
  model = { ...model, ...snapshot };
  renderDevices(deviceChanged);
  renderOcr();
  renderJev();
  renderActivity();
  renderStatus();
  for (const position of positions) {
    const pane = byId(position.id);
    if (sameRun) restoreScroll(pane, position);
    else pane.scrollTop = 0;
  }
}

async function request(path, body) {
  commandBusy = true;
  renderStatus();
  try {
    const response = await fetch(path, { method: "POST", headers: { "Content-Type": "application/json", "X-Antiscam-Token": token }, body: JSON.stringify(body) });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || "The command failed.");
  } catch (error) {
    notice(error.message || "The local recorder is unavailable.", "error");
  } finally {
    commandBusy = false;
    // Keep a command error visible until the next device event.
    const text = byId("notice").textContent;
    const tone = byId("notice").className;
    renderStatus();
    if (tone.includes("error")) { byId("notice").textContent = text; byId("notice").className = tone; byId("notice").hidden = false; }
  }
}

async function sendApiKey(action = "send") {
  if (commandBusy || model.connection !== "connected" || !streamReady || byId("device").value !== model.selectedDevice) return;
  const provider = keyProviders[keyProvider];
  const clear = action === "clear";
  const useSaved = action === "saved";
  const remember = Boolean(model.keyStorage?.available && byId("api-key-remember").checked);
  const hadSavedKey = Boolean(model.keyStorage?.saved?.[keyProvider]);
  if (useSaved && (!model.keyStorage?.available || !hadSavedKey)) return;
  let payload = JSON.stringify(useSaved
    ? { useSaved: true, serial: model.selectedDevice }
    : { apiKey: clear ? "" : byId("api-key-input").value, remember: !clear && remember, serial: model.selectedDevice });
  byId("api-key-input").value = "";
  keySending = true;
  commandBusy = true;
  byId("api-key-message").textContent = "";
  byId("api-key-message").className = "key-message";
  renderStatus();
  try {
    const delivery = fetch(provider.endpoint, { method: "POST", headers: { "Content-Type": "application/json", "X-Antiscam-Token": token }, body: payload });
    payload = "";
    const response = await delivery;
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || "The key request failed. Check the phone connection and try again.");
    if (result.keyStorage) model.keyStorage = result.keyStorage;
    const deliveryMessage = useSaved ? "Saved key sent to phone." : remember
      ? "Key saved on this PC and sent to phone." : "Key sent to phone memory only. Any saved copy was removed.";
    byId("api-key-message").textContent = clear
      ? hadSavedKey ? "Saved key forgotten. Clear request sent to phone." : "Clear request sent to phone."
      : `${deliveryMessage} ${keyProvider === "jev" ? "Jev readiness confirms receipt, not key validity." : "The OpenAI indicator confirms loading, not key validity. Voice also needs consent and microphone permission."}`;
  } catch (error) {
    byId("api-key-message").textContent = error.message || "The request could not be sent. Check the phone connection and try again.";
    byId("api-key-message").className = "key-message error";
  } finally {
    payload = "";
    keySending = false;
    commandBusy = false;
    renderStatus();
  }
}

function openApiKey(provider) {
  if (commandBusy) return;
  keyProvider = provider;
  byId("api-key-title").textContent = `${keyProviders[provider].label} API key`;
  byId("api-key-voice-help").hidden = provider !== "openai";
  byId("api-key-input").setAttribute("aria-describedby", provider === "openai" ? "api-key-help api-key-voice-help" : "api-key-help");
  byId("api-key-input").value = "";
  byId("api-key-message").textContent = "";
  byId("api-key-message").className = "key-message";
  renderStatus();
  byId("api-key-dialog").showModal();
}

byId("jev-key-open").addEventListener("click", () => openApiKey("jev"));
byId("openai-key-open").addEventListener("click", () => openApiKey("openai"));
byId("api-key-form").addEventListener("submit", (event) => { event.preventDefault(); sendApiKey(); });
byId("api-key-clear").addEventListener("click", () => sendApiKey("clear"));
byId("api-key-use-saved").addEventListener("click", () => sendApiKey("saved"));
byId("api-key-close").addEventListener("click", () => byId("api-key-dialog").close());
byId("api-key-dialog").addEventListener("cancel", () => { byId("api-key-input").value = ""; });
byId("api-key-dialog").addEventListener("close", () => { byId("api-key-input").value = ""; });

byId("start").addEventListener("click", () => request("/api/command", { command: "start" }));
byId("stop").addEventListener("click", () => request("/api/command", { command: "stop" }));
byId("pause").addEventListener("click", () => request("/api/command", { command: model.state.monitoringEnabled ? "pause" : "resume" }));
byId("refresh").addEventListener("click", () => request("/api/devices", {}));
byId("connect").addEventListener("click", () => request("/api/connect", { serial: byId("device").value }));
byId("device").addEventListener("change", renderStatus);
byId("export").addEventListener("click", (event) => { if (!model.run) event.preventDefault(); });
byId("text-latest").addEventListener("click", () => { byId("text-scroll").scrollTop = 0; });
byId("jev-latest").addEventListener("click", () => { byId("jev-scroll").scrollTop = 0; });
byId("activity-latest").addEventListener("click", () => { byId("activity-scroll").scrollTop = 0; });

async function initialize() {
  try {
    const response = await fetch("/api/session", { cache: "no-store" });
    if (!response.ok) throw new Error("The local recorder rejected the connection.");
    const session = await response.json();
    token = session.token;
    applySnapshot(session);
    stream = new EventSource("/api/events");
    stream.onopen = () => { streamReady = true; renderStatus(); };
    stream.onmessage = (event) => {
      const update = JSON.parse(event.data);
      if (update.kind === "snapshot") applySnapshot(update.data);
      else if (update.kind === "status") {
        const deviceChanged = model.selectedDevice !== update.data.selectedDevice;
        model = { ...model, ...update.data };
        renderDevices(deviceChanged);
        renderStatus();
      }
      else if (update.kind === "ocr") { model.ocr.push(update.data); prependOcr(update.data, model.ocr.length - 1); prependJev(update.data); }
      else if (update.kind === "jev") applyJev(update.data);
      else if (update.kind === "activity") { model.activity.push(update.data); prependActivity(update.data); byId("activity-count").textContent = String(model.activity.length); }
    };
    stream.onerror = () => { streamReady = false; renderStatus(); };
  } catch (error) {
    notice(`${error.message} Restart the launcher, then reload this window.`, "error");
  }
}

window.addEventListener("pagehide", () => { byId("api-key-input").value = ""; stream?.close(); });
// This timer only updates the displayed clock. Device data arrives through SSE.
setInterval(renderElapsed, 1000);
initialize();
