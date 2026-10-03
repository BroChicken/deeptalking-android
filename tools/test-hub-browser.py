"""Offline browser regression.

Smoke / differential: python tools/test-hub-browser.py --smoke | --baseline-ref <ref>
Real private save boot check (read-only): python tools/test-hub-browser.py --save [path]
"""
import argparse
import hashlib
import json
import mimetypes
import re
import subprocess
import tempfile
from pathlib import Path
from urllib.parse import urlparse

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent.parent
URL = "https://hub.test/hub.html"
KEY = "deeptalking_data_v1"
INIT = """(() => {
  const RealDate = Date;
  window.Date = class extends RealDate {
    constructor(...args) { super(...(args.length ? args : ['2026-10-02T08:00:00Z'])); }
    static now() { return new RealDate('2026-10-02T08:00:00Z').getTime(); }
  };
  let seed = 42;
  Math.random = () => ((seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296);
  window.nativeBackups = [];
  window.AndroidBridge = { saveBackup: (text, name) => {
    nativeBackups.push(JSON.parse(text)); return 'ok:Downloads/' + name;
  }};
})();"""
SEED = """() => {
  const char = createCharacterObj('Test Character', '\u2b50', 'Calm', 'A researcher.');
  char.id = 'fixture-character';
  Object.keys(STATIC_PROFILE_FIELDS).forEach(key => char.basicInfo[key] = 'Known ' + key);
  char.basicInfo.speakingStyle = 'Short, direct sentences.';
  char.fieldsMigrationVersion = FIELDS_MIGRATION_VERSION;
  char.lorebook = normalizeLorebook([{id: 'book-one', name: 'Library', content: 'A quiet library.',
    keywords: ['library'], alwaysActive: true, origin: 'user'}]);
  char.memory.instant = [
    {id: 'old-user', role: 'user', content: 'Hello, library.', timestamp: new Date().toISOString()},
    {id: 'old-reply', role: 'assistant', content: '**Welcome.** Formula: $x^2 + y^2$.', timestamp: new Date().toISOString()}
  ];
  char.dynamicState.currentLocation = 'Library';
  const group = createGroupObj('Test Group', '\u2b50', 'A study group.',
    [{basicInfo: {...char.basicInfo, name: 'Member One'}}, {basicInfo: {...char.basicInfo, name: 'Member Two'}}]);
  group.id = 'fixture-group';
  group.fieldsMigrationVersion = FIELDS_MIGRATION_VERSION;
  group.members.forEach(m => m.fieldsMigrationVersion = FIELDS_MIGRATION_VERSION);
  state.characters = {[char.id]: char, [group.id]: group};
  state.activeCharacterId = char.id;
  Object.assign(state.config, {apiKey: '', proactiveEnabled: false, styleCritique: false,
    quickReplyRepair: false, stream: false});
  saveData();
  return JSON.parse(localStorage.getItem(STORAGE_KEY));
}"""


def response(text):
    return {"status": "completed", "output": [{"type": "message", "role": "assistant",
            "content": [{"type": "output_text", "text": text}]}]}


def open_page(browser, html, viewport, fixture=None):
    context = browser.new_context(viewport=viewport, device_scale_factor=1)
    context.add_init_script(INIT)
    if fixture is not None:
        context.add_init_script("if (!localStorage.getItem(" + json.dumps(KEY) + ")) localStorage.setItem("
                                + json.dumps(KEY) + ", " + json.dumps(json.dumps(fixture)) + ");")
    page = context.new_page()
    errors = []
    requests = []
    mock = {"mode": "json", "count": 0}
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.on("console", lambda message: errors.append(message.text) if message.type == "error" else None)
    page.on("dialog", lambda dialog: dialog.accept())

    def route_request(route):
        parsed = urlparse(route.request.url)
        if parsed.hostname == "hub.test":
            if parsed.path == "/hub.html":
                route.fulfill(content_type="text/html", body=html)
                return
            resource = (ROOT / parsed.path.lstrip("/")).resolve()
            if resource.is_relative_to(ROOT / "katex") and resource.is_file():
                route.fulfill(content_type=mimetypes.guess_type(str(resource))[0] or "application/octet-stream",
                              body=resource.read_bytes())
                return
            route.fulfill(status=404, body="Missing test resource")
            return
        if route.request.method != "POST":
            route.abort()
            return
        body = route.request.post_data_json
        requests.append(body)
        mock["count"] += 1
        structured = {"reply": "The books are ready.", "quickReplies": ["Let's read.", "Tell me more."]}
        mode = mock["mode"]
        if mode == "multi":
            if mock["count"] <= 2:
                payload = {"output": [
                    {"type": "function_call", "name": "search_memory", "call_id": "call_1",
                     "arguments": json.dumps({"query": "books"})},
                    {"type": "function_call", "name": "list_memories", "call_id": "call_2", "arguments": "{}"}]}
            else:
                payload = {"output": [{"type": "function_call", "name": "submit_response",
                                      "call_id": "call_3", "arguments": json.dumps(structured)}]}
        elif mode == "tool" and mock["count"] == 1:
            payload = {"output": [{"type": "function_call", "name": "get_current_time",
                                  "call_id": "call-time", "arguments": "{}"}]}
        elif mode == "submit":
            payload = {"output": [{"type": "function_call", "name": "submit_response",
                                  "call_id": "call-submit", "arguments": json.dumps(structured)}]}
        else:
            text = "The room is quiet." if mode == "prose" and mock["count"] == 1 else json.dumps(structured)
            payload = response(text)
        if mode == "stream":
            text = json.dumps(structured)
            events = [{"type": "response.output_text.delta", "delta": text[:25]},
                      {"type": "response.output_text.delta", "delta": text[25:]},
                      {"type": "response.completed", "response": payload}]
            route.fulfill(content_type="text/event-stream", body="".join(
                "data: " + json.dumps(event) + "\n\n" for event in events) + "data: [DONE]\n\n")
        else:
            route.fulfill(content_type="application/json", body=json.dumps(payload))

    page.route("**/*", route_request)
    page.goto(URL, wait_until="networkidle")
    page.evaluate("document.fonts.ready")
    return context, page, errors, requests, mock


