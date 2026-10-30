apk_sha256=3979f0f61160cdc4
device_date=Fri Oct 30 19:49:41 CST 2026（最近一次详情页播放采样；各行 WAV 的采集时刻见 3b 附注与各索引）
rerun=python docs/evidence/plan2/e2e/build-3b-table.py

| wav | bytes(t1)/bytes(t2) | RIFF/data 字段 | dataSize==bytes-44 | (bytes-44)/32 ms | ffprobe duration | Δms | RMS dBFS | 信号来源 | rem | 结论 |
|---|---|---|---|---|---|---|---|---|---|---|
| `p2-wav-01.wav` | 32044/32044 | riff=0x7d24 data=0x7d00 | ✓ | 1000.0 | 1.000000 | +0.0 | -inf | fixture（python/代码两份同 sha） | 0 | PASS-ENV |
| `p2-followup-appended.wav` | 121644/153644 | riff=0x25824 data=0x25800 | ✓ | 4800.0 | 4.800000 | +0.0 | -76.74 | seam（模拟器灌零） | 0 | PASS-ENV |
| `p2-scan-02-repaired.wav` | 137644/137644 | riff=0x219a4 data=0x21980 | ✓ | 4300.0 | 4.300000 | +0.0 | -76.75 | seam（模拟器灌零） | 0 | PASS-ENV |
| `b5249a66-76b0-4a15-8c0b-0535edc57f45.wav` | 124844/185644 | riff=0x2d524 data=0x2d500 | ✓ | 5800.0 | 5.800000 | +0.0 | -76.95 | seam（模拟器灌零） | 0 | PASS-ENV |
| `p2-e2e-live-take.wav` | 58348/128748 | riff=0x273e4 data=0x273c0 | ✓ | 5022.0 | 5.022000 | +0.0 | -76.90 | seam（模拟器灌零） | 0 | PASS-ENV |
| `2fe64b54-dab2-4716-9138-443438ad0c8c.wav` | 96396/192396 | riff=0x2ef84 data=0x2ef60 | ✓ | 6011.0 | 6.011000 | +0.0 | -76.92 | seam（模拟器灌零） | 0 | PASS-ENV |
| `3fc451d7-7530-4ef1-9074-7f3a25d684b7.wav` | 80204/80204 | riff=0x13944 data=0x13920 | ✓ | 2505.0 | 2.505000 | +0.0 | -inf | seam（appops ignore → audioserver silencing） | 0 | PASS-ENV |

逐行附注：
- `p2-wav-01.wav`：bytes=32044、rem=0、canonical 头=True、ffmpeg stderr 1 行（非 mono 提示 0 行）、非零样本 0/16000
- `p2-followup-appended.wav`：bytes=153644、rem=0、canonical 头=True、ffmpeg stderr 1 行（非 mono 提示 0 行）、非零样本 65950/76800
- `p2-scan-02-repaired.wav`：bytes=137644、rem=0、canonical 头=True、ffmpeg stderr 1 行（非 mono 提示 0 行）、非零样本 58878/68800
- `b5249a66-76b0-4a15-8c0b-0535edc57f45.wav`：bytes=185644、rem=0、canonical 头=True、ffmpeg stderr 1 行（非 mono 提示 0 行）、非零样本 75966/92800
- `p2-e2e-live-take.wav`：bytes=160748、rem=0、canonical 头=True、ffmpeg stderr 1 行（非 mono 提示 0 行）、非零样本 66399/80352
- `2fe64b54-dab2-4716-9138-443438ad0c8c.wav`：bytes=192396、rem=0、canonical 头=True、ffmpeg stderr 1 行（非 mono 提示 0 行）、非零样本 79390/96176
- `3fc451d7-7530-4ef1-9074-7f3a25d684b7.wav`：bytes=80204、rem=0、canonical 头=True、ffmpeg stderr 1 行（非 mono 提示 0 行）、非零样本 0/40080
