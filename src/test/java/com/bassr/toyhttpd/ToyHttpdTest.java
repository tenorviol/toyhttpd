package com.bassr.toyhttpd;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.logging.Logger;

import junit.framework.TestCase;

import org.json.JSONObject;

public class ToyHttpdTest extends TestCase {
    private static final Logger log = Logger.getLogger(ToyHttpdTest.class.getSimpleName());

    public void testResponse() throws Exception {
        ToyHttpd httpd = (new ToyHttpd() {
            public void server(ToyHttpd.Request req, ToyHttpd.Response res) {
                res.write("<h1>ToyHttpd</h1>\n");
                res.end("<h2>Hello there!!!</h2>");
            }
        }).listen(9999);

        try {
            String result = get("http://localhost:9999");
            assertEquals("<h1>ToyHttpd</h1>\n<h2>Hello there!!!</h2>", result);
        } finally {
            httpd.close();
        }
    }

    public void testRequest() throws Exception {
        ToyHttpd httpd = (new ToyHttpd() {
            public void server(ToyHttpd.Request req, ToyHttpd.Response res) throws Exception {
                JSONObject json = new JSONObject();
                json.put("method", req.getMethod());
                res.end(json.toString());
            }
        }).listen(9999);

        try {
            String result = get("http://localhost:9999");
            JSONObject json = new JSONObject(result);
            assertEquals("GET", json.getString("method"));
        } finally {
            httpd.close();
        }
    }

    private String get(String urlString) throws IOException {
        String result;
        URL url = new URL(urlString);
        URLConnection urlConnection = url.openConnection();
        InputStream in = new BufferedInputStream(urlConnection.getInputStream());
        try {
            result = readStream(in);
        } finally {
            in.close();
        }
        return result;
    }

    private String readStream(InputStream is) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            int i = is.read();
            while(i != -1) {
                bo.write(i);
                i = is.read();
            }
            return bo.toString();
        } catch (IOException e) {
            return "";
        }
    }
}
