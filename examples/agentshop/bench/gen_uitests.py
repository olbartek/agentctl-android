#!/usr/bin/env python3
"""Generates AgentShop's Compose UI tests from its scenario files, so all three modes run the same steps.

The port of agentctl-ios's Examples/AgentShop/bench/gen_uitests.py: the same mapping, emitting Kotlin instrumented
tests instead of XCUITests.

    python3 bench/gen_uitests.py            # write app/src/androidTest/…/generated/*.kt and bench/uitests.json
    python3 bench/gen_uitests.py --check    # exit 1 if the generated files are stale (CI)
    python3 bench/gen_uitests.py --self-test

How a scenario becomes a UI test:

1. `./appctl screens` lists every screen path with its screen name and its commands (argument, source, gate).
   That is what a command's test tag is derived from (`app/…/design/UiTestTags.kt`).
2. `./appctl run --json` runs the scenario headlessly. Step i's command was sent on step i-1's screen, so every
   command is resolved against the screen the app really showed, and every `expect` against its own step's screen.
3. Each command becomes one driver call (`app/src/androidTest/…/ShopUiTestCase.kt`):
   no argument -> tap `<Screen>.<command>`; `<on|off>` -> a switch; free text -> typing into `<Screen>.<command>`;
   any other argument -> tap `<Screen>.<command>.<argument>`; a container's `back` -> the back button.
4. `expect` pairs become waits: `screen=` and `error=` always; `can*` keys through the command they gate; other
   keys only where the screen shows the value (`UI_KEYS`). Everything else (`loading`, `call=`, timers…) is
   checked headlessly only, and counted as such in `bench/uitests.json`.
5. `mock <method> <code>` becomes a `mock-fault` launch extra `<method>#<n>=<code>`: n is the index of the call
   the fault hits, counted from the headless run's `calls`.

A scenario that uses `advance`, `login-as` or `reset` cannot be replayed through the UI and is skipped (it stays a
headless scenario). Anything else that has no UI mapping is an error, so the mapping never silently drifts.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCENARIOS = ROOT / "scenarios"
PACKAGE = "io.github.olbartek.agentctl.examples.agentshop.app"
OUT_DIR = ROOT / "app/src/androidTest/kotlin" / PACKAGE.replace(".", "/") / "generated"
MANIFEST = ROOT / "bench/uitests.json"

GROUPS = {"auth": "AuthUiTests", "onboarding": "OnboardingUiTests", "shop": "ShopUiTests"}

#: Commands that only exist headlessly (or only make sense there).
HEADLESS_ONLY = {"advance", "login-as", "reset"}

#: Summary keys each screen shows on screen, as `<Screen>.<key>` with the value as its `UiTestValue`.
UI_KEYS: dict[str, set[str]] = {
    "Login": {"email"},
    "OTPLogin": {"email"},
    "Register": {"email"},
    "ForgotPassword": {"email"},
    "VerifyEmail": set(),
    "OrdersList": {"orders"},
    "OrderDetail": {"status", "total"},
    "Profile": {"name", "email"},
    "Welcome": {"page"},
    "Interests": {"selected"},
    "AddressForm": {"name", "street", "city", "zip"},
    "Notifications": {"notifications"},
    "ShopFeed": {"products", "filter", "sort"},
    "ProductDetail": {"name", "price", "size", "qty", "favorite", "inCart"},
    "Cart": {"lines", "items", "subtotal", "discount", "total", "promo"},
    "Checkout": {"name", "street", "city", "zip", "shipping", "payment", "total"},
    "OrderConfirmation": {"order", "total"},
}

#: Argument placeholders that mean "type this text".
TEXT_ARGUMENTS = {"<text>", "<digits>", "<number>", "<code>"}


@dataclass
class Command:
    name: str
    argument: str | None
    source: str
    gate_key: str | None  # "canSubmit" for "[disabled when canSubmit=false]"


@dataclass
class Screen:
    pattern: str
    name: str
    commands: dict[str, Command] = field(default_factory=dict)

    def matches(self, path: str) -> bool:
        regex = "^" + re.sub(r"<[^>]+>", "[^/]+", re.escape(self.pattern).replace(r"\<", "<").replace(r"\>", ">")) + "$"
        return re.match(regex, path) is not None


class GenerationError(Exception):
    pass


# MARK: Parsing


def parse_screens(text: str) -> list[Screen]:
    """Parses `./appctl screens`."""
    screens: list[Screen] = []
    current: Screen | None = None
    for raw in text.splitlines():
        header = re.match(r"^(\S+)\s+\[(\w+)\]$", raw)
        if header:
            if header.group(2) == "runtime":
                current = None
                continue
            current = Screen(header.group(1), header.group(2))
            screens.append(current)
            continue
        if current is None or not raw.startswith("  ") or raw.strip().startswith("summary:"):
            continue
        line = raw.strip()
        if line == "(no screen commands)":
            continue
        usage = re.split(r"\s{2,}", line, maxsplit=1)[0]
        name, _, argument = usage.partition(" ")
        source_match = re.search(r"\s{2}\((\w+)\)$", line)
        gate_match = re.search(r"\[disabled when (\w+)=false\]", line)
        current.commands[name] = Command(
            name=name,
            argument=argument or None,
            source=source_match.group(1) if source_match else current.name,
            gate_key=gate_match.group(1) if gate_match else None,
        )
    return screens


def parse_pairs(text: str) -> list[tuple[str, str]]:
    """`k=v k2="a b"` -> [(k, v), (k2, a b)]."""
    pairs = []
    for key, value in re.findall(r'([\w.]+)=("(?:[^"\\]|\\.)*"|\S+)', text):
        if value.startswith('"'):
            value = value[1:-1].replace('\\"', '"')
        pairs.append((key, value))
    return pairs


def script_lines(text: str) -> list[tuple[int, str]]:
    """(line number, command) for every command in a scenario file, as the CLI splits it (one per line here)."""
    lines = []
    for number, raw in enumerate(text.splitlines(), start=1):
        stripped = raw.strip()
        if not stripped or stripped.startswith("#"):
            continue
        lines.append((number, stripped))
    return lines


# MARK: Generation


def kotlin_string(text: str) -> str:
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def screen_for(screens: list[Screen], path: str) -> Screen:
    exact = [s for s in screens if s.pattern == path]
    if exact:
        return exact[0]
    for screen in screens:
        if screen.matches(path):
            return screen
    raise GenerationError(f"no screen pattern matches {path}")


@dataclass
class Generated:
    name: str
    test: str
    body: list[str]
    faults: list[str]
    checked: int
    headless_only: int


def generate_test(file_name: str, text: str, steps: list[dict], screens: list[Screen]) -> Generated:
    lines = script_lines(text)
    commands = steps[1:]  # steps[0] is "(launch)"
    if len(lines) != len(commands):
        raise GenerationError(f"{file_name}: {len(lines)} script lines but {len(commands)} steps")
    body: list[str] = []
    faults: list[str] = []
    checked = headless_only = 0
    for index, ((number, command_text), step) in enumerate(zip(lines, commands), start=1):
        where = kotlin_string(f"{file_name}:{number}  {command_text}")
        name, _, argument = command_text.partition(" ")
        argument = argument.strip()
        if name != step["command"].partition(" ")[0]:
            raise GenerationError(f"{file_name}:{number}: step {step['command']!r} does not match {command_text!r}")

        if name == "mock":
            method, code = argument.split()
            # An `expect` step reprints the calls of the step before it, so only other steps count.
            previous = sum(
                call == method
                for earlier in steps[:index]
                if not earlier["command"].startswith("expect")
                for call in earlier["calls"]
            )
            faults.append(f"{method}#{previous + 1}={code}")
            continue

        if name == "expect":
            screen = screen_for(screens, step["screen"])
            for key, value in parse_pairs(argument):
                if key == "screen":
                    body.append(f"expectScreen({kotlin_string(value)}, line = {where})")
                elif key == "error":
                    body.append(
                        f"expectNoError(line = {where})" if value == "none"
                        else f"expectError({kotlin_string(value)}, line = {where})"
                    )
                elif gated := next((c for c in screen.commands.values() if c.gate_key == key), None):
                    owner = gated.source if gated.source != screen.name else screen.name
                    body.append(
                        f"expectEnabled({kotlin_string(f'{owner}.{gated.name}')}, {str(value == 'true').lower()}, line = {where})"
                    )
                elif key in UI_KEYS.get(screen.name, set()):
                    body.append(f"expectValue({kotlin_string(f'{screen.name}.{key}')}, {kotlin_string(value)}, line = {where})")
                else:
                    headless_only += 1
                    continue
                checked += 1
            continue

        previous_step = steps[index - 1]
        screen = screen_for(screens, previous_step["screen"])
        command = screen.commands.get(name)
        if command is None:
            raise GenerationError(f"{file_name}:{number}: '{name}' is not a command of {screen.pattern}")
        owner = command.source
        if name == "back" and owner != screen.name:
            body.append(f"back(line = {where})")
        elif command.argument is None:
            body.append(f"tap({kotlin_string(f'{owner}.{name}')}, line = {where})")
        elif command.argument == "<on|off>":
            body.append(f"setSwitch({kotlin_string(f'{owner}.{name}')}, {str(argument == 'on').lower()}, line = {where})")
        elif command.argument in TEXT_ARGUMENTS:
            body.append(f"type({kotlin_string(f'{owner}.{name}')}, {kotlin_string(argument)}, line = {where})")
        else:
            body.append(f"tap({kotlin_string(f'{owner}.{name}.{argument}')}, line = {where})")

    stem = file_name.removesuffix(".appctl")
    group, _, rest = stem.partition("-")
    return Generated(
        name=stem,
        test="test_" + rest.replace("-", "_"),
        body=body,
        faults=faults,
        checked=checked,
        headless_only=headless_only,
    )


def render(group: str, tests: list[Generated]) -> str:
    class_name = GROUPS[group]
    out = [
        f"// GENERATED by bench/gen_uitests.py from scenarios/{group}-*.appctl. Do not edit; run",
        "// `python3 bench/gen_uitests.py` after changing a scenario or a screen's commands.",
        f"package {PACKAGE}.generated",
        "",
        f"import {PACKAGE}.ShopUiTestCase",
        "import org.junit.Test",
        "",
        '@Suppress("FunctionName", "ktlint:standard:function-naming", "ktlint:standard:max-line-length")',
        f"class {class_name} : ShopUiTestCase() {{",
    ]
    for index, test in enumerate(tests):
        if index:
            out.append("")
        out.append("    @Test")
        out.append(f"    fun {test.test}() {{")
        faults = ", ".join(kotlin_string(f) for f in test.faults)
        out.append(f"        launch(faults = listOf({faults}))" if test.faults else "        launch()")
        out.extend(f"        {line}" for line in test.body)
        out.append("    }")
    out.append("}")
    return "\n".join(out) + "\n"


# MARK: Driving the CLI


def appctl(*arguments: str) -> str:
    result = subprocess.run([str(ROOT / "appctl"), *arguments], cwd=ROOT, capture_output=True, text=True)
    if result.returncode != 0:
        raise GenerationError(f"./appctl {' '.join(arguments[:1])} failed ({result.returncode}):\n{result.stdout}{result.stderr}")
    return result.stdout


def is_comparable(text: str) -> bool:
    return not any(command.split(" ", 1)[0] in HEADLESS_ONLY for _, command in script_lines(text))


def generate_all() -> tuple[dict[Path, str], dict]:
    screens = parse_screens(appctl("screens"))
    files: dict[Path, str] = {}
    manifest: dict = {"scenarios": {}}
    for group, class_name in GROUPS.items():
        tests = []
        for path in sorted(SCENARIOS.glob(f"{group}-*.appctl")):
            text = path.read_text()
            entry: dict = {"group": group}
            if is_comparable(text):
                steps = json.loads(appctl("run", "--json", text))
                test = generate_test(path.name, text, steps, screens)
                tests.append(test)
                entry.update(
                    comparable=True,
                    uitest=f"{PACKAGE}.generated.{class_name}#{test.test}",
                    steps=len(steps) - 1,
                    assertions_checked_in_ui=test.checked,
                    assertions_headless_only=test.headless_only,
                    faults=test.faults,
                )
            else:
                entry.update(comparable=False, steps=len(script_lines(text)))
            manifest["scenarios"][path.stem] = entry
        if tests:
            files[OUT_DIR / f"{class_name}.kt"] = render(group, tests)
    return files, manifest


# MARK: Self-test


def self_test() -> None:
    screens = parse_screens(
        "auth/login  [Login]\n"
        "  email <text>            Set the email field.\n"
        "  keep-signed-in <on|off>  Tick it.\n"
        "  submit                  Log in. [disabled when canSubmit=false]\n"
        "  login-as <alice|bob>    Go home.  (AppFeature)\n"
        "  summary: email canSubmit\n"
        "home/orders/<id>  [OrderDetail]\n"
        "  cancel                  Cancel. [disabled when canCancel=false]\n"
        "  tab <orders|profile>    Switch tab.  (HomeTabs)\n"
        "  back                    Go back.  (HomeTabs)\n"
        "every screen  [runtime]\n"
        "  expect k=v [k=v …]  Assert.\n"
    )
    assert [s.name for s in screens] == ["Login", "OrderDetail"], screens
    assert screens[0].commands["submit"].gate_key == "canSubmit"
    assert screens[0].commands["login-as"].source == "AppFeature"
    assert screen_for(screens, "home/orders/1003").name == "OrderDetail"
    assert parse_pairs('screen=items/2 title="Second item" saved=false') == [
        ("screen", "items/2"), ("title", "Second item"), ("saved", "false")
    ]
    text = "# comment\nexpect screen=auth/login canSubmit=false\nemail a@b.co\nkeep-signed-in off\nmock orders.fetchOrders network\nsubmit\nexpect screen=home/orders/1 loading=false\ntab profile\nback\n"
    steps = [
        {"command": "(launch)", "screen": "auth/login", "calls": ["session.current", "orders.fetchOrders"]},
        {"command": "expect screen=auth/login canSubmit=false", "screen": "auth/login", "calls": ["session.current", "orders.fetchOrders"]},
        {"command": "email a@b.co", "screen": "auth/login", "calls": []},
        {"command": "keep-signed-in off", "screen": "auth/login", "calls": []},
        {"command": "mock orders.fetchOrders network", "screen": "auth/login", "calls": []},
        {"command": "submit", "screen": "home/orders/1", "calls": ["auth.login", "orders.fetchOrders"]},
        {"command": "expect screen=home/orders/1 loading=false", "screen": "home/orders/1", "calls": []},
        {"command": "tab profile", "screen": "home/orders/1", "calls": []},
        {"command": "back", "screen": "home/orders/1", "calls": []},
    ]
    test = generate_test("auth-x.appctl", text, steps, screens)
    assert test.faults == ["orders.fetchOrders#2=network"], test.faults
    assert test.body == [
        'expectScreen("auth/login", line = "auth-x.appctl:2  expect screen=auth/login canSubmit=false")',
        'expectEnabled("Login.submit", false, line = "auth-x.appctl:2  expect screen=auth/login canSubmit=false")',
        'type("Login.email", "a@b.co", line = "auth-x.appctl:3  email a@b.co")',
        'setSwitch("Login.keep-signed-in", false, line = "auth-x.appctl:4  keep-signed-in off")',
        'tap("Login.submit", line = "auth-x.appctl:6  submit")',
        'expectScreen("home/orders/1", line = "auth-x.appctl:7  expect screen=home/orders/1 loading=false")',
        'tap("HomeTabs.tab.profile", line = "auth-x.appctl:8  tab profile")',
        'back(line = "auth-x.appctl:9  back")',
    ], "\n".join(test.body)
    assert test.checked == 3 and test.headless_only == 1, (test.checked, test.headless_only)
    assert test.test == "test_x"
    assert not is_comparable("login-as alice\nexpect screen=home/orders")
    # Kotlin string templates: a price's `$` is escaped.
    assert kotlin_string('total=$9.00 "x"') == '"total=\\$9.00 \\"x\\""', kotlin_string('total=$9.00 "x"')
    print("self-test passed")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="exit 1 if the generated files are stale")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    try:
        files, manifest = generate_all()
    except GenerationError as error:
        sys.exit(f"gen_uitests: {error}")
    files[MANIFEST] = json.dumps(manifest, indent=2, sort_keys=True) + "\n"
    stale = [path for path, text in files.items() if not path.exists() or path.read_text() != text]
    existing = set(OUT_DIR.glob("*.kt")) if OUT_DIR.exists() else set()
    orphans = existing - set(files)
    if args.check:
        if stale or orphans:
            names = ", ".join(str(p.relative_to(ROOT)) for p in [*stale, *orphans])
            sys.exit(f"gen_uitests: stale generated files: {names}. Run python3 bench/gen_uitests.py.")
        print("generated UI tests are up to date")
        return
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for path, text in files.items():
        path.write_text(text)
    for path in orphans:
        path.unlink()
    comparable = sum(entry["comparable"] for entry in manifest["scenarios"].values())
    print(f"wrote {len(files) - 1} test files: {comparable} of {len(manifest['scenarios'])} scenarios run in the UI")


if __name__ == "__main__":
    main()
