#!/usr/bin/env python3
# Task 9: put the three mandatory header lines (brief 0.1) on top of every new
# evidence log. Idempotent: rewrites the header block instead of stacking it.
import io, os, sys
ROOT = 'D:/DreamKeeper/'
APK = 'apk_sha256=3979f0f61160cdc4'
FILES = [
    ('docs/evidence/plan2/e2e/p2-e2e-launch-live-take.log', 'Fri Oct 30 19:38:56 CST 2026', 'bash docs/evidence/plan2/e2e/run-live-take.sh'),
    ('docs/evidence/plan2/e2e/p2-e2e-run-crash-recovery.log', 'Fri Oct 30 19:39:30 CST 2026', 'bash docs/evidence/plan2/e2e/run-crash-recovery.sh'),
    ('docs/evidence/plan2/perm/p2-perm-01-A.log', 'Fri Oct 30 19:41:22 CST 2026', 'bash docs/evidence/plan2/e2e/run-perm-a.sh'),
    ('docs/evidence/plan2/perm/p2-perm-03-B.log', 'Fri Oct 30 19:41:44 CST 2026', 'bash docs/evidence/plan2/e2e/run-perm-b.sh'),
    ('docs/evidence/plan2/perm/p2-perm-10-C.log', 'Fri Oct 30 19:42:51 CST 2026', 'bash docs/evidence/plan2/e2e/run-perm-c.sh'),
    ('docs/evidence/plan2/perm/p2-perm-12-C2.log', 'Fri Oct 30 19:43:24 CST 2026', 'bash docs/evidence/plan2/e2e/run-perm-c2.sh'),
    ('docs/evidence/plan2/perm/p2-perm-14-recovery.log', 'Fri Oct 30 19:43:53 CST 2026', 'bash docs/evidence/plan2/e2e/run-perm-recover.sh'),
    ('docs/evidence/plan2/audit/p2-audit-01-run.log', 'Fri Oct 30 19:44:17 CST 2026', 'bash docs/evidence/plan2/e2e/run-audit.sh'),
    ('docs/evidence/plan2/coldstart/p2-coldstart-02-report.txt', 'Fri Oct 30 19:47:08 CST 2026', 'powershell -NoProfile -ExecutionPolicy Bypass -File scripts/capture-logcat.ps1 -Runs 3 -MaxStartMs 2000 -OutFile docs/evidence/plan2/coldstart/p2-coldstart-01-logcat.txt'),
    ('docs/evidence/plan2/coldstart/p2-coldstart-04-report-b.txt', 'Fri Oct 30 19:47:26 CST 2026', 'powershell -NoProfile -ExecutionPolicy Bypass -File scripts/capture-logcat.ps1 -Runs 3 -MaxStartMs 2000 -OutFile docs/evidence/plan2/coldstart/p2-coldstart-03-logcat-b.txt'),
]
for rel, date, cmd in FILES:
    p = ROOT + rel
    if not os.path.exists(p):
        print('MISSING', rel); continue
    txt = open(p, encoding='utf-8-sig', errors='replace').read()
    lines = txt.splitlines(True)
    if lines and lines[0].startswith('apk_sha256='):
        lines = lines[3:]
    head = '%s\ndevice_date=%s\nrerun=%s\n' % (APK, date, cmd)
    open(p, 'w', encoding='utf-8', newline='').write(head + ''.join(lines))
    print('OK', rel, os.path.getsize(p), 'bytes')
