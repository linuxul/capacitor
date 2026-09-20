package com.getcapacitor

public class ServerPath(public val type: PathType, public val path: String) {
    public enum class PathType {
        BASE_PATH,
        ASSET_PATH
    }
}
