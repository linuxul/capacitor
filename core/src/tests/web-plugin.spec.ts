/**
 * @jest-environment jsdom
 */

import { initBridge } from '../../native-bridge';
import type { WindowCapacitor } from '../definitions-internal';
import { createCapacitor } from '../runtime';
import { WebPlugin } from '../web-plugin';

const win = {};
initBridge(win);

class MockPlugin extends WebPlugin {
  trigger() {
    this.notifyListeners('test', {
      value: 'Capacitors on top of toast!',
    });
  }

  triggerRetained() {
    this.notifyListeners(
      'testRetained',
      {
        value: 'Test Retained Value 1',
      },
      true,
    );

    this.notifyListeners(
      'testRetained',
      {
        value: 'Test Retained Value 2',
      },
      true,
    );
  }

  getListeners() {
    return this.listeners;
  }

  getWindowListeners() {
    return this.windowListeners;
  }

  registerFakeWindowListener() {
    this.registerWindowListener('fake', 'test');
  }
}

describe('Web Plugin', () => {
  let plugin: MockPlugin;
  let win: WindowCapacitor;

  beforeEach(() => {
    win = {};
    createCapacitor(win);
    plugin = new MockPlugin();
  });

  it('Should add event listeners', async () => {
    const lf = (event: any) => {
      console.log(event);
    };

    const handle = await plugin.addListener('test', lf);

    const listener = plugin.getListeners()['test'];
    expect(listener).not.toBe(undefined);
    expect(listener.length).toEqual(1);
    handle.remove();
  });

  it('Should manage multiple event listeners', async () => {
    const lf1 = (event: any) => {
      console.log(event);
    };
    const lf2 = (event: any) => {
      console.log(event);
    };
    const lf3 = (event: any) => {
      console.log(event);
    };
    const handle1 = await plugin.addListener('test', lf1);
    const handle2 = await plugin.addListener('test', lf2);
    const handle3 = await plugin.addListener('test', lf3);

    const listener = plugin.getListeners()['test'];
    expect(listener.length).toEqual(3);
    handle1.remove();
    expect(listener.length).toEqual(2);
    handle2.remove();
    expect(listener.length).toEqual(1);
    handle3.remove();
    expect(listener.length).toEqual(0);
  });

  it('Should remove event listeners', async () => {
    const lf = (event: any) => {
      console.log(event);
    };
    const handle = await plugin.addListener('test', lf);
    await handle.remove();

    expect(plugin.getListeners()['test']).toBeUndefined();
  });

  it('Should call every listener when one removes itself while being notified', async () => {
    const calls: string[] = [];
    const handle = await plugin.addListener('test', async () => {
      calls.push('first');
      await handle.remove();
    });
    await plugin.addListener('test', () => calls.push('second'));

    plugin.trigger();

    expect(calls).toEqual(['first', 'second']);
  });

  it('Should retain events again after the last listener was removed', async () => {
    const first = await plugin.addListener('testRetained', jest.fn());
    await first.remove();

    plugin.triggerRetained();

    const lf = jest.fn();
    await plugin.addListener('testRetained', lf);
    expect(lf).toHaveBeenCalledTimes(2);
  });

  it('Should notify listeners', async () => {
    const lf = jest.fn();
    const handle = await plugin.addListener('test', lf);

    plugin.trigger();

    expect(lf.mock.calls.length).toEqual(1);
    expect(lf.mock.calls[0][0]).toEqual({
      value: 'Capacitors on top of toast!',
    });
    handle.remove();
  });

  it('Should submit retained events on event registration', async () => {
    const lf = jest.fn();
    plugin.triggerRetained();

    const handle = await plugin.addListener('testRetained', lf);

    expect(lf.mock.calls.length).toEqual(2);
    expect(lf.mock.calls[0][0]).toEqual({
      value: 'Test Retained Value 1',
    });
    expect(lf.mock.calls[1][0]).toEqual({
      value: 'Test Retained Value 2',
    });

    handle.remove();
  });

  it('Should register and remove window listeners', async () => {
    const pluginAddWindowListener = jest.spyOn(MockPlugin.prototype as any, 'addWindowListener');
    plugin.registerFakeWindowListener();

    const lf = jest.fn();
    const handle = await plugin.addListener('test', lf);

    // Make sure the window listener was added
    let windowListener = plugin.getWindowListeners()['test'];
    expect(windowListener.registered).toEqual(true);
    expect(pluginAddWindowListener.mock.calls.length).toEqual(1);

    // Trigger a custom window event
    const event = new CustomEvent('fake', {
      detail: { value: 'Capacitors on top of toast!' },
    });
    window.dispatchEvent(event);

    expect(lf.mock.calls.length).toEqual(1);

    const eventArg = lf.mock.calls[0][0];
    expect(eventArg.detail.value).toEqual('Capacitors on top of toast!');

    handle.remove();
    windowListener = plugin.getWindowListeners()['test'];
    expect(windowListener.registered).toEqual(false);
  });

  it('Should only call window event if listeners bound', () => {
    plugin.registerFakeWindowListener();

    // Make sure the window listener was added
    const windowListener = plugin.getWindowListeners()['test'];

    expect(windowListener.registered).toEqual(false);

    const handlerFunction = jest.spyOn(windowListener, 'handler');
    // Trigger a custom window event
    const event = new CustomEvent('fake', {
      detail: { value: 'Capacitors on top of toast!' },
    });
    window.dispatchEvent(event);

    expect(handlerFunction).not.toHaveBeenCalled();
  });

  it('Should not remove a listener if it is not found', async () => {
    const lf1 = (event: any) => {
      console.log(event);
    };
    const lf2 = (event: any) => {
      console.log(event);
    };
    const lf3 = (event: any) => {
      console.log(event);
    };

    await plugin.addListener('test', lf1);
    await plugin.addListener('test', lf2);

    const listenersBefore = plugin.getListeners()['test'];
    expect(listenersBefore.length).toEqual(2);

    // Try to remove a listener that was never added
    await (plugin as any).removeListener('test', lf3);

    const listenersAfter = plugin.getListeners()['test'];
    expect(listenersAfter.length).toEqual(2);
    expect(listenersAfter[0]).toBe(lf1);
    expect(listenersAfter[1]).toBe(lf2);
  });
});

// Compile-time checks: ts-jest type-checks this file, and an unused @ts-expect-error fails the run.
describe('Web Plugin event types', () => {
  interface TickEvent {
    count: number;
  }

  class TypedPlugin extends WebPlugin<{ tick: TickEvent }> {
    fire(count: number) {
      this.notifyListeners('tick', { count });
    }

    fireWrongly() {
      // @ts-expect-error not one of the plugin's events
      this.notifyListeners('tock', { count: 1 });
      // @ts-expect-error wrong data for the event
      this.notifyListeners('tick', { count: 'one' });
    }
  }

  it('delivers typed events', async () => {
    const plugin = new TypedPlugin();
    const listener = jest.fn();
    await plugin.addListener('tick', listener);

    plugin.fire(3);

    expect(listener).toHaveBeenCalledWith({ count: 3 });
  });
});
