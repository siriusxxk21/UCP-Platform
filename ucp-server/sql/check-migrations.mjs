import { createHash } from "node:crypto";
import { readFile, readdir } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const sqlRoot = dirname(fileURLToPath(import.meta.url));
const pattern = /^V([0-9]{3,})__[a-z][a-z0-9_]*\.sql$/;

/** 检查 SQL 源文件及已封存版本，不连接数据库、不生成或执行迁移。 */
export async function checkMigrations(root = sqlRoot) {
  const folder = join(root, "postgresql/migrations");
  const files = await readdir(folder, { withFileTypes: true });
  const migrations = [];
  for (const entry of files) {
    const match = pattern.exec(entry.name);
    if (!entry.isFile() || !match)
      throw new Error(`非法迁移文件：${entry.name}`);
    const version = Number(match[1]);
    if (
      !Number.isSafeInteger(version) ||
      version < 1 ||
      String(version).padStart(3, "0") !== match[1]
    ) {
      throw new Error(`版本编号必须从 V001 连续递增：${entry.name}`);
    }
    const bytes = await readFile(join(folder, entry.name));
    new TextDecoder("utf-8", { fatal: true }).decode(bytes);
    if (!bytes.length) throw new Error(`迁移文件为空：${entry.name}`);
    migrations.push({
      file: entry.name,
      version,
      sha256: createHash("sha256").update(bytes).digest("hex"),
    });
  }
  migrations.sort((a, b) => a.version - b.version);
  migrations.forEach((item, index) => {
    if (item.version !== index + 1)
      throw new Error(`版本重复或不连续：${item.file}`);
  });
  const lock = JSON.parse(
    await readFile(join(root, "postgresql/migrations.lock.json"), "utf8"),
  );
  if (
    lock.formatVersion !== 1 ||
    lock.historyTable !== "public.nocode_schema_history" ||
    !Array.isArray(lock.migrations)
  ) {
    throw new Error("迁移锁清单格式或历史表不匹配");
  }
  const sealed = new Set();
  for (const entry of lock.migrations) {
    if (sealed.has(entry.file)) throw new Error(`锁清单重复：${entry.file}`);
    sealed.add(entry.file);
    const actual = migrations.find((item) => item.file === entry.file);
    if (!actual || actual.sha256 !== entry.sha256)
      throw new Error(`已封存迁移被删除、重命名或修改：${entry.file}`);
  }
  return {
    count: migrations.length,
    sealed: sealed.size,
    unsealed: migrations.filter((item) => !sealed.has(item.file)),
    nextVersion: `V${String(migrations.length + 1).padStart(3, "0")}`,
  };
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  try {
    const result = await checkMigrations();
    console.log(
      `迁移目录检查通过：${result.count} 个版本，${result.sealed} 个已封存；下一版本 ${result.nextVersion}。`,
    );
    for (const entry of result.unsealed)
      console.log(`待封存：${entry.file} sha256=${entry.sha256}`);
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
