package com.example.driversafetyapp; // Make sure this matches your package name

import android.annotation.SuppressLint;
import android.graphics.PointF;
import android.media.Image;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.face.FaceLandmark;

import java.util.List;

public class FatigueDetector implements ImageAnalysis.Analyzer {

    private static final String TAG = "FatigueDetector";

    // --- Fatigue Detection Parameters (NEEDS CALIBRATION!) ---
    private static final float EYE_CLOSED_THRESHOLD = 0.4f; // Probability threshold for eye closure
    private static final long FATIGUE_DURATION_THRESHOLD_MS = 2000; // 2 seconds of closed eyes
    // ---

    private final FaceDetector faceDetector;
    private final FatigueListener listener;

    private long eyesClosedStartTime = -1; // Timestamp when eyes were first detected as closed

    // Interface to communicate fatigue status back to MainActivity
    public interface FatigueListener {
        void onFatigueDetected(boolean isFatigued); // True if fatigue detected, false otherwise
        void onNoFaceDetected(); // Called when no face is found in the frame
    }

    public FatigueDetector(FatigueListener listener) {
        this.listener = listener;

        // Configure ML Kit Face Detector
        // High accuracy needed for landmarks, classification enabled for eye open probability
        FaceDetectorOptions options =
                new FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST) // Fast mode for real-time
                        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)       // Detect eyes, mouth, etc.
                        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL) // Classify eyes open/closed
                        .setMinFaceSize(0.35f) // Minimum face size relative to image (adjust as needed)
                        .enableTracking() // Enable tracking for smoother results between frames
                        .build();

        faceDetector = FaceDetection.getClient(options);
        Log.d(TAG, "Face Detector Initialized.");
    }

    @SuppressLint("UnsafeOptInUsageError") // Needed for image.getImage()
    @Override
    public void analyze(@NonNull ImageProxy imageProxy) {
        Image mediaImage = imageProxy.getImage();
        if (mediaImage != null) {
            // Create InputImage from ImageProxy, getting rotation degrees
            InputImage image = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());

            faceDetector.process(image)
                    .addOnSuccessListener(faces -> {
                        if (faces.isEmpty()) {
                            // No face detected, reset fatigue state
                            resetFatigueState();
                            listener.onNoFaceDetected();
                            // Log.v(TAG, "No face detected."); // Verbose logging
                        } else {
                            // Process the first detected face (assuming driver is primary subject)
                            processFace(faces.get(0));
                        }
                    })
                    .addOnFailureListener(e -> Log.e(TAG, "Face detection failed", e))
                    .addOnCompleteListener(task -> {
                        // VERY IMPORTANT: Close the ImageProxy to allow the next frame to be processed
                        imageProxy.close();
                    });
        } else {
            // If mediaImage is null, close the proxy anyway
            imageProxy.close();
        }
    }

    private void processFace(Face face) {
        // Check Eye Open Probability
        Float leftEyeOpenProb = face.getLeftEyeOpenProbability();
        Float rightEyeOpenProb = face.getRightEyeOpenProbability();

        boolean eyesClosed = (leftEyeOpenProb != null && leftEyeOpenProb < EYE_CLOSED_THRESHOLD) &&
                (rightEyeOpenProb != null && rightEyeOpenProb < EYE_CLOSED_THRESHOLD);

        // Log eye probabilities for debugging/calibration
        // Log.v(TAG, String.format("Eye Probs - L: %.2f, R: %.2f, Closed: %b", leftEyeOpenProb, rightEyeOpenProb, eyesClosed));

        if (eyesClosed) {
            if (eyesClosedStartTime == -1) {
                // Eyes just closed, record start time
                eyesClosedStartTime = System.currentTimeMillis();
                // Log.d(TAG, "Eyes detected closed. Starting timer.");
            } else {
                // Eyes still closed, check duration
                long durationClosed = System.currentTimeMillis() - eyesClosedStartTime;
                if (durationClosed >= FATIGUE_DURATION_THRESHOLD_MS) {
                    // Fatigue detected! Eyes closed for too long.
                    // Log.w(TAG, "FATIGUE DETECTED! Eyes closed for " + durationClosed + "ms");
                    listener.onFatigueDetected(true); // Notify listener
                }
                // else: eyes closed, but not long enough yet
            }
        } else {
            // Eyes are open, reset timer and notify listener if fatigue state changes
            if (eyesClosedStartTime != -1) {
                //Log.d(TAG, "Eyes detected open. Resetting fatigue state.");
                listener.onFatigueDetected(false); // Notify that fatigue ended (eyes opened)
            }
            resetFatigueState();
        }

        // --- Optional: Yawn Detection (More complex) ---
        // Yawn detection is harder. Could look for:
        // 1. Mouth landmark vertical distance (mouth open wide).
        // 2. Smiling probability (sometimes low during yawn, but unreliable).
        // Requires more sophisticated logic and calibration.
        // FaceLandmark mouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM);
        // FaceLandmark mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT);
        // FaceLandmark mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT);
        // if (mouthBottom != null && mouthLeft != null && mouthRight != null) {
        // Calculate mouth aspect ratio or vertical opening
        // }
    }

    private void resetFatigueState() {
        // If eyes were previously closed, notify that fatigue state is now false (eyes open/no face)
        if (eyesClosedStartTime != -1) {
            listener.onFatigueDetected(false);
        }
        eyesClosedStartTime = -1; // Reset timer
    }

    // Call this when the detector is no longer needed (e.g., in MainActivity's onDestroy)
    public void stop() {
        faceDetector.close();
        Log.d(TAG, "Face Detector stopped and resources released.");
    }
}