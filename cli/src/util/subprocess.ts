import { Subprocess, SubprocessError, which } from '@ionic/utils-subprocess';

export interface RunCommandOptions {
  cwd?: string;
}

export async function runCommand(
  command: string,
  args: readonly string[],
  options: RunCommandOptions = {},
): Promise<string> {
  const p = new Subprocess(command, args, options);

  try {
    return await p.output();
  } catch (e) {
    if (e instanceof SubprocessError) {
      // old behavior of just throwing the stdout/stderr strings
      throw e.output ? e.output : e.cause ? `${e.message} ${e.cause.toString()}` : e.code ? e.code : 'Unknown error';
    }

    throw e;
  }
}

/**
 * Whether a failure from `runCommand` is a permission error, such as a gradlew without the
 * executable bit. `runCommand` usually throws the output as a string, but can also throw an Error
 * or another value, so none of those may be assumed.
 */
export function isPermissionError(e: unknown): boolean {
  if (typeof e === 'string') {
    return e.includes('EACCES');
  }
  if (e instanceof Error) {
    return e.message.includes('EACCES') || (e as NodeJS.ErrnoException).code === 'EACCES';
  }
  return false;
}

export async function getCommandOutput(
  command: string,
  args: readonly string[],
  options: RunCommandOptions = {},
): Promise<string | null> {
  try {
    return (await runCommand(command, args, options)).trim();
  } catch (e) {
    return null;
  }
}

export async function isInstalled(command: string): Promise<boolean> {
  try {
    await which(command);
  } catch (e) {
    return false;
  }

  return true;
}
