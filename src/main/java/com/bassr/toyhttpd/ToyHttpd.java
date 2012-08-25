package com.bassr.toyhttpd;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.logging.Logger;

import org.apache.http.ConnectionClosedException;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.RequestLine;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.DefaultHttpResponseFactory;
import org.apache.http.impl.DefaultHttpServerConnection;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.BasicHttpProcessor;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestHandler;
import org.apache.http.protocol.HttpRequestHandlerRegistry;
import org.apache.http.protocol.HttpService;
import org.apache.http.protocol.ResponseConnControl;
import org.apache.http.protocol.ResponseContent;
import org.apache.http.protocol.ResponseDate;
import org.apache.http.protocol.ResponseServer;

public abstract class ToyHttpd {
    private static final Logger log = Logger.getLogger(ToyHttpd.class.getSimpleName());

    /**
     * Integer timeout in milliseconds for blocking accept or
     * read/receive operations (but not write/send operations).
     */
    public int soTimeout = 1000;

    /**
     * Determines the size of the internal socket buffer used
     * to buffer data while receiving / transmitting HTTP messages.
     */
    public int socketBufferSize = 8 * 1024;

    /**
     * Determines the content of the "Server" header.
     */
    public String originServer = "ToyHttpd";

    /**
     * Override this method and create your new http server.
     */
    public abstract void server(ToyHttpd.Request req, ToyHttpd.Response res)
            throws Exception;

    private ServerThread serverThread;
    private HttpParams httpParams;
    private HttpService httpService;

    /**
     * Start the http server listening on a port.
     * @param port  the server port to listen on
     * @return      self
     * @throws IOException
     */
    public ToyHttpd listen(int port) throws IOException {
        BasicHttpProcessor httpproc = new BasicHttpProcessor();
        httpproc.addInterceptor(new ResponseDate());
        httpproc.addInterceptor(new ResponseServer());
        httpproc.addInterceptor(new ResponseContent());
        httpproc.addInterceptor(new ResponseConnControl());

        this.httpParams = new BasicHttpParams();
        this.httpParams
            .setIntParameter(CoreConnectionPNames.SO_TIMEOUT, ToyHttpd.this.soTimeout)
            .setIntParameter(CoreConnectionPNames.SOCKET_BUFFER_SIZE, ToyHttpd.this.socketBufferSize)
            .setBooleanParameter(CoreConnectionPNames.TCP_NODELAY, true)
            .setParameter(CoreProtocolPNames.ORIGIN_SERVER, ToyHttpd.this.originServer);

        HttpRequestHandlerRegistry registry = new HttpRequestHandlerRegistry();
        registry.register("*", new RequestHandler());

        this.httpService = new HttpService(httpproc,
                new DefaultConnectionReuseStrategy(),
                new DefaultHttpResponseFactory());
        this.httpService.setHandlerResolver(registry);
        this.httpService.setParams(ToyHttpd.this.httpParams);

        this.serverThread = new ServerThread(port);
        this.serverThread.setDaemon(false);
        this.serverThread.start();

        return this;
    }

    /**
     * Shut it down.
     * @throws IOException
     */
    public void close() throws IOException {
        this.serverThread.closeSocket();
    }

    /**
     * Listens for incoming connections,
     * passing each to a request thread.
     */
    private class ServerThread extends Thread {
        private ServerSocket serverSocket;

        public ServerThread(int port) throws IOException {
            this.serverSocket = new ServerSocket(port);
        }

        @Override
        public void run() {
            log.info("listening on port " + this.serverSocket.getLocalPort());
            while (!Thread.interrupted()) {
                try {
                    Socket socket = this.serverSocket.accept();
                    Thread t = new RequestThread(socket);
                    t.setDaemon(true);
                    t.start();
                } catch (SocketException e) {
                    // server socket closed
                    break;
                } catch (Exception e) {
                    e.printStackTrace();
                    break;
                }
            }
            log.info("shut down port " + this.serverSocket.getLocalPort());
        }

        public void closeSocket() throws IOException {
            this.serverSocket.close();
        }
    }

    /**
     * Processes a single http connection.
     */
    private class RequestThread extends Thread {
        private Socket socket;

        public RequestThread(Socket socket)
                throws IOException {
            log.info("incoming connection from " + socket.getInetAddress());
            this.socket = socket;
        }

        @Override
        public void run() {
            DefaultHttpServerConnection connection = new DefaultHttpServerConnection();
            HttpContext context = new BasicHttpContext(null);

            try {
                connection.bind(this.socket, ToyHttpd.this.httpParams);
                // TODO: This loop lets us process multiple http requests on a single connection,
                //       but that is bad for testing bringing up and down different servers.
                //       Can the keep-alive option become configurable?
                //while (!Thread.interrupted() && connection.isOpen()) {
                    ToyHttpd.this.httpService.handleRequest(connection, context);
                //}
            } catch (ConnectionClosedException ex) {
                log.info("client closed connection");
            } catch (IOException ex) {
                log.warning("I/O error: " + ex.getMessage());
            } catch (HttpException ex) {
                log.warning("Unrecoverable HTTP protocol violation: " + ex.getMessage());
            } finally {
                try {
                    connection.shutdown();
                } catch (IOException ignore) {}
            }
        }
    }

    /**
     * Convert an apache HttpRequestHandler
     * into a ToyHttpd handler.
     */
    private class RequestHandler implements HttpRequestHandler {
        public void handle(HttpRequest httpReq, HttpResponse httpRes, HttpContext context) {
            ToyHttpd.Request req = new ToyHttpd.Request(httpReq);
            ToyHttpd.Response res = new ToyHttpd.Response(httpRes);
            try {
                ToyHttpd.this.server(req, res);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static class Request {
        private HttpRequest httpRequest;
        private RequestLine requestLine;

        public Request(HttpRequest httpRequest) {
            this.httpRequest = httpRequest;
            this.requestLine = httpRequest.getRequestLine();
        }

        public String getMethod() {
            return this.requestLine.getMethod();
        }
    }

    public static class Response {
        private HttpResponse httpResponse;
        private String out = "";

        public Response(HttpResponse httpResponse) {
            this.httpResponse = httpResponse;
        }

        public void write(String out) {
            this.out += out;
        }

        public void end(String out) {
            this.write(out);
            this.end();
        }

        public void end() {
            this.httpResponse.setStatusCode(HttpStatus.SC_OK);
            try {
                StringEntity entity = new StringEntity(this.out);
                this.httpResponse.setEntity(entity);
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }
    }
}
