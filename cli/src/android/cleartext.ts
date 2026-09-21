import Debug from 'debug';
import { pathExists } from 'fs-extra';
import { join, relative } from 'path';

import c from '../colors';
import type { Config } from '../definitions';
import { logger } from '../log';
import type { RunCommandOptions } from '../tasks/run';
import { convertToUnixPath } from '../util/fs';
import { readXML } from '../util/xml';

const debug = Debug('capacitor:android:cleartext');

const MANIFEST_FILE = 'AndroidManifest.xml';

/** The part of `RunCommandOptions` that says which flavor is being built. */
export interface FlavorOptions {
  flavor?: string;
}

/** What the Android template ships in `app/src/debug/`, for the warning to quote. */
const DEBUG_MANIFEST = `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:usesCleartextTraffic="true" />
</manifest>`;

/**
 * Live reload points the app at an `http://` URL, which Android blocks by default. Apps created
 * before Cordova support was removed got `usesCleartextTraffic` from the generated Cordova module
 * and have no `app/src/debug/AndroidManifest.xml`, so the WebView silently loads nothing and the
 * app shows a blank screen.
 *
 * This only warns: the build and the deploy are fine either way, and an app that arranges for
 * cleartext itself is left alone.
 */
export async function warnIfCleartextBlocked(config: Config, options: RunCommandOptions): Promise<void> {
  if (!options.liveReload || options.https) {
    return;
  }
  if (await findSourceSetManifest(config, debugSourceSets(config, options))) {
    return;
  }
  // Anything but a plain `false` means the app has made its own arrangements, or that there is
  // nothing to go on - either way a warning would only be noise.
  if ((await manifestDeclaresCleartext(join(config.android.srcMainDirAbs, MANIFEST_FILE))) !== false) {
    return;
  }

  const debugManifestPath = join(config.android.srcDirAbs, 'debug', MANIFEST_FILE);

  logger.warn(
    `Live reload serves the app over ${c.strong('http')}, which Android blocks by default, so the app is ` +
      `likely to show a blank screen.\n` +
      `Create ${c.strong(convertToUnixPath(relative(config.app.rootDir, debugManifestPath)))}, the file new ` +
      `projects get, to allow cleartext traffic in debug builds:\n\n` +
      DEBUG_MANIFEST,
  );
}

/**
 * The source sets Gradle merges into a debug build on top of `src/main`. `src/<flavor>` is left
 * out on purpose: it is part of release builds too, so a manifest there says nothing about live
 * reload.
 */
export function debugSourceSets(config: Config, options: FlavorOptions = {}): string[] {
  // The same precedence `runAndroid` uses to pick the Gradle task.
  const flavor = options.flavor || config.android.flavor || '';

  if (!flavor) {
    return ['debug'];
  }

  // The config holds the flavor the way the Gradle task spells it (`assembleProdDebug`), the
  // source set directory has a lower case first letter (`src/prodDebug`).
  return ['debug', `${flavor.charAt(0).toLowerCase()}${flavor.slice(1)}Debug`];
}

/** The path of the first of these source sets that carries a manifest of its own, if any. */
export async function findSourceSetManifest(config: Config, sourceSets: string[]): Promise<string | undefined> {
  for (const sourceSet of sourceSets) {
    const manifestPath = join(config.android.srcDirAbs, sourceSet, MANIFEST_FILE);
    if (await pathExists(manifestPath)) {
      debug('Found a manifest in source set %O', sourceSet);
      return manifestPath;
    }
  }

  return undefined;
}

/**
 * Whether any of the manifests that go into a debug build - the main one and the debug source
 * sets' - says how cleartext traffic is handled. `true` from any one of them settles it; `false`
 * needs at least one manifest that could be read and said nothing.
 */
export async function debugBuildDeclaresCleartext(
  config: Config,
  options: FlavorOptions = {},
): Promise<boolean | undefined> {
  const paths = [
    join(config.android.srcMainDirAbs, MANIFEST_FILE),
    ...debugSourceSets(config, options).map((sourceSet) => join(config.android.srcDirAbs, sourceSet, MANIFEST_FILE)),
  ];

  let answer: boolean | undefined = undefined;
  for (const manifestPath of paths) {
    const declared = await manifestDeclaresCleartext(manifestPath);
    if (declared) {
      return true;
    }
    if (declared === false) {
      answer = false;
    }
  }

  return answer;
}

/**
 * Whether a manifest says anything about cleartext traffic, either by setting
 * `android:usesCleartextTraffic` or by pointing at a network security config. Both mean the app
 * has made its own arrangements and should not be second-guessed: not by warning about a blank
 * screen, and not by adding a debug manifest that contradicts it and fails the manifest merger.
 *
 * `undefined` means there is nothing to go on, because the manifest is missing or cannot be
 * parsed. Each caller decides what to do with that; it is not the same answer as `false`.
 */
async function manifestDeclaresCleartext(manifestPath: string): Promise<boolean | undefined> {
  if (!(await pathExists(manifestPath))) {
    debug('%O does not exist, nothing to go on', manifestPath);
    return undefined;
  }

  try {
    const xmlData = await readXML(manifestPath);
    const applicationNodes: any[] = Array.isArray(xmlData?.manifest?.application) ? xmlData.manifest.application : [];

    return applicationNodes.some(
      (applicationNode) =>
        applicationNode?.$?.['android:usesCleartextTraffic'] !== undefined ||
        applicationNode?.$?.['android:networkSecurityConfig'] !== undefined,
    );
  } catch (e: any) {
    debug('Could not read %O: %O', manifestPath, e);
    return undefined;
  }
}
