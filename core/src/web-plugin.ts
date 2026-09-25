import type { PluginListenerHandle, Plugin } from './definitions';
import { Capacitor } from './global';
import { ExceptionCode } from './util';
import type { CapacitorException } from './util';

/**
 * Base class web plugins should extend.
 *
 * `Events` maps the plugin's event names to the data each one carries. When it is given,
 * `notifyListeners` only accepts those names and data, for example
 * `class NetworkWeb extends WebPlugin<{ networkStatusChange: ConnectionStatus }>`.
 */
export class WebPlugin<Events extends Record<string, any> = Record<string, any>> implements Plugin {
  protected listeners: { [eventName: string]: ListenerCallback[] } = {};
  protected retainedEventArguments: { [eventName: string]: any[] } = {};
  protected windowListeners: { [eventName: string]: WindowListenerHandle } = {};

  addListener(eventName: string, listenerFunc: ListenerCallback): Promise<PluginListenerHandle> {
    let firstListener = false;

    const listeners = this.listeners[eventName];
    if (!listeners) {
      this.listeners[eventName] = [];
      firstListener = true;
    }

    this.listeners[eventName].push(listenerFunc);

    // If we haven't added a window listener for this event and it requires one,
    // go ahead and add it
    const windowListener = this.windowListeners[eventName];
    if (windowListener && !windowListener.registered) {
      this.addWindowListener(windowListener);
    }

    if (firstListener) {
      this.sendRetainedArgumentsForEvent(eventName);
    }

    const remove = async () => this.removeListener(eventName, listenerFunc);

    const p: any = Promise.resolve({ remove });

    return p;
  }

  async removeAllListeners(): Promise<void> {
    this.listeners = {};
    for (const listener in this.windowListeners) {
      this.removeWindowListener(this.windowListeners[listener]);
    }
    this.windowListeners = {};
  }

  protected notifyListeners<E extends keyof Events & string>(
    eventName: E,
    data: Events[E],
    retainUntilConsumed?: boolean,
  ): void {
    this.notifyEventListeners(eventName, data, retainUntilConsumed);
  }

  protected hasListeners(eventName: keyof Events & string): boolean {
    return !!this.listeners[eventName]?.length;
  }

  protected registerWindowListener(windowEventName: string, pluginEventName: keyof Events & string): void {
    this.windowListeners[pluginEventName] = {
      registered: false,
      windowEventName,
      pluginEventName,
      handler: (event) => {
        this.notifyEventListeners(pluginEventName, event);
      },
    };
  }

  protected unimplemented(msg = 'not implemented'): CapacitorException {
    return new Capacitor.Exception(msg, ExceptionCode.Unimplemented);
  }

  protected unavailable(msg = 'not available'): CapacitorException {
    return new Capacitor.Exception(msg, ExceptionCode.Unavailable);
  }

  private notifyEventListeners(eventName: string, data: unknown, retainUntilConsumed?: boolean): void {
    const listeners = this.listeners[eventName];
    if (!listeners?.length) {
      if (retainUntilConsumed) {
        let args = this.retainedEventArguments[eventName];
        if (!args) {
          args = [];
        }

        args.push(data);

        this.retainedEventArguments[eventName] = args;
      }

      return;
    }

    // Iterate over a copy: a listener that removes itself would otherwise make the next one be skipped.
    [...listeners].forEach((listener) => listener(data));
  }

  private async removeListener(eventName: string, listenerFunc: ListenerCallback): Promise<void> {
    const listeners = this.listeners[eventName];
    if (!listeners) {
      return;
    }

    const index = listeners.indexOf(listenerFunc);
    if (index !== -1) {
      this.listeners[eventName].splice(index, 1);
    }

    // If there are no more listeners for this type of event, forget the event so that the next
    // addListener counts as the first one again (retained arguments are then delivered), and
    // remove the window listener
    if (!this.listeners[eventName].length) {
      delete this.listeners[eventName];
      this.removeWindowListener(this.windowListeners[eventName]);
    }
  }

  private addWindowListener(handle: WindowListenerHandle): void {
    window.addEventListener(handle.windowEventName, handle.handler);
    handle.registered = true;
  }

  private removeWindowListener(handle: WindowListenerHandle): void {
    if (!handle) {
      return;
    }

    window.removeEventListener(handle.windowEventName, handle.handler);
    handle.registered = false;
  }

  private sendRetainedArgumentsForEvent(eventName: string): void {
    const args = this.retainedEventArguments[eventName];
    if (!args) {
      return;
    }

    delete this.retainedEventArguments[eventName];

    args.forEach((arg) => {
      this.notifyEventListeners(eventName, arg);
    });
  }
}

/**
 * A listener of a web plugin event. It receives the event's data.
 */
export type ListenerCallback<T = any> = (event: T) => void;

export interface WindowListenerHandle {
  registered: boolean;
  windowEventName: string;
  pluginEventName: string;
  handler: (event: any) => void;
}
