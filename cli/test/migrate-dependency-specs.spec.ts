import { readFileSync, writeFileSync } from 'fs-extra';
import { join, resolve } from 'path';
import { inc } from 'semver';

import { checkJDKMajorVersion, getCoreVersion } from '../src/common';
import type { Config } from '../src/definitions';
import { logger } from '../src/log';
import {
  forkPlugins,
  forkPluginsVersion,
  installLatestLibs,
  isRegistrySpec,
  migrateCommand,
} from '../src/tasks/migrate';

import { mktmp } from './util';

// The fork is not on npm, so a registry spec of @capacitor/* installs upstream Capacitor. cap migrate
// moves the runtime packages to the release tarballs of the running CLI's version and the official
// plugins to the fork's plugin release, and keeps file:, link:, git and newer fork release specs.

jest.mock('../src/util/subprocess', () => ({
  ...jest.requireActual('../src/util/subprocess'),
  runCommand: jest.fn(),
}));

jest.mock('../src/common', () => ({
  ...jest.requireActual('../src/common'),
  getCoreVersion: jest.fn(),
  checkJDKMajorVersion: jest.fn(),
}));

// Not the plugin release version, so the tests tell the two apart.
const CLI_VERSION = '9.1.0';
const RELEASE = 'https://github.com/linuxul/capacitor/releases/download/8.5.3';
const TEMPLATE_ARCHIVE = resolve(__dirname, '..', 'assets', 'android-template.tar.gz');

const runtimeUrl = (pkg: string, version: string) =>
  `https://github.com/linuxul/capacitor/releases/download/${version}/capacitor-${pkg}-${version}.tgz`;
const pluginUrl = (plugin: string, version: string) =>
  `https://github.com/linuxul/capacitor-plugins/releases/download/${version}/capacitor-${plugin}-${version}.tgz`;

