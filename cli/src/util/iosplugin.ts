import { readFileSync, readJSONSync, writeJSONSync } from 'fs-extra';
import { resolve } from 'path';

import type { Config } from '../definitions';
import type { Plugin } from '../plugin';
import { getPluginType, PluginType } from '../plugin';

import type { ReaddirPOptions } from './fs';
import { readdirp } from './fs';

export async function getPluginFiles(plugins: Plugin[]): Promise<string[]> {
  let filenameList: string[] = [];

  const options: ReaddirPOptions = {
    filter: (item) => {
      return item.stats.isFile() && item.path.endsWith('.swift');
    },
  };

  for (const plugin of plugins) {
    if (plugin.ios && getPluginType(plugin, 'ios') === PluginType.Core) {
      const pluginPath = resolve(plugin.rootPath, plugin.ios?.path);
      const filenames = await readdirp(pluginPath, options);
      filenameList = filenameList.concat(filenames);
    }
  }

  return filenameList;
}

export async function findPluginClasses(files: string[]): Promise<string[]> {
  const classList: string[] = [];

  for (const file of files) {
    const fileData = readFileSync(file, 'utf-8');

    // The bridge loads plugins by their Objective-C runtime name, so only classes exposed with @objc(Name) count
    for (const match of fileData.matchAll(/@objc\(([A-Za-z0-9_-]+)\)/g)) {
      if (!classList.includes(match[1])) {
        classList.push(match[1]);
      }
    }
  }

  return classList;
}

export async function writePluginJSON(config: Config, classList: string[]): Promise<void> {
  const capJSONFile = resolve(config.ios.nativeTargetDirAbs, 'capacitor.config.json');
  const capJSON = readJSONSync(capJSONFile);
  capJSON['packageClassList'] = classList;
  writeJSONSync(capJSONFile, capJSON, { spaces: '\t' });
}

export async function generateIOSPackageJSON(config: Config, plugins: Plugin[]): Promise<void> {
  const fileList = await getPluginFiles(plugins);
  const classList = await findPluginClasses(fileList);
  writePluginJSON(config, classList);
}
