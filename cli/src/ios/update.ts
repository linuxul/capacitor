import { remove, pathExists, readFile, realpath, writeFile } from 'fs-extra';
import { basename, dirname, join, relative } from 'path';
import { major, prerelease } from 'semver';

import c from '../colors';
import { checkPlatformVersions, getCapacitorPackageVersion, runTask, writeLegacyCordovaStubs } from '../common';
import type { Config } from '../definitions';
import { fatal } from '../errors';
import { logger } from '../log';
import type { Plugin } from '../plugin';
import { PluginType, getPluginType, getPlugins, printPlugins } from '../plugin';
import { copy as copyTask } from '../tasks/copy';
import { setAllStringIn } from '../tasks/migrate';
import { convertToUnixPath } from '../util/fs';
import { generateIOSPackageJSON } from '../util/iosplugin';
import { resolveNode } from '../util/node';
import { generatePackageFile, checkPluginsForPackageSwift } from '../util/spm';
import { runCommand, isInstalled } from '../util/subprocess';

import { getIOSPlugins } from './common';

const platform = 'ios';

/**
 * Directory that used to hold the native code of Cordova plugins.
 * Cordova is not supported anymore, this is only used to clean up older apps.
 */
const legacyCordovaPluginsDir = 'capacitor-cordova-ios-plugins';

export async function updateIOS(config: Config, deployment: boolean): Promise<void> {
  const plugins = await getPluginsTask(config);

  const capacitorPlugins = plugins.filter((p) => getPluginType(p, platform) === PluginType.Core);
  await updatePluginFiles(config, plugins, deployment);
  await checkPlatformVersions(config, platform);
  generateIOSPackageJSON(config, plugins);

  printPlugins(capacitorPlugins, 'ios');
}

async function updatePluginFiles(config: Config, plugins: Plugin[], deployment: boolean) {
  await removePluginsNativeFiles(config);
  await checkLegacyConfigXMLReference(config);
  if (!(await pathExists(await config.ios.webDirAbs))) {
    await copyTask(config, platform);
  }
  await writeLegacyCordovaStubs(await config.ios.webDirAbs);
  if ((await config.ios.packageManager) === 'SPM') {
    const validSPMPackages = await checkPluginsForPackageSwift(config, plugins);
    await Promise.all(
      validSPMPackages.map(async (plugin) => {
        const iosPlatformVersion = await getCapacitorPackageVersion(config, config.ios.name);
        const packageSwiftPath = join(plugin.rootPath, 'Package.swift');
        let content = await readFile(packageSwiftPath, { encoding: 'utf-8' });
        const regex = new RegExp(
          'url:\\s*"https://github.com/ionic-team/capacitor-swift-pm\\.git",\\s*from:\\s*"([^"]+)"',
        );
        const version = content.match(regex)?.[1];
        const majorCapVersion = major(iosPlatformVersion);
        if (version && major(version) != majorCapVersion) {
          const preCapVersion = prerelease(iosPlatformVersion);
          const forceVersion = preCapVersion ? iosPlatformVersion : `${majorCapVersion}.0.0`;
          content = setAllStringIn(
            content,
            `url: "https://github.com/ionic-team/capacitor-swift-pm.git",`,
            `)`,
            ` from: "${forceVersion}"`,
          );
          await writeFile(packageSwiftPath, content);
          logger.warn(`${plugin.id} is built for Capacitor ${major(version)}, it might cause issues`);
        }
      }),
    );

    await generatePackageFile(config, validSPMPackages);
  } else {
    await installCocoaPodsPlugins(config, plugins, deployment);
  }

  const incompatibleCordovaPlugins = plugins.filter((p) => getPluginType(p, platform) === PluginType.Incompatible);
  printPlugins(incompatibleCordovaPlugins, platform, 'incompatible');
}

export async function installCocoaPodsPlugins(config: Config, plugins: Plugin[], deployment: boolean): Promise<void> {
  await runTask(`Updating iOS native dependencies with ${c.input(`${await config.ios.podPath} install`)}`, () => {
    return updatePodfile(config, plugins, deployment);
  });
}

