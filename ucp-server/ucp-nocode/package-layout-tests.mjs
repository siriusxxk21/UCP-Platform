import { test } from 'node:test';
import assert from 'node:assert/strict';
import { packagePolicyViolations, legacyPackageViolations, fieldOptionsRulesViolations } from './check-quality.mjs';

const owner = 'com.lingan.ucp.nocode.runtime';
const file = (pkg, name = 'Example.java', module = 'runtime') =>
  '/project/ucp-nocode/ucp-nocode-' + module + '/src/main/java/' + pkg.replaceAll('.', '/') + '/' + name;

test('报表模块执行同样的业务分包与模块归属规则', () => {
  const pkg = 'com.lingan.ucp.nocode.report.service.dataset';
  assert.deepEqual(packagePolicyViolations(file(pkg, 'Example.java', 'report'), 'package ' + pkg + ';'), []);
  for (const invalid of ['com.lingan.ucp.nocode.report.service', owner + '.service.record']) {
    assert.notEqual(packagePolicyViolations(file(invalid, 'Example.java', 'report'), 'package ' + invalid + ';').length, 0);
  }
});

test('业务类不能回到模块根包、service 根包或全局 impl 包', () => {
  for (const suffix of ['', '.service', '.service.impl']) {
    const pkg = owner + suffix;
    assert.notEqual(packagePolicyViolations(file(pkg), 'package ' + pkg + ';\npublic class Example {}').length, 0);
  }
});

test('业务包、包说明和根包配置可以通过，不将规则扩大到 API 模块', () => {
  const pkg = owner + '.service.record';
  assert.deepEqual(packagePolicyViolations(file(pkg), 'package ' + pkg + ';\npublic class Example {}'), []);
  assert.deepEqual(packagePolicyViolations(file(owner, 'package-info.java'), 'package ' + owner + ';'), []);
  assert.deepEqual(packagePolicyViolations(file(owner), 'package ' + owner + ';\n@Configuration\nclass Example {}'), []);
  assert.deepEqual(packagePolicyViolations(file('com.lingan.ucp.nocode.api', 'Example.java', 'api'), 'package com.lingan.ucp.nocode.api;'), []);
});

test('目录与声明不一致、跨模块放置或 metadata 遗漏前缀均失败', () => {
  assert.notEqual(packagePolicyViolations(file(owner + '.service.record'), 'package ' + owner + '.service.view;').length, 0);
  for (const pkg of ['com.lingan.ucp.nocode.dal.mapper', owner + '.dal.mapper']) {
    assert.notEqual(packagePolicyViolations(file(pkg, 'Example.java', 'metadata'), 'package ' + pkg + ';').length, 0);
  }
});

test('同时阻止 Java 和 MyBatis XML 中的旧名称，保留当前 DAL/API 名称', () => {
  for (const old of ['com.lingan.ucp.nocode.dal.mapper.DataCenterMapper', owner + '.RecordService', owner + '.service.RecordHistoryService', owner + '.service.impl.RecordHistoryServiceImpl']) {
    assert.equal(legacyPackageViolations('import ' + old + ';').length, 1);
    assert.equal(legacyPackageViolations('<mapper namespace="' + old + '">').length, 1);
  }
  assert.deepEqual(legacyPackageViolations('<mapper namespace="com.lingan.ucp.nocode.metadata.dal.mapper.DataCenterMapper">'), []);
  assert.deepEqual(legacyPackageViolations('import com.lingan.ucp.nocode.metadata.api.DataTables;'), []);
});

test('正式代码重建 FieldOptions 必须带上字段对象规则：少于 19 参的兼容构造器一律拦下', () => {
  const args = (count) => Array.from({ length: count }, (_, index) => 'a' + index).join(', ');
  // 规范构造器、copyOf 复制与嵌套调用里的逗号都不误报。
  assert.deepEqual(fieldOptionsRulesViolations('var o = new FieldOptions(' + args(19) + ');'), []);
  assert.deepEqual(fieldOptionsRulesViolations('var o = FieldOptions.copyOf(old).state(next).build();'), []);
  assert.deepEqual(
    fieldOptionsRulesViolations('new DataCenter.FieldOptions(' + args(17) + ', pick(a, b), List.of(x, y))'), []);
  // 15/16/17/18 参分别对应历次兼容构造器，全部会把 rules 置空。
  for (const count of [15, 16, 17, 18]) {
    const found = fieldOptionsRulesViolations('class A {\n  Object o = new FieldOptions(' + args(count) + ');\n}');
    assert.equal(found.length, 1);
    assert.match(found[0], new RegExp('第 2 行用 ' + count + ' 参构造'));
  }
  // 全限定写法、换行写法同样拦下；同一文件多处逐处报告。
  assert.equal(fieldOptionsRulesViolations(
    'new DataCenter\n    .FieldOptions(' + args(18) + ');\nnew FieldOptions(\n' + args(16) + ')').length, 2);
  // 注释与字符串里的文字不算调用，其中的逗号和括号也不影响真实调用的参数计数。
  assert.deepEqual(fieldOptionsRulesViolations('// new FieldOptions(a, b)\nString s = "new FieldOptions(a)";'), []);
  assert.deepEqual(fieldOptionsRulesViolations(
    'new FieldOptions(' + args(17) + ', "x, y)", /* p, q */ z)'), []);
});
