import { writeFileSync, readFileSync, existsSync } from 'fs-extra';
import { join } from 'path';
import { rimraf } from 'rimraf';
import { coerce, gte, lt, valid, validRange } from 'semver';

import { cleanupLegacyCordovaAndroid } from '../android/cordova-cleanup';
import c from '../colors';
import { getCoreVersion, runTask, checkJDKMajorVersion } from '../common';
import type { Config } from '../definitions';
import { fatal } from '../errors';
import { getMajoriOSVersion } from '../ios/common';
import { logger, logPrompt, logSuccess } from '../log';
import { isPermissionError, runCommand } from '../util/subprocess';
import { withExtractedTemplate } from '../util/template';

import { migrateToUIScene } from './migrate-uiscene';

// eslint-disable-next-line prefer-const
let allDependencies: { [key: string]: any } = {};
const libs = ['@capacitor/core', '@capacitor/cli', '@capacitor/ios', '@capacitor/android'];
// The fork is not on npm. Its runtime packages are released at the version of this CLI, and its
// official plugins separately, as tarballs attached to GitHub releases of these repositories.
const forkRuntimeRepository = 'https://github.com/linuxul/capacitor';
const forkPluginsRepository = 'https://github.com/linuxul/capacitor-plugins';
// The current official plugin release of the fork and the plugins it contains. The fork release
// process updates both whenever it publishes a new release of linuxul/capacitor-plugins.
export const forkPluginsVersion = '9.0.0';
export const forkPlugins = [
  'action-sheet',
  'app',
  'app-launcher',
  'browser',
  'camera',
  'clipboard',
  'device',
  'dialog',
  'local-notifications',
  'motion',
  'network',
  'preferences',
  'push-notifications',
  'screen-orientation',
  'screen-reader',
  'share',
  'splash-screen',
  'status-bar',
  'text-zoom',
  'toast',
];
// @capacitor/* packages that are build tools, not plugins, so migrate does not warn about them.
const capacitorTools = ['@capacitor/assets', '@capacitor/docgen'];
const gradleVersion = '8.14.3';
const iOSVersion = '17';
const kotlinVersion = '2.2.20';
let installFailed = false;