async function updatePodfile(config: Config, plugins: Plugin[], deployment: boolean): Promise<void> {
  const dependenciesContent = await generatePodFile(config, plugins);
  const relativeCapacitoriOSPath = await getRelativeCapacitoriOSPath(config);
  const podfilePath = join(config.ios.nativeProjectDirAbs, 'Podfile');
  let podfileContent = await readFile(podfilePath, { encoding: 'utf-8' });
  podfileContent = podfileContent.replace(/(def capacitor_pods)[\s\S]+?(\nend)/, `$1${dependenciesContent}$2`);
  podfileContent = podfileContent.replace(
    /(require_relative)[\s\S]+?(@capacitor\/ios\/scripts\/pods_helpers')/,
    `require_relative '${relativeCapacitoriOSPath}/scripts/pods_helpers'`,
  );
  await writeFile(podfilePath, podfileContent, { encoding: 'utf-8' });

  const podPath = await config.ios.podPath;
  const useBundler = (await config.ios.packageManager) === 'bundler';
  if (useBundler) {
    await runCommand('bundle', ['exec', 'pod', 'install', ...(deployment ? ['--deployment'] : [])], {
      cwd: config.ios.nativeProjectDirAbs,
    });
  } else if (await isInstalled('pod')) {
    await runCommand(podPath, ['install', ...(deployment ? ['--deployment'] : [])], {
      cwd: config.ios.nativeProjectDirAbs,
    });
  } else {
    logger.warn('Skipping pod install because CocoaPods is not installed');
  }

  const isXcodebuildAvailable = await isInstalled('xcodebuild');
  if (isXcodebuildAvailable) {
    await runCommand('xcodebuild', ['-project', basename(`${config.ios.nativeXcodeProjDirAbs}`), 'clean'], {
      cwd: config.ios.nativeProjectDirAbs,
    });
  } else {
    logger.warn('Unable to find "xcodebuild". Skipping xcodebuild clean step...');
  }
}

async function getRelativeCapacitoriOSPath(config: Config) {
  const capacitoriOSPath = resolveNode(config.app.rootDir, '@capacitor/ios', 'package.json');

  if (!capacitoriOSPath) {
    fatal(
      `Unable to find ${c.strong('node_modules/@capacitor/ios')}.\n` +
        `Are you sure ${c.strong('@capacitor/ios')} is installed?`,
    );
  }

  return convertToUnixPath(relative(config.ios.nativeProjectDirAbs, await realpath(dirname(capacitoriOSPath))));
}

async function generatePodFile(config: Config, plugins: Plugin[]): Promise<string> {
  const relativeCapacitoriOSPath = await getRelativeCapacitoriOSPath(config);

  const capacitorPlugins = plugins.filter((p) => getPluginType(p, platform) === PluginType.Core);
  const pods = await Promise.all(
    capacitorPlugins.map(async (p) => {
      if (!p.ios) {
        return '';
      }

      return `  pod '${p.ios.name}', :path => '${convertToUnixPath(
        relative(config.ios.nativeProjectDirAbs, await realpath(p.rootPath)),
      )}'\n`;
    }),
  );
  return `
  pod 'Capacitor', :path => '${relativeCapacitoriOSPath}'
${pods.join('').trimRight()}`;
}

/**
 * Apps created before Cordova support was removed have a generated `config.xml`
 * in their Xcode project resources. It isn't generated anymore, so if the file is
 * missing (it used to be git ignored) Xcode fails to build until the reference is removed.
 */
async function checkLegacyConfigXMLReference(config: Config) {
  const pbxPath = join(config.ios.nativeXcodeProjDirAbs, 'project.pbxproj');
  if (!(await pathExists(pbxPath)) || (await pathExists(join(config.ios.nativeTargetDirAbs, 'config.xml')))) {
    return;
  }
  const pbxContent = await readFile(pbxPath, { encoding: 'utf-8' });
  if (pbxContent.includes('config.xml in Resources')) {
    logger.warn(
      `The Xcode project still references ${c.strong('config.xml')}, which is not generated anymore because Cordova is not supported in this fork.\n` +
        `Remove ${c.strong('config.xml')} from the App target in Xcode, otherwise the build will fail.`,
    );
  }
}

async function removePluginsNativeFiles(config: Config) {
  await remove(join(config.ios.platformDirAbs, legacyCordovaPluginsDir));
  if ((await config.ios.packageManager) === 'SPM') {
    await remove(join(config.ios.nativeProjectDirAbs, 'CapApp-SPM', 'symlinks'));
  }
}

async function getPluginsTask(config: Config) {
  return await runTask('Updating iOS plugins', async () => {
    const allPlugins = await getPlugins(config, 'ios');
    const iosPlugins = await getIOSPlugins(allPlugins);
    return iosPlugins;
  });
}
