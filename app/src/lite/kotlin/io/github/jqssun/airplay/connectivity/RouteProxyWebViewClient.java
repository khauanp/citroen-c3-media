package io.github.jqssun.airplay.connectivity;

import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.util.Collections;
import java.util.Map;

public final class RouteProxyWebViewClient extends WebViewClient {
    private final RouteResourceProxy proxy;
    public RouteProxyWebViewClient(RouteResourceProxy proxy) { this.proxy = proxy; }

    @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        return intercept(request.getUrl().toString(), request.getMethod(), request.getRequestHeaders());
    }

    @Override public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
        return intercept(url, "GET", Collections.<String, String>emptyMap());
    }

    private WebResourceResponse intercept(String url, String method, Map<String, String> headers) {
        if (url.startsWith("data:") || url.startsWith("blob:") || url.equals("about:blank")) return null;
        RouteResourceProxy.Result result = proxy.fetch(url, method, headers);
        String mime = result.contentType.split(";", 2)[0].trim();
        String charset = null;
        for (String part : result.contentType.split(";")) {
            if (part.trim().toLowerCase(java.util.Locale.US).startsWith("charset="))
                charset = part.trim().substring(8).replace("\"", "");
        }
        return new WebResourceResponse(mime, charset, result.status, "Proxy", result.headers, result.body);
    }
}
