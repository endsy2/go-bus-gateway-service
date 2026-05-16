package com.busapp.gatewayservice.health;

import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.shared.Application;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
public class HealthCheckController {

    private final RouteLocator routeLocator;
    private final EurekaClient eurekaClient;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public HealthCheckController(RouteLocator routeLocator, 
                                  EurekaClient eurekaClient,
                                  CircuitBreakerRegistry circuitBreakerRegistry) {
        this.routeLocator = routeLocator;
        this.eurekaClient = eurekaClient;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "gateway-service");
        response.put("timestamp", LocalDateTime.now());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/")
    public ResponseEntity<Map<String, String>> root() {
        Map<String, String> response = new HashMap<>();
        response.put("service", "Gateway Service");
        response.put("status", "running");
        return ResponseEntity.ok(response);
    }

    /**
     * Debug endpoint to list all configured routes.
     * Useful for verifying route configuration and troubleshooting.
     */
    @GetMapping("/debug/routes")
    public Mono<ResponseEntity<Map<String, Object>>> listRoutes() {
        return routeLocator.getRoutes()
                .collectList()
                .map(routes -> {
                    List<Map<String, Object>> routeDetails = routes.stream()
                            .map(route -> {
                                Map<String, Object> details = new HashMap<>();
                                details.put("id", route.getId());
                                details.put("uri", route.getUri().toString());
                                details.put("order", route.getOrder());
                                details.put("predicates", route.getPredicate().toString());
                                details.put("filters", route.getFilters().stream()
                                        .map(filter -> filter.getClass().getSimpleName())
                                        .collect(Collectors.toList()));
                                return details;
                            })
                            .collect(Collectors.toList());

                    Map<String, Object> response = new HashMap<>();
                    response.put("timestamp", LocalDateTime.now());
                    response.put("totalRoutes", routes.size());
                    response.put("routes", routeDetails);
                    return ResponseEntity.ok(response);
                });
    }