def exercise(browser, html, viewport, fixture, label, output):
    context, page, errors, requests, mock = open_page(browser, html, viewport, fixture)
    snapshots = {}

    def capture(name):
        page.evaluate("document.fonts.ready")
        snapshots[name] = page.screenshot(path=str(output / f"{label}-{viewport['width']}-{name}.png"),
                                          animations="disabled", caret="hide")

    assert page.locator(".katex").count() > 0, "Local math asset failed"
    assert page.evaluate("state.characters['fixture-character'].memory.instant.length") == 2
    capture("chat")
    page.locator("#themeMenuToggle").click()
    page.locator('[data-theme="theme-black"]').click()
    capture("dark")
    page.evaluate("openCharacterModal('fixture-character')")
    page.locator("#modal_basic_name").fill("Edited Character")
    page.locator('[onclick="switchModalTab(\'lorebook\')"]').click()
    capture("lorebook")
    page.locator('[onclick="switchModalTab(\'state\')"]').click()
    page.locator("#modal_state_currentLocation").fill("Study")
    page.locator('[onclick="saveCharacterModal()"]').click()
    assert page.evaluate("state.characters['fixture-character'].basicInfo.name") == "Edited Character"
    page.evaluate("selectCharacter('fixture-group'); openGroupMemberModal('fixture-group', 0)")
    page.locator("#modal_basic_name").fill("Edited Member")
    page.locator('[onclick="saveCharacterModal()"]').click()
    assert page.evaluate("state.characters['fixture-group'].members[0].basicInfo.name") == "Edited Member"
    capture("group")
    page.evaluate("toggleSidebar(true)")
    page.locator('[onclick="openCreateModal()"]').click()
    page.locator("#newCharName").fill("New Character")
    page.locator('[onclick="createCharacter()"]').click()
    new_id = page.evaluate("state.activeCharacterId")
    assert page.evaluate("Object.keys(state.characters).length") == 3
    page.evaluate("id => deleteCharacter(id)", new_id)
    page.evaluate("selectCharacter('fixture-character'); toggleSidebar(true)")
    page.locator("#tab-settings").click()
    page.locator("#apiKey").fill("test-only-key")
    page.locator("#proactiveToggle").uncheck()
    page.locator("#apiStream").uncheck()
    page.locator('[onclick="saveSettings()"]').click()
    capture("settings")
    page.evaluate("toggleSidebar(false); exportAllData()")
    backup = page.evaluate("nativeBackups.at(-1)")
    assert "apiKey" not in backup["config"]
    assert len(backup["characters"]) == 2
    backup["characters"]["fixture-character"]["basicInfo"]["name"] = "Imported Character"
    page.locator("#importFile").set_input_files({"name": "backup.json", "mimeType": "application/json",
                                               "buffer": json.dumps(backup).encode()})
    page.wait_for_function("state.characters['fixture-character'].basicInfo.name === 'Imported Character'")
    assert page.evaluate("state.config.apiKey") == "test-only-key", "Import must preserve the local API key"
    multi_tool_found = False
    for mode in ["json", "submit", "tool", "stream", "prose", "multi"]:
        mock.update(mode=mode, count=0)
        page.evaluate("state.config.apiKey = 'test-only-key'; state.config.stream = false")
        page.locator("#messageInput").fill("A new question about books: " + mode)
        page.locator('[onclick="sendMessage()"]').click()
        page.wait_for_function("!state.isProcessing && state.characters[state.activeCharacterId].memory.instant.at(-1).role === 'assistant'")
        expected = "The room is quiet." if mode == "prose" else "The books are ready."
        assert page.evaluate("state.characters[state.activeCharacterId].memory.instant.at(-1).content") == expected
        if mode == "tool":
            assert mock["count"] == 2
            assert any(item.get("type") == "function_call_output" for item in requests[-1]["input"])
        if mode == "multi":
            assert mock["count"] == 3, mock["count"]
            multi_tool_found = True
        page.evaluate("backgroundTaskChain")
    assert multi_tool_found, "multi-tool scenario did not run"
    for request in requests:
        call_ids = [item.get("call_id") for item in request.get("input", []) if item.get("type") == "function_call"]
        assert len(call_ids) == len(set(call_ids)), "duplicate call_id sent to the API: " + str(call_ids)
    page.evaluate("flushScheduledSave()")
    stored = page.evaluate("JSON.parse(localStorage.getItem(STORAGE_KEY))")
    page.reload(wait_until="networkidle")
    assert page.evaluate("state.characters['fixture-character'].memory.instant.length") == len(
        stored["characters"]["fixture-character"]["memory"]["instant"])
    assert not errors, errors
    context.close()
    return snapshots, stored, requests


