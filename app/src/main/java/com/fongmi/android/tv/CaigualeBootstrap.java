package com.fongmi.android.tv;

import android.content.Context;
import android.content.res.AssetManager;

import com.fongmi.android.tv.setting.CustomCspSetting;
import com.github.catvod.utils.Prefers;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 菜瓜乐影视首启引导：
 * 把随 APK 打包的 assets/caiguale 首页资源释放到 App 私有目录，
 * 并注册成 CustomCsp 里的 webHome 站点，同时设为默认首页。
 * 幂等：版本号未变则只校验文件是否存在，不重复写。
 */
public final class CaigualeBootstrap {

    private static final String ASSET_DIR = "caiguale";
    private static final String ITEM_ID = "caiguale";
    private static final String VERSION_KEY = "caiguale_home_version";
    private static final int VERSION = 1;

    private CaigualeBootstrap() {
    }

    public static void ensure(Context context) {
        try {
            File dir = new File(CustomCspSetting.dir(), ITEM_ID);
            boolean fresh = Prefers.getInt(VERSION_KEY, 0) < VERSION || !new File(dir, "index.html").exists();
            if (fresh) {
                copyAssets(context.getAssets(), ASSET_DIR, dir);
                Prefers.put(VERSION_KEY, String.valueOf(VERSION));
            }
            register();
        } catch (Throwable e) {
            // 引导失败不能挡住 App 启动：退回原生首页。
            android.util.Log.w("CaigualeBootstrap", "bootstrap failed", e);
        }
    }

    private static void register() {
        CustomCspSetting.Registry registry = CustomCspSetting.load();
        String homePage = CustomCspSetting.localUrl(ITEM_ID, "index.html");
        CustomCspSetting.Item item = null;
        for (CustomCspSetting.Item exist : registry.getItems()) {
            if (ITEM_ID.equals(exist.getId())) {
                item = exist;
                break;
            }
        }
        if (item == null) {
            item = new CustomCspSetting.Item();
            item.setId(ITEM_ID);
            item.setKey(CustomCspSetting.keyFor(ITEM_ID));
            item.setWebHome(true);
            item.setType(3);
            item.setApi("csp_Builtin");
            registry.getItems().add(item);
        }
        item.setEnabled(true);
        item.setKind("webHome");
        item.setWebHome(true);
        item.setName("菜瓜乐影视");
        item.setHomePage(homePage);
        registry.setEnabled(true);
        registry.setHomeKey(item.getKey());
        CustomCspSetting.save(registry.normalize());
    }

    private static void copyAssets(AssetManager assets, String path, File target) throws IOException {
        String[] children = assets.list(path);
        if (children == null) return;
        if (children.length == 0) {
            copyFile(assets, path, target);
            return;
        }
        if (!target.exists() && !target.mkdirs()) throw new IOException("mkdirs failed: " + target);
        for (String child : children) copyAssets(assets, path + "/" + child, new File(target, child));
    }

    private static void copyFile(AssetManager assets, String path, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("mkdirs failed: " + parent);
        try (InputStream in = assets.open(path); OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        }
    }
}
