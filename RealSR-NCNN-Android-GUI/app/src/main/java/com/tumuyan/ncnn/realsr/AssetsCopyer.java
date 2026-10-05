package com.tumuyan.ncnn.realsr;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import android.content.Context;
import android.content.res.AssetManager;
import android.text.TextUtils;
import android.util.Log;

public class AssetsCopyer {
    /** Repair pre-JNI/truncated installs and never mark failed extraction complete. */
    public static void releaseVersionedAssets(Context context, File root, int version) throws IOException {
        File marker = new File(root, ".assets-" + version + ".complete");
        AssetStreams.install(marker, () -> releaseAssetsChecked(context, "realsr", root));
    }

    private static void releaseAssetsChecked(Context context, String path, File parent) throws IOException {
        String[] entries = context.getAssets().list(path);
        if (entries == null) throw new IOException("Cannot list assets: " + path);
        String name = path.substring(path.lastIndexOf('/') + 1);
        File target = new File(parent, name);
        if (entries.length > 0) {
            if (!target.isDirectory() && !target.mkdirs())
                throw new IOException("Cannot create asset directory: " + target);
            for (String child : entries) releaseAssetsChecked(context, path + "/" + child, target);
        } else {
            AssetStreams.copy(target, context.getAssets().open(path), false);
        }
    }


    private static final String TAG = "AssetsCopyer";

    public static void releaseAssets(Context context, String assetsDir,
                                     String releaseDir, Boolean skipExistFile) {

//		Log.d(TAG, "context: " + context + ", " + assetsDir);
        if (TextUtils.isEmpty(releaseDir)) {
            return;
        }

        releaseDir = releaseDir.replaceFirst("/+$","");

        if (TextUtils.isEmpty(assetsDir) || assetsDir.equals("/")) {
            assetsDir = "";
        } else {
            assetsDir = assetsDir.replaceFirst("/+$","");
        }

        AssetManager assets = context.getAssets();
        try {
            String[] fileNames = assets.list(assetsDir);//只能获取到文件(夹)名,所以还得判断是文件夹还是文件
            if (fileNames.length > 0) {// is dir
                for (String name : fileNames) {
                    if (!TextUtils.isEmpty(assetsDir)) {
                        name = assetsDir + File.separator + name;//补全assets资源路径
                    }
//                    Log.i(, brian name= + name);
                    String[] childNames = assets.list(name);//判断是文件还是文件夹
                    if (!TextUtils.isEmpty(name) && childNames.length > 0) {
                        checkFolderExists(releaseDir + File.separator + name);
                        releaseAssets(context, name, releaseDir, skipExistFile);//递归, 因为资源都是带着全路径,
                        //所以不需要在递归是设置目标文件夹的路径
                    } else {
                        InputStream is = assets.open(name);
//                        FileUtil.writeFile(releaseDir + File.separator + name, is);
                        writeFile(releaseDir + File.separator + name, is, skipExistFile);
                    }
                }
            } else {// is file
                InputStream is = assets.open(assetsDir);
                // 写入文件前, 需要提前级联创建好路径, 下面有代码贴出
//                FileUtil.writeFile(releaseDir + File.separator + assetsDir, is);
                writeFile(releaseDir + File.separator + assetsDir, is, skipExistFile);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static boolean writeFile(String fileName, InputStream in, boolean skipExistFile) throws IOException {
        AssetStreams.copy(new File(fileName), in, skipExistFile);
        return true;
    }

    private static void checkFolderExists(String path) {
        File file = new File(path);
        if ((file.exists() && !file.isDirectory()) || !file.exists()) {
            file.mkdirs();
        }
    }
}
