package com.koltinjo.android179_camera2_api;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Chronometer;
import android.widget.ImageButton;
import android.widget.Toast;
import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnLongClick;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

// TODO Fix orientation.

// https://www.youtube.com/watch?v=CuvVpsFc77w&index=1&list=PL9jCwTXYWjDIHNEGtsRdCTk79I9-95TbJ
public class ActivityMain extends AppCompatActivity {

    private static final String THREAD_NAME = "threadCamera2API";
    private static final int CAMERA_REQUEST = 61; // Used also fo audio recording permission.
    private static final int WRITE_REQUEST = 62;
    private static final int STATE_PREVIEW = 71;
    private static final int STATE_WAIT_LOCK = 72;

    @BindView(R.id.activitymain_textureview)
    TextureView textureView;
    @BindView(R.id.activitymain_imagebutton_video)
    ImageButton imageButtonVideo;
    @BindView(R.id.activitymain_chronometer)
    Chronometer chronometer;

    private static SparseIntArray orientations;
    private boolean recording;
    private TextureView.SurfaceTextureListener surfaceTextureListener;
    private CameraDevice camera;
    private CameraDevice.StateCallback cameraStateCallback;
    private String cameraId;
    private HandlerThread handlerThreadBackground;
    private Handler handler;
    private Size previewSize;
    private CaptureRequest.Builder captureRequestBuilder;
    private File videoFolder;
    private String videoFileName;
    private int rotationTotal;
    private Size videoSize;
    private MediaRecorder mediaRecorder;
    private Size imageSize;
    private ImageReader imageReader;
    private ImageReader.OnImageAvailableListener imageReaderListener;
    private CameraCaptureSession captureSessionPreview;
    private CameraCaptureSession.CaptureCallback captureCallbackPreview;
    private int captureState;
    private File imageFolder;
    private String imageFileName;
    private CameraCaptureSession captureSessionRecord;
    private CameraCaptureSession.CaptureCallback captureCallbackRecord;
    private boolean timelapse;

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
                if (recording) {
                    createVideoFileName();
                    startRecord();
                    mediaRecorder.start();
                    chronometer.setBase(SystemClock.elapsedRealtime());
                    chronometer.setVisibility(View.VISIBLE);
                    chronometer.start();
                } else {
                    startPreview();
                }
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
        imageReaderListener = new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader imageReader) {
                handler.post(new ImageSaver(imageReader.acquireLatestImage()));
            }
        };
        captureCallbackPreview = new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                super.onCaptureCompleted(session, request, result);
                process(result);
            }

            private void process(CaptureResult captureResult) {
                switch (captureState) {
                    case STATE_PREVIEW:
                        // Do nothing.
                        break;
                    case STATE_WAIT_LOCK:
                        captureState = STATE_PREVIEW;
                        Integer autoFocusState = captureResult.get(CaptureResult.CONTROL_AF_STATE);
                        if (autoFocusState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || autoFocusState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                            Toast.makeText(getApplicationContext(), "Focus locked.", Toast.LENGTH_SHORT).show();
                            startStillCapture();
                        } else if (autoFocusState == CaptureResult.CONTROL_AF_STATE_INACTIVE) {
                            // Workaround for no focus?
                            Toast.makeText(getApplicationContext(), "Focus not locked, but who cares.", Toast.LENGTH_SHORT).show();
                            startStillCapture();
                        }
                        break;
                }
            }
        };
        captureCallbackRecord = new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                super.onCaptureCompleted(session, request, result);
                process(result);
            }

            private void process(CaptureResult captureResult) {
                switch (captureState) {
                    case STATE_PREVIEW:
                        // Do nothing.
                        break;
                    case STATE_WAIT_LOCK:
                        captureState = STATE_PREVIEW;
                        Integer autoFocusState = captureResult.get(CaptureResult.CONTROL_AF_STATE);
                        if (autoFocusState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || autoFocusState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                            Toast.makeText(getApplicationContext(), "Focus locked.", Toast.LENGTH_SHORT).show();
                            startStillCapture();
                        } else if (autoFocusState == CaptureResult.CONTROL_AF_STATE_INACTIVE) {
                            // Workaround for no focus?
                            Toast.makeText(getApplicationContext(), "Focus not locked, but who cares.", Toast.LENGTH_SHORT).show();
                            startStillCapture();
                        }
                        break;
                }
            }
        };
        orientations = new SparseIntArray();
        recording = false;
        timelapse = false;
        mediaRecorder = new MediaRecorder();
        captureState = STATE_PREVIEW;

        createVideoFolder();
        createImageFolder();

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

        switch (requestCode) {
            case CAMERA_REQUEST:
                if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, getString(R.string.app_name) + " will not run without camera permission.", Toast.LENGTH_SHORT).show();
                }
                if (grantResults[1] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, getString(R.string.app_name) + " will not record without audio permission.", Toast.LENGTH_SHORT).show();
                }
                break;
            case WRITE_REQUEST:
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    recording = true;
                    imageButtonVideo.setImageResource(R.mipmap.button_video_busy);
                    createVideoFileName();
                } else {
                    Toast.makeText(this, getString(R.string.app_name) + " will not run without write permission.", Toast.LENGTH_SHORT).show();
                }
                break;
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
                rotationTotal = sensorToDeviceRotation(cameraCharacteristics, orientation);
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
                videoSize = chooseOptimumSize(map.getOutputSizes(MediaRecorder.class), widthRotated, heightRotated);
                imageSize = chooseOptimumSize(map.getOutputSizes(ImageFormat.JPEG), widthRotated, heightRotated);
                imageReader = ImageReader.newInstance(imageSize.getWidth(), imageSize.getHeight(), ImageFormat.JPEG, 1);
                imageReader.setOnImageAvailableListener(imageReaderListener, handler);
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
                    requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, CAMERA_REQUEST);
                }
            } else {
                cameraManager.openCamera(cameraId, cameraStateCallback, handler);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void startPreview() {
        SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
        surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        Surface surfacePreview = new Surface(surfaceTexture);

        try {
            captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surfacePreview);
            camera.createCaptureSession(
                    Arrays.asList(surfacePreview, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                            captureSessionPreview = cameraCaptureSession;
                            try {
                                captureSessionPreview.setRepeatingRequest(captureRequestBuilder.build(), null, handler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
                            Toast.makeText(getApplicationContext(), "Unable to setup camera preview.", Toast.LENGTH_SHORT).show();
                        }
                    },
                    null
            );
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void startStillCapture() {
        try {
            if (recording) {
                captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_VIDEO_SNAPSHOT);
            } else {
                captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            }
            captureRequestBuilder.addTarget(imageReader.getSurface());
            captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, rotationTotal);
            CameraCaptureSession.CaptureCallback stillCaptureCallback = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request, long timestamp, long frameNumber) {
                    super.onCaptureStarted(session, request, timestamp, frameNumber);
                    createImageFileName();
                }
            };
            if (recording) {
                captureSessionRecord.capture(captureRequestBuilder.build(), stillCaptureCallback, null);
            } else {
                captureSessionPreview.capture(captureRequestBuilder.build(), stillCaptureCallback, null);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void startRecord() {
        if (recording) {
            setupMediaRecorder();
        } else if (timelapse) {
            setupTimelapse();
        }
        SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
        surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        Surface surfacePreview = new Surface(surfaceTexture);
        Surface surfaceRecord = mediaRecorder.getSurface();

        try {
            captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            captureRequestBuilder.addTarget(surfacePreview);
            captureRequestBuilder.addTarget(surfaceRecord);
            // TODO Check on real device.
            camera.createCaptureSession(
                    Arrays.asList(surfacePreview, surfaceRecord, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                            captureSessionRecord = cameraCaptureSession;
                            try {
                                captureSessionRecord.setRepeatingRequest(captureRequestBuilder.build(), null, null);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
                            Toast.makeText(getApplicationContext(), "Unable to record video.", Toast.LENGTH_SHORT).show();
                        }
                    },
                    null
            );
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

    // Image button method.
    public void captureImage(View view) {
        lockFocus();
    }

    // Video button method.
    public void recordVideo(View view) {
        if (recording || timelapse) {
            chronometer.stop();
            chronometer.setVisibility(View.INVISIBLE);
            recording = false;
            timelapse = false;
            imageButtonVideo.setImageResource(R.mipmap.button_video_online);
            mediaRecorder.stop();
            mediaRecorder.reset();
            updateMediaStoreVideo();
            startPreview();
        } else {
            recording = true;
            imageButtonVideo.setImageResource(R.mipmap.button_video_busy);
            checkWriteStoragePermission();
        }
    }

    // Video button onLongClick.
    @OnLongClick(R.id.activitymain_imagebutton_video)
    public boolean onLongClick(View view) {
        timelapse = true;
        imageButtonVideo.setImageResource(R.mipmap.button_timelapse);
        checkWriteStoragePermission();
        return true;
    }

    private void createVideoFolder() {
        File fileMovie = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
        videoFolder = new File(fileMovie, getString(R.string.app_name));
        if (!videoFolder.exists()) {
            videoFolder.mkdirs();
        }
    }

    // TODO return = void?
    private File createVideoFileName() {
        String time = new SimpleDateFormat("MMddyyyyHHmmss").format(new Date());
//        String prefix = "video_" + time;
        String prefix = "video_";
        File videoFile = null;
        try {
            videoFile = File.createTempFile(prefix, ".mp4", videoFolder);
            videoFileName = videoFile.getAbsolutePath();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return videoFile;
    }

    private void createImageFolder() {
        File fileImage = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        imageFolder = new File(fileImage, getString(R.string.app_name));
        if (!imageFolder.exists()) {
            imageFolder.mkdirs();
        }
    }

    private File createImageFileName() {
        String time = new SimpleDateFormat("MMddyyyyHHmmss").format(new Date());
        String prefix = "image_";
        File imageFile = null;
        try {
            imageFile = File.createTempFile(prefix, ".jpg", imageFolder);
            imageFileName = imageFile.getAbsolutePath();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return imageFile;
    }

    private void checkWriteStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                createVideoFileName();
                startRecord();
                mediaRecorder.start();
                chronometer.setBase(SystemClock.elapsedRealtime());
                chronometer.setVisibility(View.VISIBLE);
                chronometer.start();
            } else {
                if (shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                    Toast.makeText(this, getString(R.string.app_name) + " needs to be able to save videos.", Toast.LENGTH_SHORT).show();
                }
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, WRITE_REQUEST);
            }
        } else {
            createVideoFileName();
            startRecord();
            mediaRecorder.start();
            chronometer.setBase(SystemClock.elapsedRealtime());
            chronometer.setVisibility(View.VISIBLE);
            chronometer.start();
        }
    }

    private void setupMediaRecorder() {
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.setOutputFile(videoFileName);
        mediaRecorder.setVideoEncodingBitRate(1000000);
        mediaRecorder.setVideoFrameRate(30);
        mediaRecorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setOrientationHint(rotationTotal);
        try {
            mediaRecorder.prepare();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void setupTimelapse() {
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_TIME_LAPSE_HIGH));
        mediaRecorder.setOutputFile(videoFileName);
        mediaRecorder.setCaptureRate(2);
        mediaRecorder.setOrientationHint(rotationTotal);
        try {
            mediaRecorder.prepare();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void lockFocus() {
        captureState = STATE_WAIT_LOCK;
        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
        try {
            if (recording) {
                captureSessionRecord.capture(captureRequestBuilder.build(), captureCallbackRecord, handler);
            } else {
                captureSessionPreview.capture(captureRequestBuilder.build(), captureCallbackPreview, handler);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void updateMediaStoreImage() {
        Intent intentMediaStoreUpdate = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        intentMediaStoreUpdate.setData(Uri.fromFile(new File(imageFileName)));
        sendBroadcast(intentMediaStoreUpdate);
    }

    private void updateMediaStoreVideo() {
        Intent intentMediaStoreUpdate = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        intentMediaStoreUpdate.setData(Uri.fromFile(new File(videoFileName)));
        sendBroadcast(intentMediaStoreUpdate);
    }

    // Subclass
    public class ImageSaver implements Runnable {
        private final Image image;

        public ImageSaver(Image image) {
            this.image = image;
        }

        @Override
        public void run() {
            ByteBuffer byteBuffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[byteBuffer.remaining()];
            byteBuffer.get(bytes);

            FileOutputStream fileOutputStream = null;
            try {
                fileOutputStream = new FileOutputStream(imageFileName);
                fileOutputStream.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                image.close();
                updateMediaStoreImage();
                if (fileOutputStream != null) {
                    try {
                        fileOutputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

}