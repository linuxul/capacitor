import { pathExists, readJSON } from 'fs-extra';
import { dirname, join } from 'path';

import c from './colors';
import type { Config } from './definitions';
import { fatal } from './errors';
import { logger } from './log';
import { resolveNode } from './util/node';

export const enum PluginType {
  Core,
  Incompatible,
}

export interface PluginManifest {
  readonly ios?: {
    readonly src?: string;
  };
  readonly android?: {
    readonly src?: string;
  };
}

export interface Plugin {
  id: string;
  name: string;
  version: string;
  rootPath: string;
  manifest?: PluginManifest;
  repository?: any;
  /**
   * The package looks like a Cordova plugin (it has a `plugin.xml` or a
   * `cordova` key in its package.json) and has no Capacitor manifest.
   * Cordova plugins are not supported, so these are reported and skipped.
   */
  legacyCordova?: boolean;
  ios?: {
    name: string;
    type: PluginType;
    path: string;
  };
  android?: {
    type: PluginType;
    path: string;
  };
}

export function getIncludedPluginPackages(config: Config, platform: string): readonly string[] | undefined {
  const { extConfig } = config.app;

  switch (platform) {
    case 'android':
      return extConfig.android?.includePlugins ?? extConfig.includePlugins;
    case 'ios':
      return extConfig.ios?.includePlugins ?? extConfig.includePlugins;
  }
}

export async function getPlugins(config: Config, platform: string): Promise<Plugin[]> {
  const possiblePlugins = getIncludedPluginPackages(config, platform) ?? getDependencies(config);
  const resolvedPlugins = await Promise.all(possiblePlugins.map(async (p) => resolvePlugin(config, p)));

  return resolvedPlugins.filter((p): p is Plugin => !!p);
}

export async function resolvePlugin(config: Config, name: string): Promise<Plugin | null> {
  try {
    const packagePath = resolveNode(config.app.rootDir, name, 'package.json');
    if (!packagePath) {
      fatal(`Unable to find ${c.strong(`node_modules/${name}`)}.\n` + `Are you sure ${c.strong(name)} is installed?`);
    }

    const rootPath = dirname(packagePath);
    const meta = await readJSON(packagePath);
    if (!meta) {
      return null;
    }
    if (meta.capacitor) {
      return {
        id: name,
        name: fixName(name),
        version: meta.version,
        rootPath,
        repository: meta.repository,
        manifest: meta.capacitor,
      };
    }
    if (meta.cordova || (await pathExists(join(rootPath, 'plugin.xml')))) {
      return {
        id: name,
        name: fixName(name),
        version: meta.version,
        rootPath,
        repository: meta.repository,
        legacyCordova: true,
      };
    }
  } catch (e) {
    // ignore
  }
  return null;
}

export function getDependencies(config: Config): string[] {
  return [
    ...Object.keys(config.app.package.dependencies ?? {}),
    ...Object.keys(config.app.package.devDependencies ?? {}),
  ];
}

export function fixName(name: string): string {
  name = name
    .replace(/\//g, '_')
    .replace(/-/g, '_')
    .replace(/@/g, '')
    .replace(/_\w/g, (m) => m[1].toUpperCase());

  return name.charAt(0).toUpperCase() + name.slice(1);
}

export function printPlugins(
  plugins: Plugin[],
  platform: string,
  type: 'capacitor' | 'incompatible' = 'capacitor',
): void {
  if (plugins.length === 0) {
    return;
  }

  let msg: string;
  const plural = plugins.length === 1 ? '' : 's';

  switch (type) {
    case 'incompatible':
      msg = `Found ${plugins.length} Cordova plugin${plural} for ${c.strong(
        platform,
      )}. Cordova is not supported in this fork, skipped:\n`;
      break;
    case 'capacitor':
      msg = `Found ${plugins.length} Capacitor plugin${plural} for ${c.strong(platform)}:\n`;
      break;
  }

  msg += plugins.map((p) => `${p.id}${c.weak(`@${p.version}`)}`).join('\n');

  if (type === 'incompatible') {
    logger.warn(msg);
  } else {
    logger.info(msg);
  }
}

export function getPluginType(p: Plugin, platform: string): PluginType {
  switch (platform) {
    case 'ios':
      return p.ios?.type ?? PluginType.Core;
    case 'android':
      return p.android?.type ?? PluginType.Core;
  }

  return PluginType.Core;
}
