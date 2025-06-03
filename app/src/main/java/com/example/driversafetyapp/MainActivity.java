package com.example.driversafetyapp; // Match your package name

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.telephony.SmsManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;


import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner; // Keep this import for clarity even if cast is removed

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

// Ensure you have the FatigueDetector.java and UserDetailsActivity.java files
// and the corresponding layouts (activity_main.xml, activity_user_details.xml)
// and the alarm sound file (e.g., res/raw/alarm.mp3) in your project.

public class MainActivity extends AppCompatActivity implements SensorEventListener, FatigueDetector.FatigueListener {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 101;
    private static final String NOTIFICATION_CHANNEL_ID = "DRIVER_SAFETY_ALERTS";
    private static final int COUNTDOWN_SECONDS = 7; // Countdown duration

    // SharedPreferences Keys (consistent with UserDetailsActivity)
    public static final String SHARED_PREFS_NAME = "DriverSafetyPrefs";
    public static final String KEY_EMERGENCY_CONTACT_PHONE = "emergencyContactPhone";


    // --- Configuration (NEEDS CALIBRATION!) ---
    private static final float ACCIDENT_ACCELERATION_THRESHOLD = 45.0f; // m/s^2 (Increased sensitivity)
    private static final float ACCIDENT_ROTATION_THRESHOLD = 15.0f; // rad/s (Increased sensitivity)
    private static final long ALERT_COOLDOWN_MS = TimeUnit.MINUTES.toMillis(2); // Min time between SMS/Calls

    // UI Elements
    private PreviewView previewView;
    private TextView statusTextView;
    private TextView fatigueWarningTextView;
    private ImageButton settingsButton;

    // CameraX
    private ExecutorService cameraExecutor;
    private FatigueDetector fatigueDetector;
    private ProcessCameraProvider cameraProvider;

    // Sensors
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor gyroscope;

    // Location
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private Location lastKnownLocation = null;
    private boolean requestingLocationUpdates = false;

    // Alerts & State
    private MediaPlayer alarmMediaPlayer;
    private boolean isFatigueAlarmPlaying = false;
    private boolean isFatigueDetectedState = false; // Current fatigue state from detector
    private long lastFatigueAlertTime = 0;
    private long lastAccidentAlertTime = 0;
    private Handler mainThreadHandler; // To post UI updates from background threads
    private PowerManager.WakeLock wakeLock; // To keep CPU running

    // Countdown State
    private CountDownTimer alertCountDownTimer = null;
    private AlertDialog alertCountdownDialog = null;
    private boolean isAlertCountdownActive = false;
    private String pendingAlertCause = "";
    private String pendingAlertLocation = "";
    private String pendingEmergencyContact = "";
    private String pendingAlertType = ""; // *** ADDED: To track the type of alert causing the countdown ***


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        // --- Find Views ---
        previewView = findViewById(R.id.previewView);
        statusTextView = findViewById(R.id.statusTextView);
        fatigueWarningTextView = findViewById(R.id.fatigueWarningTextView);
        settingsButton = findViewById(R.id.settingsButton);

        mainThreadHandler = new Handler(Looper.getMainLooper());

