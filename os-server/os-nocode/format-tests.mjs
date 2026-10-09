import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { collectJavaFiles } from './format.mjs';

function fixture(run) {
  const root = mkdtempSync(path.join(os.tmpdir(), 'nocode 格式检查 '));
  function file(name, content = 'class Example {}') {
    const target = path.join(root, name);
    mkdirSync(path.dirname(target), { recursive: true });
    writeFileSync(target, content);
    return target;
  }
  try {
    file('os-nocode/pom.xml', '<project><modules><module>os-nocode-example</module></modules></project>');
    const main = file('os-nocode/os-nocode-example/src/main/java/Example.java');
    const test = file('os-nocode/os-nocode-example/src/test/java/ExampleTest.java');
    file('os-server/src/main/java/Application.java');
    const metadata = 'os-framework/os-spring-boot-starter-mybatis/src/main/java/com/richuang/os/framework/mybatis/';
    file(metadata + 'core/metadata/Metadata.java');
    file(metadata + 'config/OsDatabaseMetadataAutoConfiguration.java');
    file('os-framework/os-spring-boot-starter-excel/src/main/java/com/richuang/os/framework/excel/core/util/ExcelUtils.java');
    file('os-module-bpm/os-module-bpm-api/src/main/java/com/richuang/os/module/bpm/api/definition/dto/BpmProcessDefinitionDTO.java');
    file('os-module-bpm/os-module-bpm-api/src/main/java/com/richuang/os/module/bpm/api/definition/BpmUserGroupApi.java');
    file('os-module-bpm/os-module-bpm-core/src/main/java/com/richuang/os/module/bpm/api/definition/BpmUserGroupApiImpl.java');
    file('os-module-bpm/os-module-bpm-core/src/main/java/com/richuang/os/module/bpm/api/definition/BpmProcessDefinitionApiImpl.java');
    run({ root, file, main, test });
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

test('只选择声明模块的源码、测试、启动类和既有底座扩展，支持中文和空格路径', () => {
  fixture(({ root, file, main, test }) => {
    const excluded = [
      file('os-nocode/.work/copy/os-nocode-example/src/main/java/Old.java'),
      file('os-nocode/os-nocode-example/target/generated-sources/Generated.java'),
      file('os-nocode/os-nocode-unlisted/src/main/java/Unlisted.java'),
      file('os-nocode/os-nocode-example/src/main/java/.work/Copy.java'),
    ];
    const actual = collectJavaFiles(root);
    assert.equal(actual.length, 10);
    assert.ok(actual.includes(main));
    assert.ok(actual.includes(test));
    for (const name of excluded) assert.ok(!actual.includes(name), name);
  });
});

test('忽略注释中的模块声明，缺失的声明模块不能静默跳过', () => {
  fixture(({ root, file }) => {
    file('os-nocode/pom.xml', '<project><!-- <modules><module>old</module></modules> --><modules><module>os-nocode-missing</module></modules></project>');
    assert.throws(() => collectJavaFiles(root), /source directory is missing/);
  });
});

test('空模块清单、重复模块和路径越界明确失败', () => {
  fixture(({ root, file }) => {
    for (const modules of ['', '<module>../outside</module>', '<module>os-nocode-example</module><module>os-nocode-example</module>']) {
      file('os-nocode/pom.xml', '<project><modules>' + modules + '</modules></project>');
      assert.throws(() => collectJavaFiles(root));
    }
  });
});

test('声明的生产源码为空或必须保留的底座文件缺失时失败', () => {
  fixture(({ root, main }) => {
    rmSync(main);
    assert.throws(() => collectJavaFiles(root), /No production Java files/);
  });
  fixture(({ root }) => {
    rmSync(path.join(root, 'os-module-bpm'), { recursive: true });
    assert.throws(() => collectJavaFiles(root), /Required foundation source is missing/);
  });
});


// 与格式清单一起验证 SQL 门禁，防止全限定注解绕过检查。
test('SQL policy detects qualified and short annotations', async () => {
  const { sqlPolicyViolations } = await import('./check-quality.mjs');
  for (const verb of ['Select', 'Insert', 'Update', 'Delete']) {
    for (const prefix of ['', 'org.apache.ibatis.annotations.']) {
      assert.equal(sqlPolicyViolations('@' + prefix + verb + '("statement")').length, 1);
      assert.equal(sqlPolicyViolations('@' + prefix + verb + 'Provider(type = Factory.class)').length, 1);
    }
  }
  assert.deepEqual(sqlPolicyViolations('@Mapper\npublic interface Records {}'), []);
});
