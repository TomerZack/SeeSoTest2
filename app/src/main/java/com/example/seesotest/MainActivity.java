package com.example.seesotest;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityOptionsCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;

import camp.visual.gazetracker.GazeTracker;
import camp.visual.gazetracker.callback.CalibrationCallback;
import camp.visual.gazetracker.callback.GazeCallback;
import camp.visual.gazetracker.constant.AccuracyCriteria;
import camp.visual.gazetracker.constant.CalibrationModeType;
import camp.visual.gazetracker.constant.InitializationErrorType;
import camp.visual.gazetracker.filter.OneEuroFilterManager;
import camp.visual.gazetracker.gaze.GazeInfo;
import camp.visual.gazetracker.util.ViewLayoutChecker;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getSimpleName();
    private static final String[] PERMISSIONS = new String[] {Manifest.permission.CAMERA};
    private static final int REQ_PERMISSION = 1000;
    private static final int CREATE_REQUEST_CODE = 40;

    private boolean cameraPermit = false;
    private boolean videoRunning = false;
    private long startTime = -1;
    ArrayList<String> dataArray = new ArrayList<String>();

    private Button startButton;
    private Button calibrationButton;
    private Button videoButton;
    private Button saveResultsButton;

    private GazeTracker gazeTracker = null;
    private VideoView videoViewer;
    private CalibrationViewer viewCalibration;
    private Handler backgroundHandler;
    private final HandlerThread backgroundThread = new HandlerThread("background");
    private final OneEuroFilterManager oneEuroFilterManager = new OneEuroFilterManager(2);
    private final ViewLayoutChecker viewLayoutChecker = new ViewLayoutChecker();

    // callback for handling the calibration process.
    private final CalibrationCallback calibrationCallback = new CalibrationCallback() {
        @Override
        public void onCalibrationProgress(float progress) {
            runOnUiThread(() -> viewCalibration.setPointAnimationPower(progress));
        }

        @Override
        public void onCalibrationNextPoint(final float x, final float y) {

            runOnUiThread(() -> {
                viewCalibration.setVisibility(View.VISIBLE);
                viewCalibration.changeDraw(true, null);
                viewCalibration.setPointPosition(x, y);
                viewCalibration.setPointAnimationPower(0);
            });
            // Give time to eyes find calibration coordinates, then collect data samples
            backgroundHandler.postDelayed(MainActivity.this::startCollectSamples, 1000);
        }

        @Override
        public void onCalibrationFinished(double[] doubles) {
            runOnUiThread(() -> {
                viewCalibration.setVisibility(View.INVISIBLE);
                videoButton.setVisibility(View.VISIBLE);
                startButton.setVisibility(View.INVISIBLE);
            });
        }
    };
    // callback for handling the eye tracking.
    private final GazeCallback gazeCallback = new GazeCallback() {
        @Override
        public void onGaze(GazeInfo gazeInfo) {
            //Log.i("SeeSo", "gaze coord " + gazeInfo.x + "x" + gazeInfo.y);
            if (oneEuroFilterManager.filterValues(gazeInfo.timestamp, gazeInfo.x, gazeInfo.y)) {
                float[] filteredValues = oneEuroFilterManager.getFilteredValues();
                float filteredX = filteredValues[0];
                float filteredY = filteredValues[1];
                if (videoRunning) {
                    if (startTime == -1) startTime = gazeInfo.timestamp;
                    dataArray.add(((double)(gazeInfo.timestamp - startTime)/1000)  + ", " + filteredX + "," + filteredY + "\n");
                }
                //Log.i("SeeSo", "gaze filterd coord " + filteredX + "x" + filteredY);
            }
        }
    };
    // an Activity Result for saving the csv file with the tracking data. Using the new Activity Result API
    private final ActivityResultLauncher<String> mGetContent =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/csv"), uri -> {
                try {
                    ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "w");
                    FileOutputStream fileOutputStream = new FileOutputStream(pfd.getFileDescriptor());
                    for (String elm : dataArray) fileOutputStream.write(elm.getBytes());
                    fileOutputStream.close();
                    pfd.close();
                }  catch (IOException e) {
                    e.printStackTrace();
                }
            });

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        startButton = findViewById(R.id.startButton);
        calibrationButton = findViewById(R.id.calibrationButton);
        videoButton = findViewById(R.id.videoButton);
        videoViewer = findViewById(R.id.videoViewer);
        viewCalibration = findViewById(R.id.view_calibration);
        saveResultsButton = findViewById(R.id.saveResultsButton);

        videoViewer.setVideoURI(Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.disk_roll));
        startButton.setOnClickListener(this::initGaze);
        calibrationButton.setOnClickListener(this::startCalibration);
        videoButton.setOnClickListener(this::startVideo);
        videoViewer.setOnCompletionListener(this::finishVideo);
        saveResultsButton.setOnClickListener(this::saveResults);

        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
        dataArray.add("Sec,X,Y\n");
        checkPermission();

        // offsetting the calibration viewer
        viewLayoutChecker.setOverlayView(viewCalibration, (x, y) -> viewCalibration.setOffset(x, y));
        //Log.i("SeeSo", "sdk version : " + GazeTracker.getVersionName());
    }

    private void startVideo(View v) {
        runOnUiThread(() -> {
            videoButton.setVisibility(View.INVISIBLE);
            videoViewer.setVisibility(View.VISIBLE);
        });
        videoRunning = true;
        videoViewer.start();
    }
    private void finishVideo(MediaPlayer mp) {
        videoRunning = false;
        runOnUiThread(() -> {
            videoViewer.setVisibility(View.INVISIBLE);
            saveResultsButton.setVisibility(View.VISIBLE);
        });
        stopAndReleaseGaze();
    }
    public void saveResults(View view)
    {
        mGetContent.launch("track_results.csv");
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        backgroundThread.quitSafely();
    }

    private void checkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Check permission status
            if (!hasPermissions(PERMISSIONS)) {
                requestPermissions(PERMISSIONS, REQ_PERMISSION);
            } else {
                checkPermission(true);
            }
        }else{
            checkPermission(true);
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private boolean hasPermissions(String[] permissions) {
        int result;
        // Check permission status in string array
        for (String perms : permissions) {
            if (perms.equals(Manifest.permission.SYSTEM_ALERT_WINDOW)) {
                if (!Settings.canDrawOverlays(this)) {
                    return false;
                }
            }
            result = ContextCompat.checkSelfPermission(this, perms);
            if (result == PackageManager.PERMISSION_DENIED) {
                // When if unauthorized permission found
                return false;
            }
        }
        // When if all permission allowed
        return true;
    }

    private void checkPermission(boolean isGranted) {
        if (isGranted) {
            cameraPermit = true;
        } else {
            finish();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSION) {
            if (grantResults.length > 0) {
                boolean cameraPermissionAccepted = grantResults[0] == PackageManager.PERMISSION_GRANTED;
                checkPermission(cameraPermissionAccepted);
            }
        }
    }

    private void initGaze(View v) {
        if (cameraPermit) {
            String licenseKey = "dev_co0g13fzy0es7gej6bwt169f0t26779z3u2z7kgs";
            GazeTracker.initGazeTracker(getApplicationContext(), licenseKey,
                    (gazeTracker, error) -> {
                if (gazeTracker != null) {
                    initSuccess(gazeTracker);
                } else {
                    initFail(error);
                }
            });
        }
    }

    private void initSuccess(@NonNull GazeTracker gazeTracker) {
        this.gazeTracker = gazeTracker;
        this.gazeTracker.setGazeCallback(gazeCallback);
        gazeTracker.setCallbacks(calibrationCallback);
        runOnUiThread(() -> {
            calibrationButton.setVisibility(View.VISIBLE);
            startButton.setVisibility(View.INVISIBLE);
        });
        gazeTracker.startTracking();
    }
    private void initFail(InitializationErrorType error) {
        String err;
        switch (error) {
            case ERROR_INIT: // When initialization is failed
                err = "Initialization failed";
                break;
            case ERROR_CAMERA_PERMISSION: // When camera permission doesn't not exists
                err = "Required permission not granted";
                break;
            default: // Gaze library initialization failure
                err = "init gaze library fail";
                break; // It can ba caused by several reasons(i.e. Out of memory).
        }
        Log.w("SeeSo", "error description: " + err);
    }

    private void startCalibration(View v) {
        runOnUiThread(() -> {
            viewCalibration.setVisibility(View.VISIBLE);
            calibrationButton.setVisibility(View.INVISIBLE);
        });
        gazeTracker.startCalibration(CalibrationModeType.FIVE_POINT, AccuracyCriteria.DEFAULT);
    }

    private boolean startCollectSamples() {
        return isCalibrating() && gazeTracker.startCollectSamples();
    }
    public boolean isCalibrating() {
        return gazeTracker != null && gazeTracker.isCalibrating();
    }
    private void stopAndReleaseGaze() {
        gazeTracker.stopTracking();
        GazeTracker.deinitGazeTracker(this.gazeTracker);
        this.gazeTracker = null;
    }
}