// linuxul/capacitor-plugins 9.0.0
const RELEASED_PLUGINS = [
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

describe('isRegistrySpec', () => {
  it.each(['^8.0.0', '8.5.3', '~8.5', '>=8 <9', '*', 'latest', 'next'])('counts %s as a registry spec', (spec) => {
    expect(isRegistrySpec(spec)).toBe(true);
  });

  it.each([
    'file:../capacitor/core',
    'link:../capacitor/cli',
    `${RELEASE}/capacitor-core-8.5.3.tgz`,
    'git+ssh://git@github.com/linuxul/capacitor.git',
    'github:linuxul/capacitor',
    'linuxul/capacitor',
    'npm:@linuxul/capacitor-core@8.5.3',
    './vendor/capacitor-core-8.5.3.tgz',
  ])('does not count %s as a registry spec', (spec) => {
    expect(isRegistrySpec(spec)).toBe(false);
  });
});

describe('installLatestLibs', () => {
  let tmpDir: any;
  let config: Config;
  let infoSpy: jest.SpyInstance;
  let warnSpy: jest.SpyInstance;

  beforeEach(async () => {
    tmpDir = await mktmp();
    config = { app: { rootDir: tmpDir.path }, cli: { package: { version: CLI_VERSION } } } as unknown as Config;
    infoSpy = jest.spyOn(logger, 'info').mockImplementation(() => undefined);
    warnSpy = jest.spyOn(logger, 'warn').mockImplementation(() => undefined);
  });

  afterEach(() => {
    infoSpy.mockRestore();
    warnSpy.mockRestore();
    tmpDir.cleanupCallback();
  });

  const writePackage = (pkg: unknown) => writeFileSync(join(tmpDir.path, 'package.json'), JSON.stringify(pkg));
  const readPackage = () => JSON.parse(readFileSync(join(tmpDir.path, 'package.json'), 'utf-8'));
  const migrateDependency = async (name: string, spec: string): Promise<string> => {
    writePackage({ dependencies: { [name]: spec } });
    await installLatestLibs('npm', false, config);
    return readPackage().dependencies[name];
  };

  it('moves registry specs of the runtime packages to the release of the running CLI', async () => {
    writePackage({
      dependencies: {
        '@capacitor/core': '^7.0.0',
        '@capacitor/ios': '8.5.3',
        '@capacitor/android': 'latest',
        'left-alone': '^1.0.0',
      },
      devDependencies: {
        '@capacitor/cli': '~8.5',
      },
    });

    await installLatestLibs('npm', false, config);

    expect(readPackage()).toEqual({
      dependencies: {
        '@capacitor/core': runtimeUrl('core', CLI_VERSION),
        '@capacitor/ios': runtimeUrl('ios', CLI_VERSION),
        '@capacitor/android': runtimeUrl('android', CLI_VERSION),
        'left-alone': '^1.0.0',
      },
      devDependencies: {
        '@capacitor/cli': runtimeUrl('cli', CLI_VERSION),
      },
    });
    expect(warnSpy).not.toHaveBeenCalled();
  });

  it.each([
    ['@capacitor/core', 'core', '8.5.3'],
    ['@capacitor/cli', 'cli', '9.0.0'],
    ['@capacitor/android', 'android', '9.1.0-beta.1'],
  ])('moves %s from the older fork release %s', async (name, pkg, version) => {
    expect(await migrateDependency(name, runtimeUrl(pkg, version))).toBe(runtimeUrl(pkg, CLI_VERSION));
  });

  it.each([
    ['@capacitor/core', 'core', CLI_VERSION],
    ['@capacitor/ios', 'ios', '9.2.0'],
  ])('keeps %s at the fork release %s, which is not older than the CLI', async (name, pkg, version) => {
    const spec = runtimeUrl(pkg, version);

    expect(await migrateDependency(name, spec)).toBe(spec);
    expect(infoSpy).toHaveBeenCalledWith(
      `Kept ${name} at ${spec}, a Capacitor fork release that is not older than ${CLI_VERSION}.`,
    );
  });

  it.each([
    ['@capacitor/core', 'file:../capacitor/core'],
    ['@capacitor/cli', 'link:../capacitor/cli'],
    ['@capacitor/ios', 'git+ssh://git@github.com/linuxul/capacitor.git#8.5.3'],
    ['@capacitor/android', 'github:linuxul/capacitor'],
    ['@capacitor/core', './vendor/capacitor-core-8.5.3.tgz'],
    ['@capacitor/core', 'https://github.com/someone/capacitor/releases/download/8.5.3/capacitor-core-8.5.3.tgz'],
    ['@capacitor/core', `${RELEASE}/capacitor-android-8.5.3.tgz`],
    ['@capacitor/camera', 'file:../capacitor-plugins/camera'],
    ['@capacitor/app', pluginUrl('app-launcher', '8.0.0')],
    ['@capacitor/haptics', 'file:../haptics'],
  ])('keeps %s at %s', async (name, spec) => {
    expect(await migrateDependency(name, spec)).toBe(spec);
    expect(infoSpy).toHaveBeenCalledWith(`Kept ${name} at ${spec}, which does not come from the npm registry.`);
    expect(warnSpy).not.toHaveBeenCalled();
  });

  it.each(RELEASED_PLUGINS)('moves a registry spec of @capacitor/%s to the fork plugin release', async (plugin) => {
    expect(forkPlugins).toContain(plugin);
    expect(await migrateDependency(`@capacitor/${plugin}`, '^8.0.0')).toBe(pluginUrl(plugin, forkPluginsVersion));
  });

  it('moves older fork plugin releases and keeps newer ones', async () => {
    const newer = inc(forkPluginsVersion, 'minor') as string;
    writePackage({
      dependencies: {
        '@capacitor/camera': pluginUrl('camera', '8.0.0'),
        '@capacitor/toast': pluginUrl('toast', `${forkPluginsVersion}-beta.1`),
        '@capacitor/share': pluginUrl('share', forkPluginsVersion),
        '@capacitor/status-bar': pluginUrl('status-bar', newer),
      },
    });

    await installLatestLibs('npm', false, config);

    expect(readPackage().dependencies).toEqual({
      '@capacitor/camera': pluginUrl('camera', forkPluginsVersion),
      '@capacitor/toast': pluginUrl('toast', forkPluginsVersion),
      '@capacitor/share': pluginUrl('share', forkPluginsVersion),
      '@capacitor/status-bar': pluginUrl('status-bar', newer),
    });
    expect(infoSpy).toHaveBeenCalledWith(expect.stringContaining('Kept @capacitor/share at'));
    expect(infoSpy).toHaveBeenCalledWith(expect.stringContaining('Kept @capacitor/status-bar at'));
  });

  it('leaves upstream plugins that the fork does not release, with a warning', async () => {
    const dependencies = {
      '@capacitor/filesystem': '^8.0.0',
      '@capacitor/geolocation': '^7.0.0',
      '@capacitor/google-maps': 'latest',
      '@capacitor/barcode-scanner': '^2.0.0',
      '@capacitor/assets': '^3.0.0',
    };
    writePackage({ dependencies });

    await installLatestLibs('npm', false, config);

    expect(readPackage().dependencies).toEqual(dependencies);
    expect(warnSpy).toHaveBeenCalledTimes(4);
    for (const name of ['filesystem', 'geolocation', 'google-maps', 'barcode-scanner']) {
      expect(warnSpy).toHaveBeenCalledWith(
        expect.stringMatching(
          new RegExp(
            `^Left @capacitor/${name} at .*The Capacitor fork does not release @capacitor/${name}, so this is the upstream npm package, which is not built for the Capacitor fork ${CLI_VERSION}\\.`,
          ),
        ),
      );
    }
  });

  it('moves a fork 8.5.3 app to the release of the running CLI', async () => {
    writePackage({
      dependencies: {
        '@capacitor/core': `${RELEASE}/capacitor-core-8.5.3.tgz`,
        '@capacitor/android': `${RELEASE}/capacitor-android-8.5.3.tgz`,
        '@capacitor/ios': `${RELEASE}/capacitor-ios-8.5.3.tgz`,
        '@capacitor/camera': '^8.0.0',
        '@capacitor/app': 'file:../capacitor-plugins/app',
      },
      devDependencies: {
        '@capacitor/cli': `${RELEASE}/capacitor-cli-8.5.3.tgz`,
      },
    });

    await installLatestLibs('npm', false, config);

    expect(readPackage()).toEqual({
      dependencies: {
        '@capacitor/core': runtimeUrl('core', CLI_VERSION),
        '@capacitor/android': runtimeUrl('android', CLI_VERSION),
        '@capacitor/ios': runtimeUrl('ios', CLI_VERSION),
        '@capacitor/camera': pluginUrl('camera', forkPluginsVersion),
        '@capacitor/app': 'file:../capacitor-plugins/app',
      },
      devDependencies: {
        '@capacitor/cli': runtimeUrl('cli', CLI_VERSION),
      },
    });
  });
});

describe('migrateCommand messages', () => {
  let tmpDir: any;
  let config: Config;
  const spies: jest.SpyInstance[] = [];
  const messages = () => spies.flatMap((spy) => spy.mock.calls.map((call) => String(call[0])));

  beforeEach(async () => {
    tmpDir = await mktmp();
    const rootDir = tmpDir.path;
    const dependencies = { '@capacitor/core': '^7.0.0' };
    writeFileSync(join(rootDir, 'package.json'), JSON.stringify({ dependencies }));
    config = {
      app: { rootDir, package: { dependencies } },
      android: { platformDirAbs: join(rootDir, 'android') },
      ios: { platformDirAbs: join(rootDir, 'ios') },
      cli: {
        assets: { android: { platformTemplateArchiveAbs: TEMPLATE_ARCHIVE } },
        package: { version: CLI_VERSION },
      },
    } as unknown as Config;

    (getCoreVersion as jest.Mock).mockResolvedValue('7.4.0');
    (checkJDKMajorVersion as jest.Mock).mockResolvedValue(17);
    for (const level of ['info', 'warn', 'error', 'msg'] as const) {
      spies.push(jest.spyOn(logger, level).mockImplementation(() => undefined));
    }
  });

  afterEach(() => {
    while (spies.length > 0) {
      spies.pop()?.mockRestore();
    }
    jest.clearAllMocks();
    tmpDir.cleanupCallback();
  });

  it('names the Capacitor fork at the version of the running CLI', async () => {
    await migrateCommand(config, true, 'npm');

    const logged = messages();
    expect(logged).toContain(`The Capacitor fork ${CLI_VERSION} requires JDK 21 or higher. Some steps may fail.`);
    expect(logged).toContainEqual(
      expect.stringContaining(`Refer to https://github.com/linuxul/capacitor/blob/${CLI_VERSION}/BREAKING.md`),
    );
    expect(logged).toContain(
      `IMPORTANT: Review https://github.com/linuxul/capacitor/blob/${CLI_VERSION}/BREAKING.md for the changes in the Capacitor fork ${CLI_VERSION}.`,
    );
    expect(logged).toContainEqual(
      expect.stringContaining(`Migration to the Capacitor fork ${CLI_VERSION} is complete. Run and test your app!`),
    );
    expect(logged.filter((msg) => /Capacitor 8(?![.\d])|\^8\.0\.0/.test(msg))).toEqual([]);
    expect(JSON.parse(readFileSync(join(tmpDir.path, 'package.json'), 'utf-8')).dependencies).toEqual({
      '@capacitor/core': runtimeUrl('core', CLI_VERSION),
    });
  });
});