    /**
     * Comprehensive diagnostic endpoint for a specific service.
     * Shows why you cannot proxy to a service.
     * 
     * Usage: GET /debug/service/{serviceName}
     * Example: GET /debug/service/USER-SERVICE
     */
    @GetMapping("/debug/service/{serviceName}")
    public ResponseEntity<Map<String, Object>> diagnoseService(@PathVariable String serviceName) {
        Map<String, Object> diagnosis = new HashMap<>();
        diagnosis.put("timestamp", LocalDateTime.now());
        diagnosis.put("serviceName", serviceName);

        // 1. Check if service is registered in Eureka
        Application application = eurekaClient.getApplication(serviceName);
        boolean isRegistered = application != null && !application.getInstances().isEmpty();
        
        Map<String, Object> eurekaStatus = new HashMap<>();
        eurekaStatus.put("registered", isRegistered);
        
        if (isRegistered) {
            eurekaStatus.put("instanceCount", application.getInstances().size());
            eurekaStatus.put("instances", application.getInstances().stream()
                    .map(instance -> {
                        Map<String, Object> inst = new HashMap<>();
                        inst.put("instanceId", instance.getInstanceId());
                        inst.put("hostName", instance.getHostName());
                        inst.put("ipAddr", instance.getIPAddr());
                        inst.put("port", instance.getPort());
                        inst.put("status", instance.getStatus().name());
                        inst.put("healthCheckUrl", instance.getHealthCheckUrl());
                        inst.put("homePageUrl", instance.getHomePageUrl());
                        return inst;
                    })
                    .collect(Collectors.toList()));
            eurekaStatus.put("canProxy", true);
            eurekaStatus.put("reason", "Service is registered and has " + application.getInstances().size() + " instance(s)");
        } else {
            eurekaStatus.put("instanceCount", 0);
            eurekaStatus.put("canProxy", false);
            eurekaStatus.put("reason", "Service is NOT registered in Eureka. The service must register with Eureka before the gateway can proxy to it.");
        }
        diagnosis.put("eureka", eurekaStatus);

        // 2. Check circuit breaker status
        String cbName = serviceName.toLowerCase().replace("-", "") + "CB";
        Map<String, Object> circuitBreakerStatus = new HashMap<>();
        
        try {
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(cbName);
            CircuitBreaker.State state = circuitBreaker.getState();
            CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
            
            circuitBreakerStatus.put("name", cbName);
            circuitBreakerStatus.put("state", state.name());
            circuitBreakerStatus.put("failureRate", metrics.getFailureRate());
            circuitBreakerStatus.put("slowCallRate", metrics.getSlowCallRate());
            circuitBreakerStatus.put("numberOfFailedCalls", metrics.getNumberOfFailedCalls());
            circuitBreakerStatus.put("numberOfSuccessfulCalls", metrics.getNumberOfSuccessfulCalls());
            circuitBreakerStatus.put("numberOfSlowCalls", metrics.getNumberOfSlowCalls());
            
            if (state == CircuitBreaker.State.OPEN) {
                circuitBreakerStatus.put("canProxy", false);
                circuitBreakerStatus.put("reason", "Circuit breaker is OPEN. Too many failures detected. Wait for it to transition to HALF_OPEN.");
            } else if (state == CircuitBreaker.State.HALF_OPEN) {
                circuitBreakerStatus.put("canProxy", true);
                circuitBreakerStatus.put("reason", "Circuit breaker is HALF_OPEN. Testing if service has recovered.");
            } else {
                circuitBreakerStatus.put("canProxy", true);
                circuitBreakerStatus.put("reason", "Circuit breaker is CLOSED. Service is healthy.");
            }
        } catch (Exception e) {
            circuitBreakerStatus.put("name", cbName);
            circuitBreakerStatus.put("state", "NOT_FOUND");
            circuitBreakerStatus.put("canProxy", true);
            circuitBreakerStatus.put("reason", "Circuit breaker not initialized yet (will be created on first request)");
        }
        diagnosis.put("circuitBreaker", circuitBreakerStatus);

        // 3. Check matching routes
        List<Map<String, Object>> matchingRoutes = routeLocator.getRoutes()
                .filter(route -> route.getUri().toString().contains(serviceName))
                .map(route -> {
                    Map<String, Object> routeInfo = new HashMap<>();
                    routeInfo.put("id", route.getId());
                    routeInfo.put("uri", route.getUri().toString());
                    routeInfo.put("predicates", route.getPredicate().toString());
                    return routeInfo;
                })
                .collectList()
                .block();

        Map<String, Object> routeStatus = new HashMap<>();
        routeStatus.put("routesFound", matchingRoutes != null ? matchingRoutes.size() : 0);
        routeStatus.put("routes", matchingRoutes);
        
        if (matchingRoutes != null && !matchingRoutes.isEmpty()) {
            routeStatus.put("canProxy", true);
            routeStatus.put("reason", "Found " + matchingRoutes.size() + " route(s) configured for this service");
        } else {
            routeStatus.put("canProxy", false);
            routeStatus.put("reason", "No routes configured for this service in gateway configuration");
        }
        diagnosis.put("routes", routeStatus);

        // 4. Overall diagnosis
        boolean canProxyEureka = (boolean) eurekaStatus.get("canProxy");
        boolean canProxyCB = (boolean) circuitBreakerStatus.get("canProxy");
        boolean canProxyRoute = (boolean) routeStatus.get("canProxy");
        
        Map<String, Object> overall = new HashMap<>();
        overall.put("canProxy", canProxyEureka && canProxyCB && canProxyRoute);
        
        if (!canProxyRoute) {
            overall.put("status", "CANNOT_PROXY");
            overall.put("reason", "No routes configured for this service");
            overall.put("solution", "Add route configuration in application.yml for " + serviceName);
        } else if (!canProxyEureka) {
            overall.put("status", "CANNOT_PROXY");
            overall.put("reason", "Service not registered in Eureka");
            overall.put("solution", "Start the " + serviceName + " and ensure it registers with Eureka at " + eurekaClient.getEurekaClientConfig().getEurekaServerServiceUrls("defaultZone"));
        } else if (!canProxyCB) {
            overall.put("status", "CANNOT_PROXY");
            overall.put("reason", "Circuit breaker is OPEN");
            overall.put("solution", "Wait for circuit breaker to transition to HALF_OPEN, or restart the gateway service");
        } else {
            overall.put("status", "CAN_PROXY");
            overall.put("reason", "All checks passed. Service is reachable.");
            overall.put("solution", "You can now proxy requests to this service");
        }
        
        diagnosis.put("overall", overall);

        HttpStatus status = (boolean) overall.get("canProxy") ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(diagnosis);
    }

    /**
     * Quick check for all services
     */
    @GetMapping("/debug/services")
    public ResponseEntity<Map<String, Object>> listAllServices() {
        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", LocalDateTime.now());
        
        List<Application> applications = eurekaClient.getApplications().getRegisteredApplications();
        
        List<Map<String, Object>> services = applications.stream()
                .map(app -> {
                    Map<String, Object> service = new HashMap<>();
                    service.put("name", app.getName());
                    service.put("instanceCount", app.getInstances().size());
                    service.put("status", app.getInstances().isEmpty() ? "DOWN" : "UP");
                    return service;
                })
                .collect(Collectors.toList());
        
        response.put("totalServices", services.size());
        response.put("services", services);
        response.put("eurekaUrl", eurekaClient.getEurekaClientConfig().getEurekaServerServiceUrls("defaultZone"));
        
        return ResponseEntity.ok(response);
    }
}
