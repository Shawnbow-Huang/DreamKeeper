#!/usr/bin/env bash
# 拉取内置离线语音识别的两个大二进制（D563）。
#
# 为什么不在 git 里：AAR 49MB + int8 模型 239MB，进仓库会把 clone/历史撑爆，
# 而且它们都能从官方 Release / 模型库按 SHA256 校验后重新拿到。
#
# 用法：bash scripts/fetch-asr.sh
# 幂等：已存在且哈希正确就跳过；哈希不对会删掉重下。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIB_DIR="$ROOT/android/app/libs"
ASSET_DIR="$ROOT/android/app/src/main/assets/sensevoice"
TEST_DIR="$ROOT/android/app/src/androidTest/assets"

SHERPA_AAR="sherpa-onnx-2.0.1.aar"
SHERPA_AAR_SHA="a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"
SHERPA_URL="https://gh-proxy.com/https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/$SHERPA_AAR"

MODEL_FILE="model.int8.onnx"
MODEL_SHA="c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"
TOKENS_FILE="tokens.txt"
TOKENS_SHA="f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"
MODEL_BASE="https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main"

log() { printf '%s\n' "$*" >&2; }

sha_of() { sha256sum "$1" | awk '{print $1}'; }

fetch() { # fetch <url> <dest> <sha>
  local url="$1" dest="$2" want="$3"
  if [[ -f "$dest" ]] && [[ "$(sha_of "$dest")" == "$want" ]]; then
    log "✓ 已存在：$dest"
    return 0
  fi
  rm -f "$dest"
  log "↓ 下载 $(basename "$dest") …"
  curl -fL --retry 3 --retry-delay 2 -m 1800 -o "$dest" "$url"
  local got
  got="$(sha_of "$dest")"
  if [[ "$got" != "$want" ]]; then
    log "✗ SHA256 不匹配：$dest"
    log "  期望 $want"
    log "  实际 $got"
    rm -f "$dest"
    exit 1
  fi
  log "✓ 校验通过：$(basename "$dest")"
}

mkdir -p "$LIB_DIR" "$ASSET_DIR" "$TEST_DIR"

fetch "$SHERPA_URL" "$LIB_DIR/$SHERPA_AAR" "$SHERPA_AAR_SHA"
fetch "$MODEL_BASE/$MODEL_FILE" "$ASSET_DIR/$MODEL_FILE" "$MODEL_SHA"
fetch "$MODEL_BASE/$TOKENS_FILE" "$ASSET_DIR/$TOKENS_FILE" "$TOKENS_SHA"

# 许可证只有一行引用，内容可能随上游改；只在缺失时补，不参与哈希校验。
if [[ ! -f "$ASSET_DIR/MODEL-LICENSE.txt" ]]; then
  curl -fL -m 120 -o "$ASSET_DIR/MODEL-LICENSE.txt" "$MODEL_BASE/LICENSE"
fi

log ""
log "内置语音识别依赖已就绪："
log "  $LIB_DIR/$SHERPA_AAR        $(du -h "$LIB_DIR/$SHERPA_AAR" | cut -f1)"
log "  $ASSET_DIR/$MODEL_FILE      $(du -h "$ASSET_DIR/$MODEL_FILE" | cut -f1)"
log "  $ASSET_DIR/$TOKENS_FILE     $(du -h "$ASSET_DIR/$TOKENS_FILE" | cut -f1)"
