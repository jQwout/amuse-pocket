#!/usr/bin/env python3
"""
Automated criteria checks for AI Open Pocket (see orchestrator/CRITERIA.md).

The orchestrator runs this every cycle, implementers run it before they finish, the
verifier runs it on every branch. The IDs in the output are the IDs of CRITERIA.md,
so a line here is an answer about a criterion, not about a file.

The source is ASCII-only on purpose: the consoles on this machine default to cp1251,
and a service script with Cyrillic in it once failed to parse on an unrelated line
(see the project memory "PowerShell ANSI script gotcha"). Cyrillic is matched through
a character range built from code points.

Usage:
  python tools/check.py           static checks only (seconds)
  python tools/check.py --fast    + :app:compileDebugKotlin (minutes)
  python tools/check.py --full    + :app:assembleDebug, :app:testDebugUnitTest with the
                                  Roborazzi baselines, + every key pattern over the APK
  python tools/check.py --json path.json    also write the result as JSON

Exit code 1 if any FAIL. WARN never fails the run: it marks what a human, the verifier
or the device-tester has to look at, because a static scan cannot decide it.
"""

import argparse
import collections
import hashlib
import json
import io
import os
import re
import subprocess
import sys
import time
import traceback
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "app"
MAIN = APP / "src" / "main"
JAVA = MAIN / "java"
ASSETS = MAIN / "assets"
LESSONS = ASSETS / "lessons"
APP_GRADLE = APP / "build.gradle.kts"
TEST_RESULTS = APP / "build" / "test-results" / "testDebugUnitTest"
APK = APP / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"

# The hackathon work window (Participant Guide, read on 2026-10-26; see docs/meta-hackathon-submission.md).
# Judges read the git dates to tell what was built in the window, so R3 and R7 are about these days.
# The bounds are full timestamps, not bare dates: git's approxidate reads a bare "2026-10-27" as a
# moment later than midnight, and today's own commits fell out of the window.
WINDOW_START = "2026-10-27T00:00:00"
WINDOW_END = "2026-11-05T00:00:00"
WINDOW = "2026-10-27..2026-11-04"

# The lessons the demo video walks through (docs/meta-hackathon-submission.md, section 2). They must
# exist in English; the rest of the course stays Russian on purpose, and that is said in the app.
DEMO_PATH_LESSONS = ["meta_models", "how_model_sees", "agent_blueprint"]

CYRILLIC = re.compile("[" + chr(0x0400) + "-" + chr(0x04FF) + "]")

TEXT_SUFFIXES = {".kt", ".kts", ".java", ".json", ".xml", ".py", ".md", ".yaml", ".yml",
                 ".properties", ".pro", ".toml", ".txt", ".example", ".gradle"}

# ---------------------------------------------------------------- report


class Report:
    def __init__(self):
        self.items = []

    def add(self, cid, status, title, details=None):
        self.items.append({"id": cid, "status": status, "title": title, "details": details or []})

    def counts(self):
        out = {}
        for item in self.items:
            out[item["status"]] = out.get(item["status"], 0) + 1
        return out


def rel(path):
    try:
        return str(Path(path).resolve().relative_to(ROOT)).replace("\\", "/")
    except ValueError:
        return str(path)


def read(path):
    with io.open(str(path), encoding="utf-8", errors="replace") as f:
        return f.read()


def git(*args, **kwargs):
    # core.quotepath=false keeps non-ASCII paths readable instead of \NNN escapes.
    cmd = ["git", "-c", "core.quotepath=false"] + list(args)
    proc = subprocess.run(cmd, cwd=str(ROOT), capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=kwargs.get("timeout", 120))
    return proc.returncode, proc.stdout or "", proc.stderr or ""


def tracked_files():
    code, out, _ = git("ls-files")
    if code != 0:
        return []
    return [line.strip() for line in out.splitlines() if line.strip()]


def gradle(tasks, timeout):
    # gradlew.bat on Windows: ./gradlew is a shell script and needs a POSIX shell, which the
    # orchestrator's own subprocess does not get.
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    cmd = [str(wrapper), "--console=plain"] + list(tasks)
    try:
        proc = subprocess.run(cmd, cwd=str(ROOT), capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=timeout)
    except subprocess.TimeoutExpired:
        return None, "Gradle did not finish in %d s" % timeout
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def gradle_errors(output):
    lines = [l.rstrip() for l in output.splitlines()
             if re.match(r"^\s*(e:|error:|FAILURE:|\* What went wrong|Caused by)", l)]
    return lines[:15] or [l.rstrip() for l in output.splitlines()[-10:]]


# ---------------------------------------------------------------- keys
#
# What a provider key looks like, written down from the formats the providers publish. The length is
# what keeps a pattern quiet on a binary: a debug APK holds megabytes of dex and a resource table, and
# a bare "sk-" or "AIza" turns up there by chance. Where the prefix is short the pattern also refuses
# to start or end inside a longer run of the same characters.
KeyPattern = collections.namedtuple("KeyPattern", "kind needles regex")

KEY_PATTERNS = [
    KeyPattern("OpenRouter", [b"sk-or-v1-"], re.compile(rb"sk-or-v1-[0-9a-f]{64}")),
    KeyPattern("OpenAI", [b"sk-proj-", b"sk-svcacct-", b"sk-admin-"],
               re.compile(rb"sk-(?:proj|svcacct|admin)-[A-Za-z0-9_-]{60,}")),
    KeyPattern("OpenAI (legacy)", [b"sk-"],
               re.compile(rb"(?<![A-Za-z0-9])sk-[A-Za-z0-9]{32,}(?![A-Za-z0-9])")),
    KeyPattern("Anthropic", [b"sk-ant-"], re.compile(rb"sk-ant-[A-Za-z0-9_-]{60,}")),
    KeyPattern("Google", [b"AIza"],
               re.compile(rb"(?<![A-Za-z0-9_-])AIza[A-Za-z0-9_-]{35}(?![A-Za-z0-9_-])")),
    KeyPattern("GitHub", [b"ghp_", b"gho_", b"ghu_", b"ghs_", b"ghr_"],
               re.compile(rb"gh[pousr]_[A-Za-z0-9]{36}")),
    KeyPattern("GitHub (fine-grained)", [b"github_pat_"], re.compile(rb"github_pat_[A-Za-z0-9_]{70,}")),
    KeyPattern("AWS", [b"AKIA", b"ASIA"],
               re.compile(rb"(?<![A-Z0-9])(?:AKIA|ASIA)[A-Z0-9]{16}(?![A-Z0-9])")),
    KeyPattern("NVIDIA NIM", [b"nvapi-"], re.compile(rb"nvapi-[A-Za-z0-9_-]{40,}")),
    KeyPattern("Stripe", [b"sk_live_", b"rk_live_"], re.compile(rb"[sr]k_live_[A-Za-z0-9]{24,}")),
    KeyPattern("Slack", [b"xoxb-", b"xoxp-"], re.compile(rb"xox[bp]-[0-9A-Za-z-]{20,}")),
]


def key_mask(value):
    # What the report may print of a key: the provider's prefix and stars, never the key.
    return value[:8].decode("ascii", errors="replace") + "*" * 8


def key_fingerprint(value):
    # Names a key without giving it away: a SHA-256 prefix cannot be turned back into a key. It is what
    # lets T4a tell the key .env bundles on purpose from any other key in the artifact.
    if not isinstance(value, bytes):
        value = value.encode("utf-8", errors="replace")
    return hashlib.sha256(value).hexdigest()[:12]


