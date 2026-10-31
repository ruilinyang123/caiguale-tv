package com.fongmi.android.tv.api.loader;

final class CspClassLoadingPolicy {

    private static final String PROTOBUF_PREFIX = "com.google.protobuf.";

    private CspClassLoadingPolicy() {
    }

    static boolean isProtobuf(String name) {
        return name != null && name.startsWith(PROTOBUF_PREFIX);
    }

    static boolean isChildFirst(String name, boolean jarContainsProtobuf) {
        return jarContainsProtobuf && isProtobuf(name);
    }
}
