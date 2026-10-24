"""Pre-generate lesson illustrations with Muse Image and bundle them into the app assets.

For every "image" step that has an imagePrompt but no asset/imageKey, the script asks
Muse Image (via OpenRouter) for a picture, converts it to WebP, saves it under
app/src/main/assets/lessons/images/generated/ and writes the "asset" field into the
lesson JSON. After that the app shows the bundled picture: offline, for free, and for
every provider -- runtime generation only covers steps that still have no asset.

The lesson JSON is patched as text, not re-serialized, so hand formatting and line
endings stay as they are. Prompts are therefore plain ASCII without quotes or
backslashes (LessonAssetsValidationTest enforces it).

Usage:
    python tools/lesson-images/pregenerate.py --dry-run
    OPENROUTER_API_KEY=... python tools/lesson-images/pregenerate.py [--only rag plan_mode]

Requires Pillow (pip install pillow).
"""
# @trace kartinki-urokov-cherez-muse-image-fd6f8c

import argparse
import base64
import hashlib
import io
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LESSONS_DIR = ROOT / "app" / "src" / "main" / "assets" / "lessons"
ASSETS_DIR = ROOT / "app" / "src" / "main" / "assets"
OUT_SUBDIR = "lessons/images/generated"

ENDPOINT = "https://openrouter.ai/api/v1/images"
MODEL = "meta/muse-image"

# One line on purpose: LessonImageStyleSyncTest looks for the Kotlin LESSON_IMAGE_STYLE
# verbatim here, so bundled and on-device pictures share one style.
STYLE = "Flat, clean editorial illustration for a mobile lesson about AI. Light background, soft colors, wide 16:9 landscape composition. No text, letters or numbers anywhere in the image."

MAX_WIDTH = 1280
WEBP_QUALITY = 80
REQUEST_TIMEOUT_S = 180


def bundled_asset(step):
    """Same rule as LessonChatController: explicit asset first, then imageKey shorthand."""
    if step.get("asset"):
        return step["asset"]
    if step.get("imageKey"):
        return f"lessons/images/{step['imageKey']}.svg"
    return ""


def pending_steps(lesson):
    """Image steps that have a prompt and nothing bundled yet."""
    return [
        s for s in lesson.get("steps", [])
        if s.get("type") == "image" and s.get("imagePrompt") and not bundled_asset(s)
    ]


def asset_path_for(lesson_id, prompt):
    # Named by lesson and prompt, not by step index: inserting a step must not rename files.
    digest = hashlib.sha256(f"{MODEL}\n{prompt}".encode("utf-8")).hexdigest()[:10]
    return f"{OUT_SUBDIR}/{lesson_id}-{digest}.webp"


def generate(prompt, api_key):
    body = json.dumps({"model": MODEL, "prompt": f"{prompt.strip()}\n\n{STYLE}"}).encode("utf-8")
    request = urllib.request.Request(
        ENDPOINT,
        data=body,
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_S) as response:
        payload = json.loads(response.read().decode("utf-8"))
    images = [d for d in payload.get("data", []) if d.get("b64_json")]
    if not images:
        raise RuntimeError("response has no image")
    cost = (payload.get("usage") or {}).get("cost")
    return base64.b64decode(images[0]["b64_json"]), cost


def to_webp(raw):
    from PIL import Image  # imported lazily so --dry-run works without Pillow

    image = Image.open(io.BytesIO(raw))
    image = image.convert("RGBA" if image.mode in ("RGBA", "LA", "P") else "RGB")
    if image.width > MAX_WIDTH:
        height = round(image.height * MAX_WIDTH / image.width)
        image = image.resize((MAX_WIDTH, height), Image.LANCZOS)
    out = io.BytesIO()
    image.save(out, "WEBP", quality=WEBP_QUALITY, method=6)
    return out.getvalue()


def link_asset(lesson_file, prompt, asset):
    """Insert "asset" in front of the step's imagePrompt, keeping the file's formatting."""
    text = lesson_file.read_bytes().decode("utf-8")
    needle = '"imagePrompt": ' + json.dumps(prompt, ensure_ascii=False)
    count = text.count(needle)
    if count != 1:
        raise RuntimeError(f"imagePrompt found {count} times in {lesson_file.name}, expected exactly once")
    patched = text.replace(needle, f'"asset": {json.dumps(asset)}, {needle}')
    json.loads(patched)  # never leave a broken lesson behind
    lesson_file.write_bytes(patched.encode("utf-8"))


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dry-run", action="store_true", help="only list what would be generated")
    parser.add_argument("--only", nargs="*", default=None, help="lesson ids to process")
    args = parser.parse_args()

    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    api_key = os.environ.get("OPENROUTER_API_KEY", "").strip()
    if not args.dry_run and not api_key:
        sys.exit("OPENROUTER_API_KEY is not set. Run with --dry-run to see the plan without it.")
    if not args.dry_run and ("..." in api_key or not api_key.startswith("sk-or-")):
        # The README shows "sk-or-..." as a placeholder; OpenRouter answers it with a confusing
        # "Missing Authentication header" for every single image.
        sys.exit("OPENROUTER_API_KEY does not look like a real OpenRouter key (sk-or-v1-...). "
                 "Replace the placeholder with your key from https://openrouter.ai/keys")

    todo = []
    for lesson_file in sorted(LESSONS_DIR.glob("*.json")):
        lesson = json.loads(lesson_file.read_bytes().decode("utf-8"))
        lesson_id = lesson.get("id", lesson_file.stem)
        if args.only is not None and lesson_id not in args.only:
            continue
        for step in pending_steps(lesson):
            todo.append((lesson_file, lesson_id, step["imagePrompt"]))

    print(f"{len(todo)} image(s) to bundle, about ${0.01 * len(todo):.2f} at $0.01 per image")
    failures = 0
    total_cost = 0.0
    for lesson_file, lesson_id, prompt in todo:
        asset = asset_path_for(lesson_id, prompt)
        target = ASSETS_DIR / asset
        label = f"{lesson_id}: {prompt[:70]}"
        if args.dry_run:
            print(f"  would draw  {label} -> {asset}")
            continue
        try:
            if target.is_file():
                # A previous run drew it but stopped before patching the lesson.
                print(f"  reuse       {label}")
            else:
                raw, cost = generate(prompt, api_key)
                webp = to_webp(raw)
                target.parent.mkdir(parents=True, exist_ok=True)
                tmp = target.with_suffix(".tmp")
                tmp.write_bytes(webp)
                tmp.replace(target)
                total_cost += cost or 0.0
                print(f"  drew        {label} ({len(webp) // 1024} KB)")
            link_asset(lesson_file, prompt, asset)
        except (urllib.error.URLError, RuntimeError, OSError, ValueError) as e:
            failures += 1
            detail = e.read().decode("utf-8", "replace")[:300] if isinstance(e, urllib.error.HTTPError) else ""
            print(f"  FAILED      {label}: {e} {detail}")
            if isinstance(e, urllib.error.HTTPError) and e.code in (401, 403):
                # A rejected key will not get better on the next image -- stop instead of
                # repeating the same error for every lesson.
                failures += len(todo) - todo.index((lesson_file, lesson_id, prompt)) - 1
                print("  stopping: the key was rejected, nothing else will succeed with it")
                break

    if not args.dry_run:
        print(f"done: {len(todo) - failures} bundled, {failures} failed, provider cost ${total_cost:.4f}")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
