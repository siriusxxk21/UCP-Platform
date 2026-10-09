#!/usr/bin/env node
/**
 * 无代码 Java 格式入口。只遍历 POM 声明模块的正式源码，不递归扫描 .work 或 target。
 * macOS/Linux 使用 node format.mjs；PowerShell 入口调用本文件，保持同一份清单规则。
 */
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const moduleRoot = path.dirname(fileURLToPath(import.meta.url));
const defaultServerRoot = path.dirname(moduleRoot);

// 延续原脚本的底座扩展范围，避免格式整理蔓延到其他历史代码。
const foundationFiles = [
  'ucp-framework/ucp-spring-boot-starter-mybatis/src/main/java/com/lingan/ucp/framework/mybatis/config/OsDatabaseMetadataAutoConfiguration.java',
  'ucp-framework/ucp-spring-boot-starter-excel/src/main/java/com/lingan/ucp/framework/excel/core/util/ExcelUtils.java',
  'ucp-module-bpm/ucp-module-bpm-api/src/main/java/com/lingan/ucp/module/bpm/api/definition/dto/BpmProcessDefinitionDTO.java',
  'ucp-module-bpm/ucp-module-bpm-api/src/main/java/com/lingan/ucp/module/bpm/api/definition/BpmUserGroupApi.java',
  'ucp-module-bpm/ucp-module-bpm-core/src/main/java/com/lingan/ucp/module/bpm/api/definition/BpmUserGroupApiImpl.java',
  'ucp-module-bpm/ucp-module-bpm-core/src/main/java/com/lingan/ucp/module/bpm/api/definition/BpmProcessDefinitionApiImpl.java',
];
const metadataDirectory =
  'ucp-framework/ucp-spring-boot-starter-mybatis/src/main/java/com/lingan/ucp/framework/mybatis/core/metadata';

function javaFiles(directory) {
  if (!existsSync(directory)) throw new Error('Java source directory is missing: ' + directory);
  const result = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const target = path.join(directory, entry.name);
    // 不跟随软链接，也不接纳源码目录中的临时/构建副本。
    if (entry.isDirectory() && !['.work', 'target', 'node_modules', '.git'].includes(entry.name)) {
      result.push(...javaFiles(target));
    } else if (entry.isFile() && entry.name.endsWith('.java')) {
      result.push(target);
    }
  }
  return result;
}

/** 清单供 --list、格式检查与工具回归共用。每个声明模块必须具有有效的生产源码。 */
export function collectJavaFiles(serverRoot = defaultServerRoot) {
  const nocodeRoot = path.join(serverRoot, 'ucp-nocode');
  const pom = readFileSync(path.join(nocodeRoot, 'pom.xml'), 'utf8').replace(/<!--[\s\S]*?-->/g, '');
  const modulesSection = pom.match(/<modules>([\s\S]*?)<\/modules>/)?.[1];
  const modules = [...(modulesSection ?? '').matchAll(/<module>\s*([^<]+?)\s*<\/module>/g)]
    .map((match) => match[1]);
  if (!modules.length || new Set(modules).size !== modules.length) {
    throw new Error('The nocode POM must declare a non-empty, unique module list.');
  }
  const files = [];
  for (const module of modules) {
    if (!/^ucp-nocode-[a-z0-9-]+$/.test(module)) throw new Error('Invalid nocode module: ' + module);
    const root = path.join(nocodeRoot, module);
    const main = javaFiles(path.join(root, 'src/main/java'));
    if (!main.length) throw new Error('No production Java files in module: ' + module);
    files.push(...main);
    const tests = path.join(root, 'src/test/java');
    if (existsSync(tests)) files.push(...javaFiles(tests));
  }
  files.push(...javaFiles(path.join(serverRoot, 'ucp-server/src/main/java')));
  files.push(...javaFiles(path.join(serverRoot, metadataDirectory)));
  for (const file of foundationFiles) {
    const absolute = path.join(serverRoot, file);
    if (!existsSync(absolute)) throw new Error('Required foundation source is missing: ' + file);
    files.push(absolute);
  }
  return [...new Set(files)].sort();
}

function main(args) {
  if (args.some((arg) => !['--check', '--list'].includes(arg))) {
    throw new Error('Usage: node format.mjs [--check | --list]');
  }
  const files = collectJavaFiles();
  if (args.includes('--list')) {
    console.log(files.join('\n'));
    return;
  }
  const jar = path.join(moduleRoot, '.work/formatter/google-java-format-1.24.0-all-deps.jar');
  if (!existsSync(jar)) {
    throw new Error(
      'Formatter is missing. In ucp-server run: mvn -B -N dependency:copy ' +
      '-Dartifact=com.google.googlejavaformat:google-java-format:1.24.0:jar:all-deps ' +
      '-DoutputDirectory=ucp-nocode/.work/formatter',
    );
  }
  const check = args.includes('--check');
  // 分批调用避免 Windows 命令行长度限制。参数数组保留含空格/中文路径，不经过 shell。
  for (let offset = 0; offset < files.length; offset += 20) {
    const result = spawnSync(
      'java',
      ['-jar', jar, '--aosp', ...(check ? ['--dry-run', '--set-exit-if-changed'] : ['--replace']),
        ...files.slice(offset, offset + 20)],
      { stdio: 'inherit' },
    );
    if (result.error) throw result.error;
    if (result.status !== 0) {
      process.exitCode = result.status ?? 1;
      if (!check) return;
    }
  }
  console.log('Java formatting ' + (check ? 'checked' : 'applied') + ': ' + files.length + ' files.');
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main(process.argv.slice(2));
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}

