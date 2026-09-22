import { readFileSync, writeFileSync } from 'fs-extra';
import { join } from 'path';

import type { Config } from '../src/definitions';
import { logger } from '../src/log';
import { installLatestLibs, isRegistrySpec } from '../src/tasks/migrate';

import { mktmp } from './util';

// Apps of this fork install @capacitor/* from its release tarballs or a local checkout. cap migrate
// pins Capacitor packages to a registry range, which for those apps would swap the fork for upstream.

const RELEASE = 'https://github.com/linuxul/capacitor/releases/download/8.5.3';

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

  beforeEach(async () => {
    tmpDir = await mktmp();
    config = { app: { rootDir: tmpDir.path } } as unknown as Config;
    infoSpy = jest.spyOn(logger, 'info').mockImplementation(() => undefined);
  });

  afterEach(() => {
    infoSpy.mockRestore();
    tmpDir.cleanupCallback();
  });

  const writePackage = (pkg: unknown) => writeFileSync(join(tmpDir.path, 'package.json'), JSON.stringify(pkg));
  const readPackage = () => JSON.parse(readFileSync(join(tmpDir.path, 'package.json'), 'utf-8'));

  it('keeps Capacitor packages that point at a tarball or a local checkout', async () => {
    writePackage({
      dependencies: {
        '@capacitor/core': `${RELEASE}/capacitor-core-8.5.3.tgz`,
        '@capacitor/android': 'file:../capacitor/android',
        '@capacitor/camera': 'file:../capacitor-plugins/camera',
        '@capacitor/ios': '^7.0.0',
        '@capacitor/app': '^7.0.0',
        'left-alone': '^1.0.0',
      },
      devDependencies: {
        '@capacitor/cli': `${RELEASE}/capacitor-cli-8.5.3.tgz`,
      },
    });

    await installLatestLibs('npm', false, config);

    expect(readPackage()).toEqual({
      dependencies: {
        '@capacitor/core': `${RELEASE}/capacitor-core-8.5.3.tgz`,
        '@capacitor/android': 'file:../capacitor/android',
        '@capacitor/camera': 'file:../capacitor-plugins/camera',
        '@capacitor/ios': '^8.0.0',
        '@capacitor/app': '^8.0.0',
        'left-alone': '^1.0.0',
      },
      devDependencies: {
        '@capacitor/cli': `${RELEASE}/capacitor-cli-8.5.3.tgz`,
      },
    });
    expect(infoSpy).toHaveBeenCalledWith(expect.stringContaining('Kept @capacitor/core at'));
    expect(infoSpy).toHaveBeenCalledWith(expect.stringContaining('Kept @capacitor/cli at'));
  });
});