export async function migrateCommand(config: Config, noprompt: boolean, packagemanager: string): Promise<void> {
  installFailed = false;
  const cliVersion = config.cli.package.version;

  const capMajor = await checkCapacitorMajorVersion(config);
  if (capMajor < 7) {
    fatal('Migrate can only be used on Capacitor 7, please use the CLI in Capacitor 7 to upgrade to 7 first');
  }

  const jdkMajor = await checkJDKMajorVersion();

  if (jdkMajor < 21) {
    logger.warn(`The Capacitor fork ${cliVersion} requires JDK 21 or higher. Some steps may fail.`);
  }

  const variablesAndClasspaths:
    | {
        variables: any;
        'com.android.tools.build:gradle': string;
        'com.google.gms:google-services': string;
      }
    | undefined = await getAndroidVariablesAndClasspaths(config);

  if (!variablesAndClasspaths) {
    fatal('Variable and Classpath info could not be read.');
  }

  allDependencies = {
    ...config.app.package.dependencies,
    ...config.app.package.devDependencies,
  };

  const monorepoWarning =
    'Please note this tool is not intended for use in a mono-repo environment, you should migrate manually instead. ' +
    `Refer to ${forkBreakingChangesUrl(cliVersion)} for the Capacitor fork, ` +
    'and to https://capacitorjs.com/docs/next/updating/8-0 when coming from Capacitor 7.';

  logger.info(monorepoWarning);

  const { migrateconfirm } = noprompt
    ? { migrateconfirm: 'y' }
    : await logPrompt(
        `The Capacitor fork ${cliVersion} sets a deployment target of iOS ${iOSVersion} and a minimum of Android 13 (SDK 33). \n`,
        {
          type: 'text',
          name: 'migrateconfirm',
          message: `Are you sure you want to migrate? (Y/n)`,
          initial: 'y',
        },
      );

  if (typeof migrateconfirm === 'string' && migrateconfirm.toLowerCase() === 'y') {
    try {
      const { depInstallConfirm } = noprompt
        ? { depInstallConfirm: 'y' }
        : await logPrompt(
            `Would you like the migrator to run npm, yarn, pnpm, or bun install to install the latest versions of capacitor packages? (Those using other package managers should answer N)`,
            {
              type: 'text',
              name: 'depInstallConfirm',
              message: `Run Dependency Install? (Y/n)`,
              initial: 'y',
            },
          );

      const runNpmInstall = typeof depInstallConfirm === 'string' && depInstallConfirm.toLowerCase() === 'y';

      let installerType = 'npm';
      if (runNpmInstall) {
        const { manager } = packagemanager
          ? { manager: packagemanager }
          : await logPrompt('What dependency manager do you use?', {
              type: 'select',
              name: 'manager',
              message: `Dependency Management Tool`,
              choices: [
                { title: 'NPM', value: 'npm' },
                { title: 'Yarn', value: 'yarn' },
                { title: 'PNPM', value: 'pnpm' },
                { title: 'Bun', value: 'bun' },
              ],
              initial: 0,
            });
        installerType = manager;
      }

      try {
        await runTask(`Installing Latest Modules using ${installerType}.`, () => {
          return installLatestLibs(installerType, runNpmInstall, config);
        });
      } catch (ex) {
        logger.error(
          `${installerType} install failed. Try deleting node_modules folder and running ${c.input(
            `${installerType} install --force`,
          )} manually.`,
        );
        installFailed = true;
      }

      // Update iOS Projects
      if (allDependencies['@capacitor/ios'] && existsSync(config.ios.platformDirAbs)) {
        const currentiOSVersion = getMajoriOSVersion(config);
        if (parseInt(currentiOSVersion) < parseInt(iOSVersion)) {
          // ios template changes
          await runTask(`Migrating deployment target to ${iOSVersion}.0.`, () => {
            return updateFile(
              join(config.ios.nativeXcodeProjDirAbs, 'project.pbxproj'),
              'IPHONEOS_DEPLOYMENT_TARGET = ',
              ';',
              `${iOSVersion}.0`,
            );
          });

          if ((await config.ios.packageManager) !== 'SPM') {
            // Update Podfile
            await runTask(`Migrating Podfile to ${iOSVersion}.0.`, () => {
              return updateFile(
                join(config.ios.nativeProjectDirAbs, 'Podfile'),
                `platform :ios, '`,
                `'`,
                `${iOSVersion}.0`,
              );
            });
          }
        } else {
          logger.warn('Skipped updating deployment target');
        }

        await migrateToUIScene(config);
      }

      // Has to happen before `cap sync`: `cap update android` stops with an error while the
      // Gradle files still reference the Cordova plugins project.
      if (allDependencies['@capacitor/android'] && existsSync(config.android.platformDirAbs)) {
        await cleanupLegacyCordovaAndroid(config);
      }

      if (!installFailed) {
        await runTask(`Running cap sync.`, () => {
          return runCommand('npx', ['cap', 'sync']);
        });
      } else {
        logger.warn('Skipped Running cap sync.');
      }

      if (allDependencies['@capacitor/android'] && existsSync(config.android.platformDirAbs)) {
        // AndroidManifest.xml add "density"
        await runTask(`Migrating AndroidManifest.xml by adding density to Activity configChanges.`, () => {
          return updateAndroidManifest(join(config.android.srcMainDirAbs, 'AndroidManifest.xml'));
        });

        const gradleWrapperVersion = getGradleWrapperVersion(
          join(config.android.platformDirAbs, 'gradle', 'wrapper', 'gradle-wrapper.properties'),
        );

        if (!installFailed && gte(gradleVersion, gradleWrapperVersion)) {
          try {
            await runTask(`Upgrading gradle wrapper`, () => {
              return updateGradleWrapperFiles(config.android.platformDirAbs);
            });
            // Run twice as first time it only updates the wrapper properties file
            await runTask(`Upgrading gradle wrapper files`, () => {
              return updateGradleWrapperFiles(config.android.platformDirAbs);
            });
          } catch (e) {
            if (isPermissionError(e)) {
              logger.error(
                `gradlew file does not have executable permissions. This can happen if the Android platform was added on a Windows machine. Please run ${c.input(
                  `chmod +x ./${config.android.platformDir}/gradlew`,
                )} and ${c.input(
                  `cd ${config.android.platformDir} && ./gradlew wrapper --distribution-type all --gradle-version ${gradleVersion} --warning-mode all`,
                )} to update the files manually`,
              );
            } else {
              logger.error(`gradle wrapper files were not updated`);
            }
          }
        } else {
          logger.warn('Skipped upgrading gradle wrapper files');
        }
        await runTask(`Migrating root build.gradle file.`, () => {
          return updateBuildGradle(join(config.android.platformDirAbs, 'build.gradle'), variablesAndClasspaths);
        });

        await runTask(`Migrating app build.gradle file.`, () => {
          return updateAppBuildGradle(join(config.android.appDirAbs, 'build.gradle'));
        });

        // Variables gradle
        await runTask(`Migrating variables.gradle file.`, () => {
          return (async (): Promise<void> => {
            const variablesPath = join(config.android.platformDirAbs, 'variables.gradle');
            let txt = readFile(variablesPath);
            if (!txt) {
              return;
            }
            txt = txt.replace(/= {2}'/g, `= '`);
            writeFileSync(variablesPath, txt, { encoding: 'utf-8' });
            for (const variable of Object.keys(variablesAndClasspaths.variables)) {
              let replaceStart = `${variable} = '`;
              let replaceEnd = `'\n`;
              if (typeof variablesAndClasspaths.variables[variable] === 'number') {
                replaceStart = `${variable} = `;
                replaceEnd = `\n`;
              }

              if (txt.includes(replaceStart)) {
                const first = txt.indexOf(replaceStart) + replaceStart.length;
                const value = txt.substring(first, txt.indexOf(replaceEnd, first));
                if (
                  (typeof variablesAndClasspaths.variables[variable] === 'number' &&
                    value <= variablesAndClasspaths.variables[variable]) ||
                  (typeof variablesAndClasspaths.variables[variable] === 'string' &&
                    lt(value, variablesAndClasspaths.variables[variable]))
                ) {
                  await updateFile(
                    variablesPath,
                    replaceStart,
                    replaceEnd,
                    variablesAndClasspaths.variables[variable].toString(),
                    true,
                  );
                }
              } else {
                let file = readFile(variablesPath);
                if (file) {
                  file = file.replace(
                    '}',
                    `    ${replaceStart}${variablesAndClasspaths.variables[variable].toString()}${replaceEnd}}`,
                  );
                  writeFileSync(variablesPath, file);
                }
              }
            }
            const pluginVariables: { [key: string]: string } = {
              firebaseMessagingVersion: '25.0.1',
              playServicesLocationVersion: '21.3.0',
              androidxBrowserVersion: '1.9.0',
              androidxMaterialVersion: '1.13.0',
              androidxExifInterfaceVersion: '1.4.1',
              androidxCoreKTXVersion: '1.17.0',
              googleMapsPlayServicesVersion: '19.2.0',
              googleMapsUtilsVersion: '3.19.1',
              googleMapsKtxVersion: '5.2.1',
              googleMapsUtilsKtxVersion: '5.2.1',
              kotlinxCoroutinesVersion: '1.10.2',
              coreSplashScreenVersion: '1.2.0',
            };
            for (const variable of Object.keys(pluginVariables)) {
              await updateFile(variablesPath, `${variable} = '`, `'`, pluginVariables[variable], true);
            }
          })();
        });

        rimraf.sync(join(config.android.appDirAbs, 'build'));
      }

      // Write all breaking changes
      await runTask(`Writing breaking changes.`, () => {
        return writeBreakingChanges(cliVersion);
      });

      if (!installFailed) {
        logSuccess(`Migration to the Capacitor fork ${cliVersion} is complete. Run and test your app!`);
      } else {
        logger.warn(
          `Migration to the Capacitor fork ${cliVersion} is incomplete. Check the log messages for more information.`,
        );
      }
    } catch (err) {
      fatal(`Failed to migrate: ${err}`);
    }
  } else {
    fatal(`User canceled migration.`);
  }
}

