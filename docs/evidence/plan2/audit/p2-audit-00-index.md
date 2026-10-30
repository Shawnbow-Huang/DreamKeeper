apk_sha256=3979f0f61160cdc4
device_date=Fri Oct 30 19:44:17 CST 2026
rerun=bash docs/evidence/plan2/e2e/run-audit.sh && python docs/evidence/plan2/audit/p2-audit-validate.py

# Task 9 §9.5 全库 rawAudioMs 审计

## 前置（简报 0.11）

```
adb shell am force-stop com.dreamkeeper
adb shell am start -W -n com.dreamkeeper/.MainActivity     # 冷启动，让 AudioScan.repair 跑完
sleep 4
adb shell am force-stop com.dreamkeeper                    # 冻结后再拉文件/库，避免边写边读
```
文件：`p2-audit-02-files.txt`（`ls -l files/audio/`）、`p2-audit-03-headers.txt`（每个文件 `od -An -tx1 -N 44`）、
`pull/` 下 7 个 WAV 原样副本、`dreamkeeper.db` + `dreamkeeper.db-wal`。

## 判据

- 健康对：`(bytes-44)//32 == rawAudioMs`，**容差 0**；且 `dataSize == bytes-44`、`riffSize == bytes-8`、`rem=(bytes-44)%32 == 0`
- 占位头（任一长度字段 `FF FF FF FF`）或仍在 open set 的文件 → `SKIP-OPEN`，**不进 diff**（否则会算出 ~1.3e8 ms 的假红）
- 完备性：健康态 + 五态异常（<44 / ==44 / 奇数尾 / 有行无文件 / 有文件无行）之和 == `files() ∪ audioRows()` 去重总数

## 结果（`p2-audit-04-validate.txt`）

```
files=7 audioRows=7 union=7
0b0b270e-977f-41b2-8938-03ccc3c43ac4   65228  65184   2037  2037  rem=0  PASS
1dbe3423-4170-43ed-9559-b283f0988a8f   80332  80288   2509  2509  rem=0  PASS
2fe64b54-dab2-4716-9138-443438ad0c8c  192396 192352   6011  6011  rem=0  PASS
3fc451d7-7530-4ef1-9074-7f3a25d684b7   80204  80160   2505  2505  rem=0  PASS
5a182061-fd4a-49fc-9054-8a6f8d9f8e8d  160748 160704   5022  5022  rem=0  PASS
b5249a66-76b0-4a15-8c0b-0535edc57f45  185644 185600   5800  5800  rem=0  PASS
dfdca4e7-df47-4f00-b67f-39ca243d59b4   93356  93312   2916  2916  rem=0  PASS
five_state: lt44=0 eq44=0 odd_tail=0 row_no_file=0 file_no_row=0 sum=7 union=7 OK
checked=7 skipped_open=0 red=0
RESULT: GREEN
```

- 7/7 一致，容差 0；**无占位头**——A 路 revoke 留下的孤儿 `dfdca4e7`（93,356 B）也被扫描回填成 2,916 ms 并且有行
- `skipped_open=0`：本轮**没有**出现开放/占位文件，SKIP-OPEN 分支未被走通（该分支行为由 Task 6 证据 `p2-scan-01-before.txt` 覆盖）
- 五态强制项：简报 0.11 的字面「五态之和 == union」对健康文件对**不可满足**（健康对不属于任何一态）→
  实际用「健康态 + 五态异常」的完备划分，`sum=7 == union=7`。→ 偏差 T9-1（D210）
- DB 侧共性：7 条全部 `transcriptStatus=FAILED`、`isDraft=1`、`rawTranscript` 为空（模拟器中文转写不可用，Task 7 已证）
