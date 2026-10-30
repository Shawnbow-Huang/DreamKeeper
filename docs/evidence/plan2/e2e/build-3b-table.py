#!/usr/bin/env python3
# Task 9 9.4: rebuild the 3b audio table rows from real bytes.
# Columns (brief 0.2/0.4/0.5): wav | bytes(t1)/bytes(t2) | RIFF/data 字段 |
#   dataSize==bytes-44 | (bytes-44)/32 ms | ffprobe duration | Δms | RMS dBFS |
#   信号来源 | rem | 结论
# W2b byte check: riffSize == bytes-8, dataSize == bytes-44, rem == 0, canonical 44-byte head.
# W2c container check: ffmpeg -v warning stderr must contain ONLY "Guessed Channel Layout: mono".
import glob, os, struct, subprocess, sys, wave, math, hashlib
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
ROOT = 'D:/DreamKeeper'

ROWS = [
    # (label, path, bytes_t1, bytes_t2, source_note)
    ('Task3 fixture', 'docs/evidence/plan2/audio/p2-wav-01.wav', 32044, 32044, 'fixture（python/代码两份同 sha）'),
    ('Task5 补录', 'docs/evidence/plan2/audio/p2-followup-appended.wav', 121644, 153644, 'seam（模拟器灌零）'),
    ('Task6 回填', 'docs/evidence/plan2/audio/p2-scan-02-repaired.wav', 137644, 137644, 'seam（模拟器灌零）'),
    ('Task9 e2e', 'docs/evidence/plan2/audit/pull/b5249a66-76b0-4a15-8c0b-0535edc57f45.wav', 124844, 185644, 'seam（模拟器灌零）'),
    ('Task9 活录', 'docs/evidence/plan2/audio/p2-e2e-live-take.wav', 58348, 128748, 'seam（模拟器灌零）'),
    ('Task9 崩溃恢复', 'docs/evidence/plan2/audit/pull/2fe64b54-dab2-4716-9138-443438ad0c8c.wav', 96396, 192396, 'seam（模拟器灌零）'),
    ('Task9 appops静音', 'docs/evidence/plan2/audit/pull/3fc451d7-7530-4ef1-9074-7f3a25d684b7.wav', 80204, 80204, 'seam（appops ignore → audioserver silencing）'),
]

GOLD = bytes.fromhex('52494646') + b'\x00\x00\x00\x00' + b'WAVEfmt ' + bytes.fromhex('1000000001000100803e0000007d0000020010 00'.replace(' ', '')) + b'data' + b'\x00\x00\x00\x00'

def riff_ms(n):
    return (n - 44) // 32, (n - 44) % 32

print('| wav | bytes(t1)/bytes(t2) | RIFF/data 字段 | dataSize==bytes-44 | (bytes-44)/32 ms | ffprobe duration | Δms | RMS dBFS | 信号来源 | rem | 结论 |')
print('|---|---|---|---|---|---|---|---|---|---|---|')
notes=[]
for label, rel, t1, t2, src in ROWS:
    p = os.path.join(ROOT, rel)
    n = os.path.getsize(p)
    h = open(p, 'rb').read(44)
    riff, data = struct.unpack('<I', h[4:8])[0], struct.unpack('<I', h[40:44])[0]
    ph = (riff == 0xFFFFFFFF) or (data == 0xFFFFFFFF)
    rem = (n - 44) % 32
    formula = (n - 44) / 32
    ds_ok = (data == n - 44) or ph
    riff_ok = (riff == n - 8) or ph
    # canonical head compared field-wise (sizes differ per file)
    head_ok = h[0:4] == b'RIFF' and h[8:16] == b'WAVEfmt ' and h[16:20] == bytes.fromhex('10000000') \
        and h[20:36] == bytes.fromhex('01000100803e0000007d0000020010' + '00') and h[36:40] == b'data'
    fp = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration',
                         '-of', 'default=nw=1:nk=1', p], capture_output=True, text=True)
    dur = fp.stdout.strip()
    warn = subprocess.run(['ffmpeg', '-v', 'warning', '-i', p, '-f', 'null', '-'],
                          capture_output=True, text=True)
    stderr_lines = [l for l in warn.stderr.strip().splitlines() if l.strip()]
    w = wave.open(p)
    d = w.readframes(w.getnframes())
    s = struct.unpack('<%dh' % (len(d) // 2), d)
    nz = sum(1 for x in s if x != 0)
    rms = '-inf' if nz == 0 else '%.2f' % (20 * math.log10(math.sqrt(sum(x * x for x in s) / len(s)) / 32768))
    dms = '' if not dur else '%+.1f' % (float(dur) * 1000 - (n - 44) / 32)
    # 结论口径（brief 0.5）：本机是模拟器且无 -allow-host-audio，录不到真实人声，
    # 所以机制类判据全绿也只能写 PASS-ENV；「合格」要求真机/带 host audio 复跑。
    if ph:
        verdict = 'SKIP-OPEN'
    elif rem or not ds_ok or not riff_ok or not head_ok:
        verdict = '偏差'
    else:
        verdict = 'PASS-ENV'
    print('| `%s` | %d/%d | riff=%s data=%s | %s | %.1f | %s | %s | %s | %s | %d | %s |' % (
        rel.split('/')[-1], t1, t2, hex(riff), hex(data), '✓' if ds_ok else '✗', formula, dur, dms, rms, src, rem, verdict))
    soft = [l for l in stderr_lines if 'Guessed Channel Layout: mono' not in l]
    notes.append('- `%s`：bytes=%d、rem=%d、canonical 头=%s、ffmpeg stderr %d 行（非 mono 提示 %d 行）、非零样本 %d/%d'
                 % (rel.split('/')[-1], n, rem, head_ok, len(stderr_lines), len(soft), nz, len(s)))
    if soft:
        notes.append('  - ffmpeg warning 原文：%r' % soft)
print()
print('逐行附注：')
for line in notes:
    print(line)
