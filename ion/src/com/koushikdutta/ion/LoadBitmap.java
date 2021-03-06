package com.koushikdutta.ion;

import android.graphics.Bitmap;
import android.graphics.Point;
import android.os.Looper;
import android.util.Log;

import com.koushikdutta.async.ByteBufferList;
import com.koushikdutta.async.future.FutureCallback;
import com.koushikdutta.ion.bitmap.BitmapInfo;
import com.koushikdutta.ion.gif.GifAction;
import com.koushikdutta.ion.gif.GifDecoder;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

class LoadBitmap extends LoadBitmapBase implements FutureCallback<ByteBufferList> {
    int resizeWidth;
    int resizeHeight;
    IonRequestBuilder.EmitterTransform<ByteBufferList> emitterTransform;
    boolean animateGif;

    public LoadBitmap(Ion ion, String urlKey, boolean put, int resizeWidth, int resizeHeight, boolean animateGif, IonRequestBuilder.EmitterTransform<ByteBufferList> emitterTransform) {
        super(ion, urlKey, put);
        this.resizeWidth = resizeWidth;
        this.resizeHeight = resizeHeight;
        this.animateGif = animateGif;
        this.emitterTransform = emitterTransform;
    }

    private boolean isGif() {
        if (emitterTransform == null)
            return false;
        if (emitterTransform.finalRequest != null) {
            URI uri = emitterTransform.finalRequest.getUri();
            if (uri != null && uri.toString().endsWith(".gif"))
                return true;
        }
        if (emitterTransform.headers == null)
            return false;
        return "image/gif".equals(emitterTransform.headers.get("Content-Type"));
    }

    @Override
    public void onCompleted(Exception e, final ByteBufferList result) {
        if (e != null) {
            report(e, null);
            return;
        }

        if (ion.bitmapsPending.tag(key) != this) {
            result.recycle();
            return;
        }

        Ion.getBitmapLoadExecutorService().execute(new Runnable() {
            @Override
            public void run() {
                if (ion.bitmapsPending.tag(key) != LoadBitmap.this) {
                    result.recycle();
                    return;
                }

                ByteBuffer bb = result.getAll();
                try {
                    Bitmap[] bitmaps;
                    int[] delays;
                    Point size;
                    if (!isGif()) {
                        size = new Point();
                        Bitmap bitmap = ion.bitmapCache.loadBitmap(bb.array(), bb.arrayOffset() + bb.position(), bb.remaining(), resizeWidth, resizeHeight, size);
                        if (bitmap == null)
                            throw new Exception("failed to load bitmap");
                        bitmaps = new Bitmap[] { bitmap };
                        delays = null;
                    }
                    else {
                        size = null;
                        GifDecoder decoder = new GifDecoder(bb.array(), bb.arrayOffset() + bb.position(), bb.remaining(), new GifAction() {
                            @Override
                            public boolean parseOk(boolean parseStatus, int frameIndex) {
                                return animateGif;
                            }
                        });
                        decoder.run();
                        if (decoder.getFrameCount() == 0)
                            throw new Exception("failed to load gif");
                        bitmaps = new Bitmap[decoder.getFrameCount()];
                        delays = decoder.getDelays();
                        for (int i = 0; i < decoder.getFrameCount(); i++) {
                            Bitmap bitmap = decoder.getFrameImage(i);
                            if (bitmap == null)
                                throw new Exception("failed to load gif frame");
                            bitmaps[i] = bitmap;
                            if (size == null)
                                size = new Point(bitmap.getWidth(), bitmap.getHeight());
                        }
                    }

                    BitmapInfo info = new BitmapInfo(key, bitmaps, size);
                    info.delays = delays;
                    if (emitterTransform != null)
                        info.loadedFrom = emitterTransform.loadedFrom();
                    else
                        info.loadedFrom = Loader.LoaderEmitter.LOADED_FROM_CACHE;

                    report(null, info);
                }
                catch (OutOfMemoryError e) {
                    report(new Exception(e), null);
                }
                catch (Exception e) {
                    report(e, null);
                }
                finally {
                    ByteBufferList.reclaim(bb);
                }
            }
        });
    }
}

    