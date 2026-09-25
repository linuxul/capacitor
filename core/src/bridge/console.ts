import type { CapacitorInstance, MessageCallData, PluginResult, WindowCapacitor } from '../definitions-internal';

const BRIDGED_CONSOLE_METHODS: (keyof Console)[] = ['debug', 'error', 'info', 'log', 'trace', 'warn'];

export const createLogFromNative =
  (c: Partial<Console>): ((result: PluginResult) => void) =>
  (result: PluginResult) => {
    if (isFullConsole(c)) {
      const success = result.success === true;

      const tagStyles = success
        ? 'font-style: italic; font-weight: lighter; color: gray'
        : 'font-style: italic; font-weight: lighter; color: red';
      c.groupCollapsed(
        '%cresult %c' + result.pluginId + '.' + result.methodName + ' (#' + result.callbackId + ')',
        tagStyles,
        'font-style: italic; font-weight: bold; color: #444',
      );
      if (result.success === false) {
        c.error(result.error);
      } else {
        c.dir(JSON.stringify(result.data));
      }
      c.groupEnd();
    } else {
      if (result.success === false) {
        c.error?.('LOG FROM NATIVE', result.error);
      } else {
        c.log?.('LOG FROM NATIVE', result.data);
      }
    }
  };

export const createLogToNative =
  (c: Partial<Console>): ((call: MessageCallData) => void) =>
  (call: MessageCallData) => {
    if (isFullConsole(c)) {
      c.groupCollapsed(
        '%cnative %c' + call.pluginId + '.' + call.methodName + ' (#' + call.callbackId + ')',
        'font-weight: lighter; color: gray',
        'font-weight: bold; color: #000',
      );
      c.dir(call);
      c.groupEnd();
    } else {
      c.log?.('LOG TO NATIVE: ', call);
    }
  };

const isFullConsole = (c: Partial<Console>): c is Console => {
  if (!c) {
    return false;
  }

  return typeof c.groupCollapsed === 'function' || typeof c.groupEnd === 'function' || typeof c.dir === 'function';
};

const serializeConsoleMessage = (msg: unknown): string => {
  if (msg instanceof Error) {
    // JSON.stringify(error) would give "{}"
    return `${msg.name}: ${msg.message}${msg.stack ? `\n${msg.stack}` : ''}`;
  }
  if (typeof msg === 'object' && msg !== null) {
    // an object seen twice (a cycle, or a shared reference) is written once
    const seen = new WeakSet<object>();
    try {
      const json = JSON.stringify(msg, (_key, value) => {
        if (value instanceof Error) {
          return `${value.name}: ${value.message}`;
        }
        if (typeof value === 'object' && value !== null) {
          if (seen.has(value)) {
            return '[Circular]';
          }
          seen.add(value);
        }
        return value;
      });
      return json ?? String(msg);
    } catch (e) {
      return String(msg);
    }
  }
  return String(msg);
};

/**
 * On iOS, forwards console output to native so it appears in the Xcode console, then calls the
 * original console method.
 */
export const patchConsole = (win: WindowCapacitor, cap: CapacitorInstance): void => {
  // patch window.console on iOS and store original console fns
  const winConsole = win.console;
  if (winConsole) {
    // Set while a message is on its way to native: anything the bridge logs itself (for example an
    // error from toNative) then goes to the original console only instead of recursing.
    let forwarding = false;
    Object.defineProperties(
      winConsole,
      BRIDGED_CONSOLE_METHODS.reduce((props: PropertyDescriptorMap, method) => {
        const consoleMethod = (winConsole[method] as (...args: unknown[]) => void).bind(winConsole);
        props[method] = {
          configurable: true,
          enumerable: true,
          writable: true,
          value: (...args: unknown[]) => {
            if (!forwarding) {
              forwarding = true;
              try {
                cap.toNative?.('Console', 'log', {
                  level: method,
                  message: args.map(serializeConsoleMessage).join(' '),
                });
              } finally {
                forwarding = false;
              }
            }
            return consoleMethod(...args);
          },
        };
        return props;
      }, {}),
    );
  }
};
