import { existsSync, mkdirpSync, readFileSync, writeFileSync } from 'fs-extra';
import { join, resolve } from 'path';

import { addDebugManifestIfMissing, cleanupLegacyCordovaAndroid } from '../src/android/cordova-cleanup';
import type { Config } from '../src/definitions';
import { logger } from '../src/log';

import { mktmp } from './util';

const TEMPLATE_MANIFEST = resolve(
  __dirname,
  '..',
  '..',
  'android-template',
  'app',
  'src',
  'debug',
  'AndroidManifest.xml',
);
const TEMPLATE_ARCHIVE = resolve(__dirname, '..', 'assets', 'android-template.tar.gz');

const CAPACITOR_7_SETTINGS_GRADLE =
  `include ':app'\n` +
  `include ':capacitor-cordova-android-plugins'\n` +
  `project(':capacitor-cordova-android-plugins').projectDir = new File('./capacitor-cordova-android-plugins/')\n` +
  `\n` +
  `apply from: 'capacitor.settings.gradle'\n`;

const CAPACITOR_7_APP_BUILD_GRADLE = `repositories {
    flatDir{
        dirs '../capacitor-cordova-android-plugins/src/main/libs', 'libs'
    }
}

dependencies {
    implementation project(':capacitor-android')
    implementation project(':capacitor-cordova-android-plugins')
}
`;

function makeFakeConfig(rootDir: string, flavor = ''): Config {
  const platformDirAbs = join(rootDir, 'android');

  return {
    app: { rootDir },
    android: {
      platformDirAbs,
      flavor,
      appDirAbs: join(platformDirAbs, 'app'),
      srcDirAbs: join(platformDirAbs, 'app', 'src'),
      srcMainDirAbs: join(platformDirAbs, 'app', 'src', 'main'),
    },
    cli: {
      assetsDirAbs: join(rootDir, 'cli-assets'),
      assets: {
        android: { platformTemplateArchiveAbs: TEMPLATE_ARCHIVE },
      },
    },
  } as unknown as Config;
}

function writeManifest(config: Config, sourceSet: string, contents: string): void {
  mkdirpSync(join(config.android.srcDirAbs, sourceSet));
  writeFileSync(join(config.android.srcDirAbs, sourceSet, 'AndroidManifest.xml'), contents, { encoding: 'utf-8' });
}

/** A main manifest that deliberately turns cleartext traffic off. */
const NO_CLEARTEXT_MAIN_MANIFEST = `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:usesCleartextTraffic="false">
    </application>
</manifest>
`;