        // --- Initialize Components ---
        cameraExecutor = Executors.newSingleThreadExecutor();
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        // Initialize WakeLock
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DriverSafetyApp::CpuWakeLock");
            wakeLock.setReferenceCounted(false);
        } else {
            Log.e(TAG, "PowerManager not available.");
        }

        createNotificationChannel();
        initializeMediaPlayer();

        // --- Setup Listeners ---
        settingsButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, UserDetailsActivity.class);
            startActivity(intent);
        });

        // --- Check Permissions and Start ---
        if (checkAndRequestPermissions()) {
            startAllServices();
        }
    }

    private void startAllServices() {
        Log.d(TAG, "Starting all services...");
        if (isEmergencyContactSet()) {
            updateStatus("Status: Monitoring", true);
        } else {
            updateStatus("Status: Set Emergency Contact!", true);
            Toast.makeText(this, "Please set an emergency contact via the settings icon.", Toast.LENGTH_LONG).show();
        }
        startCamera();
        startLocationUpdates();
        registerSensorListeners();
    }

    // --- Permission Handling ---
    private boolean checkAndRequestPermissions() {
        List<String> listPermissionsNeeded = new ArrayList<>();
        String[] requiredPermissions = new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.SEND_SMS,
                Manifest.permission.CALL_PHONE,
        };
        List<String> permissionList = new ArrayList<>(List.of(requiredPermissions));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionList.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        for (String perm : permissionList) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(perm);
            }
        }
        if (!listPermissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, listPermissionsNeeded.toArray(new String[0]), PERMISSION_REQUEST_CODE);
            updateStatus("Status: Requesting Permissions...", false);
            Log.w(TAG, "Requesting permissions: " + listPermissionsNeeded);
            return false;
        }
        Log.d(TAG, "All required permissions already granted.");
        return true;
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    Log.e(TAG, "Permission Denied: " + permissions[i]);
                    Toast.makeText(this, permissions[i] + " permission denied.", Toast.LENGTH_SHORT).show();
                } else {
                    Log.i(TAG, "Permission Granted: " + permissions[i]);
                }
            }
            if (allGranted && areCorePermissionsGranted()) {
                Log.d(TAG, "All permissions granted after request. Starting services.");
                startAllServices();
            } else {
                updateStatus("Status: Permissions Denied!", true);
                Log.e(TAG, "Essential permissions were denied.");
                Toast.makeText(this, "Essential permissions required. Grant them in App Settings.", Toast.LENGTH_LONG).show();
            }
        }
    }
    private boolean areCorePermissionsGranted() {
        boolean cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean locationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean smsGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
        boolean callGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED;
        return cameraGranted && locationGranted && smsGranted && callGranted;
    }

    // --- CameraX Setup ---
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                if (previewView.getSurfaceProvider() != null) {
                    preview.setSurfaceProvider(previewView.getSurfaceProvider());
                } else {
                    Log.e(TAG, "PreviewView SurfaceProvider is null.");
                    Toast.makeText(this, "Camera preview error.", Toast.LENGTH_SHORT).show(); return;
                }
                fatigueDetector = new FatigueDetector(this);
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysis.setAnalyzer(cameraExecutor, fatigueDetector);
                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis); // No cast needed
                Log.d(TAG, "CameraX started and bound.");
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "CameraX binding failed", e); updateStatus("Status: Camera Error", true);
            } catch (Exception e) {
                Log.e(TAG, "Camera setup failed", e); updateStatus("Status: Camera Init Error", true);
                Toast.makeText(this, "Cannot initialize camera.", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // --- Sensor Handling ---
    private void registerSensorListeners() {
        if (sensorManager != null) {
            if (accelerometer != null) sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
            if (gyroscope != null) sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_NORMAL);
            Log.d(TAG,"Sensor listeners registered.");
        }
    }
    private void unregisterSensorListeners() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this); Log.d(TAG,"Sensor listeners unregistered.");
        }
    }
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (isAlertCountdownActive) return; // Don't process new sensor data if already counting down
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0]; float y = event.values[1]; float z = event.values[2];
            float acceleration = (float) Math.sqrt(x * x + y * y + z * z);
            if (acceleration > ACCIDENT_ACCELERATION_THRESHOLD) {
                Log.w(TAG, "Potential Accident: High Acceleration! Val: " + acceleration);
                triggerAccidentAlert("High Impact (" + String.format(Locale.US,"%.1f", acceleration) + " m/s²)");
            }
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            float x = event.values[0]; float y = event.values[1]; float z = event.values[2];
            float rotation = (float) Math.sqrt(x * x + y * y + z * z);
            if (rotation > ACCIDENT_ROTATION_THRESHOLD) {
                Log.w(TAG, "Potential Accident: High Rotation! Val: " + rotation);
                triggerAccidentAlert("Severe Rotation (" + String.format(Locale.US,"%.1f", Math.toDegrees(rotation)) + " °/s)");
            }
        }
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // --- Location Handling ---
    @SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        if (!areCorePermissionsGranted()) { Log.w(TAG, "Location permission missing."); return; }
        if (requestingLocationUpdates) { Log.d(TAG,"Location updates already active."); return; }
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000).setMinUpdateIntervalMillis(5000).build();
        if (locationCallback == null) {
            locationCallback = new LocationCallback() {
                @Override public void onLocationResult(@NonNull LocationResult locationResult) {
                    if (locationResult.getLastLocation() != null) { lastKnownLocation = locationResult.getLastLocation(); }
                }
            };
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        requestingLocationUpdates = true; Log.d(TAG, "Requested location updates.");
        fusedLocationClient.getLastLocation().addOnSuccessListener(this, location -> { if (location != null) lastKnownLocation = location; Log.d(TAG, "Got last known location: " + (location != null)); });
    }
    private void stopLocationUpdates() {
        if (requestingLocationUpdates && locationCallback != null) {
            try {
                // Change from fusedLocationClient.removeUpdates(locationCallback) to:
                fusedLocationClient.removeLocationUpdates(locationCallback)
                        .addOnCompleteListener(task -> {
                            requestingLocationUpdates = false;
                            Log.d(TAG, "Location updates stopped.");
                        })
                        .addOnFailureListener(e -> {
                            Log.e(TAG, "Failed to remove location updates", e);
                        });
            } catch (Exception e) {
                Log.e(TAG, "Error removing location updates", e);
            }
        }
    }
    private String getCurrentLocationString() { if (lastKnownLocation != null) { return String.format(Locale.US, "Lat: %.6f, Lng: %.6f (http://maps.google.com/maps?q=%.6f,%.6f)", lastKnownLocation.getLatitude(), lastKnownLocation.getLongitude(), lastKnownLocation.getLatitude(), lastKnownLocation.getLongitude()); } else { return "Location unavailable"; } }

    // --- Fatigue Detection Callback (Handles cancelling countdown *only* if fatigue ends *during a fatigue* alert) ---
    @Override
    public void onFatigueDetected(final boolean isFatigued) {
        mainThreadHandler.post(() -> {
            // *** MODIFIED: Only cancel countdown if fatigue ends AND it's a fatigue alert ***
            if (!isFatigued && isAlertCountdownActive && "Fatigue".equals(pendingAlertType)) {
                Log.i(TAG, "Fatigue ended during FATIGUE countdown. Cancelling alert.");
                cancelCountdown("Fatigue Ended"); // This will also stop the alarm
                return; // Don't process further state changes for this event
            }

            // Ignore new fatigue detection if countdown is already active for something else
            if (isFatigued && isAlertCountdownActive) {
                Log.d(TAG,"New fatigue detected, but countdown already active. Ignoring.");
                return;
            }

            // Standard fatigue state change handling (only if no countdown active)
            if (isFatigued) {
                if (!isFatigueDetectedState) {
                    Log.w(TAG, "Fatigue DETECTED."); updateStatus("Status: Fatigue Detected!", true);
                    setFatigueWarningVisibility(true); playFatigueAlarm(); isFatigueDetectedState = true;
                    triggerFatigueAlert("Driver Fatigue Detected"); // This starts its own countdown
                }
            } else { // !isFatigued and countdown is not active
                if (isFatigueDetectedState) {
                    Log.i(TAG, "Fatigue ended (normal)."); updateStatus("Status: Monitoring", true);
                    setFatigueWarningVisibility(false); stopFatigueAlarm(); isFatigueDetectedState = false;
                }
            }
        });
    }
    @Override
    public void onNoFaceDetected() {
        mainThreadHandler.post(() -> {
            // *** MODIFIED: Only cancel countdown if no face AND it's a fatigue alert ***
            if (isAlertCountdownActive && "Fatigue".equals(pendingAlertType)) {
                Log.i(TAG, "No face detected during FATIGUE countdown. Cancelling alert.");
                cancelCountdown("No Face Detected"); // This will also stop the alarm
                return;
            }

            // Standard handling if countdown is not active (or if it's an accident countdown)
            if (isFatigueDetectedState) {
                Log.i(TAG, "Fatigue ended (No Face)."); updateStatus("Status: Monitoring", true);
                setFatigueWarningVisibility(false); stopFatigueAlarm(); isFatigueDetectedState = false;
            }
            // If an accident countdown is active, we do nothing here - let the accident alert proceed.
        });
    }

    // --- Alert Triggering ---
    private void triggerAccidentAlert(String cause) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastAccidentAlertTime < ALERT_COOLDOWN_MS) { Log.w(TAG, "Accident alert cooldown."); return; }
        String emergencyContact = getEmergencyContact(); if (emergencyContact == null) return;
        Log.w(TAG, "Accident Trigger -> Countdown Start. Cause: " + cause);
        updateStatus("Status: ACCIDENT DETECTED!", true);
        sendNotification("Potential Accident!", "Sending alert in " + COUNTDOWN_SECONDS + "s...");
        startAlertCountdown("Accident", cause, getCurrentLocationString(), emergencyContact); // Type is "Accident"
    }
    private void triggerFatigueAlert(String cause) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastFatigueAlertTime < ALERT_COOLDOWN_MS) { return; }
        String emergencyContact = getEmergencyContact(); if (emergencyContact == null) return;
        Log.w(TAG, "Fatigue Trigger -> Countdown Start. Cause: " + cause);
        sendNotification("Fatigue Alert!", "Sending alert in " + COUNTDOWN_SECONDS + "s...");
        startAlertCountdown("Fatigue", cause, getCurrentLocationString(), emergencyContact); // Type is "Fatigue"
    }

    // --- Alert Countdown Logic ---
    private void startAlertCountdown(String alertType, String cause, String locationString, String emergencyContact) {
        if (isAlertCountdownActive) { Log.w(TAG, "Countdown already active."); return; }
        isAlertCountdownActive = true;
        pendingAlertType = alertType; // *** Store the alert type ***
        pendingAlertCause = cause;
        pendingAlertLocation = locationString;
        pendingEmergencyContact = emergencyContact;

        // Keep alarm playing if it was already playing (e.g., fatigue led to accident)

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(alertType + " Detected!") // Show type in dialog title
                .setMessage("Sending alert in " + COUNTDOWN_SECONDS + " seconds...")
                .setCancelable(false) // User must explicitly cancel
                .setNegativeButton("CANCEL ALERT", (dialog, which) -> cancelCountdown("User cancelled"));
        alertCountdownDialog = builder.create();
        alertCountdownDialog.show();

        alertCountDownTimer = new CountDownTimer(COUNTDOWN_SECONDS * 1000, 1000) {
            @SuppressLint("SetTextI18n")
            @Override public void onTick(long millisUntilFinished) {
                if (alertCountdownDialog != null && alertCountdownDialog.isShowing()) {
                    // Ensure the message includes the remaining seconds correctly
                    alertCountdownDialog.setMessage("Sending alert in " + TimeUnit.MILLISECONDS.toSeconds(millisUntilFinished) + " seconds...");
                }
            }
            @Override public void onFinish() {
                Log.i(TAG, "Countdown finished for " + pendingAlertType); // Log type
                if (alertCountdownDialog != null && alertCountdownDialog.isShowing()) alertCountdownDialog.dismiss();
                alertCountdownDialog = null;
                alertCountDownTimer = null;
                isAlertCountdownActive = false; // Mark countdown as finished *before* sending

                long currentTime = System.currentTimeMillis();
                // Use the stored pendingAlertType here
                if ("Accident".equals(pendingAlertType)) {
                    lastAccidentAlertTime = currentTime;
                } else if ("Fatigue".equals(pendingAlertType)) {
                    lastFatigueAlertTime = currentTime;
                }

                // Perform the alert (SMS/Call) using stored pending info
                performActualAlertSend(pendingAlertType, pendingAlertCause, pendingAlertLocation, pendingEmergencyContact);

                // Reset visuals and stop alarm *after* sending, only if it was a fatigue alert
                if ("Fatigue".equals(pendingAlertType)) {
                    resetFatigueVisuals(); // This internally calls stopFatigueAlarm
                }
                // For accident alerts, we don't necessarily reset fatigue visuals here,
                // as fatigue might still be present. The fatigue logic will handle it separately.

                // Clear pending info AFTER use
                pendingAlertType = "";
                pendingAlertCause = "";
                pendingAlertLocation = "";
                pendingEmergencyContact = "";
            }
        }.start();
    }

    private void cancelCountdown(String reason) {
        Log.w(TAG, "Countdown cancelled: " + reason + " (Was for: " + pendingAlertType + ")"); // Log type
        if (alertCountDownTimer != null) {
            alertCountDownTimer.cancel();
            alertCountDownTimer = null;
        }
        if (alertCountdownDialog != null && alertCountdownDialog.isShowing()) {
            alertCountdownDialog.dismiss();
            alertCountdownDialog = null;
        }
        isAlertCountdownActive = false;
        // Clear pending info on cancellation
        pendingAlertType = "";
        pendingAlertCause = "";
        pendingAlertLocation = "";
        pendingEmergencyContact = "";

        // Stop the alarm explicitly when countdown is cancelled, regardless of type
        stopFatigueAlarm();

        Toast.makeText(this, "Alert Canceled", Toast.LENGTH_SHORT).show();
        updateStatus("Status: Monitoring", true); // Reset status

        // If the cancellation reason wasn't "Fatigue Ended" or "No Face Detected"
        // while the alert type *was* "Fatigue", we might need to re-evaluate the fatigue state.
        // However, the continuous checks in onFatigueDetected should handle this.
    }


    // --- Perform Actual Alert Send ---
    private void performActualAlertSend(String alertType, String cause, String locationString, String emergencyContact) {
        Log.i(TAG, "Sending alert for " + alertType);
        String smsMessage;
        if ("Accident".equals(alertType)) {
            smsMessage = "Emergency! Potential Accident Detected (" + cause + "). Last known location: " + locationString;
        } else { // Fatigue
            smsMessage = "Alert: Driver Fatigue Detected. Last known location: " + locationString;
        }
        sendEmergencySMS(emergencyContact, smsMessage);
        // Only call for Accidents
        if ("Accident".equals(alertType)) {
            makeEmergencyCall(emergencyContact);
        } else {
            Log.i(TAG,"Fatigue alert: Call skipped.");
        }
        updateStatus("Status: Alert Sent!", true);
        mainThreadHandler.postDelayed(() -> {
            // Reset status only if no new alert/fatigue state has occurred
            if (!isAlertCountdownActive && !isFatigueDetectedState) {
                updateStatus("Status: Monitoring", true);
            }
        }, 5000); // Reset status after 5 seconds
    }


    // --- Alert Sending Helpers ---
    private String getEmergencyContact() { SharedPreferences prefs = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE); String contact = prefs.getString(KEY_EMERGENCY_CONTACT_PHONE, null); if (TextUtils.isEmpty(contact)) { Log.e(TAG, "Emergency contact missing."); Toast.makeText(this, "Set emergency contact!", Toast.LENGTH_LONG).show(); updateStatus("Status: Set Emergency Contact!", true); return null; } return contact; }
    private boolean isEmergencyContactSet() { SharedPreferences prefs = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE); return !TextUtils.isEmpty(prefs.getString(KEY_EMERGENCY_CONTACT_PHONE, null)); }
    private void sendEmergencySMS(String contactNumber, String message) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) { Log.e(TAG, "SMS Permission denied."); return; }
        if (TextUtils.isEmpty(contactNumber)) { Log.e(TAG, "Invalid contact for SMS."); return; }
        try { SmsManager smsManager = SmsManager.getDefault(); ArrayList<String> parts = smsManager.divideMessage(message); smsManager.sendMultipartTextMessage(contactNumber, null, parts, null, null); Toast.makeText(this, "Emergency SMS Sent!", Toast.LENGTH_SHORT).show(); Log.i(TAG, "SMS sent to " + contactNumber); }
        catch (Exception e) { Log.e(TAG, "SMS send failed", e); Toast.makeText(this, "Failed to send SMS.", Toast.LENGTH_SHORT).show(); }
    }
    private void makeEmergencyCall(String contactNumber) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) { Log.e(TAG, "Call Permission denied."); return; }
        if (TextUtils.isEmpty(contactNumber)) { Log.e(TAG, "Invalid contact for Call."); return; }
        try { Intent callIntent = new Intent(Intent.ACTION_CALL); callIntent.setData(Uri.parse("tel:" + contactNumber)); startActivity(callIntent); Log.i(TAG, "Calling " + contactNumber); Toast.makeText(this, "Calling Emergency Contact...", Toast.LENGTH_SHORT).show(); }
        catch (SecurityException e) { Log.e(TAG, "Call failed (Security)", e); Toast.makeText(this, "Call failed (permission).", Toast.LENGTH_SHORT).show(); }
        catch (Exception e){ Log.e(TAG, "Call failed", e); Toast.makeText(this, "Could not initiate call.", Toast.LENGTH_SHORT).show(); }
    }

    // --- Notification & UI Helpers ---
    private void sendNotification(String title, String message) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) { Log.w(TAG, "Notification permission missing."); return; } NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID) .setSmallIcon(R.drawable.ic_launcher_foreground) .setContentTitle(title).setContentText(message) .setStyle(new NotificationCompat.BigTextStyle().bigText(message)) .setPriority(NotificationCompat.PRIORITY_HIGH) .setDefaults(NotificationCompat.DEFAULT_ALL) .setAutoCancel(true); NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE); if (manager != null) manager.notify((int) System.currentTimeMillis(), builder.build()); }
    private void createNotificationChannel() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { CharSequence name = "Driver Safety Alerts"; String description = "Fatigue/accident notifications"; int importance = NotificationManager.IMPORTANCE_HIGH; NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance); channel.setDescription(description); channel.enableLights(true); channel.enableVibration(true); NotificationManager nM = getSystemService(NotificationManager.class); if (nM != null) nM.createNotificationChannel(channel); } }
    private void updateStatus(final String status, boolean animate) { mainThreadHandler.post(() -> { if (statusTextView != null) { if (animate && statusTextView.getVisibility() == View.VISIBLE) { statusTextView.animate().alpha(0f).setDuration(150).setListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator animation) { statusTextView.setText(status); statusTextView.animate().alpha(1f).setDuration(150).setListener(null).start(); } }).start(); } else { statusTextView.setAlpha(1f); statusTextView.setText(status); } } }); }
    private void setFatigueWarningVisibility(boolean visible) { mainThreadHandler.post(()-> { if (fatigueWarningTextView != null) { if (visible && fatigueWarningTextView.getVisibility() != View.VISIBLE) { fatigueWarningTextView.setAlpha(0f); fatigueWarningTextView.setVisibility(View.VISIBLE); fatigueWarningTextView.animate().alpha(1f).setDuration(300).setListener(null).start(); } else if (!visible && fatigueWarningTextView.getVisibility() == View.VISIBLE) { fatigueWarningTextView.animate().alpha(0f).setDuration(300).setListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator animation) { fatigueWarningTextView.setVisibility(View.GONE); } }).start(); } } }); }
    private void resetFatigueVisuals() { mainThreadHandler.post(() -> {
        // This is called AFTER a fatigue alert is sent OR if fatigue ends normally OR after fatigue countdown cancelled
        if (isFatigueDetectedState || isFatigueAlarmPlaying) { // Check if visuals/alarm need reset
            Log.d(TAG,"Resetting fatigue visuals and stopping alarm.");
            isFatigueDetectedState = false; // Ensure state is false
            stopFatigueAlarm(); // Stop the alarm
            setFatigueWarningVisibility(false); // Hide warning
            // Only reset status to Monitoring if no other alert is active
            if (!isAlertCountdownActive) {
                updateStatus("Status: Monitoring", true);
            }
        }
    }); }

    // --- Alarm Sound Handling ---
    private void initializeMediaPlayer() { try { if (alarmMediaPlayer == null) { alarmMediaPlayer = MediaPlayer.create(this, R.raw.alarm); if (alarmMediaPlayer != null) { alarmMediaPlayer.setLooping(true); alarmMediaPlayer.setOnErrorListener((mp, what, extra) -> { Log.e(TAG, "MediaPlayer Error: " + what); releaseMediaPlayer(); isFatigueAlarmPlaying = false; return true; }); Log.d(TAG,"MediaPlayer initialized."); } else { Log.e(TAG,"Failed MediaPlayer create."); } } } catch (Exception e) { Log.e(TAG, "MediaPlayer init exception", e); } }
    private void playFatigueAlarm() { if (alarmMediaPlayer == null) initializeMediaPlayer(); if (alarmMediaPlayer != null && !isFatigueAlarmPlaying) { try { alarmMediaPlayer.start(); isFatigueAlarmPlaying = true; Log.d(TAG, "Fatigue alarm started."); } catch (IllegalStateException e) { Log.e(TAG,"MediaPlayer start error", e); releaseMediaPlayer(); } } }
    private void stopFatigueAlarm() { if (alarmMediaPlayer != null && isFatigueAlarmPlaying) { try { alarmMediaPlayer.pause(); alarmMediaPlayer.seekTo(0); isFatigueAlarmPlaying = false; Log.d(TAG, "Fatigue alarm stopped."); } catch (IllegalStateException e) { Log.e(TAG,"MediaPlayer stop error", e); releaseMediaPlayer(); } } }
    private void releaseMediaPlayer() { if (alarmMediaPlayer != null) { try { if (alarmMediaPlayer.isPlaying()) alarmMediaPlayer.stop(); alarmMediaPlayer.release(); } catch(Exception e) { Log.e(TAG,"MediaPlayer release error", e); } finally { alarmMediaPlayer = null; isFatigueAlarmPlaying = false; Log.d(TAG,"MediaPlayer released."); } } }

    // --- Activity Lifecycle Management ---
    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume.");
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(); Log.d(TAG, "WakeLock acquired.");
        }
        if (isEmergencyContactSet()) {
            if (areCorePermissionsGranted()) { registerSensorListeners(); if (!requestingLocationUpdates) startLocationUpdates(); }
            else { checkAndRequestPermissions(); }
        } else {
            updateStatus("Status: Set Emergency Contact!", true); Toast.makeText(this, "Emergency contact needed.", Toast.LENGTH_LONG).show();
            // Still register sensors/location even if contact isn't set, but alerts won't send
            if (areCorePermissionsGranted()) { registerSensorListeners(); if (!requestingLocationUpdates) startLocationUpdates(); }
            else { checkAndRequestPermissions(); }
        }
        if (alarmMediaPlayer == null) initializeMediaPlayer();
    }
    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause.");
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release(); Log.d(TAG, "WakeLock released.");
        }
        // Cancel any active countdown when the app is paused
        if (isAlertCountdownActive) cancelCountdown("Activity Paused");
        unregisterSensorListeners();
        stopFatigueAlarm(); // Stop alarm if activity is paused
        // Don't stop location updates on pause, allow background monitoring if needed/configured
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy.");
        // Ensure countdown is cancelled on destroy
        if (isAlertCountdownActive) cancelCountdown("Activity Destroyed");
        if (wakeLock != null && wakeLock.isHeld()) { wakeLock.release(); Log.w(TAG,"WakeLock released in onDestroy."); }
        if (cameraProvider != null) cameraProvider.unbindAll();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (fatigueDetector != null) fatigueDetector.stop();
        unregisterSensorListeners();
        stopLocationUpdates(); // Stop location updates when activity is destroyed
        releaseMediaPlayer();
        mainThreadHandler.removeCallbacksAndMessages(null);
        Log.d(TAG, "Resources released.");
    }
}