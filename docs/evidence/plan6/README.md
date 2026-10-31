# Plan 6 证据目录（多供应商 + 测试连接）

## 命名
`p6-<case>-<nn>-<state>.<ext>`：
- case：`contract`（契约/请求体/错误映射）、`key`（Key 存储与迁移）、`conn`（测试连接）、`llm`（传输层）、`ui`（设置页截图）、`a11y`、`full`（全量测试）、`release`。
- state：`red`/`green`/`mutation`/`ok`/`fail` 等。

## 头三行
host-only（JVM/脚本证据）：
```
APK_SHA256=n/a (host-only)
DEVICE_TIME=host-only <ISO8601 UTC>
REPLAY=<可原样重跑的命令>
```
设备证据：
```
APK_SHA256=<前16或全长>
DEVICE_TIME=<设备 date 原样>
REPLAY=<复跑步骤>
```

## 硬口径
- **Key 零命中**：证据目录 + REPLAY + logcat 三段 `grep -cE "sk-[A-Za-z0-9]{16,}"` = 0；必须带正对照（写一条临时假 Key 到临时文件 → grep = 1，再删）。
- **端点需实测确认**：五家 baseUrl/path/auth/model 均标来源 URL 与抓取日期；没有实测的一律写「需实测确认」，不得写成已验证。
- **假服务器自证**：每用例断言 path、Authorization 前缀、body 键集、request counter；不能只断言 200。
- **未配置不发请求**：JVM 断言 counter==0；配置后同流程 counter==1 正对照。
- **成本诚实**：非 DeepSeek 行 `costMicroYuan=0`、`formulaVersion=price-missing`、`priceSnapshotJson=null`；UI 显示「费用未知」。
- **PNG sidecar**：同名 `.txt` 记 `PNG_SHA256`、字节数、`nearWhite`（非艺术图 `-MaxRatio 0`；含插画图 `-Threshold 250 -MaxRatio 0.005`）。

## 复跑
```
cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew --no-daemon :app:testDebugUnitTest --rerun-tasks --console=plain
```
