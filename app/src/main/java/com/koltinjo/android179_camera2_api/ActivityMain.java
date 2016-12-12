package com.koltinjo.android179_camera2_api;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Toast;
import butterknife.BindView;
import butterknife.ButterKnife;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// https://www.youtube.com/watch?v=CuvVpsFc77w&index=1&list=PL9jCwTXYWjDIHNEGtsRdCTk79I9-95TbJ
public class ActivityMain extends AppCompatActivity {

    private static final String THREAD_NAME = "thread69";
    private static final int CAMERA_REQUEST = 69;

    @BindView(R.id.activitymain_textureview)
    TextureView textureView;

    private TextureView.SurfaceTextureListener surfaceTextureListener;
    private CameraDevice camera;
    private CameraDevice.StateCallback cameraStateCallback;
    private String cameraId;
    private HandlerThread handlerThreadBackground;
    private Handler handler;
    private static SparseIntArray orientations;
    private Size previewSize;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ButterKnife.bind(this);

        surfaceTextureListener = new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i1) {
                setupCamera(i, i1);
                connectCamera();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int i, int i1) {

            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

            }
        };
        cameraStateCallback = new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice cameraDevice) {
                camera = cameraDevice;
                Toast.makeText(ActivityMain.this, "Camera connection made.", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onDisconnected(CameraDevice cameraDevice) {
                cameraDevice.close();
                camera = null;
            }

            @Override
            public void onError(CameraDevice cameraDevice, int i) {
                cameraDevice.close();
                camera = null;
            }
        };
        orientations = new SparseIntArray();

        orientations.append(Surface.ROTATION_0, 0);
        orientations.append(Surface.ROTATION_90, 90);
        orientations.append(Surface.ROTATION_180, 180);
        orientations.append(Surface.ROTATION_270, 270);
    }

    @Override
    protected void onResume() {
        super.onResume();

        startBackgroundThread();
        if (textureView.isAvailable()) {
            setupCamera(textureView.getWidth(), textureView.getHeight());
            connectCamera();
        } else {
            textureView.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopBackgroundThread();

        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        View decorView = getWindow().getDecorView();
        if (hasFocus) {
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == CAMERA_REQUEST) {
            if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, getString(R.string.app_name) + " will not run without camera permission.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void setupCamera(int width, int height) {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(id);
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                    // Just skip if front camera.
                    continue;
                }

                StreamConfigurationMap map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                int orientation = getWindowManager().getDefaultDisplay().getRotation();
                int rotationTotal = sensorToDeviceRotation(cameraCharacteristics, orientation);
                // Swap if in landscape mode.
                int widthRotated;
                int heightRotated;
                if (rotationTotal == 90 || rotationTotal == 270) {
                    widthRotated = height;
                    heightRotated = width;
                } else {
                    widthRotated = width;
                    heightRotated = height;
                }

                previewSize = chooseOptimumSize(map.getOutputSizes(SurfaceTexture.class), widthRotated, heightRotated);
                cameraId = id;
                return;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void connectCamera() {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            // Ask for permission on Android M.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    cameraManager.openCamera(cameraId, cameraStateCallback, handler);
                } else {
                    if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                        Toast.makeText(this, getString(R.string.app_name) + " requires access to camera.", Toast.LENGTH_SHORT).show();
                    }
                    requestPermissions(new String[] {Manifest.permission.CAMERA}, CAMERA_REQUEST);
                }
            } else {
                cameraManager.openCamera(cameraId, cameraStateCallback, handler);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void closeCamera() {
        if (camera != null) {
            camera.close();
            camera = null;
        }
    }

    private void startBackgroundThread() {
        handlerThreadBackground = new HandlerThread(THREAD_NAME);
        handlerThreadBackground.start();
        handler = new Handler(handlerThreadBackground.getLooper());
    }

    private void stopBackgroundThread() {
        handlerThreadBackground.quitSafely();
        try {
            handlerThreadBackground.join();
            handlerThreadBackground = null;
            handler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private static int sensorToDeviceRotation(CameraCharacteristics cameraCharacteristics, int orientation) {
        int orientationSensor = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        int orientationDevice = orientations.get(orientation);
        return (orientationSensor + orientationDevice + 360) % 360;
    }

    private static Size chooseOptimumSize(Size[] choices, int width, int height) {
        List<Size> bigEnough = new ArrayList<>();
        for (Size option : choices) {
            // Ratio check.
            if (option.getHeight() == option.getWidth() * height / width && option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }
        if (!bigEnough.isEmpty()) {
            return Collections.min(bigEnough, new CompareSizeByArea());
        } else {
            // If all else fails.
            return choices[0];
        }
    }

}