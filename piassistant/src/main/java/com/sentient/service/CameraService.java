package com.sentient.service;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import nu.pattern.OpenCV;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.videoio.VideoCapture;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

public class CameraService {

    private VideoCapture capture;
    private ScheduledExecutorService errorTimer; // To restart if needed
    private boolean isCameraActive = false;

    public CameraService() {
        try {
            OpenCV.loadLocally();
            this.capture = new VideoCapture(0); // Default camera

            if (!this.capture.isOpened()) {
                System.err.println("Camera connection failed!");
            } else {
                isCameraActive = true;
            }
        } catch (Exception e) {
            System.err.println("Failed to load OpenCV: " + e.getMessage()); // Fallback if openpnp fails on Pi
        }
    }

    public Image getLatestFrame() {
        if (!isCameraActive)
            return null;

        Mat matrix = readFrame();

        if (matrix.empty())
            return null;

        return mat2Image(matrix);
    }

    public byte[] getFrameAsBytes() {
        if (!isCameraActive)
            return null;

        Mat matrix = readFrame();

        if (matrix.empty())
            return null;

        MatOfByte buffer = new MatOfByte();
        Imgcodecs.imencode(".jpg", matrix, buffer);
        return buffer.toArray();
    }

    private Mat readFrame() {
        Mat matrix = new Mat();
        if (capture.isOpened()) {
            this.capture.read(matrix);
            if (!matrix.empty()) {
                Core.flip(matrix, matrix, 1); // 1 = Horizontal flip
            }
        }
        return matrix;
    }

    // Helper to convert OpenCV Mat to JavaFX Image
    private Image mat2Image(Mat frame) {
        try {
            MatOfByte buffer = new MatOfByte();
            Imgcodecs.imencode(".png", frame, buffer);
            return new Image(new ByteArrayInputStream(buffer.toArray()));
        } catch (Exception e) {
            System.err.println("Cannot convert the Mat object: " + e);
            return null;
        }
    }

    public void stop() {
        if (this.capture != null && this.capture.isOpened()) {
            this.capture.release();
        }
    }

    public String convertImageToBase64(byte[] imageBytes) {
        // 1. Grab Java's built-in Base64 translator
        Base64.Encoder encoder = Base64.getEncoder();

        // 2. Feed it your raw image bytes, get a text string back
        String base64String = encoder.encodeToString(imageBytes);

        // 3. Hand the text string back to your app
        return base64String;
    }
}
