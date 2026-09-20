//
//  CAPBridgedPlugin.swift
//  Capacitor
//
//  Created by Steven Sherry on 3/1/23.
//  Copyright © 2023 Drifty Co. All rights reserved.
//

import Foundation

/// The metadata a plugin must provide in order to be registered with the bridge and exported to JavaScript.
public protocol CAPBridgedPlugin: AnyObject {
    /// The name of the plugin class as seen by the Obj-C runtime (i.e. the name given to `@objc(Name)`).
    var identifier: String { get }
    /// The name under which the plugin is exposed in JavaScript.
    var jsName: String { get }
    /// The methods that are exported to JavaScript.
    var pluginMethods: [CAPPluginMethod] { get }
}

extension CAPBridgedPlugin {
    func getMethod(named name: String) -> CAPPluginMethod? {
        pluginMethods.first { $0.name == name }
    }
}