describe('addDebugManifestIfMissing', () => {
  let tmpDir: any;
  let config: Config;
  let manifestPath: string;
  let warnSpy: jest.SpyInstance;
  let infoSpy: jest.SpyInstance;

  beforeEach(async () => {
    tmpDir = await mktmp();
    config = makeFakeConfig(tmpDir.path);
    mkdirpSync(config.cli.assetsDirAbs);
    mkdirpSync(join(config.android.srcDirAbs, 'main'));
    manifestPath = join(config.android.srcDirAbs, 'debug', 'AndroidManifest.xml');
    warnSpy = jest.spyOn(logger, 'warn').mockImplementation(() => undefined);
    infoSpy = jest.spyOn(logger, 'info').mockImplementation(() => undefined);
  });

  afterEach(() => {
    warnSpy.mockRestore();
    infoSpy.mockRestore();
    tmpDir.cleanupCallback();
  });

  it('writes the template debug manifest byte for byte', async () => {
    const written = await addDebugManifestIfMissing(config);

    expect(written).toBe(true);
    expect(readFileSync(manifestPath)).toEqual(readFileSync(TEMPLATE_MANIFEST));
    expect(readFileSync(manifestPath, 'utf-8')).toContain('android:usesCleartextTraffic="true"');
    expect(warnSpy).not.toHaveBeenCalled();
  });

  it('cleans up the directory it extracted the template into', async () => {
    await addDebugManifestIfMissing(config);

    expect(existsSync(join(config.cli.assetsDirAbs, 'tempAndroidDebugManifest'))).toBe(false);
  });

  it('leaves a debug manifest the app already has alone', async () => {
    const existing = `<?xml version="1.0" encoding="utf-8"?>\n<manifest />\n`;
    mkdirpSync(join(config.android.srcDirAbs, 'debug'));
    writeFileSync(manifestPath, existing, { encoding: 'utf-8' });

    const written = await addDebugManifestIfMissing(config);

    expect(written).toBe(false);
    expect(readFileSync(manifestPath, 'utf-8')).toBe(existing);
  });

  it('leaves an app that already declares cleartext traffic alone and says so', async () => {
    // The template manifest declares `usesCleartextTraffic="true"`. Writing it next to a main
    // manifest that declares it false would contradict it and fail the manifest merger.
    writeFileSync(join(config.android.srcMainDirAbs, 'AndroidManifest.xml'), NO_CLEARTEXT_MAIN_MANIFEST, {
      encoding: 'utf-8',
    });

    const written = await addDebugManifestIfMissing(config);

    expect(written).toBe(false);
    expect(existsSync(manifestPath)).toBe(false);
    expect(warnSpy).not.toHaveBeenCalled();
    expect(infoSpy).toHaveBeenCalledTimes(1);
    const message = String(infoSpy.mock.calls[0][0]);
    expect(message).toContain('android/app/src/debug/AndroidManifest.xml');
    expect(message).toContain('manifest merger');
  });

  it('leaves a flavoured app that declares cleartext in its own debug source set alone', async () => {
    // A flavoured project keeps its debug manifest in `src/prodDebug`, not `src/debug`. Writing
    // one that declares cleartext true next to a variant manifest that declares it false is the
    // merger failure this is here to avoid, and `src/main` saying nothing does not excuse it.
    const flavoured = makeFakeConfig(tmpDir.path, 'Prod');
    writeManifest(flavoured, 'prodDebug', NO_CLEARTEXT_MAIN_MANIFEST);

    const written = await addDebugManifestIfMissing(flavoured);

    expect(written).toBe(false);
    expect(existsSync(manifestPath)).toBe(false);
    expect(warnSpy).not.toHaveBeenCalled();
    expect(String(infoSpy.mock.calls[0][0])).toContain('manifest merger');
  });

  it('adds the debug manifest to a flavoured app that has no manifest of its own', async () => {
    const written = await addDebugManifestIfMissing(makeFakeConfig(tmpDir.path, 'Prod'));

    expect(written).toBe(true);
    expect(readFileSync(manifestPath, 'utf-8')).toContain('android:usesCleartextTraffic="true"');
  });

  it('adds the debug manifest next to a variant manifest that says nothing about cleartext', async () => {
    // `src/prodDebug` and `src/debug` merge, so a variant manifest that is silent about cleartext
    // is no reason to leave live reload broken.
    const flavoured = makeFakeConfig(tmpDir.path, 'Prod');
    writeManifest(
      flavoured,
      'prodDebug',
      NO_CLEARTEXT_MAIN_MANIFEST.replace('android:usesCleartextTraffic="false"', 'android:allowBackup="true"'),
    );

    const written = await addDebugManifestIfMissing(flavoured);

    expect(written).toBe(true);
    expect(readFileSync(manifestPath, 'utf-8')).toContain('android:usesCleartextTraffic="true"');
  });

  it('adds the debug manifest when the main manifest says nothing about cleartext', async () => {
    writeFileSync(
      join(config.android.srcMainDirAbs, 'AndroidManifest.xml'),
      NO_CLEARTEXT_MAIN_MANIFEST.replace('android:usesCleartextTraffic="false"', 'android:allowBackup="true"'),
      { encoding: 'utf-8' },
    );

    const written = await addDebugManifestIfMissing(config);

    expect(written).toBe(true);
    expect(readFileSync(manifestPath, 'utf-8')).toContain('android:usesCleartextTraffic="true"');
  });

  it('warns with the attribute to write by hand when the template cannot be read', async () => {
    const broken = makeFakeConfig(tmpDir.path) as any;
    broken.cli.assets.android.platformTemplateArchiveAbs = join(tmpDir.path, 'nope.tar.gz');

    const written = await addDebugManifestIfMissing(broken as Config);

    expect(written).toBe(false);
    expect(existsSync(manifestPath)).toBe(false);
    expect(warnSpy).toHaveBeenCalledTimes(1);
    expect(String(warnSpy.mock.calls[0][0])).toContain('usesCleartextTraffic');
  });
});

