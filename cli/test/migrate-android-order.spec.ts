import { mkdirpSync, writeFileSync } from 'fs-extra';
import { join, resolve } from 'path';

import { cleanupLegacyCordovaAndroid } from '../src/android/cordova-cleanup';
import { checkJDKMajorVersion, getCoreVersion } from '../src/common';
import type { Config } from '../src/definitions';
import { logger } from '../src/log';
import { migrateCommand } from '../src/tasks/migrate';
import { runCommand } from '../src/util/subprocess';

import { mktmp } from './util';

// `cap update android` stops with an error while the Gradle files still reference the Cordova
// plugins project, so the cleanup has to have finished before `cap sync` runs. Both call sites are
// mocked and record when they were reached, which is the only thing this spec is about.
jest.mock('../src/android/cordova-cleanup', () => ({
  cleanupLegacyCordovaAndroid: jest.fn(),
}));

jest.mock('../src/util/subprocess', () => ({
  runCommand: jest.fn(),
}));

jest.mock('../src/common', () => ({
  ...jest.requireActual('../src/common'),
  getCoreVersion: jest.fn(),
  checkJDKMajorVersion: jest.fn(),
}));

const TEMPLATE_ARCHIVE = resolve(__dirname, '..', 'assets', 'android-template.tar.gz');

const ANDROID_MANIFEST = `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <activity
            android:configChanges="orientation|keyboardHidden|keyboard|screenSize|locale|smallestScreenSize|screenLayout|uiMode"
            android:name=".MainActivity" />
    </application>
</manifest>
`;

const ROOT_BUILD_GRADLE = `buildscript {
    ext.kotlin_version = '2.0.0'
    dependencies {
        classpath 'com.android.tools.build:gradle:8.7.2'
        classpath 'com.google.gms:google-services:4.4.2'
    }
}
`;

const APP_BUILD_GRADLE = `android {
    namespace "com.getcapacitor.myapp"
    compileSdk rootProject.ext.compileSdkVersion
}
`;

const VARIABLES_GRADLE = `ext {
    minSdkVersion = 23
    compileSdkVersion = 35
    androidxAppCompatVersion = '1.7.0'
}
`;

const GRADLE_WRAPPER_PROPERTIES = `distributionUrl=https\\://services.gradle.org/distributions/gradle-8.11.1-all.zip\n`;

describe('migrateCommand on Android', () => {
  let tmpDir: any;
  let config: Config;
  let order: string[];
  const spies: jest.SpyInstance[] = [];

  beforeEach(async () => {
    tmpDir = await mktmp();
    order = [];

    const rootDir = tmpDir.path;
    const platformDirAbs = join(rootDir, 'android');
    mkdirpSync(join(platformDirAbs, 'app', 'src', 'main'));
    mkdirpSync(join(platformDirAbs, 'gradle', 'wrapper'));
    mkdirpSync(join(rootDir, 'cli-assets'));
    writeFileSync(join(rootDir, 'package.json'), JSON.stringify({ dependencies: { '@capacitor/android': '^7.0.0' } }));
    writeFileSync(join(platformDirAbs, 'app', 'src', 'main', 'AndroidManifest.xml'), ANDROID_MANIFEST);
    writeFileSync(join(platformDirAbs, 'gradle', 'wrapper', 'gradle-wrapper.properties'), GRADLE_WRAPPER_PROPERTIES);
    writeFileSync(join(platformDirAbs, 'build.gradle'), ROOT_BUILD_GRADLE);
    writeFileSync(join(platformDirAbs, 'app', 'build.gradle'), APP_BUILD_GRADLE);
    writeFileSync(join(platformDirAbs, 'variables.gradle'), VARIABLES_GRADLE);

    config = {
      app: { rootDir, package: { dependencies: { '@capacitor/android': '^7.0.0' } } },
      android: {
        platformDir: 'android',
        platformDirAbs,
        appDirAbs: join(platformDirAbs, 'app'),
        srcMainDirAbs: join(platformDirAbs, 'app', 'src', 'main'),
      },
      ios: { platformDirAbs: join(rootDir, 'ios') },
      cli: {
        assetsDirAbs: join(rootDir, 'cli-assets'),
        assets: { android: { platformTemplateArchiveAbs: TEMPLATE_ARCHIVE } },
        package: { version: '9.0.0' },
      },
    } as unknown as Config;

    (getCoreVersion as jest.Mock).mockResolvedValue('8.0.0');
    (checkJDKMajorVersion as jest.Mock).mockResolvedValue(21);
    (cleanupLegacyCordovaAndroid as jest.Mock).mockImplementation(async () => {
      order.push('cordova cleanup');
    });
    (runCommand as jest.Mock).mockImplementation(async (command: string, args: string[]) => {
      order.push([command, ...args].join(' '));
      return '';
    });

    for (const level of ['info', 'warn', 'error'] as const) {
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

  it('takes the Cordova references out of the Gradle files before it runs cap sync', async () => {
    await migrateCommand(config, true, 'npm');

    expect(cleanupLegacyCordovaAndroid).toHaveBeenCalledTimes(1);
    expect(cleanupLegacyCordovaAndroid).toHaveBeenCalledWith(config);
    expect(order).toContain('npx cap sync');
    expect(order.indexOf('cordova cleanup')).toBeLessThan(order.indexOf('npx cap sync'));
  });

  it('leaves the Gradle files alone when the app has no Android platform', async () => {
    const withoutAndroid = {
      ...config,
      android: { ...config.android, platformDirAbs: join(tmpDir.path, 'no-android') },
    } as Config;

    await migrateCommand(withoutAndroid, true, 'npm');

    expect(cleanupLegacyCordovaAndroid).not.toHaveBeenCalled();
    expect(order).toContain('npx cap sync');
  });
});
