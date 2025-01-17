package pt.ulisboa.tecnico.cnv.middleware;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.sun.net.httpserver.HttpHandler;
import com.amazonaws.services.ec2.model.Instance;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import pt.ulisboa.tecnico.cnv.middleware.Utils.Pair;
import pt.ulisboa.tecnico.cnv.middleware.estimator.DummyEstimator;
import pt.ulisboa.tecnico.cnv.middleware.estimator.OnlineBasedEstimator;
import pt.ulisboa.tecnico.cnv.middleware.estimator.Estimator;
import pt.ulisboa.tecnico.cnv.middleware.policies.LBPolicy;
import pt.ulisboa.tecnico.cnv.middleware.policies.PredictionBasedBalancing;
import pt.ulisboa.tecnico.cnv.middleware.policies.LambdaOnlyBalancing;

/*
 * Load-Balancer routes HTTP requests to workers. Needs to be notified of
 * creation and destruction of workers.
 */
public class LoadBalancer implements HttpHandler, Runnable {
    private AWSDashboard awsDashboard;
    private AWSInterface awsInterface;

    private static final int TIMER = 10000;

    public static final int WORKER_PORT = 8000;

    private static final int MAX_TRIES = 2;

    private LBPolicy policy;

    private Estimator estimator;

    private Thread daemon;

    private Map<Worker, Queue<Job>> status = new HashMap<>();

    public LoadBalancer(AWSDashboard awsDashboard, AWSInterface awsInterface) {
        this.awsDashboard = awsDashboard;
        awsDashboard.registerRegisterWorker(w -> this.registerWorker(w));
        awsDashboard.registerDeregisterWorker(w -> this.deregisterWorker(w));
        this.estimator = new OnlineBasedEstimator();
        this.policy = new PredictionBasedBalancing(estimator, status);
        // this.policy = new LambdaOnlyBalancing();
        this.awsInterface = awsInterface;
    }

    /*
     * Notifies load balancer of creation of new worker.
     */
    public void registerWorker(Worker worker) {
        if (this.status.containsKey(worker)) {
            throw new RuntimeException("Trying to add existing worker.");
        }

        this.status.put(worker, new ConcurrentLinkedQueue<>());
    }

    /*
     * Notifies load balancer of imminent destruction of a worker.
     * Blocks until load balancer ensure that no requests are running on the
     * about-to-be-destructed worker.
     */
    public void deregisterWorker(Worker worker) {
        if (!this.status.containsKey(worker)) {
            throw new RuntimeException("Trying to remove existing worker.");
        }

        // wait for worker queue to be empty
        Queue<Job> q = this.status.remove(worker);
        while (q.size() > 0) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();

            }
        }

        this.status.remove(worker);
    }


    /**
     * Handles HTTP request, by routing to worker/lambda and forward reply based
     * on an auto-balancing policy.
     */
    @Override
    public void handle(HttpExchange exchange) {
        System.out.println("Load balancer just got a new request");
        Optional<Worker> optWorker = this.policy.choose(exchange, this.awsDashboard.getMetrics());

        try {
            boolean good = false;
            int tries = 0;
            byte[] content = exchange.getRequestBody().readAllBytes();
            while (!good && tries < 3) {
                InputStream copy = new ByteArrayInputStream(content);
                exchange.setStreams(copy, null);

                tries += 1;
                if (!optWorker.isPresent()) {
                    System.out.println("No good workers available, invoking lambda");
                    good = invokeLambda(exchange);
                } else {
                    System.out.println("Worker selected, forwarding the request");
                    good = forwardTo(optWorker.get(), exchange);
                }
            }

            if (!good) { 
                exchange.sendResponseHeaders(500, 0);
                exchange.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }


    /**
     * Invokes lambda to handle request.
     */
    private boolean invokeLambda(HttpExchange exchange) throws IOException {

        System.out.println("Invoke lambda called");
        String lambdaName = exchange.getRequestURI().toString().split("\\?")[0].substring(1);
        String json = encodeRequestAsJson(exchange);
        Optional<Pair<String, Integer>> lambdaResponse = this.awsInterface.callLambda(lambdaName, json);

        if (lambdaResponse.isEmpty()) {
            return false;
        }

        String response = lambdaResponse.get().getKey();

        exchange.sendResponseHeaders(lambdaResponse.get().getValue(), response.length());
        OutputStream os = exchange.getResponseBody();
        os.write(response.getBytes());
        exchange.close();
        return true;
    }

    /*
     * Forwards request to running worker.
     */
    private boolean forwardTo(Worker worker, HttpExchange exchange) throws IOException {
        System.out.printf("Forwarding request to worker %s\n", worker.getId());
        System.out.printf("The method is %s\n", exchange.getRequestMethod());

        String uri = exchange.getRequestURI().toString();

        URL url = new URL("http://" + worker.getIP() + ":" + worker.getPort() + uri);
        System.out.printf("Opening the connection to worker - the URL is %s\n", url.toString());
        HttpURLConnection forwardCon = (HttpURLConnection) url.openConnection();

        forwardCon.setRequestMethod(exchange.getRequestMethod());

        // copy request headers from the original request
        for (String headerName : exchange.getRequestHeaders().keySet()) {
            if (headerName != null) {
                forwardCon.setRequestProperty(headerName, exchange.getRequestHeaders().get(headerName).get(0));
            }
        }

        // copy exchange body to forwarded connection
        if (exchange.getRequestBody() != null) {
            // mark that application wants to write data to connection
            forwardCon.setDoOutput(true); 
            forwardCon.getOutputStream().write(exchange.getRequestBody().readAllBytes());
            forwardCon.getOutputStream().close();
        }


        long start = System.nanoTime();
        forwardCon.connect();

        Job job = new Job(worker, estimator.estimate(exchange));
        this.status.get(worker).add(job);

        System.out.println("Waiting for worker to do its thing");

        // get the response from worker
        InputStream responseStream = forwardCon.getInputStream();

        if (forwardCon.getResponseCode() != 200) {
            return false;
        }

        // update information with actual time taken
        this.estimator.updateInfo(exchange,  System.nanoTime() - start);

        // copy response code
        exchange.sendResponseHeaders(forwardCon.getResponseCode(), 0);

        // get the output stream to write the response back to the original client
        OutputStream outputStream = exchange.getResponseBody();

        // relay the response back to the original client
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = responseStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
        }

        while (this.status.get(worker).peek() != job) {}
        this.status.get(worker).poll();

        System.out.println("Got response from worker");


        outputStream.close();
        responseStream.close();
        forwardCon.disconnect();
        return true;
    }

    /**
     * Starts load balancing server.
     */
    public void run() {
        HttpServer server = null;
        try {
            // Load balancer runs in the same port as workers
            server = HttpServer.create(new InetSocketAddress(WORKER_PORT), 0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", this);
        server.start();
    }

    public void start() {
        this.daemon = new Thread(this);
        daemon.start();
    }

    /**
     * Encodes HTTP request 
     */
    private String encodeRequestAsJson(HttpExchange exchange) throws IOException {
        String uri = exchange.getRequestURI().toString();
        byte[] body = exchange.getRequestBody().readAllBytes();
        String bodyEncoded = Base64.getEncoder().encodeToString(body);

        return String.format("{\"uri\": \"%s\", \"body\": \"%s\"}", uri, bodyEncoded);
    }
}
