#!/usr/bin/env node
/**
 * 源码门禁：禁止 SQL 注解及 Provider；保证包归属、接口说明及 Web 依赖方向。
 * 覆盖短名和全限定 SQL 注解及受影响底座目录；此检查不替代 MyBatis 映射回归或业务验收。
 */
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { collectJavaFiles } from './format.mjs';

/** Java 注解允许使用全限定名，不能只扫描 import 后的短名。 */
export function sqlPolicyViolations(source) {
  const violations = [];
  if (/^\s*@(?:org\.apache\.ibatis\.annotations\.)?(?:Select|Insert|Update|Delete)\s*\(/m.test(source))
    violations.push('SQL 必须放在 mapper.xml，不得恢复 SQL 注解');
  if (/@(?:org\.apache\.ibatis\.annotations\.)?(?:Select|Insert|Update|Delete)Provider\s*\(/.test(source))
    violations.push('SQL Provider 已迁入 XML，不得恢复 Java SQL 编译器');
  return violations;
}

/** 只限制已整理的实现模块和新增报表模块；API、枚举、工具及底座的包规则保持各自约定。 */
export function packagePolicyViolations(file, source) {
  const normalized = file.split(path.sep).join('/');
  const module = normalized.match(/\/os-nocode-(runtime|metadata|application|schema|report)\/src\/main\/java\//)?.[1];
  if (!module) return [];
  const violations = [];
  const declared = source.match(/^package\s+([\w.]+)\s*;/m)?.[1];
  const owner = 'com.richuang.os.nocode.' + module;
  if (!declared || !normalized.endsWith('/' + declared.replaceAll('.', '/') + '/' + path.basename(file))) {
    violations.push('Java package 必须与源码目录一致');
  }
  if (declared !== owner && !declared?.startsWith(owner + '.')) {
    violations.push('实现类的包必须带所属模块前缀：' + owner);
  }
  if (path.basename(file) !== 'package-info.java') {
    if (declared === owner && !/@(?:org\.springframework\.context\.annotation\.)?Configuration\b/.test(source)) {
      violations.push('业务类应归入 service.<业务>，不能平铺在模块根包');
    }
    if (declared === owner + '.service' || declared === owner + '.service.impl') {
      violations.push('Service 及协作者按业务归包，禁止 service 根包或全局 service.impl');
    }
  }
  return violations;
}

/** 正式 Java/XML 不得继续引用迁移前的业务根包或未标明模块归属的 DAL。 */
export function legacyPackageViolations(source) {
  return /com\.richuang\.os\.nocode\.(?:dal\.|(?:runtime|metadata|application|schema|report)\.[A-Z]|runtime\.service\.(?:[A-Z]|impl\.))/.test(source)
    ? ['正式源码仍引用旧业务包；同步 Java 导入、XML namespace 和类型名称'] : [];
}

/** FieldOptions 的规范构造器共 19 个组件，最后一个是字段对象规则 rules。 */
const FIELD_OPTIONS_COMPONENTS = 19;

/**
 * 字段对象规则（引用筛选、数据联动、公式默认值、取整方式）挂在 FieldOptions.rules 上。少于 19 个参数的兼容构造器会把 rules
 * 置空：正式代码里用它重建已有字段配置，规则就在保存、发布或转换时被悄悄清掉，编译和既有测试都不会报错。
 * 所以正式代码一律用 FieldOptions.copyOf(...) 复制，或用 19 参规范构造器显式写出 rules；兼容构造器只留给测试夹具。
 */
export function fieldOptionsRulesViolations(source) {
  const violations = [];
  const call = /\bnew\s+(?:DataCenter\s*\.\s*)?FieldOptions\s*\(/g;
  // 先把注释与字面量抹成等长空白，括号和逗号的计数才不会被文字内容干扰，行号也保持不变。
  const code = source.replace(
    /\/\*[\s\S]*?\*\/|\/\/[^\n]*|"""[\s\S]*?"""|"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'/g,
    (text) => text.replace(/[^\n]/g, ' '));
  for (let match = call.exec(code); match; match = call.exec(code)) {
    let depth = 1;
    let commas = 0;
    let empty = true;
    let index = call.lastIndex;
    for (; index < code.length && depth > 0; index++) {
      const char = code[index];
      if ('([{'.includes(char)) depth++;
      else if (')]}'.includes(char)) depth--;
      else if (char === ',' && depth === 1) commas++;
      if (depth > 0 && !/\s/.test(char)) empty = false;
    }
    const count = empty ? 0 : commas + 1;
    if (depth === 0 && count !== FIELD_OPTIONS_COMPONENTS) {
      const line = code.slice(0, match.index).split('\n').length;
      violations.push('第 ' + line + ' 行用 ' + count + ' 参构造 FieldOptions 会丢掉字段对象规则 rules；' +
        '请改用 FieldOptions.copyOf(...)，或用 19 参规范构造器显式写出 rules');
    }
  }
  return violations;
}

function main() {
  const errors = [];
  let fieldOptionsCalls = 0;
  let controllerCount = 0;
  let mappingCount = 0;
  let providerCount = 0;

  for (const file of collectJavaFiles()) {
    const normalized = file.split(path.sep).join('/');
    if (!/\/os-nocode\/os-nocode-[^/]+\/src\/main\/java\//.test(normalized) &&
        !normalized.includes('/mybatis/core/metadata/')) continue;
    const source = readFileSync(file, 'utf8');
    for (const violation of sqlPolicyViolations(source)) errors.push(file + ': ' + violation);
    for (const violation of packagePolicyViolations(file, source)) errors.push(file + ': ' + violation);
    for (const violation of legacyPackageViolations(source)) errors.push(file + ': ' + violation);
    for (const violation of fieldOptionsRulesViolations(source)) errors.push(file + ': ' + violation);
    fieldOptionsCalls += (source.match(/\bnew\s+(?:DataCenter\s*\.\s*)?FieldOptions\s*\(/g) ?? []).length;
    if (/\/os-nocode-(runtime|metadata|application|schema|report)\//.test(normalized) &&
        file.endsWith('ServiceImpl.java') && !existsSync(file.replace(/Impl\.java$/, '.java'))) {
      errors.push(file + ': Service 接口和实现必须位于同一业务包');
    }
    providerCount += (source.match(/@(?:org\.apache\.ibatis\.annotations\.)?(?:Select|Insert|Update|Delete)Provider\s*\(/g) ?? []).length;
    if (!normalized.includes('/os-nocode-web/') &&
        /^import (?:static )?com\.richuang\.os\.nocode\.(?:controller|web)\./m.test(source)) {
      errors.push(file + ': 非 Web 模块不得反向依赖 Web 实现');
    }
    if (!/@RestController\b/.test(source)) continue;
    controllerCount++;
    if (!/@Tag\s*\(/.test(source)) errors.push(file + ': 缺少接口分组说明');
    if (/^import com\.richuang\.os\.nocode\.[^;]*\.dal\.[^;]*Mapper;/m.test(source)) {
      errors.push(file + ': Controller 不得依赖业务 Mapper');
    }
    const mappings = [...source.matchAll(/^\s*@(?:Get|Post|Put|Delete|Patch)Mapping\([^\n]+\)/gm)];
    mappingCount += mappings.length;
    for (const mapping of mappings) {
      const declaration = source.slice(mapping.index + mapping[0].length).split(/\bpublic\s/)[0];
      if (!/@Operation\s*\(/.test(declaration)) errors.push(file + ': ' + mapping[0].trim() + ' 缺少操作说明');
    }
  }
  const root = path.dirname(fileURLToPath(import.meta.url));
  for (const module of readdirSync(root, { withFileTypes: true })) {
    if (!module.isDirectory() || !module.name.startsWith('os-nocode-')) continue;
    const resources = path.join(root, module.name, 'src/main/resources');
    if (!existsSync(resources)) continue;
    for (const file of readdirSync(resources, { recursive: true })) {
      if (!file.endsWith('.xml')) continue;
      const absolute = path.join(resources, file);
      for (const violation of legacyPackageViolations(readFileSync(absolute, 'utf8'))) {
        errors.push(absolute + ': ' + violation);
      }
    }
  }
  if (controllerCount === 0 || mappingCount === 0) errors.push('未扫描到正式 Controller，拒绝空范围通过。');
  // 规范构造器至少在 FieldOptions 自身的 with*/build 里出现；一处都扫不到说明扫描范围空了，不能当作通过。
  if (fieldOptionsCalls === 0) errors.push('未扫描到任何 FieldOptions 构造调用，拒绝空范围通过。');
  if (errors.length) {
    console.error(errors.join('\n'));
    process.exitCode = 1;
  } else {
    console.log('后端规范检查通过：' + controllerCount + ' 个 Controller、' + mappingCount +
      ' 个接口声明；包归属及 XML 引用有效；无直接 SQL 注解，SQL Provider 为 ' + providerCount +
      '；FieldOptions 构造 ' + fieldOptionsCalls + ' 处均显式带 rules。');
  }

}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