async function checkCapacitorMajorVersion(config: Config): Promise<number> {
  const capacitorVersion = await getCoreVersion(config);
  const versionArray = capacitorVersion.match(/([0-9]+)\.([0-9]+)\.([0-9]+)/) ?? [];
  const majorVersion = parseInt(versionArray[1]);
  return majorVersion;
}

// Only a version range or a dist-tag is resolved from the npm registry, which for @capacitor/* means
// upstream Capacitor. A file:, link:, tarball URL or git spec may point at a build of this fork, so
// migrate only replaces it when it is an older release tarball of the fork.
export function isRegistrySpec(spec: string): boolean {
  return validRange(spec) !== null || /^[a-z][a-z0-9._-]*$/i.test(spec);
}

function forkBreakingChangesUrl(version: string): string {
  return `${forkRuntimeRepository}/blob/${version}/BREAKING.md`;
}

/**
 * The URL of the tarball of `@capacitor/<pkg>` in the `version` release of a fork repository, such as
 * `https://github.com/linuxul/capacitor/releases/download/9.0.0/capacitor-core-9.0.0.tgz`.
 */
function forkReleaseUrl(repository: string, name: string, version: string): string {
  const pkg = name.slice('@capacitor/'.length);
  return `${repository}/releases/download/${version}/capacitor-${pkg}-${version}.tgz`;
}

