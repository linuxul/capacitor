import { toKotlinPackageName } from '../src/android/common';

describe('toKotlinPackageName', () => {
  it('leaves ordinary app ids untouched', () => {
    expect(toKotlinPackageName('com.getcapacitor.cli.test')).toBe('com.getcapacitor.cli.test');
  });

  it('escapes segments that are Kotlin hard keywords', () => {
    expect(toKotlinPackageName('com.in.app')).toBe('com.`in`.app');
    expect(toKotlinPackageName('is.fun.object')).toBe('`is`.`fun`.`object`');
  });

  it('only matches whole segments', () => {
    expect(toKotlinPackageName('com.input.isolated')).toBe('com.input.isolated');
  });
});