def env_key_fingerprints():
    """The keys of the local .env, by fingerprint. NO build bundles a key any more (the owner's decision
    of Oct 30: the person enters their own Meta key in the app), so these fingerprints excuse nothing.
    They are only used to NAME a leak: when a key found in the APK is one of this machine's .env keys,
    the report says which variable it came from, which is the first thing the dev needs to know.
    .env is gitignored and may be absent (a fresh clone, CI) -- then a finding is simply anonymous."""
    path = ROOT / ".env"
    if not path.exists():
        return {}
    out = {}
    for line in read(path).splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, value = line.split("=", 1)
        value = value.strip().strip('"').strip("'")
        if value:
            out[key_fingerprint(value)] = name.strip()
    return out


def find_keys(data):
    """Every distinct (kind, value) in the bytes. A needle check first: `in` runs over megabytes at
    memory speed, a regex does not."""
    found = {}
    for pattern in KEY_PATTERNS:
        if not any(needle in data for needle in pattern.needles):
            continue
        for m in pattern.regex.finditer(data):
            found.setdefault((pattern.kind, m.group(0)), None)
    return list(found)


# ---------------------------------------------------------------- static checks


def check_secrets(report):
    """T4: no live key in anything git tracks, .env is not tracked, and nothing can carry a key into
    BuildConfig (no secrets plugin, no key-like buildConfigField)."""
    details = []
    for path in tracked_files():
        full = ROOT / path
        if full.suffix.lower() not in TEXT_SUFFIXES or not full.exists():
            continue
        try:
            data = full.read_bytes()
        except OSError as e:
            details.append("%s: cannot be read (%s)" % (path, e))
            continue
        for kind, value in find_keys(data):
            line = data.count(b"\n", 0, data.find(value)) + 1
            details.append("%s:%d %s key %s" % (path, line, kind, key_mask(value)))

    if (ROOT / ".env").exists():
        code, out, _ = git("ls-files", "--error-unmatch", ".env")
        if code == 0:
            details.append(".env is tracked by git: it carries the live keys of this machine")

    apks = [p for p in tracked_files() if p.lower().endswith((".apk", ".aab"))]
    if apks:
        # A build artifact in the history is why the repository could not be opened: a debug APK
        # carries .env through BuildConfig (docs/meta-hackathon-submission.md, section 4).
        details.append("build artifacts are tracked: " + ", ".join(apks[:5]))

    # Since Oct 30 no key is bundled on purpose, so the old "release guard is wired" test is replaced by
    # its cause: nothing may be able to copy a secret into BuildConfig. The Secrets Gradle Plugin copied
    # every .env line there, and BuildConfig travels inside the artifact.
    gradle_text = read(APP_GRADLE) if APP_GRADLE.exists() else ""
    code_lines = [l for l in gradle_text.splitlines() if not l.strip().startswith("//")]
    code = "\n".join(code_lines)
    if re.search(r"libs\.plugins\.secrets|secrets-gradle-plugin|\bsecrets\s*\{", code):
        details.append("app/build.gradle.kts applies the Secrets Gradle Plugin: it copies .env into "
                       "BuildConfig, i.e. into the artifact; the person enters their own key instead")
    for m in re.finditer(r"buildConfigField\s*\(([^)]*)\)", code):
        if re.search(r"KEY|TOKEN|SECRET|PASSWORD", m.group(1), re.I):
            details.append("app/build.gradle.kts has a key-like buildConfigField: " + m.group(0)[:80])
    if "checkReleaseSecrets" in code:
        details.append("checkReleaseSecrets is back in app/build.gradle.kts: it guards a path "
                       "(.env into BuildConfig) that must not exist; remove the path, not the guard")

    if details:
        report.add("T4", "FAIL", "Live keys, build artifacts, or a way to bundle keys", details[:15])
    else:
        report.add("T4", "PASS", "No provider keys in tracked files; .env untracked; nothing can copy a "
                   "key into BuildConfig")


def check_apk_keys(report):
    """T4a: the artifact is what a judge can unpack, so the keys are looked for inside it, not in the
    sources that produced it.

    NO key is expected: since Oct 30 nothing is bundled, debug included, because the person enters
    their own Meta key in the app. Every key found is a FAIL -- one copied from .env, one from
    another provider, one hardcoded in the sources, a stale one. That is not a hypothetical: a
    committed debug APK with a Google key from an earlier era is exactly why this repository could not
    simply be made public (docs/meta-hackathon-submission.md, section 4).

    A key that matches this machine's .env (by fingerprint, never by value) is still a FAIL; the
    report only adds which .env variable it was, so the dev can find the path that carried it."""
    if not APK.exists():
        report.add("T4a", "FAIL", "No APK after a successful build, so nothing was scanned", [rel(APK)])
        return
    from_env = env_key_fingerprints()
    found, scanned = {}, 0
    try:
        with zipfile.ZipFile(str(APK)) as zf:
            for info in zf.infolist():
                if info.file_size > 64 * 1024 * 1024:
                    continue
                data = zf.read(info)
                scanned += 1
                for kind, value in find_keys(data):
                    # The .env variable name is a hint about the source of the leak, not an excuse.
                    name = from_env.get(key_fingerprint(value))
                    shown = key_mask(value) + (" (the value of %s in .env)" % name if name else "")
                    found.setdefault((info.filename, kind, shown), None)
    except (zipfile.BadZipFile, OSError) as e:
        report.add("T4a", "FAIL", "The APK could not be read, so it was not scanned", ["%s: %s" % (rel(APK), e)])
        return

    if not scanned:
        report.add("T4a", "FAIL", "The APK holds no entries, so the scan proves nothing", [rel(APK)])
    elif found:
        report.add("T4a", "FAIL", "Keys inside the debug APK, which must carry none",
                   ["%s: %s %s" % (f, k, v) for f, k, v in sorted(found)][:15]
                   + ["this APK carries a live key: do not commit it or hand it to anyone"])
    else:
        report.add("T4a", "PASS", "No key in any of the %d entries of the debug APK" % scanned)


def check_traxer(report):
    """T3: traxer is the project's own rule (CLAUDE.md), so the orchestrator runs it, not just a human."""
    try:
        proc = subprocess.run(["traxer", "check"], cwd=str(ROOT), capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=300)
    except OSError as e:
        report.add("T3", "FAIL", "traxer could not be started, so traceability is unchecked", ["%s" % e])
        return
    except subprocess.TimeoutExpired:
        report.add("T3", "FAIL", "traxer check did not answer in 300 s")
        return
    out = ((proc.stdout or "") + (proc.stderr or "")).strip()
    tail = [l.strip() for l in out.splitlines() if l.strip()][-12:]
    m = re.search(r"(\d+)\s+error\(s\),\s*(\d+)\s+warning\(s\)", out)
    if proc.returncode != 0 or (m and int(m.group(1))):
        report.add("T3", "FAIL", "traxer check reports drift between code and traceability.yaml", tail)
    elif not m:
        # No summary line means the output was not what this check knows how to read: silence must
        # not read as "clean".
        report.add("T3", "FAIL", "traxer check printed no summary line, so its verdict is unknown", tail)
    elif int(m.group(2)):
        report.add("T3", "WARN", "traxer check is green but warns (%s warning(s))" % m.group(2), tail)
    else:
        report.add("T3", "PASS", "traxer check: no drift")


