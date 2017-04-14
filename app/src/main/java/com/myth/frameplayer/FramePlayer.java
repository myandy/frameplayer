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

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.AsyncTask;
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

import static android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC;


public class FramePlayer implements Runnable {
    private static final String TAG = FramePlayer.class.getSimpleName();


    private static final int MSG_PLAY_START = 0;

    private static final int MSG_PLAY_PROGRESS = 1;


    private static final boolean VERBOSE = true;

    private MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
    private ByteBuffer inputBuffer;

    final int TIMEOUT_USEC = 10000;
    private AudioTrack audioTrack;

    public void setSourceFile(File sourceFile) {
        this.sourceFile = sourceFile;
    }

    private File sourceFile;
    private Surface mOutputSurface;
    private int mVideoWidth;
    private int mVideoHeight;

    private MediaExtractor mAudioExtractor;
    private MediaExtractor mMediaExtractor;
    private MediaCodec mAudioCodec;
    private MediaCodec mMediaCodec;
    private int mTrackIndex;
    private int mAudioTrackIndex;
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
    private int videoFrameRate;


    public FramePlayer(Surface outputSurface) {
        mOutputSurface = outputSurface;
    }

    private int frame;
    private Timer timer;
    private TimerTask timerTask;

    private long duration;
    private boolean seekOffsetFlag = false;
    private boolean isLoop = false;
    private boolean hadPlay = false;
    private int seekOffset = 0;
    private boolean doStop = false;
    private AudioPlayTask mAudioPlayTask;

    private volatile boolean isRunning;

    public void nextFrame() {
        mLocalHandler.sendMessage(mLocalHandler.obtainMessage(MSG_PLAY_PROGRESS, frame++, 0));
    }

    private class ProgressTimerTask extends java.util.TimerTask {
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
        if (mAudioPlayTask != null) {
            mAudioPlayTask.cancel(true);
        }
        doStop = true;
        seekOffset = 0;
        seekOffsetFlag = false;
    }


