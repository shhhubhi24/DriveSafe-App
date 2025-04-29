package com.example.driversafetyapp; // Match your package name

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

public class UserDetailsActivity extends AppCompatActivity {

    private static final String TAG = "UserDetailsActivity";
    // SharedPreferences keys (use constants defined in MainActivity or a dedicated class)
    public static final String SHARED_PREFS_NAME = "DriverSafetyPrefs";
    public static final String KEY_USER_NAME = "userName";
    public static final String KEY_EMERGENCY_CONTACT_NAME = "emergencyContactName";
    public static final String KEY_EMERGENCY_CONTACT_PHONE = "emergencyContactPhone";

    private TextInputEditText editTextUserName;
    private TextInputEditText editTextEmergencyContactName;
    private TextInputEditText editTextEmergencyContactPhone;
    private TextInputLayout textInputLayoutEmergencyContactPhone; // For error display
    private Button buttonSave;

    private SharedPreferences sharedPreferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_details);

        // Initialize SharedPreferences
        sharedPreferences = getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE);

        // Find Views
        editTextUserName = findViewById(R.id.editTextUserName);
        editTextEmergencyContactName = findViewById(R.id.editTextEmergencyContactName);
        editTextEmergencyContactPhone = findViewById(R.id.editTextEmergencyContactPhone);
        textInputLayoutEmergencyContactPhone = findViewById(R.id.textInputLayoutEmergencyContactPhone); // Get layout
        buttonSave = findViewById(R.id.buttonSave);

        // Load existing details
        loadUserDetails();

        // Set Save button listener
        buttonSave.setOnClickListener(v -> saveUserDetails());
    }

    private void loadUserDetails() {
        String userName = sharedPreferences.getString(KEY_USER_NAME, "");
        String emergencyName = sharedPreferences.getString(KEY_EMERGENCY_CONTACT_NAME, "");
        String emergencyPhone = sharedPreferences.getString(KEY_EMERGENCY_CONTACT_PHONE, "");

        editTextUserName.setText(userName);
        editTextEmergencyContactName.setText(emergencyName);
        editTextEmergencyContactPhone.setText(emergencyPhone);
        Log.d(TAG, "Loaded user details.");
    }

    private void saveUserDetails() {
        String userName = editTextUserName.getText() != null ? editTextUserName.getText().toString().trim() : "";
        String emergencyName = editTextEmergencyContactName.getText() != null ? editTextEmergencyContactName.getText().toString().trim() : "";
        String emergencyPhone = editTextEmergencyContactPhone.getText() != null ? editTextEmergencyContactPhone.getText().toString().trim() : "";

        // --- !! Basic Validation !! ---
        if (TextUtils.isEmpty(emergencyPhone)) {
            textInputLayoutEmergencyContactPhone.setError("Emergency contact phone number is required!");
            // Optionally: editTextEmergencyContactPhone.requestFocus();
            Log.w(TAG, "Save failed: Emergency phone number missing.");
            return; // Stop saving
        } else {
            // Simple check for phone number length (adjust as needed for your region)
            if (emergencyPhone.length() < 7) { // Example: Arbitrary minimum length
                textInputLayoutEmergencyContactPhone.setError("Please enter a valid phone number.");
                Log.w(TAG, "Save failed: Emergency phone number too short.");
                return;
            } else {
                textInputLayoutEmergencyContactPhone.setError(null); // Clear error if valid
            }
        }

        // Save to SharedPreferences
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(KEY_USER_NAME, userName);
        editor.putString(KEY_EMERGENCY_CONTACT_NAME, emergencyName);
        editor.putString(KEY_EMERGENCY_CONTACT_PHONE, emergencyPhone);
        editor.apply(); // Use apply() for asynchronous saving

        Log.i(TAG, "User details saved successfully.");
        Toast.makeText(this, "Details Saved!", Toast.LENGTH_SHORT).show();

        // Optional: Close the activity after saving
        // finish();
    }
}