# tr(ru, en) is how the app carries both languages (see i18n/AppLanguage.kt). Only the single-line
# literal pair can be read statically; a call built from variables or split over lines is not counted,
# and the report says how many pairs were actually read so the number is not mistaken for coverage.
TR_PAIR = re.compile(r'\btr\(\s*"((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"\s*[,)]')

# The two places where Russian on the English side is the point, not a miss. The key is the file AND the
# exact string, not the file alone: another Russian string in the same file still fails. The value is why
# it is allowed. Adding a line here is a decision, so it is written down like one.
# The escapes keep this file ASCII (see the module docstring); the comment says what the string is.
TR_EN_EXEMPT = {
    # "na angliyskom (in English)"
    ("app/src/main/java/com/aiopenpocket/agent/core/profile/ExtractUserProfileUseCase.kt",
     "\u043d\u0430 \u0430\u043d\u0433\u043b\u0438\u0439\u0441\u043a\u043e\u043c (in English)"):
        "not screen text: a fragment of the Russian extraction prompt that tells the model which language "
        "to answer in",
    # "Vyberi yazyk" ("Choose your language")
    ("app/src/main/java/com/aiopenpocket/ui/onboarding/components/LanguageSelectionStep.kt",
     "\u0412\u044b\u0431\u0435\u0440\u0438 \u044f\u0437\u044b\u043a"):
        "the language picker shows the OTHER language under the heading on purpose, so a Russian speaker "
        "reading the English screen still recognises the step",
}


def check_english_demo_path(report):
    """T6: the English side of a tr() pair must actually be English."""
    pairs, bad, used = 0, [], set()
    for path in sorted(JAVA.rglob("*.kt")):
        text = read(path)
        for m in TR_PAIR.finditer(text):
            pairs += 1
            en, where = m.group(2), (rel(path), m.group(2))
            line = text.count("\n", 0, m.start()) + 1
            if where in TR_EN_EXEMPT:
                used.add(where)
            elif CYRILLIC.search(en):
                bad.append("%s:%d English side is Russian: %s" % (rel(path), line, en[:60]))
            elif not en.strip() and m.group(1).strip():
                bad.append("%s:%d English side is empty while the Russian one is not" % (rel(path), line))
    # An exemption whose string is gone is an exemption that now covers nothing and would quietly let
    # the next edit of that file through: it has to be removed, so it is reported.
    stale = ["%s: %s" % (p, s[:40]) for p, s in sorted(set(TR_EN_EXEMPT) - used)]
    if not pairs:
        report.add("T6", "FAIL", "No tr(ru, en) pair found at all, so nothing was checked")
    elif bad:
        report.add("T6", "FAIL", "Russian text on the English side of tr() in %d of %d pairs" % (len(bad), pairs),
                   bad[:15])
    elif stale:
        report.add("T6", "WARN", "Every tr() pair is clean, but %d exemption(s) in check.py match nothing"
                   % len(stale), stale)
    else:
        report.add("T6", "PASS", "All %d literal tr(ru, en) pairs are English, bar %d named exemptions"
                   % (pairs, len(TR_EN_EXEMPT)))


# ---------------------------------------------------------------- T6c: Russian that never went through tr()
# @trace screen-text-check-7c6d09
#
# T6 only reads the English side of a tr() pair, so a string that skipped tr() altogether is invisible to
# it: the check was green (650 pairs, all English) while whole screens of the demo path were Russian under
# an English interface. T6c reads the other half: a Russian literal standing where Compose draws it.
#
# What counts as "where Compose draws it" is a list, not a guess:
#   - the named arguments text, contentDescription, label, placeholder, title, supportingText, headline
#     of any call;
#   - the first positional argument of Text(, Button(, TextButton(, OutlinedButton(.
# Inside such an argument a literal counts when it is the value itself or one of its branches
# ("..." , if (c) "..." else "...", x ?: "...", "..." + y, a string template). A literal inside a nested
# call does not: tr("ru", "en") is exactly that call, and its Russian first argument is the normal Russian
# side of the pair. Comments and KDoc are not code, so the lexer drops them.
#
# What this does NOT see, said here so a green line is not read as more than it is: a Russian string held
# in a val/list/data table and passed on by name (Text(LABEL)), the body of an ordinary lambda, a call
# other than tr() that builds the text (String.format("...", n)). It is a net for the direct form, which
# is how the 184 literals of 2026-10-27 were written; the device-tester on an English interface is the
# net for the rest.

SCREEN_ARG_NAMES = ("text", "contentDescription", "label", "placeholder", "title", "supportingText",
                    "headline")
SCREEN_CALLS = ("Text", "Button", "TextButton", "OutlinedButton")

# *Previews.kt hold the @Preview fixtures: sample chats, sample agents, sample plans. They exist for
# Android Studio and the Roborazzi baselines and are never composed for a user, so their Russian sample
# data is not screen text. Skipped by name, here, not silently.
SCREEN_RU_SKIP_SUFFIX = "Previews.kt"

# Files where Russian screen text is allowed to stay, by path, with the reason. Not a folder, not a
# pattern: one line per file, so adding a file is a decision that shows up in review.
SCREEN_RU_EXEMPT = {
}

# A scene of a lesson that exists only in Russian is listed with the lesson id instead of a free reason:
# the exemption is tied to a fact the check can read, assets/lessons/en/<id>.json. The day that file
# appears the exemption stops applying on its own and the scene's Russian turns the check red -- an
# English lesson with a Russian scene is the very bug this check was written for.
_SCENES = "app/src/main/java/com/aiopenpocket/ui/chat/components/"
SCREEN_RU_LESSON_ONLY = {
    _SCENES + "SceneChainRouter.kt": "chaining_and_routing",
    _SCENES + "SceneConfidentWrong.kt": "hallucinations",
    _SCENES + "SceneContextBudget.kt": "context_engineering",
    _SCENES + "SceneContextWindow.kt": "memory_and_context",
    _SCENES + "SceneDataFlow.kt": "privacy_and_reliability",
    _SCENES + "SceneDraftCritique.kt": "reflection",
    _SCENES + "SceneGoalLoop.kt": "goal_mode",
    _SCENES + "SceneImageReasoning.kt": "muse_image_draws",
    _SCENES + "SceneOpenWeights.kt": "open_weights_and_api",
    _SCENES + "ScenePlanGate.kt": "plan_mode",
    _SCENES + "SceneRag.kt": "rag",
    _SCENES + "SceneReactLoop.kt": "react_loop",
    _SCENES + "SceneRiskGate.kt": "tool_approval_and_limits",
    _SCENES + "SceneSkillLevels.kt": "agent_skills",
    _SCENES + "SceneStepEvidence.kt": "structured_plans",
    _SCENES + "SceneThinkingModes.kt": "muse_spark_modes",
    _SCENES + "SceneToolCall.kt": "agent_tools",
    _SCENES + "SceneWorkflowVsAgent.kt": "workflow_and_agent",
}

# After one of these a "(" is a plain parenthesis, not a call; after anything else that is an identifier
# it is a call, and the arguments of a call are not the value of the expression.
KT_KEYWORDS_BEFORE_VALUE = {"else", "return", "in", "is", "as", "to", "and", "or", "yield"}
# The "(" after these holds a condition: compared against, never shown.
KT_CONDITION_KEYWORDS = {"if", "when", "while", "for"}
# Lambdas whose last expression is the value, so a string in them is the screen text itself.
KT_VALUE_LAMBDAS = {"ifBlank", "ifEmpty", "let", "run", "takeIf", "takeUnless"}

