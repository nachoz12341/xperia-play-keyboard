package com.nachoz12341.playkeyboard;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;

/**
 * Minimal launcher entry point. The IME itself needs no activity to function, but
 * without one the app would have no visible presence after install - this just
 * shows brief instructions and a shortcut into Settings > Language & input.
 */
public class PlayKeyboardSettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Button openSettingsButton = (Button) findViewById(R.id.open_input_method_settings);
        openSettingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
            }
        });
    }
}
