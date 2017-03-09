/*
 * Copyright 2013 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.myth.frameplayer;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Timer;
import java.util.TimerTask;



public class FramePlayer implements Runnable {
    private static final String TAG = FramePlayer.class.getSimpleName();


    private static final int MSG_PLAY_START = 0;

    private static final int MSG_PLAY_PROGRESS = 1;


    private static final boolean VERBOSE = true;

    private MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();

    final int TIMEOUT_USEC = 10000;

    public void setSourceFile(File sourceFile) {
        this.sourceFile = sourceFile;
    }

    private File sourceFile;
    private Surface mOutputSurface;
    private int mVideoWidth;
    private int mVideoHeight;

    private MediaExtractor mMediaExtractor;
    private MediaCodec mMediaCodec;
    private int mTrackIndex;
    private Thread mThread;
    private LocalHandler mLocalHandler;

    public void setPlayListener(PlayListener playListener) {
        this.playListener = playListener;
    }

    private PlayListener playListener;

    public void setFrameInterval(int frameRate) {
        if (frameRate < 1 || frameRate > 100) {
            throw new IllegalArgumentException("frame rate must between 1 to 100");
        }
        this.mFrameInterval = 1000 / frameRate;
    }

    private int mFrameInterval;


    public FramePlayer(Surface outputSurface) {
        mOutputSurface = outputSurface;
    }

    private int frame;
    private Timer timer;

    private TimerTask timerTask;

    private long duration;

    public void nextFrame() {
        mLocalHandler.sendMessage(mLocalHandler.obtainMessage(MSG_PLAY_PROGRESS, frame++, 0));
    }

    private class ProgressTimerTask extends TimerTask {
        @Override
        public void run() {
            if (isRunning) {
                mLocalHandler.sendMessage(mLocalHandler.obtainMessage(MSG_PLAY_PROGRESS, frame++, 0));
            }
        }
    }


    @Override
    public void run() {
        // Establish a Looper for this thread, and define a Handler for it.
        Looper.prepare();
        mLocalHandler = new LocalHandler();
        Looper.loop();
    }

    private class LocalHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            int what = msg.what;
            Log.d(TAG, "handleMessage:" + what);
            switch (what) {
                case MSG_PLAY_START:
                    try {
                        play();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    break;
                case MSG_PLAY_PROGRESS:
                    doExtract(msg.arg1);
                    break;
                default:
                    throw new RuntimeException("Unknown msg " + what);
            }
        }
    }


    /**
     * Creates a new thread, and starts execution of the player.
     */
    public void execute() {
        mThread = new Thread(this, "Movie Player");
        mThread.start();
    }


    public void start() {
        stop();
        frame = 0;
        isRunning = true;
        mLocalHandler.sendEmptyMessage(MSG_PLAY_START);
    }

    public void stop() {
        if (timer != null) {
            timer.cancel();
            timerTask.cancel();
            timer = null;
            timerTask = null;
            isRunning = false;
        }
    }


    public boolean isRunning() {
        return isRunning;
    }

    private volatile boolean isRunning;

    public void pause() {
        if (isRunning) {
            isRunning = false;
        }
    }

    public void resume() {
        if (!isRunning) {
            isRunning = true;
        }
    }


    /**
     * Returns the width, in pixels, of the video.
     */
    public int getVideoWidth() {
        return mVideoWidth;
    }

    /**
     * Returns the height, in pixels, of the video.
     */
    public int getVideoHeight() {
        return mVideoHeight;
    }


    private void play() throws IOException {
        if (!sourceFile.canRead()) {
            throw new FileNotFoundException("Unable to read " + sourceFile);
        }
        try {
            destroyExtractor();
            mMediaExtractor = new MediaExtractor();
            mMediaExtractor.setDataSource(sourceFile.toString());
            mTrackIndex = selectTrack(mMediaExtractor);
            if (mTrackIndex < 0) {
                throw new RuntimeException("No video track found in " + sourceFile);
            }
            mMediaExtractor.selectTrack(mTrackIndex);

            MediaFormat format = mMediaExtractor.getTrackFormat(mTrackIndex);
            if (mFrameInterval == 0) {
                mFrameInterval = 1000 / format.getInteger(MediaFormat.KEY_FRAME_RATE);
            }
            String mime = format.getString(MediaFormat.KEY_MIME);
            duration = format.getLong(MediaFormat.KEY_DURATION);
            mMediaCodec = MediaCodec.createDecoderByType(mime);
            mMediaCodec.configure(format, mOutputSurface, null, 0);
            mMediaCodec.start();

            timer = new Timer();
            timerTask = new ProgressTimerTask();
            timer.schedule(timerTask, 0, mFrameInterval);
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            destroyExtractor();
        }
    }

    private void destroyExtractor() {
        if (mMediaCodec != null) {
            mMediaCodec.stop();
            mMediaCodec.release();
            mMediaCodec = null;
        }
        if (mMediaExtractor != null) {
            mMediaExtractor.release();
            mMediaExtractor = null;
        }
    }

    /**
     * Selects the video track, if any.
     *
     * @return the track index, or -1 if no video track is found.
     */
    private static int selectTrack(MediaExtractor mMediaExtractor) {
        // Select the first video track we find, ignore the rest.
        int numTracks = mMediaExtractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = mMediaExtractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("video/")) {
                if (VERBOSE) {
                    Log.d(TAG, "Extractor selected track " + i + " (" + mime + "): " + format);
                }
                return i;
            }
        }

        return -1;
    }


    private void doExtract(int frame) {
        ByteBuffer inputBuffer;
        if (mMediaCodec == null) {
            return;
        }
        int inputBufferIndex = mMediaCodec.dequeueInputBuffer(TIMEOUT_USEC);
        if (inputBufferIndex >= 0) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                // 从输入队列里去空闲buffer
                inputBuffer = mMediaCodec.getInputBuffers()[inputBufferIndex];
                inputBuffer.clear();
            } else {
                // SDK_INT > LOLLIPOP
                inputBuffer = mMediaCodec.getInputBuffer(inputBufferIndex);
            }
            if (null != inputBuffer) {
                int chunkSize = mMediaExtractor.readSampleData(inputBuffer, 0);
                if (chunkSize < 0) {
                    if (isRunning && playListener != null) {
                        playListener.onCompleted();
                        isRunning = false;
                        destroyExtractor();
                        return;
                    }
                    mMediaCodec.queueInputBuffer(inputBufferIndex, 0, 0, 0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    if (VERBOSE) Log.d(TAG, "sent input EOS");
                } else {
                    mMediaCodec.queueInputBuffer(inputBufferIndex, 0, chunkSize, 0, 0);
                    mMediaExtractor.advance();
                }
            }
            int outputBufferIndex = mMediaCodec.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            Log.d(TAG, outputBufferIndex + ":outputBufferIndex");
            if (outputBufferIndex >= 0) {
                mMediaCodec.releaseOutputBuffer(outputBufferIndex, true);
                if (playListener != null) {
                    playListener.onProgress((frame + 1) * mFrameInterval * 1f / duration);
                }
            } else {
                Log.d(TAG, "Reached EOS, looping");
            }
        }
    }

    public interface PlayListener {

        void onCompleted();

        void onProgress(float progress);
    }


}
