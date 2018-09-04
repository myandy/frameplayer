package com.myth.frameplayer;

import android.os.Bundle;
import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.EditText;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;


/**
 * warn:
 * need get file read permission first
 */
public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    /**
     * test video path
     */
    private static String videoPath = Environment.getExternalStorageDirectory().getPath() + "/Music8.1.mp4";
    private FramePlayer framePlayer = null;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        SurfaceView surfaceView = (SurfaceView) findViewById(R.id.playMovie_surface);
        surfaceView.getHolder().addCallback(this);

        //make its height same with width,should set for video size
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        RelativeLayout.LayoutParams lps = (RelativeLayout.LayoutParams) surfaceView.getLayoutParams();
        lps.width = (int) (displayMetrics.widthPixels - 2 * getResources().getDimension(R.dimen.activity_horizontal_margin));
        lps.height = lps.width;
        surfaceView.setLayoutParams(lps);

        final EditText et = (EditText) findViewById(R.id.et);

        findViewById(R.id.play).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (et.getText().toString().isEmpty()) {
                    framePlayer.start();
                } else {
                    int frame = Integer.parseInt(et.getText().toString());
                    if (frame >= 1 && frame <= 100) {
                        framePlayer.setFrameInterval(frame);
                        framePlayer.start();
                    } else {
                        Toast.makeText(MainActivity.this, "每秒帧数必须在1到100之间！", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });

        final TextView tv = (TextView) findViewById(R.id.control);
        findViewById(R.id.control).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (framePlayer.isRunning()) {
                    framePlayer.pause();
                    tv.setText("RESUME");
                } else {
                    tv.setText("PAUSE");
                    framePlayer.resume();
                }
            }
        });

        findViewById(R.id.next).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                framePlayer.nextFrame();
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (framePlayer != null) {
            framePlayer.pause();
        }

    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (framePlayer == null) {
            Surface surface = holder.getSurface();
            framePlayer = new FramePlayer(surface);
            framePlayer.setSourceFile(new File(videoPath));
            framePlayer.execute();
            framePlayer.setPlayListener(new FramePlayer.PlayListener() {
                @Override
                public void onCompleted() {
                    Log.d("PlayListener", "onCompleted");
                }

                @Override
                public void onProgress(float progress) {
                    Log.d("PlayListener", "onProgress" + progress);
                }
            });
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {

    }
}
