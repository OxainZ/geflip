package com.geflip.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The floating window itself: a WebView showing the geflip page in {@code ?overlay=1}
 * layout, drawn on top of whatever is in front — the OSRS mobile client, in practice.
 *
 * <p>It is deliberately dumb. All the market logic (scan, tax, buy limits, allocation)
 * stays in the page, which is the same one the PWA and the RuneLite bridge serve, so
 * there is exactly one flip engine and this app can never disagree with it.
 *
 * <p>It never reads or drives the game: no accessibility service, no MediaProjection,
 * no input injection. Two collapsed states so it can sit out of the way: a 48dp bubble
 * you drag anywhere, and the panel you expand when you are at the Grand Exchange.
 */
public class OverlayService extends Service
{
	static final String ACTION_STOP = "com.geflip.overlay.STOP";

	private static final String CHANNEL = "geflip-overlay";
	private static final int NOTE_ID = 1;

	private static final int DEF_W_DP = 320, DEF_H_DP = 380;
	private static final int MIN_W_DP = 200, MIN_H_DP = 160;
	private static final int BUBBLE_DP = 48;

	private WindowManager wm;
	private WindowManager.LayoutParams lp;
	private Prefs prefs;

	private FrameLayout root;
	private View bubble;
	private LinearLayout panel;
	private WebView web;

	private boolean expanded = true;
	private int touchSlop;

	@Override public IBinder onBind(Intent i) { return null; }

	@Override
	public void onCreate()
	{
		super.onCreate();
		prefs = new Prefs(this);
		wm = (WindowManager) getSystemService(WINDOW_SERVICE);
		touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

		startInForeground();
		buildViews();

		lp = new WindowManager.LayoutParams(
			dp(prefs.w(DEF_W_DP)), dp(prefs.h(DEF_H_DP)),
			WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
			// NOT_FOCUSABLE keeps the keyboard and the back key with the game underneath.
			// The overlay has no text fields, so it costs nothing and touch still lands here.
			WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
				| WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
				| WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
			PixelFormat.TRANSLUCENT);
		lp.gravity = Gravity.TOP | Gravity.START;
		lp.x = prefs.x();
		lp.y = prefs.y();
		root.setAlpha(prefs.alpha());

		try
		{
			wm.addView(root, lp);
		}
		catch (Exception e)
		{
			// Permission revoked between the setup screen and here — nothing to draw into.
			stopSelf();
			return;
		}
		web.loadUrl(Prefs.overlayUrl(prefs.url()));
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId)
	{
		if (intent != null && ACTION_STOP.equals(intent.getAction()))
		{
			stopSelf();
			return START_NOT_STICKY;
		}
		return START_STICKY;
	}

	@Override
	public void onConfigurationChanged(android.content.res.Configuration cfg)
	{
		super.onConfigurationChanged(cfg);
		// A rotation swaps the screen bounds under us; without this the window can end up
		// parked off the new display edge with no way to drag it back.
		clampPosition();
		safeUpdate();
	}

	@Override
	public void onDestroy()
	{
		if (root != null && root.isAttachedToWindow())
		{
			try { wm.removeView(root); } catch (Exception ignored) { }
		}
		if (web != null)
		{
			web.stopLoading();
			web.destroy();
			web = null;
		}
		super.onDestroy();
	}

	/* --------------------------------- views --------------------------------- */

	private void buildViews()
	{
		root = new FrameLayout(this);

		panel = new LinearLayout(this);
		panel.setOrientation(LinearLayout.VERTICAL);
		panel.setBackground(getDrawable(R.drawable.panel_bg));
		panel.setClipToOutline(true);
		panel.addView(header(), new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));

		web = new WebView(this);
		configureWeb(web);
		panel.addView(web, new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

		panel.addView(resizeHandle(), new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT, dp(16)));

