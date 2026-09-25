import { registerPlugin } from '../global';
import { WebPlugin } from '../web-plugin';

/**
 * Safely web encode a string value (inspired by js-cookie)
 * @param str The string value to encode
 */
const encode = (str: string) =>
  encodeURIComponent(str)
    .replace(/%(2[346B]|5E|60|7C)/g, decodeURIComponent)
    .replace(/[()]/g, escape);

/**
 * Safely web decode a string value (inspired by js-cookie)
 * @param str The string value to decode
 */
const decode = (str: string): string => str.replace(/(%[\dA-F]{2})+/gi, decodeURIComponent);

export interface CapacitorCookiesPlugin {
  getCookies(options?: GetCookieOptions): Promise<HttpCookieMap>;
  /**
   * Write a cookie to the device.
   */
  setCookie(options: SetCookieOptions): Promise<void>;
  /**
   * Delete a cookie from the device.
   */
  deleteCookie(options: DeleteCookieOptions): Promise<void>;
  /**
   * Clear cookies from the device at a given URL.
   */
  clearCookies(options: ClearCookieOptions): Promise<void>;
  /**
   * Clear all cookies on the device.
   */
  clearAllCookies(): Promise<void>;
}

export interface HttpCookie {
  /**
   * The URL of the cookie.
   */
  url?: string;
  /**
   * The key of the cookie.
   */
  key: string;
  /**
   * The value of the cookie.
   */
  value: string;
}

export interface HttpCookieMap {
  [key: string]: string;
}

export interface HttpCookieExtras {
  /**
   * The path to write the cookie to.
   */
  path?: string;
  /**
   * The date to expire the cookie.
   */
  expires?: string;
}

export type GetCookieOptions = Omit<HttpCookie, 'key' | 'value'>;
export type SetCookieOptions = HttpCookie & HttpCookieExtras;
export type DeleteCookieOptions = Omit<HttpCookie, 'value'>;
export type ClearCookieOptions = Omit<HttpCookie, 'key' | 'value'>;

export class CapacitorCookiesPluginWeb extends WebPlugin implements CapacitorCookiesPlugin {
  async getCookies(): Promise<HttpCookieMap> {
    const cookies = document.cookie;
    const cookieMap: HttpCookieMap = {};
    cookies.split(';').forEach((cookie) => {
      if (cookie.trim().length <= 0) return;
      // Split on the first "=" only; a value may contain more. A cookie without "=" is kept as a
      // key with an empty value instead of throwing.
      const separator = cookie.indexOf('=');
      const key = decode(separator < 0 ? cookie : cookie.slice(0, separator)).trim();
      const value = separator < 0 ? '' : decode(cookie.slice(separator + 1)).trim();
      cookieMap[key] = value;
    });
    return cookieMap;
  }

  async setCookie(options: SetCookieOptions): Promise<void> {
    try {
      // Safely Encoded Key/Value
      const encodedKey = encode(options.key);
      const encodedValue = encode(options.value);

      // Clean & sanitize options
      const expires = options.expires ? `; expires=${options.expires.replace('expires=', '')}` : '';

      const path = (options.path || '/').replace('path=', ''); // Default is "path=/"
      const domain = options.url != null && options.url.length > 0 ? `domain=${options.url}` : '';

      document.cookie = `${encodedKey}=${encodedValue || ''}${expires}; path=${path}; ${domain};`;
    } catch (error) {
      return Promise.reject(error);
    }
  }

  async deleteCookie(options: DeleteCookieOptions): Promise<void> {
    try {
      document.cookie = `${options.key}=; Max-Age=0`;
    } catch (error) {
      return Promise.reject(error);
    }
  }

  async clearCookies(): Promise<void> {
    try {
      const cookies = document.cookie.split(';') || [];
      for (const cookie of cookies) {
        document.cookie = cookie.replace(/^ +/, '').replace(/=.*/, `=;expires=${new Date().toUTCString()};path=/`);
      }
    } catch (error) {
      return Promise.reject(error);
    }
  }

  async clearAllCookies(): Promise<void> {
    try {
      await this.clearCookies();
    } catch (error) {
      return Promise.reject(error);
    }
  }
}

export const CapacitorCookies = registerPlugin<CapacitorCookiesPlugin>('CapacitorCookies', {
  web: () => new CapacitorCookiesPluginWeb(),
});
