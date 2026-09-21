import { mkdirp, writeFile } from 'fs-extra';
import { join } from 'path';

import { debugBuildDeclaresCleartext, debugSourceSets, warnIfCleartextBlocked } from '../src/android/cleartext';
import type { Config } from '../src/definitions';
import { logger } from '../src/log';
import type { RunCommandOptions } from '../src/tasks/run';

import { mktmp } from './util';

const PLAIN_MANIFEST = `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:allowBackup="true"
        android:label="@string/app_name"
        android:theme="@style/AppTheme">
    </application>
</manifest>
`;

const CLEARTEXT_MANIFEST = PLAIN_MANIFEST.replace('android:allowBackup="true"', 'android:usesCleartextTraffic="true"');

const NO_CLEARTEXT_MANIFEST = PLAIN_MANIFEST.replace(
  'android:allowBackup="true"',
  'android:usesCleartextTraffic="false"',
);

const NETWORK_SECURITY_CONFIG_MANIFEST = PLAIN_MANIFEST.replace(
  'android:allowBackup="true"',
  'android:networkSecurityConfig="@xml/network_security_config"',
);

const DEBUG_MANIFEST = `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:usesCleartextTraffic="true" />
</manifest>
`;

describe('warnIfCleartextBlocked', () => {
  let tmpDir: any;
  let rootDir: string;
  let srcDir: string;
  let warnSpy: jest.SpyInstance;

  beforeEach(async () => {
    tmpDir = await mktmp();
    rootDir = tmpDir.path;
    srcDir = join(rootDir, 'android', 'app', 'src');
    await mkdirp(join(srcDir, 'main'));
    warnSpy = jest.spyOn(logger, 'warn').mockImplementation(() => undefined);
  });

  afterEach(() => {
    warnSpy.mockRestore();
    tmpDir.cleanupCallback();
  });

  function makeConfig(flavor = ''): Config {
    return {
      app: { rootDir },
      android: {
        name: 'android',
        flavor,
        srcDirAbs: srcDir,
        srcMainDirAbs: join(srcDir, 'main'),
      },
    } as unknown as Config;
  }

  function makeOptions(options: RunCommandOptions = {}): RunCommandOptions {
    return { liveReload: true, host: '192.168.1.5', port: '3000', ...options };
  }

  async function writeMainManifest(contents: string): Promise<void> {
    await writeFile(join(srcDir, 'main', 'AndroidManifest.xml'), contents);
  }

  async function writeManifest(sourceSet: string, contents: string): Promise<void> {
    await mkdirp(join(srcDir, sourceSet));
    await writeFile(join(srcDir, sourceSet, 'AndroidManifest.xml'), contents);
  }

  it('warns when nothing allows cleartext traffic', async () => {
    await writeMainManifest(PLAIN_MANIFEST);

    await warnIfCleartextBlocked(makeConfig(), makeOptions());

    expect(warnSpy).toHaveBeenCalledTimes(1);
    const message = String(warnSpy.mock.calls[0][0]);
    expect(message).toContain('http');
    expect(message).toContain('blank screen');
    expect(message).toContain('android/app/src/debug/AndroidManifest.xml');
    expect(message).toContain('<application android:usesCleartextTraffic="true" />');
  });

  it('is quiet when the app has a debug manifest', async () => {
    await writeMainManifest(PLAIN_MANIFEST);
    await writeManifest('debug', DEBUG_MANIFEST);

    await warnIfCleartextBlocked(makeConfig(), makeOptions());

    expect(warnSpy).not.toHaveBeenCalled();
  });

  it('is quiet when the main manifest sets usesCleartextTraffic', async () => {
    await writeMainManifest(CLEARTEXT_MANIFEST);

    await warnIfCleartextBlocked(makeConfig(), makeOptions());

    expect(warnSpy).not.toHaveBeenCalled();
  });

  it('is quiet when the main manifest sets usesCleartextTraffic to false', async () => {
    // A deliberate declaration: the CLI reports what it can see, it does not argue with it.
    await writeMainManifest(NO_CLEARTEXT_MANIFEST);

    await warnIfCleartextBlocked(makeConfig(), makeOptions());

    expect(warnSpy).not.toHaveBeenCalled();
  });

  it('is quiet when the main manifest points at a network security config', async () => {
    await writeMainManifest(NETWORK_SECURITY_CONFIG_MANIFEST);

    await warnIfCleartextBlocked(makeConfig(), makeOptions());

    expect(warnSpy).not.toHaveBeenCalled();
  });

  it('is quiet when live reload runs over https', async () => {
    await writeMainManifest(PLAIN_MANIFEST);

    await warnIfCleartextBlocked(makeConfig(), makeOptions({ https: true }));

    expect(warnSpy).not.toHaveBeenCalled();
  });

  it('is quiet when live reload is off', async () => {
    await writeMainManifest(PLAIN_MANIFEST);

    await warnIfCleartextBlocked(makeConfig(), makeOptions({ liveReload: false }));

    expect(warnSpy).not.toHaveBeenCalled();
  });

  it("is quiet when the flavor's debug source set has a manifest", async () => {
    await writeMainManifest(PLAIN_MANIFEST);
    await writeManifest('prodDebug', DEBUG_MANIFEST);

    await warnIfCleartextBlocked(makeConfig('Prod'), makeOptions());

    expect(warnSpy).not.toHaveBeenCalled();
  });

  // The source set names are asserted directly rather than through the filesystem: the temp
  // volume on macOS is case-insensitive, so a lookup of `src/prodDebug` finds `src/ProdDebug`
  // too and could not tell the two spellings apart.
  describe('debugSourceSets', () => {
    it('is only src/debug when there is no flavor', () => {
      expect(debugSourceSets(makeConfig(), makeOptions())).toEqual(['debug']);
    });

    it("lower-cases the flavor's first letter, the way the source set directory spells it", () => {
      // The config holds it as the Gradle task spells it, `assembleProdDebug`.
      expect(debugSourceSets(makeConfig('Prod'), makeOptions())).toEqual(['debug', 'prodDebug']);
    });

    it('leaves a flavor that is already lower case as it is', () => {
      expect(debugSourceSets(makeConfig('prod'), makeOptions())).toEqual(['debug', 'prodDebug']);
    });

    it('takes the flavor from the run options ahead of the config', () => {
      expect(debugSourceSets(makeConfig('Prod'), makeOptions({ flavor: 'Staging' }))).toEqual([
        'debug',
        'stagingDebug',
      ]);
    });

    it('leaves the flavor source set out, since it is part of release builds too', () => {
      expect(debugSourceSets(makeConfig('Prod'), makeOptions())).not.toContain('prod');
    });
  });

  describe('debugBuildDeclaresCleartext', () => {
    it('is true when the main manifest declares it', async () => {
      await writeMainManifest(CLEARTEXT_MANIFEST);

      expect(await debugBuildDeclaresCleartext(makeConfig())).toBe(true);
    });

    it("is true when only the flavor's debug source set declares it", async () => {
      await writeMainManifest(PLAIN_MANIFEST);
      await writeManifest('prodDebug', NO_CLEARTEXT_MANIFEST);

      expect(await debugBuildDeclaresCleartext(makeConfig('Prod'))).toBe(true);
    });

    it('is false when every manifest it can read is silent about it', async () => {
      await writeMainManifest(PLAIN_MANIFEST);
      await writeManifest('debug', PLAIN_MANIFEST);

      expect(await debugBuildDeclaresCleartext(makeConfig())).toBe(false);
    });

    it('is undefined when there is no manifest to go on', async () => {
      expect(await debugBuildDeclaresCleartext(makeConfig())).toBeUndefined();
    });
  });

  it('takes the flavor from the run options too', async () => {
    await writeMainManifest(PLAIN_MANIFEST);
    await writeManifest('prodDebug', DEBUG_MANIFEST);

    await warnIfCleartextBlocked(makeConfig(), makeOptions({ flavor: 'prod' }));

    expect(warnSpy).not.toHaveBeenCalled();
  });

  it('still warns when only the flavor source set has a manifest', async () => {
    // src/prod is part of release builds too, so a manifest there says nothing about live reload.
    await writeMainManifest(PLAIN_MANIFEST);
    await writeManifest('prod', PLAIN_MANIFEST);

    await warnIfCleartextBlocked(makeConfig('Prod'), makeOptions());

    expect(warnSpy).toHaveBeenCalledTimes(1);
  });

  it('is quiet when the main manifest is missing', async () => {
    await warnIfCleartextBlocked(makeConfig(), makeOptions());

    expect(warnSpy).not.toHaveBeenCalled();
  });

  it('is quiet when the main manifest cannot be parsed', async () => {
    await writeMainManifest('<manifest><application');

    await warnIfCleartextBlocked(makeConfig(), makeOptions());

    expect(warnSpy).not.toHaveBeenCalled();
  });
});
