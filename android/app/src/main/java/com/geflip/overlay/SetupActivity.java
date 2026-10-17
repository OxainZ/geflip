package com.geflip.overlay;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

/**
 * The only screen: pick the page, grant "draw over other apps", start the window.
 * Everything after this happens in {@link OverlayService} on top of the game.
 */
public class SetupActivity extends Activity
{
	private Prefs prefs;
	private EditText url;
	private TextView status;
	private Button grant;

	@Override
	protected void onCreate(Bundle saved)
	{
		super.onCreate(saved);
		setContentView(R.layout.activity_setup);
		prefs = new Prefs(this);

		url = findViewById(R.id.url);
		status = findViewById(R.id.status);
		grant = findViewById(R.id.grant);
		url.setText(prefs.url());

		grant.setOnClickListener(new View.OnClickListener()
		{
			public void onClick(View v)
			{
				startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
					Uri.parse("package:" + getPackageName())));
			}
		});

		findViewById(R.id.start).setOnClickListener(new View.OnClickListener()
		{
			public void onClick(View v) { start(); }
		});

		findViewById(R.id.stop).setOnClickListener(new View.OnClickListener()
		{
			public void onClick(View v)
			{
				stopService(new Intent(SetupActivity.this, OverlayService.class));
				status.setText("Overlay stopped.");
			}
		});

		// The foreground-service notification is how you stop the window if it ever ends up
		// somewhere you cannot reach — worth asking for up front on Android 13+.
		if (Build.VERSION.SDK_INT >= 33
			&& checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
				!= PackageManager.PERMISSION_GRANTED)
		{
			requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
		}
	}

	@Override
	protected void onResume()
	{
		super.onResume();
		boolean ok = OverlayService.allowed(this);
		grant.setEnabled(!ok);
		grant.setText(ok ? "Drawing over other apps: allowed" : "Allow drawing over other apps");
	}

	private void start()
	{
		String typed = url.getText().toString().trim();
		prefs.url(typed.isEmpty() ? Prefs.DEFAULT_URL : typed);

		if (!OverlayService.allowed(this))
		{
			status.setText("Android still needs the \"draw over other apps\" permission — "
				+ "tap the button above, switch geflip overlay on, then come back.");
			return;
		}
		startForegroundService(new Intent(this, OverlayService.class));
		status.setText("Overlay running — loading " + Prefs.overlayUrl(prefs.url())
			+ "\n\nOpen OSRS; it stays on top. Home does not close it, the ✕ or the "
			+ "notification does.");
		moveTaskToBack(true);
	}
}
