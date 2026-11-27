package org.localwake.app.test;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;

/** An ordinary external tap target, launched as the test APK's own process. */
public final class TapPracticeActivity extends Activity {
    private static final String TAG = "UTTapPractice";
    private String mode;
    private int after;
    private int count;
    private long run;
    private MotionEvent clickMotion;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // An agent may freshly launch this package after adb stages the scenario. Only the two
        // controls live in this test APK's private preferences; each new run starts at zero.
        SharedPreferences controls = getSharedPreferences("pad_controls", MODE_PRIVATE);
        String requested = getIntent().getStringExtra("mode");
        if (requested != null) {
            mode = "page".equals(requested) || "secure".equals(requested) ? requested : "stable";
            after = Math.max(1, getIntent().getIntExtra("after", 3));
            controls.edit().putString("mode", mode).putInt("after", after).apply();
        } else {
            mode = controls.getString("mode", "stable");
            after = controls.getInt("after", 3);
        }
        run = state == null ? SystemClock.elapsedRealtimeNanos() : state.getLong("run");
        count = state == null ? 0 : state.getInt("count");
        if (count >= after && "secure".equals(mode)) secureWindow();
        showPad(count >= after && "page".equals(mode));
        record("event=ready mode=" + mode + " after=" + after + " count=" + count);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putLong("run", run);
        state.putInt("count", count);
    }

    private void showPad(boolean nextPage) {
        String page = nextPage ? "next" : "practice";
        Button pad = new Button(this);
        pad.setId(nextPage ? android.R.id.button2 : android.R.id.button1);
        pad.setAllCaps(false);
        pad.setText(nextPage ? "Next pad" : "Practice pad");
        pad.setOnClickListener(v -> completeTap(page));

        // Complete each single-finger tap synchronously at UP. Accessibility ACTION_CLICK also
        // reaches the click listener and is explicitly distinguished from measured touch input.
        GestureDetector taps = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) {
                record("event=down page=" + page + " startMs=" + event.getDownTime()
                        + " x=" + Math.round(event.getRawX()) + " y=" + Math.round(event.getRawY()));
                return true;
            }

            @Override public boolean onSingleTapUp(MotionEvent event) {
                clickMotion = event;
                try { return pad.performClick(); }
                finally { clickMotion = null; }
            }
        });
        taps.setOnDoubleTapListener(null); // Rapid consecutive taps are independent observations.
        pad.setOnTouchListener((v, event) -> { taps.onTouchEvent(event); return true; });

        FrameLayout content = new FrameLayout(this);
        float density = getResources().getDisplayMetrics().density;
        content.addView(pad, new FrameLayout.LayoutParams(
                Math.round(200 * density), Math.round(80 * density), Gravity.CENTER));
        setContentView(content);
    }

    private void completeTap(String page) {
        count++;
        String timing = clickMotion == null
                ? "source=activation clickMs=" + SystemClock.uptimeMillis()
                : "source=motion startMs=" + clickMotion.getDownTime() + " upMs=" + clickMotion.getEventTime();
        record("event=tap count=" + count + " page=" + page + " " + timing);
        if (count != after) return;
        if ("page".equals(mode)) {
            showPad(true); // A different target at the same coordinates reveals any extra tap.
            record("event=boundary kind=page count=" + count);
        } else if ("secure".equals(mode)) {
            secureWindow();
            record("event=boundary kind=secure_flag_requested count=" + count);
        }
    }

    private void secureWindow() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    private void record(String fields) {
        Log.i(TAG, "run=" + run + " " + fields);
    }
}
