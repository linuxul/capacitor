import { existsSync, readFileSync } from 'fs-extra';
import { join, resolve } from 'path';

import { getClasspathVersion, parseGradleExtVariables } from '../src/tasks/migrate';
import { withExtractedTemplate } from '../src/util/template';

const REPO_ROOT = resolve(__dirname, '..', '..');
const ANDROID_TEMPLATE = resolve(REPO_ROOT, 'android-template');
const ANDROID_TEMPLATE_ARCHIVE = resolve(__dirname, '..', 'assets', 'android-template.tar.gz');

// The parser this replaced: the variables.gradle text rewritten into JSON with regular expressions.
const parseAsJsonTheOldWay = (text: string) =>
  JSON.parse(
    text
      .replace('ext ', '')
      .replace(/=/g, ':')
      .replace(/\n/g, ',')
      .replace(/,([^:]+):/g, (_k, p1) => `,"${p1}":`)
      .replace('{,', '{')
      .replace(',}', '}')
      .replace(/\s/g, '')
      .replace(/'/g, '"'),
  );

describe('parseGradleExtVariables', () => {
  it('reads the shipped variables.gradle like the previous parser did', () => {
    const text = readFileSync(join(ANDROID_TEMPLATE, 'variables.gradle'), 'utf-8');

    const variables = parseGradleExtVariables(text);

    expect(variables).toEqual(parseAsJsonTheOldWay(text));
    expect(variables.minSdkVersion).toBe(33);
    expect(variables.androidxActivityVersion).toBe('1.11.0');
  });

  it('keeps numbers as numbers and quoted values as strings', () => {
    expect(parseGradleExtVariables(`ext {\n  a = 1\n  b = "2"\n  c = '3.0.1'\n}`)).toEqual({
      a: 1,
      b: '2',
      c: '3.0.1',
    });
  });
});

describe('getClasspathVersion', () => {
  it('reads the classpath versions of the shipped build.gradle', () => {
    const text = readFileSync(join(ANDROID_TEMPLATE, 'build.gradle'), 'utf-8');

    expect(getClasspathVersion(text, 'com.android.tools.build:gradle')).toMatch(/^\d+\.\d+\.\d+$/);
    expect(getClasspathVersion(text, 'com.google.gms:google-services')).toMatch(/^\d+\.\d+\.\d+$/);
  });

  it('returns undefined when the dependency is not declared', () => {
    expect(
      getClasspathVersion(`classpath 'com.android.tools.build:gradle:8.13.0'`, 'com.google.gms:google-services'),
    ).toBeUndefined();
  });
});

describe('withExtractedTemplate', () => {
  it('removes the temporary directory after use, also when the callback throws', async () => {
    let seen = '';
    await expect(
      withExtractedTemplate(ANDROID_TEMPLATE_ARCHIVE, (dir) => {
        seen = dir;
        expect(existsSync(join(dir, 'variables.gradle'))).toBe(true);
        throw new Error('boom');
      }),
    ).rejects.toThrow('boom');

    expect(seen).not.toBe('');
    expect(existsSync(seen)).toBe(false);
  });
});
