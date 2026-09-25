import type { WindowCapacitor } from '../definitions-internal';

/**
 * Routes document.cookie through the native cookie store on Android and iOS, when CapacitorCookies
 * is enabled.
 */
export const patchDocumentCookie = (win: WindowCapacitor, platform: 'android' | 'ios'): void => {
  // patch document.cookie on Android/iOS
  win.CapacitorCookiesDescriptor =
    Object.getOwnPropertyDescriptor(Document.prototype, 'cookie') ||
    Object.getOwnPropertyDescriptor(HTMLDocument.prototype, 'cookie');

  let doPatchCookies = false;

  // check if capacitor cookies is disabled before patching
  if (platform === 'ios') {
    // Use prompt to synchronously get capacitor cookies config.
    // https://stackoverflow.com/questions/29249132/wkwebview-complex-communication-between-javascript-native-code/49474323#49474323

    const payload = {
      type: 'CapacitorCookies.isEnabled',
    };

    const isCookiesEnabled = prompt(JSON.stringify(payload));
    if (isCookiesEnabled === 'true') {
      doPatchCookies = true;
    }
  } else if (typeof win.CapacitorCookiesAndroidInterface !== 'undefined') {
    const isCookiesEnabled = win.CapacitorCookiesAndroidInterface.isEnabled();
    if (isCookiesEnabled === true) {
      doPatchCookies = true;
    }
  }

  if (doPatchCookies) {
    Object.defineProperty(document, 'cookie', {
      get: function () {
        if (platform === 'ios') {
          // Use prompt to synchronously get cookies.
          // https://stackoverflow.com/questions/29249132/wkwebview-complex-communication-between-javascript-native-code/49474323#49474323

          const payload = {
            type: 'CapacitorCookies.get',
          };

          const res = prompt(JSON.stringify(payload));
          return res;
        } else if (typeof win.CapacitorCookiesAndroidInterface !== 'undefined') {
          // return original document.cookie since Android does not support filtering of `httpOnly` cookies
          return win.CapacitorCookiesDescriptor?.get?.call(document) ?? '';
        }
      },
      set: function (val) {
        const cookiePairs = val.split(';');
        const domainSection = val.toLowerCase().split('domain=')[1];
        const domain =
          cookiePairs.length > 1 && domainSection != null && domainSection.length > 0
            ? domainSection.split(';')[0].trim()
            : '';

        if (platform === 'ios') {
          // Use prompt to synchronously set cookies.
          // https://stackoverflow.com/questions/29249132/wkwebview-complex-communication-between-javascript-native-code/49474323#49474323

          const payload = {
            type: 'CapacitorCookies.set',
            action: val,
            domain,
          };

          prompt(JSON.stringify(payload));
        } else if (typeof win.CapacitorCookiesAndroidInterface !== 'undefined') {
          win.CapacitorCookiesAndroidInterface.setCookie(domain, val);
        }
      },
    });
  }
};
