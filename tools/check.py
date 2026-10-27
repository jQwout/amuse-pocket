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
    return io.open(str(path), encoding="utf-8", errors="replace").read()


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
    """T4: no live key in anything git tracks, .env is not tracked, and the release guard is wired."""
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

    gradle_text = read(APP_GRADLE) if APP_GRADLE.exists() else ""
    if "checkReleaseSecrets" not in gradle_text:
        details.append("the checkReleaseSecrets task is gone from app/build.gradle.kts: a release "
                       "would carry .env inside BuildConfig")
    elif not re.search(r"generateReleaseBuildConfig|bundleRelease|assembleRelease", gradle_text):
        details.append("checkReleaseSecrets hangs on no release task, so it never runs")

    if details:
        report.add("T4", "FAIL", "Live keys or build artifacts in what git tracks", details[:15])
    else:
        report.add("T4", "PASS", "No provider keys in tracked files; .env untracked; release guard wired")


def check_apk_keys(report):
    """T4a: the artifact is what a judge can unpack, so the keys are looked for inside it, not in
    the sources that produced it."""
    if not APK.exists():
        report.add("T4a", "FAIL", "No APK after a successful build, so nothing was scanned", [rel(APK)])
        return
    found, scanned = {}, 0
    try:
        with zipfile.ZipFile(str(APK)) as zf:
            for info in zf.infolist():
                if info.file_size > 64 * 1024 * 1024:
                    continue
                data = zf.read(info)
                scanned += 1
                for kind, value in find_keys(data):
                    found.setdefault((info.filename, kind, key_mask(value)), None)
    except (zipfile.BadZipFile, OSError) as e:
        report.add("T4a", "FAIL", "The APK could not be read, so it was not scanned", ["%s: %s" % (rel(APK), e)])
        return
    if not scanned:
        report.add("T4a", "FAIL", "The APK holds no entries, so the scan proves nothing", [rel(APK)])
    elif found:
        report.add("T4a", "FAIL", "Provider keys inside the debug APK",
                   ["%s: %s %s" % (f, k, v) for f, k, v in sorted(found)][:15])
    else:
        report.add("T4a", "PASS", "No provider key in any of the %d entries of the debug APK" % scanned)


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


def check_english_lessons(report):
    """T6b: the lessons of the demo path exist in English and step for step match the original."""
    details = []
    for name in DEMO_PATH_LESSONS:
        ru_path, en_path = LESSONS / (name + ".json"), LESSONS / "en" / (name + ".json")
        if not ru_path.exists():
            details.append("%s: the Russian original is gone" % rel(ru_path))
            continue
        if not en_path.exists():
            details.append("%s: no English copy, so the demo path falls back to Russian" % name)
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
        report.add("T6b", "FAIL", "The English lessons of the demo path do not match the originals", details[:15])
    else:
        report.add("T6b", "PASS", "All %d demo-path lessons have an English copy of the same shape"
                   % len(DEMO_PATH_LESSONS))


def check_meta_route(report):
    """R2: Stage 1 of the judging is pass/fail on "Meta Model API and/or Meta's open weights".
    OpenRouter is neither, so the direct route has to be the one in the build."""
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