DEFAULT_SAVE = ROOT / "save/deeptalking_backup_2026-10-02.json"


def verify_save(browser, html, save_path, output):
    save_path = Path(save_path)
    data = json.loads(save_path.read_text(encoding="utf-8"))
    digest = hashlib.sha256(save_path.read_bytes()).hexdigest()
    context, page, errors, _, _ = open_page(browser, html, {"width": 390, "height": 844}, data)
    page.evaluate("state.config.proactiveEnabled = false; state.config.apiKey = '';")
    page.wait_for_function("state.characters && Object.keys(state.characters).length > 0")
    expected = len(data.get("characters", {}))
    assert page.evaluate("Object.keys(state.characters).length") == expected, "character count changed after load"
    assert page.locator("#appVersionBadge").inner_text().startswith("v")
    for character_id in data["characters"]:
        page.evaluate("id => selectCharacter(id)", character_id)
        assert page.evaluate("state.activeCharacterId") == character_id, "selectCharacter failed for " + character_id
    page.screenshot(path=str(output / "real-save-mobile.png"), full_page=False, animations="disabled")
    page.evaluate("flushScheduledSave()")
    assert page.evaluate("!!localStorage.getItem(STORAGE_KEY)")
    fatal = [error for error in errors if "Failed to load resource" not in error and "net::" not in error]
    assert not fatal, fatal
    context.close()
    assert hashlib.sha256(save_path.read_bytes()).hexdigest() == digest, "read-only save file was modified"
    print(f"PASS real save: {expected} characters boot, select, render, persist (file read-only)")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-ref", help="Transparent-refactor mode: diff this commit against the build")
    parser.add_argument("--smoke", action="store_true", help="Smoke-test the current build without a baseline")
    parser.add_argument("--save", nargs="?", const=str(DEFAULT_SAVE), default=None,
                        help="Boot-check a real save file read-only (default: latest backup in save/)")
    args = parser.parse_args()
    current = (ROOT / "hub.html").read_text(encoding="utf-8")
    temp_root = Path(tempfile.gettempdir()) / "opencode"
    assert temp_root.is_dir(), "Expected temporary workspace: " + str(temp_root)
    output = Path(tempfile.mkdtemp(prefix="hub-regression-", dir=temp_root))
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        try:
            if args.save:
                verify_save(browser, current, args.save, output)
                print("Artifacts: " + str(output))
                return
            if args.smoke:
                baseline = current
            else:
                assert args.baseline_ref, "Provide --baseline-ref for differential mode or --smoke for a single build"
                baseline = subprocess.check_output(["git", "show", f"{args.baseline_ref}:hub.html"], cwd=ROOT).decode("utf-8")
                normalize = lambda html: re.sub(r"const APP_VERSION = '[^']*';", "const APP_VERSION = 'test';", html)
                assert normalize(baseline) == normalize(current), "Generated runtime changed beyond version; use --smoke"
            context, page, errors, _, _ = open_page(browser, baseline, {"width": 1280, "height": 800})
            fixture = page.evaluate(SEED)
            assert not errors, errors
            context.close()
            for viewport in [{"width": 1280, "height": 800}, {"width": 390, "height": 844}, {"width": 360, "height": 740}]:
                if args.smoke:
                    exercise(browser, current, viewport, fixture, "current", output)
                    print(f"PASS {viewport['width']}px: screenshots, data, backups, edits, API paths, persistence")
                else:
                    old = exercise(browser, baseline, viewport, fixture, "baseline", output)
                    new = exercise(browser, current, viewport, fixture, "current", output)
                    assert old == new, f"Behavior or screenshots differ at {viewport}"
                    print(f"PASS {viewport['width']}px: 5 identical screenshots, data, backups, edits, API paths, persistence")
            for file in [ROOT / "hub.html", ROOT / "android-lite/app/src/main/assets/hub.html"]:
                page = browser.new_page()
                errors = []
                page.on("pageerror", lambda error: errors.append(str(error)))
                page.goto(file.as_uri(), wait_until="networkidle")
                assert page.evaluate("typeof katex.renderToString") == "function"
                assert page.locator("#appVersionBadge").inner_text().startswith("v")
                assert not errors, errors
                page.close()
                print("PASS file:// " + str(file.relative_to(ROOT)))
        finally:
            browser.close()
    print("Artifacts: " + str(output))


if __name__ == "__main__":
    main()
