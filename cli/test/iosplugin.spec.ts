import { mkdirp, writeFile } from 'fs-extra';
import { join } from 'path';

import { findPluginClasses } from '../src/util/iosplugin';

import { mktmp } from './util';

describe('findPluginClasses', () => {
  let dir: string;

  const write = async (name: string, content: string) => {
    const file = join(dir, name);
    await writeFile(file, content);
    return file;
  };

  beforeAll(async () => {
    const tmp = await mktmp();
    dir = join(tmp.path, 'plugin');
    await mkdirp(dir);
  });

  it('collects every class exposed with @objc(Name)', async () => {
    const file = await write(
      'Plugins.swift',
      '@objc(FirstPlugin)\npublic class FirstPlugin: CAPPlugin, CAPBridgedPlugin {}\n' +
        '@objc(SecondPlugin)\npublic class SecondPlugin: CAPPlugin, CAPBridgedPlugin {}\n',
    );
    expect(await findPluginClasses([file])).toEqual(['FirstPlugin', 'SecondPlugin']);
  });

  it('ignores @objc method renames and plain @objc members', async () => {
    const file = await write(
      'Methods.swift',
      '@objc(OnlyPlugin)\nclass OnlyPlugin: CAPPlugin {\n  @objc(echoWith:) func echo(_ call: CAPPluginCall) {}\n  @objc func ping(_ call: CAPPluginCall) {}\n}\n',
    );
    expect(await findPluginClasses([file])).toEqual(['OnlyPlugin']);
  });

  it('does not list the same class twice', async () => {
    const a = await write('A.swift', '@objc(SharedPlugin)\nclass SharedPlugin: CAPPlugin {}\n');
    const b = await write('B.swift', '// see @objc(SharedPlugin)\n');
    expect(await findPluginClasses([a, b])).toEqual(['SharedPlugin']);
  });

  it('no longer registers Objective-C plugins declared with CAP_PLUGIN', async () => {
    const file = await write(
      'Legacy.swift',
      'CAP_PLUGIN(LegacyPlugin, "Legacy", CAP_PLUGIN_METHOD(echo, CAPPluginReturnPromise);)\n',
    );
    expect(await findPluginClasses([file])).toEqual([]);
  });
});
