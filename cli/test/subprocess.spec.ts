import { isPermissionError } from '../src/util/subprocess';

describe('isPermissionError', () => {
  it('recognizes the output runCommand throws as a string', () => {
    expect(isPermissionError('spawn ./gradlew EACCES')).toBe(true);
    expect(isPermissionError('FAILURE: Build failed with an exception.')).toBe(false);
  });

  it('recognizes an Error by its message or code', () => {
    expect(isPermissionError(new Error('spawn ./gradlew EACCES'))).toBe(true);
    expect(isPermissionError(Object.assign(new Error('permission denied'), { code: 'EACCES' }))).toBe(true);
    expect(isPermissionError(new Error('Gradle failed'))).toBe(false);
  });

  it('does not throw on other values, so the original failure is reported', () => {
    expect(isPermissionError(1)).toBe(false);
    expect(isPermissionError(undefined)).toBe(false);
    expect(isPermissionError({ code: 'ENOENT' })).toBe(false);
  });
});
