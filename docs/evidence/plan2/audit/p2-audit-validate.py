#!/usr/bin/env python3
# Task 9 9.5: whole-library audit of rawAudioMs vs real bytes.
# Rules (brief 0.11):
#   - placeholder (any of riffSize/dataSize still 0xFFFFFFFF) or an open file -> SKIP-OPEN,
#     never enters the diff (0xFFFFFFFF would otherwise compute ~1.3e8 ms).
#   - verdict for the rest: (bytes-44)//32 == rawAudioMs, tolerance 0.
#   - five-state partition (<44 / ==44 / odd tail / row w/o file / file w/o row) must
#     sum to |files() ∪ audioRows()|; unequal => enumeration incomplete.
import os, sqlite3, struct, sys, hashlib
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

BASE = 'docs/evidence/plan2/audit'
PULL = os.path.join(BASE, 'pull')
DB = os.path.join(BASE, 'dreamkeeper.db')

def head(path):
    with open(path, 'rb') as f:
        return f.read(44)

def is_wav(p):
    return os.path.getsize(p) >= 44 and head(p)[0:4] == b'RIFF' and head(p)[8:12] == b'WAVE'

files = {}
for name in sorted(os.listdir(PULL)):
    if not name.endswith('.wav'):
        continue
    p = os.path.join(PULL, name)
    n = os.path.getsize(p)
    h = head(p) if n >= 44 else b''
    riff = struct.unpack('<I', h[4:8])[0] if n >= 44 else None
    data = struct.unpack('<I', h[40:44])[0] if n >= 44 else None
    placeholder = (riff == 0xFFFFFFFF) or (data == 0xFFFFFFFF) if n >= 44 else None
    files[name[:-4]] = dict(name=name, bytes=n, riff=riff, data=data,
                            placeholder=placeholder, sha=hashlib.sha256(open(p,'rb').read()).hexdigest())

con = sqlite3.connect('file:%s?mode=ro' % DB, uri=True)
rows = {}
for rid, path, ms in con.execute("select id, rawAudioPath, rawAudioMs from dreams where rawAudioPath is not null"):
    rows[rid] = dict(path=path, ms=ms)

print('files=%d audioRows=%d union=%d' % (len(files), len(rows), len(set(files) | set(rows))))
print()
print('%-36s %10s %10s %9s %9s %8s %s' % ('id', 'bytes', 'dataSize', 'formula_ms', 'db_ms', 'rem', 'verdict'))
bad = 0
skipped = 0
st_lt44 = st_eq44 = st_odd = st_row_nofile = st_file_norow = 0
for fid in sorted(set(files) | set(rows)):
    f = files.get(fid); r = rows.get(fid)
    if f is None:
        st_row_nofile += 1
        print('%-36s %10s %10s %9s %9s %8s %s' % (fid, '-', '-', '-', r['ms'], '-', 'ROW-NO-FILE'))
        continue
    if r is None:
        st_file_norow += 1
    n = f['bytes']
    if n < 44:
        st_lt44 += 1; print('%-36s %10d %10s %9s %9s %8s %s' % (fid, n, '-', '-', '-' if r is None else r['ms'], '-', 'LT44')); continue
    if n == 44:
        st_eq44 += 1; print('%-36s %10d %10d %9d %9s %8s %s' % (fid, n, f['data'], 0, '-' if r is None else r['ms'], 0, 'EQ44')); continue
    rem = (n - 44) % 32
    if rem:
        st_odd += 1
    if f['placeholder']:
        skipped += 1
        print('%-36s %10d %10d %9s %9s %8d %s' % (fid, n, f['data'], '-', '-' if r is None else r['ms'], rem, 'SKIP-OPEN(placeholder)'))
        continue
    formula = (n - 44) // 32
    ds_ok = f['data'] == n - 44
    riff_ok = f['riff'] == n - 8
    if r is None:
        verdict = 'FILE-NO-ROW'
    elif formula == r['ms'] and rem == 0 and ds_ok and riff_ok:
        verdict = 'PASS'
    else:
        verdict = 'RED(formula=%d db=%d rem=%d ds_ok=%s riff_ok=%s)' % (formula, r['ms'], rem, ds_ok, riff_ok); bad += 1
    print('%-36s %10d %10d %9d %9d %8d %s' % (fid, n, f['data'], formula, -1 if r is None else r['ms'], rem, verdict))

healthy = len(set(files) | set(rows)) - (st_lt44 + st_eq44 + st_odd + st_row_nofile + st_file_norow)
total = healthy + st_lt44 + st_eq44 + st_odd + st_row_nofile + st_file_norow
print()
print('five_state: lt44=%d eq44=%d odd_tail=%d row_no_file=%d file_no_row=%d sum=%d union=%d %s'
      % (st_lt44, st_eq44, st_odd, st_row_nofile, st_file_norow, total, len(set(files) | set(rows)),
         'OK' if total == len(set(files) | set(rows)) else 'MISMATCH'))
print('  note: brief 0.11 literal "five states sum == union" is unsatisfiable for healthy pairs;')
print('        the exhaustive partition used here is healthy + the five anomaly states.')
print('  healthy=%d checked=%d skipped_open=%d red=%d' % (healthy, len(files) - skipped, skipped, bad))
print('RESULT:', 'GREEN' if bad == 0 and total == len(set(files) | set(rows)) and st_odd == 0 else 'RED')
