/**
 * Note: When making changes to this file or to src/bridge/, run `npm run build:nativebridge`
 * afterwards to build the nativebridge.js files to the android and iOS projects.
 */
import { createLogFromNative, createLogToNative, patchConsole } from './src/bridge/console';
import { patchDocumentCookie } from './src/bridge/cookies-patch';
import { patchHttp } from './src/bridge/http-patch';
import type { PluginCallback } from './src/definitions';
import type {
  CallData,
  CapacitorInstance,
  ErrorCallData,
  StoredCallback,
  WindowCapacitor,
} from './src/definitions-internal';
import { CapacitorException, ExceptionCode, getPlatformId } from './src/util';

// For removing exports for iOS/Android, keep let for reassignment
// eslint-disable-next-line
let dummy = {};

// The id a call carries when it expects no response. Mirrors PluginCall.CALLBACK_ID_DANGLING on Android.
const CALLBACK_ID_DANGLING = '-1';

const initBridge = (w: any): void => {
  const convertFileSrcServerUrl = (webviewServerUrl: string, filePath: string): string => {
    if (typeof filePath === 'string') {
      if (filePath.startsWith('/')) {
        return webviewServerUrl + '/_capacitor_file_' + filePath;
      } else if (filePath.startsWith('file://')) {
        return webviewServerUrl + filePath.replace('file://', '/_capacitor_file_');
      } else if (filePath.startsWith('content://')) {
        return webviewServerUrl + filePath.replace('content:/', '/_capacitor_content_');
      }
    }
    return filePath;
  };

  const initEvents = (win: WindowCapacitor, cap: CapacitorInstance) => {
    // The callback argument is kept for compatibility; the bridge releases the listener's callback itself.
    const removeListener = (pluginName: string, callbackId: string, eventName: string, callback?: PluginCallback) => {
      cap.nativeCallback(
        pluginName,
        'removeListener',
        {
          callbackId: callbackId,
          eventName: eventName,
        },
        callback,
      );
    };

    cap.addListener = (pluginName, eventName, callback) => {
      // throws when the call cannot be sent to native
      const callbackId = cap.nativeCallback(
        pluginName,
        'addListener',
        {
          eventName: eventName,
        },
        callback,
      );
      return {
        remove: async () => {
          win?.console?.debug('Removing listener', pluginName, eventName);
          if (callbackId !== null) {
            removeListener(pluginName, callbackId, eventName, callback);
          }
        },
      };
    };

    cap.removeListener = removeListener;

    const createEvent = (eventName: string, eventData?: any): Event | null => {
      const doc = win.document;
      if (doc) {
        const ev = doc.createEvent('Events');
        ev.initEvent(eventName, false, false);
        if (eventData && typeof eventData === 'object') {
          for (const i in eventData) {
            // eslint-disable-next-line no-prototype-builtins
            if (eventData.hasOwnProperty(i)) {
              ev[i] = eventData[i];
            }
          }
        }
        return ev;
      }
      return null;
    };
    cap.createEvent = createEvent;

    cap.triggerEvent = (eventName, target, eventData) => {
      const doc = win.document;
      eventData = eventData || {};
      const ev = createEvent(eventName, eventData);

      if (ev) {
        if (target === 'document') {
          if (doc?.dispatchEvent) {
            return doc.dispatchEvent(ev);
          }
        } else if (target === 'window' && win.dispatchEvent) {
          return win.dispatchEvent(ev);
        } else if (doc?.querySelector) {
          const targetEl = doc.querySelector(target);
          if (targetEl) {
            return targetEl.dispatchEvent(ev);
          }
        }
      }
      return false;
    };

    win.Capacitor = cap;
  };

  // Cordova is not supported (see BREAKING.md) and window.cordova is no longer defined, but these
  // three shims are kept on purpose: navigator.app.exitApp(), the synthetic `deviceready` document
  // event and the `backbutton` interception. Ionic's hardware back button relies on the last two.
  const initLegacyHandlers = (win: WindowCapacitor, cap: CapacitorInstance) => {
    const doc = win.document;
    const nav = win.navigator;

    if (nav) {
      nav.app = nav.app || {};
      nav.app.exitApp = () => {
        if (!cap.Plugins?.App) {
          win.console?.warn('App plugin not installed');
        } else {
          cap.nativeCallback('App', 'exitApp', {});
        }
      };
    }

    if (doc) {
      const docAddEventListener = doc.addEventListener;
      doc.addEventListener = (...args: any[]) => {
        const eventName = args[0];
        const handler = args[1];
        if (eventName === 'deviceready' && handler) {
          Promise.resolve().then(handler);
        } else if (eventName === 'backbutton' && cap.Plugins.App) {
          // Add a dummy listener so Capacitor doesn't do the default
          // back button action
          cap.Plugins.App.addListener('backButton', () => {
            // ignore
          });
        }
        return docAddEventListener.apply(doc, args);
      };
    }

    win.Capacitor = cap;
  };

  const initVendor = (win: WindowCapacitor, cap: CapacitorInstance) => {
    const Ionic = (win.Ionic = win.Ionic || {});
    const IonicWebView = (Ionic.WebView = Ionic.WebView || {});
    const Plugins = cap.Plugins;

    IonicWebView.getServerBasePath = (callback: (path: string) => void) => {
      Plugins?.WebView?.getServerBasePath().then((result: any) => {
        callback(result.path);
      });
    };

    IonicWebView.setServerAssetPath = (path: any) => {
      Plugins?.WebView?.setServerAssetPath({ path });
    };

    IonicWebView.setServerBasePath = (path: any) => {
      Plugins?.WebView?.setServerBasePath({ path });
    };

    IonicWebView.persistServerBasePath = () => {
      Plugins?.WebView?.persistServerBasePath();
    };

    IonicWebView.convertFileSrc = (url: string) => cap.convertFileSrc(url);

    win.Capacitor = cap;
    win.Ionic.WebView = IonicWebView;
  };

  const initLogger = (win: WindowCapacitor, cap: CapacitorInstance) => {
    const platform = getPlatformId(win);

    if (platform === 'android' || platform === 'ios') {
      patchDocumentCookie(win, platform);
      patchHttp(win, cap, platform);
    }

    if (platform === 'ios') {
      patchConsole(win, cap);
    }

    cap.logJs = (msg, level) => {
      switch (level) {
        case 'error':
          win.console?.error(msg);
          break;
        case 'warn':
          win.console?.warn(msg);
          break;
        case 'info':
          win.console?.info(msg);
          break;
        default:
          win.console?.log(msg);
      }
    };

    cap.logToNative = createLogToNative(win.console ?? {});
    cap.logFromNative = createLogFromNative(win.console ?? {});

    cap.handleError = (err) => win.console?.error(err);

    win.Capacitor = cap;
  };

  function initNativeBridge(win: WindowCapacitor) {
    const cap = win.Capacitor || ({} as CapacitorInstance);

    // keep a collection of callbacks for native response data, with the call each one belongs to
    const callbacks = new Map<string, StoredCallback & { pluginId: string; methodName: string }>();

    const webviewServerUrl = typeof win.WEBVIEW_SERVER_URL === 'string' ? win.WEBVIEW_SERVER_URL : '';
    cap.getServerUrl = () => webviewServerUrl;
    cap.convertFileSrc = (filePath) => convertFileSrcServerUrl(webviewServerUrl, filePath);

    // Callback ids are random UUIDs (v4) so that a pending id cannot be guessed from an earlier one,
    // and a call that comes back from an old session after a reload never matches a new id.
    // crypto.getRandomValues works on every page, including one served over plain http such as a live
    // reload server. crypto.randomUUID would be shorter but is undefined outside secure contexts
    // (https and localhost), so it is not used.
    const createCallbackId = (): string => {
      if (!win.crypto) {
        throw new Error('window.crypto is required to create callback ids');
      }
      const bytes = win.crypto.getRandomValues(new Uint8Array(16));
      bytes[6] = (bytes[6] & 0x0f) | 0x40;
      bytes[8] = (bytes[8] & 0x3f) | 0x80;
      const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
      return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
    };

    let postToNative: ((data: CallData) => void) | null = null;

    const isNativePlatform = () => true;
    const getPlatform = () => getPlatformId(win);

    cap.getPlatform = getPlatform;
    cap.isPluginAvailable = (name) => Object.prototype.hasOwnProperty.call(cap.Plugins, name);
    cap.isNativePlatform = isNativePlatform;

    // create the postToNative() fn if needed
    const androidBridge = win.androidBridge;
    const iosBridge = win.webkit?.messageHandlers?.bridge;
    if (androidBridge) {
      // android platform
      postToNative = (data) => {
        androidBridge.postMessage(JSON.stringify(data));
      };
    } else if (iosBridge) {
      // ios platform
      postToNative = (data) => {
        data.type = data.type ? data.type : 'message';
        iosBridge.postMessage(data);
      };
    }

    cap.handleWindowError = (msg, url, lineNo, columnNo, err) => {
      // window.onerror receives an Event instead of a message for errors such as a failed resource load
      const message = typeof msg === 'string' ? msg : (err?.message ?? String(msg?.type ?? msg));

      if (message.toLowerCase().indexOf('script error') > -1) {
        // A cross-origin script error carries no details
      } else {
        let errorObject: string;
        try {
          errorObject =
            err instanceof Error
              ? JSON.stringify({ name: err.name, message: err.message, stack: err.stack })
              : (JSON.stringify(err) ?? '');
        } catch (e) {
          errorObject = String(err);
        }

        const errObj: ErrorCallData = {
          type: 'js.error',
          error: {
            message,
            url: url,
            line: lineNo,
            col: columnNo,
            errorObject,
          },
        };

        if (err != null) {
          cap.handleError(err);
        }

        try {
          postToNative?.(errObj);
        } catch (e) {
          win?.console?.error(e);
        }
      }

      return false;
    };

    if (cap.DEBUG) {
      window.onerror = cap.handleWindowError;
    }

    initLogger(win, cap);

    /**
     * Send a plugin method call to the native layer
     */
    const toNative: NonNullable<CapacitorInstance['toNative']> = (pluginName, methodName, options, storedCallback) => {
      let callbackId = CALLBACK_ID_DANGLING;
      try {
        if (typeof postToNative === 'function') {
          if (methodName === 'removeListener' && typeof options?.callbackId === 'string') {
            // Native releases its side of the listener and never answers removeListener, so the
            // listener's callback is released here, and the call itself gets no callback to keep.
            callbacks.delete(options.callbackId);
            storedCallback = undefined;
          } else if (methodName === 'removeAllListeners') {
            // native drops every listener of the plugin; release their callbacks too
            for (const [id, stored] of callbacks) {
              if (stored.pluginId === pluginName && stored.methodName === 'addListener') {
                callbacks.delete(id);
              }
            }
          }

          if (
            storedCallback &&
            (typeof storedCallback.callback === 'function' || typeof storedCallback.resolve === 'function')
          ) {
            // store the call for later lookup
            callbackId = createCallbackId();
            callbacks.set(callbackId, { ...storedCallback, pluginId: pluginName, methodName });
          }

          const callData = {
            callbackId: callbackId,
            pluginId: pluginName,
            methodName: methodName,
            options: options || {},
          };

          if (cap.isLoggingEnabled && pluginName !== 'Console') {
            cap.logToNative(callData);
          }

          // post the call data to native
          postToNative(callData);

          return callbackId;
        } else {
          throw new CapacitorException(`implementation unavailable for: ${pluginName}`, ExceptionCode.Unavailable);
        }
      } catch (e) {
        callbacks.delete(callbackId);
        const error = e instanceof Error ? e : new Error(String(e));
        win?.console?.error(error);
        if (methodName === 'addListener') {
          // The callback of addListener is the event listener, which expects events, not this
          // error: let the addListener call reject instead.
          throw error;
        }
        if (typeof storedCallback?.callback === 'function') {
          storedCallback.callback(null, error);
        } else {
          storedCallback?.reject?.(error);
        }
      }

      return null;
    };
    cap.toNative = toNative;

    if (win?.androidBridge) {
      win.androidBridge.onmessage = function (event) {
        returnResult(JSON.parse(event.data));
      };
    }

    /**
     * Process a response from the native layer.
     */
    cap.fromNative = (result) => {
      returnResult(result);
    };

    const returnResult = (result: any) => {
      if (cap.isLoggingEnabled && result.pluginId !== 'Console') {
        cap.logFromNative(result);
      }

      // get the stored call, if it exists
      try {
        const storedCall = callbacks.get(result.callbackId);

        if (storedCall) {
          // looks like we've got a stored call

          if (result.error) {
            // ensure stacktraces by copying error properties to an Error
            result.error = Object.keys(result.error).reduce((err, key) => {
              // use any type to avoid importing util and compiling most of .ts files
              (err as any)[key] = (result as any).error[key];
              return err;
            }, new cap.Exception(''));
          }

          if (typeof storedCall.callback === 'function') {
            // callback
            if (result.success) {
              storedCall.callback(result.data);
            } else {
              storedCall.callback(null, result.error);
            }
          } else if (typeof storedCall.resolve === 'function') {
            // promise
            if (result.success) {
              storedCall.resolve(result.data);
            } else {
              storedCall.reject?.(result.error);
            }

            // no need to keep this stored callback
            // around for a one time resolve promise
            callbacks.delete(result.callbackId);
          }
        } else if (!result.success && result.error) {
          // no stored callback, but if there was an error let's log it
          win?.console?.warn(result.error);
        }

        if (result.save === false) {
          callbacks.delete(result.callbackId);
        }
      } catch (e) {
        win?.console?.error(e);
      }

      // always delete to prevent memory leaks
      // overkill but we're not sure what apps will do with this data
      delete result.data;
      delete result.error;
    };

    cap.nativeCallback = (pluginName, methodName, options, callback) =>
      toNative(pluginName, methodName, options, { callback });

    cap.nativePromise = (pluginName, methodName, options) => {
      return new Promise((resolve, reject) => {
        toNative(pluginName, methodName, options, {
          resolve: resolve,
          reject: reject,
        });
      });
    };

    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    cap.withPlugin = (_pluginId, _fn) => dummy;

    cap.Exception = CapacitorException;

    initEvents(win, cap);
    initLegacyHandlers(win, cap);
    initVendor(win, cap);

    win.Capacitor = cap;
  }

  initNativeBridge(w);
};

initBridge(
  typeof globalThis !== 'undefined'
    ? (globalThis as WindowCapacitor)
    : typeof self !== 'undefined'
      ? (self as WindowCapacitor)
      : typeof window !== 'undefined'
        ? (window as WindowCapacitor)
        : typeof global !== 'undefined'
          ? (global as WindowCapacitor)
          : ({} as WindowCapacitor),
);

// Export only for tests
export { initBridge };