/**
 * The version of `spec` when it is the tarball of `name` in a release of the fork repository, or
 * undefined for any other spec.
 */
function forkReleaseVersion(repository: string, name: string, spec: string): string | undefined {
  const prefix = `${repository}/releases/download/`;
  if (!spec.startsWith(prefix)) {
    return undefined;
  }
  const version = valid(spec.slice(prefix.length).split('/')[0]);
  return version && spec === forkReleaseUrl(repository, name, version) ? version : undefined;
}

/**
 * Moves a registry spec, or a fork release older than `version`, to the `version` release tarball.
 * Every other spec (a newer fork release, file:, link:, git or another URL) is kept.
 */
function toForkRelease(repository: string, name: string, spec: string, version: string): string {
  const url = forkReleaseUrl(repository, name, version);
  if (isRegistrySpec(spec)) {
    return url;
  }
  const current = forkReleaseVersion(repository, name, spec);
  if (!current) {
    logger.info(`Kept ${name} at ${spec}, which does not come from the npm registry.`);
    return spec;
  }
  if (lt(current, version)) {
    return url;
  }
  logger.info(`Kept ${name} at ${spec}, a Capacitor fork release that is not older than ${version}.`);
  return spec;
}

export async function installLatestLibs(dependencyManager: string, runInstall: boolean, config: Config): Promise<void> {
  const pkgJsonPath = join(config.app.rootDir, 'package.json');
  const pkgJsonFile = readFile(pkgJsonPath);
  if (!pkgJsonFile) {
    return;
  }
  const pkgJson: any = JSON.parse(pkgJsonFile);
  const cliVersion = config.cli.package.version;

  for (const depsKey of ['devDependencies', 'dependencies']) {
    const deps = pkgJson[depsKey] || {};
    for (const name of Object.keys(deps)) {
      const spec: string = deps[name];
      if (!name.startsWith('@capacitor/') || capacitorTools.includes(name)) {
        continue;
      }
      if (libs.includes(name)) {
        deps[name] = toForkRelease(forkRuntimeRepository, name, spec, cliVersion);
      } else if (forkPlugins.includes(name.slice('@capacitor/'.length))) {
        deps[name] = toForkRelease(forkPluginsRepository, name, spec, forkPluginsVersion);
      } else if (isRegistrySpec(spec)) {
        logger.warn(
          `Left ${name} at ${spec}. The Capacitor fork does not release ${name}, so this is the upstream npm ` +
            `package, which is not built for the Capacitor fork ${cliVersion}. Check that it works with the fork ` +
            `or replace it.`,
        );
      } else {
        logger.info(`Kept ${name} at ${spec}, which does not come from the npm registry.`);
      }
    }
  }

  writeFileSync(pkgJsonPath, JSON.stringify(pkgJson, null, 2), {
    encoding: 'utf-8',
  });

  if (runInstall) {
    // rimraf 4+ only expands patterns when asked to; without `glob` this removed nothing
    rimraf.sync(join(config.app.rootDir, 'node_modules/@capacitor/!(cli)'), { glob: true });
    await runCommand(dependencyManager, ['install']);
    if (dependencyManager == 'yarn') {
      await runCommand(dependencyManager, ['upgrade']);
    } else {
      await runCommand(dependencyManager, ['update']);
    }
  } else {
    logger.info(`Please run an install command with your package manager of choice. (ex: yarn install)`);
  }
}

