package com.particlesdevs.photoncamera.gallery.glide;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.module.AppGlideModule;

import java.io.InputStream;
import java.nio.ByteBuffer;

/** P59: the app's Glide components: DNG files through {@link DngGlideDecoder}, AVIF files through {@link AvifGlideDecoder}. */
@GlideModule
public final class ScameraGlideModule extends AppGlideModule {
    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {
        registry.prepend(Registry.BUCKET_BITMAP, InputStream.class, Bitmap.class, new DngGlideDecoder(context, glide.getBitmapPool()));
        registry.prepend(Registry.BUCKET_BITMAP, InputStream.class, Bitmap.class, new AvifGlideDecoder(context, glide.getBitmapPool()));
        registry.prepend(Registry.BUCKET_BITMAP, ByteBuffer.class, Bitmap.class, new AvifGlideDecoder.ByteBufferDecoder(glide.getBitmapPool()));
    }

    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
}
