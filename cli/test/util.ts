import { exec } from 'child_process';
import { mkdir, mkdirp, readFile, pathExists, writeFile } from 'fs-extra';
import { join, resolve } from 'path';
import tmp from 'tmp';
import type { DirCallback } from 'tmp';

import { loadConfig } from '../src/config';
import type { Config } from '../src/definitions';
import { runCommand } from '../src/util/subprocess';

const cwd = process.cwd();

/**
 * Returns the value, failing the test with a clear message when it is null or undefined. Use it
 * instead of a non-null assertion when a test goes on to use a value it expects to exist.
 */
export function defined<T>(value: T | null | undefined, what = 'value'): T {
  if (value === null || value === undefined) {
    throw new Error(`expected ${what} to be defined`);
  }
  return value;
}

export const CAPACITOR_PLUGIN_ID = 'cool-capacitor-plugin';
export const LEGACY_CORDOVA_PLUGIN_ID = 'cool-cordova-plugin';
export const APP_ID = 'com.getcapacitor.cli.test';
export const APP_NAME = 'Capacitor CLI Test';

export async function makeConfig(appRoot: string): Promise<Config> {
  process.chdir(appRoot);
  const config = await loadConfig();
  process.chdir(cwd);
  return config;
}

export async function run(appRoot: string, capCommand: string): Promise<string> {
  return new Promise((resolve, reject) => {
    exec(`cd "${appRoot}" && "${cwd}/bin/capacitor" ${capCommand}`, (error, stdout, stderr) => {
      if (error) {
        reject(stdout + stderr);
      } else {
        resolve(stdout);
      }
    });
  });
}

export function mktmp(): Promise<{
  cleanupCallback: DirCallback;
  path: string;
}> {
  return new Promise((resolve) => {
    tmp.dir((err, path, cleanupCallback) => {
      if (err) {
        throw err;
      }

      resolve({
        cleanupCallback,
        path,
      });
    });
  });
}

const APP_INDEX = `
<!DOCTYPE html>
<html lang="en" dir="ltr">
<head>
  <meta charset="UTF-8">
  <title>Test Capacitor App</title>
</head>
<body>
  <capacitor-welcome></capacitor-welcome>
</body>
</html>
`;

export async function installPlatform(appDir: string, platform: string): Promise<void> {
  const platformPath = resolve(cwd, '..', platform);
  await runCommand('npm', ['install', platformPath], { cwd: appDir });
}

export async function makeAppDir(monoRepoLike = false): Promise<void> {
  const appDirObj: any = await mktmp();
  const tmpDir = appDirObj.path;
  const rootDir = monoRepoLike ? join(tmpDir, 'test-root') : join(tmpDir, 'test-app');
  if (monoRepoLike) {
    await mkdir(rootDir);
  }
  const capacitorPluginPath = join(tmpDir, CAPACITOR_PLUGIN_ID);
  const legacyCordovaPluginPath = join(tmpDir, LEGACY_CORDOVA_PLUGIN_ID);
  const APP_PACKAGE_JSON = `
{
  "name": "test-app",
  "dependencies": {
    "${CAPACITOR_PLUGIN_ID}": "file:${capacitorPluginPath}",
    "${LEGACY_CORDOVA_PLUGIN_ID}": "file:${legacyCordovaPluginPath}"
  }
}
`;
  const appDir = monoRepoLike ? join(rootDir, 'test-app') : rootDir;
  await mkdir(appDir);
  // Make the web dir
  await mkdir(join(appDir, 'www'));
  // Make a fake index.html
  await writeFile(join(appDir, 'www', 'index.html'), APP_INDEX);
  // Make a fake package.json
  await writeFile(join(appDir, 'package.json'), APP_PACKAGE_JSON);

  // We use 'npm install' to install @capacitor/core and @capacitor/cli
  // Otherwise later use of 'npm install --save @capacitor/android|ios' will wipe 'node_modules/@capacitor/'
  const corePath = resolve(cwd, '../core');
  const cliPath = resolve(cwd, '../cli');
  await runCommand('npm', ['install', '--save', corePath, cliPath], {
    cwd: rootDir,
  });

  // Make a fake Capacitor plugin, and a fake Cordova plugin that has to be skipped
  await makeCapacitorPlugin(capacitorPluginPath);
  await makeLegacyCordovaPlugin(legacyCordovaPluginPath);

  await runCommand('npm', ['install', '--save', capacitorPluginPath, legacyCordovaPluginPath], {
    cwd: rootDir,
  });

  return {
    ...appDirObj,
    appDir,
  };
}

