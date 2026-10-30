"""逐字比对 Room schema JSON 与手写迁移 SQL 的一致性（Task 4 证据机）。

A. MigrationTest 里手搭 v1 库的 CREATE 语句 vs schemas/1.json 的 createSql
B. MIGRATION_1_2 里的 CREATE 语句 vs 2.json 中「v1 没有的语句」
   （CREATE TABLE `dreams` 被两条 ALTER TABLE 取代，属预期不出现）
C. ADD COLUMN / addMigrations / fallbackToDestructiveMigration 的硬断言

比较前去掉反斜杠/反引号/全部空白（Room 的 ${TABLE_NAME} 占位符在 2.json 与测试里
同为占位符，在迁移里已被替换成真表名，故 A 用原样、B 统一替换成真表名再比）。

用法：在仓库任意位置执行 `python scripts/check-schema-verbatim.py`；退出码 0 表示全部 PASS。
"""
import json, re, os
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
base = root + '/android/app/schemas/com.dreamkeeper.data.db.DreamDatabase/'
s1 = json.load(open(base + '1.json', encoding='utf-8'))
s2 = json.load(open(base + '2.json', encoding='utf-8'))
kt = open(root + '/android/app/src/main/java/com/dreamkeeper/data/db/DreamDatabase.kt', encoding='utf-8').read()
test = open(root + '/android/app/src/test/java/com/dreamkeeper/data/db/MigrationTest.kt', encoding='utf-8').read()

def canon(s):
    s = s.replace('\\', '').replace('`', '')
    return re.sub(r'\s+', '', s)

def stmts(schema):
    out = []
    for e in schema['database']['entities']:
        t = e['tableName']
        out.append(('table', t, e['createSql'].replace('${TABLE_NAME}', t)))
        for idx in e.get('indices', []):
            out.append(('index', idx['name'], idx['createSql'].replace('${TABLE_NAME}', t)))
    return out

def stmts_ph(schema):
    out = []
    for e in schema['database']['entities']:
        out.append(('table', e['tableName'], e['createSql']))
        for idx in e.get('indices', []):
            out.append(('index', idx['name'], idx['createSql']))
    return out

print('== A. MigrationTest 手搭 v1 库 vs 1.json（测试字面量保留 ${TABLE_NAME} 占位符）==')
t2 = test.replace('\\$', '$').replace('\\`', '`')
t2 = re.sub(r'"\s*\+\s*\n?\s*"', '', t2)
test_lits = {canon(x) for x in re.findall(r'"(CREATE [^"]*)"', t2)}
okA = True
for kind, name, sql in stmts_ph(s1):
    hit = canon(sql) in test_lits
    okA = okA and hit
    print(f'  {kind:5s} {name:26s} verbatim_in_test={hit}')
print('  RESULT_A =', 'PASS 6/6' if okA else 'FAIL')

print()
print('== B. MIGRATION_1_2 vs 2.json（期望 = 2.json 中 v1 没有的语句，排除被 ALTER 取代的 CREATE TABLE dreams）==')
body = kt[kt.index('val MIGRATION_1_2'):]
body = re.sub(r'"\s*\+\s*\n?\s*"', '', body)
got = [('migration', '', s) for s in re.findall(r'"(CREATE [^"]*)"', body)]
c1 = {canon(s) for _, _, s in stmts(s1)}
expected = [x for x in stmts(s2)
            if canon(x[2]) not in c1 and not (x[0] == 'table' and x[1] == 'dreams')]
gc = [canon(s) for _, _, s in got]
ec = [canon(s) for _, _, s in expected]
print(f'  迁移 CREATE 语句数={len(got)}  2.json 应新增语句数={len(expected)}')
print(f'  missing = {len([s for s in ec if s not in gc])}   extra = {len([s for s in gc if s not in ec])}')
for s in expected:
    print(f'    [{ "OK " if canon(s[2]) in gc else "MISS" }] {s[0]:5s} {s[1]}')
print('  RESULT_B =', 'PASS 8/8' if sorted(ec) == sorted(gc) else 'FAIL')
print('  注：2.json 的 CREATE TABLE `dreams` 不出现于迁移，由两条 ALTER TABLE 取代（预期）。')

print()
print('== C. 其它硬断言 ==')
code_only = re.sub(r'//[^\n]*', '', re.sub(r'/\*[\s\S]*?\*/', '', kt))
print('  ALTER ADD novelSelectedVersion :', 'ALTERTABLEdreamsADDCOLUMNnovelSelectedVersionINTEGER' in canon(kt))
print('  ALTER ADD generationStage      :', 'ALTERTABLEdreamsADDCOLUMNgenerationStageTEXT' in canon(kt))
print('  addMigrations 调用次数         :', code_only.count('addMigrations'))
print('  fallbackToDestructiveMigration 文本出现:', kt.count('fallbackToDestructiveMigration'),
      '（注释中的字样；代码调用', len(re.findall(r'fallbackToDestructiveMigration\s*\(', code_only)), '处）')
print('  1.json identityHash            :', s1['database']['identityHash'])
print('  2.json identityHash            :', s2['database']['identityHash'])