async function writeBreakingChanges(cliVersion: string) {
  const breaking = [
    '@capacitor/action-sheet',
    '@capacitor/barcode-scanner',
    '@capacitor/browser',
    '@capacitor/camera',
    '@capacitor/geolocation',
    '@capacitor/google-maps',
    '@capacitor/push-notifications',
    '@capacitor/screen-orientation',
    '@capacitor/splash-screen',
    '@capacitor/status-bar',
  ];
  const broken = [];
  for (const lib of breaking) {
    if (allDependencies[lib]) {
      broken.push(lib);
    }
  }
  logger.info(
    `IMPORTANT: Review ${forkBreakingChangesUrl(cliVersion)} for the changes in the Capacitor fork ${cliVersion}.`,
  );
  if (broken.length > 0) {
    logger.info(
      `IMPORTANT: If you are coming from Capacitor 7, review https://capacitorjs.com/docs/next/updating/8-0#plugins for breaking changes in these plugins that you use: ${broken.join(
        ', ',
      )}.`,
    );
  }
  if (allDependencies['@capacitor/ios']) {
    logger.info(
      'IMPORTANT: Capacitor 8.5 adopts UIScene on iOS. ' +
        'See https://capacitorjs.com/docs/updating/8-5 for the full 8.4 → 8.5 migration guide.',
    );
  }
}

async function getAndroidVariablesAndClasspaths(config: Config) {
  const template = await withExtractedTemplate(config.cli.assets.android.platformTemplateArchiveAbs, (dir) => ({
    variablesGradle: readFile(join(dir, 'variables.gradle')),
    buildGradle: readFile(join(dir, 'build.gradle')),
  }));
  if (!template.variablesGradle || !template.buildGradle) {
    return;
  }

  const androidGradlePluginVersion = getClasspathVersion(template.buildGradle, 'com.android.tools.build:gradle');
  const googleServicesVersion = getClasspathVersion(template.buildGradle, 'com.google.gms:google-services');
  if (!androidGradlePluginVersion || !googleServicesVersion) {
    return;
  }

  return {
    variables: parseGradleExtVariables(template.variablesGradle),
    'com.android.tools.build:gradle': androidGradlePluginVersion,
    'com.google.gms:google-services': googleServicesVersion,
  };
}

/**
 * The version in a `classpath 'group:artifact:version'` line of a build.gradle.
 */
export function getClasspathVersion(buildGradle: string, dependency: string): string | undefined {
  const escaped = dependency.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return buildGradle.match(new RegExp(`classpath\\s+['"]${escaped}:([^'"]+)['"]`))?.[1];
}

/**
 * The variables of an `ext { name = value }` block such as the template's variables.gradle. Quoted
 * values are strings and bare numbers are numbers, which is how migrate tells SDK levels from library
 * versions.
 */
