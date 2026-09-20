import {
  APP_ID,
  APP_NAME,
  CAPACITOR_PLUGIN_ID,
  LEGACY_CORDOVA_PLUGIN_ID,
  MappedFS,
  makeAppDir,
  run,
  installPlatform,
} from './util';

describe.each([false, true])('Update: Android (monoRepoLike: %p)', (monoRepoLike) => {
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
    await installPlatform(appDir, 'android');
    addOutput = await run(appDir, `add android`);
    FS = new MappedFS(appDir);
  });

  afterAll(() => {
    //appDirObj.cleanupCallback();
  });

  it('Should register the Capacitor plugin class', async () => {
    const pluginsJSON = JSON.parse(await FS.read('android/app/src/main/assets/capacitor.plugins.json'));
    expect(pluginsJSON).toEqual([{ pkg: CAPACITOR_PLUGIN_ID, classpath: 'com.getcapacitor.cool.CoolPlugin' }]);
  });

  it('Should add the Capacitor plugin to the Gradle files', async () => {
    const settingsGradle = await FS.read('android/capacitor.settings.gradle');
    const buildGradle = await FS.read('android/app/capacitor.build.gradle');
    expect(settingsGradle).toContain(`include ':${CAPACITOR_PLUGIN_ID}'`);
    expect(buildGradle).toContain(`implementation project(':${CAPACITOR_PLUGIN_ID}')`);
  });

  it('Should warn about a package that only has a plugin.xml and leave it out', async () => {
    expect(addOutput).toContain('Cordova is not supported in this fork, skipped');
    expect(addOutput).toContain(LEGACY_CORDOVA_PLUGIN_ID);

    const settingsGradle = await FS.read('android/capacitor.settings.gradle');
    const buildGradle = await FS.read('android/app/capacitor.build.gradle');
    const pluginsJSON = await FS.read('android/app/src/main/assets/capacitor.plugins.json');
    expect(settingsGradle).not.toMatch(/cordova/i);
    expect(buildGradle).not.toMatch(/cordova/i);
    expect(pluginsJSON).not.toContain(LEGACY_CORDOVA_PLUGIN_ID);
    expect(await FS.exists('android/capacitor-cordova-android-plugins')).toBe(false);
  });
});