KT_PARAMETER_MODIFIERS = {"val", "var", "private", "internal", "public", "protected", "override",
                          "vararg", "noinline", "crossinline"}

KtToken = collections.namedtuple("KtToken", "kind value pos")

_KT_ID = re.compile(r"[A-Za-z_][A-Za-z_0-9]*|`[^`\n]*`")
_KT_NUM = re.compile(r"[0-9][0-9_]*(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?[fFLuU]*")
_KT_OP = re.compile(r"===|!==|==|!=|<=|>=|\+=|-=|\*=|/=|%=|->|\?:|::|\.\.<|\.\.|&&|\|\||\S")
_KT_CHAR = re.compile(r"'(?:\\u[0-9a-fA-F]{4}|\\.|[^\\'\n])'")


def kotlin_tokens(src):
    """Just enough of a Kotlin lexer for T6c: identifiers, operators and string literals with their
    literal text, with comments dropped. A template expression (${...}) is lexed as code between a
    tpl_open and a tpl_close token, so a string nested in one is still found."""
    toks, n = [], len(src)

    def lex_string(i, raw):
        quote = '"""' if raw else '"'
        start, i = i, i + len(quote)
        slot = len(toks)
        toks.append(None)
        parts = []
        while i < n:
            c = src[i]
            if raw and src.startswith('"""', i):
                j = i
                while j < n and src[j] == '"':
                    j += 1
                parts.append('"' * (j - i - 3))
                i = j
                break
            if not raw and c == '"':
                i += 1
                break
            if not raw and c == "\n":
                break  # an unterminated string: stop here rather than swallow the rest of the file
            if not raw and c == "\\":
                parts.append(src[i:i + 2])
                i += 2
                continue
            if c == "$" and src.startswith("{", i + 1):
                toks.append(KtToken("tpl_open", "${", i))
                i = lex(i + 2, True)
                toks.append(KtToken("tpl_close", "}", i))
                continue
            parts.append(c)
            i += 1
        toks[slot] = KtToken("str", "".join(parts), start)
        return i

    def lex(i, in_template):
        depth = 0
        while i < n:
            c = src[i]
            if c.isspace():
                i += 1
            elif src.startswith("//", i):
                j = src.find("\n", i)
                i = n if j < 0 else j
            elif src.startswith("/*", i):
                level, i = 1, i + 2
                while i < n and level:
                    if src.startswith("/*", i):
                        level, i = level + 1, i + 2
                    elif src.startswith("*/", i):
                        level, i = level - 1, i + 2
                    else:
                        i += 1
            elif src.startswith('"""', i):
                i = lex_string(i, True)
            elif c == '"':
                i = lex_string(i, False)
            elif c == "'":
                m = _KT_CHAR.match(src, i)
                i = m.end() if m else i + 1
            else:
                if in_template and c == "}":
                    if depth == 0:
                        return i + 1
                    depth -= 1
                elif in_template and c == "{":
                    depth += 1
                for kind, rx in (("id", _KT_ID), ("num", _KT_NUM), ("op", _KT_OP)):
                    m = rx.match(src, i)
                    if m:
                        toks.append(KtToken(kind, m.group(0), i))
                        i = m.end()
                        break
        return i

    lex(0, False)
    return toks


def _is_op(tok, *values):
    return tok is not None and tok.kind == "op" and tok.value in values


def _bracket_pairs(toks):
    """open index -> close index for ( [ { and ${ }. An unbalanced file does not crash the scan: a
    closer with nothing open is ignored."""
    pairs, stack = {}, []
    for k, t in enumerate(toks):
        if t.kind == "tpl_open" or _is_op(t, "(", "[", "{"):
            stack.append(k)
        elif t.kind == "tpl_close" or _is_op(t, ")", "]", "}"):
            if stack:
                pairs[stack.pop()] = k
    return pairs


def _paren_owner(toks, pairs, close_index):
    """The identifier before the "(" that this ")" closes: `if` for `if (c) { ... }`."""
    for open_index, close in pairs.items():
        if close == close_index and open_index > 0 and toks[open_index - 1].kind == "id":
            return toks[open_index - 1].value
    return None


def _value_strings(toks, pairs, a, b, enter_blocks=False):
    """The Russian string tokens in toks[a:b] that are the value of that expression (see the T6c note
    above for what is and is not the value).

    enter_blocks=True walks into every `{ ... }` instead of only the branches of an if/when: it is for
    T6d, which reads a whole function body, where any branch of any block can be the result."""
    out, k = [], a
    while k < b:
        t = toks[k]
        prev = toks[k - 1] if k > a else None
        if t.kind == "str":
            following = toks[k + 1] if k + 1 < len(toks) else None
            # `"da" -> ...` in a when() is a condition that is compared against, not something shown
            if CYRILLIC.search(t.value) and not _is_op(following, "->"):
                out.append(t)
        elif _is_op(t, "(") and k in pairs:
            if prev is not None and prev.kind == "id" and prev.value in KT_CONDITION_KEYWORDS:
                k = pairs[k]
            elif prev is not None and ((prev.kind == "id" and prev.value not in KT_KEYWORDS_BEFORE_VALUE)
                                       or _is_op(prev, ")", "]")):
                k = pairs[k]  # a call; tr("ru", "en") lands here, which is the point
        elif _is_op(t, "[") and k in pairs:
            k = pairs[k]
        elif _is_op(t, "{") and k in pairs:
            if not (enter_blocks or _is_value_block(toks, pairs, k, prev)):
                k = pairs[k]  # a lambda or a block of statements
        k += 1
    return out


def _is_value_block(toks, pairs, k, prev):
    """A `{` at index k that opens a branch whose last expression is the value: `else { ... }`,
    `-> { ... }`, `if (c) { ... }`, `x.ifBlank { ... }`."""
    return prev is not None and (
        (prev.kind == "id" and (prev.value == "else" or prev.value in KT_VALUE_LAMBDAS))
        or _is_op(prev, "->")
        or (_is_op(prev, ")") and _paren_owner(toks, pairs, k - 1) in KT_CONDITION_KEYWORDS))


def _call_args(toks, pairs, open_index, close_index):
    """(start, end) token indices of each argument of the call whose "(" is at open_index: split at the
    commas that sit directly in this call, nested brackets stepped over whole."""
    args, start, k = [], open_index + 1, open_index + 1
    while k < close_index:
        if toks[k].kind == "tpl_open" or _is_op(toks[k], "(", "[", "{"):
            k = pairs.get(k, k)
        elif _is_op(toks[k], ","):
            args.append((start, k))
            start = k + 1
        k += 1
    if start < close_index:
        args.append((start, close_index))
    return args


def _preview_spans(toks, pairs):
    """(start, end) source offsets of the body of every function annotated @Preview*: sample data for
    Android Studio and the Roborazzi baselines, never composed for a user. It is the same reason as for
    the *Previews.kt files, for the previews that sit next to their screen."""
    spans = []
    for k, t in enumerate(toks):
        if not (_is_op(t, "@") and k + 1 < len(toks) and toks[k + 1].kind == "id"
                and toks[k + 1].value.startswith("Preview")):
            continue
        fun = next((j for j in range(k + 2, min(k + 40, len(toks)))
                    if toks[j].kind == "id" and toks[j].value == "fun"), None)
        if fun is None:
            continue
        params = next((j for j in range(fun, min(fun + 6, len(toks))) if _is_op(toks[j], "(")), None)
        if params is None or params not in pairs:
            continue
        body = next((j for j in range(pairs[params], min(pairs[params] + 8, len(toks)))
                     if _is_op(toks[j], "{")), None)
        if body is not None and body in pairs:
            spans.append((toks[body].pos, toks[pairs[body]].pos))
    return spans


