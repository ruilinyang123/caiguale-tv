package com.fongmi.chaquo;

//lite 精简版的编译桩：不含 Python 运行时，Py 类型源由调用方得到空结果并优雅降级。
public class Loader {

    public Loader() {
    }

    public Spider spider(String api) {
        return new Spider();
    }
}
