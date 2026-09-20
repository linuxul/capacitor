//
//  CAPPluginMethod.swift
//  Capacitor
//
//  Created by Steven Sherry on 4/18/24.
//  Copyright © 2024 Drifty Co. All rights reserved.
//

import Foundation

/// Represents a method that a plugin supports, along with the selector used to invoke it.
@objc(CAPPluginMethod)
public final class CAPPluginMethod: NSObject {
    /// How the result of the method is returned to JavaScript. The raw values are part of the JS protocol and must not change.
    public enum ReturnType: String {
        case promise, callback, none
    }

    /// Raw method name
    public let name: String
    /// The selector that is performed on the plugin, always of the form `name:`
    public let selector: Selector
    /// Return type of method (i.e. callback/promise/none)
    public let returnType: ReturnType

    public init(name: String, returnType: ReturnType = .promise) {
        self.name = name
        self.selector = NSSelectorFromString(name + ":")
        self.returnType = returnType
        super.init()
    }

    public init(_ selector: Selector, returnType: ReturnType = .promise) {
        // need to drop the : from the selector string
        let rawSelector = NSStringFromSelector(selector)
        self.name = rawSelector.hasSuffix(":") ? String(rawSelector.dropLast()) : rawSelector
        self.selector = selector
        self.returnType = returnType
        super.init()
    }
}
