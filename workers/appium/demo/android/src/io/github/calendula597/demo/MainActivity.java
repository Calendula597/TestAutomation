package io.github.calendula597.demo;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);
        TextView status = new TextView(this);
        status.setContentDescription("statusLabel");
        EditText note = new EditText(this);
        note.setContentDescription("noteInput");
        note.setHint("Note");
        Button save = new Button(this);
        save.setText("Save");
        save.setContentDescription("saveButton");
        String saved = getPreferences(MODE_PRIVATE).getString("note", null);
        status.setText(saved == null ? "Ready" : "Saved: " + saved);
        save.setOnClickListener(view -> {
            String value = note.getText().toString();
            getPreferences(MODE_PRIVATE).edit().putString("note", value).apply();
            status.setText("Saved: " + value);
        });
        layout.addView(status);
        layout.addView(note);
        layout.addView(save);
        setContentView(layout);
    }
}
