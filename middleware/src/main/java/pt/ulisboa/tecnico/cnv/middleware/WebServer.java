package pt.ulisboa.tecnico.cnv.middleware;

import pt.ulisboa.tecnico.cnv.middleware.LoadBalancerHandler;
import pt.ulisboa.tecnico.cnv.middleware.AutoScaler;
import pt.ulisboa.tecnico.cnv.middleware.AWSDashboard;
import java.net.InetSocketAddress;
import com.sun.net.httpserver.HttpServer;
import pt.ulisboa.tecnico.cnv.middleware.policies.ASPolicy;
import pt.ulisboa.tecnico.cnv.middleware.policies.CpuBasedScaling;


public class WebServer {
 
    public static void main(String[] args) throws Exception {

        AWSDashboard awsDashboard = new AWSDashboard();

        // Auto Scaler
        AutoScaler autoScaler = new AutoScaler(awsDashboard, new CpuBasedScaling(25, 75));
        autoScaler.start();
        System.out.println("AutoScaler started...");

        // Load Balancer
        LoadBalancer loadBalancer = new LoadBalancer(awsDashboard);
        loadBalancer.start();
        System.out.println("LoadBalancer started on port 8000...");

        // TODO - create instance monitor
    }
}
