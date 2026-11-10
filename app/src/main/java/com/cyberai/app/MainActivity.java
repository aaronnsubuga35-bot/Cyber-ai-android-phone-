
package com.cyberai.app;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.provider.ContactsContract;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.RecognitionListener;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.database.Cursor;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private SpeechRecognizer recognizer;
    private boolean listening = false;
    private static final int MIC_PERMISSION = 41;
    private static final int ROLE_ASSISTANT_REQUEST = 42;
    private static final int GENERIC_PERMISSION_REQUEST = 43;
    private String pendingPermission = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(android.graphics.Color.rgb(7,17,31));
        getWindow().setNavigationBarColor(android.graphics.Color.rgb(7,17,31));
        web = new WebView(this);
        web.setBackgroundColor(android.graphics.Color.rgb(7,17,31));
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new CyberBridge(), "CyberAndroid");
        web.addJavascriptInterface(new VoiceBridge(), "AaronNative");
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(Locale.US);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED)
                    tts.setLanguage(Locale.getDefault());
                tts.setSpeechRate(0.96f);
                ttsReady = true;
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) {
                        runJs("window.onAaronSpeechState&&window.onAaronSpeechState(true)");
                    }
                    @Override public void onDone(String id) {
                        runOnUiThread(() -> runJs("window.onAaronSpeechState&&window.onAaronSpeechState(false)"));
                    }
                    @Override public void onError(String id) {
                        runOnUiThread(() -> runJs("window.onAaronSpeechState&&window.onAaronSpeechState(false)"));
                    }
                });
            }
        });
    }

    private void runJs(String js) {
        if (web != null) web.post(() -> web.evaluateJavascript(js, null));
    }

    private String roleStatus() {
        if (Build.VERSION.SDK_INT < 29)
            return "This Android version does not support the assistant role picker.";
        RoleManager rm = (RoleManager)getSystemService(ROLE_SERVICE);
        if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT))
            return "Android assistant role is not available on this device.";
        return rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
                ? "Cyber AI is selected as your default Android assistant."
                : "Cyber AI is not yet your default assistant.";
    }

    private class CyberBridge {
        @JavascriptInterface public String requestDefaultAssistant() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT < 29) {
                    toast("Your Android version does not support the assistant role picker.");
                    return;
                }
                RoleManager rm = (RoleManager)getSystemService(ROLE_SERVICE);
                if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                    toast("The assistant role is unavailable on this phone.");
                    return;
                }
                if (rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
                    toast("Cyber AI is already your default assistant.");
                    return;
                }
                startActivityForResult(
                        rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT),
                        ROLE_ASSISTANT_REQUEST);
            });
            return "Android's default assistant selection is opening. Select Cyber AI if it appears.";
        }

        @JavascriptInterface public String getDefaultAssistantStatus() {
            return roleStatus();
        }

        @JavascriptInterface public String openSystemSettings(String name) {
            try {
                Intent i;
                switch (name == null ? "app" : name.toLowerCase(Locale.ROOT)) {
                    case "internet":
                        i = new Intent("android.settings.panel.action.INTERNET_CONNECTIVITY");
                        break;
                    case "bluetooth":
                        i = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
                        break;
                    case "display":
                        i = new Intent(Settings.ACTION_DISPLAY_SETTINGS);
                        break;
                    case "sound":
                        i = new Intent(Settings.ACTION_SOUND_SETTINGS);
                        break;
                    case "permissions":
                    case "app":
                        i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName()));
                        break;
                    default:
                        i = new Intent(Settings.ACTION_SETTINGS);
                }
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return "Opened Android settings.";
            } catch (Exception e) {
                return "Could not open that settings page: " + e.getMessage();
            }
        }

        @JavascriptInterface public String requestPermission(String name) {
            String permission = permissionFor(name);
            if (permission == null)
                return "This permission is not supported by this Android build.";
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 23 &&
                        checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                    pendingPermission = name;
                    requestPermissions(new String[]{permission}, GENERIC_PERMISSION_REQUEST);
                } else {
                    toast(name + " permission is already granted.");
                }
            });
            return "Android permission prompt requested for " + name + ".";
        }

        @JavascriptInterface public String makeCall(String number) {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_DIAL,
                            Uri.parse("tel:" + Uri.encode(number)));
                    startActivity(i);
                } catch (Exception e) {
                    toast("Unable to open the phone dialer.");
                }
            });
            return "Opened the dialer with the number. Review it and tap Call yourself.";
        }

        @JavascriptInterface public String sendSms(String number, String message) {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_SENDTO,
                            Uri.parse("smsto:" + Uri.encode(number)));
                    i.putExtra("sms_body", message);
                    startActivity(i);
                } catch (Exception e) {
                    toast("Unable to open the messaging app.");
                }
            });
            return "Opened your messaging app with a draft. Review and send it yourself.";
        }

        @JavascriptInterface public String setBluetooth(boolean enabled) {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                } catch (Exception e) {
                    toast("Open Bluetooth from Android Settings.");
                }
            });
            return "Opened Bluetooth settings. Android requires you to confirm the connection or switch there.";
        }

        @JavascriptInterface public String setTorch(boolean enabled) {
            runOnUiThread(() -> setTorchNative(enabled));
            return "Flashlight action requested.";
        }

        @JavascriptInterface public String vibrate(int milliseconds) {
            runOnUiThread(() -> {
                try {
                    Vibrator v = (Vibrator)getSystemService(VIBRATOR_SERVICE);
                    if (v != null && v.hasVibrator()) {
                        if (Build.VERSION.SDK_INT >= 26)
                            v.vibrate(VibrationEffect.createOneShot(
                                    Math.max(50, Math.min(milliseconds, 1500)),
                                    VibrationEffect.DEFAULT_AMPLITUDE));
                        else
                            v.vibrate(milliseconds);
                    }
                } catch (Exception ignored) {}
            });
            return "Vibration requested.";
        }

        @JavascriptInterface public String openApp(String packageName) {
            runOnUiThread(() -> {
                try {
                    Intent i = getPackageManager().getLaunchIntentForPackage(packageName);
                    if (i != null) startActivity(i);
                    else toast("That app is not installed or cannot be opened.");
                } catch (Exception e) {
                    toast("Could not open that app.");
                }
            });
            return "App launch request processed.";
        }

        @JavascriptInterface public String openAppByName(String requestedName) {
            if (requestedName == null || requestedName.trim().isEmpty())
                return "Tell me which app to open.";
            final String wanted = requestedName.trim().toLowerCase(Locale.ROOT);
            final android.content.pm.PackageManager pm = getPackageManager();
            try {
                Intent query = new Intent(Intent.ACTION_MAIN);
                query.addCategory(Intent.CATEGORY_LAUNCHER);
                java.util.List<android.content.pm.ResolveInfo> apps =
                        pm.queryIntentActivities(query, 0);
                android.content.pm.ResolveInfo best = null;
                int bestScore = -1;
                for (android.content.pm.ResolveInfo info : apps) {
                    String label = String.valueOf(info.loadLabel(pm)).trim();
                    String lower = label.toLowerCase(Locale.ROOT);
                    int score = lower.equals(wanted) ? 100 :
                            lower.startsWith(wanted) ? 80 :
                            lower.contains(wanted) ? 60 :
                            wanted.contains(lower) ? 30 : -1;
                    if (score > bestScore) {
                        bestScore = score;
                        best = info;
                    }
                }
                if (best == null || bestScore < 0)
                    return "I couldn't find an installed app named " + requestedName +
                            ". Check the app name and try again.";
                String packageName = best.activityInfo.packageName;
                Intent launch = pm.getLaunchIntentForPackage(packageName);
                if (launch == null)
                    return "The app is installed but Android did not provide a launch action for it.";
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
                return "Opening " + String.valueOf(best.loadLabel(pm)) + ".";
            } catch (Exception e) {
                return "Could not open " + requestedName + ": " + e.getMessage();
            }
        }

        @JavascriptInterface public String getContacts() {
            if (checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
                    PackageManager.PERMISSION_GRANTED) {
                requestPermission("READ_CONTACTS");
                return "Contacts permission requested. Ask again after granting it.";
            }
            StringBuilder out = new StringBuilder("[");
            Cursor c = null;
            try {
                c = getContentResolver().query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        new String[]{
                                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                                ContactsContract.CommonDataKinds.Phone.NUMBER
                        },
                        null, null,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");
                int count = 0;
                while (c != null && c.moveToNext() && count < 100) {
                    if (count++ > 0) out.append(',');
                    out.append("{\"name\":\"")
                            .append(jsonEscape(c.getString(0)))
                            .append("\",\"number\":\"")
                            .append(jsonEscape(c.getString(1)))
                            .append("\"}");
                }
            } catch (Exception e) {
                return "Contacts read failed: " + e.getMessage();
            } finally {
                if (c != null) c.close();
            }
            return out.append(']').toString();
        }
    }

    private class VoiceBridge {
        @JavascriptInterface public void speak(String text) {
            runOnUiThread(() -> {
                if (ttsReady && tts != null) {
                    tts.stop();
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cyber_reply");
                }
            });
        }

        @JavascriptInterface public void stopSpeaking() {
            runOnUiThread(() -> {
                if (tts != null) tts.stop();
            });
        }

        @JavascriptInterface public void startListening() {
            runOnUiThread(() -> ensureMic());
        }

        @JavascriptInterface public void stopListening() {
            runOnUiThread(() -> stopNativeListening());
        }

        @JavascriptInterface public boolean isNativeApp() {
            return true;
        }
    }

    private String permissionFor(String name) {
        if (name == null) return null;
        switch (name.toUpperCase(Locale.ROOT)) {
            case "CAMERA": return Manifest.permission.CAMERA;
            case "RECORD_AUDIO": return Manifest.permission.RECORD_AUDIO;
            case "ACCESS_FINE_LOCATION": return Manifest.permission.ACCESS_FINE_LOCATION;
            case "ACCESS_COARSE_LOCATION": return Manifest.permission.ACCESS_COARSE_LOCATION;
            case "POST_NOTIFICATIONS":
                return Build.VERSION.SDK_INT >= 33
                        ? "android.permission.POST_NOTIFICATIONS"
                        : Manifest.permission.VIBRATE;
            case "READ_CONTACTS": return Manifest.permission.READ_CONTACTS;
            case "READ_CALENDAR": return Manifest.permission.READ_CALENDAR;
            case "WRITE_CALENDAR": return Manifest.permission.WRITE_CALENDAR;
            case "CALL_PHONE": return Manifest.permission.CALL_PHONE;
            case "SEND_SMS": return Manifest.permission.SEND_SMS;
            case "BLUETOOTH_CONNECT":
                return Build.VERSION.SDK_INT >= 31
                        ? Manifest.permission.BLUETOOTH_CONNECT
                        : Manifest.permission.BLUETOOTH;
            default: return null;
        }
    }

    private void setTorchNative(boolean enabled) {
        if (checkSelfPermission(Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA},
                    GENERIC_PERMISSION_REQUEST);
            return;
        }
        try {
            CameraManager cm = (CameraManager)getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                android.hardware.camera2.CameraCharacteristics c =
                        cm.getCameraCharacteristics(id);
                Boolean flash = c.get(
                        android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = c.get(
                        android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash) && facing != null &&
                        facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
                    cm.setTorchMode(id, enabled);
                    toast(enabled ? "Flashlight on." : "Flashlight off.");
                    return;
                }
            }
            toast("No compatible flashlight was found.");
        } catch (Exception e) {
            toast("Flashlight unavailable: " + e.getMessage());
        }
    }

    private void ensureMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                    MIC_PERMISSION);
        } else {
            startNativeListening();
        }
    }

    private void startNativeListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            runJs("window.onAaronMicError&&window.onAaronMicError('Android speech recognition is unavailable')");
            return;
        }
        stopNativeListening();
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) {
                listening = true;
                runJs("window.onAaronMicState&&window.onAaronMicState(true)");
            }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rms) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onError(int error) {
                if (!listening) return;
                if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    restartListening();
                } else {
                    listening = false;
                    runJs("window.onAaronMicState&&window.onAaronMicState(false)");
                    runJs("window.onAaronMicError&&window.onAaronMicError('Voice recognition stopped. Tap the microphone to try again.')");
                }
            }
            @Override public void onResults(Bundle data) {
                ArrayList<String> m =
                        data.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (m != null && !m.isEmpty()) sendTranscrip
