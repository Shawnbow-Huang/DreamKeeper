#!/usr/bin/env bash
# 把 danjdewhurst/story-skills 的 skills/**/*.md 内嵌进 Android assets。
#
# 为什么不 pin commit：GitHub API 的 commits 接口在无 token 时限流（本项目已撞到 403），
# 而 tarball 下载走 gh-proxy 是稳定的。这里改为 pin **归档 sha256**：
#   archive_sha256 = 1943297babdcdfedc5652a3c60b713a9c9d73e5c04fd093ed4e767cf85f355d4
# 归档变了就换 sha 并重新跑，不会静默升级内容。
set -euo pipefail

REPO="danjdewhurst/story-skills"
REF="${REF:-main}"
ARCHIVE_SHA256="${ARCHIVE_SHA256:-1943297babdcdfedc5652a3c60b713a9c9d73e5c04fd093ed4e767cf85f355d4}"
PROXY="https://gh-proxy.com"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

URL="$PROXY/https://codeload.github.com/$REPO/tar.gz/refs/heads/$REF"
echo "下载 $URL"
curl -fsSL --max-time 180 "$URL" -o "$WORK/story-skills.tgz"

ACTUAL="$(sha256sum "$WORK/story-skills.tgz" | cut -d' ' -f1)"
if [[ "$ACTUAL" != "$ARCHIVE_SHA256" ]]; then
  echo "归档 sha256 不符：期望 $ARCHIVE_SHA256，实际 $ACTUAL" >&2
  exit 1
fi

tar -xzf "$WORK/story-skills.tgz" -C "$WORK" --exclude='*/plugins' 2>/dev/null || true
SRC="$WORK/story-skills-$REF"
[[ -d "$SRC/skills" ]] || { echo "解包后找不到 skills/：$SRC" >&2; exit 1; }

DST="android/app/src/main/assets/skills/story"
rm -rf "$DST"
mkdir -p "$DST"

# 只收 skills/**/*.md，保留相对路径。
(cd "$SRC" && find skills -name '*.md' -print0 | while IFS= read -r -d '' f; do
  mkdir -p "/d/DreamKeeper/$DST/$(dirname "$f")"
  cp "$f" "/d/DreamKeeper/$DST/$f"
done)

python - "$DST" "$ARCHIVE_SHA256" "$REPO" "$REF" <<'PY'
import hashlib, json, pathlib, sys
dst, archive, repo, ref = sys.argv[1:5]
root = pathlib.Path(dst)
files = []
for p in sorted(root.rglob('*.md')):
    b = p.read_bytes()
    files.append({
        "path": p.relative_to(root).as_posix(),
        "bytes": len(b),
        "sha256": hashlib.sha256(b).hexdigest(),
    })
manifest = {
    "source": f"https://github.com/{repo}",
    "ref": ref,
    "archive_sha256": archive,
    "license": "MIT",
    "files": files,
}
(root / "MANIFEST.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"files={len(files)} bytes={sum(f['bytes'] for f in files)}")
PY
