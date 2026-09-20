import Debug from 'debug';
import { remove, pathExists, readFile, writeFile, writeJSON } from 'fs-extra';
import { dirname, extname, join, relative, resolve } from 'path';

import c from '../colors';
import { checkPlatformVersions, runTask } from '../common';
import type { Config } from '../definitions';
import { fatal } from '../errors';
import { PluginType, getPluginType, getPlugins, printPlugins } from '../plugin';
import type { Plugin } from '../plugin';
import { copy as copyTask } from '../tasks/copy';
import { readdirp, convertToUnixPath } from '../util/fs';
import { resolveNode } from '../util/node';

import { getAndroidPlugins } from './common';

const platform = 'android';
const debug = Debug('capacitor:android:update');

/**
 * Gradle project that used to hold the native code of Cordova plugins.
 * Cordova is not supported anymore, this is only used to clean up older apps.
 */
const legacyCordovaPluginsDir = 'capacitor-cordova-android-plugins';

export async function updateAndroid(config: Config): Promise<void> {
  const plugins = await getPluginsTask(config);

  const capacitorPlugins = plugins.filter((p) => getPluginType(p, platform) === PluginType.Core);

  printPlugins(capacitorPlugins, 'android');

  await checkLegacyCordovaGradleReferences(config);
  await writePluginsJson(config, capacitorPlugins);
  await removePluginsNativeFiles(config);
  if (!(await pathExists(config.android.webDirAbs))) {
    await copyTask(config, platform);
  }
  await installGradlePlugins(config, capacitorPlugins);

  const incompatibleCordovaPlugins = plugins.filter((p) => getPluginType(p, platform) === PluginType.Incompatible);
  printPlugins(incompatibleCordovaPlugins, platform, 'incompatible');
  await checkPlatformVersions(config, platform);
}

function getGradlePackageName(id: string): string {
  return id.replace('@', '').replace('/', '-');
}

interface PluginsJsonEntry {
  pkg: string;
  classpath: string;
}

async function writePluginsJson(config: Config, plugins: Plugin[]): Promise<void> {
  const classes = await findAndroidPluginClasses(plugins);
  const pluginsJsonPath = resolve(config.android.assetsDirAbs, 'capacitor.plugins.json');

  await writeJSON(pluginsJsonPath, classes, { spaces: '\t' });
}

async function findAndroidPluginClasses(plugins: Plugin[]): Promise<PluginsJsonEntry[]> {
  const entries: PluginsJsonEntry[] = [];

  for (const plugin of plugins) {
    entries.push(...(await findAndroidPluginClassesInPlugin(plugin)));
  }

  return entries;
}

async function findAndroidPluginClassesInPlugin(plugin: Plugin): Promise<PluginsJsonEntry[]> {
  if (!plugin.android || getPluginType(plugin, platform) !== PluginType.Core) {
    return [];
  }

  const srcPath = resolve(plugin.rootPath, plugin.android.path, 'src/main');
  const srcFiles = await readdirp(srcPath, {
    filter: (entry) => !entry.stats.isDirectory() && ['.java', '.kt'].includes(extname(entry.path)),
  });

  const classRegex = /^@CapacitorPlugin[\s\S]+?class ([\w]+)/m;
  // Kotlin escapes a package segment that is a hard keyword with backticks, the way
  // toKotlinPackageName() writes MainActivity's. They are source syntax only: the JVM
  // name, and so the classpath PluginManager loads, has none.
  const packageRegex = /^package\s+([\w.`]+);?$/m;

  debug('Searching %O source files in %O by %O regex', srcFiles.length, srcPath, classRegex);

  const entries = await Promise.all(
    srcFiles.map(async (srcFile): Promise<PluginsJsonEntry | undefined> => {
      const srcFileContents = await readFile(srcFile, { encoding: 'utf-8' });
      const classMatch = classRegex.exec(srcFileContents);

      if (classMatch) {
        const className = classMatch[1];

        debug('Searching %O for package by %O regex', srcFile, packageRegex);

        const packageMatch = packageRegex.exec(srcFileContents.substring(0, classMatch.index));

        if (!packageMatch) {
          fatal(`Package could not be parsed from Android plugin.\n` + `Location: ${c.strong(srcFile)}`);
        }

        const packageName = packageMatch[1].replace(/`/g, '');
        const classpath = `${packageName}.${className}`;

        debug('%O is a suitable plugin class', classpath);

        return {
          pkg: plugin.id,
          classpath,
        };
      }
    }),
  );

  return entries.filter((entry): entry is PluginsJsonEntry => !!entry);
}

