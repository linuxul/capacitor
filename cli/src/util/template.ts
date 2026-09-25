import { mkdirp, mkdtemp, remove } from 'fs-extra';
import { tmpdir } from 'os';
import { join } from 'path';
import { extract } from 'tar';

export async function extractTemplate(src: string, dir: string): Promise<void> {
  await mkdirp(dir);
  await extract({ file: src, cwd: dir });
}

/**
 * Extracts a template archive into a new temporary directory, calls `fn` with that directory, and
 * removes the directory afterwards, whether `fn` returns or throws. The directory is created under
 * the OS temp directory, never inside the installed CLI, so concurrent runs and read-only installs
 * work.
 */
export async function withExtractedTemplate<T>(archive: string, fn: (dir: string) => Promise<T> | T): Promise<T> {
  const dir = await mkdtemp(join(tmpdir(), 'capacitor-template-'));
  try {
    await extract({ file: archive, cwd: dir });
    return await fn(dir);
  } finally {
    await remove(dir);
  }
}