def screen_cyrillic_literals(src):
    """(offset, text) of every Russian literal standing in a screen position in this Kotlin source."""
    toks = kotlin_tokens(src)
    pairs = _bracket_pairs(toks)
    previews = _preview_spans(toks, pairs)
    hits = {}
    for open_index, close_index in pairs.items():
        if not _is_op(toks[open_index], "(") or open_index == 0:
            continue
        callee = toks[open_index - 1]
        if callee.kind != "id" or callee.value in KT_CONDITION_KEYWORDS:
            continue
        for position, (a, b) in enumerate(_call_args(toks, pairs, open_index, close_index)):
            while a < b and toks[a].kind == "id" and toks[a].value in KT_PARAMETER_MODIFIERS:
                a += 1  # `val`, `private` ... in front of a constructor parameter
            if b - a >= 2 and toks[a].kind == "id" and _is_op(toks[a + 1], "="):
                if toks[a].value in SCREEN_ARG_NAMES:
                    for s in _value_strings(toks, pairs, a + 2, b):
                        hits[s.pos] = s.value
            elif b - a >= 4 and toks[a].kind == "id" and _is_op(toks[a + 1], ":"):
                # a declaration `title: String = "..."`: the default of a text parameter is shown the
                # moment a caller leaves it out
                eq, k = None, a + 2
                while k < b:
                    if toks[k].kind == "tpl_open" or _is_op(toks[k], "(", "[", "{"):
                        k = pairs.get(k, k)
                    elif _is_op(toks[k], "="):
                        eq = k
                        break
                    k += 1
                if eq is not None and toks[a].value in SCREEN_ARG_NAMES:
                    for s in _value_strings(toks, pairs, eq + 1, b):
                        hits[s.pos] = s.value
            elif position == 0 and callee.value in SCREEN_CALLS:
                for s in _value_strings(toks, pairs, a, b):
                    hits[s.pos] = s.value
    return [(pos, hits[pos]) for pos in sorted(hits)
            if not any(start <= pos <= end for start, end in previews)]


def scene_function_of_lesson(lesson_id):
    """The Compose function that draws the animation step of a lesson, found the way the app finds it:
    the scene id in the lesson's JSON, then the dispatcher in LessonAnimationScenes.kt."""
    lesson = LESSONS / (lesson_id + ".json")
    dispatcher = JAVA / "com" / "aiopenpocket" / "ui" / "chat" / "components" / "LessonAnimationScenes.kt"
    if not lesson.exists() or not dispatcher.exists():
        return None
    try:
        steps = json.loads(read(lesson)).get("steps", [])
    except ValueError:
        return None
    scene = next((s.get("scene") for s in steps if s.get("type") == "animation"), None)
    m = re.search(r'"%s"\s*->\s*(\w+)\(' % re.escape(str(scene)), read(dispatcher))
    return m.group(1) if m else None


def lesson_only_exemption(path, lesson_id):
    """(reason, problem) of a scene-file exemption. problem is None while the exemption holds. It holds
    only on three facts the check reads itself: the lesson exists, it has no English copy, and this very
    file draws that lesson's scene. Lose any of them and the exemption is void."""
    reason = ("lesson %s exists only in Russian; the exemption ends when assets/lessons/en/%s.json appears"
              % (lesson_id, lesson_id))
    if not (LESSONS / (lesson_id + ".json")).exists():
        return reason, "there is no lesson %s, so the exemption names nothing" % lesson_id
    if (LESSONS / "en" / (lesson_id + ".json")).exists():
        return reason, ("lesson %s now has an English copy (assets/lessons/en/%s.json): its scene has to "
                        "be English too" % (lesson_id, lesson_id))
    function = scene_function_of_lesson(lesson_id)
    full = ROOT / path
    if not function or not full.exists() or not re.search(r"\bfun\s+%s\b" % re.escape(function), read(full)):
        return reason, "this file does not draw the scene of lesson %s (%s)" % (lesson_id, function or "?")
    return reason, None


def check_screen_russian(report):
    """T6c: no Russian literal where Compose draws it, unless the file is named in the exemptions."""
    exemptions = {path: (why, None) for path, why in SCREEN_RU_EXEMPT.items()}
    for path, lesson_id in SCREEN_RU_LESSON_ONLY.items():
        exemptions[path] = lesson_only_exemption(path, lesson_id)

    scanned, covered, bad, voided, used = 0, 0, [], {}, set()
    for path in sorted(JAVA.rglob("*.kt")):
        if path.name.endswith(SCREEN_RU_SKIP_SUFFIX):
            continue
        scanned += 1
        text = read(path)
        hits = screen_cyrillic_literals(text)
        if not hits:
            continue
        name = rel(path)
        why, problem = exemptions.get(name, (None, "no exemption"))
        if name in exemptions:
            used.add(name)
        if why and problem is None:
            covered += len(hits)
            continue
        if problem and name in exemptions:
            voided[name] = problem
        for pos, literal in hits:
            bad.append("%s:%d %s" % (name, text.count("\n", 0, pos) + 1, literal.strip()[:60]))
    stale = ["%s: no Russian screen literal left, remove the exemption" % p
             for p in sorted(set(exemptions) - used)]

    if not scanned:
        report.add("T6c", "FAIL", "No Kotlin file found, so nothing was checked")
    elif bad:
        details = ["%s: exemption void, %s" % (p, why) for p, why in sorted(voided.items())] + bad
        report.add("T6c", "FAIL", "Russian text that never went through tr() in %d screen position(s): "
                   "an English interface shows it as it is" % len(bad), details[:15])
    elif stale:
        report.add("T6c", "WARN", "No loose Russian on screen, but %d exemption(s) in check.py match nothing"
                   % len(stale), stale)
    else:
        report.add("T6c", "PASS", "No Russian literal in a screen position outside tr() in %d files; "
                   "%d literals sit in %d named exemptions" % (scanned, covered, len(used)))


