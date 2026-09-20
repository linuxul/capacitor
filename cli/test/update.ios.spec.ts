import { APP_ID, APP_NAME, LEGACY_CORDOVA_PLUGIN_ID, MappedFS, makeAppDir, run, installPlatform } from './util';

describe.each([false, true])('Update: iOS (monoRepoLike: %p)', (monoRepoLike) => {
  let appDirObj: any;
  let appDir: string;
  let addOutput: string;
  let FS: MappedFS;

  beforeAll(async () => {
    // These commands are slowww...
    jest.setTimeout(150000);
    appDirObj = await makeAppDir(monoRepoLike);
    appDir = appDirObj.appDir;
    // Init in this directory so we can test add
    await run(appDir, `init "${APP_NAME}" "${APP_ID}"`);
    await installPlatform(appDir, 'ios');
    addOutput = await run(appDir, `add ios`);
    FS = new MappedFS(appDir);
  });

  afterAll(() => {
    //appDirObj.cleanupCallback();
  });

  it('Should add the Capacitor plugin to Package.swift', async () => {
    const packageSwift = await FS.read('ios/App/CapApp-SPM/Package.swift');
    expect(packageSwift).toContain('.package(name: "CoolCapacitorPlugin", path: ');
    expect(packageSwift).toContain('.product(name: "CoolCapacitorPlugin", package: "CoolCapacitorPlugin")');
  });

  it('Should register the Capacitor plugin class', async () => {
    const capacitorConfig = JSON.parse(await FS.read('ios/App/App/capacitor.config.json'));
    expect(capacitorConfig.packageClassList).toEqual(['CoolPlugin']);
  });

  it('Should warn about a package that only has a plugin.xml and leave it out', async () => {
    expect(addOutput).toContain('Cordova is not supported in this fork, skipped');
    expect(addOutput).toContain(LEGACY_CORDOVA_PLUGIN_ID);

    const packageSwift = await FS.read('ios/App/CapApp-SPM/Package.swift');
    const capacitorConfig = await FS.read('ios/App/App/capacitor.config.json');
    expect(packageSwift).not.toMatch(/cordova/i);
    expect(capacitorConfig).not.toContain('CDVPlugin');
    expect(await FS.exists('ios/capacitor-cordova-ios-plugins')).toBe(false);
  });
});
