package com.koltinjo.android179_camera2_api;

import android.util.Size;

import java.util.Comparator;

/**
 * Created by colt on 12.12.2016.
 */

public class CompareSizeByArea implements Comparator<Size> {

    @Override
    public int compare(Size size, Size t1) {
        return Long.signum((long) size.getWidth() * size.getHeight() / (long) t1.getWidth() * t1.getHeight());
    }

}