# ---------------------------------------------------------------- T6d: Russian one step from the person
# @trace screen-text-check-7c6d09
#
# The same blind spot, a third time. T6 reads the English side of a tr() pair, T6b reads the lesson copies,
# T6c reads the screen position -- and all three measure the place where a string is WRITTEN. The turn's
# error text is not written on a screen: userFacingLlmError (agent/core/llm/OpenAiCompatClient.kt) returns
# bare Russian literals, its caller puts the result into StreamEvent.Error(...), and the chat draws what
# arrived through a variable (ui/chat/ChatScreen.kt: `text = state.error!!`). Three green checks, and an
# English lesson still showed a Russian red banner on the emulator. T6d measures the other end: the place
# where a string is SHOWN.
#
# A sink is a call that certainly hands its string to a person. Every one is named in USER_TEXT_SINKS with
# the reason; this is not "anything that looks like a message", and a string meant for the model is not one.
#
# Two things are read at a sink:
#   1. a bare Russian literal standing as that argument (`setContentTitle("...")`);
#   2. ONE step of indirection: when the argument is a call by bare name to a function defined in the SAME
#      file, the Russian literals in that function's body count as its results.
# One step, not arbitrary depth: it covers the real case and it is honest to implement with a lexer rather
# than a Kotlin front end. Inside the inspected helper the scan over-reports on purpose -- every Russian
# literal of that body that is not inside a nested call counts, result or not -- because a function that
# exists to produce the text a person reads has no business holding a bare Russian literal at all.
#
# WHAT T6d DOES NOT SEE. Written down here so that a green line is never read as more than it is; a check
# whose blind spots are unsaid is the reason this one had to be written at all.
#   - two steps of indirection: a sink fed by f() whose own text comes from g();
#   - a helper in ANOTHER file -- the lexer reads one file at a time and resolves `fun` by bare name only;
#   - a string that reaches the sink through a variable, a parameter, a property or a constant
#     (`showAgentNotification(..., message = preview)`, `val TITLE = "..."`, `copy(error = "...")`);
#   - a string built by a call the scan steps over on purpose (`String.format("...", n)`, `buildString {}`,
#     a resource lookup), or glued from pieces that each live somewhere else;
#   - a qualified call (`helper.text()`) or a function reference (`::userFacingLlmError`);
#   - an overload: the FIRST `fun` of that name in the file is the body that gets read;
#   - a Russian DEFAULT value of a sink's own parameter (`fun f(title: String = "...")`): T6d reads
#     calls, and a declaration is not one; T6c reads the defaults of `title` and `text`, nothing else;
#   - any sink that is not in the table below -- a new way of putting text in front of a person stays
#     invisible until someone adds it there.
# The device-tester walking an English interface stays the net for all of that.

# One line per sink, with WHY it is one. args = the (position, name) of each argument that holds the text;
# name is None where the argument has none. Position is counted as written, so it only decides an argument
# that was passed positionally.
UserTextSink = collections.namedtuple("UserTextSink", "args why")

USER_TEXT_SINKS = {
    # ui/chat/ChatScreen.kt draws it as the red banner (`text = state.error!!`), and a background turn
    # pushes it (TurnManager -> TurnNotifier.turnFinished).
    "StreamEvent.Error": UserTextSink(((0, "message"),), "the chat's error banner"),
    # agent/core/notification/NotificationHelper.kt passes both straight to setContentTitle/setContentText.
    "showAgentNotification": UserTextSink(((1, "title"), (2, "message")), "a notification's title and body"),
    # NotificationCompat.Builder: the two lines the system shade shows.
    "setContentTitle": UserTextSink(((0, None),), "a notification's title"),
    "setContentText": UserTextSink(((0, None),), "a notification's body"),
    # agent/tools/ExternalActivityLauncher.kt hands both to showAgentNotification unchanged.
    "launchExternalActivity": UserTextSink(((3, "notificationTitle"), (4, "notificationText")),
                                           "the notification shown when the app is in the background"),
    # agent/loop/TurnPorts.kt -> AgentTurnNotifier: the title and the body of the "turn finished" push.
    "turnFinished": UserTextSink(((1, "conversationTitle"), (2, "preview")), "the turn's push"),
}

# Files where a Russian literal may stand at a user-text sink, by path, with the reason. One line per file,
# like SCREEN_RU_EXEMPT: adding one is a decision that shows up in review. A sink that turns out NOT to
# reach a person belongs out of USER_TEXT_SINKS, not in here.
USER_TEXT_SINK_EXEMPT = {
}

# `name(` right after one of these opens a declaration's own parameter list, not a call: `fun
# showAgentNotification(...)`, `data class Error(val message: String)`.
KT_DECLARED_BY = {"fun", "class", "object", "interface"}

# An expression body (`fun f() = ...`) ends before one of these: they start the next declaration.
KT_BODY_END = {"fun", "class", "interface", "object", "typealias", "enum", "annotation", "val", "var",
               "override", "private", "internal", "public", "protected", "abstract", "companion",
               "init", "constructor"}


def _value_calls(toks, pairs, a, b):
    """The bare-name calls standing where the value of toks[a:b] is: `f(...)` in `x ?: f(...)`, in each
    branch of an if/when, in each piece of a concatenation. A qualified call (`x.f()`) is not one -- the
    body T6d can go and read is a `fun` of this same file."""
    out, k = [], a
    while k < b:
        t = toks[k]
        prev = toks[k - 1] if k > a else None
        if _is_op(t, "(") and k in pairs:
            if prev is not None and prev.kind == "id" and prev.value in KT_CONDITION_KEYWORDS:
                k = pairs[k]
            elif prev is not None and prev.kind == "id" and prev.value not in KT_KEYWORDS_BEFORE_VALUE:
                if not (k >= 2 and _is_op(toks[k - 2], ".", "::")):
                    out.append(prev.value)
                k = pairs[k]
            elif _is_op(prev, ")", "]"):
                k = pairs[k]
        elif _is_op(t, "[") and k in pairs:
            k = pairs[k]
        elif _is_op(t, "{") and k in pairs:
            if not _is_value_block(toks, pairs, k, prev):
                k = pairs[k]
        k += 1
    return out


def _expression_end(toks, pairs, start):
    """Where the expression body beginning at `start` ends: the closer of an enclosing bracket, the start
    of the next declaration, or the end of the file. Balanced groups are stepped over whole."""
    k, n = start, len(toks)
    while k < n:
        t = toks[k]
        if t.kind == "tpl_open" or _is_op(t, "(", "[", "{"):
            k = pairs.get(k, k)
        elif t.kind == "tpl_close" or _is_op(t, ")", "]", "}", ";"):
            return k
        elif (t.kind == "id" and t.value in KT_BODY_END) or _is_op(t, "@"):
            return k
        k += 1
    return n


def kotlin_local_functions(toks, pairs):
    """name -> (start, end) token indices of the body of every `fun name(...)` written in this file, in
    both shapes: a block body `{ ... }` and an expression body `= ...`. The first definition of a name
    wins (see the stated limits above). A declaration without a body is skipped."""
    out, n = {}, len(toks)
    for k, t in enumerate(toks):
        if not (t.kind == "id" and t.value == "fun"):
            continue
        params = next((j for j in range(k + 1, min(k + 40, n))
                       if _is_op(toks[j], "(") and j in pairs and toks[j - 1].kind == "id"), None)
        if params is None:
            continue
        name, j = toks[params - 1].value, pairs[params] + 1
        limit = min(pairs[params] + 60, n)
        while j < limit:
            if _is_op(toks[j], "{") and j in pairs:
                out.setdefault(name, (j + 1, pairs[j]))
                break
            if _is_op(toks[j], "="):
                out.setdefault(name, (j + 1, _expression_end(toks, pairs, j + 1)))
                break
            # an abstract or interface function: the next declaration starts and no body ever came
            if (toks[j].kind == "id" and toks[j].value in KT_BODY_END) or _is_op(toks[j], "@", "}"):
                break
            j += 1
    return out


def _dotted_callee(toks, idx):
    """The dotted name of the callee whose last identifier is toks[idx]: `StreamEvent.Error` for
    `StreamEvent.Error(`, `setContentTitle` for `...setSmallIcon(x).setContentTitle(`."""
    parts, k = [toks[idx].value], idx - 1
    while k - 1 >= 0 and _is_op(toks[k], ".") and toks[k - 1].kind == "id":
        parts.append(toks[k - 1].value)
        k -= 2
    parts.reverse()
    return ".".join(parts)


def _sink_for(dotted):
    """(key, spec) of the sink this callee is, matched on the dotted suffix: the key `StreamEvent.Error`
    matches `StreamEvent.Error(` but never a bare `Error(`, which could be anything."""
    for key, spec in USER_TEXT_SINKS.items():
        if dotted == key or dotted.endswith("." + key):
            return key, spec
    return None, None


