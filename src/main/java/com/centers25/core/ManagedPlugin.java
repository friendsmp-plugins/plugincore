package com.centers25.core;

record ManagedPlugin(String id, String repository, String asset, String plugin, String file, String release) {
    ManagedPlugin withRelease(String value) {
        return new ManagedPlugin(id, repository, asset, plugin, file, value);
    }
}
