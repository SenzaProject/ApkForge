package com.example.hello;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView t = new TextView(this);
        t.setText("Halo dari APK buatan ApkForge!");
        t.setTextSize(22);
        t.setPadding(48, 48, 48, 48);
        setContentView(t);
    }
}