def user_text_sink_literals(src):
    """(offset, literal, why) of every Russian literal this Kotlin source hands to a user-text sink,
    either straight or through one function of this same file."""
    toks = kotlin_tokens(src)
    pairs = _bracket_pairs(toks)
    previews = _preview_spans(toks, pairs)
    bodies = kotlin_local_functions(toks, pairs)
    hits = {}
    for open_index, close_index in sorted(pairs.items()):
        if not _is_op(toks[open_index], "(") or open_index == 0:
            continue
        if toks[open_index - 1].kind != "id":
            continue
        if (open_index >= 2 and toks[open_index - 2].kind == "id"
                and toks[open_index - 2].value in KT_DECLARED_BY):
            continue
        if any(start <= toks[open_index].pos <= end for start, end in previews):
            continue
        sink, spec = _sink_for(_dotted_callee(toks, open_index - 1))
        if spec is None:
            continue
        for position, (a, b) in enumerate(_call_args(toks, pairs, open_index, close_index)):
            arg_name = None
            if b - a >= 2 and toks[a].kind == "id" and _is_op(toks[a + 1], "="):
                arg_name, a = toks[a].value, a + 2
            if not any(arg_name == n if arg_name is not None else position == p for p, n in spec.args):
                continue
            for s in _value_strings(toks, pairs, a, b):
                hits[s.pos] = (s.value, "straight into %s(): %s" % (sink, spec.why))
            for fn in _value_calls(toks, pairs, a, b):
                if fn not in bodies:
                    continue
                start, end = bodies[fn]
                for s in _value_strings(toks, pairs, start, end, enter_blocks=True):
                    hits.setdefault(s.pos, (s.value, "%s() returns it and %s() shows it: %s"
                                            % (fn, sink, spec.why)))
    return [(pos, hits[pos][0], hits[pos][1]) for pos in sorted(hits)]


def check_sink_russian(report):
    """T6d: no Russian literal reaches a user-text sink -- straight, or through one function of the same
    file -- unless the file is named in USER_TEXT_SINK_EXEMPT."""
    scanned, covered, bad, used = 0, 0, [], set()
    for path in sorted(JAVA.rglob("*.kt")):
        # the same reason as in T6c: *Previews.kt is fixture data for Android Studio and Roborazzi
        if path.name.endswith(SCREEN_RU_SKIP_SUFFIX):
            continue
        scanned += 1
        text = read(path)
        hits = user_text_sink_literals(text)
        if not hits:
            continue
        name = rel(path)
        if name in USER_TEXT_SINK_EXEMPT:
            used.add(name)
            covered += len(hits)
            continue
        for pos, literal, why in hits:
            bad.append("%s:%d %s -- %s" % (name, text.count("\n", 0, pos) + 1, literal.strip()[:50], why))
    stale = ["%s: nothing Russian reaches a sink there any more, remove the exemption" % p
             for p in sorted(set(USER_TEXT_SINK_EXEMPT) - used)]

    if not scanned:
        report.add("T6d", "FAIL", "No Kotlin file found, so nothing was checked")
    elif bad:
        report.add("T6d", "FAIL", "Russian text reaching a person through %d sink argument(s): the text is "
                   "shown where it arrives, so an English reader gets it as it is" % len(bad), bad[:15])
    elif stale:
        report.add("T6d", "WARN", "Nothing Russian at a user-text sink, but %d exemption(s) in check.py "
                   "match nothing" % len(stale), stale)
    else:
        report.add("T6d", "PASS", "No Russian literal reaches any of the %d user-text sinks in %d files, "
                   "straight or one call deep; %d literals sit in %d named exemptions"
                   % (len(USER_TEXT_SINKS), scanned, covered, len(used)))


def check_english_lessons(report):
    """T6b: every lesson that HAS an English copy matches its original step for step, and the
    lessons of the demo path have a copy at all.

    Checking only the demo path was the earlier mistake. On 27 Oct 2026 this graded exactly the
    three lessons that were already translated and said nothing about the twenty-seven that were
    not, so it stayed green while an English reader hit Russian at the first lesson of the Path.
    A check whose scope is "the part already done" cannot fail where it matters."""
    details = []
    en_dir = LESSONS / "en"
    translated = sorted(p.name[:-len(".json")] for p in en_dir.glob("*.json")) if en_dir.is_dir() else []
    for name in DEMO_PATH_LESSONS:
        if name not in translated:
            details.append("%s: no English copy, so the demo path falls back to Russian" % name)
    for name in translated:
        ru_path, en_path = LESSONS / (name + ".json"), en_dir / (name + ".json")
        if not ru_path.exists():
            details.append("%s: an English copy with no Russian original" % name)
            continue
        try:
            ru, en = json.loads(read(ru_path)), json.loads(read(en_path))
        except ValueError as e:
            details.append("%s: the JSON cannot be read (%s)" % (name, e))
            continue
        ru_steps, en_steps = ru.get("steps", []), en.get("steps", [])
        if len(ru_steps) != len(en_steps):
            details.append("%s: %d steps in Russian, %d in English" % (name, len(ru_steps), len(en_steps)))
            continue
        for i, (r, e) in enumerate(zip(ru_steps, en_steps)):
            if r.get("type") != e.get("type"):
                details.append("%s: step %d is %r in Russian and %r in English"
                               % (name, i + 1, r.get("type"), e.get("type")))
        for field in ("title", "description"):
            if CYRILLIC.search(str(en.get(field, ""))):
                details.append("%s: the English copy's %s is Russian" % (name, field))
    if details:
        report.add("T6b", "FAIL", "An English lesson does not match its Russian original", details[:15])
    else:
        report.add("T6b", "PASS", "All %d translated lessons mirror their originals step for step; "
                   "the %d demo-path lessons have a copy" % (len(translated), len(DEMO_PATH_LESSONS)))


def check_meta_route(report):
    """R2: Stage 1 of the judging is pass/fail on "Meta Model API and/or Meta's open weights".
    Anything that is not the direct Meta route fails it, so that route has to be the one in the build."""
    details = []
    provider = JAVA / "com" / "aiopenpocket" / "agent" / "core" / "llm" / "LlmProvider.kt"
    catalog = JAVA / "com" / "aiopenpocket" / "agent" / "core" / "llm" / "ModelCatalog.kt"
    meta_models = ASSETS / "config" / "models_meta.json"

    if not provider.exists() or not re.search(r"^\s*META\s*,?\s*$", read(provider), re.M):
        details.append("no META route in LlmProvider.kt")
    if not catalog.exists() or "api.meta.ai" not in read(catalog):
        details.append("api.meta.ai is not the base address in ModelCatalog.kt")
    if not meta_models.exists():
        details.append("%s is missing: the app has no Meta catalog" % rel(meta_models))
    else:
        text = read(meta_models)
        if "muse-" not in text:
            details.append("%s names no muse-* model" % rel(meta_models))
        if "muse-spark" not in text:
            details.append("%s names no muse-spark model, the brain of every agent" % rel(meta_models))

    if details:
        report.add("R2", "FAIL", "The direct Meta Model API route is not wired, so Stage 1 is at risk", details)
    else:
        report.add("R2", "PASS", "Direct Meta Model API route wired: META provider, api.meta.ai, muse-spark catalog")


