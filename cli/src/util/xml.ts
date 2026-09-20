import { readFile } from 'fs-extra';
import xml2js from 'xml2js';

export async function readXML(path: string): Promise<any> {
  try {
    const xmlStr = await readFile(path, { encoding: 'utf-8' });
    try {
      return await xml2js.parseStringPromise(xmlStr);
    } catch (e: any) {
      throw `Error parsing: ${path}, ${e.stack ?? e}`;
    }
  } catch (e) {
    throw `Unable to read: ${path}`;
  }
}