    public boolean isRunning() {
        return isRunning;
    }


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
            MediaFormat format = mMediaExtractor.getTrackFormat(mTrackIndex);
            videoFrameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE);
            if (mFrameInterval == 0) {
                mFrameInterval = 1000 / videoFrameRate;
            }
            String mime = format.getString(MediaFormat.KEY_MIME);
            duration = format.getLong(MediaFormat.KEY_DURATION);


            mAudioExtractor = new MediaExtractor();
            mAudioExtractor.setDataSource(sourceFile.toString());
            mAudioTrackIndex = selectAudioTrack(mAudioExtractor);

            //only support pcm
            if (mAudioTrackIndex != -1) {
                relaxResources(true);
                MediaFormat audioFormat = mMediaExtractor.getTrackFormat(mAudioTrackIndex);
                String audioMime = audioFormat.getString(MediaFormat.KEY_MIME);
                try {
                    // 实例化一个指定类型的解码器,提供数据输出
                    mAudioCodec = MediaCodec.createDecoderByType(audioMime);
                } catch (IOException e) {
                    e.printStackTrace();
                }

                mAudioCodec.configure(audioFormat, null /* surface */, null /* crypto */, 0 /* flags */);
                mAudioCodec.start();


                int channels = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                int sampleRate = (int) (1.0f * audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) * (1000 / videoFrameRate) / mFrameInterval);
                int channelConfiguration = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
                audioTrack = new AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        sampleRate,
                        channelConfiguration,
                        AudioFormat.ENCODING_PCM_16BIT,
                        AudioTrack.getMinBufferSize(
                                sampleRate,
                                channelConfiguration,
                                AudioFormat.ENCODING_PCM_16BIT
                        ),
                        AudioTrack.MODE_STREAM
                );

                //开始play，等待write发出声音
                audioTrack.play();
                mAudioExtractor.selectTrack(mAudioTrackIndex);
            }


            mMediaExtractor.selectTrack(mTrackIndex);
            mMediaCodec = MediaCodec.createDecoderByType(mime);
            mMediaCodec.configure(format, mOutputSurface, null, 0);
            mMediaCodec.start();

            timer = new Timer();
            timerTask = new ProgressTimerTask();
            timer.schedule(timerTask, 0, mFrameInterval);

            mAudioPlayTask = new AudioPlayTask();
            mAudioPlayTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

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

    private void relaxResources(Boolean release) {
        if (mAudioCodec != null) {
            if (release) {
                mAudioCodec.stop();
                mAudioCodec.release();
                mAudioCodec = null;
            }

        }
        if (audioTrack != null) {
            if (!doStop)
                audioTrack.flush();
            audioTrack.release();
            audioTrack = null;
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

    private int selectAudioTrack(MediaExtractor mMediaExtractor) {
        for (int i = 0; i < mMediaExtractor.getTrackCount(); i++) {
            MediaFormat format = mMediaExtractor.getTrackFormat(i);
            if (format.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }

    private long curPosition;

    private void doAudio() {

        ByteBuffer[] codecInputBuffers;
        ByteBuffer[] codecOutputBuffers;


        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        codecInputBuffers = mAudioCodec.getInputBuffers();
        // 解码后的数据
        codecOutputBuffers = mAudioCodec.getOutputBuffers();

        // 解码
        boolean sawInputEOS = false;
        boolean sawOutputEOS = false;
        int noOutputCounter = 0;
        int noOutputCounterLimit = 50;

        int inputBufIndex;
        doStop = false;
        while (!sawOutputEOS && noOutputCounter < noOutputCounterLimit && !doStop) {

            if (!isRunning) {
                try {
                    //防止死循环ANR
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                continue;
            }

            noOutputCounter++;
            if (!sawInputEOS) {
                if (seekOffsetFlag) {
                    seekOffsetFlag = false;
                    mAudioExtractor.seekTo(seekOffset, SEEK_TO_PREVIOUS_SYNC);
                }

                inputBufIndex = mAudioCodec.dequeueInputBuffer(TIMEOUT_USEC);

                if (inputBufIndex >= 0) {
                    ByteBuffer dstBuf = codecInputBuffers[inputBufIndex];

                    int sampleSize =
                            mAudioExtractor.readSampleData(dstBuf, 0 /* offset */);

                    long presentationTimeUs = 0;

                    if (sampleSize < 0) {
                        sawInputEOS = true;
                        sampleSize = 0;
                    } else {
                        presentationTimeUs = mAudioExtractor.getSampleTime();
                    }
                    curPosition = presentationTimeUs;
                    mAudioCodec.queueInputBuffer(
                            inputBufIndex,
                            0 /* offset */,
                            sampleSize,
                            presentationTimeUs,
                            sawInputEOS ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);


                    if (!sawInputEOS) {
                        mAudioExtractor.advance();
                    }
                } else {
                    Log.e(TAG, "inputBufIndex " + inputBufIndex);
                }
            }

            // decode to PCM and push it to the AudioTrack player
            // 解码数据为PCM
            int res = mAudioCodec.dequeueOutputBuffer(info, TIMEOUT_USEC);

            if (res >= 0) {
                if (info.size > 0) {
                    noOutputCounter = 0;
                }
                int outputBufIndex = res;
                ByteBuffer buf = codecOutputBuffers[outputBufIndex];

                final byte[] chunk = new byte[info.size];
                buf.get(chunk);
                buf.clear();
                if (chunk.length > 0 && audioTrack != null && !doStop) {
                    //播放
                    audioTrack.write(chunk, 0, chunk.length);
                    hadPlay = true;
                }
                //释放
                mAudioCodec.releaseOutputBuffer(outputBufIndex, false /* render */);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    sawOutputEOS = true;
                }
            } else if (res == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                codecOutputBuffers = mAudioCodec.getOutputBuffers();
            } else if (res == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat oformat = mAudioCodec.getOutputFormat();
            } else {
            }
        }

        relaxResources(true);

        doStop = true;

        if (sawOutputEOS) {
            try {
                if (isLoop || !hadPlay) {
                    doAudio();
                    return;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    /**
     * AsyncTask that takes care of running the decode/playback loop
     */
    private class AudioPlayTask extends AsyncTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... values) {
            doAudio();
            return null;
        }

        @Override
        protected void onPreExecute() {
        }

        @Override
        protected void onProgressUpdate(Void... values) {
        }
    }


    private void doExtract(int frame) {

        if (mMediaCodec == null) {
            return;
        }
        int inputBufferIndex = 0;
        try {
            inputBufferIndex = mMediaCodec.dequeueInputBuffer(TIMEOUT_USEC);
        } catch (Exception e) {
            // TODO: 17-3-21 error after onResume,in none exxcuting state
            return;
        }
        if (inputBufferIndex >= 0) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                // 从输入队列里去空闲outputBufferfer
                inputBuffer = mMediaCodec.getInputBuffers()[inputBufferIndex];
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
