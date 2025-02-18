package me.eldodebug.mememorilauncher.util;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

public class MememoriUtil {

    // メメントモリのパッケージ名
    public static final String PACKAGE_NAME = "jp.boi.mementomori.apk";

    // メメントモリがインストールされているかどうかを判定する
    public static boolean hasMememori(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(PACKAGE_NAME);
        return intent != null;
    }

    // メメントモリのバージョンを確認する
    public static String getVersionName(Context context) {
        try {
            PackageManager packageManager = context.getPackageManager();
            PackageInfo packageInfo = packageManager.getPackageInfo(PACKAGE_NAME, 0);
            return packageInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }
}
