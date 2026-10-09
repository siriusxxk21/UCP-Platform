import { strict as assert } from "node:assert";
import { createHash } from "node:crypto";
import { mkdtemp, mkdir, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { checkMigrations } from "./check-migrations.mjs";

async function fixture(action) {
  const root = await mkdtemp(join(tmpdir(), "ucp-platform-migration-check-"));
  try {
    const folder = join(root, "postgresql/migrations");
    await mkdir(folder, { recursive: true });
    const file = "V001__fixture.sql",
      sql = "-- 测试夹具，不连接数据库\nSELECT 1;\n";
    await writeFile(join(folder, file), sql);
    await writeFile(
      join(root, "postgresql/migrations.lock.json"),
      JSON.stringify({
        formatVersion: 1,
        historyTable: "public.nocode_schema_history",
        migrations: [
          { file, sha256: createHash("sha256").update(sql).digest("hex") },
        ],
      }),
    );
    await action(root, folder);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
}

test("已封存版本原样通过，新增草稿版本单独列出", () =>
  fixture(async (root, folder) => {
    assert.equal((await checkMigrations(root)).sealed, 1);
    await writeFile(join(folder, "V002__next.sql"), "SELECT 2;\n");
    const result = await checkMigrations(root);
    assert.equal(result.nextVersion, "V003");
    assert.deepEqual(
      result.unsealed.map((item) => item.file),
      ["V002__next.sql"],
    );
  }));

test("历史文件改写或删除都会失败", () =>
  fixture(async (root, folder) => {
    const file = join(folder, "V001__fixture.sql");
    await writeFile(file, "SELECT 99;\n");
    await assert.rejects(checkMigrations(root), /已封存迁移/);
    await rm(file);
    await assert.rejects(checkMigrations(root), /已封存迁移/);
  }));

test("重复版本及跳号被拒绝", () =>
  fixture(async (root, folder) => {
    const duplicate = join(folder, "V001__duplicate.sql");
    await writeFile(duplicate, "SELECT 2;\n");
    await assert.rejects(checkMigrations(root), /版本重复或不连续/);
    await rm(duplicate);
    await writeFile(join(folder, "V003__gap.sql"), "SELECT 3;\n");
    await assert.rejects(checkMigrations(root), /版本重复或不连续/);
  }));

test("错误命名不会被静默漏扫", () =>
  fixture(async (root, folder) => {
    await writeFile(join(folder, "V002_single_underscore.sql"), "SELECT 2;\n");
    await assert.rejects(checkMigrations(root), /非法迁移文件/);
  }));
