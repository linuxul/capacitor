import { readFileSync } from 'fs-extra';
import { resolve } from 'path';

import { getMajoriOSVersionFromPbx } from '../src/ios/common';

const REPO_ROOT = resolve(__dirname, '..', '..');

const target = (version: string) => `\t\t\t\tIPHONEOS_DEPLOYMENT_TARGET = ${version};\n`;

describe('getMajoriOSVersionFromPbx', () => {
  it.each([
    ['15.0', '15'],
    ['17.0', '17'],
    ['17.4', '17'],
    ['9.0', '9'],
    ['100.0', '100'],
    ['17', '17'],
  ])('reads the major version of %s', (version, expected) => {
    expect(getMajoriOSVersionFromPbx(target(version))).toBe(expected);
  });

  it('uses the lowest target when build configurations differ', () => {
    expect(getMajoriOSVersionFromPbx(target('18.0') + target('17.0') + target('26.0'))).toBe('17');
  });

  it('returns undefined when no deployment target is set', () => {
    expect(getMajoriOSVersionFromPbx('SWIFT_VERSION = 5.0;')).toBeUndefined();
  });

  it.each(['ios-spm-template', 'ios-pods-template'])('reads iOS 17 from the shipped %s', (template) => {
    const pbx = readFileSync(resolve(REPO_ROOT, template, 'App/App.xcodeproj/project.pbxproj'), 'utf-8');
    expect(getMajoriOSVersionFromPbx(pbx)).toBe('17');
  });
});