describe('cleanupLegacyCordovaAndroid', () => {
  let tmpDir: any;
  let config: Config;
  let settingsPath: string;
  let buildPath: string;
  let warnSpy: jest.SpyInstance;
  let infoSpy: jest.SpyInstance;

  beforeEach(async () => {
    tmpDir = await mktmp();
    config = makeFakeConfig(tmpDir.path);
    mkdirpSync(config.cli.assetsDirAbs);
    mkdirpSync(join(config.android.srcDirAbs, 'main'));
    settingsPath = join(config.android.platformDirAbs, 'settings.gradle');
    buildPath = join(config.android.appDirAbs, 'build.gradle');
    warnSpy = jest.spyOn(logger, 'warn').mockImplementation(() => undefined);
    infoSpy = jest.spyOn(logger, 'info').mockImplementation(() => undefined);
  });

  afterEach(() => {
    warnSpy.mockRestore();
    infoSpy.mockRestore();
    tmpDir.cleanupCallback();
  });

  it('rewrites both Gradle files and adds the debug manifest', async () => {
    writeFileSync(settingsPath, CAPACITOR_7_SETTINGS_GRADLE, { encoding: 'utf-8' });
    writeFileSync(buildPath, CAPACITOR_7_APP_BUILD_GRADLE, { encoding: 'utf-8' });

    await cleanupLegacyCordovaAndroid(config);

    expect(readFileSync(settingsPath, 'utf-8')).toBe(`include ':app'\n\napply from: 'capacitor.settings.gradle'\n`);
    expect(readFileSync(buildPath, 'utf-8')).toBe(`repositories {
    flatDir{
        dirs 'libs'
    }
}

dependencies {
    implementation project(':capacitor-android')
}
`);
    expect(existsSync(join(config.android.srcDirAbs, 'debug', 'AndroidManifest.xml'))).toBe(true);
    expect(warnSpy).not.toHaveBeenCalled();
  });

  it('leaves a file it cannot rewrite byte for byte alone and names every line to remove', async () => {
    const source = `repositories {
    flatDir {
        dirs file('../capacitor-cordova-android-plugins/src/main/libs')
    }
}

dependencies {
    implementation project(':capacitor-cordova-android-plugins')
}
`;
    writeFileSync(buildPath, source, { encoding: 'utf-8' });

    await cleanupLegacyCordovaAndroid(config);

    expect(readFileSync(buildPath, 'utf-8')).toBe(source);
    expect(warnSpy).toHaveBeenCalledTimes(1);
    const message = String(warnSpy.mock.calls[0][0]);
    expect(message).toContain('android/app/build.gradle');
    expect(message).toContain('left untouched');
    expect(message).toContain(`3: dirs file('../capacitor-cordova-android-plugins/src/main/libs')`);
    expect(message).toContain(`8: implementation project(':capacitor-cordova-android-plugins')`);
  });

  it('leaves a Windows-1252 build file byte for byte alone and names the lines to remove', async () => {
    // Gradle reads build scripts in the JVM's default charset, so a Windows-1252 build.gradle is
    // a real thing to find in a European project. Decoding it as UTF-8 turns the 0xFC of the
    // u-umlaut into U+FFFD, and writing that back would destroy the comment.
    const windows1252 = Buffer.from(
      `// Geprüft von Jan\ndependencies {\n    implementation project(':capacitor-cordova-android-plugins')\n}\n`,
      'latin1',
    );
    writeFileSync(buildPath, windows1252);

    await cleanupLegacyCordovaAndroid(config);

    expect(readFileSync(buildPath)).toEqual(windows1252);
    expect(warnSpy).toHaveBeenCalledTimes(1);
    const message = String(warnSpy.mock.calls[0][0]);
    expect(message).toContain('android/app/build.gradle');
    expect(message).toContain('not valid UTF-8');
    expect(message).toContain(`3: implementation project(':capacitor-cordova-android-plugins')`);
  });

  it('leaves a CP949 build file byte for byte alone', async () => {
    const cp949 = Buffer.concat([
      Buffer.from('// '),
      // The CP949 bytes for a Korean comment, none of which is valid UTF-8 on its own.
      Buffer.from([0xc7, 0xd1, 0xb1, 0xdb, 0x20, 0xc1, 0xd6, 0xbc, 0xae]),
      Buffer.from(`\ndependencies {\n    implementation project(':capacitor-cordova-android-plugins')\n}\n`),
    ]);
    writeFileSync(buildPath, cp949);

    await cleanupLegacyCordovaAndroid(config);

    expect(readFileSync(buildPath)).toEqual(cp949);
    expect(warnSpy).toHaveBeenCalledTimes(1);
    expect(String(warnSpy.mock.calls[0][0])).toContain('not valid UTF-8');
  });

  it('still rewrites a UTF-8 build file that has non-ASCII in it', async () => {
    // The check is a round trip, not an "is it ASCII" test: a UTF-8 file survives it whatever is
    // in it.
    const source = `// 한글 주석\ndependencies {\n    implementation project(':capacitor-cordova-android-plugins')\n}\n`;
    writeFileSync(buildPath, source, { encoding: 'utf-8' });

    await cleanupLegacyCordovaAndroid(config);

    expect(readFileSync(buildPath, 'utf-8')).toBe(`// 한글 주석\ndependencies {\n}\n`);
    expect(warnSpy).not.toHaveBeenCalled();
  });

  it('does nothing to Gradle files that are missing or already clean', async () => {
    const clean = `include ':app'\n`;
    writeFileSync(settingsPath, clean, { encoding: 'utf-8' });

    await cleanupLegacyCordovaAndroid(config);

    expect(readFileSync(settingsPath, 'utf-8')).toBe(clean);
    expect(existsSync(buildPath)).toBe(false);
    expect(warnSpy).not.toHaveBeenCalled();
  });
});