		root.addView(panel, new FrameLayout.LayoutParams(
			FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

		bubble = bubble();
		bubble.setVisibility(View.GONE);
		root.addView(bubble, new FrameLayout.LayoutParams(dp(BUBBLE_DP), dp(BUBBLE_DP)));
	}

	private View header()
	{
		LinearLayout bar = new LinearLayout(this);
		bar.setOrientation(LinearLayout.HORIZONTAL);
		bar.setGravity(Gravity.CENTER_VERTICAL);
		bar.setBackgroundColor(0xFF1D1712);

		TextView title = new TextView(this);
		title.setText("geflip");
		title.setTextColor(0xFFFF981F);
		title.setTextSize(12f);
		title.setPadding(dp(10), 0, dp(4), 0);
		bar.addView(title, new LinearLayout.LayoutParams(0,
			LinearLayout.LayoutParams.MATCH_PARENT, 1f));
		// The whole title strip is the drag grip; a tap on it does nothing (too easy to
		// hit by accident while repositioning).
		title.setContentDescription("geflip overlay — drag to move");
		title.setOnTouchListener(new Drag());

		bar.addView(chip("⟳", "Reload", new Runnable() { public void run() { web.reload(); } }));
		bar.addView(chip("◐", "Change opacity", new Runnable() { public void run() { cycleAlpha(); } }));
		bar.addView(chip("–", "Shrink to a bubble", new Runnable() { public void run() { setExpanded(false); } }));
		bar.addView(chip("✕", "Close the overlay", new Runnable() { public void run() { stopSelf(); } }));
		return bar;
	}

	private TextView chip(String glyph, String label, final Runnable onTap)
	{
		TextView t = new TextView(this);
		t.setText(glyph);
		t.setTextColor(0xFF9A8A6E);
		t.setTextSize(13f);
		t.setGravity(Gravity.CENTER);
		t.setPadding(dp(9), 0, dp(9), 0);
		t.setContentDescription(label);
		t.setLayoutParams(new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
		t.setOnClickListener(new View.OnClickListener()
		{
			public void onClick(View v) { onTap.run(); }
		});
		return t;
	}

	private View bubble()
	{
		TextView b = new TextView(this);
		b.setText("gf");
		b.setTextColor(0xFFFF981F);
		b.setTextSize(15f);
		b.setGravity(Gravity.CENTER);
		b.setBackground(getDrawable(R.drawable.bubble_bg));
		b.setContentDescription("Expand the geflip overlay");
		b.setOnClickListener(new View.OnClickListener()
		{
			public void onClick(View v) { setExpanded(true); }
		});
		b.setOnTouchListener(new Drag());
		return b;
	}

	private View resizeHandle()
	{
		TextView h = new TextView(this);
		h.setText("◢");
		h.setTextColor(0xFF6B5D46);
		h.setTextSize(11f);
		h.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
		h.setPadding(0, 0, dp(6), 0);
		h.setBackgroundColor(0xFF1D1712);
		h.setContentDescription("Drag to resize the overlay");
		h.setOnTouchListener(new Resize());
		return h;
	}

	@android.annotation.SuppressLint("SetJavaScriptEnabled")
	private void configureWeb(WebView v)
	{
		// The page is the whole application — without JS there is nothing to show. It loads
		// one origin the user chose, has no JavascriptInterface bridge, and gets no file or
		// content access, so there is no path from page script back into this app.
		WebSettings s = v.getSettings();
		s.setJavaScriptEnabled(true);
		s.setDomStorageEnabled(true);      // the page keeps its whole config in localStorage
		s.setLoadWithOverviewMode(false);
		s.setUseWideViewPort(false);
		s.setAllowFileAccess(false);
		s.setAllowContentAccess(false);
		s.setMediaPlaybackRequiresUserGesture(true);
		s.setCacheMode(WebSettings.LOAD_DEFAULT);
		v.setBackgroundColor(Color.parseColor("#14100C"));
		v.setVerticalScrollBarEnabled(true);
		v.setWebViewClient(new WebViewClient()
		{
			@Override
			public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req)
			{
				Uri target = req.getUrl();
				Uri home = Uri.parse(Prefs.overlayUrl(prefs.url()));
				if (target.getHost() != null && target.getHost().equals(home.getHost()))
				{
					return false;   // the page's own links (incl. the ⤢ full app) stay in here
				}
				// Anything off-site — the wiki, a docs link — belongs in the real browser,
				// not in a 320dp window with no address bar.
				try
				{
					startActivity(new Intent(Intent.ACTION_VIEW, target)
						.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
				}
				catch (Exception ignored) { }
				return true;
			}
		});
	}

	/* -------------------------------- behaviour ------------------------------- */

	private void setExpanded(boolean on)
	{
		if (expanded == on) return;
		expanded = on;
		panel.setVisibility(on ? View.VISIBLE : View.GONE);
		bubble.setVisibility(on ? View.GONE : View.VISIBLE);
		lp.width = on ? dp(prefs.w(DEF_W_DP)) : dp(BUBBLE_DP);
		lp.height = on ? dp(prefs.h(DEF_H_DP)) : dp(BUBBLE_DP);
		clampPosition();
		safeUpdate();
	}

	private void cycleAlpha()
	{
		float a = prefs.alpha();
		a = a > 0.85f ? 0.75f : a > 0.65f ? 0.5f : 1f;
		prefs.alpha(a);
		root.setAlpha(a);
	}

	/** Keeps the window on screen after a drag, a resize or a rotation. */
	private void clampPosition()
	{
		DisplayMetrics m = getResources().getDisplayMetrics();
		int w = lp.width > 0 ? lp.width : dp(BUBBLE_DP);
		int h = lp.height > 0 ? lp.height : dp(BUBBLE_DP);
		lp.x = Math.max(0, Math.min(lp.x, Math.max(0, m.widthPixels - w)));
		lp.y = Math.max(0, Math.min(lp.y, Math.max(0, m.heightPixels - h)));
	}

	private void safeUpdate()
	{
		if (root != null && root.isAttachedToWindow())
		{
			try { wm.updateViewLayout(root, lp); } catch (Exception ignored) { }
		}
	}

	/**
	 * Drag the window by this view. A press that never moved is handed on to the view's own
	 * OnClickListener via performClick(), so taps stay reachable to TalkBack.
	 */
	private final class Drag implements View.OnTouchListener
	{
		private int originX, originY;
		private float downX, downY;
		private boolean dragged;

		@Override
		public boolean onTouch(View v, MotionEvent e)
		{
			switch (e.getActionMasked())
			{
				case MotionEvent.ACTION_DOWN:
					originX = lp.x;
					originY = lp.y;
					downX = e.getRawX();
					downY = e.getRawY();
					dragged = false;
					return true;
				case MotionEvent.ACTION_MOVE:
					float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
					if (!dragged && Math.hypot(dx, dy) < touchSlop) return true;
					dragged = true;
					lp.x = originX + (int) dx;
					lp.y = originY + (int) dy;
					clampPosition();
					safeUpdate();
					return true;
				case MotionEvent.ACTION_UP:
				case MotionEvent.ACTION_CANCEL:
					if (dragged) prefs.position(lp.x, lp.y);
					else if (e.getActionMasked() == MotionEvent.ACTION_UP) v.performClick();
					return true;
				default:
					return false;
			}
		}
	}

	/** Bottom-right grip: drag to resize the panel, remembered for next time. */
	private final class Resize implements View.OnTouchListener
	{
		private int originW, originH;
		private float downX, downY;

		@Override
		public boolean onTouch(View v, MotionEvent e)
		{
			switch (e.getActionMasked())
			{
				case MotionEvent.ACTION_DOWN:
					originW = lp.width;
					originH = lp.height;
					downX = e.getRawX();
					downY = e.getRawY();
					return true;
				case MotionEvent.ACTION_MOVE:
					DisplayMetrics m = getResources().getDisplayMetrics();
					lp.width = clamp(originW + (int) (e.getRawX() - downX), dp(MIN_W_DP), m.widthPixels);
					lp.height = clamp(originH + (int) (e.getRawY() - downY), dp(MIN_H_DP), m.heightPixels);
					clampPosition();
					safeUpdate();
					return true;
				case MotionEvent.ACTION_UP:
				case MotionEvent.ACTION_CANCEL:
					float d = getResources().getDisplayMetrics().density;
					prefs.size(Math.round(lp.width / d), Math.round(lp.height / d));
					v.performClick();
					return true;
				default:
					return false;
			}
		}
	}

	private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(v, hi)); }

