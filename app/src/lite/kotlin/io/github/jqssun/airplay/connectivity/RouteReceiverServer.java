package io.github.jqssun.airplay.connectivity;

import java.util.List;
import java.util.Map;
import java.net.URI;

import fi.iki.elonen.NanoHTTPD;

public final class RouteReceiverServer extends NanoHTTPD {
    private final WebViewTargetListener listener;

    public interface WebViewTargetListener {
        void onRouteReceived(String wazeUrl);
    }

    public RouteReceiverServer(int port, WebViewTargetListener listener) {
        super(port);
        this.listener = listener;
    }

    @Override
    public Response serve(IHTTPSession session) {
        if (session.getMethod() == Method.GET && "/set-route".equals(session.getUri())) {
            // NanoHTTPD 2.3.1 may retain the previous raw query on keep-alive
            // requests without a query. Its parsed parameters reset per request.
            Map<String, List<String>> parameters = session.getParameters();
            List<String> values = parameters.get("waze_url");
            if (values != null && values.size() == 1 && isWebUrl(values.get(0))) {
                if (listener != null) {
                    listener.onRouteReceived(values.get(0));
                }
                return newFixedLengthResponse(
                    Response.Status.OK,
                    MIME_PLAINTEXT,
                    "ROTA_RECEBIDA"
                );
            }
        }
        return newFixedLengthResponse(
            Response.Status.BAD_REQUEST,
            MIME_PLAINTEXT,
            "PARAMETRO_INVALIDO"
        );
    }

    static boolean isWebUrl(String value) {
        if (value == null || value.length() > 8192) return false;
        try {
            URI uri = new URI(value);
            return ("https".equalsIgnoreCase(uri.getScheme())
                || "http".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (Exception invalid) {
            return false;
        }
    }
}
