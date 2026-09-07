package com.particlesdevs.photoncamera.util;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class AssetLoader {
    private final Context context;

    public AssetLoader(Context context) {
        this.context = context;
    }

    public File getFile(String name) throws IOException {
        InputStream initialStream = context.getAssets().open(name, AssetManager.ACCESS_BUFFER);
        byte[] buffer = new byte[initialStream.available()];
        initialStream.read(buffer);
        File targetFile = new File(name);
        OutputStream outStream = new FileOutputStream(targetFile);
        outStream.write(buffer);
        outStream.close();
        return targetFile;
    }

    public InputStream getInputStream(String name) throws IOException {
        return context.getAssets().open(name, AssetManager.ACCESS_BUFFER);
    }
    public String getString(String name) {
        InputStream initialStream;
        try {
            initialStream = context.getAssets().open(name, AssetManager.ACCESS_BUFFER);
        } catch (IOException e) {
            // Swallowing this left initialStream null and the NullPointerException
            // surfaced two frames later inside InputStreamReader, which hid the real
            // problem (a wrong asset path) behind an unrelated stack trace.
            throw new IllegalArgumentException("Asset not found: " + name, e);
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(initialStream, StandardCharsets.UTF_8 ));
        String str = null;
        StringBuilder sb = new StringBuilder();
        while (true) {
            try {
                if ((str = br.readLine()) == null) break;
            } catch (IOException e) {
                e.printStackTrace();
            }
            sb.append(str).append("\n");
        }
        try {
            br.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return sb.toString();
    }
}