	private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

	/* ------------------------------ notification ------------------------------ */

	private void startInForeground()
	{
		NotificationManager nm = getSystemService(NotificationManager.class);
		NotificationChannel ch = new NotificationChannel(CHANNEL, "geflip overlay",
			NotificationManager.IMPORTANCE_LOW);
		ch.setShowBadge(false);
		nm.createNotificationChannel(ch);

		PendingIntent open = PendingIntent.getActivity(this, 0,
			new Intent(this, SetupActivity.class), PendingIntent.FLAG_IMMUTABLE);
		PendingIntent stop = PendingIntent.getService(this, 1,
			new Intent(this, OverlayService.class).setAction(ACTION_STOP),
			PendingIntent.FLAG_IMMUTABLE);

		Notification n = new Notification.Builder(this, CHANNEL)
			.setSmallIcon(R.drawable.ic_stat_geflip)
			.setContentTitle("geflip overlay")
			.setContentText("Floating over your screen")
			.setContentIntent(open)
			.addAction(new Notification.Action.Builder(
				android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat_geflip),
				"Stop", stop).build())
			.setOngoing(true)
			.build();

		if (Build.VERSION.SDK_INT >= 34)
		{
			startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
		}
		else
		{
			startForeground(NOTE_ID, n);
		}
	}

	/** True when Android will actually let us draw on top. */
	static boolean allowed(android.content.Context c) { return Settings.canDrawOverlays(c); }
}