def check_window_commits(report):
    """R3: judges tell what was built in the window from the git dates, and an unreadable
    contribution is grounds for disqualification."""
    code, out, err = git("log", "--since=" + WINDOW_START, "--until=" + WINDOW_END,
                         "--pretty=%h %ad %s", "--date=short")
    if code != 0:
        report.add("R3", "FAIL", "git log failed, so the window cannot be read", [err.strip()[:200]])
        return
    lines = [l for l in out.splitlines() if l.strip()]
    if not lines:
        report.add("R3", "WARN", "No commit in the hackathon window %s yet" % WINDOW)
    else:
        report.add("R3", "PASS", "%d commits in the window %s" % (len(lines), WINDOW),
                   lines[:5] + (["..."] if len(lines) > 5 else []))


def check_commit_language(report):
    """R7: the repository goes public for English-speaking judges, and from 2026-10-27 the commit
    subject and body are English (CLAUDE.md). Trailers are exempt: Trace: and Co-Authored-By: are
    not prose."""
    code, out, err = git("log", "--since=" + WINDOW_START, "--until=" + WINDOW_END,
                         "--pretty=%h%x01%s%x01%b%x02")
    if code != 0:
        report.add("R7", "FAIL", "git log failed, so the commit language cannot be read", [err.strip()[:200]])
        return
    bad, total = [], 0
    for record in out.split("\x02"):
        record = record.strip("\n")
        if not record.strip():
            continue
        parts = record.split("\x01")
        if len(parts) < 2:
            continue
        sha, subject, body = parts[0], parts[1], parts[2] if len(parts) > 2 else ""
        total += 1
        prose = [subject] + [l for l in body.splitlines()
                             if not re.match(r"^[A-Za-z-]+:\s", l.strip())]
        hit = next((l for l in prose if CYRILLIC.search(l)), None)
        if hit:
            bad.append("%s %s" % (sha, hit.strip()[:70]))
    if not total:
        report.add("R7", "WARN", "No commit in the window, so nothing to read")
    elif bad:
        report.add("R7", "FAIL", "Russian in %d of %d commit messages inside the window" % (len(bad), total),
                   bad[:10])
    else:
        report.add("R7", "PASS", "All %d commit messages in the window are English" % total)


# ---------------------------------------------------------------- build and tests


def check_build(report, full):
    tasks = [":app:assembleDebug"] if full else [":app:compileDebugKotlin"]
    if full and APK.exists():
        # A stale APK from an earlier build must not be scanned as this one's.
        try:
            APK.unlink()
        except OSError as e:
            report.add("T1", "FAIL", "The old APK cannot be deleted, so a stale one could be scanned",
                       ["%s: %s" % (rel(APK), e)])
            return False
    code, out = gradle(tasks, timeout=3600 if full else 1800)
    if code is None:
        report.add("T1", "FAIL", "Build timed out: %s" % " ".join(tasks), [out])
        return False
    if code == 0:
        report.add("T1", "PASS", "Build OK: %s" % " ".join(tasks))
        return True
    report.add("T1", "FAIL", "Build failed: %s" % " ".join(tasks), gradle_errors(out))
    return False


def check_tests(report):
    """T2: the full JVM suite, Roborazzi comparing against the committed baselines. This is the long
    one (tens of minutes) and the reason --fast exists."""
    for old in TEST_RESULTS.glob("*.xml"):
        # Gradle may skip an UP-TO-DATE test task; leftovers from a green run would then be read as
        # this run's result.
        try:
            old.unlink()
        except OSError:
            pass
    code, out = gradle([":app:testDebugUnitTest", "-Proborazzi.test.verify=true"], timeout=5400)
    if code is None:
        report.add("T2", "FAIL", "The unit tests timed out", [out])
        return
    total = failed = errors = skipped = 0
    for xml in TEST_RESULTS.glob("*.xml"):
        head = read(xml)[:2000]
        m = re.search(r'tests="(\d+)"\s+skipped="(\d+)"\s+failures="(\d+)"\s+errors="(\d+)"', head)
        if m:
            total += int(m.group(1))
            skipped += int(m.group(2))
            failed += int(m.group(3))
            errors += int(m.group(4))
    if total == 0:
        report.add("T2", "FAIL", "No unit test ran, so a green Gradle means nothing",
                   gradle_errors(out) if code else ["Gradle succeeded but wrote no test results"])
    elif code != 0 or failed or errors:
        report.add("T2", "FAIL", "Unit tests red: %d failures and %d errors of %d" % (failed, errors, total),
                   gradle_errors(out))
    else:
        report.add("T2", "PASS", "Unit tests green: %d of %d (%d skipped), Roborazzi verified against baselines"
                   % (total - skipped, total, skipped))


# ---------------------------------------------------------------- main


def print_report(report, mode, out):
    out.write("AI Open Pocket criteria check (%s) %s\n" % (mode, time.strftime("%Y-%m-%d %H:%M")))
    order = {"FAIL": 0, "WARN": 1, "PASS": 2, "SKIP": 3}
    for item in sorted(report.items, key=lambda x: (order.get(x["status"], 9), x["id"])):
        out.write("%-4s %-4s %s\n" % (item["id"], item["status"], item["title"]))
        for d in item["details"]:
            out.write("           - %s\n" % d)
    c = report.counts()
    out.write("Summary: %d FAIL, %d WARN, %d PASS\n"
              % (c.get("FAIL", 0), c.get("WARN", 0), c.get("PASS", 0)))


def guarded(report, cid, check, *args, **kwargs):
    """One check that crashes must not end the run: the others still have to report, and a crash is a
    FAIL of that check with the line it happened on, never a silent pass."""
    try:
        return check(*args, **kwargs)
    except Exception as e:
        where = traceback.extract_tb(e.__traceback__)[-1]
        report.add(cid, "FAIL", "The check itself crashed: %s: %s" % (type(e).__name__, e),
                   ["tools/check.py line %d in %s" % (where.lineno, where.name)])
        return None


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--fast", action="store_true", help="also compile Kotlin")
    ap.add_argument("--full", action="store_true", help="also assemble, run the unit tests and scan the APK")
    ap.add_argument("--json", help="write the result here (default build/check/last.json)")
    args = ap.parse_args()
    mode = "full" if args.full else "fast" if args.fast else "static"

    report = Report()
    guarded(report, "R2", check_meta_route, report)
    guarded(report, "R3", check_window_commits, report)
    guarded(report, "R7", check_commit_language, report)
    guarded(report, "T3", check_traxer, report)
    guarded(report, "T4", check_secrets, report)
    guarded(report, "T6", check_english_demo_path, report)
    guarded(report, "T6b", check_english_lessons, report)
    guarded(report, "T6c", check_screen_russian, report)
    guarded(report, "T6d", check_sink_russian, report)

    if args.fast or args.full:
        built = guarded(report, "T1", check_build, report, full=args.full)
        if args.full:
            guarded(report, "T2", check_tests, report)
            if built:
                guarded(report, "T4a", check_apk_keys, report)

    print_report(report, mode, sys.stdout)
    target = Path(args.json) if args.json else ROOT / "build" / "check" / "last.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    io.open(str(target), "w", encoding="utf-8", newline="").write(
        json.dumps({"mode": mode, "time": time.strftime("%Y-%m-%dT%H:%M:%S"), "items": report.items},
                   ensure_ascii=False, indent=2))
    return 1 if report.counts().get("FAIL") else 0


if __name__ == "__main__":
    sys.exit(main())
