package com.fongmi.android.tv.api.loader;

import dalvik.system.DexClassLoader;

final class CspDexClassLoader extends DexClassLoader {

    private final boolean jarContainsProtobuf;

    CspDexClassLoader(String dexPath, String optimizedDirectory, String librarySearchPath, ClassLoader parent, boolean jarContainsProtobuf) {
        super(dexPath, optimizedDirectory, librarySearchPath, parent);
        this.jarContainsProtobuf = jarContainsProtobuf;
    }

    @Override
    protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!CspClassLoadingPolicy.isChildFirst(name, jarContainsProtobuf)) return super.loadClass(name, resolve);
        Class<?> loaded = findLoadedClass(name);
        if (loaded == null) {
            try {
                loaded = findClass(name);
            } catch (ClassNotFoundException e) {
                throw e;
            }
        }
        if (resolve) resolveClass(loaded);
        return loaded;
    }
}