const CAPACITOR_PLUGIN_PACKAGE = `
{
  "name": "${CAPACITOR_PLUGIN_ID}",
  "version": "1.0.0",
  "description": "Cool Capacitor plugin",
  "capacitor": {
    "ios": {
      "src": "ios"
    },
    "android": {
      "src": "android"
    }
  },
  "author": "Max",
  "license": "MIT"
}
`;

const CAPACITOR_PLUGIN_JAVA = `package com.getcapacitor.cool;

import com.getcapacitor.Plugin;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "Cool")
public class CoolPlugin extends Plugin {}
`;

const CAPACITOR_PLUGIN_SWIFT = `import Capacitor

@objc(CoolPlugin)
public class CoolPlugin: CAPPlugin {}
`;

const CAPACITOR_PLUGIN_PACKAGE_SWIFT = `// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CoolCapacitorPlugin",
    platforms: [.iOS(.v17)],
    products: [
        .library(
            name: "CoolCapacitorPlugin",
            targets: ["CoolPlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0")
    ],
    targets: [
        .target(
            name: "CoolPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm")
            ],
            path: "ios/Sources/CoolPlugin")
    ]
)
`;

async function makeCapacitorPlugin(pluginPath: string) {
  const iosPath = join(pluginPath, 'ios/Sources/CoolPlugin');
  const androidPath = join(pluginPath, 'android/src/main/java/com/getcapacitor/cool');
  await mkdirp(pluginPath);
  await writeFile(join(pluginPath, 'package.json'), CAPACITOR_PLUGIN_PACKAGE);
  await writeFile(join(pluginPath, 'Package.swift'), CAPACITOR_PLUGIN_PACKAGE_SWIFT);
  await mkdirp(iosPath);
  await mkdirp(androidPath);
  await writeFile(join(iosPath, 'CoolPlugin.swift'), CAPACITOR_PLUGIN_SWIFT);
  await writeFile(join(androidPath, 'CoolPlugin.java'), CAPACITOR_PLUGIN_JAVA);
}

const LEGACY_CORDOVA_PLUGIN_XML = `
<?xml version="1.0" encoding="UTF-8"?>
<plugin xmlns="http://apache.org/cordova/ns/plugins/1.0" id="${LEGACY_CORDOVA_PLUGIN_ID}" version="1.0.0">
    <name>Cool Cordova Plugin</name>
    <platform name="android"></platform>
    <platform name="ios"></platform>
</plugin>
`;

const LEGACY_CORDOVA_PLUGIN_PACKAGE = `
{
  "name": "${LEGACY_CORDOVA_PLUGIN_ID}",
  "version": "1.0.0",
  "description": "Cool Cordova plugin",
  "author": "Max",
  "license": "MIT"
}
`;

/**
 * A package that only has a \`plugin.xml\`: Cordova plugins are not supported,
 * so the CLI has to warn about it and leave it out of the native projects.
 */
async function makeLegacyCordovaPlugin(pluginPath: string) {
  await mkdirp(pluginPath);
  await writeFile(join(pluginPath, 'plugin.xml'), LEGACY_CORDOVA_PLUGIN_XML);
  await writeFile(join(pluginPath, 'package.json'), LEGACY_CORDOVA_PLUGIN_PACKAGE);
}

class MappedFS {
  private rootDir: string;
  constructor(rootDir: string) {
    this.rootDir = rootDir;
  }
  async read(path: string): Promise<string> {
    return await readFile(resolve(this.rootDir, path), { encoding: 'utf-8' });
  }
  async exists(path: string): Promise<boolean> {
    return await pathExists(resolve(this.rootDir, path));
  }
}

export { MappedFS };
