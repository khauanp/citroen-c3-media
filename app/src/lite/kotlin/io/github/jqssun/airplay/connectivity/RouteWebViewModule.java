package io.github.jqssun.airplay.connectivity;

import android.app.Activity;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.IOException;
import java.util.WeakHashMap;

import fi.iki.elonen.NanoHTTPD;

public final class RouteWebViewModule {
    private static final int SERVER_PORT = 8080;
    private static final WeakHashMap<Activity, RouteWebViewModule> INSTANCES =
        new WeakHashMap<>();

    private final Activity activity;
    private View mapLayout;
    private WebView webView;
    private RouteReceiverServer server;

    private RouteWebViewModule(Activity activity) {
        this.activity = activity;
    }

    public static synchronized void start(Activity activity) {
        if (INSTANCES.containsKey(activity)) {
            return;
        }
        RouteWebViewModule module = new RouteWebViewModule(activity);
        INSTANCES.put(activity, module);
        try {
            module.startInternal();
        } catch (RuntimeException | LinkageError failure) {
            android.util.Log.e("C3Route", "Map initialization failed", failure);
            stop(activity);
        }
    }

    public static synchronized void stop(Activity activity) {
        RouteWebViewModule module = INSTANCES.remove(activity);
        if (module != null) {
            module.stopInternal();
        }
    }

    private void startInternal() {
        ViewGroup content = activity.findViewById(android.R.id.content);
        int layoutId = activity.getResources().getIdentifier(
            "waze_webview",
            "layout",
            activity.getPackageName()
        );
        int webViewId = activity.getResources().getIdentifier(
            "wazeWebView",
            "id",
            activity.getPackageName()
        );

        mapLayout = LayoutInflater.from(activity).inflate(layoutId, content, false);
        webView = mapLayout.findViewById(webViewId);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webView.setWebViewClient(new WebViewClient());
        webView.setBackgroundColor(Color.BLACK);
        mapLayout.setVisibility(View.GONE);

        content.addView(mapLayout);
        mapLayout.post(new Runnable() {
            @Override
            public void run() {
                placeOverMap(content);
            }
        });

        server = new RouteReceiverServer(
            SERVER_PORT,
            new RouteReceiverServer.WebViewTargetListener() {
                @Override
                public void onRouteReceived(final String wazeUrl) {
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (webView != null && !activity.isDestroyed()) {
                                placeOverMap(content);
                                mapLayout.setVisibility(View.VISIBLE);
                                try {
                                    webView.loadUrl(wazeUrl);
                                } catch (RuntimeException failure) {
                                    mapLayout.setVisibility(View.GONE);
                                    android.util.Log.e("C3Route", "Route display failed", failure);
                                }
                            }
                        }
                    });
                }
            }
        );

        try {
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        } catch (IOException failure) {
            android.util.Log.e("C3Route", "Port 8080 unavailable", failure);
            stop(activity);
        }
    }

    private void placeOverMap(ViewGroup content) {
        if (mapLayout == null) return;
        int width = content.getWidth();
        int height = content.getHeight();
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            Math.round(width * 778f / 1280f),
            Math.round(height * 672f / 800f)
        );
        params.leftMargin = Math.round(width * 124f / 1280f);
        params.topMargin = Math.round(height * 108f / 800f);
        mapLayout.setLayoutParams(params);
    }

    private void stopInternal() {
        if (server != null) {
            server.stop();
            server = null;
        }
        if (mapLayout != null && mapLayout.getParent() instanceof ViewGroup) {
            ((ViewGroup) mapLayout.getParent()).removeView(mapLayout);
        }
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        mapLayout = null;
    }
}