export function parseGradleExtVariables(variablesGradle: string): Record<string, string | number> {
  const variables: Record<string, string | number> = {};
  for (const match of variablesGradle.matchAll(/^\s*(\w+)\s*=\s*(?:(['"])(.*?)\2|(-?\d+(?:\.\d+)?))\s*$/gm)) {
    variables[match[1]] = match[3] ?? Number(match[4]);
  }
  return variables;
}

function readFile(filename: string): string | undefined {
  try {
    if (!existsSync(filename)) {
      logger.error(`Unable to find ${filename}. Try updating it manually`);
      return;
    }
    return readFileSync(filename, 'utf-8');
  } catch (err) {
    logger.error(`Unable to read ${filename}. Verify it is not already open. ${err}`);
  }
}

function getGradleWrapperVersion(filename: string): string {
  const txt = readFile(filename);
  if (!txt) {
    return '0.0.0';
  }
  const version = txt.substring(txt.indexOf('gradle-') + 7, txt.indexOf('-all.zip'));
  const semverVersion = coerce(version)?.version;
  return semverVersion ? semverVersion : '0.0.0';
}

async function updateGradleWrapperFiles(platformDir: string) {
  await runCommand(
    `./gradlew`,
    ['wrapper', '--distribution-type', 'all', '--gradle-version', gradleVersion, '--warning-mode', 'all'],
    {
      cwd: platformDir,
    },
  );
}

async function updateBuildGradle(
  filename: string,
  variablesAndClasspaths: {
    variables: any;
    'com.android.tools.build:gradle': string;
    'com.google.gms:google-services': string;
  },
) {
  const txt = readFile(filename);
  if (!txt) {
    return;
  }
  const neededDeps: { [key: string]: string } = {
    'com.android.tools.build:gradle': variablesAndClasspaths['com.android.tools.build:gradle'],
    'com.google.gms:google-services': variablesAndClasspaths['com.google.gms:google-services'],
  };
  let replaced = txt;

  for (const dep of Object.keys(neededDeps)) {
    if (replaced.includes(`classpath '${dep}`)) {
      const firstIndex = replaced.indexOf(dep) + dep.length + 1;
      const existingVersion = '' + replaced.substring(firstIndex, replaced.indexOf("'", firstIndex));
      if (gte(neededDeps[dep], existingVersion)) {
        replaced = setAllStringIn(replaced, `classpath '${dep}:`, `'`, neededDeps[dep]);
        logger.info(`Set ${dep} = ${neededDeps[dep]}.`);
      }
    }
  }

  const beforeKotlinVersionUpdate = replaced;
  replaced = replaceVersion(replaced, /(ext\.kotlin_version\s*=\s*['"])([^'"]+)(['"])/, kotlinVersion);
  replaced = replaceVersion(replaced, /(org\.jetbrains\.kotlin:kotlin[^:]*:)([\d.]+)(['"])/, kotlinVersion);
  if (beforeKotlinVersionUpdate !== replaced) {
    logger.info(`Set Kotlin version to ${kotlinVersion}`);
  }
  writeFileSync(filename, replaced, 'utf-8');
}

function replaceVersion(text: string, regex: RegExp, newVersion: string): string {
  return text.replace(regex, (match, prefix, currentVersion, suffix) => {
    const semVer = coerce(currentVersion)?.version;
    if (gte(newVersion, semVer ? semVer : '0.0.0')) {
      return `${prefix || ''}${newVersion}${suffix || ''}`;
    }
    return match;
  });
}

async function updateAppBuildGradle(filename: string) {
  const txt = readFile(filename);
  if (!txt) {
    return;
  }
  let replaced = txt;

  const gradlePproperties = ['compileSdk', 'namespace', 'ignoreAssetsPattern'];
  for (const prop of gradlePproperties) {
    // Use updated Groovy DSL syntax with " = " assignment
    const regex = new RegExp(`(^\\s*${prop})\\s+(?!=)(.+)$`, 'gm');
    replaced = replaced.replace(regex, (_match, key, value) => {
      return `${key} = ${value.trim()}`;
    });
  }
  writeFileSync(filename, replaced, 'utf-8');
}

async function updateFile(
  filename: string,
  textStart: string,
  textEnd: string,
  replacement: string,
  skipIfNotFound?: boolean,
): Promise<boolean> {
  const txt = readFile(filename);
  if (!txt) {
    return false;
  }
  if (txt.includes(textStart)) {
    writeFileSync(filename, setAllStringIn(txt, textStart, textEnd, replacement), { encoding: 'utf-8' });
    return true;
  } else if (!skipIfNotFound) {
    logger.error(`Unable to find "${textStart}" in ${filename}. Try updating it manually`);
  }

  return false;
}

export function setAllStringIn(data: string, start: string, end: string, replacement: string): string {
  let position = 0;
  let result = data;
  let replaced = true;
  while (replaced) {
    const foundIdx = result.indexOf(start, position);
    if (foundIdx == -1) {
      replaced = false;
    } else {
      const idx = foundIdx + start.length;
      position = idx + replacement.length;
      result = result.substring(0, idx) + replacement + result.substring(result.indexOf(end, idx));
    }
  }
  return result;
}

async function updateAndroidManifest(filename: string) {
  const txt = readFile(filename);
  if (!txt) {
    return;
  }

  if (txt.includes('|density') || txt.includes('density|')) {
    return; // Probably already updated
  }
  // Since navigation was an optional change in Capacitor 7, attempting to add density and/or navigation
  const replaced = txt
    .replace(
      'android:configChanges="orientation|keyboardHidden|keyboard|screenSize|locale|smallestScreenSize|screenLayout|uiMode|navigation"',
      'android:configChanges="orientation|keyboardHidden|keyboard|screenSize|locale|smallestScreenSize|screenLayout|uiMode|navigation|density"',
    )
    .replace(
      'android:configChanges="orientation|keyboardHidden|keyboard|screenSize|locale|smallestScreenSize|screenLayout|uiMode"',
      'android:configChanges="orientation|keyboardHidden|keyboard|screenSize|locale|smallestScreenSize|screenLayout|uiMode|navigation|density"',
    );

  if (!replaced.includes('|density')) {
    logger.error(`Unable to add 'density' to 'android:configChanges' in ${filename}. Try adding it manually`);
  } else {
    writeFileSync(filename, replaced, 'utf-8');
  }
}