export async function installGradlePlugins(config: Config, capacitorPlugins: Plugin[]): Promise<void> {
  const capacitorAndroidPackagePath = resolveNode(config.app.rootDir, '@capacitor/android', 'package.json');
  if (!capacitorAndroidPackagePath) {
    fatal(
      `Unable to find ${c.strong('node_modules/@capacitor/android')}.\n` +
        `Are you sure ${c.strong('@capacitor/android')} is installed?`,
    );
  }

  const capacitorAndroidPath = resolve(dirname(capacitorAndroidPackagePath), 'capacitor');

  const settingsPath = config.android.platformDirAbs;
  const dependencyPath = config.android.appDirAbs;
  const relativeCapcitorAndroidPath = convertToUnixPath(relative(settingsPath, capacitorAndroidPath));
  const settingsLines = `// DO NOT EDIT THIS FILE! IT IS GENERATED EACH TIME "capacitor update" IS RUN
include ':capacitor-android'
project(':capacitor-android').projectDir = new File('${relativeCapcitorAndroidPath}')
${capacitorPlugins
  .map((p) => {
    if (!p.android) {
      return '';
    }

    const relativePluginPath = convertToUnixPath(relative(settingsPath, p.rootPath));

    return `
include ':${getGradlePackageName(p.id)}'
project(':${getGradlePackageName(p.id)}').projectDir = new File('${relativePluginPath}/${p.android.path}')
`;
  })
  .join('')}`;

  const dependencyLines = `// DO NOT EDIT THIS FILE! IT IS GENERATED EACH TIME "capacitor update" IS RUN

android {
  compileOptions {
      sourceCompatibility JavaVersion.VERSION_21
      targetCompatibility JavaVersion.VERSION_21
  }
}

dependencies {
${capacitorPlugins
  .map((p) => {
    return `    implementation project(':${getGradlePackageName(p.id)}')`;
  })
  .join('\n')}
}

if (hasProperty('postBuildExtras')) {
  postBuildExtras()
}
`;

  await writeFile(join(settingsPath, 'capacitor.settings.gradle'), settingsLines);
  await writeFile(join(dependencyPath, 'capacitor.build.gradle'), dependencyLines);
}

/**
 * Apps created before Cordova support was removed still reference the
 * `capacitor-cordova-android-plugins` Gradle project, which doesn't exist anymore.
 * Fail early with instructions instead of letting Gradle fail with a cryptic error.
 */
async function checkLegacyCordovaGradleReferences(config: Config): Promise<void> {
  const gradleFiles = [
    join(config.android.platformDirAbs, 'settings.gradle'),
    join(config.android.appDirAbs, 'build.gradle'),
  ];
  const offenders: string[] = [];

  for (const gradleFile of gradleFiles) {
    if (!(await pathExists(gradleFile))) {
      continue;
    }
    const lines = (await readFile(gradleFile, { encoding: 'utf-8' })).split(/\r?\n/);
    const found = lines.filter((line) => line.includes(legacyCordovaPluginsDir)).map((line) => `    ${line.trim()}`);
    if (found.length > 0) {
      offenders.push(`${c.strong(convertToUnixPath(relative(config.app.rootDir, gradleFile)))}:\n${found.join('\n')}`);
    }
  }

  if (offenders.length > 0) {
    fatal(
      `Cordova is not supported in this fork, but the Android project still references ${c.strong(
        legacyCordovaPluginsDir,
      )}.\n` +
        `Remove the following lines from the Android project, then run the command again:\n\n` +
        offenders.join('\n\n'),
    );
  }
}

async function removePluginsNativeFiles(config: Config) {
  await remove(join(config.android.platformDirAbs, legacyCordovaPluginsDir));
}

async function getPluginsTask(config: Config) {
  return await runTask('Updating Android plugins', async () => {
    const allPlugins = await getPlugins(config, 'android');
    const androidPlugins = await getAndroidPlugins(allPlugins);
    return androidPlugins;
  });
}
